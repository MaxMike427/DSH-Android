package com.dsh.android.runtime

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** One line of runtime output, tagged with its source stream. */
data class LogLine(val stream: LogStream, val text: String) {
    enum class LogStream { STDOUT, STDERR, SYSTEM }
}

sealed interface RuntimeState {
    data object NotInstalled : RuntimeState
    data class Installing(val message: String, val fraction: Float) : RuntimeState
    data class Starting(val message: String) : RuntimeState
    data class Running(val url: String, val pid: Int) : RuntimeState
    data class Stopped(val reason: String?) : RuntimeState
    data class Failed(val message: String, val detail: String? = null) : RuntimeState
}

/**
 * Owns the bundled Ubuntu + PRoot runtime: one-time extraction, then the
 * `dsh web` server process.
 *
 * Every command runs as the app's own uid inside PRoot (`-0` fakes root for the
 * guest). Nothing here requires root, and no binaries are written outside the
 * app's private storage.
 */
class DshRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val paths = DshPaths(context)
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInstalled)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    @Volatile private var serverProcess: Process? = null

    val port: Int = 3080
    val localUrl: String get() = "http://127.0.0.1:$port"

    fun log(stream: LogLine.LogStream, text: String) {
        _logs.value = (_logs.value + LogLine(stream, text)).takeLast(MAX_LOG_LINES)
    }

    suspend fun refreshState() = withContext(Dispatchers.IO) {
        paths.ensureDirectories()
        _state.value = when {
            !paths.isInstalled() -> RuntimeState.NotInstalled
            serverProcess?.isAlive == true -> RuntimeState.Running(localUrl, processId(serverProcess))
            else -> RuntimeState.Stopped(null)
        }
    }

    /** Extract the bundled payload. Safe to call repeatedly; resumes from the marker. */
    suspend fun install(onProgress: (Float, String) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        paths.ensureDirectories()
        if (paths.isInstalled()) {
            _state.value = RuntimeState.Stopped(null)
            return@withContext
        }

        try {
            _state.value = RuntimeState.Installing("Preparing storage", 0f)

            val assets = context.assets
            val manifest = assets.open("runtime-manifest.json").bufferedReader().use { it.readText() }
            val stamp = "installed:${manifest.hashCode()}:${BuildConfigPayload.VERSION}"

            val prootBytes = assets.openFd("proot.tar.gz").use { it.length }
            val rootfsBytes = assets.openFd("rootfs.tar.gz").use { it.length }
            val totalBytes = (prootBytes + rootfsBytes).coerceAtLeast(1)

            // PRoot first: it is small, and a failed rootfs extract must not leave
            // a marker that claims a usable install.
            _state.value = RuntimeState.Installing("Extracting PRoot", 0.01f)
            assets.open("proot.tar.gz").use { input ->
                TarExtractor.extract(input, paths.prefix) { done ->
                    val fraction = 0.01f + (done.toFloat() / totalBytes) * 0.09f
                    onProgress(fraction.coerceIn(0.01f, 0.10f), "Extracting PRoot")
                }
            }
            listOf(
                paths.proot,
                paths.prootLoader,
                File(paths.prefix, "libexec/proot/loader32"),
            ).forEach { file ->
                if (file.exists()) Os.chmod(file.absolutePath, 0b111_101_101) // 0755
            }

            _state.value = RuntimeState.Installing("Extracting Ubuntu", 0.10f)
            assets.open("rootfs.tar.gz").use { input ->
                TarExtractor.extract(input, paths.rootfs) { done ->
                    val fraction = 0.10f + (done.toFloat() / totalBytes) * 0.89f
                    onProgress(fraction.coerceIn(0.10f, 0.99f), "Extracting Ubuntu")
                }
            }

            // Keep guest /root on app storage so upgrades of the rootfs can keep data.
            paths.home.mkdirs()
            paths.installMarker.writeText(stamp)

            _state.value = RuntimeState.Stopped(null)
        } catch (t: Throwable) {
            log(LogLine.LogStream.SYSTEM, "install failed: ${t.stackTraceToString()}")
            _state.value = RuntimeState.Failed("Installation failed", t.message)
        }
    }

    /** Start `dsh web` inside the guest and wait until the HTTP endpoint answers. */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (serverProcess?.isAlive == true) {
            _state.value = RuntimeState.Running(localUrl, processId(serverProcess))
            return@withContext
        }
        if (!paths.isInstalled()) {
            _state.value = RuntimeState.NotInstalled
            return@withContext
        }

        _state.value = RuntimeState.Starting("Launching DeepSeekRuntime")
        paths.serverLog.parentFile?.mkdirs()

        try {
            writeNetworkConfig()
            val process = ProcessBuilder(prootCommand(listOf(
                "/bin/bash", "-lc", startScript()
            )))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.serverLog))
                .start()

            serverProcess = process

            val ready = waitForHttp(process, timeoutSeconds = 180)
            if (ready) {
                _state.value = RuntimeState.Running(localUrl, processId(process))
                log(LogLine.LogStream.SYSTEM, "dsh web ready at $localUrl (pid ${processId(process)})")
                monitor(process)
            } else {
                val tail = tailLog()
                process.destroy()
                _state.value = RuntimeState.Failed("DeepSeekRuntime did not start", tail)
            }
        } catch (t: Throwable) {
            log(LogLine.LogStream.SYSTEM, "start failed: ${t.stackTraceToString()}")
            _state.value = RuntimeState.Failed("Could not start DeepSeekRuntime", t.message)
        }
    }

    private fun monitor(process: Process) {
        scope.launch(Dispatchers.IO) {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (_state.value is RuntimeState.Running) {
                _state.value = RuntimeState.Stopped("dsh exited with code $code")
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val process = serverProcess
        serverProcess = null
        if (process != null) {
            runCatching {
                // Terminate the whole guest tree: PRoot runs the server as a child.
                ProcessBuilder("kill", "-TERM", processId(process).toString()).start().waitFor()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
        _state.value = RuntimeState.Stopped(null)
    }

    /** Run a one-shot command inside the guest and stream its output. */
    fun exec(command: String): Flow<LogLine> = callbackFlow {
        val process = runCatching {
            ProcessBuilder(prootCommand(listOf("/bin/bash", "-lc", command)))
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            trySend(LogLine(LogLine.LogStream.SYSTEM, "exec failed: ${it.message}"))
            close()
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val thread = Thread {
            reader.forEachLine { trySend(LogLine(LogLine.LogStream.STDOUT, it)) }
            trySend(LogLine(LogLine.LogStream.SYSTEM, "exit ${process.waitFor()}"))
            close()
        }
        thread.start()
        awaitClose {
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /** Full PRoot argv for a guest command. */
    fun prootCommand(guestCommand: List<String>): List<String> {
        val cmd = mutableListOf(
            paths.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", paths.rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${paths.home.absolutePath}:/root",
            "-b", "${paths.tmp.absolutePath}:/tmp",
            "-w", "/root",
        )
        if (paths.resolvConf.isFile) cmd += listOf("-b", "${paths.resolvConf.absolutePath}:/etc/resolv.conf")
        if (paths.hostsFile.isFile) cmd += listOf("-b", "${paths.hostsFile.absolutePath}:/etc/hosts")
        cmd += guestCommand
        return cmd
    }

    fun environment(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to paths.tmp.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_LOADER" to paths.prootLoader.absolutePath,
        "HOME" to paths.home.absolutePath,
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
    )

    private fun startScript(): String = """
        export NVM_DIR=/root/.nvm
        [ -s "${'$'}NVM_DIR/nvm.sh" ] && . "${'$'}NVM_DIR/nvm.sh"
        cd /root
        # /root/.dsh/profiles/web/cordis.patch.yml already carries the PRoot
        # composition (local bash executor instead of the sandboxed one), so no
        # launcher flag is needed here.
        exec dsh web --no-open --port $port
    """.trimIndent()

    private fun writeNetworkConfig() {
        // PRoot shares the host network; resolv.conf is bound so DNS works.
        runCatching {
            paths.resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            paths.hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
    }

    private fun waitForHttp(process: Process, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            val ok = runCatching {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                socket.close()
                true
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(500)
        }
        return false
    }

    fun tailLog(lines: Int = 80): String = runCatching {
        paths.serverLog.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    fun runtimeLogFile(): File = paths.serverLog

    /**
     * `Process.pid()` is a Java 9 API that Android's libcore does not expose, so
     * the field is read reflectively. Returns -1 when the process is gone.
     */
    private fun processId(process: Process?): Int {
        if (process == null) return -1
        return runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrElse {
            runCatching {
                val field = process.javaClass.getMethod("pid")
                field.invoke(process) as Int
            }.getOrDefault(-1)
        }
    }

    fun pathsForUi(): DshPaths = paths

    companion object {
        private const val MAX_LOG_LINES = 2000
    }
}

/** Filled by the Gradle build so the install marker tracks payload changes. */
object BuildConfigPayload {
    const val VERSION: String = "1"
}

