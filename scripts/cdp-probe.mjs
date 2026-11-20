/**
 * Minimal Chrome DevTools Protocol driver.
 *
 * Two uses:
 *   - reproduce a load with exact device metrics on desktop Chrome;
 *   - attach to the WebView inside the installed debug APK (through
 *     `adb forward`) and read what the page actually laid out.
 *
 * Usage:
 *   node scripts/cdp-probe.mjs --cdp http://127.0.0.1:9333 --url <url> \
 *     --width 1080 --height 2400 --dpr 2.75 --shot out.png
 *   node scripts/cdp-probe.mjs --cdp http://127.0.0.1:9222 --nav false --shot out.png
 */
import { writeFileSync } from 'node:fs'

const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback
}

const cdpRoot = arg('cdp', 'http://127.0.0.1:9333')
const url = arg('url', '')
const navigate = arg('nav', url ? 'true' : 'false') === 'true'
const width = Number(arg('width', '1080'))
const height = Number(arg('height', '2400'))
const dpr = Number(arg('dpr', '2.75'))
const mobile = arg('mobile', 'true') !== 'false'
const applyMetrics = arg('metrics', 'false') === 'true'
const shotPath = arg('shot', '')
const evaluateOnly = arg('eval', '')

const targets = await (await fetch(`${cdpRoot}/json/list`)).json()
const pages = targets.filter(t => t.type === 'page')
const page = pages.find(t => !t.url.startsWith('devtools://')) ?? pages[0]
if (!page) {
  console.error('no page target')
  process.exit(2)
}

const ws = new WebSocket(page.webSocketDebuggerUrl)
const pending = new Map()
let nextId = 1

ws.addEventListener('message', event => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(JSON.stringify(msg.error)))
    else resolve(msg.result)
  }
})

await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})

const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = nextId++
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })

const sleep = ms => new Promise(r => setTimeout(r, ms))

await send('Page.enable')
await send('Runtime.enable')

if (applyMetrics) {
  await send('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: dpr,
    mobile,
  })
}

if (navigate) {
  await send('Page.navigate', { url })
  let ready = false
  for (let i = 0; i < 90; i += 1) {
    await sleep(500)
    const state = await send('Runtime.evaluate', {
      expression: 'document.readyState',
      returnByValue: true,
    })
    if (state.result?.value === 'complete') {
      ready = true
      break
    }
  }
  if (!ready) console.log('warning: document never reported complete')
  await sleep(4000)
}

const layoutExpression = evaluateOnly || `(() => {
  const d = document, de = d.documentElement, b = d.body
  const app = d.getElementById('app') || d.querySelector('#root')
  const first = app && app.firstElementChild
  const r = e => { if (!e) return null; const x = e.getBoundingClientRect();
    return { w: Math.round(x.width), h: Math.round(x.height), t: Math.round(x.top), l: Math.round(x.left) } }
  const heading = [...d.querySelectorAll('*')].find(e => e.children.length === 0 && (e.textContent || '').includes('探索未至之境'))
  let headingInfo = null
  if (heading) {
    const cs = getComputedStyle(heading)
    headingInfo = { rect: r(heading), color: cs.color, opacity: cs.opacity, fontSize: cs.fontSize }
  }
  return JSON.stringify({
    vw: innerWidth, vh: innerHeight, dpr: devicePixelRatio,
    visibility: d.visibilityState,
    docW: de.scrollWidth, docH: de.scrollHeight,
    bodyW: r(b), bodyH: r(b),
    app: r(app), first: r(first),
    heading: headingInfo,
    text: (b ? b.innerText : '').slice(0, 120),
    fontReady: d.fonts ? d.fonts.status : 'n/a',
  })
})()`

const layout = await send('Runtime.evaluate', {
  expression: layoutExpression,
  returnByValue: true,
  // The injected probes sometimes return a promise (fetch to the pet API); the
  // script must wait for it or the value comes back as an empty object.
  awaitPromise: true,
})
console.log('layout:', layout.result?.value, layout.exceptionDetails ? JSON.stringify(layout.exceptionDetails) : '')

if (shotPath) {
  const shot = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
  console.log('screenshot:', shotPath)
}

ws.close()
