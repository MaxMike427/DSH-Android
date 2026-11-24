/** Fresh desktop WebViews must not expose app onboarding or other app modals. */
import assert from 'node:assert/strict'
import { adb, connectPet, sleep, windowBounds } from './pet-cdp.mjs'

const inspect = `(() => {
  const visible=e=>{
    if(!e)return false
    const r=e.getBoundingClientRect()
    if(!r.width||!r.height)return false
    for(let p=e;p;p=p.parentElement){const s=getComputedStyle(p)
      if(s.display==='none'||s.visibility!=='visible'||Number(s.opacity)===0)return false}
    return true
  }
  const dialogs=[...document.querySelectorAll('[aria-modal="true"]:not([data-shortcut-modal])')]
  return {
    pet:visible(document.querySelector('.dsh-pet-hit')),
    onboarding:dialogs.filter(e=>e.textContent.includes('开始使用')).length,
    leaked:dialogs.filter(e=>!e.querySelector('[class^="dsh-pet-"],[class*=" dsh-pet-"]')&&visible(e))
      .map(e=>({cls:e.className,onboarding:e.textContent.includes('开始使用')}))
  }
})()`

for (let round = 1; round <= 3; round++) {
  adb('shell', 'am', 'start', '-n', 'com.dshapp/com.dsh.android.MainActivity')
  let main
  try {
    // An APK replacement restarts the runtime; don't background its startup or
    // mistake a page that hasn't loaded for a successful isolation result.
    for (let i = 0; i < 180; i++) {
      try { main = await connectPet(true, true) } catch { await sleep(500); continue }
      if (await main.evaluate('!!document.querySelector(".dsh-pet-hit")')) break
      main.close()
      main = null
      await sleep(500)
    }
    assert.ok(main && await main.evaluate('!!document.querySelector(".dsh-pet-hit")'), 'in-app pet did not load')
  } finally {
    main?.close()
  }
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
  let page
  try {
    for (let i = 0; i < 80; i++) {
      try { page = await connectPet(false, true); break } catch { await sleep(150) }
    }
    assert.ok(page, 'desktop WebView did not start')
    for (let i = 0; i < 80; i++) {
      if (await page.evaluate('!!document.querySelector(".dsh-pet-hit")')) break
      await sleep(150)
    }
    await sleep(900)
    const state = await page.evaluate(inspect)
    console.log(JSON.stringify({ round, ...state, window: windowBounds() }))
    assert.ok(state.pet, 'desktop pet did not load')
    assert.deepEqual(state.leaked, [], 'desktop pet overlay leaked an app modal')
    assert.ok(windowBounds().w < 700, 'an unrelated app modal expanded the pet window')
  } finally {
    page?.close()
  }
}
console.log('PASS: three fresh desktop WebViews isolate app onboarding without changing account state')
