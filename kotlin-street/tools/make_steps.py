#!/usr/bin/env python3
"""Footsteps made from scratch (the generated ones came out near silent): a heel strike (a short low thump and a bright
click of grit), then the toe rolling down a few tens of ms later; wet steps add a splash of filtered noise with droplets.
Four of each, all different (seeded). Writes WAVs for make_audio.py:
  python3 make_steps.py OUT_DIR"""
import sys, os, wave
import numpy as np
from scipy.signal import butter, sosfilt

SR = 48000

def band(x, lo, hi):
    return sosfilt(butter(2, [lo, hi], btype='band', fs=SR, output='sos'), x)

def env(n, attack, decay):
    t = np.arange(n) / SR
    return np.minimum(1, t / max(attack, 1e-4)) * np.exp(-t / decay)

def hit(rng, n, at, gain, lo, hi, decay, thump):
    out = np.zeros(n)
    k = int(SR * 0.25)
    noise = band(rng.standard_normal(k), lo, hi) * env(k, 0.0015, decay)
    t = np.arange(k) / SR
    body = np.sin(2 * np.pi * (thump + 40 * np.exp(-t / 0.01)) * t) * env(k, 0.002, 0.03)
    s = (noise / (np.abs(noise).max() + 1e-9) * 0.8 + body * 0.6) * gain
    i = int(at * SR); out[i:i + k] += s[:n - i]
    return out

def step(seed, wet):
    rng = np.random.default_rng(seed)
    n = int(SR * (0.45 if wet else 0.3))
    x = hit(rng, n, 0.0, 1.0, 900 + rng.uniform(-200, 300), 5200, 0.018 + rng.uniform(0, 0.008), 85 + rng.uniform(-15, 20))
    x += hit(rng, n, 0.05 + rng.uniform(0, 0.03), 0.45, 1500, 7000, 0.012, 120)
    if wet:
        k = n
        splash = band(rng.standard_normal(k), 1800, 9000) * env(k, 0.004, 0.07 + rng.uniform(0, 0.03))
        drops = np.zeros(k)
        for _ in range(rng.integers(4, 9)):
            i = int(rng.uniform(0.01, 0.25) * SR); m = int(0.012 * SR)
            f = rng.uniform(1500, 4000); t = np.arange(m) / SR
            drops[i:i + m] += np.sin(2 * np.pi * f * (1 + 2 * t) * t) * np.exp(-t / 0.004) * rng.uniform(.2, .5)
        x = x * 0.7 + splash / (np.abs(splash).max() + 1e-9) * 0.55 + drops
    x = x / (np.abs(x).max() + 1e-9) * 0.7
    return (x * 32767).astype(np.int16)

def save(path, x):
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(x.tobytes())

if __name__ == '__main__':
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    for i in range(4):
        save(os.path.join(out, f'step_{i + 1}.wav'), step(100 + i, False))
        save(os.path.join(out, f'step_wet_{i + 1}.wav'), step(200 + i, True))
