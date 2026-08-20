package io.legado.app.utils

import com.script.SimpleBindings
import io.legado.app.constant.AppConst.SCRIPT_ENGINE
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.RegexJsExtensions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

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
    book: Book? = null,
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
        val pattern = compileAndroidLike(name, regex)
        val matcher = pattern.matcher(charSequence)
        val stringBuffer = StringBuffer()
        while (matcher.find()) {
            if (isJs) {
                val bindings = SimpleBindings()
                bindings["result"] = matcher.group()
                bindings["chapter"] = chapter
                // book 不能漏 —— 脚本里引用到它就会抛 ReferenceError，
                // 整条规则被 catch 掉，表现为「App 上生效、服务端不生效」
                bindings["book"] = book
                bindings["java"] = reJsExtensions
                val jsResult = try {
                    SCRIPT_ENGINE.eval(replacement1, bindings)?.toString() ?: ""
                } catch (e: Exception) {
                    // 脚本出错就保留原文，别把这一处吃掉；
                    // 同一条规则只报一次，否则一章上千次刷屏
                    if (reportedBadRules.add("js:$name")) {
                        logger.warn { "净化规则【$name】的 @js: 脚本出错，本处保留原文：${e.message}" }
                    }
                    matcher.group()
                }
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

/**
 * 按 Android 的语义编译正则，并把不兼容的规则报出来。
 *
 * legado 跑在 Android 上，那里的 java.util.regex 是 ICU 的包装；
 * 服务端是标准 JVM 的自研实现。两者在变长后顾、占有量词、
 * \h 这些地方语义不同 —— 实测 569 条正则规则里，22 条用了变长后顾、
 * 15 条用了占有量词、10 条用了 \h。
 *
 * 没有现成的替代引擎可用：ICU4J（纯 Java）不含正则部分，re2j 不支持后顾。
 *
 * 对「后顾组里含交替 + 量词」这一类，做语义等价重写：把 (?:A|B){m,n}
 * 换成 [最宽分支]{m, n×宽度}。长度就确定了，而覆盖的字符跨度不变 ——
 * 这不是猜作者意图，也不针对具体规则，任何命中同样形态的都能修。
 *
 * 这一条很关键：全量普查 557 条正则规则，真正编译失败的只有
 * 【#10 标点「」】，而它负责把 “” 换成 「」。换完之后 order>10 的那 8 条
 * （#14 净化标点、#19 拆分长句…）才认得出句子边界并拆段。实测同一段正文：
 * 不修得 5 段，修了得 7 段，后者与 App 一致 —— 段评错位的主因就在这里。
 *
 * 重写也不行的才跳过，并把规则名 warn 一次，不静默吞掉。
 */
private val patternCache = java.util.concurrent.ConcurrentHashMap<String, Pattern>()
private val reportedBadRules = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

private fun compileAndroidLike(name: String, regex: Regex): Pattern {
    val src = regex.pattern
    patternCache[src]?.let { return it }
    // UNICODE_CHARACTER_CLASS：Android 默认把 \w  \s 按 Unicode 解释。
    // 不加的话「整行都是符号」那类模式会因为 \w 不含中文，
    // 变成「任意 4 个以上中文开头的行」，整行被吃掉。
    val flags = regex.toPattern().flags() or Pattern.UNICODE_CHARACTER_CLASS
    val compiled = try {
        Pattern.compile(src, flags)
    } catch (e: PatternSyntaxException) {
        val rewritten = rewriteForJvm(src, flags)
        if (rewritten != null) {
            if (reportedBadRules.add("rw:$name")) {
                logger.info { "净化规则【$name】的后顾组已按 JVM 语义重写" }
            }
            patternCache[src] = rewritten
            return rewritten
        }
        if (reportedBadRules.add(name)) {
            logger.warn {
                "净化规则【$name】在服务端编译不了，已跳过（App 上正常）：" +
                    (e.description ?: e.message)
            }
        }
        throw e
    }
    patternCache[src] = compiled
    return compiled
}

/** 后顾组里的 (?:A|B){m,n} —— 交替分支长度不一时 Java 算不出最大长度 */
private val altQuantified =
    Regex("""\(\?:((?:[^()]|\\.)*\|(?:[^()]|\\.)*)\)\{(\d+),(\d+)\}""")

/** 字符类与转义都按一个字符算，用来估交替分支的宽度 */
private val charClassOrEscape = Regex("""\[[^\]]*\]|\\.""")

/**
 * 把后顾组里长度不确定的交替改写成确定长度的等价写法。
 * 一条规则可能有多处，所以反复试；改不动就返回 null，由调用方跳过该规则。
 */
private fun rewriteForJvm(src: String, flags: Int): Pattern? {
    var cur = src
    repeat(REWRITE_ROUNDS) {
        val failAt = try {
            return Pattern.compile(cur, flags)
        } catch (e: PatternSyntaxException) {
            e.index
        }
        if (failAt < 0) return null
        val range = lookBehindAround(cur, failAt) ?: return null
        val group = cur.substring(range.first, range.second)
        val relaxed = altQuantified.replace(group) { m ->
            val alts = m.groupValues[1].split(Regex("""(?<!\\)\|"""))
            val widest = alts.maxByOrNull { it.length } ?: return@replace m.value
            val lo = m.groupValues[2].toInt()
            val hi = m.groupValues[3].toInt()
            val span = charClassOrEscape.replace(widest, "x").length.coerceAtLeast(1)
            "$widest{$lo,${hi * span}}"
        }
        if (relaxed == group) return null
        cur = cur.substring(0, range.first) + relaxed + cur.substring(range.second)
    }
    return null
}

/** 反复重写的次数上限，一条规则里的后顾组不会多到这个数 */
private const val REWRITE_ROUNDS = 6

/** 找包住第 idx 个字符的那个 (?<= / (?<! 组的起止下标 */
private fun lookBehindAround(p: String, idx: Int): Pair<Int, Int>? {
    var start = -1
    for (i in idx.coerceAtMost(p.length - 1) downTo 1) {
        if (p[i] == '(' && i + 3 < p.length && p[i + 1] == '?' && p[i + 2] == '<' &&
            (p[i + 3] == '=' || p[i + 3] == '!')
        ) {
            start = i
            break
        }
    }
    if (start < 0) return null
    var depth = 0
    var i = start
    while (i < p.length) {
        when (p[i]) {
            '\\' -> i++
            '[' -> while (i < p.length && p[i] != ']') { if (p[i] == '\\') i++; i++ }
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return start to (i + 1)
            }
        }
        i++
    }
    return null
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
