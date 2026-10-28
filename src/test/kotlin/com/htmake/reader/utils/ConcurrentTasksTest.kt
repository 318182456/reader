package com.htmake.reader.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentTasksTest {
    @Test(timeout = 3000) fun allFailuresAndEmptyRangesFinish() = runBlocking {
        val attempts = AtomicInteger()
        var completed = 0
        runConcurrentTasks(3, 0, 9, { attempts.incrementAndGet(); throw IllegalStateException("fixture") }) { values, _ ->
            assertTrue(values.isEmpty()); completed++; true
        }
        assertEquals(9, attempts.get())
        assertEquals(9, completed)
        runConcurrentTasks(3, 0, 0, { fail("Empty range started work"); 0 }) { _, _ -> fail("Empty range callback"); true }
    }

    @Test fun boundedWorkersExecuteEachIndexOnce() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        runConcurrentTasks(2, 0, 12, {
            val count = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, count) }
            try { seen.add(it); delay(5); it } finally { active.decrementAndGet() }
        }) { _, _ -> true }
        assertEquals((0..11).toSet(), seen)
        assertTrue(maximum.get() <= 2)
        assertEquals(0, active.get())
    }

    @Test fun stopCancelsOutstandingWork() = runBlocking {
        val running = AtomicInteger()
        runConcurrentTasks(2, 0, 100, {
            running.incrementAndGet()
            try { delay(if (it == 0) 5 else 10000); it } finally { running.decrementAndGet() }
        }) { _, _ -> false }
        assertEquals(0, running.get())
    }
}
