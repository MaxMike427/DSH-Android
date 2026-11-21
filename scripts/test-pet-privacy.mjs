/** A long-press menu must not reveal the harness workspace behind the pet. */
import assert from 'node:assert/strict'
import { adb, connectPet, sleep, stateExpression, windowBounds } from './pet-cdp.mjs'

const page = await connectPet()
const forbidden = /工作区|主工作区|workspace|探索未至之境|新建会话|新建对话/i
const visible = `el => {
  const r = el.getBoundingClientRect()
  if (r.width <= 0 || r.height <= 0 || getComputedStyle(el).visibility !== 'visible') return false
  for (let node = el; node && node !== document.documentElement; node = node.parentElement) {
    const s = getComputedStyle(node)
    if (s.display === 'none' || Number(s.opacity) === 0) return false
  }
  return true
}`
const inspect = `(() => {
  const isVisible = ${visible}
  const keep = '.dsh-pet-root,.dsh-pet-menu,.dsh-pet-chat,.dsh-pet-todo,.dsh-pet-score,.dsh-pet-score-burst,[data-shortcut-modal="settings"]'
  const outside = [...document.body.children].filter(el => !el.matches(keep) && isVisible(el))
  const text = [...document.querySelectorAll('body *')].filter(el => isVisible(el) &&
    el.children.length === 0 && (el.textContent || '').trim()).map(el => el.textContent.trim()).slice(0, 200)
  return { outside: outside.map(el => ({ tag: el.tagName, class: el.className, text: (el.innerText || '').slice(0, 100) })), text }
})()`

try {
  const state = await page.evaluate(stateExpression)
  const d = await page.description()
  if (windowBounds().w < 600) {
    const x = Math.round(d.screenX + (state.hit.x + state.hit.w / 2) * state.dpr)
    const y = Math.round(d.screenY + (state.hit.y + state.hit.h / 2) * state.dpr)
    adb('shell', 'input', 'swipe', String(x), String(y), String(x), String(y), '900')
    await sleep(400)
  }
  const result = await page.evaluate(inspect)
  console.log(JSON.stringify(result))
  const badOutside = result.outside.filter(node => forbidden.test(`${node.class} ${node.text}`))
  const badText = result.text.filter(text => forbidden.test(text))
  assert.deepEqual(badOutside, [], 'workspace DOM is visible outside the pet/menu portals')
  assert.deepEqual(badText, [], 'workspace text is visible during the desktop menu')
  console.log('PASS: desktop long-press exposes only the pet and its menu')
} finally {
  await page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape", bubbles:true}))')
  page.close()
}
