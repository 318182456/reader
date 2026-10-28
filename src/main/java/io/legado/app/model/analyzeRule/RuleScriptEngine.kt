package io.legado.app.model.analyzeRule

import com.script.Bindings
import com.script.javascript.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.Job
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import java.util.concurrent.TimeUnit

/** Bounds actual JS execution, rather than just timing out its HTTP caller. */
object RuleScriptEngine {
    private val delegate = RhinoScriptEngine()
    val requestJob = ThreadLocal<Job?>()
    private val deadline = ThreadLocal<Long?>()
    private class ExecutionLimit : Error()
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            instructionObserverThreshold = 10000
        }
        override fun observeInstructionCount(context: Context, instructionCount: Int) {
            val expires = deadline.get()
            if (Thread.currentThread().isInterrupted || requestJob.get()?.isActive == false || (expires != null && System.nanoTime() - expires >= 0)) {
                throw ExecutionLimit()
            }
        }
    }

    fun eval(script: String, bindings: Bindings): Any? {
        val previous = deadline.get()
        if (previous == null) deadline.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
        try {
            return factory.call { delegate.eval(script, bindings) }
        } catch (_: ExecutionLimit) {
            throw NoStackTraceException("书源脚本执行超时或请求已取消，已停止执行")
        } finally {
            if (previous == null) deadline.remove() else deadline.set(previous)
        }
    }
}
