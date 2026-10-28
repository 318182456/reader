package com.htmake.reader.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

suspend fun runConcurrentTasks(
    concurrentCount: Int, startIndex: Int, endIndex: Int,
    handler: suspend CoroutineScope.(Int) -> Any,
    needContinue: (ArrayList<Any>, Int) -> Boolean
) = supervisorScope {
    if (startIndex >= endIndex) return@supervisorScope
    val count = concurrentCount.coerceIn(1, 64).coerceAtMost(endIndex - startIndex)
    class Completion(val value: Any?)
    val completed = Channel<Completion>(count)
    val jobs = arrayListOf<Job>()
    var next = startIndex
    var active = 0
    var finished = 0
    fun schedule() {
        val index = next++
        active++
        jobs.add(launch {
            val value = try { handler(index) }
            catch (e: CancellationException) {
                if (!coroutineContext.isActive) throw e
                null
            } catch (_: Exception) { null }
            completed.send(Completion(value))
        })
    }
    try {
        repeat(count) { schedule() }
        while (active > 0) {
            val completion = completed.receive()
            active--
            finished++
            val values = arrayListOf<Any>()
            completion.value?.let { values.add(it) }
            if (!needContinue(values, finished / count)) break
            if (next < endIndex) schedule()
        }
    } finally {
        jobs.forEach { it.cancel() }
        completed.cancel()
    }
}
