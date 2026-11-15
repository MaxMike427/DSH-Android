"""Shrink the launcher PNGs.

Launcher icons are opaque squares shown at <=192dp; full 24-bit colour with a
full alpha channel wastes several hundred KB in the APK (and in the API payload
that publishes this repo). Quantising to a 256-colour palette keeps the artwork
visually identical at icon scale and cuts each file by ~5-10x.
"""

from __future__ import annotations

import glob
import os
from PIL import Image

RES = r"C:\Users\Administrator\Documents\DSH-Android\app\src\main\res"

TARGETS = (
    glob.glob(os.path.join(RES, "mipmap-*", "ic_launcher*.png"))
    + glob.glob(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"))
)

total_before = 0
total_after = 0

for path in sorted(TARGETS):
    before = os.path.getsize(path)
    total_before += before
    image = Image.open(path).convert("RGBA")

    # Preserve transparency: an opaque icon quantises cleanly; the adaptive
    # foreground needs its alpha channel kept separate from the palette.
    if image.getchannel("A").getextrema() == (255, 255):
        flat = image.convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT)
        flat.save(path, optimize=True)
    else:
        alpha = image.getchannel("A")
        rgb = Image.new("RGB", image.size, (255, 255, 255))
        rgb.paste(image, mask=alpha)
        rgb = rgb.quantize(colors=255, method=Image.Quantize.MEDIANCUT).convert("RGBA")
        rgb.putalpha(alpha)
        rgb.save(path, optimize=True)

    after = os.path.getsize(path)
    total_after += after
    print(f"{os.path.relpath(path, RES):55s} {before:7d} -> {after:6d}")

print(f"\ntotal {total_before} -> {total_after} bytes "
      f"({100 - total_after * 100 // max(total_before, 1)}% smaller)")
