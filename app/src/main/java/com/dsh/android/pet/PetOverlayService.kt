package com.dsh.android.pet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dsh.android.DshApplication
import com.dsh.android.MainActivity
import com.dsh.android.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the harness's desktop pet in a floating window above other apps, and
 * lets it be used there instead of only looked at.
 *
 * ## Why a WebView
 *
 * The pet is a plugin that renders into the harness page: there is no image or
 * page of its own (its HTTP prefix only exposes state, config, thumbnails and
 * playback). The one faithful way to show the same pet — same animations, same
 * memes, same speech — is to load that page and hide everything except the pet.
 *
 * ## Interaction
 *
 * The window is sized to the character's hit box, not to the whole stage, so the
 * screen area it blocks is only the pet itself. Touch events go to the page
 * (clicks answer, long-press opens the plugin's own menu), and a gesture that
 * turns into a drag is taken over by the host to move the window across the
 * screen. That mirrors the desktop behaviour: the pet reacts when poked and
 * follows the finger when pulled.
 *
 * ## Growing for panels
 *
 * The pet's menu is far larger than the pet, and a page cannot paint outside its
 * window. When the plugin reports a panel, the window grows to the rest of the
 * screen and the page is told to offset the pet by the distance the window
 * origin moved, so the pet appears to stay exactly where it was while the menu
 * has room to open. Collapsing restores both.
 */
class PetOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var container: DragFrameLayout? = null
    private var webView: WebView? = null
    private var menu: PetMenu? = null
    private var harnessUrl: String? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { PetPreferences.of(this) }

    /** Height/width of the character, measured by the page. */
    private var petAspect = PET_ASPECT
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        if (url == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        main.post { show(url) }
        return START_STICKY
    }

    // ------------------------------------------------------------- window ----

    private fun show(url: String) {
        if (container != null) {
            if (webView?.url != url) webView?.loadUrl(url)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission not granted; stopping")
            stopSelf()
            return
        }

        val wm = getSystemService(WindowManager::class.java)
        windowManager = wm

        val frame = DragFrameLayout(this).apply {
            onDrag = { dx, dy -> moveBy(dx, dy) }
            onDragEnd = { rememberPosition() }
            onLongPress = { openMenu() }
            onMenuDismiss = { closeMenu() }
        }
        harnessUrl = url
        val view = createWebView(url)
        view.tag = "pet"
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val layout = WindowManager.LayoutParams(
            dp(PET_WIDTH_DP),
            dp(PET_WIDTH_DP * PET_ASPECT),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = dp(16f)
            x = if (prefs.petX != PetPreferences.UNSET) prefs.petX
            else resources.displayMetrics.widthPixels - width - margin
            y = if (prefs.petY != PetPreferences.UNSET) prefs.petY
            else (resources.displayMetrics.heightPixels * 0.35f).toInt()
            clampToScreen(this)
        }

        runCatching { wm.addView(frame, layout) }
            .onSuccess {
                container = frame
                params = layout
            }
            .onFailure {
                Log.e(TAG, "cannot add overlay: ${it.message}")
                view.destroyQuietly()
                stopSelf()
            }
    }

    /**
     * Base window flags.
     *
     * `FLAG_NOT_TOUCH_MODAL` is what makes the window coexist with the app
     * underneath: touches outside its bounds go to that app instead of being
     * swallowed. `FLAG_NOT_FOCUSABLE` keeps the keyboard with whatever the user
     * is typing into; it is dropped while a panel is open, because the pet's chat
     * box needs the IME.
     */
    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // The pet's own layout is defined at the page's natural scale; a wide
            // viewport would rescale it away from the size the host measured.
            useWideViewPort = false
            loadWithOverviewMode = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        view.addJavascriptInterface(Bridge(), "DshPetHost")
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(v: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                // Both scripts are idempotent; running them as early as possible
                // avoids a frame of the full UI flashing over other apps.
                v?.evaluateJavascript(PET_ONLY_SCRIPT, null)
            }

            override fun onPageFinished(v: WebView?, finishedUrl: String?) {
                v?.evaluateJavascript(PET_ONLY_SCRIPT, null)
            }
        }
        view.loadUrl(url)
        webView = view
        return view
    }

    /** Calls from [PET_ONLY_SCRIPT] arrive on a WebView thread, so hop to main. */
    private inner class Bridge {
        @JavascriptInterface
        fun onPetBox(width: Float, height: Float) {
            main.post {
                if (width <= 0f || height <= 0f) return@post
                // Only the aspect ratio is taken from the measurement: the width
                // is the host's choice, so the window can never shrink in
                // response to the viewport it just created.
                val aspect = (height / width).coerceIn(MIN_ASPECT, MAX_ASPECT)
                if (abs(aspect - petAspect) < 0.02f) return@post
                petAspect = aspect
                resizeToPet()
            }
        }

        @JavascriptInterface
        fun onPanel(open: Boolean) {
            // The plugin's menu only renders inside the main harness window; in
            // this second WebView instance it stays display:none. Long-press is
            // therefore served by the host's own menu (see PetMenu), and this
            // callback is left as a hook for the day the plugin supports it.
            main.post { if (open) Log.i(TAG, "plugin reported a panel") }
        }
    }

    /**
     * Show the host's own menu for the pet.
     *
     * The plugin's menu is built from data that only exists in the main harness
     * window; in this second WebView instance it is never created, so a long
     * press here has to be answered by the host. The menu is added to the window
     * itself, which is why its position is in window coordinates.
     */
    private fun openMenu() {
        if (menu != null) return
        val frame = container ?: return
        val api = PetApi(this).apply {
            baseUrl = harnessUrl?.substringBefore("?")?.trimEnd('/') ?: ""
        }
        // The menu is far larger than the pet, and a page cannot paint outside
        // its window: grow the window first, then place the menu beside the pet.
        val anchor = expandForMenu()
        menu = PetMenu(this, api, anchor, onClose = { closeMenu() }).also { frame.menu = it }
        frame.addView(
            menu,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /**
     * Grow the window to the screen so the menu is not clipped, and tell the page
     * how far its origin moved so the pet stays on the same screen pixel.
     *
     * @returns the pet's position inside the grown window, in pixels.
     */
    private fun expandForMenu(): Int {
        val layout = params ?: return 0
        val frame = container ?: return 0
        val metrics = resources.displayMetrics
        val density = metrics.density
        val oldX = layout.x
        val oldY = layout.y
        prefs.petX = oldX
        prefs.petY = oldY
        layout.x = 0
        layout.y = 0
        layout.width = metrics.widthPixels
        layout.height = metrics.heightPixels
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        runCatching { windowManager?.updateViewLayout(frame, layout) }
        val offsetX = (oldX / density).toInt()
        val offsetY = (oldY / density).toInt()
        Log.d(TAG, "expandForMenu: offset=$offsetX,$offsetY webView=${webView != null}")
        webView?.evaluateJavascript(
            "window.__dshPetOnly && window.__dshPetOnly.setOffset($offsetX, $offsetY)",
        ) { Log.d(TAG, "setOffset result=$it (hasScript=${it == null})") }
        return oldX
    }


    /** Where the pet currently sits on screen, in window coordinates. */

    private fun closeMenu() {
        val frame = container ?: return
        val hadMenu = menu != null
        menu?.let { frame.removeView(it) }
        menu = null
        frame.menu = null
        if (!hadMenu) return

        // Back to pet size at the remembered spot. The offset is cleared in the
        // same pass so the page and the window change together and the pet does
        // not appear to slide during the resize.
        webView?.evaluateJavascript("window.__dshPetOnly && window.__dshPetOnly.setOffset(0,0)", null)
        val layout = params ?: return
        layout.width = dp(PET_WIDTH_DP)
        layout.height = dp(PET_WIDTH_DP * petAspect)
        layout.x = prefs.petX
        layout.y = prefs.petY
        clampToScreen(layout)
        layout.flags = baseFlags()
        runCatching { windowManager?.updateViewLayout(frame, layout) }
    }

    private fun resizeToPet() {
        val layout = params ?: return
        val frame = container ?: return
        layout.width = dp(PET_WIDTH_DP)
        layout.height = dp(PET_WIDTH_DP * petAspect)
        clampToScreen(layout)
        runCatching { windowManager?.updateViewLayout(frame, layout) }
    }

    /**
     * Grow to the rest of the screen while a panel is open, and shrink back
     * after. The page is offset by how far its origin moved so the pet itself
     * does not appear to jump.
     */
private fun moveBy(dx: Float, dy: Float) {
        val layout = params ?: return
        val frame = container ?: return
        layout.x = layout.x + dx.roundToInt()
        layout.y = layout.y + dy.roundToInt()
        clampToScreen(layout)
        runCatching { windowManager?.updateViewLayout(frame, layout) }
    }

    private fun rememberPosition() {
        val layout = params ?: return
        prefs.petX = layout.x
        prefs.petY = layout.y
    }

    /**
     * Keep the window fully on screen.
     *
     * The overlay is `FLAG_LAYOUT_NO_LIMITS`, so nothing else stops a drag from
     * carrying the pet out of view.
     */
    private fun clampToScreen(layout: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - layout.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - layout.height).coerceAtLeast(0)
        layout.x = layout.x.coerceIn(0, maxX)
        layout.y = layout.y.coerceIn(0, maxY)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    // ---------------------------------------------------------- lifecycle ----

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this,
            3,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.pet_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .addAction(0, getString(R.string.pet_notification_hide), hide)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            // `specialUse` only exists from Android 14. Below that the platform
            // rejects the unknown value, so the type is left unset there; the
            // manifest declaration is what the newer platforms read.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        val view = container ?: return
        container = null
        params = null
        runCatching { windowManager?.removeView(view) }
        (view.getChildAt(0) as? WebView)?.destroyQuietly()
        webView = null
    }

    private fun WebView.destroyQuietly() {
        runCatching {
            removeJavascriptInterface("DshPetHost")
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
    }

    companion object {
        private const val TAG = "DSH_PET"

        /** Fallback size until the page reports the character's real box. */
        private const val PET_WIDTH_DP = 132f
        private const val PET_ASPECT = 1.19f
        private const val MIN_ASPECT = 0.6f
        private const val MAX_ASPECT = 2.5f

        private const val NOTIFICATION_ID = 2
        const val EXTRA_URL = "url"
        const val ACTION_HIDE = "com.dsh.android.pet.HIDE"

        /** Whether the platform will actually allow an overlay window. */
        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * Bring the overlay up for [url], or take it down when [url] is null.
         *
         * Callers are the lifecycle observer, so this runs only on the main
         * thread and only while the harness is running.
         */
        fun show(context: Context, url: String) {
            if (!canDraw(context)) {
                Log.i(TAG, "overlay permission missing; not showing")
                return
            }
            val intent = Intent(context, PetOverlayService::class.java)
                .putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, PetOverlayService::class.java))
        }
    }
}

/**
 * Hosts the WebView and decides, gesture by gesture, whether the page or the
 * window gets the touch.
 *
 * A press that stays put belongs to the page: clicks answer and a long press
 * opens the pet's own menu. Once the finger travels past the touch slop the
 * gesture is taken over — the page is cancelled and the window follows the
 * finger instead, which is what dragging a desktop pet should feel like.
 */
private class DragFrameLayout(context: Context) : FrameLayout(context) {

    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onMenuDismiss: (() -> Unit)? = null
    var menu: PetMenu? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var enabled = true

    /**
     * Long press opens the host menu when no finger has moved far enough to
     * become a drag. Posting the check keeps taps on the pet itself — which the
     * page answers with a click — working exactly as in the app.
     */
    private val longPressRunnable = Runnable {
        Log.d("DSH_PET", "longPress fired (dragging=$dragging)")
        if (!dragging) {
            onLongPress?.invoke()
        }
    }

    fun setDraggingEnabled(value: Boolean) {
        enabled = value
        if (!value) dragging = false
    }

    /**
     * Observes every gesture before it reaches the page.
     *
     * A WebView calls `requestDisallowInterceptTouchEvent` for its own input, so
     * `onInterceptTouchEvent` never sees `ACTION_DOWN`. Dispatch is therefore the
     * first place the host can tell "the finger is holding still" (a menu press)
     * from "the finger is pulling the pet" (a drag).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // An open menu owns the touch wherever it lands on it; the page must not
        // see the press, or it would answer with a pet reaction instead of
        // running the chosen action.
        menu?.let { list ->
            if (list.parent != null && list.isShown) {
                val lx = list.absoluteXForTouch()
                val ly = list.absoluteYForTouch()
                val inside = ev.rawX >= lx && ev.rawX <= lx + list.width &&
                    ev.rawY >= ly && ev.rawY <= ly + list.height
                if (inside) {
                    removeCallbacks(longPressRunnable)
                    // Re-express the event in the list's own coordinates and hand
                    // it straight to it: the frame's other child (the WebView)
                    // would otherwise take the press for a pet click.
                    val local = MotionEvent.obtain(ev)
                    local.offsetLocation(-lx, -ly)
                    val handled = list.dispatchTouchEvent(local)
                    local.recycle()
                    return handled
                }
                // A press outside the menu dismisses it instead of reaching the page.
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    onMenuDismiss?.invoke()
                    return true
                }
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (enabled && !dragging &&
                    (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)
                ) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    onDrag?.invoke(ev.rawX - lastX, ev.rawY - lastY)
                    lastX = ev.rawX
                    lastY = ev.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (dragging) onDragEnd?.invoke()
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                dragging = false
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && dragging) return true
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = ev.rawX
            lastY = ev.rawY
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Dispatch already decides; nothing to intercept a second time.
        return dragging
    }

}
