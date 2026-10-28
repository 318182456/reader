package io.legado.app.help.http

import com.htmake.reader.config.BrowserServiceConfig
import com.sun.net.httpserver.HttpServer
import io.legado.app.exception.NoStackTraceException
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class BrowserServiceClientTest {
    private fun fixture(block: (String, JsonObject?) -> Pair<Int, JsonObject>, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            val input = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = block(exchange.requestMethod + " " + exchange.requestURI.path, if (input.isBlank()) null else JsonObject(input))
            val bytes = response.second.encode().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            try {
                exchange.sendResponseHeaders(response.first, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try { test("http://127.0.0.1:" + server.address.port) }
        finally { server.stop(0); executor.shutdownNow() }
    }

    private fun expectFailure(fragment: String, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Expected failure containing $fragment") }
        catch (e: NoStackTraceException) { assertTrue(e.message, e.message!!.contains(fragment)) }
    }

    @Test fun nativePostPreservesBodyHeadersCookiesAndRedirectStatusAndClosesSession() {
        val calls = mutableListOf<String>()
        fixture({ path, body ->
            calls.add(path)
            when (path) {
                "POST /sessions" -> { assertTrue(body!!.getBoolean("keepPage")); 201 to JsonObject().put("id", "fixture") }
                "POST /sessions/fixture/scrape" -> {
                    assertEquals("https://books.test/?q=%E4%B9%A6", body!!.getString("url"))
                    assertEquals("POST", body.getString("method"))
                    assertEquals("{\"q\":\"书\"}", body.getString("body"))
                    assertEquals("token", body.getJsonObject("headers").getString("X-Test"))
                    assertFalse(body.getJsonObject("headers").containsKey("Cookie"))
                    assertEquals("a=b", body.getJsonArray("cookies").getJsonObject(0).getString("value"))
                    200 to JsonObject().put("url", "https://books.test/final").put("statusCode", 404).put("html", "missing")
                }
                "DELETE /sessions/fixture" -> 200 to JsonObject().put("status", "ok")
                else -> throw AssertionError(path)
            }
        }) { base -> runBlocking {
            val request = Request.Builder().url("https://books.test/?q=%E4%B9%A6").header("X-Test", "token").header("Cookie", "name=a=b")
                .post("{\"q\":\"书\"}".toRequestBody("application/json".toMediaType())).build()
            val result = BrowserServiceClient(BrowserServiceConfig(true, "trawl", base), { "fixture" }).fetch(request)
            assertEquals("https://books.test/final", result.url)
            assertEquals(404, result.code())
            assertFalse(result.isSuccessful())
            assertEquals("missing", result.body)
        } }
        assertEquals(listOf("POST /sessions", "POST /sessions/fixture/scrape", "DELETE /sessions/fixture"), calls)
    }

    @Test fun nativeEvalReturnsValueAndCleanupRunsOnFailure() {
        for (failure in listOf(false, true)) {
            val calls = mutableListOf<String>()
            fixture({ path, body ->
                calls.add(path)
                when (path) {
                    "POST /sessions" -> 201 to JsonObject().put("id", "fixture")
                    "POST /sessions/fixture/scrape" -> 200 to JsonObject().put("url", "https://books.test/").put("statusCode", 200).put("html", "html")
                    "POST /sessions/fixture/eval" -> {
                        assertEquals("document.title", body!!.getString("expression"))
                        if (failure) 500 to JsonObject().put("secret", "must not leak") else 200 to JsonObject().put("value", "章节正文")
                    }
                    "DELETE /sessions/fixture" -> 200 to JsonObject().put("status", "ok")
                    else -> throw AssertionError(path)
                }
            }) { base -> runBlocking {
                val client = BrowserServiceClient(BrowserServiceConfig(true, "trawl", base), { "fixture" })
                try {
                    val result = client.fetch(Request.Builder().url("https://books.test/").build(), "document.title")
                    assertFalse(failure)
                    assertEquals("章节正文", result.body)
                } catch (e: NoStackTraceException) {
                    assertTrue(failure)
                    assertFalse(e.message!!.contains("secret"))
                }
            } }
            assertEquals("DELETE /sessions/fixture", calls.last())
        }
    }

    @Test fun compatibleHtmlAndFormPostUseV1AndRetainStatus() {
        fixture({ path, body ->
            assertEquals("POST /v1", path)
            assertEquals("request.post", body!!.getString("cmd"))
            assertEquals("q=%E4%B9%A6", body.getString("postData"))
            200 to JsonObject().put("status", "ok").put("solution", JsonObject().put("url", "https://books.test/new").put("status", 200).put("response", "渲染正文"))
        }) { base -> runBlocking {
            val request = Request.Builder().url("https://books.test/").post(FormBody.Builder().add("q", "书").build()).build()
            assertEquals("渲染正文", BrowserServiceClient(BrowserServiceConfig(true, "flaresolverr", base)).fetch(request).body)
        } }
    }

    @Test fun unsupportedCapabilitiesAreExplicitAndNeverSentToServer() {
        val config = BrowserServiceConfig(true, "flaresolverr", "http://127.0.0.1:1")
        val client = BrowserServiceClient(config)
        val request = Request.Builder().url("https://books.test/").build()
        expectFailure("脚本取值") { client.fetch(request, "document.title") }
        expectFailure("sourceRegex") { client.fetch(request, sourceRegex = ".*mp3") }
        expectFailure("直接载入 HTML") { client.fetch(request, html = "<html></html>") }
        expectFailure("自定义请求头") { client.fetch(request.newBuilder().header("Authorization", "secret").build()) }
        expectFailure("JSON/XML") { client.fetch(request.newBuilder().post("{}".toRequestBody("application/json".toMediaType())).build()) }
        expectFailure("启用浏览器服务") { BrowserServiceClient(config.copy(enabled = false)).fetch(request) }
    }

    @Test fun normalizationRejectsInvalidConfigurationAndAcceptsHostAndV1() {
        assertEquals("http://reader-host:8191", BrowserServiceConfig(url = "reader-host:8191/").normalized().url)
        assertEquals("http://reader-host:8191/prefix", BrowserServiceConfig(provider = "flaresolverr", url = "http://reader-host:8191/prefix/v1").normalized().url)
        for (config in listOf(BrowserServiceConfig(enabled = true), BrowserServiceConfig(provider = "unknown"), BrowserServiceConfig(timeoutSeconds = 0), BrowserServiceConfig(url = "ftp://host"), BrowserServiceConfig(url = "http://user:pass@host"), BrowserServiceConfig(url = "http://host?key=secret"))) {
            try { config.normalized(); fail("Invalid config accepted") } catch (_: NoStackTraceException) { }
        }
    }

    @Test fun connectionTestChecksChosenProtocol() {
        fixture({ path, _ ->
            when (path) {
                "GET /health" -> 200 to JsonObject().put("status", "ok")
                "POST /v1" -> 200 to JsonObject().put("status", "ok").put("sessions", io.vertx.core.json.JsonArray())
                else -> throw AssertionError(path)
            }
        }) { base -> runBlocking {
            for (provider in listOf("trawl", "flaresolverr")) assertTrue(BrowserServiceClient(BrowserServiceConfig(url = base, provider = provider)).checkConnection().contains("成功"))
        } }
    }

    @Test fun cancelledNativeRequestDeletesItsSession() {
        val scrapeStarted = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        val deleteAttempts = java.util.concurrent.atomic.AtomicInteger()
        fixture({ path, _ ->
            when (path) {
                "POST /sessions" -> 201 to JsonObject().put("id", "fixture")
                "POST /sessions/fixture/scrape" -> { scrapeStarted.countDown(); Thread.sleep(1000); 200 to JsonObject().put("html", "late") }
                "DELETE /sessions/fixture" -> {
                    if (deleteAttempts.incrementAndGet() == 1) 409 to JsonObject().put("error", "busy")
                    else { deleted.countDown(); 200 to JsonObject().put("status", "ok") }
                }
                else -> throw AssertionError(path)
            }
        }) { base -> runBlocking {
            val task = launch(Dispatchers.IO) { BrowserServiceClient(BrowserServiceConfig(true, "trawl", base), { "fixture" }).fetch(Request.Builder().url("https://books.test/").build()) }
            assertTrue(scrapeStarted.await(5, TimeUnit.SECONDS))
            task.cancelAndJoin()
            assertTrue(deleted.await(5, TimeUnit.SECONDS))
        } }
    }
}
