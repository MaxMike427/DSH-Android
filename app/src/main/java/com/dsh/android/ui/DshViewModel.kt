package com.dsh.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.dsh.android.DshApplication
import com.dsh.android.runtime.DshRuntime
import com.dsh.android.runtime.LogLine
import com.dsh.android.runtime.RuntimeState
import com.dsh.android.storage.StorageAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DshViewModel(
    private val appContext: Context,
    private val runtime: DshRuntime,
) : ViewModel() {

    val state: StateFlow<RuntimeState> = runtime.state
    val logs: StateFlow<List<LogLine>> = runtime.logs

    private val _installProgress = MutableStateFlow(0f)
    val installProgress: StateFlow<Float> = _installProgress

    private val _terminalOutput = MutableStateFlow<List<String>>(emptyList())
    val terminalOutput: StateFlow<List<String>> = _terminalOutput

    private val _mountSummary = MutableStateFlow<List<String>>(emptyList())
    val mountSummary: StateFlow<List<String>> = _mountSummary

    init {
        viewModelScope.launch {
            runtime.refreshState()
            refreshMounts()
            if (runtime.state.value is RuntimeState.NotInstalled) {
                install()
            } else {
                start()
            }
        }
    }

    fun refreshMounts() {
        _mountSummary.value = StorageAccess.mountPoints(appContext).map { (name, dir) ->
            "${StorageAccess.guestPath(name)}  <-  ${dir.absolutePath}"
        }
    }

    fun install() {
        viewModelScope.launch {
            runtime.install { fraction, message ->
                _installProgress.value = fraction
                runtime.log(LogLine.LogStream.SYSTEM, "$message ${(fraction * 100).toInt()}%")
            }
            if (runtime.state.value is RuntimeState.Stopped) start()
        }
    }

    fun start() {
        viewModelScope.launch { runtime.start() }
    }

    fun stop() {
        viewModelScope.launch { runtime.stop() }
    }

    fun refreshLogTail() {
        val tail = runtime.tailLog()
        runtime.log(LogLine.LogStream.SYSTEM, tail)
    }

    fun exec(command: String) {
        viewModelScope.launch {
            _terminalOutput.value = _terminalOutput.value + "\$ $command"
            runtime.exec(command).collect { line ->
                _terminalOutput.value = (_terminalOutput.value + line.text).takeLast(500)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        fun factory(app: DshApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DshViewModel(app.applicationContext, app.runtime) as T
            }
        }
    }
}
