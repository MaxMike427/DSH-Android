"""
Detect a pet jump as a large displacement between pixels that appear and
pixels that disappear between two consecutive frames.

Video animation changes shape in place, so appeared/disappeared regions overlap
and their centroid distance stays small. A real jump moves the pet, so the two
centroids separate by the jump distance. This is robust to the pet's animation.
"""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

video, out_dir, ffmpeg = sys.argv[1], sys.argv[2], sys.argv[3]
region = None
if len(sys.argv) > 4:
    x, y, w, h = (int(v) for v in sys.argv[4].split(','))
    region = (x, y, w, h)

os.makedirs(out_dir, exist_ok=True)
for name in os.listdir(out_dir):
    if name.endswith('.png'):
        os.remove(os.path.join(out_dir, name))

subprocess.run([ffmpeg, '-v', 'quiet', '-i', video, '-vsync', '0',
                os.path.join(out_dir, 'f%05d.png')], check=True)

files = sorted(n for n in os.listdir(out_dir) if n.startswith('f'))
if len(files) < 5:
    print(json.dumps({'error': 'too few frames', 'frames': len(files)}))
    sys.exit(2)

frames = []
for name in files:
    with Image.open(os.path.join(out_dir, name)) as image:
        small = image.convert('RGB').resize((image.width // 2, image.height // 2), Image.BILINEAR)
        frames.append(np.asarray(small, dtype=np.int16))

scale = 2
if region:
    x, y, w, h = (v // scale for v in region)
else:
    x, y, w, h = 0, 0, frames[0].shape[1], frames[0].shape[0]

events = []
for index in range(1, len(frames)):
    before = frames[index - 1][y:y + h, x:x + w]
    after = frames[index][y:y + h, x:x + w]
    delta = after - before
    magnitude = np.abs(delta).sum(axis=2)
    appeared = magnitude > 45
    # Direction matters: a jump both removes the pet somewhere and adds it
    # elsewhere. Brightness-only changes (a video frame swapping) affect both
    # signs in the same place.
    gained = ((delta.sum(axis=2) > 25) & appeared)
    lost = ((delta.sum(axis=2) < -25) & appeared)
    if gained.sum() < 60 or lost.sum() < 60:
        continue
    gy, gx = np.nonzero(gained)
    ly, lx = np.nonzero(lost)
    gcx, gcy = float(gx.mean()), float(gy.mean())
    lcx, lcy = float(lx.mean()), float(ly.mean())
    distance = ((gcx - lcx) ** 2 + (gcy - lcy) ** 2) ** 0.5 * scale
    events.append({'frame': index, 'distance': round(distance, 1),
                   'gained': int(gained.sum()), 'lost': int(lost.sum()),
                   'from': [round(lcx * scale + x * scale, 1), round(lcy * scale + y * scale, 1)],
                   'to': [round(gcx * scale + x * scale, 1), round(gcy * scale + y * scale, 1)]})

worst = max(events, key=lambda e: e['distance']) if events else None
print(json.dumps({
    'frames': len(frames),
    'events': len(events),
    'worst': worst,
    'top': sorted(events, key=lambda e: -e['distance'])[:8],
}, indent=2))
