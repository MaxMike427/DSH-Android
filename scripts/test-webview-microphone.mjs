/** Acquire/release the real microphone in a WebView; never persist any audio. */
import assert from 'node:assert/strict'
import { connectPet } from './pet-cdp.mjs'

const page = await connectPet(!process.argv.includes('--overlay'), true)
let timer
try {
  assert.ok((await page.description()).visible, 'bring the tested WebView to the foreground before recording')
  const results = await Promise.race([page.evaluate(`(async () => {
    if (document.visibilityState !== 'visible') throw new Error('tested page is hidden')
    const activeTracks = new Set()
    let cancelled = false
    window.__dshMicTestCleanup = () => {
      cancelled = true
      activeTracks.forEach(track => track.stop())
      activeTracks.clear()
    }
    const results = []
    for (const constraints of [{audio:true}, {audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}}]) {
      let stream
      try {
        stream = await navigator.mediaDevices.getUserMedia(constraints)
        stream.getTracks().forEach(track => activeTracks.add(track))
        if (cancelled) {
          stream.getTracks().forEach(track => track.stop())
          throw new Error('test was cancelled')
        }
        const track = stream.getAudioTracks()[0]
        const recorder = new MediaRecorder(stream)
        let bytes = 0
        recorder.addEventListener('dataavailable', event => { bytes += event.data.size })
        const stopped = new Promise((resolve, reject) => {
          recorder.addEventListener('stop', resolve, {once:true})
          recorder.addEventListener('error', reject, {once:true})
        })
        recorder.start(100)
        await new Promise(resolve => setTimeout(resolve, 450))
        recorder.stop()
        await stopped
        results.push({ok:track.readyState==='live' && bytes>0,state:recorder.state,
          sampleRate:track.getSettings().sampleRate,bytes})
      } catch (error) {
        results.push({ok:false,name:error.name,message:error.message})
      } finally {
        stream?.getTracks().forEach(track => { track.stop(); activeTracks.delete(track) })
      }
      if (cancelled) break
    }
    window.__dshMicTestCleanup()
    delete window.__dshMicTestCleanup
    return { visibility:document.visibilityState,secure:isSecureContext,results }
  })()`), new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('microphone test timed out after 15s')), 15000)
  })])
  console.log(JSON.stringify(results, null, 2))
  assert.ok(results.results.every(result => result.ok), 'WebView could not start the microphone')
} finally {
  clearTimeout(timer)
  await Promise.race([
    page.evaluate('window.__dshMicTestCleanup?.()'),
    new Promise(resolve => { const cleanupTimer = setTimeout(resolve, 1000); cleanupTimer.unref() }),
  ])
  page.close()
}
