"""Track a two-colour fiducial in real Android screenrecord frames, not DOM geometry."""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageFilter

video, ffmpeg, out = sys.argv[1:4]
width, height = 540, 1200
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
    green = ((rgb[:, :, 1] > 170) & (rgb[:, :, 0] < 100) & (rgb[:, :, 2] < 150))
    magenta = ((rgb[:, :, 0] > 180) & (rgb[:, :, 1] < 100) & (rgb[:, :, 2] > 140))
    near_green = np.asarray(Image.fromarray((green * 255).astype('uint8')).filter(ImageFilter.MaxFilter(11))) > 0
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
with open(os.path.join(out, 'marker.json'), 'w', encoding='utf8') as handle:
    json.dump(result, handle, indent=2)
print(json.dumps(result))
