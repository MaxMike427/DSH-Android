package com.dsh.android.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Android shared-storage access for the guest.
 *
 * Two mechanisms are needed because the app targets API 28 (PRoot requires
 * `exec()` on private files, which API 29+ forbids):
 *
 *  - API 30+ ignores targetSdk for "All files access": the user grants
 *    MANAGE_EXTERNAL_STORAGE in system settings. That is the only way to read
 *    and write arbitrary public directories such as Download or Documents.
 *  - API < 30 grants READ/WRITE_EXTERNAL_STORAGE at runtime, and legacy storage
 *    (requestLegacyExternalStorage) keeps direct path access working.
 *
 * Media permissions are also mapped for completeness so the system's per-media
 * prompts behave correctly on Android 13+.
 */
object StorageAccess {

    /** Directories bind-mounted into the guest when they exist. */
    data class PublicDirectory(val name: String, val file: File)

    private const val ALL_FILES_SETTINGS_ACTION = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION

    /** True when the app can read and write the shared storage root. */
    fun hasBroadAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return hasLegacyPermission(context)
    }

    private fun hasLegacyPermission(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val write = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            return read && write
        }
        return read
    }

    /** Runtime permissions to request before the guest starts. */
    fun legacyPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        }
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** Opens the "All files access" screen for this app (API 30+). */
    fun allFilesAccessIntent(context: Context): Intent {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        return Intent(ALL_FILES_SETTINGS_ACTION, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Fallback when the per-app screen is unavailable on a vendor ROM. */
    fun allFilesAccessFallbackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Public directories to bind into the guest, in mount order.
     *
     * Only existing directories are returned: PRoot aborts when a bind source is
     * missing. The app-specific external directory always exists and needs no
     * permission, so it is always included as a guaranteed working exchange area.
     */
    fun mountPoints(context: Context): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()

        // Always available, no permission required.
        context.getExternalFilesDir(null)?.let { dir ->
            if (dir.isDirectory) result += "android-shared" to dir
        }

        if (!hasBroadAccess(context)) return result

        val root = Environment.getExternalStorageDirectory()
        val named = listOf(
            "Download",
            "Documents",
            "DCIM",
            "Pictures",
            "Movies",
            "Music",
            "Podcasts",
            "Alarms",
            "Ringtones",
        )
        named.forEach { name ->
            val dir = File(root, name)
            if (dir.isDirectory) result += name.lowercase() to dir
        }

        // The whole shared volume last, so the named mounts above take precedence
        // for their own subpaths while everything else stays reachable.
        if (root.isDirectory) result += "shared" to root

        return result
    }

    /** Guest mount root for Android storage. */
    const val GUEST_ROOT = "/mnt/android"

    /** Guest path for a named public directory. */
    fun guestPath(name: String): String = "$GUEST_ROOT/$name"
}
