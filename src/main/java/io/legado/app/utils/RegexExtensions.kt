package io.legado.app.utils

import com.script.SimpleBindings
import io.legado.app.constant.AppConst.SCRIPT_ENGINE
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.RegexJsExtensions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

/**
 * 带超时检测的正则替换，与 legado 的同名扩展保持一致的行为。
 *
 * 用 Java 的 Pattern/Matcher 而不是 Kotlin Regex.replace —— 净化规则里大量使用
 * Java 专有语法（\h、变长后顾、前瞻里的反向引用），换个引擎结果就不一样，
 * 段落数随之对不上。@js: 替换走 Rhino，与 App 同一套脚本语义。
 *
 * 与 App 的差别只有一处：超时后 App 会重启自己，服务端不能这么干，
 * 改为抛 RegexTimeoutException，由调用方跳过这一条规则。
 */
fun CharSequence.replace(
    name: String,
    regex: Regex,
    replacement: String,
    timeout: Long,
    chapter: BookChapter? = null,
): String {
    val charSequence = this@replace
    val isJs = replacement.startsWith("@js:")
    val replacement1 = if (isJs) replacement.substring(4) else replacement
    val reJsExtensions by lazy { RegexJsExtensions(name) }

    val task = Callable {
        // 必须带 UNICODE_CHARACTER_CLASS：Android 的 Pattern 默认就把 \w \b \s
        // 按 Unicode 解释，标准 JVM 不是。差别很致命 —— 净化规则里的
        // ^[^\n\w]{4,} 本意是「整行都是符号」，在 JVM 上因为 \w 不含中文，
        // 变成「任意 4 个以上中文开头的行」，整行被替换掉。
        // 实测某章 63 段被这一条吃到只剩 17 段。
        val pattern = Pattern.compile(
            regex.pattern,
            regex.toPattern().flags() or Pattern.UNICODE_CHARACTER_CLASS
        )
        val matcher = pattern.matcher(charSequence)
        val stringBuffer = StringBuffer()
        while (matcher.find()) {
            if (isJs) {
                val bindings = SimpleBindings()
                bindings["result"] = matcher.group()
                bindings["chapter"] = chapter
                bindings["java"] = reJsExtensions
                val jsResult = SCRIPT_ENGINE.eval(replacement1, bindings)?.toString() ?: ""
                matcher.appendReplacement(stringBuffer, jsResult.quoteReplacementJs())
            } else {
                matcher.appendReplacement(stringBuffer, replacement1)
            }
        }
        matcher.appendTail(stringBuffer)
        stringBuffer.toString()
    }

    // 病态正则会在 matcher.find() 里空转，只能靠独立线程 + 超时打断
    val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "replace-$name").apply { isDaemon = true }
    }
    try {
        val future = executor.submit(task)
        return try {
            future.get(timeout, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw RegexTimeoutException("替换超时，跳过规则：$name")
        }
    } finally {
        executor.shutdownNow()
    }
}

/** appendReplacement 会把 \ 当转义符，JS 返回的结果要先自行转义 */
fun String.quoteReplacementJs(): String {
    if (!this.contains('\\')) return this
    val sb = StringBuilder()
    for (c in this) {
        if (c == '\\') sb.append("\\\\") else sb.append(c)
    }
    return sb.toString()
}
