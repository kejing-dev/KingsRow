"""Builds Kings Row's layered spatial app icon (PICO OS 6) with Pillow.

  python3 tools/build_icon.py [--export512 out.png] [--preview out.png]

Writes into app/src/main/res:
  drawable/icon_3d_layer_0.png  background: the wine-red disc (#7A1F2B, the app's own colour)
  drawable/icon_3d_layer_1.png  middle: the lower ebony piece
  drawable/icon_3d_layer_2.png  top: the upper oak piece with its gold crown (a king = two stacked discs)
  drawable/icon_3d_sdf_N.png    each layer's SDF (inward edge distance x 8, grey in RGB and alpha)
  mipmap-xxxhdpi/ic_spatial_launcher.png   flattened round icon
Layers are 1024 px, kept inside the inscribed circle, no baked shadows (the system adds them).
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SS = 4

WINE = (122, 31, 43)
WINE_LIGHT = (150, 46, 58)
EBONY = (30, 26, 24)
EBONY_TOP = (46, 40, 36)
OAK_SIDE = (184, 146, 94)
OAK_TOP = (227, 199, 150)
OAK_RING = (184, 146, 94)
CREAM = (244, 233, 214)
GOLD = (226, 179, 71)
GOLD_LINE = (122, 82, 16)


def canvas():
    return Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))


def down(img):
    return img.resize((S, S), Image.LANCZOS)


def p(x):
    """Artwork units (108 = full icon, like the vector) -> supersampled px."""
    return x / 108 * S * SS


def ellipse(d, cx, cy, rx, ry, fill, outline=None, width=0):
    d.ellipse((p(cx - rx), p(cy - ry), p(cx + rx), p(cy + ry)), fill=fill, outline=outline, width=round(p(width)) if width else 0)


def disc(d, cy, side, top, ring=None):
    # side band: bottom half-ellipse + rectangle
    ellipse(d, 54, cy + 8, 27, 12, side)
    d.rectangle((p(27), p(cy), p(81), p(cy + 8)), fill=side)
    ellipse(d, 54, cy, 27, 12, top, outline=CREAM, width=1.2)
    if ring:
        ellipse(d, 54, cy, 21, 9, None, outline=ring, width=1.0)


def background():
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, S * SS - 1, S * SS - 1), fill=WINE)
    # a soft lighter wash, upper left (flat colour bands, no gradient banding)
    d.ellipse((p(10), p(6), p(80), p(70)), fill=WINE_LIGHT)
    img = img.filter(ImageFilter.GaussianBlur(p(10)))
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, S * SS - 1, S * SS - 1), fill=255)
    img.putalpha(m)
    return down(img)


def lower():
    img = canvas()
    disc(ImageDraw.Draw(img), 62, EBONY, EBONY_TOP)
    return down(img)


def upper():
    img = canvas()
    d = ImageDraw.Draw(img)
    disc(d, 50, OAK_SIDE, OAK_TOP, ring=OAK_RING)
    crown = [(42, 54), (42, 45), (47.5, 49), (54, 41), (60.5, 49), (66, 45), (66, 54)]
    d.polygon([(p(x), p(y)) for x, y in crown], fill=GOLD, outline=GOLD_LINE, width=round(p(0.8)))
    return down(img)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def flatten(layers):
    out = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        out.alpha_composite(layer)
    return out


def main():
    layers = [background(), lower(), upper()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = flatten(layers)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    if "--export512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--export512") + 1])
    if "--preview" in sys.argv:
        out = sys.argv[sys.argv.index("--preview") + 1]
        w = 300
        tiles = layers + [flat] + [sdf(x) for x in layers]
        sheet = Image.new("RGB", (w * len(tiles), w), (60, 64, 70))
        for i, t in enumerate(tiles):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(out)
    print("ICON layers", len(layers))


main()
