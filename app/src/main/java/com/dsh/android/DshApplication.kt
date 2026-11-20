package com.dsh.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dsh.android.pet.PetForeground
import com.dsh.android.runtime.DshRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DshApplication : Application() {

    lateinit var runtime: DshRuntime
        private set

    /** Keeps the floating pet in step with the runtime and this app's visibility. */
    lateinit var petForeground: PetForeground
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        runtime = DshRuntime(this, appScope)
        createNotificationChannel()

        petForeground = PetForeground.install(this)
        // State changes drive the overlay: it may only exist while `dsh web`
        // is up, and it must go away the moment the runtime stops.
        appScope.launch {
            runtime.state.collect { petForeground.onRuntimeState(it) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "dsh-runtime"
    }
}
