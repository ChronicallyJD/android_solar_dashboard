#!/usr/bin/env python3
"""Prepare Play-compliant phone screenshots: uniform 1080x2160 (exactly 2:1),
24-bit PNG (no alpha)."""
import os
from PIL import Image

SRC = "/home/jd/repos/android_solar_dashboard/docs/images"
OUT = "/home/jd/repos/android_solar_dashboard/docs/store/screenshots"
os.makedirs(OUT, exist_ok=True)
NAVY = (11, 18, 32)
TARGET_H = 2160

# (source, output-name, top-crop, bottom-crop). Crops chosen so h-top-bottom == 2160.
PLAN = [
    ("dashboard-top.png",     "01-dashboard.png", 112, 68),   # 2340 -> 2160, drop status bar
    ("dashboard-inverter.png","02-devices.png",     0, 30),   # 2190 -> 2160
    ("dashboard-bms.png",     "03-batteries.png",    0, 30),
    ("dashboard-history.png", "04-history.png",      0, 30),
    ("settings-alerts.png",   "05-alerts.png",       0, 30),
    ("help-screen.png",       "06-help.png",         0, 30),
]

for src, out, top, bot in PLAN:
    im = Image.open(os.path.join(SRC, src)).convert("RGBA")
    w, h = im.size
    # Flatten onto navy (alpha is opaque, so this only drops the alpha channel).
    bg = Image.new("RGB", (w, h), NAVY)
    bg.paste(im, (0, 0), im)
    crop = bg.crop((0, top, w, h - bot))
    assert crop.size == (1080, TARGET_H), f"{out} is {crop.size}"
    crop.save(os.path.join(OUT, out))
    r = max(crop.size) / min(crop.size)
    print(f"{out:20s} {crop.size[0]}x{crop.size[1]} ratio={r:.3f} mode={crop.mode}")
