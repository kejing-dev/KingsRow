"""Imports the CC0 recordings for the physical sounds (profile D51: a wooden man on a wooden board, not synthesis).

  python3 tools/import_sounds.py [--src ~/Downloads/cc0-sounds]

Kenney CC0 packs (kenney.nl, Creative Commons Zero): impact-sounds / casino-audio. Each source is decoded with
ffmpeg to 22.05 kHz mono, high-passed at 150 Hz (no dull thump), low-passed at 4 kHz (no harsh click), the leading
silence trimmed, cut to <= 0.30 s with a 40 ms fade and normalised to its peak target. Output: app/src/main/assets/
sounds/<event>[_2|_3].wav. The crowning adds a quiet synthesized "ding" (UI cue) under a real disc-on-disc click.
apps/kings-row/build/sound-sources.md lists every file.
"""
import math
import os
import subprocess
import sys
import wave
import struct

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
SRC = os.path.expanduser(sys.argv[sys.argv.index("--src") + 1] if "--src" in sys.argv else "~/Downloads/cc0-sounds")
RATE = 22050

PLAN = {
    # event: [(pack/file, peak dBFS)]
    "slide": [("kenney_impact-sounds/Audio/impactWood_light_000.ogg", -1), ("kenney_impact-sounds/Audio/impactWood_light_001.ogg", -1), ("kenney_impact-sounds/Audio/impactWood_light_002.ogg", -1)],
    "jump": [("kenney_impact-sounds/Audio/impactWood_light_003.ogg", -1), ("kenney_impact-sounds/Audio/impactWood_light_004.ogg", -1), ("kenney_impact-sounds/Audio/impactWood_medium_001.ogg", -1.5)],
    "tray": [("kenney_casino-audio/Audio/chip-lay-1.ogg", -1), ("kenney_casino-audio/Audio/chip-lay-2.ogg", -1), ("kenney_casino-audio/Audio/chip-lay-3.ogg", -1)],
    "crown": [("kenney_casino-audio/Audio/chips-stack-1.ogg", -1)],
}


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<i2").astype(float) / 32768


def biquad(x, kind, freq, q=0.707):
    w = 2 * math.pi * freq / RATE
    cw, sw = math.cos(w), math.sin(w)
    alpha = sw / (2 * q)
    b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2] if kind == "low" else [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
    return y


def clean(x, peak_db, max_s=0.30):
    x = biquad(biquad(x, "high", 150), "high", 150)
    x = biquad(biquad(x, "low", 4000), "low", 4000)
    thr = np.abs(x).max() * 10 ** (-40 / 20)
    start = max(0, int(np.argmax(np.abs(x) > thr)) - int(0.002 * RATE))
    x = x[start:start + int(max_s * RATE)]
    k = min(len(x), int(0.04 * RATE))
    x[-k:] *= np.linspace(1, 0, k)
    x[: int(0.002 * RATE)] *= np.linspace(0, 1, int(0.002 * RATE))
    return x * (10 ** (peak_db / 20) / np.abs(x).max())


def ding(seconds=0.6):
    t = np.arange(int(RATE * seconds)) / RATE
    y = sum(a * np.sin(2 * math.pi * 1046.5 * k * t) * np.exp(-t * k / 0.3) for k, a in ((1, 1.0), (3.9, 0.1)))
    y[: int(0.004 * RATE)] *= np.linspace(0, 1, int(0.004 * RATE))
    return y / np.abs(y).max()


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(round(max(-1, min(1, v)) * 32767))) for v in x))


for f in ("select.wav",):  # select is silent now (D51: 选子 / 预选默认无声)
    p = os.path.join(OUT, f)
    if os.path.exists(p):
        os.remove(p)
for event, items in PLAN.items():
    for i, (rel, peak) in enumerate(items):
        x = clean(decode(os.path.join(SRC, rel)), peak)
        if event == "crown":
            d = ding() * 10 ** (-10 / 20)
            y = np.zeros(max(len(x), int(0.06 * RATE) + len(d)))
            y[: len(x)] += x
            y[int(0.06 * RATE): int(0.06 * RATE) + len(d)] += d
            x = y * (10 ** (-1 / 20) / np.abs(y).max())
        name = event if i == 0 else f"{event}_{i + 1}"
        write(name, x)
        print(f"{name}.wav <- {rel}: {len(x) / RATE:.2f} s, peak {20 * math.log10(np.abs(x).max()):.1f} dBFS, RMS {20 * math.log10(np.sqrt(np.mean(x ** 2))):.1f} dBFS")
