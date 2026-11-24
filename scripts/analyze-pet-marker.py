"""Track a two-colour fiducial in real Android screenrecord frames, not DOM geometry."""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

video, ffmpeg, out = sys.argv[1:4]
width, height = 540, 1200
menu_box = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
menu_whites = []
outside_menu_whites = []
baseline_outside = None
process = subprocess.Popen([
    ffmpeg, '-v', 'error', '-i', video, '-vsync', '0', '-f', 'rawvideo',
    '-pix_fmt', 'rgb24', 'pipe:1',
], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
size = width * height * 3
samples = []
selected = []
last = None
while True:
    data = process.stdout.read(size)
    if not data:
        break
    if len(data) != size:
        raise RuntimeError('partial RGB frame')
    image = Image.frombytes('RGB', (width, height), data)
    rgb = np.asarray(image)
    if menu_box:
        x, y, w, h = menu_box
        left, top = max(0, int(x / 2)), max(0, int(y / 2))
        right, bottom = min(width, int((x + w) / 2)), min(height, int((y + h) / 2))
        region = rgb[top:bottom, left:right]
        menu_whites.append(int((region.min(axis=2) > 230).sum()))
        # A genuine WebView/window white clear is different from the clipped
        # menu: it paints outside the final menu bounds. Track both symptoms.
        outside = rgb.min(axis=2) > 230
        outside[:60] = False
        outside[1150:] = False
        outside[max(0, top - 6):min(height, bottom + 6),
                max(0, left - 6):min(width, right + 6)] = False
        if baseline_outside is None:
            baseline_outside = outside.copy()
        outside_menu_whites.append(int((outside & ~baseline_outside).sum()))
    green = ((rgb[:, :, 1] > 170) & (rgb[:, :, 0] < 100) & (rgb[:, :, 2] < 150))
    magenta = ((rgb[:, :, 0] > 180) & (rgb[:, :, 1] < 100) & (rgb[:, :, 2] > 140))
    # Summed-area neighbourhood lookup, rather than MaxFilter across the whole
    # screen on every frame: linear time keeps this device regression fast.
    padded = np.pad(green.astype(np.int32), 5)
    summed = np.pad(padded, ((1, 0), (1, 0))).cumsum(0, dtype=np.int32).cumsum(1, dtype=np.int32)
    near_green = (summed[11:, 11:] - summed[:-11, 11:] -
                  summed[11:, :-11] + summed[:-11, :-11]) > 0
    mask = magenta & near_green
    ys, xs = np.nonzero(mask)
    index = len(samples)
    point = None
    if len(xs) >= 20:
        point = [round(float(xs.mean()) * 2, 2), round(float(ys.mean()) * 2, 2)]
    samples.append(point)
    jump = 0 if last is None or point is None else float(np.hypot(point[0] - last[0], point[1] - last[1]))
    if index == 0 or jump > 4 or (point is None) != (last is None):
        selected.append((index, image.copy(), point))
    last = point
process.stdout.close()
if process.wait() != 0:
    raise RuntimeError('ffmpeg failed')
found = [(i, p) for i, p in enumerate(samples) if p is not None]
if len(found) < 20:
    raise RuntimeError('not enough marker frames: marker must stay outside the menu')
baseline = np.asarray(found[0][1])
distances = [(round(float(np.linalg.norm(np.asarray(p) - baseline)), 2), i) for i, p in found]
worst, worst_index = max(distances)
first, final = found[0][0], found[-1][0]
missing = sum(p is None for p in samples[first:final + 1])
for index, image, point in selected[:24]:
    # Save only the relevant strip around the known marker positions.
    image.save(os.path.join(out, f'frame-{index:03d}.png'))
result = {'frames': len(samples), 'tracked': len(found), 'baseline': found[0][1],
          'maxJumpPx': worst, 'worstFrame': worst_index, 'missingDuringTransition': missing,
          'changedFrames': [{'frame': i, 'point': p} for i, p in enumerate(samples)
                            if p is None or float(np.linalg.norm(np.asarray(p) - baseline)) > 4]}
if menu_box:
    base_white = min(menu_whites)
    menu_white = max(menu_whites) - base_white
    if menu_white < 1000:
        raise RuntimeError('menu did not actually paint a white panel in the recording')
    partial = [i for i, count in enumerate(menu_whites)
               if 0.05 < (count - base_white) / menu_white < 0.9]
    result.update({'partialMenuFrames': len(partial), 'partialMenuFrameIndices': partial,
                   'menuWhitePixels': menu_white,
                   'maxUnexpectedWhiteOutsideMenu': max(outside_menu_whites)})
with open(os.path.join(out, 'marker.json'), 'w', encoding='utf8') as handle:
    json.dump(result, handle, indent=2)
print(json.dumps(result))
