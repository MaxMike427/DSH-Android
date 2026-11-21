/** Repeatable device benchmark: real system drags and native-plugin shake input. */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { adb, connectPet, sleep, stateExpression } from './pet-cdp.mjs'

const page = await connectPet()
const strict = process.argv.includes('--assert')
const output = process.argv.find(arg => arg.endsWith('.json'))
try {
  await page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape", bubbles:true}))')
  await sleep(300)
  adb('shell', 'dumpsys gfxinfo com.dshapp reset')
  await page.evaluate(`(() => {
    const stats = { frames: [], reports: [], motion: [], lags: [], mutationAt: 0, running: true, pointer: [], mutations: 0 }
    const api = window.__dshPetOnly
    const originalMotion = api.receiveMotion
    const timeout = window.setTimeout, raf = window.requestAnimationFrame
    const recordReport = () => {
      const now = performance.now()
      stats.reports.push(now)
      if (stats.mutationAt) stats.lags.push(now - stats.mutationAt)
      stats.mutationAt = 0
    }
    const wrap = callback => typeof callback === 'function' && callback.name === 'report'
      ? (...args) => { recordReport(); return callback(...args) } : callback
    window.setTimeout = (callback, delay, ...args) => timeout(wrap(callback), delay, ...args)
    window.requestAnimationFrame = callback => raf(wrap(callback))
    const microtask = window.queueMicrotask
    window.queueMicrotask = callback => microtask(wrap(callback))
    api.receiveMotion = function (sample) {
      stats.motion.push(performance.now())
      return originalMotion.call(api, sample)
    }
    const root = document.querySelector('.dsh-pet-root')
    const observer = new MutationObserver(() => {
      stats.mutations++
      if (!stats.mutationAt) stats.mutationAt = performance.now()
    })
    observer.observe(root, { attributes: true, attributeFilter: ['style'] })
    const pointer = e => stats.pointer.push({ type: e.type, target: e.target.className, x: e.clientX, y: e.clientY })
    for (const type of ['pointerdown', 'pointermove', 'pointerup']) document.addEventListener(type, pointer, true)
    let previous = performance.now()
    function sample(now) {
      if (!stats.running) return
      stats.frames.push(now - previous)
      previous = now
      requestAnimationFrame(sample)
    }
    requestAnimationFrame(sample)
    window.__petBench = stats
    stats.stop = () => {
      stats.running = false
      observer.disconnect()
      for (const type of ['pointerdown', 'pointermove', 'pointerup']) document.removeEventListener(type, pointer, true)
      window.setTimeout = timeout
      window.requestAnimationFrame = raf
      window.queueMicrotask = microtask
      api.receiveMotion = originalMotion
      delete window.__petBench
      return { frames: stats.frames, reports: stats.reports, motion: stats.motion, lags: stats.lags, pointer: stats.pointer, mutations: stats.mutations }
    }
  })()`)
  for (const dx of [-160, 160]) {
    const state = await page.evaluate(stateExpression), d = await page.description()
    const x = Math.round(d.screenX + (state.hit.x + state.hit.w / 2) * state.dpr)
    const y = Math.round(d.screenY + (state.hit.y + state.hit.h / 2) * state.dpr)
    const toX = Math.max(120, Math.min(960, x + dx))
    adb('shell', 'input', 'swipe', String(x), String(y), String(toX), String(y), '1000')
    await sleep(150)
  }
  await page.evaluate(`window.__dshPetOnly.receiveMotion({alpha:0,beta:null,gamma:null,
    acceleration:{x:40,y:25,z:0},gravity:{x:0,y:0,z:9.8},
    rotationRate:{alpha:0,beta:0,gamma:0},interval:20})`)
  await sleep(1000)
  const raw = await page.evaluate('window.__petBench.stop()')
  const diffs = values => values.slice(1).map((v, i) => v - values[i]).filter(v => v > 0 && v < 150)
  const quantile = (values, q) => {
    const sorted = [...values].sort((a, b) => a - b)
    return Math.round((sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))] || 0) * 100) / 100
  }
  const describe = values => ({ n: values.length, p50: quantile(values, .5), p95: quantile(values, .95), max: Math.round(Math.max(0, ...values) * 100) / 100 })
  const result = {
    frameMs: describe(raw.frames),
    cropIntervalMs: describe(diffs(raw.reports)),
    mutationToCropMs: describe(raw.lags),
    sensorIntervalMs: describe(diffs(raw.motion)),
    slowFrames: raw.frames.filter(v => v > 34).length,
    pointerEvents: raw.pointer.length,
    pointerTargets: [...new Set(raw.pointer.map(e => e.target))],
    mutations: raw.mutations,
    gfx: adb('shell', 'dumpsys gfxinfo com.dshapp').split(/\r?\n/)
      .filter(line => /Total frames rendered|Janky frames|50th percentile|95th percentile|99th percentile/.test(line)).map(line => line.trim()),
  }
  console.log(JSON.stringify(result, null, 2))
  if (output) fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n')
  if (strict) {
    assert.ok(result.mutationToCropMs.n > 20, 'not enough movement to profile')
    assert.ok(result.mutationToCropMs.p50 < 12, 'crop adds an extra frame of motion latency')
    assert.ok(result.sensorIntervalMs.p50 < 28, 'sensor forwarding is below 35Hz')
  }
} finally {
  await page.evaluate('window.__petBench?.stop()')
  page.close()
}
