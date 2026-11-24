/** Exercise the same empty-video state reached between rapid animation swaps. */
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { adb, connectPet, sleep } from './pet-cdp.mjs'

const overlay = process.argv.includes('--overlay')
const out = process.env.PET_PIXEL_ARTIFACTS ||
  'C:/Users/Administrator/AppData/Local/Temp/opencode/pet-video-poster'
fs.mkdirSync(out, { recursive: true })
const page = await connectPet(!overlay, true)
const screenshot = name => {
  adb('shell', 'screencap', '-p', '/sdcard/dsh-pet-poster-regression.png')
  const file = path.join(out, name + '.png')
  adb('pull', '/sdcard/dsh-pet-poster-regression.png', file)
  return file
}
try {
  const box = await page.evaluate(`(() => {
    const hit=document.querySelector('.dsh-pet-hit')
    if(!hit)throw Error('pet did not load')
    const video=document.createElement('video')
    video.id='pet-video-poster-regression'
    video.controls=false
    video.muted=true
    video.playsInline=true
    video.style.cssText='position:absolute;inset:0;width:100%;height:100%;object-fit:fill;' +
      'background:transparent;opacity:1;pointer-events:none;z-index:999'
    hit.appendChild(video)
    video.load()
    const r=hit.getBoundingClientRect()
    return {x:r.x,y:r.y,w:r.width,h:r.height,dpr:devicePixelRatio}
  })()`)
  await sleep(350)
  const origin = await page.description()
  const pixels = [origin.screenX + box.x * box.dpr, origin.screenY + box.y * box.dpr,
    box.w * box.dpr, box.h * box.dpr].map(Math.round)
  const image = screenshot(overlay ? 'desktop' : 'inapp')
  const verdict = JSON.parse(execFileSync('python', ['-c', `
import json,sys,numpy as np
from PIL import Image
x,y,w,h=json.loads(sys.argv[2])
a=np.asarray(Image.open(sys.argv[1]).convert('RGB'))[y:y+h,x:x+w]
low,high=a.min(2),a.max(2)
grey=(low>65)&(high<200)&(high.astype(int)-low<8)
print(json.dumps({'defaultPosterCoverage':float(grey.mean())}))
`, image, JSON.stringify(pixels)], { encoding: 'utf8' }))
  console.log(JSON.stringify({ overlay, ...verdict, image }))
  assert.ok(verdict.defaultPosterCoverage < 0.2,
    'Android default video poster paints a grey/white rectangle over the pet')
  console.log('PASS: an empty pet animation frame does not paint Android’s default play poster')
} finally {
  await page.evaluate('document.getElementById("pet-video-poster-regression")?.remove()')
  page.close()
}
