package com.dsh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.KeyEvent
import com.dsh.android.runtime.LogLine
import com.dsh.android.runtime.RuntimeState
import com.dsh.android.storage.StorageAccessCard

/** Which overlay the volume keys opened, if any. */
private enum class Overlay { NONE, CONTROLS, LOGS, TERMINAL }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AB7FF),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF121821),
    surfaceVariant = Color(0xFF1B2430),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
)

@Composable
fun DshRoot(
    state: RuntimeState,
    logs: List<LogLine>,
    mountSummary: List<String>,
    terminalOutput: List<String>,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
    onExec: (String) -> Unit,
    onMountsChanged: () -> Unit,
) {
    MaterialTheme(colorScheme = DarkColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (state) {
                is RuntimeState.NotInstalled -> InstallScreen(state, onInstall, mountSummary, onMountsChanged)
                is RuntimeState.Installing -> InstallScreen(state, onInstall, mountSummary, onMountsChanged)
                is RuntimeState.Starting -> StartingScreen(state)
                is RuntimeState.Running -> WebScreen(
                    state = state,
                    logs = logs,
                    terminalOutput = terminalOutput,
                    onStop = onStop,
                    onExec = onExec,
                )
                is RuntimeState.Stopped -> InstallScreen(state, onStart, mountSummary, onMountsChanged)
                is RuntimeState.Failed -> FailureScreen(state, logs, onStart, onOpenLogs)
            }
        }
    }
}

@Composable
private fun InstallScreen(
    state: RuntimeState,
    onInstall: () -> Unit,
    mountSummary: List<String>,
    onMountsChanged: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DSH", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "DeepSeekRuntime · Ubuntu 24.04 · ARM64",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(28.dp))

        when (state) {
            is RuntimeState.Installing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { state.fraction })
                Spacer(Modifier.height(12.dp))
                Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }

            is RuntimeState.Stopped -> {
                if (state.reason != null) {
                    Text(state.reason, fontSize = 13.sp, color = Color(0xFFFFB4AB))
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = onInstall) { Text("Start DeepSeekRuntime") }
            }

            else -> {
                Button(onClick = onInstall) { Text("Set up environment") }
            }
        }

        Spacer(Modifier.height(24.dp))
        StorageAccessCard(mountSummary = mountSummary, onMountsChanged = onMountsChanged)
    }
}

@Composable
private fun StartingScreen(state: RuntimeState.Starting) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(state.message)
    }
}

@Composable
private fun FailureScreen(
    state: RuntimeState.Failed,
    logs: List<LogLine>,
    onRetry: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Text(state.message, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFB4AB))
        Spacer(Modifier.height(12.dp))
        state.detail?.let {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 20, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onOpenLogs) { Text("Show log") }
        }
        Spacer(Modifier.height(16.dp))
        LogList(logs, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogList(logs: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(logs) { line ->
            Text(
                text = line.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = when (line.stream) {
                    LogLine.LogStream.STDERR -> Color(0xFFFFB4AB)
                    LogLine.LogStream.SYSTEM -> Color(0xFF7AB7FF)
                    else -> Color(0xFFE6EDF3).copy(alpha = 0.85f)
                },
            )
        }
    }
}

/**
 * Full-screen Web UI.
 *
 * The harness fills the whole surface: no app chrome sits on top of it, because
 * the floating buttons overlapped the harness's own controls. Volume + opens a
 * small panel with Logs / Terminal / Stop; volume − closes it. The keys are
 * consumed so they do not also change media volume while the panel is in use.
 */
@Composable
private fun WebScreen(
    state: RuntimeState.Running,
    logs: List<LogLine>,
    terminalOutput: List<String>,
    onStop: () -> Unit,
    onExec: (String) -> Unit,
) {
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    val url = state.url
    val context = LocalContext.current

    // Volume keys are delivered to the Activity, so intercept there and drive
    // this state through a holder rather than fighting the WebView's focus.
    VolumeKeyOverlay(
        onVolumeUp = {
            overlay = if (overlay == Overlay.NONE) Overlay.CONTROLS else Overlay.NONE
        },
        onVolumeDown = { overlay = Overlay.NONE },
    )

    Box(Modifier.fillMaxSize()) {
        DshWebView(url = url, modifier = Modifier.fillMaxSize())

        if (overlay == Overlay.CONTROLS) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF20B0F14))
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Runtime", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { overlay = Overlay.NONE }) { Text("Close") }
                }
                ControlsPanel(
                    onLogs = { overlay = Overlay.LOGS },
                    onTerminal = { overlay = Overlay.TERMINAL },
                    onStop = onStop,
                    onOpenBrowser = { openInBrowser(context, url) },
                )
            }
        }

        if (overlay == Overlay.LOGS) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF20B0F14))
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Runtime log", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { overlay = Overlay.NONE }) { Text("Close") }
                }
                LogList(logs, modifier = Modifier.weight(1f))
            }
        }

        if (overlay == Overlay.TERMINAL) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF20B0F14))
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Terminal", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { overlay = Overlay.NONE }) { Text("Close") }
                }
                TerminalPanel(
                    output = terminalOutput,
                    onExec = onExec,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Open the harness UI in the system browser.
 *
 * The token is part of the URL and the server rejects anything else, so the
 * exact `dsh web` URL must be handed over unchanged. Android's package visibility
 * rules and some browsers strip the query string, so the intent is explicit and
 * verified before it is considered successful.
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            android.util.Log.e("DSH_WEBVIEW", "cannot open browser: ${it.message}")
        }
}

@Composable
private fun ControlsPanel(
    onLogs: () -> Unit,
    onTerminal: () -> Unit,
    onStop: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = onTerminal, modifier = Modifier.fillMaxWidth()) { Text("Open terminal") }
        Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) { Text("Open in browser") }
        Button(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("Show logs") }
        TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop runtime") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Volume+ toggles this panel, Volume- closes it.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/**
 * Terminal into the Ubuntu guest.
 *
 * Each line runs as its own bash invocation (no persistent shell state), which
 * matches how the harness executes commands and keeps a hung command from
 * wedging the session.
 */
@Composable
private fun TerminalPanel(
    output: List<String>,
    onExec: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) listState.animateScrollToItem(output.size - 1)
    }

    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            items(output) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE6EDF3).copy(alpha = 0.9f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier
                    .weight(1f)
                    .imePadding(),
                placeholder = { Text("command", fontSize = 12.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (command.isNotBlank()) {
                        onExec(command)
                        command = ""
                    }
                }),
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Button(
                onClick = {
                    if (command.isNotBlank()) {
                        onExec(command)
                        command = ""
                    }
                },
            ) { Text("Run") }
        }
    }
}

/** Bridge Activity-level key events into Compose state. */
@Composable
private fun VolumeKeyOverlay(onVolumeUp: () -> Unit, onVolumeDown: () -> Unit) {
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(view, onVolumeUp, onVolumeDown) {
        val activity = context.findActivity()
        val owner = activity as? VolumeKeyHost
        owner?.onVolumeKey = { code ->
            when (code) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    onVolumeUp(); true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    onVolumeDown(); true
                }
                else -> false
            }
        }
        onDispose { owner?.onVolumeKey = null }
    }
}

@Composable
private fun DshWebView(url: String, modifier: Modifier = Modifier) {
    var lastError by remember { mutableStateOf<String?>(null) }
    // Load each URL exactly once. `WebView.url` reports the normalized URL after
    // redirects, so comparing it against the requested URL would reload the page
    // on every recomposition and leave the screen blank.
    val loaded = remember { mutableStateOf<String?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    cacheMode = WebSettings.LOAD_DEFAULT
                    textZoom = 100
                    // This is a desktop-class SPA that already ships responsive
                    // meta viewport handling. `useWideViewPort` +
                    // `loadWithOverviewMode` make WebView scale a wide virtual
                    // viewport into the device width, which collapsed the whole
                    // page into a thin strip at the bottom of the screen (only
                    // ~8px of text was painted). Both are off by default for
                    // responsive pages.
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    // Let the page use the real device pixel width.
                    mediaPlaybackRequiresUserGesture = false
                }
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.rgb(21, 21, 23))
                // The WebView sits inside a Compose Box and must receive touches
                // itself: without clickable/focusable it never gets them, which
                // makes the whole page appear frozen even though it renders.
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                setOnTouchListener { view, event ->
                    // Hand the event back to the WebView after taking focus, so
                    // scrolling and links keep working.
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        view.requestFocus()
                    }
                    false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        return if (target.host == "127.0.0.1" || target.host == "localhost") {
                            false
                        } else {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, target)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            true
                        }
                    }

                        override fun onPageStarted(view: WebView, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                            // Run as soon as the document exists: the page's own
                            // `height: 100%` rules collapse the shell before the
                            // first paint on this WebView, so waiting for
                            // onPageFinished leaves a visible blank frame.
                            view.evaluateJavascript(VIEWPORT_FIX_SCRIPT, null)
                        }

                        override fun onPageFinished(view: WebView, finishedUrl: String?) {
                            // Re-apply after load: the guard in the script makes
                            // this a no-op when onPageStarted already ran it, and
                            // it covers documents that replaced `#root` while
                            // loading or were restored from the back/forward cache.
                            view.evaluateJavascript(VIEWPORT_FIX_SCRIPT, null)
                            android.util.Log.i(
                                "DSH_WEBVIEW",
                                "onPageFinished: $finishedUrl size=${view.width}x${view.height}",
                            )
                            view.evaluateJavascript(LAYOUT_PROBE_SCRIPT) { result ->
                                android.util.Log.i("DSH_WEBVIEW", "layout: $result")
                            }
                        }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            lastError = error.description?.toString()
                            android.util.Log.e("DSH_WEBVIEW", "error ${error.errorCode}: ${error.description}")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse,
                    ) {
                        android.util.Log.e(
                            "DSH_WEBVIEW",
                            "http ${errorResponse.statusCode} for ${request.url}",
                        )
                    }
                }
                // `loadUrl` must wait until the WebView has a real size.
                //
                // The page shell is laid out with `html, body, #root { height:
                // 100% }`, which resolves against the initial containing block.
                // Loading while the view is still 0x0 (its state when the Compose
                // factory runs) makes this WebView latch a zero-height viewport:
                // every percentage height and viewport unit then stays 0 for the
                // life of the document, so the whole UI collapses into the
                // background and only the page colour gets painted. The page's
                // `dvh`-sized dialogs collapse the same way.
                //
                // A reload after the view had a size stayed broken too, so the
                // first load is what latches it: defer it instead of retrying.
                val startWhenSized = object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (width <= 0 || height <= 0) return true
                        viewTreeObserver.removeOnPreDrawListener(this)
                        if (loaded.value != url) {
                            loaded.value = url
                            loadUrl(url)
                        }
                        return true
                    }
                }
                viewTreeObserver.addOnPreDrawListener(startWhenSized)
            }
        },
        update = { view ->
            if (loaded.value != url) {
                loaded.value = url
                view.loadUrl(url)
            }
        },
    )
}
