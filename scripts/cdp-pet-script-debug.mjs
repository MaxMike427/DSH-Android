/**
 * Re-runs the generated pet-only script inside the overlay page and reports any
 * exception plus the resulting geometry. Used to find out why the injected
 * script leaves `window.__dshPetOnly` unset.
 */
const cdpRoot = 'http://127.0.0.1:9222'
const targets = await (await fetch(`${cdpRoot}/json/list`)).json()
const page = targets
  .filter(t => t.type === 'page')
  .map(t => ({ ...t, d: JSON.parse(t.description || '{}') }))
  .filter(t => (t.d.width || 0) < 600)[0] || targets.filter(t => t.type === 'page')[0]

console.log('target:', page.id, JSON.stringify(page.d))

const ws = new WebSocket(page.webSocketDebuggerUrl)
const pending = new Map()
let nextId = 1
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id)
    pending.delete(m.id)
    m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
  }
})
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true })
  ws.addEventListener('error', rej, { once: true })
})
const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = nextId++
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })

const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  })
  if (r.exceptionDetails) {
    return {
      error: r.exceptionDetails.exception?.description ||
        r.exceptionDetails.text,
    }
  }
  return r.result?.value
}

console.log('before:', await evaluate(`JSON.stringify({
  state: window.__dshPetOnly ? 'installed' : 'missing',
  bridge: typeof window.DshPetHost
})`))

// Reset the guard so the script's install path runs from the top.
console.log('reset:', await evaluate(`(() => {
  delete window.__dshPetOnly
  delete window.__dshPetNatural
  const t = document.getElementById('dsh-pet-only-style')
  if (t) t.remove()
  return 'cleared'
})()`))

// Paste the compiled script with its guard bypassed, wrapped so exceptions come
// back as text instead of a silent parse failure.
const script = process.env.PET_SCRIPT || ''
if (!script) {
  console.log('no PET_SCRIPT provided')
} else {
  const result = await evaluate(`(() => { try { ${script}; return 'ran' } catch (e) { return 'THROW: ' + e.message + ' @ ' + (e.stack||'').split('\\n')[1] } })()`)
  console.log('run:', result)
}

console.log('after:', await evaluate(`(() => {
  const pet = document.querySelector('.dsh-pet-root')
  const hit = document.querySelector('.dsh-pet-hit')
  const rb = pet.getBoundingClientRect()
  const hb = hit.getBoundingClientRect()
  const cs = getComputedStyle(pet)
  return JSON.stringify({
    state: window.__dshPetOnly ? 'installed' : 'missing',
    transform: cs.transform,
    left: cs.left,
    top: cs.top,
    root: { w: Math.round(rb.width), h: Math.round(rb.height), l: Math.round(rb.left), t: Math.round(rb.top) },
    hit: { w: Math.round(hb.width), h: Math.round(hb.height), l: Math.round(hb.left), t: Math.round(hb.top) },
    vw: innerWidth, vh: innerHeight
  })
})()`))

ws.close()