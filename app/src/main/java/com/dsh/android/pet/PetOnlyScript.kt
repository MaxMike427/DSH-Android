package com.dsh.android.pet

/**
 * Isolates the real plugin without taking over its layout, menu or physics.
 *
 * The WebView keeps a screen-sized viewport. Only the Android window is cropped
 * to the visible pet. Previously both the host and plugin moved the character,
 * and plugin style writes displaced it outside a tiny viewport after a drag.
 */
internal val PET_ONLY_SCRIPT: String = """
(function installPetOverlay() {
  if (!document.documentElement) {
    setTimeout(installPetOverlay, 25);
    return;
  }
  if (window.__dshPetOnly && window.__dshPetOnly.version === 2) {
    window.__dshPetOnly.refresh();
    return;
  }
  var host = window.DshPetHost;
  if (!host) return;
  var keep = '.dsh-pet-root,.dsh-pet-menu,.dsh-pet-chat,.dsh-pet-score,' +
    '.dsh-pet-score-burst,[data-shortcut-modal="settings"],' +
    '[aria-modal="true"]:not([data-shortcut-modal])';
  var panels = '.dsh-pet-menu-column,.dsh-pet-chat,.dsh-pet-todo,' +
    '.dsh-pet-score,[data-shortcut-modal="settings"],' +
    '[aria-modal="true"]:not([data-shortcut-modal])';
  var pending = false;
  var structureChanged = true;
  var lastFrame = '';
  var openedAt = 0;
  var wasOpen = false;
  var nativeMotion = false;
  var touchMenuItem = null;
  var api = { version: 2, refresh: schedule, motionSamples: 0 };
  window.__dshPetOnly = api;

  function installStyle() {
    if (document.getElementById('dsh-pet-only-style')) return;
    var style = document.createElement('style');
    style.id = 'dsh-pet-only-style';
    style.textContent =
      'html,body{background:transparent!important;overflow:hidden!important}' +
      'body::before,body::after{display:none!important}' +
      '[data-pet-host-hidden]{display:none!important}' +
      '[data-pet-host-path]{background:transparent!important;box-shadow:none!important;' +
      'border:0!important;backdrop-filter:none!important;position:fixed!important;' +
      'inset:0!important;width:auto!important;height:auto!important;transform:none!important;' +
      'overflow:visible!important;pointer-events:none!important}';
    (document.head || document.documentElement).appendChild(style);
  }

  function related(el) {
    return el.matches(keep) || !!el.querySelector(keep);
  }

  function preserveBranch(node) {
    node.removeAttribute('data-pet-host-hidden');
    if (node.matches(keep)) return;
    node.setAttribute('data-pet-host-path', '');
    Array.prototype.forEach.call(node.children, function (child) {
      if (child.tagName === 'SCRIPT' || child.tagName === 'STYLE') return;
      if (related(child)) {
        preserveBranch(child);
      } else {
        child.setAttribute('data-pet-host-hidden', '');
      }
    });
  }

  function isolate() {
    installStyle();
    var pets = document.querySelectorAll('.dsh-pet-root');
    if (!pets.length) return;
    // Preserve body portals as well as the pet's ancestor branch. A container
    // can contain both the pet and the workspace, so preserving that container
    // wholesale is not enough: recurse and hide every unrelated descendant.
    Array.prototype.forEach.call(document.body.children, function (child) {
      if (child.tagName === 'SCRIPT' || child.tagName === 'STYLE') return;
      if (related(child)) {
        preserveBranch(child);
      } else {
        child.setAttribute('data-pet-host-hidden', '');
      }
    });
  }

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || getComputedStyle(el).visibility !== 'visible') return false;
    for (var node = el; node && node !== document.documentElement; node = node.parentElement) {
      var style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) === 0) return false;
    }
    return true;
  }

  function report() {
    pending = false;
    if (structureChanged) {
      structureChanged = false;
      isolate();
    }
    var open = Array.prototype.some.call(document.querySelectorAll(panels), visible);
    if (open && !wasOpen) openedAt = Date.now();
    wasOpen = open;
    // Only the chat dialog needs the keyboard. Making the whole window focusable
    // for a plain menu is a much heavier WindowManager transition (input channel,
    // insets) and flashes the pet; menus stay non-focusable.
    var keyboard = Array.prototype.some.call(
      document.querySelectorAll('.dsh-pet-chat'), visible
    );
    // A page-level modal is screen-centered and blocks the app behind it. The
    // overlay must show it in full, not crop it to the pet, or the user has no
    // way to dismiss it from the desktop.
    var modal = Array.prototype.some.call(
      document.querySelectorAll('[aria-modal="true"]:not([data-shortcut-modal])'), visible
    );
    var boxes = Array.prototype.filter.call(
      document.querySelectorAll('.dsh-pet-hit,.dsh-pet-bubble.is-on,.pet-tooltip.is-on'), visible
    ).map(function (el) { return el.getBoundingClientRect(); });
    var x = 0, y = 0, right = 0, bottom = 0;
    if (boxes.length) {
      x = Math.min.apply(null, boxes.map(function (r) { return r.left; }));
      y = Math.min.apply(null, boxes.map(function (r) { return r.top; }));
      right = Math.max.apply(null, boxes.map(function (r) { return r.right; }));
      bottom = Math.max.apply(null, boxes.map(function (r) { return r.bottom; }));
    }
    var frame = [x, y, right - x, bottom - y, open ? 1 : 0, keyboard ? 1 : 0, modal ? 1 : 0];
    // Quantise to device pixels; don't queue identical window updates for video
    // swaps, blinking cursors and sub-pixel spring motion.
    var dpr = window.devicePixelRatio || 1;
    for (var i = 0; i < 4; i++) frame[i] = Math.round(frame[i] * dpr) / dpr;
    var key = JSON.stringify(frame);
    if (key !== lastFrame) {
      lastFrame = key;
      host.onFrame(frame[0], frame[1], frame[2], frame[3], open, keyboard, modal);
    }
  }

  function schedule() {
    if (pending) return;
    pending = true;
    // MutationObserver already runs at the end of the DOM task. Queue the crop
    // in the next microtask instead of waiting another 16ms timer tick; that
    // removes a whole display frame from finger-to-window latency. The first
    // report also happens before the transparent surface can be suspended.
    if (typeof queueMicrotask === 'function') queueMicrotask(report);
    else setTimeout(report, 0);
  }

  // Touch produces a compatibility mousedown after a long press. It must not
  // immediately close the menu that the same press has just opened.
  document.addEventListener('mousedown', function (event) {
    if (!wasOpen || Date.now() - openedAt > 700) return;
    if (event.target.closest('.dsh-pet-menu,.dsh-pet-chat,.dsh-pet-todo')) return;
    event.stopImmediatePropagation();
  }, true);

  // Native menu branches use mouseenter. On touch, enter the branch explicitly;
  // leaf clicks and all action handlers remain the plugin's own handlers.
  document.addEventListener('pointerdown', function (event) {
    touchMenuItem = event.pointerType === 'mouse' ? null : event.target.closest('.dsh-pet-menu-item');
  }, true);
  document.addEventListener('click', function (event) {
    var item = touchMenuItem;
    touchMenuItem = null;
    if (item && item.matches('.dsh-pet-menu-branch') && event.target.closest('.dsh-pet-menu')) {
      // A touch used to expand a branch must not select an overlapping child
      // after the browser re-hit-tests its compatibility click.
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);
  document.addEventListener('pointerup', function (event) {
    if (event.pointerType === 'mouse') return;
    var branch = event.target.closest('.dsh-pet-menu-branch');
    // Let the touch's pointerup/click finish before opening a child panel. If
    // the panel is inserted synchronously, its reflow changes the hit target
    // under the same finger and a branch tap can accidentally activate a leaf.
    if (branch) setTimeout(function () {
      if (branch.isConnected) branch.dispatchEvent(new MouseEvent('mouseenter', { view: window }));
    }, 0);
  }, true);

  // Chromium may suppress hardware events when an Activity is backgrounded.
  // The visible foreground overlay supplies the same W3C events from Android
  // sensors, so the plugin's existing tiltGravity/shakeThrow switches still win.
  ['deviceorientation', 'devicemotion'].forEach(function (type) {
    window.addEventListener(type, function (event) {
      if (wasOpen || (nativeMotion && event.isTrusted)) event.stopImmediatePropagation();
    }, true);
  });
  function dispatchSensor(type, values) {
    var event;
    try {
      if (type === 'deviceorientation' && typeof DeviceOrientationEvent !== 'undefined') {
        event = new DeviceOrientationEvent(type, values);
      } else if (type === 'devicemotion' && typeof DeviceMotionEvent !== 'undefined') {
        event = new DeviceMotionEvent(type, values);
      }
    } catch (e) { event = null; }
    if (!event) event = new Event(type);
    Object.keys(values).forEach(function (key) {
      try { Object.defineProperty(event, key, { value: values[key], enumerable: true }); } catch (e) {}
    });
    window.dispatchEvent(event);
  }
  api.receiveMotion = function (sample) {
    nativeMotion = true;
    api.motionSamples++;
    // The plugin stops flight on menu open, but a subsequent tilt sample can
    // start it again while the finger is still choosing a menu item.
    if (wasOpen) return;
    if (sample.beta !== null) {
      dispatchSensor('deviceorientation', {
        alpha: sample.alpha, beta: sample.beta, gamma: sample.gamma, absolute: false
      });
    }
    dispatchSensor('devicemotion', {
      acceleration: sample.acceleration,
      accelerationIncludingGravity: sample.gravity,
      rotationRate: sample.rotationRate,
      interval: sample.interval
    });
  };

  new MutationObserver(function (records) {
    if (records.some(function (r) { return r.type === 'childList'; })) structureChanged = true;
    schedule();
  }).observe(document.documentElement, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class', 'hidden']
  });
  window.addEventListener('resize', schedule, { passive: true });
  window.addEventListener('load', function () { structureChanged = true; schedule(); }, { once: true });
  schedule();
})();
""".trimIndent()
