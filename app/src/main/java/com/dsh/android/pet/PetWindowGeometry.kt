package com.dsh.android.pet

import kotlin.math.ceil
import kotlin.math.floor

/** Convert the plugin's CSS box to a bounded Android crop without scaling it. */
internal object PetWindowGeometry {
    data class Crop(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val contentX: Int, val contentY: Int,
    )

    fun crop(
        x: Float, y: Float, width: Float, height: Float, density: Float,
        screenWidth: Int, screenHeight: Int, expanded: Boolean, modal: Boolean = false,
    ): Crop? {
        if (listOf(x, y, width, height, density).any { !it.isFinite() } || density <= 0f ||
            screenWidth <= 0 || screenHeight <= 0 || width < 0f || height < 0f
        ) return null
        // A page modal is centred and must be seen whole; a menu is anchored to
        // the pet and must not move it.
        if (modal) return Crop(0, 0, screenWidth, screenHeight, 0, 0)
        if (expanded) {
            // Full screen so nested submenus always have room. The host disables
            // automatic move animations: window position and page translation
            // must change immediately together, never interpolate independently.
            return Crop(0, 0, screenWidth, screenHeight, 0, 0)
        }
        val left = floor(x * density).toInt()
        val top = floor(y * density).toInt()
        // Deriving size from ceil(right)-floor(left) alternates the surface size
        // as a fixed-size pet crosses fractional pixels. Reserve one pixel for
        // rounding and keep size independent of position, avoiding buffer resize
        // work on every drag/physics step.
        val w = (ceil(width * density).toInt() + 1).coerceIn(1, screenWidth)
        val h = (ceil(height * density).toInt() + 1).coerceIn(1, screenHeight)
        return Crop(
            left.coerceIn(0, screenWidth - w), top.coerceIn(0, screenHeight - h), w, h, left, top,
        )
    }
}
