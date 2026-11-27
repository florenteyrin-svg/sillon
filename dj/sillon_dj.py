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


def beats(x):
    import librosa
    mono = x.mean(axis=0)
    tempo, frames = librosa.beat.beat_track(y=mono, sr=SR, units="frames", tightness=120)
    times = librosa.frames_to_time(frames, sr=SR)
    tempo = float(np.atleast_1d(tempo)[0])
    if len(times) > 8:
        # The median beat spacing is steadier than the tracker's global estimate
        tempo = 60.0 / float(np.median(np.diff(times)))
    return tempo, times


def tempo_ratio(ta, tb):
    """Factor to apply to B's speed so its beat matches A's, allowing double/half time."""
    best = None
    for mult in (0.5, 1.0, 2.0):
        r = ta / (tb * mult)
        if best is None or abs(r - 1) < abs(best[0] - 1):
            best = (r, mult)
    return best[0] * best[1], abs(best[0] - 1)


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

    ta, a_beats = beats(a)
    tb, _ = beats(b_probe[:, b_first:])
    ratio, gap = tempo_ratio(ta, tb)
    meta = {"tempoA": round(ta, 1), "tempoB": round(tb, 1)}

    if gap > MAX_TEMPO_GAP or len(a_beats) < 40:
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)

    beat = 60.0 / ta
    bars = BARS
    # Transition = the last `bars` bars of A, ending on A's last full beat (phrase-aligned from the end)
    last_beat = a_beats[-1]
    while bars > 4 and bars * 4 * beat > last_beat - a_beats[0]:
        bars //= 2
    length = bars * 4 * beat
    seg_start = last_beat - length
    # snap the start onto a detected beat
    seg_start = float(a_beats[np.argmin(np.abs(a_beats - seg_start))])
    n = int(round(length * SR))
    a_seg = a[:, int(seg_start * SR): int(seg_start * SR) + n]

    # B, sped up/slowed down to A's tempo, starting on its first beat
    b_len_orig = min(b_dur - b_start, (length + 4 * beat) * ratio + 2)
    b = decode(b_path, b_start, b_len_orig, tempo=ratio)
    _, b_beats = beats(b)
    b0 = float(b_beats[0]) if len(b_beats) else 0.0
    b_seg = b[:, int(b0 * SR): int(b0 * SR) + n]
    if b_seg.shape[1] < n or a_seg.shape[1] < n:
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)

    sa = sep.split(a_seg)
    sb = sep.split(b_seg)

    # Gains over the transition, in fractions of its length (16 bars: 1 bar = 1/16)
    q = 1.0 / 16
    gains_a = {
        "drums": ramp(n, [(0, 1), (8 * q, 1), (9 * q, 0), (1, 0)]),
        "bass": ramp(n, [(0, 1), (8 * q - 0.004, 1), (8 * q, 0), (1, 0)]),
        "other": ramp(n, [(0, 1), (6 * q, 1), (10 * q, 0), (1, 0)]),
        "vocals": ramp(n, [(0, 1), (14 * q, 1), (16 * q, 0)]),
    }
    gains_b = {
        "drums": ramp(n, [(0, 0), (4 * q, 0), (8 * q, 1), (1, 1)]),
        "bass": ramp(n, [(0, 0), (8 * q - 0.004, 0), (8 * q, 1), (1, 1)]),
        "other": ramp(n, [(0, 0), (6 * q, 0), (10 * q, 1), (1, 1)]),
        "vocals": ramp(n, [(0, 0), (14 * q, 0), (15 * q, 1), (1, 1)]),
    }
    # Start from the original audio and only add what changes (gain - 1) per stem: where every gain is 1,
    # the transition is exactly the original song, so the joins with A and B are seamless.
    mix = a_seg[:, :n] * 0.0
    a_part = a_seg[:, :n].copy()
    b_part = b_seg[:, :n].copy()
    full_a = ramp(n, [(0, 1), (1, 1)])
    for name in sa:
        a_part += sa[name][:, :n] * (gains_a.get(name, full_a) - 1.0)
        b_part += sb[name][:, :n] * (gains_b.get(name, full_a) - 1.0)
    # B's stems start silent: below its stems' share, keep B fully out (no leak from the separation residue)
    b_presence = np.maximum.reduce([gains_b[k] for k in gains_b])
    a_presence = np.maximum.reduce([gains_a[k] for k in gains_a])
    mix = a_part * np.minimum(1.0, a_presence * 4) + b_part * np.minimum(1.0, b_presence * 4)
    a_out = a_start + seg_start
    # Where B continues after the transition, in B's own (unstretched) timeline
    b_in = b_start + (b0 + length) * ratio
    if abs(ratio - 1) > 1e-3:
        # The transition ends on B at its own speed (short crossfade), so B's file carries on from b_in
        xf = int(0.35 * SR)
        native = decode(b_path, b_in - xf / SR, xf / SR + 0.05)[:, :xf]
        if native.shape[1] == xf:
            w = np.sin(np.linspace(0, math.pi / 2, xf, dtype=np.float32)) ** 2
            mix[:, -xf:] = mix[:, -xf:] * (1 - w) + native * w
    meta.update({"style": "vocal-swap", "bars": bars, "ratio": round(ratio, 4)})
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
