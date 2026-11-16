package com.dsh.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent

/** Implemented by the Activity so the Web UI can intercept the volume keys. */
interface VolumeKeyHost {
    /**
     * Invoked for KEYCODE_VOLUME_UP / KEYCODE_VOLUME_DOWN before the system
     * handles them. Returning true consumes the event, which stops the volume
     * slider from appearing while the overlay is being toggled.
     */
    var onVolumeKey: ((Int) -> Boolean)?
}

/** Unwrap the Activity behind a Compose LocalContext. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Helper so the Activity can ask whether a key code is one it forwards. */
fun isVolumeKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
