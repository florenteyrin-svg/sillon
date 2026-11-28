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
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

SR = 44100
WINDOW = 75.0  # seconds decoded at each side
BARS = 16
MAX_TEMPO_RATIO = float(os.environ.get("SILLON_DJ_MAX_TEMPO_RATIO", "1.08"))  # beyond: crossfade (like AutoMix)  # beyond this (after double/half time), a crossfade instead of a beat-matched mix
MIN_BEAT_CONTRAST = 2.5  # how much the found tempo must stand out (real songs: 3 to 15, beatless: < 2)
JOIN = 0.05  # seconds: both ends of a transition melt into the original songs (sample-exact joins)

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


def beat_phase(mono, period):
    """Where in the beat the hits land: every beat of the excerpt is folded onto one, and the position
    where the low (kick) and overall attacks pile up most is the beat. Off-beat hats or syncopated notes
    spread out over the fold and do not pull it (a weighted average of all onsets would)."""
    import librosa

    hop = 64  # 1.5 ms resolution
    fps = SR / hop
    low = librosa.onset.onset_strength(y=mono, sr=SR, hop_length=hop, fmax=250, n_mels=6)
    full = librosa.onset.onset_strength(y=mono, sr=SR, hop_length=hop)
    env = low / (low.max() + 1e-9) + 0.5 * full / (full.max() + 1e-9)
    bins = 240
    pos = ((np.arange(len(env)) / fps) % period) / period
    fold = np.bincount((pos * bins).astype(int) % bins, weights=env, minlength=bins)
    fold = np.convolve(np.concatenate([fold[-3:], fold, fold[:3]]), np.ones(5) / 5, mode="same")[3:-3]
    return float(np.argmax(fold) / bins * period)


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
            # contrast: how much this tempo stands out from its neighbours (real music has many off-beat
            # onsets, so the raw coherence is low even with a perfectly steady beat)
            best = (float(score[i]), float(periods[i]), complex(z[i]), float(score[i] / (np.median(score) + 1e-9)))
    coherence, period, z, contrast = best

    # The two halves of the excerpt must agree on the tempo (a steady beat, not a lucky match)
    def half_period(sel):
        bp = np.arange(60 / period * 0.99, 60 / period * 1.01, 0.002)
        pr = 60.0 / bp
        zz = np.exp(2j * np.pi * t[sel][None, :] / pr[:, None]) @ w[sel]
        return float(pr[int(np.argmax(np.abs(zz)))])
    mid = t.max() / 2
    p1, p2 = half_period(t < mid), half_period(t >= mid)
    agree = abs(p1 - p2) / period
    strength = contrast if agree < 0.012 else 0.0
    phase = beat_phase(mono, period)
    # Downbeat: the beat position (of 4) where the harmony changes most and the kick is strongest
    chroma = librosa.feature.chroma_stft(y=mono, sr=SR, hop_length=HOP)
    low = librosa.onset.onset_strength(y=mono, sr=SR, hop_length=HOP, fmax=250, n_mels=6)
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


def beat_offset_ms(x, beats):
    """Median distance (ms) between the strongest onset near each expected beat and that beat."""
    import librosa

    env = librosa.onset.onset_strength(y=x.mean(axis=0), sr=SR, hop_length=128)
    fps = SR / 128
    offs = []
    for k in range(len(beats) - 1):
        half = (beats[k + 1] - beats[k]) / 4
        f0, f1 = int((beats[k] - half) * fps), int((beats[k] + half) * fps)
        if f0 < 0 or f1 >= len(env):
            continue
        j = f0 + int(np.argmax(env[f0:f1]))
        offs.append((j / fps - beats[k]) * 1000)
    return round(float(np.median(offs)), 1) if offs else None


def beat_multiple(pa, pb):
    """B's beats grouped (x2) or split (x0.5) so its pulse is closest to A's: 70 BPM rap against 140 BPM
    techno is matched beat for 2 beats, like a DJ would."""
    return min((0.5, 1.0, 2.0), key=lambda m: abs(math.log(pb * m / pa)))


def write_wav(x, path):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "2", "-i", "pipe:0",
                    "-c:a", "pcm_f32le", path], input=np.ascontiguousarray(x.T).astype(np.float32).tobytes(), check=True)


def read_wav(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "2", "-ar", str(SR), "pipe:1"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).T.copy()


def stretch_to_beats(x, src_beats, dst_beats, n_out, workdir, name):
    """Time-stretches x (pitch kept) so the beat at src_beats[k] (seconds in x) lands at dst_beats[k] in the
    output: a varying tempo, beat by beat (rubberband key-frame map). The standard engine (R2) is used:
    the finer R3 engine drifts by up to 100 ms on strong tempo changes (measured)."""
    src = os.path.join(workdir, name + "-in.wav")
    dst = os.path.join(workdir, name + "-out.wav")
    tmap = os.path.join(workdir, name + ".map")
    write_wav(x, src)
    with open(tmap, "w") as f:
        for s_, d_ in zip(src_beats, dst_beats):
            f.write(f"{int(round(s_ * SR))} {int(round(d_ * SR))}\n")
    subprocess.run(["rubberband", "-q", "--timemap", tmap, "-D", f"{n_out / SR:.6f}", src, dst],
                   check=True, capture_output=True)
    y = read_wav(dst)
    if y.shape[1] < n_out:
        y = np.pad(y, ((0, 0), (0, n_out - y.shape[1])))
    return y[:, :n_out]


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


def phrase_profile(x, grid, t_from, t_to, bars=4):
    """Energy (dB) and harmony of each `bars`-bar block from the first downbeat at or after t_from."""
    import librosa

    start = grid.downbeat_at_or_after(t_from)
    length = bars * 4 * grid.period
    out = []
    t = start
    prev_chroma = None
    while t + length <= t_to:
        seg = x[:, int(t * SR): int((t + length) * SR)]
        energy = float(20 * np.log10(np.sqrt((seg ** 2).mean()) + 1e-9))
        chroma = librosa.feature.chroma_stft(y=seg.mean(axis=0), sr=SR, hop_length=4096).mean(axis=1)
        change = 0.0 if prev_chroma is None else float(np.linalg.norm(chroma - prev_chroma))
        out.append((t, energy, change))
        prev_chroma = chroma
        t += length
    return out


def low_high(x):
    """Bass (< 180 Hz) and the rest; zero-phase, and low + high == x exactly."""
    import scipy.signal as sg

    sos = sg.butter(4, 180, btype="low", fs=SR, output="sos")
    low = sg.sosfiltfilt(sos, x, axis=1).astype(np.float32)
    return low, (x - low).astype(np.float32)


def plan_and_render(sep, a_path, b_path, workdir="/tmp"):
    """A transition the way DJs do it (and AutoMix / Spotify Mix): short (8 bars, 4 when the keys clash),
    beat-matched, placed on the song structure:
      - it starts on a phrase boundary where A's outro begins (energy drops, harmony changes);
      - B is lined up so its first strong section lands right when the transition ends;
      - EQ mix: B's treble rises over the first half, the basses swap on the middle downbeat, A's treble
        fades over the second half. No source separation: fast, and every song sounds as it was made.
    Tempos too far apart or no steady beat: a smooth crossfade between A's real end and B's real start."""
    t0 = time.time()
    a_dur, _ = probe(a_path)
    b_dur, _ = probe(b_path)

    a_start = max(0.0, a_dur - WINDOW)
    a = decode(a_path, a_start, a_dur - a_start)
    _, a_end = trim_points(a)
    a = a[:, :a_end]
    a_len = a.shape[1] / SR

    b_probe = decode(b_path, 0, min(WINDOW, b_dur))
    b_first, _ = trim_points(b_probe)
    b_start = b_first / SR
    b_body = b_probe[:, b_first:]

    ga = beat_grid(a)
    gb = beat_grid(b_body)
    meta = {}
    if ga is None or gb is None or ga.strength < MIN_BEAT_CONTRAST or gb.strength < MIN_BEAT_CONTRAST:
        meta["reason"] = "no steady beat"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)
    pa = ga.period
    mult = beat_multiple(pa, gb.period)
    pb = gb.period * mult
    meta.update({"tempoA": round(ga.bpm, 2), "tempoB": round(gb.bpm, 2)})
    if max(pa, pb) / min(pa, pb) > MAX_TEMPO_RATIO:
        meta["reason"] = "tempos too far apart"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)

    # Keys decide the length: two harmonies that clash should overlap as little as possible
    ka = musical_key(a[:, max(0, a.shape[1] - int(30 * SR)):])
    kb = musical_key(b_body[:, : int(30 * SR)])
    compatible = keys_compatible(ka, kb)
    bars = 8 if compatible else 4
    nb = bars * 4
    meta.update({"keyA": key_name(ka), "keyB": key_name(kb), "keysCompatible": compatible})

    # --- Where A leaves: the start of its outro (phrase boundary followed by an energy drop),
    # cutting at most 16 bars of A beyond the transition.
    length_a = nb * pa
    prof = phrase_profile(a, ga, 0.0, a_len)
    best, best_score = None, None
    for i, (t, energy, change) in enumerate(prof):
        if t + length_a > a_len or a_len - t > length_a + 8 * 4 * pa:
            continue
        before = prof[i - 1][1] if i > 0 else energy
        score = (before - energy) + 2.0 * change + 0.02 * t  # energy drop, new section, keep more of A
        if best_score is None or score > best_score:
            best, best_score = t, score
    if best is None:
        best = ga.downbeat_at_or_after(a_len - length_a - 4 * pa)
        while best + length_a > a_len and best > 0:
            best -= 4 * pa
    if best < 0:
        meta["reason"] = "ending too short"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)
    cue_a = best

    # --- Where B enters: lined up so that its first strong section (biggest energy rise in its first 32 bars)
    # starts right when the transition ends; B's intro plays during the transition.
    bprof = phrase_profile(b_body, gb, 0.0, min(b_body.shape[1] / SR, 32 * 4 * gb.period + gb.phase + 1))
    first_down = gb.downbeat_at_or_after(0.0)
    target = first_down + nb * pb  # default: B starts on its first downbeat
    best_rise = 3.0  # dB: a real change, not noise
    for i in range(1, len(bprof)):
        rise = bprof[i][1] - bprof[i - 1][1]
        if rise > best_rise and first_down + nb * pb - 1e-6 <= bprof[i][0] <= first_down + (nb + 64) * pb:
            target, best_rise = bprof[i][0], rise
    cue_b = target - nb * pb  # seconds in b_body, on a downbeat
    b_abs = b_start + cue_b
    if b_abs + nb * pb + 1 > min(WINDOW, b_dur):
        meta["reason"] = "opening too short"
        return crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0)

    # --- Shared beat grid: the tempo glides from A's to B's over the transition (small: tempos are close)
    bpm_curve = np.linspace(60 / pa, 60 / pb, nb + 1)
    out_beats = np.concatenate([[0.0], np.cumsum(60 / bpm_curve[:-1])])
    length = float(out_beats[-1])
    n = int(round(length * SR))

    i0 = int(round(cue_a * SR))
    a_in = a[:, i0: i0 + int(round(nb * pa * SR)) + int(0.2 * SR)]
    b_in_audio = decode(b_path, b_abs, nb * pb + 0.2)
    a_seg = stretch_to_beats(a_in, np.arange(nb + 1) * pa, out_beats, n, workdir, "a")
    b_seg = stretch_to_beats(b_in_audio, np.arange(nb + 1) * pb, out_beats, n, workdir, "b")
    meta["syncA_ms"] = beat_offset_ms(a_seg, out_beats)
    meta["syncB_ms"] = beat_offset_ms(b_seg, out_beats)

    # --- EQ mix (fractions of the transition; the middle is the middle downbeat)
    mid = float(out_beats[nb // 2] / length)
    e = 0.004
    a_low, a_high = low_high(a_seg)
    b_low, b_high = low_high(b_seg)
    g_a_high = ramp(n, [(0, 1), (mid, 1), (1, 0)])
    g_a_low = ramp(n, [(0, 1), (mid, 1), (mid + e, 0), (1, 0)])
    g_b_high = ramp(n, [(0, 0), (mid, 1), (1, 1)])
    g_b_low = ramp(n, [(0, 0), (mid, 0), (mid + e, 1), (1, 1)])
    # equal-power treble crossfade
    mix = a_low * g_a_low + a_high * np.sin(g_a_high * math.pi / 2) + b_low * g_b_low + b_high * np.sin(g_b_high * math.pi / 2)
    mix = soft_limit(mix.astype(np.float32))

    a_out = a_start + i0 / SR
    b_in = b_abs + nb * pb
    xf = int(JOIN * SR)
    orig_a = a[:, i0: i0 + xf]
    orig_b = decode(b_path, b_in - xf / SR, xf / SR + 0.02)[:, :xf]
    w = np.sin(np.linspace(0, math.pi / 2, xf, dtype=np.float32)) ** 2
    if orig_a.shape[1] == xf:
        mix[:, :xf] = orig_a * (1 - w) + mix[:, :xf] * w
    if orig_b.shape[1] == xf:
        mix[:, -xf:] = mix[:, -xf:] * (1 - w) + orig_b * w
    meta.update({"style": "beatmatch", "bars": bars, "bpmFrom": round(60 / pa, 2), "bpmTo": round(60 / pb, 2),
                 "cutA_s": round(a_len - cue_a - length_a, 1), "introB_s": round(cue_b - first_down, 1)})
    return mix, a_out, b_in, SR, meta, t0


def crossfade(a_path, b_path, a_start, a, b_start, b_dur, meta, t0):
    """No beat matching possible: a smooth equal-power 6-second crossfade, A's real end into B's real start."""
    _, b_rate = probe(b_path)
    fade = min(6.0, a.shape[1] / SR / 2)
    n = int(fade * SR)
    a_seg = a[:, -n:]
    b = decode(b_path, b_start, fade + 1)[:, :n]
    if b.shape[1] < n:
        b = np.pad(b, ((0, 0), (0, n - b.shape[1])))
    t = np.linspace(0, math.pi / 2, n, dtype=np.float32)
    mix = soft_limit(a_seg * np.cos(t) + b * np.sin(t))
    a_out = a_start + (a.shape[1] - n) / SR
    meta.update({"style": "crossfade"})
    return mix, a_out, b_start + fade, SR, meta, t0


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
        self.sep = None  # source separation is not used by the transitions any more (EQ mix)
        while True:
            job = self.jobs.get()
            meta_path = os.path.join(job["out"], job["key"] + ".json")
            try:
                os.makedirs(job["out"], exist_ok=True)
                with tempfile.TemporaryDirectory() as wd:
                    mix, a_out, b_in, rate, meta, t0 = plan_and_render(self.sep, job["a"], job["b"], wd)
                write_flac(mix, os.path.join(job["out"], job["key"] + ".flac"), SR)
                meta.update({"status": "done", "aOut": round(a_out, 4), "bIn": round(b_in, 4),
                             "length": round(mix.shape[1] / SR, 3), "seconds": round(time.time() - t0, 1)})
                log.info("%s: %s in %.1fs (%s)", job["key"], meta["style"], meta["seconds"],
                         ", ".join(f"{k}={v}" for k, v in meta.items() if k in (
                             "bpmFrom", "bpmTo", "keyA", "keyB", "bars", "syncA_ms", "syncB_ms", "cutA_s", "introB_s", "reason")))
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
            return self.reply(200, {"ok": True, "ready": True})
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
