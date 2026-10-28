package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import com.htmake.reader.config.BrowserServiceConfig
import com.htmake.reader.config.BrowserServiceSettings
import io.legado.app.help.http.BrowserServiceClient
import io.vertx.ext.web.RoutingContext
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class BrowserServiceController(coroutineContext: CoroutineContext): BaseController(coroutineContext) {
    private suspend fun authorized(context: RoutingContext): ReturnData? {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        if (!checkManagerAuth(context)) return ReturnData().setData("NEED_SECURE_KEY").setErrorMsg("请使用管理模式配置浏览器服务")
        return null
    }

    private suspend fun execute(context: RoutingContext, action: suspend () -> Any): ReturnData {
        authorized(context)?.let { return it }
        return try {
            ReturnData().setData(action())
        } catch (e: CancellationException) { throw e }
        catch (e: NoStackTraceException) { ReturnData().setErrorMsg(e.message ?: "浏览器服务请求失败") }
        catch (e: IllegalArgumentException) { ReturnData().setErrorMsg("浏览器服务配置格式错误") }
        catch (e: IOException) { ReturnData().setErrorMsg("无法连接浏览器服务，请检查地址和服务状态") }
    }

    private fun config(context: RoutingContext): BrowserServiceConfig {
        val body = context.bodyAsJson ?: throw NoStackTraceException("参数错误")
        return body.mapTo(BrowserServiceConfig::class.java).normalized()
    }

    suspend fun get(context: RoutingContext): ReturnData = execute(context) {
        withContext(Dispatchers.IO) { BrowserServiceSettings.current() }
    }

    suspend fun save(context: RoutingContext): ReturnData = execute(context) {
        val settings = config(context)
        withContext(Dispatchers.IO) { BrowserServiceSettings.save(settings) }
    }

    suspend fun test(context: RoutingContext): ReturnData = execute(context) {
        BrowserServiceClient(config(context)).checkConnection()
    }
}
