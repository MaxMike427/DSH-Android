package com.dsh.android.ui

import android.graphics.Bitmap

/**
 * A video without a poster otherwise gets Android's grey play-button bitmap,
 * even with controls disabled and a transparent CSS background. Rapid pet
 * animation swaps briefly empty a video and expose that bitmap over the pet.
 *
 * Explicit page posters and video controls still work. Share one transparent
 * bitmap so rapid reloads do not allocate a placeholder for every animation.
 */
internal val transparentVideoPoster: Bitmap by lazy {
    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
