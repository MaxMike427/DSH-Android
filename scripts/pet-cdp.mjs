/** Shared, read-only connection/geometry helpers for pet device regressions. */
import { execFileSync } from 'node:child_process'

export const adb = (...args) => execFileSync('adb', args, { encoding: 'utf8' }).trim()
export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

export async function connectPet(main = false, visibleOnly = false) {
  const socket = adb('shell', 'cat /proc/net/unix').match(/webview_devtools_remote_\d+/)?.[0]
  if (!socket) throw new Error('no debug WebView')
  adb('forward', 'tcp:9222', `localabstract:${socket}`)
  const targets = await (await fetch('http://127.0.0.1:9222/json/list')).json()
  for (const page of targets.filter(t => t.type === 'page')) {
    const ws = new WebSocket(page.webSocketDebuggerUrl)
    const pending = new Map()
    let nextId = 1
    ws.addEventListener('message', event => {
      const m = JSON.parse(event.data)
      if (!pending.has(m.id)) return
      const { resolve, reject } = pending.get(m.id)
      pending.delete(m.id)
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
    })
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true })
      ws.addEventListener('error', reject, { once: true })
    })
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = nextId++
      pending.set(id, { resolve, reject })
      ws.send(JSON.stringify({ id, method, params }))
    })
    const evaluate = async expression => {
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
      return r.result.value
    }
    const bridge = await evaluate('typeof window.DshPetHost !== "undefined"')
    const description = JSON.parse(page.description || '{}')
    // Both WebViews use a screen-sized viewport. Geometry cannot distinguish
    // them; the host bridge identifies the overlay. Hidden main pages remain
    // useful as menu references, but media acquisition needs a visible page.
    if ((main ? !bridge : bridge) && (!visibleOnly || description.visible)) {
      return {
        evaluate, send, close: () => ws.close(),
        description: async () => {
          const list = await (await fetch('http://127.0.0.1:9222/json/list')).json()
          return JSON.parse(list.find(t => t.id === page.id)?.description || '{}')
        },
      }
    }
    ws.close()
  }
  throw new Error(main ? 'no in-app page' : 'background DSH first: no overlay page')
}

export function windowBounds() {
  const dump = adb('shell', 'dumpsys window windows')
  const block = dump.split(/\r?\n(?=\s*Window #\d+ Window\{)/)
    .find(b => b.includes('package=com.dshapp appop=SYSTEM_ALERT_WINDOW'))
  const r = /\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(block || '')
  if (!r) throw new Error('no floating Android window')
  return { x: Number(r[1]), y: Number(r[2]), w: Number(r[3]) - Number(r[1]), h: Number(r[4]) - Number(r[2]) }
}

export const stateExpression = `(() => {
  const box = el => {
    if (!el) return null
    const r = el.getBoundingClientRect(), s = getComputedStyle(el)
    return { x: r.x, y: r.y, w: r.width, h: r.height,
      display: s.display, visibility: s.visibility, opacity: s.opacity }
  }
  const pet = document.querySelector('.dsh-pet-root')
  return { vw: innerWidth, vh: innerHeight, dpr: devicePixelRatio,
    pet: box(pet), hit: box(document.querySelector('.dsh-pet-hit')),
    motionSamples: window.__dshPetOnly?.motionSamples || 0,
    media: [...(pet?.querySelectorAll('video,canvas,img') || [])].map(box) }
})()`
