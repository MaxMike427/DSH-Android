package com.dsh.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface

/**
 * Bridge the harness page uses to leave the app for a URL it cannot render
 * usefully itself — in practice the DeepSeek sign-in page.
 *
 * Only `http`/`https` are accepted: the page runs from the local runtime, but a
 * `javascript:` or `intent:` URL arriving here would otherwise let the page start
 * arbitrary components.
 *
 * The sign-in flow depends on the browser coming back: the host registers a
 * loopback callback for the attempt, the platform redirects the browser there,
 * and the host finishes the exchange. The app is not part of that hop, which is
 * why nothing is returned to the page.
 */
class HostBridge(private val context: Context) {

    /** Open [url] in the user's default browser. */
    @JavascriptInterface
    fun openExternal(url: String) {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        val intent = Intent(Intent.ACTION_VIEW, parsed)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
