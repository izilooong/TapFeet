#!/usr/bin/env python3
"""Synthesize 4 distinct keyboard click sounds for TapFeet IME sound schemes.

Each scheme gets its own timbre (not just a re-pitch of one sample), so that
switching the scheme is clearly audible even on devices whose ROM ships no
system keypress audio (e.g. BlackBerry Q25).

Output: 16-bit PCM mono WAV files into app/src/main/res/raw/
  keypress_classic.wav  - balanced mechanical-ish click
  keypress_crisp.wav    - bright bell-like ping
  keypress_muffled.wav  - low, warm, muffled thud
  keypress_soft.wav     - gentle, quiet soft tap
  keypress_piano.wav    - struck piano string (bright, fast decay)
  keypress_telegraph.wav- dry telegraph key click (short, square-ish)
  keypress_woodfish.wav - hollow wooden "tok" (mid resonance, woody knock)
  keypress_abacus.wav  - abacus bead clatter (crisp, granular)
"""
import math
import os
import wave

import numpy as np

SR = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def one_pole_lp(x: np.ndarray, cutoff: float) -> np.ndarray:
    """Simple one-pole low-pass filter."""
    rc = 1.0 / (2.0 * math.pi * cutoff)
    a = 1.0 / (1.0 + rc * SR)
    y = np.empty_like(x, dtype=float)
    prev = 0.0
    for i in range(x.shape[0]):
        prev = prev + a * (x[i] - prev)
        y[i] = prev
    return y


def one_pole_hp(x: np.ndarray, cutoff: float) -> np.ndarray:
    """Simple one-pole high-pass filter."""
    rc = 1.0 / (2.0 * math.pi * cutoff)
    a = rc / (rc + 1.0 / SR)
    y = np.empty_like(x, dtype=float)
    prev_x = 0.0
    prev_y = 0.0
    for i in range(x.shape[0]):
        prev_y = a * (prev_y + x[i] - prev_x)
        prev_x = x[i]
        y[i] = prev_y
    return y


def tone(freq: float, dur: float, amp: float, decay: float, attack: float = 0.001):
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.exp(-t / decay)
    a = int(attack * SR)
    if a > 0:
        env[:a] = t[:a] / attack
    return amp * env * np.sin(2.0 * math.pi * freq * t)


def noise(dur: float, amp: float, decay: float, attack: float = 0.0005, filt=None, cutoff=2000.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    rng = np.random.RandomState(7)
    sig = rng.randn(n)
    if filt == "lp":
        sig = one_pole_lp(sig, cutoff)
    elif filt == "hp":
        sig = one_pole_hp(sig, cutoff)
    env = np.exp(-t / decay)
    a = int(attack * SR)
    if a > 0:
        env[:a] = t[:a] / attack
    return amp * env * sig


def mix(*comps: np.ndarray) -> np.ndarray:
    n = max(c.shape[0] for c in comps)
    out = np.zeros(n)
    for c in comps:
        out[: c.shape[0]] += c
    return out


def normalize(sig: np.ndarray, peak: float) -> np.ndarray:
    m = np.max(np.abs(sig))
    if m < 1e-9:
        return sig
    return sig * (peak / m)


def write_wav(name: str, sig: np.ndarray):
    sig = np.clip(sig, -1.0, 1.0)
    pcm = (sig * 32767.0).astype("<i2")
    path = os.path.join(OUT_DIR, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"wrote {name}: {len(pcm)} samples, {len(pcm)/SR*1000:.0f} ms, peak {np.max(np.abs(sig)):.3f}")


# ---- Classic: balanced mechanical-ish click ----------------------------------
def classic():
    return normalize(mix(
        tone(200, 0.11, 0.55, 0.05),
        tone(330, 0.10, 0.30, 0.04),
        noise(0.004, 0.25, 0.003, attack=0.0003, filt="hp", cutoff=1500),
    ), 0.90)


# ---- Crisp: bright bell-like ping -------------------------------------------
def crisp():
    return normalize(mix(
        tone(2600, 0.14, 0.45, 0.10),
        tone(3900, 0.12, 0.22, 0.08),   # inharmonic partial (~1.5x)
        tone(5200, 0.10, 0.10, 0.06),
        noise(0.003, 0.12, 0.002, attack=0.0002, filt="hp", cutoff=4000),
    ), 0.85)


# ---- Muffled: low, warm, muffled thud ---------------------------------------
def muffled():
    return normalize(mix(
        tone(120, 0.12, 0.50, 0.07),
        tone(180, 0.11, 0.30, 0.06),
        noise(0.02, 0.25, 0.02, attack=0.004, filt="lp", cutoff=800),
    ), 0.90)


# ---- Soft: gentle, quiet soft tap -------------------------------------------
def soft():
    return normalize(mix(
        tone(440, 0.08, 0.32, 0.04),
        tone(660, 0.07, 0.12, 0.03),
        noise(0.002, 0.06, 0.0015, attack=0.0002, filt="hp", cutoff=3000),
    ), 0.70)


# ---- Piano: struck string, bright with fast decay ---------------------------
def piano():
    return normalize(mix(
        tone(523.25, 0.30, 0.50, 0.12),     # C5 fundamental
        tone(659.25, 0.28, 0.18, 0.10),     # E5 (a fifth -> richer chord feel)
        tone(783.99, 0.26, 0.10, 0.09),     # G5
        tone(1046.5, 0.22, 0.06, 0.07),     # C6 harmonic sparkle
        noise(0.003, 0.08, 0.002, attack=0.0003, filt="hp", cutoff=3000),  # hammer click
    ), 0.85)


# ---- Telegraph: dry key click, short & square-ish ---------------------------
def telegraph():
    # ~720 Hz with odd harmonics approximates a square wave, plus a key click.
    return normalize(mix(
        tone(720, 0.06, 0.50, 0.03),
        tone(2160, 0.05, 0.18, 0.025),  # 3rd harmonic -> square-ish
        tone(3600, 0.04, 0.10, 0.02),   # 5th harmonic
        noise(0.002, 0.10, 0.0015, attack=0.0002, filt="hp", cutoff=5000),  # key click
    ), 0.85)


# ---- Woodfish: hollow wooden "tok", mid resonance + woody knock -------------
def woodfish():
    return normalize(mix(
        tone(1000, 0.05, 0.45, 0.022),    # hollow body resonance
        tone(1600, 0.04, 0.16, 0.015),    # wood ring
        tone(220, 0.045, 0.28, 0.03),     # low "thock" body
        noise(0.005, 0.28, 0.003, attack=0.0003, filt="hp", cutoff=1800),  # knock transient
    ), 0.90)


# ---- Abacus: crisp bead clatter, granular wooden/plastic ticks ---------------
def abacus():
    # 算盘: 多颗珠连续碰撞的清脆"噼啪" —— 高频木/塑料撞击 + 轻微体共振
    def hit(delay: float) -> np.ndarray:
        s = mix(
            noise(0.0035, 0.34, 0.003, attack=0.0002, filt="hp", cutoff=2600),  # 主撞击脆响
            tone(1500, 0.025, 0.18, 0.012),   # 珠体共振
            noise(0.002, 0.12, 0.0015, attack=0.0002, filt="hp", cutoff=5000),  # 高频珠尖
        )
        if delay > 0:
            s = np.concatenate([np.zeros(int(SR * delay)), s])
        return s
    return normalize(mix(
        hit(0.0),
        hit(0.012),
        hit(0.023),
    ), 0.88)


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    write_wav("keypress_classic.wav", classic())
    write_wav("keypress_crisp.wav", crisp())
    write_wav("keypress_muffled.wav", muffled())
    write_wav("keypress_soft.wav", soft())
    write_wav("keypress_piano.wav", piano())
    write_wav("keypress_telegraph.wav", telegraph())
    write_wav("keypress_woodfish.wav", woodfish())
    write_wav("keypress_abacus.wav", abacus())
    print("done")
