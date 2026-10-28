package com.htmake.reader.config

import com.htmake.reader.utils.getStorage
import com.htmake.reader.utils.saveStorage
import io.legado.app.exception.NoStackTraceException
import io.vertx.core.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Server-wide configuration; never stored in a user's frontend backup. */
data class BrowserServiceConfig(
    val enabled: Boolean = false,
    val provider: String = "trawl",
    val url: String = "",
    val timeoutSeconds: Int = 45
) {
    fun normalized(): BrowserServiceConfig {
        if (provider !in listOf("trawl", "flaresolverr")) {
            throw NoStackTraceException("请选择 Trawl 或 FlareSolverr 兼容接口")
        }
        if (timeoutSeconds !in 5..120) throw NoStackTraceException("超时时间须为 5 至 120 秒")
        val text = url.trim().trimEnd('/')
        if (text.isEmpty()) {
            if (enabled) throw NoStackTraceException("启用浏览器服务前请填写地址")
            return copy(url = "")
        }
        val address = (if (text.contains("://")) text else "http://$text").toHttpUrlOrNull()
            ?: throw NoStackTraceException("请输入有效的 HTTP 或 HTTPS 服务地址")
        if (address.username.isNotEmpty() || address.password.isNotEmpty() || address.query != null || address.fragment != null) {
            throw NoStackTraceException("服务地址不能包含账号密码、查询参数或片段")
        }
        // Users may paste the compatible endpoint instead of the service root.
        val base = address.toString().trimEnd('/').let {
            if (provider == "flaresolverr" && it.endsWith("/v1")) it.removeSuffix("/v1") else it
        }
        return copy(url = base)
    }
}

object BrowserServiceSettings {
    @Volatile private var cached: BrowserServiceConfig? = null

    @Synchronized fun current(): BrowserServiceConfig {
        cached?.let { return it }
        val stored = getStorage("data", "browserService")
        val config = if (stored == null) BrowserServiceConfig() else JsonObject(stored).mapTo(BrowserServiceConfig::class.java).normalized()
        cached = config
        return config
    }

    @Synchronized fun save(config: BrowserServiceConfig): BrowserServiceConfig {
        val normalized = config.normalized()
        saveStorage("data", "browserService", value = normalized)
        cached = normalized
        return normalized
    }
}
