/**
 * Measure the pet's context menu in the *main* WebView (the 1080-wide page),
 * so the floating window can be sized to hold both the pet and its menu.
 */
const cdpRoot = 'http://127.0.0.1:9222'
const targets = await (await fetch(`${cdpRoot}/json/list`)).json()
const page = targets
  .filter(t => t.type === 'page')
  .map(t => ({ ...t, d: JSON.parse(t.description || '{}') }))
  .sort((a, b) => (b.d.width || 0) - (a.d.width || 0))[0]

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

await send('Runtime.enable')

const openMenu = `(() => {
  const hit = document.querySelector('.dsh-pet-hit')
  if (!hit) return 'no hit'
  const r = hit.getBoundingClientRect()
  hit.dispatchEvent(new MouseEvent('contextmenu', {
    bubbles: true, button: 2, cancelable: true,
    clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, view: window
  }))
  return 'dispatched'
})()`

const measure = `(() => {
  const menu = document.querySelector('.dsh-pet-menu')
  if (!menu) return JSON.stringify({ menu: null })
  const r = menu.getBoundingClientRect()
  const root = document.querySelector('.dsh-pet-root')
  const rr = root ? root.getBoundingClientRect() : null
  const hit = document.querySelector('.dsh-pet-hit')
  const hr = hit ? hit.getBoundingClientRect() : null
  return JSON.stringify({
    menu: { w: Math.round(r.width), h: Math.round(r.height), l: Math.round(r.left), t: Math.round(r.top) },
    root: rr && { w: Math.round(rr.width), h: Math.round(rr.height), l: Math.round(rr.left), t: Math.round(rr.top) },
    hit: hr && { w: Math.round(hr.width), h: Math.round(hr.height), l: Math.round(hr.left), t: Math.round(hr.top) },
    viewport: { w: innerWidth, h: innerHeight }
  })
})()`

console.log('open:', (await send('Runtime.evaluate', { expression: openMenu, returnByValue: true })).result?.value)
await new Promise(r => setTimeout(r, 700))
const m = await send('Runtime.evaluate', { expression: measure, returnByValue: true })
console.log('measure:', m.result?.value)

// close it again
await send('Runtime.evaluate', {
  expression: `(() => { document.dispatchEvent(new MouseEvent('mousedown', {bubbles:true})); document.body.dispatchEvent(new MouseEvent('mousedown', {bubbles:true})); return 'closed' })()`,
  returnByValue: true,
})
ws.close()
