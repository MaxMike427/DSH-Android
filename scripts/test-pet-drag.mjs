/** Real Android drags: the pet must stay visible inside its moving window. */
import assert from 'node:assert/strict'
import { adb, connectPet, sleep, stateExpression, windowBounds } from './pet-cdp.mjs'

const page = await connectPet()
const sample = async () => {
  const value = await page.evaluate(stateExpression)
  const d = await page.description()
  const w = windowBounds()
  assert.ok(value.hit?.w > 0 && value.hit?.h > 0, 'pet hit box disappeared')
  const hit = {
    x: d.screenX + value.hit.x * value.dpr,
    y: d.screenY + value.hit.y * value.dpr,
    w: value.hit.w * value.dpr, h: value.hit.h * value.dpr,
  }
  const ix = Math.max(0, Math.min(hit.x + hit.w, w.x + w.w) - Math.max(hit.x, w.x))
  const iy = Math.max(0, Math.min(hit.y + hit.h, w.y + w.h) - Math.max(hit.y, w.y))
  assert.ok(ix * iy > hit.w * hit.h * 0.85, 'pet moved outside its Android window')
  assert.ok(value.media.some(m => m.w > 0 && m.h > 0 && m.display !== 'none' &&
    m.visibility === 'visible' && Number(m.opacity) > 0), 'pet video is hidden')
  return { hit, w, value }
}

try {
  for (const dx of process.argv.length > 2 ? [Number(process.argv[2])] : [30, -40, 55, -30]) {
    const before = await sample()
    const x = Math.round(before.hit.x + before.hit.w / 2)
    const y = Math.round(before.hit.y + before.hit.h / 2)
    adb('shell', 'input', 'swipe', String(x), String(y), String(x + dx), String(y + 15), '250')
    await sleep(650)
    const after = await sample()
    console.log(`PASS drag ${dx}px: pet=${after.value.hit.w.toFixed(1)}x${after.value.hit.h.toFixed(1)}, window=${JSON.stringify(after.w)}`)
  }
} finally {
  page.close()
}
