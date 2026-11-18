package com.dsh.android.ui

/**
 * Scripts injected into the harness page to repair layout that this WebView
 * gets wrong.
 *
 * ## The defect
 *
 * Measured on the test device (Redmi Note 12 Turbo, Android 16, WebView
 * 156.0.8078.47): inside this app's WebView every viewport unit resolves to `0`
 * while `window.innerHeight` reports the real height (872).
 *
 * ```
 * innerHeight = 872
 * 100vh       = 0      <- a <div style="height:100vh"> measures 0
 * body height = 0      <- html, body, #root { height: 100% } all collapse
 * ```
 *
 * It is a property of the WebView, not of the page: a minimal `data:` URL with a
 * single `height:100vh` element measures 0 in the same WebView, and the same page
 * renders correctly in desktop Chrome at this device's exact metrics. A real
 * orientation change does not repair it either, so the page cannot recover on
 * its own.
 *
 * ## The repair
 *
 * Two independent problems, two rules:
 *
 * 1. **Percentage heights.** `html`, `body` and `#root` are sized `height:100%`,
 *    which resolves against a zero-height containing block. They are pointed at
 *    `--dsh-viewport-height`, kept in sync with `window.innerHeight`.
 *
 * 2. **Viewport units.** The page's own stylesheets size things like
 *    `max-height: calc(100dvh - 24px)` and `height: min(500px, 100dvh - 32px)`.
 *    With `100dvh` reading 0 the first becomes negative and clamps to 0 (menus
 *    and popovers collapse to an invisible sliver); the second also clamps to 0
 *    (the workspace picker and dialogs open empty).
 *
 *    Rewriting each rule to a pixel value would go stale on rotation, so the
 *    units are rewritten to `calc(<n> * var(--dsh-vh))` once and the variable
 *    alone is updated. Every dependent rule then follows automatically.
 *
 * Both are idempotent and survive re-injection; the guards make the second call
 * a no-op refresh.
 */

/**
 * Rewrites viewport units in the page's stylesheets and pins the shell heights.
 *
 * Runs from `onPageStarted` (as soon as a document exists) and again from
 * `onPageFinished` (to cover markup that replaced `#root` while loading).
 */
val VIEWPORT_FIX_SCRIPT: String = """
(function () {
  if (window.__dshViewportFix) {
    // Already installed for this document: refresh the measured sizes and pick
    // up any stylesheet the app added since the last pass.
    window.__dshViewportFix.apply();
    window.__dshViewportFix.rewrite();
    return;
  }

  var HEIGHT_UNIT = /(-?\d*\.?\d+)(dvh|svh|lvh|vh)\b/g;
  var WIDTH_UNIT  = /(-?\d*\.?\d+)(dvw|svw|lvw|vw)\b/g;

  function apply() {
    var root = document.documentElement;
    root.style.setProperty('--dsh-viewport-height', (window.innerHeight || 0) + 'px');
    root.style.setProperty('--dsh-vh', ((window.innerHeight || 0) / 100) + 'px');
    root.style.setProperty('--dsh-vw', ((window.innerWidth || 0) / 100) + 'px');
  }

  function rewriteDeclaration(style) {
    for (var i = style.length - 1; i >= 0; i--) {
      var property = style[i];
      var value = style.getPropertyValue(property);
      if (!value || value.indexOf('v') === -1) continue;
      var next = value
        .replace(HEIGHT_UNIT, 'calc($1 * var(--dsh-vh))')
        .replace(WIDTH_UNIT, 'calc($1 * var(--dsh-vw))');
      if (next !== value) {
        style.setProperty(property, next, style.getPropertyPriority(property));
      }
    }
  }

  function rewriteRules(rules) {
    for (var i = 0; i < rules.length; i++) {
      var rule = rules[i];
      // Grouping rules (@media, @supports, @layer) expose cssRules; their
      // conditions are left alone because var() is not allowed in a query.
      if (rule.cssRules && !rule.selectorText) {
        rewriteRules(rule.cssRules);
        continue;
      }
      if (rule.style) rewriteDeclaration(rule.style);
    }
  }

  function rewrite() {
    var sheets = document.styleSheets;
    for (var i = 0; i < sheets.length; i++) {
      // Cross-origin sheets throw on cssRules; the harness serves its own.
      var rules;
      try { rules = sheets[i].cssRules; } catch (e) { continue; }
      if (rules) rewriteRules(rules);
    }
    var styled = document.querySelectorAll('[style]');
    for (var n = 0; n < styled.length; n++) rewriteDeclaration(styled[n].style);
  }

  function install() {
    if (!document.head && !document.documentElement) return false;

    var style = document.createElement('style');
    style.id = 'dsh-viewport-fix';
    // Percentage heights only: viewport units are handled by the rewrite above.
    style.textContent =
      ':root{--dsh-viewport-height:0px;--dsh-vh:0px;--dsh-vw:0px}' +
      'html,body,#root{height:var(--dsh-viewport-height)!important;' +
      'min-height:var(--dsh-viewport-height)!important}';
    (document.head || document.documentElement).appendChild(style);

    apply();
    rewrite();

    window.addEventListener('resize', apply, { passive: true });
    window.addEventListener('orientationchange', apply, { passive: true });

    window.__dshViewportFix = { apply: apply, rewrite: rewrite };
    return true;
  }

  if (!install()) {
    // `onPageStarted` can fire before the document exists; retry until it does.
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
 * a size, yet nothing paints. These numbers separate "the viewport units are
 * broken" (`vhUnit` near 0 while `vh` is not) from "the page boxes collapsed"
 * (`bodyH`/`appH` zero).
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
