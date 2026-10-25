#!/usr/bin/env python3
"""Generate Android icon layers and web UI logos from the source artwork.

    python3 tools/make-icons.py [source.png]

The artwork (assets/icon-source.png) is a finished icon: grayscale art on a
near-black rounded tile with a lit border. The launcher applies its own shape,
so the tile and its border are dropped and only the art is kept: everything
outside KEEP is cleared, except the rope, which is continued up past the top
of the tile so the figure still hangs from the edge of whatever shape the
launcher draws.

Layers are white with alpha = luminance: over the black adaptive-icon
background they look like the source, and the same mask doubles as the
Android 13+ monochrome layer. The status-bar icon is the same art, solid.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "assets" / "icon-source.png"
RES = REPO / "android" / "app" / "src" / "main" / "res"
WEB = REPO / "webui" / "public"

# Measured on the 1254 px source (fractions, so another size scales).
S = 1254
# The art inside the tile: a rounded box (left, top, right, bottom, corner radius).
KEEP = (190 / S, 160 / S, 1064 / S, 1080 / S, 160 / S)
# The rope above KEEP: its columns and a row of plain rope to repeat upwards.
ROPE = (612 / S, 642 / S)
ROPE_ROW = 130 / S
# Centre of the art, and how much of the 108 dp adaptive canvas the source's
# side spans (the art itself then sits inside the 66 dp safe zone).
CENTER = (627 / S, 625 / S)
CANVAS_SOURCE_SIDE = 1480 / S
# Web logo: a tighter square around the art.
WEB_CROP = 940 / S
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
# The tile is near-black (up to ~20). Values at or below this become transparent.
BLACK_POINT = 24
# The status-bar icon is the figure alone, since the circuit lines are noise at
# 24 dp: whatever touches the vertical centre line (rope, figure, ring), in a
# square of this side, with alpha above the threshold made solid.
STATUS_SIDE = 960 / S
STATUS_THRESHOLD = 60


def clean(src: Image.Image) -> Image.Image:
    """The art alone, on black, on a canvas tall enough for the rope to run off the top."""
    w, h = src.size
    gray = src.convert("L")
    l, t, r, b, rad = (KEEP[0] * w, KEEP[1] * h, KEEP[2] * w, KEEP[3] * h, KEEP[4] * w)
    mask = Image.new("L", src.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((l, t, r, b), radius=rad, fill=255)
    art = Image.new("L", src.size, 0)
    art.paste(gray, (0, 0), mask)
    # Room above for the rope: the canvas is cropped around CENTER later.
    pad = max(0, int(CANVAS_SOURCE_SIDE * max(w, h) / 2 - CENTER[1] * h) + 2)
    out = Image.new("L", (w, h + pad), 0)
    out.paste(art, (0, pad))
    x0, x1 = int(ROPE[0] * w), int(ROPE[1] * w)
    row = gray.crop((x0, int(ROPE_ROW * h), x1, int(ROPE_ROW * h) + 1))
    for y in range(0, pad + int(t)):
        out.paste(row, (x0, y))
    return out, pad


def white_alpha(img: Image.Image) -> Image.Image:
    alpha = img.point(lambda v: 0 if v <= BLACK_POINT else round((v - BLACK_POINT) * 255 / (255 - BLACK_POINT)))
    out = Image.new("RGBA", img.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def figure_only(alpha: Image.Image, x: int) -> Image.Image:
    """The parts of [alpha] connected to column [x], kept at their original values."""
    solid = alpha.point(lambda v: 255 if v > 8 else 0)
    for y in range(solid.size[1]):
        if solid.getpixel((x, y)) == 255:
            ImageDraw.floodfill(solid, (x, y), 128)
    keep = solid.point(lambda v: 255 if v == 128 else 0)
    out = Image.new("L", alpha.size, 0)
    out.paste(alpha, (0, 0), keep)
    return out


def square(img: Image.Image, cx: float, cy: float, side: int) -> Image.Image:
    half = side // 2
    return img.crop((int(cx) - half, int(cy) - half, int(cx) - half + side, int(cy) - half + side))


def main() -> None:
    src = Image.open(SOURCE).convert("RGB")
    w, h = src.size
    art, pad = clean(src)
    layer = white_alpha(art)
    cx, cy = CENTER[0] * w, CENTER[1] * h + pad
    canvas = square(layer, cx, cy, int(CANVAS_SOURCE_SIDE * max(w, h)))
    status = square(figure_only(layer.getchannel("A"), int(cx)), cx, cy - 0.07 * h, int(STATUS_SIDE * max(w, h)))

    for name, scale in DENSITIES.items():
        px = round(108 * scale)
        fg = canvas.resize((px, px), Image.LANCZOS)
        out_dir = RES / f"mipmap-{name}"
        out_dir.mkdir(parents=True, exist_ok=True)
        fg.save(out_dir / "ic_launcher_foreground.png", optimize=True)
        fg.save(out_dir / "ic_launcher_monochrome.png", optimize=True)

        # Status bar: 24 dp, solid white where the figure is.
        side = round(24 * scale)
        a = status.resize((side, side), Image.LANCZOS).point(lambda v: 255 if v > STATUS_THRESHOLD else 0)
        icon = Image.new("RGBA", (side, side), (255, 255, 255, 0))
        icon.putalpha(a)
        d = RES / f"drawable-{name}"
        d.mkdir(parents=True, exist_ok=True)
        icon.save(d / "ic_stat_app.png", optimize=True)

    any_dir = RES / "mipmap-anydpi-v26"
    any_dir.mkdir(parents=True, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/icon_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (any_dir / name).write_text(xml, encoding="utf-8")

    # Web UI: logo mark (transparent, for the dark UI) and favicon (the tile as drawn).
    WEB.mkdir(parents=True, exist_ok=True)
    square(layer, cx, cy - 0.02 * h, int(WEB_CROP * max(w, h))).resize((256, 256), Image.LANCZOS).save(WEB / "logo-mark.png", optimize=True)
    src.resize((192, 192), Image.LANCZOS).save(WEB / "favicon.png", optimize=True)
    print("icons written")


if __name__ == "__main__":
    main()
