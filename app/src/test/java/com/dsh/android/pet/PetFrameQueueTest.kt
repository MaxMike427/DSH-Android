package com.dsh.android.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetFrameQueueTest {
    @Test
    fun `a slow main thread gets the latest frame without a queue of old positions`() {
        var tasks = 0
        val queue = PetFrameQueue<Int> { tasks++ }
        repeat(100) { queue.offer(it) }
        assertEquals(1, tasks)
        assertEquals(99, queue.take())
        assertNull(queue.take())
        queue.offer(100)
        assertEquals(2, tasks)
        assertEquals(100, queue.take())
    }

    @Test
    fun `teardown clears pending updates`() {
        val queue = PetFrameQueue<Int> { }
        queue.offer(1)
        queue.clear()
        assertNull(queue.take())
    }
}
