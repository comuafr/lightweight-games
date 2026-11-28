#!/usr/bin/env python3
"""Builds every icon and store graphic from the key art, store/play/feature-final.png. Needs Pillow and numpy.

  store/play/icon_512.png                       Google Play icon (full square: Play rounds the corners)
  store/play/feature_1024x500_<lang>.png        feature graphics of the six listing languages
  store/play/product_icon_unlock_all_512.png    icon of the in-app product
  androidApp/src/main/res/                      launcher icon (adaptive, plus a bitmap for Android 7)
  iosApp/.../AppIcon.appiconset/ios_AppIcon.png  iOS icon (1024, no alpha)

Screenshots are not touched. Usage: tools/icons/gen_store_art.py
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCE = os.path.join(ROOT, "store/play/feature-final.png")
PLAY = os.path.join(ROOT, "store/play")
RES = os.path.join(ROOT, "androidApp/src/main/res")
IOS_ICON = os.path.join(ROOT, "iosApp/LightweightGames/Assets.xcassets/AppIcon.appiconset/ios_AppIcon.png")
FONT = "/usr/share/fonts/truetype/ubuntu/UbuntuSans[wdth,wght].ttf"

# The key art is a rounded tile on a white page: what lies inside this rounded square (source pixels, 1024 wide)
# is the picture; the rest (page, corners, rim) is repainted from the picture's own edge.
INNER_MARGIN, INNER_RADIUS = 62, 170

NAVY = (23, 9, 76)
YELLOW = (255, 212, 38)
WHITE = (255, 255, 255)


def extended(side):
    """The picture centred in a [side] x [side] square, its background carried out to the edges."""
    src = np.asarray(Image.open(SOURCE).convert("RGB"), dtype=np.float32)
    n = src.shape[0]
    pad = (side - n) // 2
    ys, xs = np.mgrid[0:side, 0:side].astype(np.float32)
    ys, xs = ys - pad, xs - pad
    # Nearest point of the inner rounded square, for every pixel of the output.
    lo, hi = INNER_MARGIN + INNER_RADIUS, n - 1 - INNER_MARGIN - INNER_RADIUS
    cx, cy = np.clip(xs, lo, hi), np.clip(ys, lo, hi)
    dx, dy = xs - cx, ys - cy
    dist = np.hypot(dx, dy)
    k = np.minimum(1.0, INNER_RADIUS / np.maximum(dist, 1e-6))
    px = np.clip(np.rint(cx + dx * k), 0, n - 1).astype(int)
    py = np.clip(np.rint(cy + dy * k), 0, n - 1).astype(int)
    out = Image.fromarray(src[py, px].astype(np.uint8))
    # Smooth the carried colours (they would otherwise streak), then lay the untouched picture back on top.
    soft = out.filter(ImageFilter.GaussianBlur(60))
    # Away from the picture the carried colours fade into plain navy.
    fade = np.clip((dist - INNER_RADIUS + 10.0) / 90.0, 0.0, 1.0)
    soft = Image.composite(Image.new("RGB", soft.size, NAVY), soft, Image.fromarray((fade * 255).astype(np.uint8)))
    keep = np.clip((INNER_RADIUS - dist) / 40.0, 0.0, 1.0)  # feathered over the last 40 px
    mask = Image.fromarray((keep * 255).astype(np.uint8))
    return Image.composite(out, soft, mask)


def square(size):
    return extended(1024).resize((size, size), Image.LANCZOS)


def font(size, weight="Bold"):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


def wrap(draw, text, f, room):
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        if draw.textlength(trial, font=f) <= room or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    return lines + [cur]


def feature(path, title, sentences):
    """Art on the left, title and tagline (one line or more per sentence) on the right."""
    w, h = 1024, 500
    art_side = 540  # a little taller than the banner: the margin around the picture is cropped away
    wide = extended(1024).resize((art_side, art_side), Image.LANCZOS)
    # Background: plain navy with a soft glow behind the text.
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    glow = np.clip(1.0 - np.hypot((xs - 770) / 380.0, (ys - 250) / 330.0), 0.0, 1.0)[..., None]
    back = np.array(NAVY, np.float32) + glow * np.array((22, 14, 62), np.float32)
    img = Image.fromarray(back.astype(np.uint8))
    ramp = np.ones((art_side, art_side), dtype=np.float32)
    edge = np.linspace(0.0, 1.0, 110, dtype=np.float32)
    ramp[:, -110:] *= edge[::-1]
    ramp[:, :30] *= np.linspace(0.0, 1.0, 30, dtype=np.float32)
    img.paste(wide, (0, (h - art_side) // 2), Image.fromarray((ramp * 255).astype(np.uint8)))

    d = ImageDraw.Draw(img)
    left, right = 556, w - 40
    room = right - left
    size = 92
    while d.textlength(title, font=font(size, "ExtraBold")) > room:
        size -= 2
    big = font(size, "ExtraBold")
    # Each sentence on one line, with a smaller tagline if need be.
    tag = 36
    while tag > 28 and any(d.textlength(t, font=font(tag, "SemiBold")) > room for t in sentences):
        tag -= 1
    small = font(tag, "SemiBold")
    lines = [line for s in sentences for line in wrap(d, s, small, room)]
    y = (h - (size + 30 + len(lines) * 48)) // 2
    d.text((left, y), title, font=big, fill=YELLOW)
    y += size + 30
    for line in lines:
        d.text((left + 2, y), line, font=small, fill=WHITE)
        y += 48
    img.save(path)


def rounded_mask(size, radius):
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=radius * 4, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def product_icon(path):
    """The picture with an open padlock, so that the purchase does not look like the app itself."""
    s = 4
    img = square(512 * s).convert("RGBA")
    d = ImageDraw.Draw(img)

    def box(*v):
        return [c * s for c in v]

    d.ellipse(box(318, 318, 498, 498), fill=(255, 212, 38, 255), outline=(255, 255, 255, 255), width=8 * s)
    ink = (30, 16, 88, 255)
    # Shackle, open on the right, then the body and its keyhole.
    d.arc(box(362, 344, 428, 410), 180, 360, fill=ink, width=14 * s)
    d.line(box(369, 376, 369, 408), fill=ink, width=14 * s)
    d.rounded_rectangle(box(358, 400, 458, 468), radius=12 * s, fill=ink)
    d.ellipse(box(399, 418, 417, 436), fill=(255, 212, 38, 255))
    d.rectangle(box(404, 430, 412, 452), fill=(255, 212, 38, 255))
    img = img.resize((512, 512), Image.LANCZOS)
    img.putalpha(rounded_mask(512, 96))
    img.save(path)


def launcher():
    # Adaptive icon: a 108 dp canvas of which 72 dp show, often through a circle. The picture is laid over 64 dp of it
    # so that the corner details (the no-wifi badge) stay inside the circle.
    canvas = 1024 * 108 // 64
    fg = extended(canvas).resize((432, 432), Image.LANCZOS)
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    fg.save(os.path.join(RES, "drawable-nodpi/ic_launcher_art.webp"), quality=82, method=6)
    # Android 7 (API 24 and 25) has no adaptive icons: a plain bitmap, rounded square and round.
    os.makedirs(os.path.join(RES, "mipmap-xxxhdpi"), exist_ok=True)
    for name, image, radius in (
        ("ic_launcher", square(192), 40),
        ("ic_launcher_round", extended(1152).resize((192, 192), Image.LANCZOS), 96),
    ):
        icon = image.convert("RGBA")
        icon.putalpha(rounded_mask(192, radius))
        icon.save(os.path.join(RES, f"mipmap-xxxhdpi/{name}.webp"), quality=85, method=6)


def ios_art():
    """The art as an ordinary image of the iOS app (96 pt at 3x), shown on the "unlock all" screen."""
    folder = os.path.join(os.path.dirname(os.path.dirname(IOS_ICON)), "AppArt.imageset")
    os.makedirs(folder, exist_ok=True)
    square(288).save(os.path.join(folder, "app_art.png"), optimize=True)
    with open(os.path.join(folder, "Contents.json"), "w") as f:
        f.write('{\n  "images" : [\n    { "filename" : "app_art.png", "idiom" : "universal" }\n  ],\n'
                '  "info" : { "author" : "xcode", "version" : 1 }\n}\n')


def main():
    square(512).save(os.path.join(PLAY, "icon_512.png"), optimize=True)
    square(1024).save(IOS_ICON, optimize=True)
    ios_art()
    # The app exists in English and French only, so the other store languages keep the title "Light Games".
    for code, title, sentences in (
        ("en", "Light Games", ("15 tiny offline games.", "No ads.")),
        ("fr", "Jeux Légers", ("15 mini-jeux hors ligne.", "Sans pub.")),
        ("es", "Light Games", ("15 minijuegos sin conexión.", "Sin anuncios.")),
        ("de", "Light Games", ("15 Mini-Spiele ohne Internet.", "Keine Werbung.")),
        ("it", "Light Games", ("15 mini-giochi offline.", "Niente pubblicità.")),
        ("pt-BR", "Light Games", ("15 minijogos offline.", "Sem anúncios.")),
    ):
        feature(os.path.join(PLAY, f"feature_1024x500_{code}.png"), title, sentences)
    product_icon(os.path.join(PLAY, "product_icon_unlock_all_512.png"))
    launcher()
    print("ok")


if __name__ == "__main__":
    main()
