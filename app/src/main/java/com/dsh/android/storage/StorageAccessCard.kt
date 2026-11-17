package com.dsh.android.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Storage-access card shown before the runtime is started.
 *
 * Requesting permissions is a visible user action rather than a silent launch
 * step, because Android will not grant "All files access" without the user
 * visiting system settings.
 */
@Composable
fun StorageAccessCard(
    mountSummary: List<String>,
    onMountsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(StorageAccess.hasBroadAccess(context)) }

    // Re-read after returning from the system settings screen.
    LifecycleResumeEffect(Unit) {
        granted = StorageAccess.hasBroadAccess(context)
        if (granted) onMountsChanged()
        onPauseOrDispose { }
    }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = StorageAccess.hasBroadAccess(context)
        if (result.values.any { it }) onMountsChanged()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Android storage", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (granted) {
                    "Public folders are mounted inside the guest at ${StorageAccess.GUEST_ROOT}."
                } else {
                    "Grant file access so the agent can read and write Download, Documents, DCIM and the shared volume."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(10.dp))

            if (!granted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { requestAccess(context) { legacyLauncher.launch(StorageAccess.legacyPermissions()) } }) {
                        Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Open settings" else "Grant access")
                    }
                    TextButton(onClick = onMountsChanged) { Text("Recheck") }
                }
            }

            if (mountSummary.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Mounted", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                mountSummary.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No public directories available yet.",
                    fontSize = 11.sp,
                    color = Color(0xFFFFB4AB),
                )
            }
        }
    }
}

private fun requestAccess(context: Context, requestLegacy: () -> Unit) {
    // Request the runtime permissions first: on devices where the legacy model
    // still applies they are what grant the volume, and the dialog is immediate.
    requestLegacy()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // The appop is what actually grants the external_storage group on
        // Android 16 (verified on device), even with targetSdk 28. The per-app
        // screen may not exist for a legacy-target app, so fall back to the
        // global list.
        val direct = StorageAccess.allFilesAccessIntent(context)
        val opened = runCatching { context.startActivity(direct) }.isSuccess
        if (!opened) {
            runCatching { context.startActivity(StorageAccess.allFilesAccessFallbackIntent()) }
        }
    }
}
