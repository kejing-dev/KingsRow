"""Offline 30 s loudness render of a game at the app's DEFAULT volume (B20 evidence file).

  python3 tools/loudness.py <out.wav>

kj-mbp-m5 has no audio loopback, so the emulator's output can't be recorded. This mixes the app's own wav files
(app/src/main/assets/sounds) at SoundPlayer's default gain (volume step 2 = 1.0, rate 1.0) on the game's own
timeline (GameFlow / Timeline): your select, slide 220 ms or jump 290 ms + tray landing at 145 + 240 ms, the AI's
minimum 400 ms think, its move, a crowning every few turns, a must-jump bump now and then; win at the end.
Prints peak and RMS (dBFS) of the whole 30 s; the factory gate wants RMS >= -20, peak <= -1.
"""
import math
import os
import random
import struct
import sys
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 22050


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def main(out):
    rnd = random.Random(3)
    total = 30.0
    mix = np.zeros(int(RATE * total))

    def put(name, at):
        x = load(name)
        i = int(at * RATE)
        if i >= len(mix):
            return
        n = min(len(x), len(mix) - i)
        mix[i:i + n] += x[:n]

    def variant(base):
        v = rnd.choice([1, 2, 3])
        return base if v == 1 else f"{base}_{v}"

    t = 0.4
    turn = 0
    while t < total - 2.5:
        # human: select, maybe a must-jump bump first, then slide or jump
        if turn % 5 == 3:
            put("bump", t); t += 0.5
        put("select", t); t += 0.45
        jumps = 1 + (turn % 4 == 2) if turn % 2 == 1 else 0
        if jumps:
            for k in range(jumps):
                put(variant("jump"), t); put(variant("tray"), t + 0.145 + 0.24); t += 0.29 + (0.17 if k < jumps - 1 else 0)
            t += 0.15
        else:
            put(variant("slide"), t); t += 0.22
        if turn % 6 == 5:
            put("crown", t + 0.4); t += 0.8
        # AI: think >= 400 ms, then its move
        t += 0.45 + rnd.random() * 0.25
        if turn % 3 == 1:
            put(variant("jump"), t); put(variant("tray"), t + 0.385); t += 0.45
        else:
            put(variant("slide"), t); t += 0.25
        t += 0.35
        turn += 1
    put("win", total - 1.6)
    peak = np.abs(mix).max()
    rms = math.sqrt(np.mean(mix ** 2))
    clipped = int((np.abs(mix) > 1.0).sum())
    pcm = np.clip(mix, -1, 1)
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(round(v * 32767))) for v in pcm))
    print(f"LOUDNESS {out}: {total:.0f} s, peak {20 * math.log10(peak):.1f} dBFS, RMS {20 * math.log10(rms):.1f} dBFS, clipped samples {clipped}, turns {turn}")


main(sys.argv[1])
