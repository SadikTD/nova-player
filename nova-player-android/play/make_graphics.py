"""Renders the Play Store icon (512x512) and feature graphic (1024x500).

Drawn from the same shapes as the adaptive launcher icon
(res/drawable/ic_launcher_bg.xml + ic_launcher_fg.xml), so the store and the
home screen always match. Run:  python play/make_graphics.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).parent
SS = 4  # supersampling factor for smooth edges


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def diagonal_gradient(w, h, stops):
    """Linear gradient from top-left to bottom-right through (pos, rgb) stops."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
                if t <= p1:
                    px[x, y] = lerp(c0, c1, (t - p0) / (p1 - p0))
                    break
    return img


BLUE = [(0.0, (0x6F, 0xB8, 0xFF)), (0.5, (0x4F, 0x8F, 0xF5)), (1.0, (0x2F, 0x5F, 0xE6))]


def glyph(size):
    """The Nova mark: soft disc + rounded play triangle, on a transparent layer (108-unit viewport)."""
    s = size * SS / 108
    layer = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([(54 - 25) * s, (54 - 25) * s, (54 + 25) * s, (54 + 25) * s], fill=(255, 255, 255, 0x26))
    tri = [(46 * s, 40.5 * s), (67 * s, 54 * s), (46 * s, 67.5 * s)]
    d.polygon(tri, fill="white")
    d.line(tri + [tri[0]], fill="white", width=round(3 * s), joint="curve")
    for x, y in tri:  # round the corners like strokeLineJoin="round"
        r = 1.5 * s
        d.ellipse([x - r, y - r, x + r, y + r], fill="white")
    return layer.resize((size, size), Image.LANCZOS)


def icon(size=512):
    bg = diagonal_gradient(size, size, BLUE).convert("RGBA")
    # Launcher foreground is drawn in a 108 viewport where the central 72 is the safe zone;
    # the store icon shows the full square, so scale the mark up to fill it the same way.
    mark = glyph(round(size * 108 / 72))
    off = (size - mark.width) // 2
    bg.alpha_composite(mark, (off, off))
    return bg.convert("RGB")


def font(names, px):
    for n in names:
        p = Path("C:/Windows/Fonts") / n
        if p.exists():
            return ImageFont.truetype(str(p), px)
    return ImageFont.load_default()


def feature(w=1024, h=500):
    img = Image.new("RGB", (w, h), (0x0B, 0x10, 0x1C))
    # Accent glows, like the app's Aurora background.
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.ellipse([-160, -260, 620, 460], fill=(0x4F, 0x8F, 0xF5, 120))
    g.ellipse([620, 180, 1240, 760], fill=(0x6D, 0x28, 0xD9, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    img.paste(glow, (0, 0), glow)

    tile = 250
    ic = icon(tile)
    mask = Image.new("L", (tile, tile), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, tile - 1, tile - 1], radius=58, fill=255)
    shadow = Image.new("RGBA", (tile + 80, tile + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([40, 52, tile + 40, tile + 52], radius=58, fill=(0, 0, 0, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(20))
    x0, y0 = 96, (h - tile) // 2
    img.paste(shadow, (x0 - 40, y0 - 40), shadow)
    img.paste(ic, (x0, y0), mask)

    d = ImageDraw.Draw(img)
    bold = font(["segoeuib.ttf", "arialbd.ttf"], 86)
    semi = font(["seguisb.ttf", "segoeui.ttf", "arial.ttf"], 34)
    small = font(["segoeui.ttf", "arial.ttf"], 26)
    tx = x0 + tile + 64
    d.text((tx, 138), "Nova Player", font=bold, fill="white")
    d.text((tx, 250), "Plays everything. Finds subtitles.", font=semi, fill=(0xC9, 0xD8, 0xFF))
    d.text((tx, 294), "Syncs them by itself.", font=semi, fill=(0xC9, 0xD8, 0xFF))
    d.text((tx, 362), "No ads  ·  No accounts  ·  Open source", font=small, fill=(0x8E, 0x9B, 0xB8))
    return img


if __name__ == "__main__":
    icon().save(HERE / "icon-512.png")
    feature().save(HERE / "feature-graphic.png")
    print("wrote icon-512.png and feature-graphic.png")
