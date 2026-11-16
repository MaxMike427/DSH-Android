package com.dsh.android

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.android.ui.DshRoot
import com.dsh.android.ui.DshViewModel
import com.dsh.android.ui.VolumeKeyHost
import com.dsh.android.ui.isVolumeKey

/**
 * Single-screen host for the DeepSeekRuntime Web UI.
 *
 * Implements [VolumeKeyHost] so the Web UI can use the hardware volume keys to
 * toggle its control overlay instead of showing permanent floating buttons.
 */
class MainActivity : ComponentActivity(), VolumeKeyHost {

    override var onVolumeKey: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DshApplication

        setContent {
            val viewModel: DshViewModel = viewModel(factory = DshViewModel.factory(app))
            val state by viewModel.state.collectAsStateWithLifecycle()
            val logs by viewModel.logs.collectAsStateWithLifecycle()
            val mounts by viewModel.mountSummary.collectAsStateWithLifecycle()
            val terminal by viewModel.terminalOutput.collectAsStateWithLifecycle()

            DshRoot(
                state = state,
                logs = logs,
                mountSummary = mounts,
                terminalOutput = terminal,
                onInstall = viewModel::install,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onOpenLogs = viewModel::refreshLogTail,
                onExec = viewModel::exec,
                onMountsChanged = viewModel::refreshMounts,
            )
        }
    }

    /**
     * The WebView has focus, so key events arrive here first. A registered
     * handler consumes them; otherwise the system's normal volume behaviour is
     * left untouched (so the keys still work on the install screen).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode)) {
            val handled = onVolumeKey?.invoke(keyCode) == true
            if (handled) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
