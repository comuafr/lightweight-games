#!/usr/bin/env python3
"""Generates the app icon PNGs procedurally (no image assets in the repo, no Pillow needed).

Usage:
  tools/icons/gen_icon.py [--out DIR] [--sizes 1024,512,...]

Draws a rounded dark tile with a stylised snake (rounded segments) and a food dot,
matching the in-app palette. Writes ios_AppIcon.png (1024, no alpha, as Apple requires)
plus optional extra sizes. Pure standard library: zlib + struct.
"""
import argparse
import os
import struct
import zlib

BG = (0x1E, 0x2A, 0x38)
ACCENT = (0x4C, 0xD9, 0x8A)
BODY = (0x3A, 0xB8, 0x72)
FOOD = (0xFF, 0x6B, 0x57)


def png_bytes(width, height, rows):
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB, no alpha
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def render(size):
    s = size
    img = [[BG] * s for _ in range(s)]

    def fill_rounded(x0, y0, x1, y1, r, color):
        for y in range(max(0, int(y0)), min(s, int(y1) + 1)):
            for x in range(max(0, int(x0)), min(s, int(x1) + 1)):
                dx = max(x0 + r - x, 0, x - (x1 - r))
                dy = max(y0 + r - y, 0, y - (y1 - r))
                if dx * dx + dy * dy <= r * r:
                    img[y][x] = color

    def fill_circle(cx, cy, r, color):
        for y in range(max(0, int(cy - r)), min(s, int(cy + r) + 1)):
            for x in range(max(0, int(cx - r)), min(s, int(cx + r) + 1)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    img[y][x] = color

    # Snake path on a 7x7 logical grid, cell size derived from icon size.
    cell = s / 7.0
    pad = cell * 0.12
    path = [(1, 5), (2, 5), (3, 5), (3, 4), (3, 3), (2, 3), (1, 3), (1, 2), (1, 1), (2, 1), (3, 1), (4, 1), (5, 1)]
    for i, (gx, gy) in enumerate(path):
        color = ACCENT if i == len(path) - 1 else BODY
        x0, y0 = gx * cell + pad, gy * cell + pad
        fill_rounded(x0, y0, x0 + cell - 2 * pad, y0 + cell - 2 * pad, cell * 0.25, color)
    fill_circle(5.5 * cell, 4.5 * cell, cell * 0.32, FOOD)
    return [[c for px in row for c in px] for row in img]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "out"))
    ap.add_argument("--sizes", default="1024")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for size in (int(v) for v in args.sizes.split(",")):
        name = "ios_AppIcon.png" if size == 1024 else f"icon_{size}.png"
        with open(os.path.join(args.out, name), "wb") as fh:
            fh.write(png_bytes(size, size, render(size)))
        print("wrote", os.path.join(args.out, name))


if __name__ == "__main__":
    main()
