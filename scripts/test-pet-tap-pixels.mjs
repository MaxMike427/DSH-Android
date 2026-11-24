/**
 * Record and assert actual Android pixels, including translucent default posters.
 * --cdp --varied replays rapid head/body taps; constant centre taps miss the race.
 * --overlay tests the desktop, --manual records real human touch for 90 seconds.
 */
import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { adb, connectPet, sleep } from './pet-cdp.mjs'

const out = process.env.PET_PIXEL_ARTIFACTS ||
  'C:/Users/Administrator/AppData/Local/Temp/opencode/pet-onboarding-taps'
const manual = process.argv.includes('--manual')
const varied = process.argv.includes('--varied')
const count = Number(process.env.PET_TAPS_COUNT || (varied ? 80 : 16))
const seconds = Number(process.env.PET_TAPS_SECONDS || (manual ? 90 : varied ? 20 : 16))
const ffmpeg = process.env.PET_FFMPEG ||
  'C:/Users/Administrator/AppData/Local/Temp/opencode/ffmpeg/node_modules/@ffmpeg-installer/win32-x64/ffmpeg.exe'
assert.ok(fs.existsSync(ffmpeg), 'set PET_FFMPEG to an existing ffmpeg executable')
const overlay = process.argv.includes('--overlay')
fs.mkdirSync(out, { recursive: true })
adb('shell', 'am', 'start', '-n', 'com.dshapp/com.dsh.android.MainActivity')
await sleep(800)
if (overlay) {
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
  await sleep(1000)
}
let page
for (let i = 0; i < 100; i++) {
  try { page = await connectPet(!overlay, true); break } catch { await sleep(150) }
}
assert.ok(page, 'test WebView did not start')
let completed
try {
  for (let i = 0; i < 60; i++) {
    if (await page.evaluate('!!document.querySelector(".dsh-pet-hit")')) break
    await sleep(200)
  }
  assert.ok(await page.evaluate('!!document.querySelector(".dsh-pet-hit")'), 'pet did not load')
  await page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown",{key:"Escape",bubbles:true}))')
  await page.evaluate(`(() => {
    const frames=[],events=[]
    const event=e=>events.push({type:e.type,at:performance.now(),x:e.clientX,y:e.clientY,
      hit:!!e.target.closest('.dsh-pet-hit'),cls:typeof e.target.className==='string'?e.target.className:'',
      pointerType:e.pointerType,pressure:e.pressure,width:e.width,height:e.height})
    const types=['pointerdown','pointerup','pointercancel']
    types.forEach(t=>document.addEventListener(t,event,true))
    const sample=()=>{
      const visible=[...document.querySelectorAll('[class*="dsh-pet-"],[aria-modal="true"]')]
        .map(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {
          tag:e.tagName,cls:typeof e.className==='string'?e.className:'',
          x:r.x,y:r.y,w:r.width,h:r.height,bg:s.backgroundColor,
          display:s.display,visibility:s.visibility,opacity:s.opacity,
          video:e.tagName==='VIDEO'?{src:e.currentSrc?new URL(e.currentSrc).pathname:'',
            ready:e.readyState,network:e.networkState,paused:e.paused,poster:e.poster,controls:e.controls}:undefined,
          textLength:e.matches('.dsh-pet-bubble')?e.textContent.length:undefined}})
        .filter(e=>e.w>0&&e.h>0&&e.display!=='none'&&e.visibility==='visible'&&e.opacity!=='0')
      const signature=JSON.stringify(visible)
      if(signature!==frames.at(-1)?.signature)frames.push({at:performance.now(),signature,visible})
    }
    const timer=setInterval(sample,30)
    window.__petTapProbe={frames,events,cleanup:()=>{clearInterval(timer);
      types.forEach(t=>document.removeEventListener(t,event,true));delete window.__petTapProbe}}
    sample()
  })()`)
  const video = '/sdcard/dsh-pet-taps.mp4'
  const startedAt = await page.evaluate('performance.now()')
  const recording = spawn('adb', ['shell', 'screenrecord', '--time-limit', String(seconds), '--size', '540x1200',
    '--bit-rate', '12000000', video], { stdio: ['ignore', 'pipe', 'pipe'] })
  let error = ''
  recording.stderr.on('data', data => { error += data })
  completed = new Promise((resolve, reject) => {
    recording.on('error', reject)
    recording.on('exit', code => code === 0 ? resolve() : reject(Error(`screenrecord ${code}: ${error}`)))
  })
  await sleep(900)
  console.log(manual ? 'MANUAL RECORDING: repeatedly tap the visible pet without switching apps' : 'Recording automated taps')
  const taps = []
  for (let i = 0; i < (manual ? 0 : count); i++) {
    const point = await page.evaluate(`(() => {
      const r=document.querySelector('.dsh-pet-hit').getBoundingClientRect()
      const index=${i}
      const varied=${varied}
      const rx=varied?0.2+((index*37)%61)/100:0.5
      const ry=varied?0.15+((index*23)%67)/100:0.5
      return {x:r.x+r.width*rx,y:r.y+r.height*ry,dpr:devicePixelRatio,at:performance.now()}
    })()`)
    const origin = await page.description()
    const x = Math.round(origin.screenX + point.x * point.dpr)
    const y = Math.round(origin.screenY + point.y * point.dpr)
    taps.push({ ...point, screenX: x, screenY: y })
    if (process.argv.includes('--cdp')) {
      await page.send('Input.dispatchTouchEvent', { type: 'touchStart',
        touchPoints: [{ x: point.x, y: point.y, radiusX: 6, radiusY: 6, force: 1, id: 1 }] })
      await sleep(Number(process.env.PET_TAP_DOWN_MS || (varied ? 8 : 60 + (i % 3) * 50)))
      await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    } else {
      adb('shell', 'input', 'tap', String(x), String(y))
    }
    await sleep(Number(process.env.PET_TAP_INTERVAL || 100))
  }
  await completed
  adb('pull', video, path.join(out, 'taps.mp4'))
  const frames = await page.evaluate('window.__petTapProbe.frames.map(({signature,...frame})=>frame)')
  const events = await page.evaluate('window.__petTapProbe.events')
  if (manual) {
    const origin = await page.description()
    const dpr = await page.evaluate('devicePixelRatio')
    taps.push(...events.filter(e => e.type === 'pointerdown' && e.hit).map(e => ({
      ...e, dpr, screenX: origin.screenX + e.x * dpr, screenY: origin.screenY + e.y * dpr,
    })))
  }
  fs.writeFileSync(path.join(out, 'taps.json'), JSON.stringify({ startedAt, taps, frames, events }, null, 2) + '\n')
  console.log(JSON.stringify({ taps: taps.length, states: frames.length,
    hits: events.filter(e => e.type === 'pointerdown' && e.hit).length,
    classes: [...new Set(frames.flatMap(f => f.visible.map(e => e.cls)))], artifacts: out }))
  if (!manual) assert.equal(events.filter(e => e.type === 'pointerdown' && e.hit).length, count,
    'test taps did not actually reach the pet')
  assert.ok(taps.length, 'no actual pet taps were recorded')
  const analyzer = path.join(path.dirname(fileURLToPath(import.meta.url)), 'analyze-pet-taps.py')
  const verdict = JSON.parse(execFileSync('python', [analyzer, path.join(out, 'taps.mp4'), ffmpeg,
    path.join(out, 'taps.json'), out], { encoding: 'utf8', timeout: 90000 }))
  console.log(JSON.stringify(verdict))
  assert.equal(verdict.whiteRectangleFrames, 0, 'a white video rectangle covered the pet')
  assert.equal(verdict.posterRectangleFrames, 0, 'Android’s default play poster flashed over the pet')
  console.log('PASS: repeated taps do not expose a white rectangle or the default video poster')
} finally {
  if (completed) await completed.catch(() => {})
  await page.evaluate('window.__petTapProbe?.cleanup()')
  page.close()
}
