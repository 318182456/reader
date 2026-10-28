package io.legado.app.model.analyzeRule

import com.htmake.reader.config.BrowserServiceConfig
import com.htmake.reader.config.BrowserServiceSettings
import org.junit.Before
import org.junit.After
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID

class AnalyzeUrlFailureTest {
    private var previousConfig: Any? = null
    private val configField = BrowserServiceSettings::class.java.getDeclaredField("cached").apply { isAccessible = true }
    @Before fun disableBrowserService() {
        previousConfig = configField.get(null)
        configField.set(null, BrowserServiceConfig())
    }
    @After fun restoreBrowserService() { configField.set(null, previousConfig) }

    private fun assertReleased(source: BookSource) {
        val field = AnalyzeUrl::class.java.getDeclaredField("concurrentRecordMap")
        field.isAccessible = true
        val records = field.get(null) as Map<*, *>
        val record = records[source.bookSourceUrl] as AnalyzeUrl.ConcurrentRecord
        assertEquals("失败请求必须释放并发计数", 0, record.frequency)
    }

    @Test fun webViewFailureReleasesSlotAndExplainsUnsupportedFeature() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://test.invalid/" + UUID.randomUUID(), concurrentRate = "1")
        val request = AnalyzeUrl("https://test.invalid/, {\"webView\":true}", source = source)
        try {
            request.getStrResponseAwait()
            fail("WebView should be rejected")
        } catch (e: NoStackTraceException) {
            assertTrue(e.message!!.contains("启用浏览器服务"))
            assertEquals(0, e.stackTrace.size)
        }
        assertReleased(source)
    }

    @Test fun transportFailuresReleaseAllThreeRequestPaths() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        for (path in 0..2) {
            val source = BookSource(bookSourceUrl = "http://127.0.0.1/$path/" + UUID.randomUUID(), concurrentRate = "1")
            val request = AnalyzeUrl("http://127.0.0.1:$port/", source = source)
            try {
                when (path) {
                    0 -> request.getStrResponseAwait()
                    1 -> request.getResponseAwait().close()
                    else -> request.getByteArrayAwait()
                }
                fail("Connection should fail")
            } catch (e: java.io.IOException) {
                // A refused loopback connection exercises the actual HTTP failure path.
            }
            assertReleased(source)
        }
    }

    @Test fun javaWebViewDoesNotSilentlyReturnNull() {
        try {
            AnalyzeUrl("https://test.invalid/").webView(null, "https://test.invalid/", null)
            fail("java.webView should explain that it is unsupported")
        } catch (e: NoStackTraceException) {
            assertTrue(e.message!!.contains("启用浏览器服务"))
        }
    }
}
