/** The pet must stay anchored while a long-press menu is open, including hand motion. */
import assert from 'node:assert/strict'
import { adb, connectPet, sleep, stateExpression } from './pet-cdp.mjs'

const page = await connectPet()
const dismiss = 'document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape", bubbles:true}))'
let touchStarted = false
const physicalCenter = async () => {
  const s = await page.evaluate(stateExpression), d = await page.description()
  return {x:d.screenX+(s.hit.x+s.hit.w/2)*s.dpr,y:d.screenY+(s.hit.y+s.hit.h/2)*s.dpr}
}
try {
  await page.evaluate(dismiss)
  await sleep(250)
  if (process.argv.includes('--edge')) {
    const center = await physicalCenter()
    adb('shell', 'input', 'swipe', String(Math.round(center.x)), String(Math.round(center.y)), '45', '2210', '1200')
    await sleep(700)
  }
  const state = await page.evaluate(stateExpression)
  const point = { x: state.hit.x + state.hit.w / 2, y: state.hit.y + state.hit.h / 2,
    radiusX: 6, radiusY: 6, force: 1, id: 1 }
  await page.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] })
  touchStarted = true
  await sleep(200)
  const beforeMenu = await physicalCenter()
  await sleep(500)
  assert.ok(await page.evaluate(`!![...document.querySelectorAll('.dsh-pet-menu-column')]
    .find(el => el.getBoundingClientRect().width > 0)`), 'long press did not actually open the native menu')
  const afterMenu = await physicalCenter()
  const openingMovement = Math.hypot(afterMenu.x-beforeMenu.x,afterMenu.y-beforeMenu.y)
  const result = await page.evaluate(`(async () => {
    const api = window.__dshPetOnly
    const read = () => { const r=document.querySelector('.dsh-pet-hit').getBoundingClientRect(); return {x:r.x,y:r.y} }
    const before = read(), points = []
    // A long press makes the plugin stop its flight. Continuing device input
    // must not restart it while the finger is still choosing a menu action.
    for (let i=0;i<8;i++) {
      api.receiveMotion({alpha:0,beta:25,gamma:15,acceleration:{x:24,y:8,z:0},
        gravity:{x:0,y:0,z:9.8},rotationRate:{alpha:0,beta:0,gamma:0},interval:20})
      await new Promise(resolve => setTimeout(resolve, 20))
      points.push(read())
    }
    return {before,points,maxMovement:Math.max(...points.map(p=>Math.hypot(p.x-before.x,p.y-before.y)))}
  })()`)
  console.log(JSON.stringify({beforeMenu,afterMenu,openingMovementPx:openingMovement,handMotionMovementCssPx:result.maxMovement}))
  assert.ok(openingMovement < 1.5, 'expanding the menu jumps the pet on screen')
  assert.ok(result.maxMovement < 2, 'pet jumps while long-press menu is open')
  console.log('PASS: hand motion cannot move the pet while its menu is open')
} finally {
  if (touchStarted) await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await page.evaluate(dismiss)
  page.close()
}
