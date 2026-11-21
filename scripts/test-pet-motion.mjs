/** Hardware samples must reach a backgrounded overlay; its real physics must react. */
import assert from 'node:assert/strict'
import { connectPet, sleep, stateExpression } from './pet-cdp.mjs'

const page = await connectPet()
try {
  const first = await page.evaluate(stateExpression)
  await sleep(1000)
  const second = await page.evaluate(stateExpression)
  assert.ok(second.motionSamples > first.motionSamples + 5, 'background Android sensor samples are not reaching the pet')
  console.log(`PASS: ${second.motionSamples - first.motionSamples} native sensor samples in 1s`)
  // The hardware path above is real. A deterministic high-acceleration sample
  // checks the receiver -> native plugin physics -> window-report path without
  // asking a human to shake the attached phone on every regression run.
  const before = await page.evaluate(stateExpression)
  await page.evaluate(`window.__dshPetOnly.receiveMotion({alpha:0,beta:null,gamma:null,
    acceleration:{x:40,y:25,z:0},gravity:{x:0,y:0,z:9.8},
    rotationRate:{alpha:0,beta:0,gamma:0},interval:33})`)
  await sleep(100)
  const after = await page.evaluate(stateExpression)
  assert.ok(Math.hypot(after.hit.x - before.hit.x, after.hit.y - before.hit.y) > 1,
    'shake did not move the native pet (check plugin shakeThrow is enabled)')
  console.log('PASS: shake uses the plugin physics and moves the pet without losing it')
} finally {
  page.close()
}
