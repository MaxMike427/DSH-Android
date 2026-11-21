/** Hold a touch on the live overlay pet through CDP. */
import { connectPet, sleep, stateExpression } from './pet-cdp.mjs'

const page = await connectPet()
try {
  const state = await page.evaluate(stateExpression)
  const point = {
    x: Number(process.argv[2] ?? state.hit.x + state.hit.w / 2),
    y: Number(process.argv[3] ?? state.hit.y + state.hit.h / 2),
    radiusX: 6, radiusY: 6, force: 1, id: 1,
  }
  await page.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] })
  await sleep(1200)
  await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await sleep(300)
  console.log(await page.evaluate(`({
    visibleColumns: [...document.querySelectorAll('.dsh-pet-menu-column')]
      .filter(el => el.getBoundingClientRect().width > 0).length
  })`))
} finally {
  page.close()
}
