package com.dsh.android.pet

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.dsh.android.DshApplication
import com.dsh.android.runtime.RuntimeState

/**
 * Raises the floating pet while this app is off screen and takes it down again
 * when the app comes back.
 *
 * The in-app Web UI already draws the pet; the floating window is only the
 * background stand-in. Keeping it tied to this app's own foreground state means
 * the two never show at once and the (heavy) second page is only alive while it
 * is actually visible.
 *
 * Activity bookkeeping rather than `ProcessLifecycleOwner` on purpose: the
 * runtime's foreground service keeps this process alive, and only the activity
 * callbacks say whether a *window* of ours is on screen.
 */
class PetForeground(private val app: DshApplication) : Application.ActivityLifecycleCallbacks {

    private val prefs = PetPreferences.of(app)
    private var started = 0

    /** The last URL the runtime reported, kept so a later background can use it. */
    @Volatile
    private var lastUrl: String? = null

    private val isForeground: Boolean get() = started > 0

    fun register() {
        app.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Follow the runtime so the overlay appears on a background *after* the
     * server is up, and disappears immediately when it stops.
     */
    fun onRuntimeState(state: RuntimeState) {
        when (state) {
            is RuntimeState.Running -> {
                lastUrl = state.url
                if (!isForeground) sync()
            }
            else -> {
                lastUrl = null
                PetOverlayService.hide(app)
            }
        }
    }

    /** Re-evaluate the two switches without waiting for a lifecycle change. */
    fun sync() {
        val url = lastUrl
        if (!isForeground && url != null && prefs.floatEnabled) {
            PetOverlayService.show(app, url)
        } else {
            PetOverlayService.hide(app)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        started += 1
        if (started == 1) {
            // Back in the app: the in-app pet takes over.
            PetOverlayService.hide(app)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) sync()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val TAG = "DSH_PET"

        fun install(app: DshApplication): PetForeground {
            Log.i(TAG, "pet overlay lifecycle installed")
            return PetForeground(app).also { it.register() }
        }
    }
}
