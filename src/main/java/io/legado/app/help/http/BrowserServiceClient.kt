package io.legado.app.help.http

import com.htmake.reader.config.BrowserServiceConfig
import com.htmake.reader.config.BrowserServiceSettings
import io.legado.app.exception.NoStackTraceException
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The two protocols intentionally have different capabilities. */
class BrowserServiceClient(private val config: BrowserServiceConfig, private val sessionId: () -> String = { UUID.randomUUID().toString() }) {
    private class ServiceHttpException(val status: Int, message: String): NoStackTraceException(message)
    companion object {
        private val permits = Mutex()
        private val jsonType = "application/json; charset=utf-8".toMediaType()
        private val transport = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(130, TimeUnit.SECONDS).callTimeout(135, TimeUnit.SECONDS).build()
    }

    private suspend fun exchange(path: String, body: JsonObject? = null, method: String = if (body == null) "GET" else "POST"): JsonObject {
        val builder = Request.Builder().url(config.url + path)
        if (method == "DELETE") builder.delete() else if (body != null) builder.post(body.encode().toRequestBody(jsonType))
        val response = transport.newCall(builder.build()).await()
        response.use {
            if (!it.isSuccessful) {
                val detail = when (it.code) {
                    401, 403 -> "鉴权失败"
                    404 -> "接口不存在，请检查服务类型和地址"
                    409, 429, 503 -> "服务繁忙，请稍后重试"
                    else -> "HTTP ${it.code}"
                }
                throw ServiceHttpException(it.code, "浏览器服务请求失败：$detail")
            }
            val source = it.body?.source() ?: throw NoStackTraceException("浏览器服务返回空响应")
            source.request(8L * 1024 * 1024 + 1)
            if (source.buffer.size > 8L * 1024 * 1024) throw NoStackTraceException("浏览器服务响应超过 8 MB")
            return try { JsonObject(source.buffer.readUtf8()) } catch (e: Exception) {
                throw NoStackTraceException("浏览器服务返回格式错误，请检查服务类型和地址")
            }
        }
    }

    suspend fun checkConnection(): String = withTimeoutOrNull(15000) {
        if (config.url.isEmpty()) throw NoStackTraceException("请先填写浏览器服务地址")
        if (config.provider == "trawl") {
            val health = exchange("/health")
            if (health.getString("status") != "ok") throw NoStackTraceException("Trawl 健康检查未通过")
            "Trawl 连接成功"
        } else {
            val result = exchange("/v1", JsonObject().put("cmd", "sessions.list"))
            if (result.getString("status") != "ok" || result.getJsonArray("sessions") == null) {
                throw NoStackTraceException("FlareSolverr 兼容接口检查未通过")
            }
            "FlareSolverr 兼容接口连接成功"
        }
    } ?: throw NoStackTraceException("浏览器服务连接测试超时")

    suspend fun fetch(request: Request, js: String? = null, sourceRegex: String? = null, html: String? = null): StrResponse {
        if (!config.enabled || config.url.isEmpty()) throw NoStackTraceException("此书源需要 WebView，请在设置中启用浏览器服务")
        if (!sourceRegex.isNullOrBlank()) throw NoStackTraceException("当前浏览器服务适配不支持 sourceRegex 资源嗅探")
        if (!html.isNullOrBlank()) throw NoStackTraceException("当前浏览器服务适配暂不支持直接载入 HTML，请使用页面 URL")
        return permits.withLock {
            withTimeoutOrNull(config.timeoutSeconds * 1000L) {
                try {
                    if (config.provider == "trawl") fetchTrawl(request, js) else fetchCompatible(request, js)
                } catch (e: CancellationException) { throw e }
                catch (e: IOException) { throw NoStackTraceException("无法连接浏览器服务或请求超时，请检查地址和服务状态") }
            } ?: throw NoStackTraceException("浏览器服务请求超时，请稍后重试或调整超时时间")
        }
    }

    private fun cookies(request: Request): JsonArray {
        val result = JsonArray()
        request.header("Cookie")?.split(';')?.forEach {
            val pair = it.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotEmpty()) result.add(JsonObject().put("name", pair[0]).put("value", pair[1]))
        }
        return result
    }

    private fun requestBody(request: Request): String {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private suspend fun fetchTrawl(request: Request, js: String?): StrResponse {
        val id = sessionId()
        try {
            val created = exchange("/sessions", JsonObject().put("id", id).put("keepPage", true))
            if (created.getString("id") != id) throw NoStackTraceException("Trawl 未返回有效的浏览器会话")
            val headers = JsonObject()
            request.headers.forEach { (name, value) ->
                if (!name.equals("Cookie", true) && !name.equals("Host", true) && !name.equals("Content-Length", true)) headers.put(name, value)
            }
            val payload = JsonObject().put("url", request.url.toString()).put("method", request.method)
                .put("headers", headers).put("maxTimeout", config.timeoutSeconds * 1000).put("allowShortContent", true)
            if (request.body != null) payload.put("body", requestBody(request))
            val cookieList = cookies(request)
            if (!cookieList.isEmpty) payload.put("cookies", cookieList)
            val page = exchange("/sessions/$id/scrape", payload)
            val text = if (js.isNullOrBlank()) {
                page.getString("html") ?: throw NoStackTraceException("Trawl 未返回页面 HTML")
            } else {
                val evaluated = exchange("/sessions/$id/eval", JsonObject().put("expression", js).put("maxTimeout", config.timeoutSeconds * 1000))
                if (!evaluated.containsKey("value")) throw NoStackTraceException("Trawl 未返回脚本执行结果")
                when (val value = evaluated.getValue("value")) {
                    null -> ""
                    is String -> value
                    is JsonObject -> value.encode()
                    is JsonArray -> value.encode()
                    else -> value.toString()
                }
            }
            return result(page.getString("url") ?: request.url.toString(), page.getInteger("statusCode") ?: 200, text)
        } finally {
            // Cancellation of the reader request must not leave a browser context behind.
            withContext(NonCancellable) {
                withTimeoutOrNull(config.timeoutSeconds * 1000L + 5000) {
                    while (true) {
                        try {
                            exchange("/sessions/$id", method = "DELETE")
                            break
                        } catch (e: ServiceHttpException) {
                            if (e.status != 409) break
                            // A busy Trawl session cannot be deleted until its scrape finishes.
                            delay(500)
                        } catch (_: IOException) { break }
                        catch (_: NoStackTraceException) { break }
                    }
                }
            }
        }
    }

    private suspend fun fetchCompatible(request: Request, js: String?): StrResponse {
        if (!js.isNullOrBlank()) throw NoStackTraceException("FlareSolverr 兼容接口不支持页面内脚本取值，请选择 Trawl")
        if (request.method !in listOf("GET", "POST")) throw NoStackTraceException("FlareSolverr 兼容接口仅支持 GET 和表单 POST")
        val unsupportedHeaders = request.headers.names().any {
            !it.equals("User-Agent", true) && !it.equals("Cookie", true) && !it.equals("Content-Type", true)
        }
        if (unsupportedHeaders) throw NoStackTraceException("FlareSolverr 兼容接口不支持此书源的自定义请求头，请选择 Trawl")
        if (request.method == "POST" && request.body?.contentType()?.let { "${it.type}/${it.subtype}" } != "application/x-www-form-urlencoded") {
            throw NoStackTraceException("FlareSolverr 兼容接口不支持 JSON/XML POST，请选择 Trawl")
        }
        val payload = JsonObject().put("cmd", if (request.method == "POST") "request.post" else "request.get")
            .put("url", request.url.toString()).put("maxTimeout", config.timeoutSeconds * 1000)
        if (request.body != null) payload.put("postData", requestBody(request))
        val cookieList = cookies(request)
        if (!cookieList.isEmpty) payload.put("cookies", cookieList)
        val response = exchange("/v1", payload)
        if (response.getString("status") != "ok") throw NoStackTraceException("浏览器服务未能完成页面加载，请检查目标网站或稍后重试")
        val solution = response.getJsonObject("solution") ?: throw NoStackTraceException("兼容接口未返回 solution")
        val text = solution.getString("response") ?: throw NoStackTraceException("兼容接口未返回页面 HTML")
        return result(solution.getString("url") ?: request.url.toString(), solution.getInteger("status") ?: 200, text)
    }

    private fun result(url: String, status: Int, text: String): StrResponse {
        if (status !in 100..599) throw NoStackTraceException("浏览器服务返回无效的页面状态码")
        val raw = Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
            .code(status).message("Browser service").build()
        return StrResponse(raw, text)
    }
}

object BrowserService {
    suspend fun fetch(request: Request, js: String? = null, sourceRegex: String? = null, html: String? = null): StrResponse =
        BrowserServiceClient(BrowserServiceSettings.current()).fetch(request, js, sourceRegex, html)
}
