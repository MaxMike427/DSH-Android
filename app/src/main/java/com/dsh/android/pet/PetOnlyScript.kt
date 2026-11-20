package com.dsh.android.pet

/**
 * Turns the harness page into the desktop pet, and keeps it interactive.
 *
 * ## What it does
 *
 * The pet is one element, `.dsh-pet-root`, nested inside the app shell:
 *
 * ```
 * body > div#root > div > div.frame > div.overlayLayer > div > div.dsh-pet-root
 * ```
 *
 * Everything else on the page is chrome the floating window must not show. The
 * script works outwards from the pet instead of inwards from the body: for every
 * ancestor it hides the siblings, clears the background and pins the box to the
 * viewport, so the only thing that can paint is the pet.
 *
 * ## Why the hit box, not the root
 *
 * The root is the whole animation stage (462x260 at its default size), but the
 * character only occupies `.dsh-pet-hit` — measured at 78x93 CSS px. Sizing the
 * window to the root would block 6x more of the screen than the pet covers, and
 * every one of those pixels would steal touches from the app underneath. The
 * root is therefore offset so the hit box sits exactly at the window origin, and
 * the host is told that size so it can size the window to match.
 *
 * Nothing here touches `pointer-events`: the plugin already sets
 * `pointer-events: none` on the root and `auto` on `.dsh-pet-hit`, which is
 * exactly the split needed for a pet that is clickable while its transparent
 * surroundings are not.
 *
 * ## Why it is a resident script
 *
 * The plugin re-renders: it remounts on state changes and swaps nodes as the
 * video changes. A one-shot pass would be undone by the first re-render, so the
 * rules are installed once and re-applied from a `MutationObserver`. All of it is
 * idempotent and guarded by a window flag, so both injection points
 * (`onPageStarted`, `onPageFinished`) can call it.
 */
internal val PET_ONLY_SCRIPT: String = """
(function () {
  var HOST = window.DshPetHost || null;
  var PET = '.dsh-pet-root';
  var HIT = '.dsh-pet-hit';

  // The plugin positions the pet itself (roaming, dragging, throwing). Those
  // writes are inline styles without !important, and every rule below is
  // !important, so the pet stays where the host wants it while still running
  // its own animation and click handling.
  var offsetX = 0;
  var offsetY = 0;
  var natural = null;      // { dx, dy, w, h } — measured once, before scaling
  var fixedScale = null;   // width-fitted scale, fixed so a resize keeps the size
  // Viewport width at the moment `natural` was measured. The plugin sizes the
  // pet from the viewport, so the two must come from the same layout pass or the
  // scale is derived from mismatched numbers (capturing this at script load was
  // wrong: the first pass can run before the page has laid out).
  var referenceWidth = 0;
  var reported = false;

  function ensureStyle() {
    if (document.getElementById('dsh-pet-only-style')) return;
    var style = document.createElement('style');
    style.id = 'dsh-pet-only-style';
    // A WebView only shows what it paints, so the page has to be transparent or
    // the overlay would be a dark rectangle over the app behind it.
    style.textContent =
      'html,body{background:transparent!important;overflow:hidden!important}' +
      'body::before,body::after{display:none!important}';
    (document.head || document.documentElement).appendChild(style);
  }

  function hideSiblings(node) {
    var parent = node.parentElement;
    if (!parent) return;
    for (var i = 0; i < parent.children.length; i++) {
      var child = parent.children[i];
      if (child === node) continue;
      if (child.tagName === 'SCRIPT' || child.tagName === 'STYLE') continue;
      if (child.style.display !== 'none') child.style.display = 'none';
    }
  }

  function clearBox(el) {
    var s = el.style;
    s.setProperty('background', 'transparent', 'important');
    s.setProperty('background-color', 'transparent', 'important');
    s.setProperty('box-shadow', 'none', 'important');
    s.setProperty('border', '0', 'important');
    s.setProperty('backdrop-filter', 'none', 'important');
  }

  /**
   * Where the character sits inside its own stage, in CSS pixels.
   *
   * Measured from the rendered boxes rather than recomputed from the plugin's
   * constants: the stage size is user-configurable, so the ratio is not fixed.
   */
  function measure(pet) {
    var hit = pet.querySelector(HIT);
    if (!hit) return null;
    var rootBox = pet.getBoundingClientRect();
    var hitBox = hit.getBoundingClientRect();
    if (!(hitBox.width > 0) || !(hitBox.height > 0)) return null;
    return {
      dx: hitBox.left - rootBox.left,
      dy: hitBox.top - rootBox.top,
      w: hitBox.width,
      h: hitBox.height
    };
  }

  function apply() {
    var pet = document.querySelector(PET);
    if (!pet) return false;
    ensureStyle();

    // Walk outwards from the pet's parent, hiding every sibling and flattening
    // each ancestor into a transparent, full-viewport box.
    var node = pet.parentElement;
    while (node && node !== document.body && node.parentElement) {
      hideSiblings(node);
      clearBox(node);
      node.style.setProperty('position', 'fixed', 'important');
      node.style.setProperty('inset', '0', 'important');
      node.style.setProperty('width', 'auto', 'important');
      node.style.setProperty('height', 'auto', 'important');
      node.style.setProperty('transform', 'none', 'important');
      node.style.setProperty('overflow', 'visible', 'important');
      node.style.setProperty('pointer-events', 'none', 'important');
      node = node.parentElement;
    }

    if (!natural) {
      // Measure with any host scaling cleared: a previous pass's transform would
      // otherwise be baked into the rect and the scale would come out as 1.
      pet.style.setProperty('transform', 'none', 'important');
      natural = measure(pet);
      // Measured in the same pass as the box, so the ratio is consistent.
      if (natural) referenceWidth = window.innerWidth;
    }
    // The plugin sizes the pet relative to the viewport, so `natural` describes
    // the box at the width it was first measured at. The scale is derived once
    // from that reference width and then reused: the host keeps the window at
    // that width, so re-deriving from a later resize (a panel opening to full
    // screen) would blow the pet up.
    if (natural && !fixedScale && referenceWidth > 0 && natural.w > 0) {
      fixedScale = referenceWidth / natural.w;
    }

    var s = pet.style;
    s.setProperty('position', 'fixed', 'important');
    s.setProperty('right', 'auto', 'important');
    s.setProperty('bottom', 'auto', 'important');
    s.setProperty('margin', '0', 'important');
    s.setProperty('transform-origin', 'top left', 'important');
    s.setProperty('transform', 'scale(' + (fixedScale || 1) + ')', 'important');
    // Pin the character (not the stage) to the window origin. `offsetX/Y` is
    // how the host keeps the pet still while it grows the window around it.
    var scale = fixedScale || 1;
    s.setProperty('left', (offsetX - (natural ? natural.dx * scale : 0)) + 'px', 'important');
    s.setProperty('top', (offsetY - (natural ? natural.dy * scale : 0)) + 'px', 'important');

    if (!reported && natural && HOST && HOST.onPetBox) {
      reported = true;
      try { HOST.onPetBox(natural.w, natural.h); } catch (e) { /* host gone */ }
    }
    return true;
  }

  /** Panels the pet opens that need more room than the pet itself occupies. */
  function panelOpen() {
    return !!document.querySelector('.dsh-pet-menu');
  }

  var pending = false;
  var lastPanel = false;

  /**
   * The plugin closes its menu from a capture-phase `mousedown` on the document.
   * A touch user gets that `mousedown` synthesised from the very press that
   * opened the menu, so the menu would flash and vanish. The menu is therefore
   * treated as newly opened for a moment after it appears, and presses are
   * swallowed during that window.
   *
   * After that, presses inside the menu still pass through the plugin untouched;
   * only the ones that would close it on the same gesture are suppressed.
   */
  var menuOpenedAt = 0;
  var MENU_GRACE_MS = 700;

  function guardMenuPress() {
    document.addEventListener('mousedown', function (e) {
      if (!panelOpen()) return;
      var menu = document.querySelector('.dsh-pet-menu');
      if (Date.now() - menuOpenedAt < MENU_GRACE_MS) {
        e.stopPropagation();
        return;
      }
      if (menu && menu.contains(e.target)) {
        e.stopPropagation();
      }
    }, true);
  }

  var observer = new MutationObserver(function () {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      apply();
      var open = panelOpen();
      if (open !== lastPanel) {
        lastPanel = open;
        if (open) menuOpenedAt = Date.now();
        if (HOST && HOST.onPanel) {
          try { HOST.onPanel(open); } catch (e) { /* host gone */ }
        }
      }
    });
  });

  function start() {
    ensureStyle();
    apply();
    guardMenuPress();
    // childList+subtree only: the rules above are inline styles, and observing
    // attributes would make this loop on its own writes.
    observer.observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('resize', apply, { passive: true });
    window.__dshPetOnly = {
      apply: apply,
      setOffset: function (x, y) {
        offsetX = x || 0;
        offsetY = y || 0;
        apply();
      }
    };
  }

  if (!apply()) {
    var tries = 0;
    var timer = setInterval(function () {
      tries += 1;
      if (apply()) {
        clearInterval(timer);
        start();
      } else if (tries > 600) {
        clearInterval(timer);
      }
    }, 100);
  } else {
    start();
  }
})();
""".trimIndent()
