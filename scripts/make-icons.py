"""Generate Android launcher icons from the character sheet.

The sheet has three views (front/side/back) on one image. The launcher icon uses
the front view, cropped to a square around the head and torso so the character
stays readable at 48dp.

Outputs (overwriting the previous vector placeholder):
  mipmap-mdpi/ic_launcher.png      48
  mipmap-hdpi/ic_launcher.png      72
  mipmap-xhdpi/ic_launcher.png     96
  mipmap-xxhdpi/ic_launcher.png    144
  mipmap-xxxhdpi/ic_launcher.png   192
  drawable-nodpi/ic_launcher_foreground.png  432 (adaptive foreground)
  values/colors.xml                background color sampled from the art
"""

from __future__ import annotations

import os
from PIL import Image

SOURCE = r"C:\Users\Administrator\Downloads\a1bdabe0516e4f62b11bc5db349fcc97_1.jpg"
RES = r"C:\Users\Administrator\Documents\DSH-Android\app\src\main\res"

# Front view occupies roughly the left third of the sheet.
FRONT_BOX = (10, 20, 340, 470)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

ADAPTIVE_FOREGROUND = 432


def square_crop(image: Image.Image, box: tuple[int, int, int, int]) -> Image.Image:
    cropped = image.crop(box)
    width, height = cropped.size
    side = min(width, height)
    left = (width - side) // 2
    top = (height - side) // 2
    return cropped.crop((left, top, left + side, top + side))


def rounded(image: Image.Image) -> Image.Image:
    """Round the legacy icon corners; adaptive icons are masked by the launcher."""
    from PIL import ImageDraw

    mask = Image.new("L", image.size, 0)
    draw = ImageDraw.Draw(mask)
    radius = int(image.size[0] * 0.18)
    draw.rounded_rectangle((0, 0, image.size[0] - 1, image.size[1] - 1), radius=radius, fill=255)
    out = Image.new("RGBA", image.size, (0, 0, 0, 0))
    out.paste(image, (0, 0), mask)
    return out


def main() -> None:
    sheet = Image.open(SOURCE).convert("RGB")
    front = square_crop(sheet, FRONT_BOX)

    # Background: sample the sheet's own pale-blue backdrop so the icon edge
    # blends with the artwork instead of introducing a new palette.
    background = sheet.getpixel((5, 5))

    for density, size in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        icon = front.resize((size, size), Image.LANCZOS)
        rounded(icon).save(os.path.join(folder, "ic_launcher.png"))
        icon.save(os.path.join(folder, "ic_launcher_round.png"))

    # Adaptive icons: the foreground is scaled to 66% inside the 108dp canvas so
    # the launcher's mask never clips the character.
    target = os.path.join(RES, "drawable-nodpi")
    os.makedirs(target, exist_ok=True)
    foreground = Image.new("RGBA", (ADAPTIVE_FOREGROUND, ADAPTIVE_FOREGROUND), (0, 0, 0, 0))
    inner = int(ADAPTIVE_FOREGROUND * 0.68)
    figure = front.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    offset = (ADAPTIVE_FOREGROUND - inner) // 2
    foreground.paste(figure, (offset, offset), figure)
    foreground.save(os.path.join(target, "ic_launcher_foreground.png"))

    # Remove the placeholder vector so it cannot be picked up by resource merging.
    vector = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
    if os.path.exists(vector):
        os.remove(vector)

    from PIL import ImageColor

    hex_color = "#%02X%02X%02X" % background
    ImageColor.getrgb(hex_color)
    colors = os.path.join(RES, "values", "colors.xml")
    with open(colors, "w", encoding="utf-8") as handle:
        handle.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_launcher_background">{hex_color}</color>\n'
            "</resources>\n"
        )

    print(f"icons written from {SOURCE}")
    print(f"background {hex_color}")


if __name__ == "__main__":
    main()
