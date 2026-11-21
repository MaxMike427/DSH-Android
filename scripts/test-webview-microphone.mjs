/** Acquire/release the real microphone in a WebView; never persist any audio. */
import assert from 'node:assert/strict'
import { connectPet } from './pet-cdp.mjs'

const page = await connectPet(!process.argv.includes('--overlay'))
try {
  const results = await page.evaluate(`(async () => {
    const results = []
    for (const constraints of [{audio:true}, {audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}}]) {
      let stream
      try {
        stream = await navigator.mediaDevices.getUserMedia(constraints)
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
        stream?.getTracks().forEach(track => track.stop())
      }
    }
    return { visibility:document.visibilityState,secure:isSecureContext,results }
  })()`)
  console.log(JSON.stringify(results, null, 2))
  assert.ok(results.results.every(result => result.ok), 'WebView could not start the microphone')
} finally {
  page.close()
}
