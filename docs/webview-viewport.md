# The harness Web UI renders as an empty background

## Symptom

On the target device the app started, the runtime came up and `dsh web` served
the page, but the WebView painted nothing except the page background colour
(`rgb(21, 21, 23)`). Logs showed a complete document:

```
dom: bodyLen 171337, text "工作区\n探索未至之境\n预览版\n选择工作区..."
```

A pixel analysis of the screenshot put every non-background pixel in a 10px band
at the bottom of the screen — and that band turned out to be the Android gesture
navigation bar, not page content. The page had painted nothing at all.

## Diagnosis

WebView DevTools made the numbers readable without a custom build: the debug APK
exposes `webview_devtools_remote_<pid>`, so `adb forward` plus a short CDP script
can query the live document.

```powershell
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
node scripts/cdp-probe.mjs --cdp http://127.0.0.1:9222 --nav false
```

The first measurement on the device:

```json
{ "vw": 392, "vh": 872, "vhUnit": 0, "docH": 872,
  "bodyW": 393, "bodyH": 0, "appH": 0 }
```

`window.innerHeight` reports 872, but `body` and `#root` are 0 tall, and an
element sized `height: 100vh` measures 0 as well. The document is there; its
boxes collapse, so everything is laid out above the viewport.

The defect is a property of the WebView, not of the page:

- a `data:` URL holding one `height: 100vh` element also measures 0 in the same
  WebView;
- the page's `html, body, #root { height: 100% }` rule is present and does match
  (verified by walking `document.styleSheets`), it just resolves against a
  zero-height containing block;
- forcing `html { height: 900px !important }` immediately restored the complete
  UI, which pinned the cause to the viewport rather than to the page's own CSS;
- reloading after the view had a size stayed broken, so the first load is not
  what breaks it and deferring `loadUrl` does not help.

The same page renders correctly in desktop Chrome at the device's exact metrics
(393x873 at dpr 2.75), which ruled out the page itself.

## Fix

`WebViewViewportFix.kt` injects a stylesheet from `onPageStarted` (and again from
`onPageFinished`), and keeps it current on resize:

```css
:root { --dsh-viewport-height: 0px }        /* set to innerHeight in px by JS */
html, body, #root {
  height: var(--dsh-viewport-height) !important;
  min-height: var(--dsh-viewport-height) !important;
}
[role="dialog"] { height: auto !important;
  max-height: var(--dsh-viewport-height) !important; min-height: 0 !important }
```

The dialog rule exists because the workspace picker sizes itself with
`height: min(500px, 100dvh - 32px)`. With `100dvh` reading as 0 that expression
goes negative and clamps to 0, so the picker opened as an invisible sliver.

## Verification

On the device, after the fix:

```
layout: {"vw":392,"vh":872,"vhUnit":0,"docH":872,"bodyH":872,"appH":872,"fix":true}
```

- the harness hero, workspace chip and composer render on a cold start;
- the "开始使用" dialog renders at full size;
- tapping the workspace chip opens the picker at 229px with its folder UI,
  "主目录" path, and 新建文件夹 / 显示隐藏文件 / 取消 / 打开 controls;
- choosing a folder updates the chip to that workspace and enables the composer.

`vhUnit` stays 0 in the probe output. That is expected: it reports the WebView's
own viewport-unit defect, which the injected stylesheet works around rather than
repairs.

## Regression check

`scripts/cdp-probe.mjs` drives any Chrome DevTools endpoint. With a debug APK
installed, `scripts/device-webview-probe.ps1` attaches to the running WebView and
prints the same numbers, so a future regression shows up as `bodyH: 0` instead of
as a black screen.
