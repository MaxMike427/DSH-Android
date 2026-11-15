package com.dsh.android.runtime

import android.content.Context
import java.io.File

/**
 * Filesystem layout of the bundled runtime.
 *
 * PRoot's loader is compiled against Termux's prefix, so the app reproduces that
 * layout inside its private files directory:
 *
 *   files/usr/bin/proot        Termux-built PRoot binary
 *   files/usr/libexec/proot/   loader + loader32
 *   files/usr/lib/             libtalloc, libandroid-shmem, libandroid-support
 *   files/rootfs/              Ubuntu 24.04.x arm64
 *   files/home/                guest /root, persisted across upgrades
 *   files/logs/                runtime logs
 *   files/state/               install marker + manifest
 */
class DshPaths(context: Context) {
    val files: File = context.filesDir
    val prefix: File = File(files, "usr")
    val rootfs: File = File(files, "rootfs")
    val home: File = File(files, "home")
    val logs: File = File(files, "logs")
    val state: File = File(files, "state")
    val tmp: File = File(files, "tmp")

    val proot: File get() = File(prefix, "bin/proot")
    val prootLoader: File get() = File(prefix, "libexec/proot/loader")
    val installMarker: File get() = File(state, "installed.json")
    val resolvConf: File get() = File(state, "resolv.conf")
    val hostsFile: File get() = File(state, "hosts")
    val serverLog: File get() = File(logs, "dsh-web.log")

    /** Guest path of the persisted home directory (bound over /root). */
    val guestHome: String = "/root"

    fun ensureDirectories() {
        listOf(files, prefix, rootfs, home, logs, state, tmp).forEach { it.mkdirs() }
    }

    fun isInstalled(): Boolean = installMarker.isFile && proot.isFile && rootfs.isDirectory

    /** Versioned install root; bump when the payload layout changes. */
    fun payloadStamp(): String = installMarker.takeIf { it.isFile }?.readText().orEmpty()
}
