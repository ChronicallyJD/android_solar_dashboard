#!/usr/bin/env python3
"""Prepare Play-compliant phone screenshots: uniform 1080x1920 (9:16),
24-bit PNG (no alpha). Play requires a 16:9 or 9:16 ratio for phone shots."""
import os
from PIL import Image

SRC = "/home/jd/repos/android_solar_dashboard/docs/images"
OUT = "/home/jd/repos/android_solar_dashboard/docs/store/screenshots"
os.makedirs(OUT, exist_ok=True)
NAVY = (11, 18, 32)
TARGET_H = 1920  # 1080x1920 == 9:16

# (source, output-name, top-crop, bottom-crop). Crops chosen so h-top-bottom == 1920,
# anchored to keep the top (toolbar and the most important content).
PLAN = [
    ("dashboard-top.png",     "01-dashboard.png", 112, 308),  # 2340, drop status bar + bottom
    ("dashboard-inverter.png","02-devices.png",     0, 270),  # 2190 -> 1920
    ("dashboard-bms.png",     "03-batteries.png",    0, 270),
    ("dashboard-history.png", "04-history.png",      0, 270),
    ("settings-alerts.png",   "05-alerts.png",       0, 270),
    ("help-screen.png",       "06-help.png",         0, 270),
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
