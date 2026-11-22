package com.dsh.android.runtime

import android.content.Context
import android.system.Os
import com.dsh.android.storage.StorageAccess
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
        // Mirror to disk: the UI is not reachable when the app crashes or the
        // failure happens during early extraction, and `run-as cat` is the only
        // way to read it back on a device.
        runCatching {
            paths.ensureDirectories()
            File(paths.logs, "app.log").appendText("${timestamp()} ${stream.name} $text\n")
        }
    }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())

    suspend fun refreshState() = withContext(Dispatchers.IO) {
        paths.ensureDirectories()
        // A payload that changed under an existing tree is not "installed": the
        // runtime directories are there but they belong to the previous APK, so
        // report NotInstalled and let install() replace them.
        val stale = paths.isInstalled() && paths.payloadStamp() != expectedStamp()
        if (stale) {
            log(LogLine.LogStream.SYSTEM, "runtime: the bundled payload differs from the extracted tree")
        }
        _state.value = when {
            !paths.isInstalled() || stale -> RuntimeState.NotInstalled
            serverProcess?.isAlive == true ->
                RuntimeState.Running(readTokenUrl() ?: localUrl, processId(serverProcess))
            else -> RuntimeState.Stopped(null)
        }
    }

    /**
     * Stamp of the payload this APK ships.
     *
     * The manifest embeds the archive sizes and hashes, so its hash changes
     * exactly when the image does; the constant is for layout changes that keep
     * the same archives. Both together decide whether an extracted tree is still
     * the one this build expects.
     */
    private fun expectedStamp(): String = runCatching {
        context.assets.open("runtime-manifest.json").bufferedReader().use { reader ->
            "installed:${reader.readText().hashCode()}:${BuildConfigPayload.VERSION}"
        }
    }.getOrElse {
        // A build without the payload asset is broken anyway; returning an empty
        // stamp lets install() fail later with the extraction error instead of
        // taking down state refresh on launch.
        ""
    }

    /**
     * Guest state that belongs to the user, not to the image.
     *
     * The harness keeps sessions, stored credentials and plugin data under
     * `/root/.dsh` (the profile bundles are separate, under `.dsh/profiles`, and
     * are image-owned). A payload swap has to survive those, so they are moved
     * out before the old rootfs is deleted and put back once extraction lands.
     */
    private fun stashGuestData() {
        val dsh = File(paths.rootfs, "root/.dsh")
        if (!dsh.isDirectory) return
        val stash = File(paths.state, "upgrade/.dsh")
        stash.parentFile?.deleteRecursively()
        stash.parentFile?.mkdirs()
        if (!dsh.renameTo(stash)) {
            // Different volumes are not a case here, but a failed move must not
            // silently drop user data, so fall back to a copy.
            dsh.copyRecursively(stash, overwrite = true)
        }
        // Profiles are rebuilt from the image; only keep what the user owns.
        File(stash, "profiles").deleteRecursively()
        log(LogLine.LogStream.SYSTEM, "install: kept guest data for the upgrade")
    }

    private fun restoreGuestData() {
        val stash = File(paths.state, "upgrade/.dsh")
        if (!stash.isDirectory) return
        val dsh = File(paths.rootfs, "root/.dsh")
        dsh.mkdirs()
        stash.listFiles()?.forEach { entry ->
            val target = File(dsh, entry.name)
            target.deleteRecursively()
            if (!entry.renameTo(target)) entry.copyRecursively(target, overwrite = true)
        }
        stash.parentFile?.deleteRecursively()
        log(LogLine.LogStream.SYSTEM, "install: restored guest data")
    }

    /** Extract the bundled payload. Safe to call repeatedly; resumes from the marker. */
    suspend fun install(onProgress: (Float, String) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        paths.ensureDirectories()

        // The stamp describes the payload, not the app build: the manifest holds
        // the archive hashes, so a new APK carries a new stamp. Without this
        // check an upgrade finds the marker and stops, leaving the previous image
        // in place — which silently keeps removed plugins (and their failures)
        // alive on every device that updates instead of reinstalling.
        val stamp = expectedStamp()
        // A stale tree can exist without a usable marker (an interrupted install,
        // or a marker cleared by hand). It must still be replaced before writing:
        // extracting over it hits guest-created read-only files and fails EACCES.
        val staleTree = paths.rootfs.isDirectory && paths.rootfs.list()?.isNotEmpty() == true
        val upgrading = paths.isInstalled() || staleTree
        if (paths.isInstalled() && paths.payloadStamp() == stamp) {
            _state.value = RuntimeState.Stopped(null)
            return@withContext
        }

        try {
            _state.value = RuntimeState.Installing(
                if (upgrading) "Replacing the runtime" else "Preparing storage",
                0f,
            )
            log(
                LogLine.LogStream.SYSTEM,
                if (upgrading) {
                    "install: payload changed, re-extracting over the old runtime"
                } else {
                    "install: begin"
                },
            )

            val assets = context.assets
            log(LogLine.LogStream.SYSTEM, "install: manifest ok")

            if (upgrading) {
                // The new image owns every file under rootfs, and extracting over
                // the old tree would leave anything the new payload dropped in
                // place — a removed plugin keeps loading from its stale directory.
                // It would also fail outright on read-only files the guest left
                // behind (EACCES), so a stale tree is always replaced, even when
                // the marker is missing and "upgrading" cannot be inferred from it.
                // Guest /root is image-owned except for the harness data directory,
                // which is moved aside and restored so sessions survive.
                stashGuestData()
                paths.rootfs.deleteRecursively()
            }

            val prootBytes = assets.openFd("proot.tar.xz").use { it.length }
            val rootfsBytes = assets.openFd("rootfs.tar.xz").use { it.length }
            val totalBytes = (prootBytes + rootfsBytes).coerceAtLeast(1)
            log(LogLine.LogStream.SYSTEM, "install: proot=$prootBytes rootfs=$rootfsBytes")

            // PRoot first: it is small, and a failed rootfs extract must not leave
            // a marker that claims a usable install.
            //
            // The payload is archived from the files/ root (it contains usr/...),
            // so it extracts into `files`, not into `files/usr`.
            _state.value = RuntimeState.Installing("Extracting PRoot", 0.01f)
            assets.open("proot.tar.xz").use { input ->
                TarExtractor.extractXz(input, paths.files) { done ->
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
            assets.open("rootfs.tar.xz").use { input ->
                TarExtractor.extractXz(input, paths.rootfs) { done ->
                    val fraction = 0.10f + (done.toFloat() / totalBytes) * 0.89f
                    onProgress(fraction.coerceIn(0.10f, 0.99f), "Extracting Ubuntu")
                }
            }

            // Keep guest /root on app storage so upgrades of the rootfs can keep data.
            paths.home.mkdirs()

            // The tar reader stops silently at a truncated stream, so verify the
            // tree is actually complete before declaring success: a missing
            // /bin/bash or /etc/os-release means extraction was cut short.
            val required = listOf(
                File(paths.rootfs, "bin/bash"),
                File(paths.rootfs, "usr/bin/env"),
                File(paths.rootfs, "etc/os-release"),
                File(paths.rootfs, "opt/dsh/cordis.patch.yml"),
                paths.proot,
                paths.prootLoader,
            )
            val missing = required.filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString { it.absolutePath }
                log(LogLine.LogStream.SYSTEM, "install: incomplete extraction, missing $names")
                _state.value = RuntimeState.Failed(
                    "Extraction incomplete",
                    "missing: $names",
                )
                return@withContext
            }

            paths.installMarker.writeText(stamp)
            restoreGuestData()
            applyMirror()
            log(LogLine.LogStream.SYSTEM, "install: complete")
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
        // Start each run with an empty log: the token is printed at startup and
        // an appended log would keep offering tokens from previous runs, which
        // are no longer valid.
        runCatching { paths.serverLog.delete() }

        try {
            writeNetworkConfig()
            val process = ProcessBuilder(prootCommand(listOf(
                "/bin/bash", "-lc", startScript()
            )))
                .apply { environment().putAll(this@DshRuntime.environment()) }
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.serverLog))
                .start()

            serverProcess = process
            log(LogLine.LogStream.SYSTEM, "start: spawned pid ${processId(process)}")

            val ready = waitForHttp(process, timeoutSeconds = 180)
            if (ready) {
                // `dsh web` prints a tokenized URL and refuses requests without
                // it. Loading the bare host would show "authentication required",
                // so the URL from its own log is what the WebView must open.
                val url = readTokenUrl() ?: localUrl
                _state.value = RuntimeState.Running(url, processId(process))
                log(LogLine.LogStream.SYSTEM, "dsh web ready at $url (pid ${processId(process)})")
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
    /**
     * Run one command inside the guest and stream its output.
     *
     * The wrapper sources nvm first so `node`, `npm`, `pnpm` and `dsh` resolve
     * from the terminal; a plain `bash -lc` would only have the system PATH.
     */
    fun exec(command: String): Flow<LogLine> = callbackFlow {
        val wrapped = """
            export NVM_DIR=/root/.nvm
            [ -s "${'$'}NVM_DIR/nvm.sh" ] && . "${'$'}NVM_DIR/nvm.sh"
            cd /root
            $command
        """.trimIndent()

        val process = runCatching {
            ProcessBuilder(prootCommand(listOf("/bin/bash", "-lc", wrapped)))
                .apply { environment().putAll(this@DshRuntime.environment()) }
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
            // Fake root for the guest: apt, nvm and npm all expect uid 0, and
            // `-0` is shorthand for `-i 0:0`.
            //
            // Deliberately NOT combined with a second `-i uid:gid`: PRoot keeps
            // only the LAST -i/-0/-S option (handle_option_i frees the previous
            // extension), so adding -i after -0 silently disables the fake root.
            // Files written into shared storage still end up owned by the app's
            // uid, because PRoot cannot change host ownership without real root.
            "-0",
            "-r", paths.rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${paths.tmp.absolutePath}:/tmp",
            "-w", "/root",
        )

        // Only override the guest home when it actually holds user data. The
        // toolchain (nvm, node, dsh) lives in the rootfs at /root, so mounting an
        // empty directory over it would hide every installed tool.
        if (paths.home.isDirectory && (paths.home.list()?.isNotEmpty() == true)) {
            cmd += listOf("-b", "${paths.home.absolutePath}:/root")
        }
        if (paths.resolvConf.isFile) cmd += listOf("-b", "${paths.resolvConf.absolutePath}:/etc/resolv.conf")
        if (paths.hostsFile.isFile) cmd += listOf("-b", "${paths.hostsFile.absolutePath}:/etc/hosts")

        // Android shared storage. Bind each existing directory individually so a
        // missing one (or a denied permission) never aborts the whole spawn.
        //
        // PRoot sanitizes each bind source with stat() before mounting. On the
        // FUSE-backed shared volume that call can fail while real reads and
        // writes still succeed, which prints "can't sanitize binding". The path
        // is still listed so the guest gets the mount.
        StorageAccess.mountPoints(context).forEach { (name, dir) ->
            cmd += listOf("-b", "${dir.absolutePath}:${StorageAccess.guestPath(name)}")
        }

        cmd += guestCommand
        return cmd
    }

    fun environment(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to paths.tmp.absolutePath,
        // PRoot's ptrace-based syscall interception conflicts with Android's
        // seccomp filter on some kernels; Termux ships this default too.
        "PROOT_NO_SECCOMP" to "1",
        // Point at the bundled loader so PRoot does not have to write one into
        // PROOT_TMP_DIR on every exec (and cannot fail if that dir is noexec).
        "PROOT_LOADER" to paths.prootLoader.absolutePath,
        // Guest-relative, not host-relative: /root is bound to files/home.
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
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
        // PRoot shares the host network, but the guest has no systemd-resolved,
        // so its own /etc/resolv.conf (pointing at 127.0.0.53) cannot resolve.
        // Writing a real resolver into the rootfs is what makes DNS work; the
        // bind mount is kept as a secondary path for the state directory.
        runCatching {
            paths.resolvConf.writeText(
                """
                nameserver 223.5.5.5
                nameserver 119.29.29.29
                nameserver 1.1.1.1
                """.trimIndent() + "\n"
            )
            paths.hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")

            val guestResolv = File(paths.rootfs, "etc/resolv.conf")
            guestResolv.parentFile?.mkdirs()
            guestResolv.writeText(paths.resolvConf.readText())
        }
    }

    /** Tsinghua TUNA mirror; Ubuntu Base ships ports.ubuntu.com, which is slow here. */
    private val mirrorSources = """
        # Tsinghua University mirror (TUNA) - arm64/ports
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-backports main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main restricted universe multiverse
    """.trimIndent() + "\n"

    /**
     * Rewrite the guest's apt sources to the Tsinghua mirror.
     *
     * Done after extraction so `apt-get update` inside the guest works without
     * the user editing sources by hand.
     */
    private fun applyMirror(): Boolean = runCatching {
        val sources = File(paths.rootfs, "etc/apt/sources.list")
        sources.parentFile?.mkdirs()
        sources.writeText(mirrorSources)
        // noble also reads deb822 `.sources` fragments; a leftover ubuntu.sources
        // would keep pointing at the default mirror.
        File(paths.rootfs, "etc/apt/sources.list.d").listFiles()
            ?.filter { it.extension == "sources" || it.extension == "list" }
            ?.forEach { it.delete() }
        log(LogLine.LogStream.SYSTEM, "install: apt mirror set to tuna")
        true
    }.getOrElse {
        log(LogLine.LogStream.SYSTEM, "install: could not set apt mirror: ${it.message}")
        false
    }

    /**
     * Read the tokenized URL `dsh web` prints on startup.
     *
     * The server rejects unauthenticated requests, so this exact URL (token
     * included) is the only way into the UI. Polls briefly because the line is
     * flushed just before the port starts accepting.
     */
    private fun readTokenUrl(): String? {
        val pattern = Regex("""http://127\.0\.0\.1:$port/\?token=[A-Za-z0-9_\-]+""")

        // The log is appended to across restarts, so it can hold tokens from
        // earlier runs; only the last one is valid. Search from the end.
        fun latest(): String? = runCatching {
            val lines = paths.serverLog.readLines()
            lines.asReversed().firstNotNullOfOrNull { pattern.find(it)?.value }
        }.getOrNull()

        repeat(30) {
            latest()?.let { return it }
            Thread.sleep(200)
        }
        return latest()
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

