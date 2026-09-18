#!/usr/bin/env python3
"""
TEMPLATE — generate tiny 8-bit PCM WAV sound effects by synthesis.

Output goes to app/src/main/res/raw/ as PCM unsigned 8-bit, mono, 11025 Hz.
Edit the `SOUNDS` list at the bottom for each game. Keep total size < ~20 KB.

Usage:
    python3 tools/gen_sounds.py
"""
import struct, os, random

SR = 11025
OUT = "app/src/main/res/raw"

def write_wav_8bit(name, samples):
    data = bytearray()
    for s in samples:
        v = int((max(-1.0, min(1.0, s)) * 0.5 + 0.5) * 255)
        data.append(v & 0xFF)
    n = len(data)
    hdr = b'RIFF' + struct.pack('<I', 36 + n) + b'WAVE'
    hdr += b'fmt ' + struct.pack('<IHHIIHH', 16, 1, 1, SR, SR, 1, 8)
    hdr += b'data' + struct.pack('<I', n)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), 'wb') as f:
        f.write(hdr + bytes(data))
    return 44 + n

def square(freq, dur, duty=0.5, vol=0.6, decay=True):
    out = []; N = int(SR * dur)
    for i in range(N):
        phase = ((i / SR) * freq) % 1.0
        s = vol if phase < duty else -vol
        if decay: s *= (1 - i / N)
        out.append(s)
    return out

def sweep(f0, f1, dur, vol=0.6):
    out = []; N = int(SR * dur)
    for i in range(N):
        t = i / N
        f = f0 + (f1 - f0) * t
        phase = ((i / SR) * f) % 1.0
        s = (vol if phase < 0.5 else -vol) * (1 - t)
        out.append(s)
    return out

def noise(dur, vol=0.5, decay=True):
    out = []; N = int(SR * dur)
    for i in range(N):
        s = (random.random() * 2 - 1) * vol
        if decay: s *= (1 - i / N)
        out.append(s)
    return out

# --- Snake sound set. Two effects only: eat (blip) and game over. ---
SOUNDS = {
    "s_eat.wav":  square(660, 0.045, 0.5, 0.5, False) + square(988, 0.07, 0.5, 0.5),
    "s_over.wav": sweep(500, 110, 0.38) + noise(0.12, 0.28),
}

if __name__ == "__main__":
    total = 0
    for name, samples in SOUNDS.items():
        sz = write_wav_8bit(name, samples)
        print(f"{name:16s} {sz:6d} bytes")
        total += sz
    print(f"{'TOTAL':16s} {total:6d} bytes ({total/1024:.1f} KB)")
    if total > 20480:
        print("WARNING: audio exceeds ~20 KB budget; trim durations/sample count.")
