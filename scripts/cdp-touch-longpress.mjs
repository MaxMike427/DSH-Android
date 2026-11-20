/**
 * Drives a real long-press through CDP touch input.
 *
 * `adb shell input` cannot hold a press: `tap` releases immediately and `swipe`
 * injects MOVE events, which the pet reads as a drag and never as a menu press.
 * `Input.dispatchTouchEvent` lets the press sit still for an arbitrary time.
 */
const cdpRoot = 'http://127.0.0.1:9222'
const targets = await (await fetch(`${cdpRoot}/json/list`)).json()
const page = targets
  .filter(t => t.type === 'page')
  .map(t => ({ ...t, d: JSON.parse(t.description || '{}') }))
  .filter(t => (t.d.width || 0) < 600)[0]

const x = Number(process.argv[2] ?? 66)
const y = Number(process.argv[3] ?? 78)
console.log('target:', page.id, JSON.stringify(page.d), 'press at', x, y)

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
const sleep = ms => new Promise(r => setTimeout(r, ms))

await send('Runtime.enable')
const point = { x, y, radiusX: 6, radiusY: 6, force: 1, id: 1 }

await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] })
await sleep(1200)
await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
await sleep(600)

const state = await send('Runtime.evaluate', {
  expression: `(() => {
    const menus = document.querySelectorAll('.dsh-pet-menu')
    const out = []
    menus.forEach(m => { const r = m.getBoundingClientRect();
      out.push({ w: Math.round(r.width), h: Math.round(r.height), d: getComputedStyle(m).display }) })
    return JSON.stringify({ n: menus.length, menus: out })
  })()`,
  returnByValue: true,
})
console.log('menus:', state.result?.value)
ws.close()