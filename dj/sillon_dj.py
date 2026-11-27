"""Sillon DJ: prepares the transition between two songs, like a DJ would.

Only the end of song A and the start of song B are processed (about 30 to 45 seconds each), never whole
songs, and only for the next transition while the current song plays. Steps:

1. Decode A's last ~50 s (up to its real end, trailing silence cut) and B's first ~50 s (leading silence cut).
2. Find each tempo and beat grid. If the tempos are close (within 8 %, double/half time included), B is
   time-stretched to A's tempo without changing its pitch (rubberband).
3. Split both windows into vocals / drums / bass / other with Demucs (GPU if usable, else CPU).
4. Mix over 16 bars, beat-aligned: B's drums come in, the basses swap on bar 8, A's melody leaves, and
   A's voice keeps singing over B's music before B's voice takes over ("vocal swap").
   Tempos too far apart: a smooth 10-second crossfade instead.
5. Write the transition (FLAC) and where to leave A / where to join B, in the original songs' timelines.

HTTP API (internal network only):
  POST /render {"a": path, "b": path, "out": dir, "key": name}  -> 202, runs in the background
  GET  /health
Results: <out>/<key>.flac and <out>/<key>.json ({"status":"done","aOut":s,"bIn":s,...} or "failed").
"""

import json
import logging
import math
import os
import queue
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

SR = 44100
WINDOW = 50.0  # seconds decoded at each side
BARS = 16
MAX_TEMPO_GAP = 0.08

log = logging.getLogger("sillon-dj")

# ---------------------------------------------------------------- audio helpers


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
         "stream=sample_rate:format=duration", "-of", "json", path],
        capture_output=True, text=True, check=True).stdout
    j = json.loads(out)
    return float(j["format"]["duration"]), int(j["streams"][0]["sample_rate"])


def decode(path, start, duration, tempo=None):
    """Decodes [start, start+duration] as float32 stereo at SR, optionally time-stretched (pitch kept)."""
    af = []
    if tempo and abs(tempo - 1) > 1e-3:
        af.append(f"rubberband=tempo={tempo:.6f}")
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{max(0.0, start):.6f}", "-t", f"{duration:.6f}", "-i", path,
           "-vn", "-ac", "2", "-ar", str(SR)]
    if af:
        cmd += ["-af", ",".join(af)]
    cmd += ["-f", "f32le", "pipe:1"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).T.copy()  # (2, n)


def rms_db(x, frame):
    n = x.shape[1] // frame
    if n == 0:
        return np.array([-120.0])
    m = x[:, : n * frame].reshape(2, n, frame)
    r = np.sqrt((m ** 2).mean(axis=(0, 2)) + 1e-12)
    return 20 * np.log10(r)


def trim_points(x, threshold_db=-45.0):
    """First and last sample index above the threshold (cuts silence)."""
    frame = 1024
    db = rms_db(x, frame)
    loud = np.where(db > threshold_db)[0]
    if len(loud) == 0:
        return 0, x.shape[1]
    return int(loud[0] * frame), int(min(x.shape[1], (loud[-1] + 1) * frame))


HOP = 256


class Grid:
    """A steady beat grid: beat k is at phase + k * period (seconds, from the start of the analysed audio),
    downbeat (first beat of a bar) when (k - down) % 4 == 0."""

    def __init__(self, period, phase, down, strength):
        self.period, self.phase, self.down, self.strength = period, phase, down, strength

    @property
    def bpm(self):
        return 60.0 / self.period

    def downbeat_at_or_after(self, t):
        k = math.ceil((t - self.phase) / self.period - 1e-9)
        k += (self.down - k) % 4
        return self.phase + k * self.period


def beat_grid(x):
    """Finds tempo and beat phase to a fraction of a millisecond: the onsets of the whole excerpt vote for
    every candidate period (phase coherence), so small errors average out over dozens of beats."""
    import librosa

    mono = x.mean(axis=0)
    env = librosa.onset.onset_strength(y=mono, sr=SR, hop_length=HOP)
    env = np.maximum(env - np.median(env), 0)
    fps = SR / HOP
    # strong onsets only, with sub-frame timing
    peaks = librosa.util.peak_pick(env, pre_max=3, post_max=3, pre_avg=10, post_avg=10, delta=float(env.std() * 0.3), wait=4)
    if len(peaks) < 16:
        return None
    w = env[peaks]
    t = peaks / fps
    # first guess, then fine search around it (and its double / half)
    guess = float(np.atleast_1d(librosa.feature.tempo(onset_envelope=env, sr=SR, hop_length=HOP))[0])
    while guess < 80:
        guess *= 2
    while guess > 170:
        guess /= 2
    best = None
    for center in (guess, guess * 2 / 3 if guess * 2 / 3 >= 80 else None, guess * 4 / 3 if guess * 4 / 3 <= 170 else None):
        if center is None:
            continue
        bpms = np.arange(center * 0.94, center * 1.06, 0.005)
        periods = 60.0 / bpms
        z = np.exp(2j * np.pi * t[None, :] / periods[:, None]) @ w
        score = np.abs(z) / w.sum()
        i = int(np.argmax(score))
        if best is None or score[i] > best[0] * 1.15:
            best = (float(score[i]), float(periods[i]), complex(z[i]))
    strength, period, z = best
    phase = (np.angle(z) / (2 * np.pi)) * period % period
    # Downbeat: the beat position (of 4) where the harmony changes most and the kick is strongest
    chroma = librosa.feature.chroma_stft(y=mono, sr=SR, hop_length=HOP)
    low = librosa.onset.onset_strength(y=mono, sr=SR, hop_length=HOP, fmax=150, n_mels=32)
    nbeats = int((len(mono) / SR - phase) / period)
    scores = np.zeros(4)
    for k in range(1, nbeats):
        f = int((phase + k * period) * fps)
        f0 = int((phase + (k - 1) * period) * fps)
        if f >= chroma.shape[1] - 2:
            break
        change = float(np.linalg.norm(chroma[:, f:f + 4].mean(1) - chroma[:, f0:f].mean(1))) if f > f0 else 0.0
        scores[k % 4] += change + 0.5 * float(low[f:f + 3].max(initial=0)) / (low.max() + 1e-9)
    down = int(np.argmax(scores))
    return Grid(period, phase, down, strength)


_MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def musical_key(x):
    """(root 0-11, is_minor) from the average chroma (Krumhansl-Schmuckler key profiles)."""
    import librosa

    chroma = librosa.feature.chroma_cqt(y=x.mean(axis=0), sr=SR, hop_length=2048).mean(axis=1)
    best = None
    for root in range(12):
        for minor, prof in ((False, _MAJOR), (True, _MINOR)):
            c = float(np.corrcoef(chroma, np.roll(prof, root))[0, 1])
            if best is None or c > best[0]:
                best = (c, root, minor)
    return best[1], best[2]


def camelot(key):
    root, minor = key
    # Camelot wheel number: neighbours on the wheel mix well (same key, relative, fifth up/down)
    return ((root * 7 + (5 if minor else 8)) % 12 or 12), minor


def keys_compatible(ka, kb):
    (na, ma), (nb, mb) = camelot(ka), camelot(kb)
    if ma == mb:
        return (na - nb) % 12 in (0, 1, 11)
    return na == nb


def key_name(k):
    return _NAMES[k[0]] + ("m" if k[1] else "")


def tempo_ratio(pa, pb):
    """Speed factor for B (rubberband tempo) so its beat period matches A's, allowing double/half time."""
    best = None
    for mult in (0.5, 1.0, 2.0):
        r = (pb * mult) / pa
        if best is None or abs(r - 1) < abs(best - 1):
            best = r
    return best, abs(best - 1)


def ramp(n, points):
    """Gain curve over n samples from (fraction, gain) points, linear between them."""
    xs = np.array([p[0] for p in points]) * n
    ys = np.array([p[1] for p in points])
    return np.interp(np.arange(n), xs, ys).astype(np.float32)


def soft_limit(x):
    """Keeps the mix under 0 dBFS (two songs summed can peak higher than either). The gain change is
    eased in and out over 2 seconds and is 1 at both ends, so the joins with the songs stay seamless."""
    peak = float(np.abs(x).max()) if x.size else 0.0
    if peak <= 0.98:
        return x
    n = x.shape[1]
    edge = min(int(2 * SR), n // 3)
    env = np.ones(n, dtype=np.float32)
    env[edge:n - edge] = 0.0
    env[:edge] = np.linspace(1, 0, edge)
    env[n - edge:] = np.linspace(0, 1, edge)
    g = 0.98 / peak
    return x * (g + (1 - g) * env)


# ---------------------------------------------------------------- stems


class Separator:
    def __init__(self):
        import torch
        from demucs.pretrained import get_model

        self.torch = torch
        self.model = get_model("htdemucs")
        self.model.eval()
        self.device = "cpu"
        if os.environ.get("SILLON_DJ_DEVICE", "auto") != "cpu" and torch.cuda.is_available():
            try:  # old cards (e.g. Quadro M2000) may not be supported by this PyTorch build
                torch.zeros(1, device="cuda").add_(1).cpu()
                self.device = "cuda"
            except Exception as e:  # noqa: BLE001
                log.warning("GPU not usable (%s), using the CPU", e)
        torch.set_num_threads(max(1, os.cpu_count() or 1))
        self.lock = threading.Lock()  # one separation at a time (GPU memory, CPU cores)
        log.info("Demucs ready on %s", self.device)

    def gpu_busy(self):
        """Another program (Jellyfin transcoding...) using most of the GPU memory: let it be, use the CPU."""
        if self.device != "cuda":
            return False
        try:
            free, total = self.torch.cuda.mem_get_info()
            return free < 1.2e9
        except Exception:  # noqa: BLE001
            return False

    def split(self, x):
        """x: (2, n) float32 at 44.1 kHz -> dict of stems, each (2, n)."""
        from demucs.apply import apply_model

        torch = self.torch
        with self.lock:
            device = "cpu" if self.gpu_busy() else self.device
            mix = torch.from_numpy(x)
            ref = mix.mean(0)
            mean, std = ref.mean(), ref.std() + 1e-8
            try:
                with torch.no_grad():
                    out = apply_model(self.model, ((mix - mean) / std)[None], device=device, split=True,
                                      overlap=0.25, progress=False)[0]
            except RuntimeError as e:
                if device != "cuda":
                    raise
                # The card passed the start-up test but cannot run the model: use the CPU from now on
                log.warning("GPU separation failed (%s), switching to the CPU", str(e)[:200])
                self.device = device = "cpu"
                torch.cuda.empty_cache()
                with torch.no_grad():
                    out = apply_model(self.model, ((mix - mean) / std)[None], device="cpu", split=True,
                                      overlap=0.25, progress=False)[0]
            out = out * std + mean
            if device == "cuda":
                torch.cuda.empty_cache()
        return {name: out[i].cpu().numpy().astype(np.float32) for i, name in enumerate(self.model.sources)}


# ---------------------------------------------------------------- transition


def plan_and_render(sep, a_path, b_path):
    t0 = time.time()
    a_dur, _ = probe(a_path)
    b_dur, b_rate = probe(b_path)

    # A: last WINDOW seconds, up to its real end
    a_start = max(0.0, a_dur - WINDOW)
    a = decode(a_path, a_start, a_dur - a_start)
    _, a_end = trim_points(a)
    a = a[:, :a_end]

    # B: first WINDOW seconds, from its real start
    b_probe = decode(b_path, 0, min(WINDOW, b_dur))
    b_first, _ = trim_points(b_probe)
    b_start = b_first / SR

    ga = beat_grid(a)
    gb = beat_grid(b_probe[:, b_first:])
    meta = {}
    if ga is None or gb is None or ga.strength < 0.25 or gb.strength < 0.25:
        meta["reason"] = "no steady beat"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)
    ratio, gap = tempo_ratio(ga.period, gb.period)
    meta.update({"tempoA": round(ga.bpm, 2), "tempoB": round(gb.bpm, 2)})
    if gap > MAX_TEMPO_GAP:
        meta["reason"] = "tempos too far apart"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)

    pa = ga.period
    bars = BARS
    a_len = a.shape[1] / SR
    # Transition = the last `bars` bars of A, starting on a downbeat, ending before A's real end
    while True:
        length = bars * 4 * pa
        last_down = ga.downbeat_at_or_after(a_len - length - 4 * pa)
        if last_down + length > a_len:
            last_down -= 4 * pa
        if last_down >= 0 or bars <= 4:
            break
        bars //= 2
    seg_start = max(0.0, last_down)
    n = int(round(length * SR))
    i0 = int(round(seg_start * SR))
    a_seg = a[:, i0: i0 + n]

    # B starts on its first downbeat, stretched to A's tempo. Decode from 2 beats earlier, then find the
    # downbeat precisely in the stretched audio (the stretcher's own delay is measured, not assumed).
    b_down = b_start + gb.downbeat_at_or_after(0.0)
    lead = 2 * gb.period
    from_t = max(0.0, b_down - lead)
    b = decode(b_path, from_t, (length + 8 * pa) * ratio + lead + 2, tempo=ratio)
    expected = (b_down - from_t) / ratio
    gs = beat_grid(b)
    offset = expected
    if gs is not None:
        # nearest beat of the stretched grid to where the downbeat should be
        k = round((expected - gs.phase) / gs.period)
        cand = gs.phase + k * gs.period
        if abs(cand - expected) < pa / 2:
            offset = cand
    j0 = int(round(offset * SR))
    b_seg = b[:, j0: j0 + n]
    if b_seg.shape[1] < n or a_seg.shape[1] < n:
        meta["reason"] = "excerpt too short"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)

    sa = sep.split(a_seg)
    sb = sep.split(b_seg)

    # Keys: two melodies or voices in clashing keys sound wrong even when the beats match
    ka, kb = musical_key(a_seg), musical_key(b_seg)
    compatible = keys_compatible(ka, kb)
    meta.update({"keyA": key_name(ka), "keyB": key_name(kb), "keysCompatible": compatible})

    # Never two melodies at once. Over the transition (1 bar = 1/16 of it):
    #  bars 2-4  B's drums come in (no pitch: no clash), both kits play in sync
    #  bar 8     swap: B's drums and bass take over, A's melody and rhythm leave
    #  bars 8-12 A's voice sings on B's beat (B's bass waits until bar 12 if the keys clash)
    #  bar 12    A's voice leaves, B's melody comes in, then B's voice
    q = 1.0 / 16
    e = 0.004  # ~0.1 s ramps: cuts land on the downbeat without clicks
    bass_in = 8 if compatible else 12
    gains_a = {
        "drums": ramp(n, [(0, 1), (8 * q, 1), (8 * q + e, 0), (1, 0)]),
        "bass": ramp(n, [(0, 1), (8 * q, 1), (8 * q + e, 0), (1, 0)]),
        "other": ramp(n, [(0, 1), (7 * q, 1), (8 * q, 0), (1, 0)]),
        "vocals": ramp(n, [(0, 1), (12 * q, 1), (13 * q, 0), (1, 0)]),
    }
    gains_b = {
        "drums": ramp(n, [(0, 0), (2 * q, 0), (4 * q, 1), (1, 1)]),
        "bass": ramp(n, [(0, 0), (bass_in * q, 0), (bass_in * q + e, 1), (1, 1)]),
        "other": ramp(n, [(0, 0), (12 * q, 0), (13 * q, 1), (1, 1)]),
        "vocals": ramp(n, [(0, 0), (13 * q, 0), (14 * q, 1), (1, 1)]),
    }
    # Start from the original audio and only add what changes (gain - 1) per stem: where every gain is 1,
    # the transition is exactly the original song, so the joins with A and B are seamless.
    a_part = a_seg[:, :n].copy()
    b_part = b_seg[:, :n].copy()
    full = ramp(n, [(0, 1), (1, 1)])
    for name in sa:
        a_part += sa[name][:, :n] * (gains_a.get(name, full) - 1.0)
        b_part += sb[name][:, :n] * (gains_b.get(name, full) - 1.0)
    b_presence = np.maximum.reduce([gains_b[k] for k in gains_b])
    a_presence = np.maximum.reduce([gains_a[k] for k in gains_a])
    mix = a_part * np.minimum(1.0, a_presence * 4) + b_part * np.minimum(1.0, b_presence * 4)

    a_out = a_start + i0 / SR
    # Where B continues after the transition, in B's own (unstretched) timeline
    b_in = from_t + (j0 / SR + length) * ratio
    if abs(ratio - 1) > 1e-3:
        # The transition ends on B at its own speed (short crossfade), so B's file carries on from b_in
        xf = int(0.35 * SR)
        native = decode(b_path, b_in - xf / SR, xf / SR + 0.05)[:, :xf]
        if native.shape[1] == xf:
            w = np.sin(np.linspace(0, math.pi / 2, xf, dtype=np.float32)) ** 2
            mix[:, -xf:] = mix[:, -xf:] * (1 - w) + native * w
    meta.update({"style": "vocal-swap", "bars": bars, "ratio": round(ratio, 5)})
    return soft_limit(mix), a_out, b_in, b_rate, meta, t0


def crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0):
    """Tempos too different for beat matching: a smooth equal-power 10-second crossfade."""
    _, b_rate = probe(b_path)
    fade = min(10.0, a.shape[1] / SR / 2)
    n = int(fade * SR)
    a_seg = a[:, -n:]
    b = decode(b_path, b_start, fade + 1)[:, :n]
    if b.shape[1] < n:
        b = np.pad(b, ((0, 0), (0, n - b.shape[1])))
    t = np.linspace(0, math.pi / 2, n, dtype=np.float32)
    mix = soft_limit(a_seg * np.cos(t) + b * np.sin(t))
    a_out = a_start + (a.shape[1] - n) / SR
    meta.update({"style": "crossfade"})
    return mix, a_out, b_start + fade, b_rate, meta, t0


def write_flac(mix, path, rate):
    tmp = path + ".tmp.flac"
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "2", "-i", "pipe:0"]
    if rate != SR:
        cmd += ["-af", "aresample=resampler=soxr", "-ar", str(rate)]
    cmd += ["-c:a", "flac", "-sample_fmt", "s32", tmp]
    subprocess.run(cmd, input=np.ascontiguousarray(mix.T).astype(np.float32).tobytes(), check=True)
    os.replace(tmp, path)


# ---------------------------------------------------------------- jobs and HTTP


class Worker:
    def __init__(self):
        self.sep = None
        self.jobs = queue.Queue()
        self.pending = set()
        self.mu = threading.Lock()
        threading.Thread(target=self.loop, daemon=True).start()

    def submit(self, job):
        with self.mu:
            if job["key"] in self.pending:
                return
            self.pending.add(job["key"])
        self.jobs.put(job)

    def loop(self):
        self.sep = Separator()
        while True:
            job = self.jobs.get()
            meta_path = os.path.join(job["out"], job["key"] + ".json")
            try:
                os.makedirs(job["out"], exist_ok=True)
                mix, a_out, b_in, rate, meta, t0 = plan_and_render(self.sep, job["a"], job["b"])
                write_flac(mix, os.path.join(job["out"], job["key"] + ".flac"), rate)
                meta.update({"status": "done", "aOut": round(a_out, 4), "bIn": round(b_in, 4),
                             "length": round(mix.shape[1] / SR, 3), "seconds": round(time.time() - t0, 1)})
                log.info("%s: %s in %.1fs", job["key"], meta["style"], meta["seconds"])
            except Exception as e:  # noqa: BLE001
                log.exception("%s failed", job["key"])
                meta = {"status": "failed", "error": str(e)[:300]}
            tmp = meta_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(meta, f)
            os.replace(tmp, meta_path)
            with self.mu:
                self.pending.discard(job["key"])


WORKER = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            sep = WORKER.sep
            return self.reply(200, {"ok": True, "ready": sep is not None, "device": sep.device if sep else None})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/render":
            return self.reply(404, {"error": "not found"})
        try:
            job = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            for k in ("a", "b", "out", "key"):
                if not isinstance(job.get(k), str) or not job[k]:
                    raise ValueError(f"missing {k}")
            if "/" in job["key"] or ".." in job["key"]:
                raise ValueError("bad key")
        except Exception as e:  # noqa: BLE001
            return self.reply(400, {"error": str(e)})
        WORKER.submit(job)
        self.reply(202, {"status": "preparing"})


def main():
    global WORKER
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    WORKER = Worker()
    port = int(os.environ.get("SILLON_DJ_PORT", "8085"))
    log.info("listening on :%d", port)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
