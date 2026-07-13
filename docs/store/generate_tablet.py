#!/usr/bin/env python3
"""Build 7-inch and 10-inch tablet store graphics that present the real app
screens at tablet dimensions (<=2:1, 24-bit PNG)."""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE = "/home/jd/repos/android_solar_dashboard/docs/store"
SHOTS = f"{BASE}/screenshots"
NAVY = (11, 18, 32)
NAVY2 = (14, 26, 44)
TILE = (14, 22, 38)
TILE_BORDER = (30, 44, 64)
AMBER = (255, 184, 48)
CYAN = (0, 229, 255)
TITLE = (242, 245, 248)

def font(paths, size):
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

BOLD = ["/usr/share/fonts/truetype/lato/Lato-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]

def draw_icon_art(draw, ox, oy, s):
    def X(v): return ox + v * s
    def Y(v): return oy + v * s
    draw.ellipse([X(42), Y(32), X(66), Y(56)], fill=AMBER)
    for a, b, c, d in [(52,24,56,33),(52,55,56,64),(30,42,39,46),(69,42,78,46)]:
        draw.rectangle([X(a), Y(b), X(c), Y(d)], fill=AMBER)
    draw.polygon([(X(36),Y(70)),(X(72),Y(70)),(X(80),Y(90)),(X(28),Y(90))], fill=CYAN)
    lw = max(1, int(1.6 * s))
    for a, b, c, d in [(48,70,44,90),(60,70,64,90),(32,80,76,80)]:
        draw.line([X(a), Y(b), X(c), Y(d)], fill=NAVY, width=lw)

def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0]-1, im.size[1]-1],
                                           radius=radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out

def make_tablet(shots, W, H, out):
    img = Image.new("RGB", (W, H), NAVY)
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        d.line([(0, y), (W, y)],
               fill=tuple(int(NAVY[i] + (NAVY2[i]-NAVY[i]) * t) for i in range(3)))
    img = img.convert("RGBA")

    # header: icon mark + wordmark, top-left
    hh = int(H * 0.10)
    d = ImageDraw.Draw(img)
    tile = int(hh * 0.78)
    tx, ty = int(W * 0.035), (hh - tile) // 2 + int(hh * 0.15)
    d.rounded_rectangle([tx, ty, tx+tile, ty+tile], radius=int(tile*0.24),
                        fill=TILE, outline=TILE_BORDER, width=max(1, tile//90))
    pad = 0.16 * tile
    draw_icon_art(d, tx+pad, ty+pad, (tile-2*pad)/108.0)
    wf = font(BOLD, int(hh * 0.44))
    d.text((tx+tile+int(hh*0.28), ty + (tile-wf.size)//2 - int(hh*0.02)),
           "Solar Dashboard", font=wf, fill=TITLE)

    # panels row
    n = len(shots)
    gap = int(W * 0.045)
    avail_h = H - int(hh * 1.25) - int(H * 0.05)
    panel_h = int(avail_h)
    panel_w = int(panel_h * 1080 / 1920)
    total_w = n * panel_w + (n - 1) * gap
    while total_w > W * 0.94:  # shrink to fit width
        panel_h -= 20
        panel_w = int(panel_h * 1080 / 1920)
        total_w = n * panel_w + (n - 1) * gap
    x0 = (W - total_w) // 2
    y0 = int(hh * 1.25) + (avail_h - panel_h) // 2
    radius = int(panel_w * 0.055)

    for i, s in enumerate(shots):
        ph = Image.open(os.path.join(SHOTS, s)).convert("RGB").resize(
            (panel_w, panel_h), Image.LANCZOS)
        ph = rounded(ph, radius)
        x = x0 + i * (panel_w + gap)
        # soft shadow
        sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle(
            [x, y0+10, x+panel_w, y0+panel_h+10], radius=radius, fill=(0, 0, 0, 150))
        sh = sh.filter(ImageFilter.GaussianBlur(24))
        img = Image.alpha_composite(img, sh)
        # subtle border
        bd = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(bd).rounded_rectangle(
            [x-1, y0-1, x+panel_w+1, y0+panel_h+1], radius=radius,
            outline=(40, 56, 78, 255), width=3)
        img.paste(ph, (x, y0), ph)
        img = Image.alpha_composite(img, bd)

    img.convert("RGB").save(out)
    print(f"{os.path.basename(out):24s} {W}x{H} ratio={max(W,H)/min(W,H):.2f}")

GROUPS = [
    (["01-dashboard.png", "02-devices.png", "03-batteries.png"], "a"),
    (["04-history.png", "05-alerts.png", "06-help.png"], "b"),
]
for sub, W, H in [("tablet-7in", 1920, 1080), ("tablet-10in", 2560, 1440)]:
    os.makedirs(f"{BASE}/{sub}", exist_ok=True)
    for shots, tag in GROUPS:
        make_tablet(shots, W, H, f"{BASE}/{sub}/{tag}.png")
