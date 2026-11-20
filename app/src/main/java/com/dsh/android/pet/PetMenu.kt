package com.dsh.android.pet

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * The floating pet's own menu.
 *
 * The plugin ships a rich context menu, but it only builds inside the main
 * harness window: in the overlay's separate WebView instance it is created and
 * then left `display:none`, so a long press there would do nothing. This menu
 * covers the same ground with the plugin's public HTTP API instead, which means
 * the floating pet can be told to walk, turn, eat, speak or sleep without ever
 * leaving the other app.
 *
 * Rendered as a plain view tree rather than a Compose dialog: an overlay window
 * has no Activity to host a Dialog, and the service must stay alive regardless.
 *
 * It is added to the pet's own window, so it is bounded by that window — the
 * caller sizes the window first.
 */
class PetMenu(
    context: Context,
    private val api: PetApi,
    /** Pets x position inside the window, in pixels. */
    private val anchorX: Int,
    /** Called after an action or a dismissal, so the host can shrink the window. */
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density

    private fun dismiss() {
        (parent as? android.view.ViewGroup)?.removeView(this)
        onClose()
    }

    /**
     * Left/top of this view on screen, for the host's hit testing.
     *
     * `getLocationOnScreen` is used rather than the local `x`: the containing
     * window is not always at the screen origin, and raw touch coordinates are
     * screen-relative.
     */
    fun absoluteXForTouch(): Float {
        val point = IntArray(2)
        getLocationOnScreen(point)
        return point[0].toFloat()
    }

    fun absoluteYForTouch(): Float {
        val point = IntArray(2)
        getLocationOnScreen(point)
        return point[1].toFloat()
    }


    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(0xF21B2430.toInt())
            setStroke((1 * density).toInt(), 0x33FFFFFF)
        }
        // Never let the list itself be dragged around as if it were the pet.
        isClickable = true
        elevation = 12 * density
        setPadding(dp(6), dp(6), dp(6), dp(6))

        PetApi.menuActions().forEachIndexed { index, group ->
            if (index > 0) addView(divider())
            group.forEach { action -> addView(item(action)) }
        }
    }

    /**
     * Place the list next to the pet once its size is known.
     *
     * The window has already been grown to the screen by the caller, so the only
     * question is which side of the pet the list fits on.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!changed || width == 0) return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val right = anchorX + width + dp(4)
        x = (if (right + width <= screenWidth) right else (anchorX - width - dp(4)).coerceAtLeast(0)).toFloat()
        y = ((screenHeight - height) / 2).coerceAtLeast(0).toFloat()
    }

    private fun item(action: PetApi.Action): TextView =
        TextView(context).apply {
            text = action.label
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            minWidth = dp(150)
            setOnClickListener {
                action.run(api)
                dismiss()
            }
        }

    private fun divider(): View =
        View(context).apply {
            setBackgroundColor(0x22FFFFFF)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }

    private fun dp(value: Int): Int = max(1, (value * density).toInt())
}