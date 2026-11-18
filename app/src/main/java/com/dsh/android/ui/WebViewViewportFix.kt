package com.dsh.android.ui

/**
 * Script injected into the harness page to repair the layout viewport.
 *
 * The problem it fixes, measured on the test device: inside this app's WebView
 * every viewport unit and percentage height resolves to `0`, while
 * `window.innerHeight` still reports the real height (872). The harness shell is
 * laid out as `html, body, #root { height: 100% }`, so all three boxes collapse
 * to zero height. The content is still in the DOM — it is pushed above the
 * visible area — and the user sees only the page background.
 *
 * The defect is WebView-wide, not page-specific: a `data:` URL with a single
 * `height: 100vh` element also measures 0 in the same WebView. Giving `html` an
 * explicit pixel height restored the complete UI on the device.
 *
 * What the script does:
 *  - declare a CSS variable holding `window.innerHeight` in pixels;
 *  - point `html`, `body` and `#root` at that variable with `!important`, so the
 *    page's own `height: 100%` rules cannot win;
 *  - re-read the height on `resize` and `orientationchange`;
 *  - wait for a document when called before the page has one, because
 *    `onPageStarted` can fire while `document.head` does not exist yet.
 *
 * A variable rather than a height written on each element: the page re-mounts
 * `#root` children at any time, and a stylesheet keeps applying without touching
 * the DOM. The `__dshViewportFix` guard makes repeated injections harmless.
 */
val VIEWPORT_FIX_SCRIPT: String = """
(function () {
  if (window.__dshViewportFix) {
    // Already installed; just refresh the measured height.
    window.__dshViewportFix.apply();
    return;
  }

  function install() {
    if (!document.head && !document.documentElement) return false;

    var style = document.createElement('style');
    style.id = 'dsh-viewport-fix';
    style.textContent =
      ':root{--dsh-viewport-height:0px}' +
      // The shell is laid out with percentage heights, which resolve against a
      // zero-height containing block on this WebView.
      'html,body,#root{height:var(--dsh-viewport-height)!important;' +
      'min-height:var(--dsh-viewport-height)!important}' +
      // Any element sized from viewport units collapses here, because this
      // WebView reports them as 0. `--dsh-viewport-height` carries the real
      // height, so these rules restate the same sizing against it.
      // Dialogs first: the workspace picker asks for
      // `height: min(500px, 100dvh - 32px)`, which is negative (and therefore
      // clamped to 0) when `100dvh` reads as 0.
      '[role="dialog"]{height:auto!important;' +
      'max-height:var(--dsh-viewport-height)!important;' +
      'min-height:0!important}' +
      '[role="dialog"],[role="menu"],[role="listbox"]{' +
      'overflow-y:auto}';
    (document.head || document.documentElement).appendChild(style);

    function apply() {
      var height = window.innerHeight || 0;
      if (height > 0) {
        document.documentElement.style.setProperty('--dsh-viewport-height', height + 'px');
      }
    }

    apply();
    window.addEventListener('resize', apply, { passive: true });
    window.addEventListener('orientationchange', apply, { passive: true });

    window.__dshViewportFix = { apply: apply };
    return true;
  }

  if (!install()) {
    // The document does not exist yet: retry on the next frames until it does.
    var tries = 0;
    var timer = setInterval(function () {
      tries += 1;
      if (install() || tries > 200) clearInterval(timer);
    }, 10);
  }
})();
""".trimIndent()

/**
 * Diagnostic snapshot of the live layout, logged after each page load.
 *
 * The failure it describes is silent: the DOM completes and the document reports
 * a size, yet nothing paints. These numbers separate "the viewport itself is
 * broken" (`vhUnit` near 0 while `vh` is not) from "the page boxes collapsed"
 * (`bodyH`/`appH` zero), and `fix` records whether the injected stylesheet is in
 * effect.
 */
val LAYOUT_PROBE_SCRIPT: String = """
(function () {
  var d = document;
  var de = d.documentElement;
  var b = d.body;
  if (!b) return 'no body yet';
  var app = d.getElementById('app') || d.querySelector('#root');
  var probe = d.createElement('div');
  probe.style.cssText = 'position:fixed;width:1px;height:100vh;';
  b.appendChild(probe);
  var vhUnit = Math.round(probe.getBoundingClientRect().height);
  probe.remove();
  function size(e) {
    if (!e) return -1;
    return Math.round(e.getBoundingClientRect().height);
  }
  return JSON.stringify({
    vw: innerWidth,
    vh: innerHeight,
    vhUnit: vhUnit,
    dpr: devicePixelRatio,
    docH: de.scrollHeight,
    bodyH: size(b),
    appH: size(app),
    fix: !!window.__dshViewportFix
  });
})();
""".trimIndent()
