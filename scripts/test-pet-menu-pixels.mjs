/** Capture real system long-press transitions; DOM coordinates miss compositor jumps. */
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { adb, connectPet, sleep } from './pet-cdp.mjs'

const root = path.dirname(fileURLToPath(import.meta.url))
const out = process.env.PET_PIXEL_ARTIFACTS || 'C:/Users/Administrator/AppData/Local/Temp/opencode/pet-menu-pixels'
const ffmpeg = process.env.PET_FFMPEG ||
  'C:/Users/Administrator/AppData/Local/Temp/opencode/ffmpeg/node_modules/@ffmpeg-installer/win32-x64/ffmpeg.exe'
assert.ok(fs.existsSync(ffmpeg), 'set PET_FFMPEG to an existing ffmpeg executable')
fs.mkdirSync(out, { recursive: true })
const page = await connectPet(false, true)
let recording, completed
try {
  // Hold a known stationary pose, not its video animation. A tiny two-colour
  // fiducial is attached to the actual hit box, so it traverses the same Android
  // buffer/window transactions as the pet but is unambiguous in screen pixels.
  const target = await page.evaluate(`(() => {
    document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true}))
    const api=window.__dshPetOnly, original=api.receiveMotion
    api.receiveMotion=()=>{}
    const style=document.createElement('style')
    style.id='pet-pixel-test-style'
    style.textContent='.dsh-pet-root{left:70px!important;top:150px!important;right:auto!important;bottom:auto!important}'
    document.head.appendChild(style)
    const hit=document.querySelector('.dsh-pet-hit')
    if(!hit)throw Error('no pet hit box')
    const marker=document.createElement('i')
    marker.id='pet-pixel-test-marker'
    marker.style.cssText='position:absolute;left:2px;top:2px;width:16px;height:16px;box-sizing:border-box;border:3px solid rgb(0,255,64);background:rgb(255,0,204);pointer-events:none;z-index:999'
    hit.appendChild(marker)
    window.__petPixelCleanup=()=>{api.receiveMotion=original;style.remove();marker.remove();delete window.__petPixelCleanup}
    const r=hit.getBoundingClientRect()
    return {x:r.x+r.width/2,y:r.y+r.height/2,dpr:devicePixelRatio}
  })()`)
  await sleep(800)
  const d = await page.description()
  const x = Math.round(d.screenX + target.x * target.dpr)
  const y = Math.round(d.screenY + target.y * target.dpr)
  const video = '/sdcard/dsh-pet-pixel-test.mp4'
  recording = spawn('adb', ['shell', 'screenrecord', '--time-limit', '6', '--size', '540x1200',
    '--bit-rate', '12000000', video], { stdio: ['ignore', 'pipe', 'pipe'] })
  let recordError = ''
  recording.stderr.on('data', data => { recordError += data })
  completed = new Promise((resolve, reject) => {
    recording.on('error', reject)
    recording.on('exit', code => code === 0 ? resolve() : reject(Error(`screenrecord ${code}: ${recordError}`)))
  })
  await sleep(1000)
  adb('shell', 'input', 'swipe', String(x), String(y), String(x), String(y), '1000')
  await sleep(600)
  assert.ok(await page.evaluate(`!![...document.querySelectorAll('.dsh-pet-menu-column')].find(e=>e.getBoundingClientRect().width>0)`), 'real system long press did not open a menu')
  await page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape",bubbles:true}))')
  await completed
  const local = path.join(out, 'menu.mp4')
  adb('pull', video, local)
  const result = execFileSync('python', [path.join(root, 'analyze-pet-marker.py'), local, ffmpeg, out], { encoding: 'utf8' })
  console.log(result)
  const verdict = JSON.parse(result)
  assert.ok(verdict.maxJumpPx <= 4, `compositor moved pet ${verdict.maxJumpPx}px; artifacts: ${out}`)
  assert.ok(verdict.missingDuringTransition <= 1, `pet disappears during ${verdict.missingDuringTransition} rendered frames`)
  console.log('PASS: actual pet pixels stay fixed and visible through menu open/close')
} finally {
  if (completed) await completed.catch(() => {})
  await page.evaluate('window.__petPixelCleanup?.()')
  page.close()
}
