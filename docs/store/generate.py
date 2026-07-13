#!/usr/bin/env python3
"""Generate Play store icon (512x512) and feature graphic (1024x500) matching
the app's adaptive launcher icon."""
import os
from PIL import Image, ImageDraw, ImageFont

NAVY = (11, 18, 32)       # #0B1220
NAVY2 = (14, 26, 44)      # subtle gradient bottom
TILE = (14, 22, 38)       # icon tile fill on feature graphic
TILE_BORDER = (28, 42, 61)
AMBER = (255, 184, 48)    # #FFB830
CYAN = (0, 229, 255)      # #00E5FF
TITLE = (242, 245, 248)
SUBTLE = (154, 167, 180)

OUT = "/home/jd/repos/android_solar_dashboard/docs/store"
os.makedirs(OUT, exist_ok=True)

def font(paths, size):
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

BOLD = ["/usr/share/fonts/truetype/lato/Lato-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
REG = ["/usr/share/fonts/truetype/lato/Lato-Regular.ttf",
       "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]

def draw_icon_art(draw, ox, oy, s):
    """Draw the launcher foreground art (108-unit viewport) at scale s, origin (ox,oy)."""
    def X(v): return ox + v * s
    def Y(v): return oy + v * s
    # Sun disc: center (54,44) r=12
    draw.ellipse([X(54-12), Y(44-12), X(54+12), Y(44+12)], fill=AMBER)
    # Rays (top, bottom, left, right)
    for x0, y0, x1, y1 in [(52,24,56,33),(52,55,56,64),(30,42,39,46),(69,42,78,46)]:
        draw.rectangle([X(x0), Y(y0), X(x1), Y(y1)], fill=AMBER)
    # Panel trapezoid
    draw.polygon([(X(36),Y(70)),(X(72),Y(70)),(X(80),Y(90)),(X(28),Y(90))], fill=CYAN)
    # Panel grid lines (navy), width scales
    lw = max(1, int(1.6 * s))
    for x0, y0, x1, y1 in [(48,70,44,90),(60,70,64,90),(32,80,76,80)]:
        draw.line([X(x0), Y(y0), X(x1), Y(y1)], fill=NAVY, width=lw)

def rounded_rect(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)

# ---------- App icon 512x512 (supersampled 4x) ----------
def make_icon():
    SS = 4
    n = 512 * SS
    img = Image.new("RGBA", (n, n), NAVY + (255,))
    d = ImageDraw.Draw(img)
    draw_icon_art(d, 0, 0, n / 108.0)
    img = img.resize((512, 512), Image.LANCZOS)
    img.save(f"{OUT}/play-icon-512.png")
    print("wrote play-icon-512.png", img.size)

# ---------- Feature graphic 1024x500 (supersampled 2x) ----------
_scratch = ImageDraw.Draw(Image.new("RGB", (10, 10)))

def fit_font(text, paths, max_w, start, min_size=16):
    size = start
    while size > min_size:
        f = font(paths, size)
        if _scratch.textlength(text, font=f) <= max_w:
            return f
        size -= 2
    return font(paths, min_size)

def wrap(text, f, max_w):
    lines, cur = [], ""
    for w in text.split():
        t = (cur + " " + w).strip()
        if _scratch.textlength(t, font=f) <= max_w:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines

def make_feature():
    SS = 2
    W, H = 1024 * SS, 500 * SS
    img = Image.new("RGB", (W, H), NAVY)
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(NAVY[i] + (NAVY2[i] - NAVY[i]) * t) for i in range(3))
        d.line([(0, y), (W, y)], fill=c)
    # icon tile on the left
    tile = 264 * SS
    tx, ty = 84 * SS, (H - tile) // 2
    rounded_rect(d, [tx, ty, tx + tile, ty + tile], radius=60 * SS,
                 fill=TILE, outline=TILE_BORDER, width=2 * SS)
    pad = 0.16 * tile
    draw_icon_art(d, tx + pad, ty + pad, (tile - 2 * pad) / 108.0)
    # text block, right of the tile, fitted to remaining width
    tox = tx + tile + 56 * SS
    right_margin = 56 * SS
    max_w = W - tox - right_margin
    title_f = fit_font("Solar Dashboard", BOLD, max_w, 80 * SS)
    sub_f = font(REG, 30 * SS)
    sub_lines = wrap("Monitor solar chargers, inverters, and batteries over Bluetooth.",
                     sub_f, max_w)
    # measure block height to vertically center
    ta = title_f.getbbox("Solar Dashboard")
    th = ta[3] - ta[1]
    rule_gap, rule_h = 26 * SS, 6 * SS
    sub_gap, sub_lh = 30 * SS, 44 * SS
    block_h = th + rule_gap + rule_h + sub_gap + sub_lh * len(sub_lines)
    y = (H - block_h) // 2
    d.text((tox, y - ta[1]), "Solar Dashboard", font=title_f, fill=TITLE)
    y += th + rule_gap
    d.rectangle([tox, y, tox + 120 * SS, y + rule_h], fill=AMBER)
    y += rule_h + sub_gap
    for ln in sub_lines:
        d.text((tox, y), ln, font=sub_f, fill=SUBTLE)
        y += sub_lh
    img = img.resize((1024, 500), Image.LANCZOS)
    img.save(f"{OUT}/play-feature-1024x500.png")
    print("wrote play-feature-1024x500.png", img.size, "| title fits, sublines:", len(sub_lines))

make_icon()
make_feature()
