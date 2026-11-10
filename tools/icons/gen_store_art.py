#!/usr/bin/env python3
"""Draws the Google Play icon (512x512) and feature graphics (1024x500, EN + FR) from the
launcher vector (androidApp/src/main/res/drawable/ic_launcher_fg.xml). Needs Pillow.

Usage: tools/icons/gen_store_art.py [--out store/play]
"""
import argparse
import math
import os

from PIL import Image, ImageDraw, ImageFont

BG = (0x2E, 0xC4, 0xB6)
WHITE = (255, 255, 255)
DOT = (0xFF, 0x5A, 0x36)
DARK = (0x14, 0x3A, 0x44)
SS = 4  # supersampling
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def mark(size, offset=(0, 0), view=108.0):
    """The launcher mark on a transparent square of [size] px (viewport [view])."""
    s = size * SS / view
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    w = 11 * s
    pts = [(26, 42), (52, 42)]
    pts += [(52 + 10 * math.cos(math.radians(a)), 52 + 10 * math.sin(math.radians(a))) for a in range(-90, 91, 3)]
    pts += [(52, 62), (36, 62)]
    # Stamp round discs along the path (a polyline joint leaves spikes on the curve).
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0) * 4))
        for k in range(n + 1):
            x = (x0 + (x1 - x0) * k / n) * s
            y = (y0 + (y1 - y0) * k / n) * s
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=WHITE)
    cx, cy, r = 77 * s, 48 * s, 9 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=DOT)
    return img


def icon(path):
    img = Image.new("RGBA", (512 * SS, 512 * SS), BG + (255,))
    img.alpha_composite(mark(512))
    img.resize((512, 512), Image.LANCZOS).convert("RGB").save(path)


def wrap(draw, text, font, room):
    """Greedy word wrap of [text] to [room] px."""
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        if draw.textlength(trial, font=font) <= room or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    lines.append(cur)
    return lines


def feature(path, title, tagline):
    w, h = 1024, 500
    img = Image.new("RGBA", (w * SS, h * SS), BG + (255,))
    # The mark only fills about 61% of the launcher viewport: draw it larger so the zigzag and the
    # dot (viewport x 20..86, y 36..68) span ~480 px, centred in the left half.
    view_w = 66.0
    k = 430.0 / view_w
    side = round(108 * k)
    m = mark(side)
    dx = round(250 - 53.0 * k)
    dy = round(250 - 52.0 * k)
    img.paste(m, (dx * SS, dy * SS), m)
    img = img.resize((w, h), Image.LANCZOS).convert("RGB")
    d = ImageDraw.Draw(img)
    left, right = 530, w - 36
    room = right - left
    big = ImageFont.truetype(FONT, 100)
    while d.textlength(title, font=big) > room:
        big = ImageFont.truetype(FONT, big.size - 2)
    small = ImageFont.truetype(FONT, 38)
    lines = wrap(d, tagline, small, room)
    block = big.size + 24 + len(lines) * 50
    y = (h - block) // 2 - 10
    d.text((left, y), title, font=big, fill=WHITE)
    y += big.size + 24
    for line in lines:
        d.text((left + 4, y), line, font=small, fill=DARK)
        y += 50
    img.save(path)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="store/play")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    icon(os.path.join(a.out, "icon_512.png"))
    feature(os.path.join(a.out, "feature_1024x500_en.png"), "Light Games", "15 tiny offline games. No ads.")
    feature(os.path.join(a.out, "feature_1024x500_fr.png"), "Jeux Légers", "15 mini-jeux hors ligne. Sans pub.")
    # Other store languages: the app is only in English and French, so the title stays "Light Games".
    for code, tagline in (
        ("es", "15 minijuegos sin conexión. Sin anuncios."),
        ("de", "15 Mini-Spiele ohne Internet. Keine Werbung."),
        ("it", "15 mini-giochi offline. Niente pubblicità."),
        ("pt-BR", "15 minijogos offline. Sem anúncios."),
    ):
        feature(os.path.join(a.out, f"feature_1024x500_{code}.png"), "Light Games", tagline)
    print("ok")
