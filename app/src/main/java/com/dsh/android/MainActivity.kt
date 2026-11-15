package com.dsh.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.android.ui.DshRoot
import com.dsh.android.ui.DshViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DshApplication

        setContent {
            val viewModel: DshViewModel = viewModel(factory = DshViewModel.factory(app))
            val state by viewModel.state.collectAsStateWithLifecycle()
            val logs by viewModel.logs.collectAsStateWithLifecycle()
            val mounts by viewModel.mountSummary.collectAsStateWithLifecycle()

            DshRoot(
                state = state,
                logs = logs,
                mountSummary = mounts,
                onInstall = viewModel::install,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onOpenLogs = viewModel::refreshLogTail,
                onExec = viewModel::exec,
                onMountsChanged = viewModel::refreshMounts,
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
