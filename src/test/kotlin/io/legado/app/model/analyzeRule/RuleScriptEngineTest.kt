package io.legado.app.model.analyzeRule

import com.script.SimpleBindings
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class RuleScriptEngineTest {
    @Test(timeout = 10000) fun infiniteScriptActuallyStopsAndNextScriptStillWorks() {
        val start = System.nanoTime()
        try {
            RuleScriptEngine.eval("while (true) {}", SimpleBindings())
            fail("Infinite loop must stop")
        } catch (e: NoStackTraceException) { assertTrue(e.message!!.contains("超时")) }
        assertTrue((System.nanoTime() - start) / 1000000 < 7000)
        assertEquals("3", RuleScriptEngine.eval("1 + 2", SimpleBindings()).toString().removeSuffix(".0"))
    }

    @Test(timeout = 10000) fun scriptCannotSwallowExecutionLimit() {
        try {
            RuleScriptEngine.eval("try { while (true) {} } catch(e) { while (true) {} }", SimpleBindings())
            fail("JS catch must not swallow execution budget")
        } catch (e: NoStackTraceException) { assertTrue(e.message!!.contains("超时")) }
    }

    @Test(timeout = 3000) fun cancelledRequestStopsExecutingScript() {
        val job = Job()
        job.cancel()
        RuleScriptEngine.requestJob.set(job)
        try {
            RuleScriptEngine.eval("var n = 0; while(n < 10000000) n++; n", SimpleBindings())
            fail("Cancelled request must stop script")
        } catch (e: NoStackTraceException) { assertTrue(e.message!!.contains("取消")) }
        finally { RuleScriptEngine.requestJob.remove() }
    }

    @Test fun bindingsRemainAvailable() {
        val bindings = SimpleBindings()
        bindings["value"] = "章节"
        assertEquals("章节正文", RuleScriptEngine.eval("value + '正文'", bindings))
    }
}
