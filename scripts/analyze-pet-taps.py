"""Detect white clears and the Android default video poster in real recordings."""
import json
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

video, ffmpeg, capture, out = sys.argv[1:5]
with open(capture, encoding='utf-8') as handle:
    data = json.load(handle)
tap = data['taps'][0]
scale = tap['dpr'] / 2
origin_x = tap['screenX'] / 2 - tap['x'] * scale
origin_y = tap['screenY'] / 2 - tap['y'] * scale
info = subprocess.run([ffmpeg, '-v', 'info', '-i', video, '-vf', 'showinfo',
                       '-vsync', '0', '-f', 'null', '-'], capture_output=True)
times = [float(value) for value in re.findall(rb'pts_time:([\d.]+)', info.stderr)]
start = data.get('startedAt', data['frames'][0]['at'])
state_index = 0
process = subprocess.Popen([
    ffmpeg, '-v', 'fatal', '-i', video, '-vsync', '0', '-f', 'rawvideo',
    '-pix_fmt', 'rgb24', 'pipe:1',
], stdout=subprocess.PIPE)
samples = []
brightest = []
white_frames = []
poster_frames = []
patch_size = 20
while True:
    raw = process.stdout.read(540 * 1200 * 3)
    if not raw:
        break
    if len(raw) != 540 * 1200 * 3:
        raise RuntimeError('partial screenrecord frame')
    rgb = np.frombuffer(raw, dtype=np.uint8).reshape(1200, 540, 3)
    i = len(samples)
    at = start + times[i] * 1000
    while state_index + 1 < len(data['frames']) and data['frames'][state_index + 1]['at'] <= at:
        state_index += 1
    stage = next(e for e in data['frames'][state_index]['visible'] if e['cls'] == 'dsh-pet-stage')
    left = max(0, int(origin_x + stage['x'] * scale))
    top = max(0, int(origin_y + stage['y'] * scale))
    right = min(540, int(origin_x + (stage['x'] + stage['w']) * scale + 1))
    bottom = min(1200, int(origin_y + (stage['y'] + stage['h']) * scale + 1))
    region = rgb[top:bottom, left:right]
    white = region.min(axis=2) > 210
    summed = np.pad(white.astype(np.int32), ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    blocks = (summed[patch_size:, patch_size:] - summed[:-patch_size, patch_size:]
              - summed[patch_size:, :-patch_size] + summed[:-patch_size, :-patch_size])
    patch = int(blocks.max()) if blocks.size else 0
    # Android's default video poster is translucent grey, not pure white.
    # Its large flat neutral rectangle is distinct from the coloured character.
    low, high = region.min(axis=2), region.max(axis=2)
    neutral = (low > 28) & (low < 190) & (high.astype(np.int16) - low < 8)
    neutral_sum = np.pad(neutral.astype(np.int32), ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    neutral_blocks = (neutral_sum[patch_size:, patch_size:] - neutral_sum[:-patch_size, patch_size:]
                      - neutral_sum[patch_size:, :-patch_size] + neutral_sum[:-patch_size, :-patch_size])
    neutral_patch = int(neutral_blocks.max()) if neutral_blocks.size else 0
    samples.append({'frame': i, 'whitePixels': int(white.sum()), 'whitePatch': patch,
                    'neutralPixels': int(neutral.sum()), 'neutralPatch': neutral_patch})
    if patch >= patch_size * patch_size * 0.96:
        white_frames.append(i)
    if neutral_patch >= patch_size * patch_size * 0.96 and neutral.sum() > 2000:
        poster_frames.append(i)
    score = neutral_patch + int(neutral.sum()) / 100
    if len(brightest) < 12 or score > min(item[0] for item in brightest):
        brightest.append((score, i, region.copy()))
        brightest.sort(key=lambda item: item[0], reverse=True)
        brightest = brightest[:12]
process.stdout.close()
if process.wait() != 0:
    raise RuntimeError('ffmpeg failed')
w, h = 320, 200
sheet = Image.new('RGB', (w * 4, (h + 24) * 3))
for j, (score, i, rgb) in enumerate(brightest):
    x, y = j % 4 * w, j // 4 * (h + 24)
    image = Image.fromarray(rgb)
    image.thumbnail((w, h))
    sheet.paste(image, (x, y + 24))
    ImageDraw.Draw(sheet).text((x + 4, y + 4), f'frame {i} score={score:.1f}', fill='white')
sheet.save(os.path.join(out, 'body-rectangles.jpg'))
result = {'frames': len(samples),
          'maxWhitePixels': max(s['whitePixels'] for s in samples),
          'maxWhitePatch': max(s['whitePatch'] for s in samples),
          'whiteRectangleFrames': len(white_frames), 'whiteRectangleFrameIndices': white_frames,
          'posterRectangleFrames': len(poster_frames), 'posterRectangleFrameIndices': poster_frames}
with open(os.path.join(out, 'body-pixels.json'), 'w', encoding='utf-8') as handle:
    json.dump(result, handle, indent=2)
print(json.dumps(result))
