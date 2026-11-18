package com.dsh.android.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Visible window for the harness's browser sign-in flow.
 *
 * The account panel drives sign-in through a popup it creates itself:
 *
 * ```js
 * this.popup = window.open('about:blank', '_blank')
 * this.popup.opener = null
 * this.popup.location.replace(authorizeUrl)
 * ```
 *
 * That only works when `window.open` returns a real window the page can
 * navigate. Handing WebView a detached `WebView` (the first attempt) satisfied
 * the API but produced no window on screen: the authorization page loaded where
 * nobody could see or complete it, and the platform answered "登录失败".
 *
 * This dialog is that window. It is attached and visible before the transport is
 * filled, which is what lets Chromium bind the opener's window proxy to it.
 *
 * The popup shares the app's cookie jar, so the platform session it establishes
 * is the one the harness reads back through its polling.
 */
@SuppressLint("SetJavaScriptEnabled")
class LoginPopup(context: Context) {

    /** The window the page navigates; assigned to the transport by the caller. */
    val webView: WebView = WebView(context)

    private val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
        }
        webView.setBackgroundColor(Color.rgb(21, 21, 23))

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(21, 21, 23))
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // The platform page tells the user to close the tab when it is done, and
        // the harness never closes a popup it navigated itself, so the window
        // needs its own way out.
        root.addView(
            TextView(context).apply {
                text = "关闭"
                setTextColor(Color.parseColor("#E6EDF3"))
                textSize = 14f
                setPadding(dp(20), dp(12), dp(20), dp(12))
                setBackgroundColor(Color.parseColor("#CC1A1A1E"))
                setOnClickListener { dismiss() }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(28); marginEnd = dp(16) },
        )

        dialog.setContentView(root)
        dialog.setOnDismissListener { webView.destroy() }
    }

    /** Show the window before the page starts driving it. */
    fun show() {
        if (!dialog.isShowing) dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(value: Int): Int =
        (value * webView.resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Close the popup once the flow returns to the app's own origin.
         *
         * The platform redirects there when authorization finishes; loading that
         * URL in the opener is what lets the harness observe the result on its own
         * page. Any other host keeps loading inside the popup, which is how the
         * identity provider's pages are reached.
         */
        fun closeOnReturnToApp(popup: LoginPopup, opener: WebView): WebViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url
                    val host = target.host
                    if (host == "127.0.0.1" || host == "localhost") {
                        opener.post { opener.loadUrl(target.toString()) }
                        popup.dismiss()
                        return true
                    }
                    return false
                }
            }
    }
}
