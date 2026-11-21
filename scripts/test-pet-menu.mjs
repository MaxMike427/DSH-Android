/** Compare the entire native menu with the in-app menu, then exercise touch submenus. */
import assert from 'node:assert/strict'
import { adb, connectPet, sleep, stateExpression, windowBounds } from './pet-cdp.mjs'

const snapshot = `(() => {
  const root = document.querySelector('.dsh-pet-menu')
  const columns = [...(root?.querySelectorAll('.dsh-pet-menu-column') || [])]
  const label = el => el.querySelector('.dsh-pet-menu-label')?.textContent || ''
  const visible = el => getComputedStyle(el).display !== 'none' && el.getBoundingClientRect().width > 0
  return { labels: [...(root?.querySelectorAll('.dsh-pet-menu-label') || [])].map(e => e.textContent),
    visible: columns.filter(visible).map(column => ({
      width: column.getBoundingClientRect().width, height: column.getBoundingClientRect().height,
      labels: [...column.children].map(label),
    })) }
})()`
const contextMenu = `(() => {
  const hit = document.querySelector('.dsh-pet-hit'), r = hit.getBoundingClientRect()
  hit.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true,
    button: 2, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, view: window }))
})()`
const dismiss = `document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`

const reference = await connectPet(true)
await reference.evaluate(contextMenu)
const expected = await reference.evaluate(snapshot)
await reference.evaluate(dismiss)
reference.close()
assert.ok(expected.labels.length > 50, 'in-app reference menu is missing')

const page = await connectPet()
const tapLabel = async text => {
  const box = await page.evaluate(`(() => {
    const label = [...document.querySelectorAll('.dsh-pet-menu-label')]
      .find(el => el.textContent === ${JSON.stringify(text)} && el.getBoundingClientRect().width > 0)
    if (!label) throw new Error('visible menu label not found')
    const r = label.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, dpr: devicePixelRatio }
  })()`)
  const d = await page.description()
  adb('shell', 'input', 'tap', String(Math.round(d.screenX + box.x * box.dpr)),
    String(Math.round(d.screenY + box.y * box.dpr)))
  await sleep(250)
}
try {
  const s = await page.evaluate(stateExpression), d = await page.description()
  const x = Math.round(d.screenX + (s.hit.x + s.hit.w / 2) * s.dpr)
  const y = Math.round(d.screenY + (s.hit.y + s.hit.h / 2) * s.dpr)
  adb('shell', 'input', 'swipe', String(x), String(y), String(x), String(y), '900')
  await sleep(400)
  const actual = await page.evaluate(snapshot)
  assert.ok(actual.visible.length > 0, 'native overlay menu is hidden')
  assert.deepEqual(actual.labels, expected.labels, 'overlay menu lost in-app labels or branches')
  assert.ok(Math.abs(actual.visible[0].width - expected.visible[0].width) < 2, 'menu is larger than in app')
  console.log(`PASS: all ${actual.labels.length} labels match, menu width ${actual.visible[0].width.toFixed(1)} CSS px`)
  await tapLabel('动作')
  await tapLabel('小动作')
  assert.ok((await page.evaluate(snapshot)).visible.some(c => c.labels.includes('超大伸懒腰')), 'nested action menu is inaccessible by touch')
  await tapLabel('超大伸懒腰')
  await sleep(400)
  assert.equal((await page.evaluate(snapshot)).visible.length, 0, 'menu did not close after action')
  const w = windowBounds()
  assert.ok(w.w < 700, 'fullscreen window did not shrink after action')
  console.log('PASS: touch opens two submenu levels, runs an action, then restores the small window')

  await page.evaluate(contextMenu)
  await sleep(250)
  await tapLabel('对话')
  assert.ok(await page.evaluate('!!document.querySelector(".dsh-pet-chat textarea")'), 'native chat panel did not open')
  assert.ok(windowBounds().w > 700, 'chat window was clipped to the pet')
  await page.evaluate(dismiss)
  await sleep(400)
  assert.ok(windowBounds().w < 700, 'window did not shrink after chat closed')
  console.log('PASS: native chat opens on the desktop and closes without leaving a fullscreen window')

  await page.evaluate(contextMenu)
  await sleep(250)
  await tapLabel('待办事项')
  await sleep(400)
  assert.ok(await page.evaluate('!!document.querySelector(".dsh-pet-todo-close")'), 'native todo panel did not open')
  assert.ok(windowBounds().w > 700, 'todo window was clipped to the pet')
  await page.evaluate('document.querySelector(".dsh-pet-todo-close").click()')
  await sleep(400)
  assert.ok(windowBounds().w < 700, 'invisible todo ancestor kept the fullscreen window open')
  console.log('PASS: native todo panel closes and returns touches to the app underneath')
} finally {
  await page.evaluate(dismiss)
  page.close()
}
