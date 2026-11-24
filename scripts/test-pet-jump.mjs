/**
 * DOM-pose stability check across menu open/close.
 *
 * screenX/screenY report the renderer's logical origin, NOT WindowManager's
 * interpolated surface position. This test cannot catch system move animations.
 * Use test-pet-menu-pixels.mjs for the actual on-screen jump regression.
 */
import assert from 'node:assert/strict'
import { adb, connectPet, sleep, stateExpression } from './pet-cdp.mjs'

const page = await connectPet()
const dismiss = 'document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape", bubbles:true}))'
let touch = false
const sampler = `(() => {
  const samples = []
  const tick = () => {
    const r = document.querySelector('.dsh-pet-hit')?.getBoundingClientRect()
    if (r) samples.push({ t: performance.now(), sx: screenX, sy: screenY,
      iw: innerWidth, ih: innerHeight, dpr: devicePixelRatio,
      x: r.left, y: r.top, w: r.width, h: r.height })
    if (samples.length < 400) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
  window.__jumpSamples = samples
  return 'sampling'
})()`

try {
  await page.evaluate(dismiss)
  await sleep(300)
  const s = await page.evaluate(stateExpression)
  const point = { x: s.hit.x + s.hit.w / 2, y: s.hit.y + s.hit.h / 2,
    radiusX: 6, radiusY: 6, force: 1, id: 1 }
  await page.evaluate(sampler)
  await sleep(100)
  await page.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] })
  touch = true
  await sleep(900)
  await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  touch = false
  await sleep(400)
  const menu = await page.evaluate(`(() => {
    const label = [...document.querySelectorAll('.dsh-pet-menu-label')]
      .find(el => el.textContent === '碎碎念' && el.getBoundingClientRect().width > 0)
    if (!label) throw new Error('menu item not found')
    const r = label.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, dpr: devicePixelRatio }
  })()`)
  const d = await page.description()
  adb('shell', 'input', 'tap', String(Math.round(d.screenX + menu.x * menu.dpr)),
    String(Math.round(d.screenY + menu.y * menu.dpr)))
  await sleep(900)
  const samples = await page.evaluate('window.__jumpSamples')
  const centre = sample => ({
    x: sample.sx + (sample.x + sample.w / 2) * sample.dpr,
    y: sample.sy + (sample.y + sample.h / 2) * sample.dpr,
  })
  const base = centre(samples[0])
  const points = samples.map(sample => ({ ...centre(sample), iw: sample.iw, ih: sample.ih, t: sample.t }))
  const deviations = points.map(p => ({ dx: Math.round((p.x - base.x) * 10) / 10,
    dy: Math.round((p.y - base.y) * 10) / 10, iw: p.iw, ih: p.ih }))
  const worst = deviations.reduce((a, b) => Math.hypot(b.dx, b.dy) > Math.hypot(a.dx, a.dy) ? b : a)
  const sizes = [...new Set(points.map(p => `${p.iw}x${p.ih}`))]
  console.log(JSON.stringify({ frames: points.length, baseline: base, worst, viewports: sizes,
    first: deviations.slice(0, 6), last: deviations.slice(-6) }, null, 2))
  assert.ok(Math.hypot(worst.dx, worst.dy) < 2, `pet jumps ${Math.hypot(worst.dx, worst.dy).toFixed(1)}px during long-press/click`)
  console.log('PASS: logical pet pose stays anchored (use the pixel test to verify presentation)')
} finally {
  if (touch) await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await page.evaluate(dismiss)
  page.close()
}
