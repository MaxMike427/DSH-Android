package com.dsh.android.pet

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dsh.android.DshApplication
import com.dsh.android.MainActivity
import com.dsh.android.R
import com.dsh.android.ui.transparentVideoPoster

/**
 * A cropped view of the actual dsh-pet page, not a second implementation of it.
 *
 * The WebView always has the same screen-sized viewport as the in-app page. The
 * window follows its hit box and translates the page behind that crop. Pointer
 * events (including UP/CANCEL), menus, animations and sensor physics all belong
 * to the plugin. Menus/dialogs temporarily use the whole window, then shrink.
 * Keeping the page size fixed avoids both the viewport/scale feedback loop and
 * the two independent drag implementations that made the character disappear.
 */
class PetOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var motion: PetMotionSensors? = null
    private var panelOpen = false
    private var panelRequest = 0
    private var acknowledgedPanelRequest = -1
    private data class Frame(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val expanded: Boolean,
        val keyboard: Boolean,
        val modal: Boolean,
        val panelRequest: Int,
    )
    private val frames = PetFrameQueue<Frame> { main.post { flushFrame() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { isHarnessUrl(it) }
        if (intent?.action == ACTION_HIDE || url == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        main.post { show(url) }
        return START_NOT_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun show(url: String) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (container != null) return
        val wm = getSystemService(WindowManager::class.java)
        windowManager = wm
        val frame = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                acknowledgePanelSpace()
            }
        }
        val view = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
            }
            addJavascriptInterface(Bridge(), "DshPetHost")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, target: String?, icon: android.graphics.Bitmap?) {
                    if (!isHarnessUrl(target)) {
                        view.stopLoading()
                        return
                    }
                    acknowledgedPanelRequest = -1
                    view.evaluateJavascript(PET_ONLY_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView, target: String?) {
                    if (isHarnessUrl(target)) view.evaluateJavascript(PET_ONLY_SCRIPT, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = !isHarnessUrl(request.url.toString())
            }
            webChromeClient = object : WebChromeClient() {
                override fun getDefaultVideoPoster(): android.graphics.Bitmap = transparentVideoPoster

                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = request.resources.filter { resource ->
                        val permission = when (resource) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                            else -> return@filter false
                        }
                        isHarnessUrl(request.origin.toString()) &&
                            ContextCompat.checkSelfPermission(this@PetOverlayService, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                }
            }
        }
        // An explicit child size, not MATCH_PARENT: shrinking the Android window
        // must never resize the plugin's viewport or its physics arena.
        frame.addView(view, FrameLayout.LayoutParams(viewportWidth(), viewportHeight()))
        val layout = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(false, false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f // Don't flash the harness before the pet is ready.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // This window is a moving crop, not an independently animated card.
            // WindowManager's automatic move animation interpolates the old
            // origin for ~200ms while the WebView translation changes at once.
            // That makes the pet fly across the screen on menu open AND close,
            // even though its DOM/screenX coordinates report no movement.
            disableMoveAnimation(this)
        }
        try {
            wm.addView(frame, layout)
            container = frame
            webView = view
            params = layout
            motion = PetMotionSensors(this, view).also { it.start() }
            view.loadUrl(url)
        } catch (error: Exception) {
            Log.e(TAG, "cannot add pet overlay", error)
            view.destroy()
            stopSelf()
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onFrame(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            expanded: Boolean,
            keyboard: Boolean,
            modal: Boolean,
            panelRequest: Int,
        ) {
            frames.offer(Frame(x, y, width, height, expanded, keyboard, modal, panelRequest))
        }
    }

    private fun flushFrame() {
        val frame = frames.take() ?: return
        panelRequest = frame.panelRequest
        updateCrop(frame.x, frame.y, frame.width, frame.height,
            frame.expanded, frame.keyboard, frame.modal)
    }

    private fun updateCrop(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        expanded: Boolean,
        keyboard: Boolean,
        modal: Boolean,
    ) {
        val view = webView ?: return
        val frame = container ?: return
        val layout = params ?: return
        val crop = PetWindowGeometry.crop(
            x, y, width, height, resources.displayMetrics.density,
            viewportWidth(), viewportHeight(), expanded, modal,
        ) ?: return
        val alpha = if (width > 0f || expanded || modal) 1f else 0f
        val flags = windowFlags(expanded, keyboard)
        val changed = layout.x != crop.x || layout.y != crop.y ||
            layout.width != crop.width || layout.height != crop.height ||
            layout.alpha != alpha || layout.flags != flags
        view.translationX = -crop.contentX.toFloat()
        view.translationY = -crop.contentY.toFloat()
        layout.x = crop.x
        layout.y = crop.y
        layout.width = crop.width
        layout.height = crop.height
        layout.alpha = alpha
        layout.flags = flags
        if (panelOpen && !expanded) view.clearFocus()
        panelOpen = expanded
        if (changed) {
            runCatching { windowManager?.updateViewLayout(frame, layout) }
                .onFailure { Log.w(TAG, "cannot update pet crop", it) }
        }
        acknowledgePanelSpace()
    }

    private fun acknowledgePanelSpace() {
        val frame = container ?: return
        val layout = params ?: return
        if (!panelOpen || panelRequest == acknowledgedPanelRequest ||
            frame.width != layout.width || frame.height != layout.height
        ) return
        val request = panelRequest
        acknowledgedPanelRequest = request
        // Run after traversal. The menu may already exist in Chromium, but it
        // must not paint until WindowManager has actually expanded its surface.
        frame.post {
            if (panelOpen && request == panelRequest) {
                webView?.evaluateJavascript("window.__dshPetOnly?.onPanelSpace($request)", null)
            }
        }
    }

    private fun disableMoveAnimation(layout: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 34) {
            layout.setCanPlayMoveAnimation(false)
        } else {
            // API 34 made the existing NO_MOVE_ANIMATION setting public. Older
            // supported Android versions expose the same flag on LayoutParams;
            // isolate this compatibility access and retain every other flag.
            @SuppressLint("SoonBlockedPrivateApi")
            val result = runCatching {
                val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
                field.setInt(layout, field.getInt(layout) or (1 shl 6))
            }
            result.onFailure { Log.w(TAG, "Cannot disable pet window move animation", it) }
        }
    }

    private fun viewportWidth(): Int = resources.displayMetrics.widthPixels
    private fun viewportHeight(): Int = resources.displayMetrics.heightPixels

    private fun windowFlags(expanded: Boolean, keyboard: Boolean): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (expanded && keyboard) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webView?.layoutParams = FrameLayout.LayoutParams(viewportWidth(), viewportHeight())
        webView?.evaluateJavascript("window.__dshPetOnly?.refresh()", null)
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this, 3, Intent(this, PetOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.pet_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .addAction(0, getString(R.string.pet_notification_hide), hide)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, 2, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        frames.clear()
        motion?.stop()
        motion = null
        container?.let { runCatching { windowManager?.removeView(it) } }
        container = null
        webView?.apply {
            removeJavascriptInterface("DshPetHost")
            stopLoading()
            destroy()
        }
        webView = null
        params = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DSH_PET"
        const val EXTRA_URL = "url"
        const val ACTION_HIDE = "com.dsh.android.pet.HIDE"

        private fun isHarnessUrl(url: String?): Boolean = runCatching {
            val uri = android.net.Uri.parse(url ?: return false)
            uri.scheme == "http" && uri.host == "127.0.0.1"
        }.getOrDefault(false)

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun show(context: Context, url: String) {
            if (!canDraw(context)) return
            val intent = Intent(context, PetOverlayService::class.java).putExtra(EXTRA_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, PetOverlayService::class.java))
        }
    }
}
