package com.dsh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dsh.android.runtime.LogLine
import com.dsh.android.runtime.RuntimeState

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
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
    onExec: (String) -> Unit,
) {
    MaterialTheme(colorScheme = DarkColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (state) {
                is RuntimeState.NotInstalled -> InstallScreen(state = state, onInstall = onInstall)
                is RuntimeState.Installing -> InstallScreen(state = state, onInstall = onInstall)
                is RuntimeState.Starting -> StartingScreen(state)
                is RuntimeState.Running -> WebScreen(state, logs, onStop)
                is RuntimeState.Stopped -> InstallScreen(state = state, onInstall = onStart)
                is RuntimeState.Failed -> FailureScreen(state, logs, onStart, onOpenLogs)
            }
        }
    }
}

@Composable
private fun InstallScreen(state: RuntimeState, onInstall: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DSH", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "DeepSeekRuntime · Ubuntu 24.04 · ARM64",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(40.dp))

        when (state) {
            is RuntimeState.Installing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { state.fraction })
                Spacer(Modifier.height(12.dp))
                Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }

            is RuntimeState.Stopped -> {
                if (state.reason != null) {
                    Text(state.reason, fontSize = 13.sp, color = Color(0xFFFFB4AB))
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = onInstall) { Text("Start DeepSeekRuntime") }
            }

            else -> {
                Button(onClick = onInstall) { Text("Set up environment") }
            }
        }
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

@Composable
private fun WebScreen(state: RuntimeState.Running, logs: List<LogLine>, onStop: () -> Unit) {
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(state.url) }

    Box(Modifier.fillMaxSize()) {
        DshWebView(url = url, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(visible = showLogs) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xEE0B0F14))
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Runtime log", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showLogs = false }) { Text("Close") }
                    }
                    LogList(logs, modifier = Modifier.weight(1f))
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { showLogs = !showLogs },
                modifier = Modifier.background(Color(0xCC121821), RoundedCornerShape(20.dp)),
            ) { Text("Logs") }
            TextButton(
                onClick = onStop,
                modifier = Modifier.background(Color(0xCC121821), RoundedCornerShape(20.dp)),
            ) { Text("Stop") }
        }
    }
}

@Composable
private fun DshWebView(url: String, modifier: Modifier = Modifier) {
    var lastError by remember { mutableStateOf<String?>(null) }
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
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    textZoom = 100
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

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) lastError = error.description?.toString()
                    }
                }
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        },
    )
}
