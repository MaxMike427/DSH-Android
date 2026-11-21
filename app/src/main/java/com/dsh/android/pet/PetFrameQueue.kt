package com.dsh.android.pet

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A renderer may outrun WindowManager; keep one scheduled task and the newest frame. */
internal class PetFrameQueue<T>(private val schedule: () -> Unit) {
    private val pending = AtomicReference<T?>(null)
    private val scheduled = AtomicBoolean(false)

    fun offer(frame: T) {
        pending.set(frame)
        if (scheduled.compareAndSet(false, true)) schedule()
    }

    fun take(): T? {
        // Release scheduling before taking the value so an offer racing this
        // drain always has either this task or a newly posted task to handle it.
        scheduled.set(false)
        return pending.getAndSet(null)
    }

    fun clear() {
        pending.set(null)
        scheduled.set(false)
    }
}
