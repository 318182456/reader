package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.spaceRegex
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.escapeRegex
import io.legado.app.utils.replace
import java.util.regex.Pattern

internal fun sameTitleLineMatcher(
    content: String,
    namePattern: String,
    titlePattern: String
) = Pattern.compile(
    "^(\\s|\\p{P}|$namePattern)*$titlePattern" +
        "[\\t\\x0B\\f\\p{Zs}]*(?:(?:\\r\\n|\\r|\\n)\\s*|$)"
).matcher(content)

/**
 * 正文净化，与 legado 的同名类保持一致的处理顺序。
 *
 * legado 的段号是「净化之后」算出来的，reader 此前直接返回原始正文，
 * 两边分段就对不上 —— 段评挂错位置的根因。这里把 App 那条链路搬过来：
 * 去重复标题 → 简繁 → usehtml 占位 → 逐行 trim → 按 sortOrder 套规则 → 切段。
 *
 * 简繁转换用与 legado 同一个库（quick-chinese-transfer）同一个版本，
 * 连排除词表都是原样搬过来的，转换结果一致。
 *
 * 与 App 唯一的差别：单条规则替换超时后 App 会重启自己，
 * 服务端不能这么干，改为跳过该条规则。
 */
class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    companion object {
        private val processors = hashMapOf<String, ContentProcessor>()

        /** 由外部在启动/还原备份后注入全部净化规则 */
        @Volatile
        private var allRules: List<ReplaceRule> = emptyList()

        /** 0=不转换 1=繁转简 2=简转繁，与 App 的设置保持一致 */
        @Volatile
        var chineseConverterType: Int = 0

        fun setReplaceRules(rules: List<ReplaceRule>) {
            allRules = rules
            synchronized(processors) {
                processors.values.forEach { it.upReplaceRules() }
            }
        }

        fun getReplaceRules(): List<ReplaceRule> = allRules

        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val key = bookName + bookOrigin
            synchronized(processors) {
                return processors.getOrPut(key) { ContentProcessor(bookName, bookOrigin) }
            }
        }
    }

    private var titleReplaceRules: List<ReplaceRule> = emptyList()
    private var contentReplaceRules: List<ReplaceRule> = emptyList()

    init {
        upReplaceRules()
    }

    /**
     * 与 ReplaceRuleDao.findEnabledByContentScope 的 SQL 等价：
     * 启用 + 作用域命中 + 未被排除，按 sortOrder 排序。
     * 顺序会影响结果，不能乱。
     */
    fun upReplaceRules() {
        val rules = allRules
        titleReplaceRules = rules.filter { it.isEnabled && it.scopeTitle && inScope(it) }
            .sortedBy { it.order }
        contentReplaceRules = rules.filter { it.isEnabled && it.scopeContent && inScope(it) }
            .sortedBy { it.order }
    }

    private fun inScope(rule: ReplaceRule): Boolean {
        val scope = rule.scope
        val hit = scope.isNullOrEmpty() ||
            scope.contains(bookName) || scope.contains(bookOrigin)
        if (!hit) return false
        val exclude = rule.excludeScope
        if (!exclude.isNullOrEmpty() &&
            (exclude.contains(bookName) || exclude.contains(bookOrigin))
        ) return false
        return true
    }

    fun getTitleReplaceRules(): List<ReplaceRule> = titleReplaceRules

    fun getContentReplaceRules(): List<ReplaceRule> = contentReplaceRules

    /**
     * 返回净化并分段后的正文。段号即 textList 的下标 + 1。
     */
    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true
    ): List<String> {
        var mContent = content
        if (content != "null") {
            //去除重复标题
            try {
                val name = Pattern.quote(book.name)
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                var matcher = sameTitleLineMatcher(mContent, name, title)
                if (matcher.find()) {
                    mContent = mContent.substring(matcher.end())
                } else if (useReplace && book.useReplaceRule) {
                    title = Pattern.quote(chapter.title)
                    matcher = sameTitleLineMatcher(mContent, name, title)
                    if (matcher.find()) {
                        mContent = mContent.substring(matcher.end())
                    }
                }
            } catch (_: Exception) {
                // 标题里有奇怪字符导致模式非法，跳过这一步即可
            }

            if (chineseConvert) {
                //简繁转换 —— 必须在净化规则之前，
                //否则繁体正文匹配不上用简体写的规则
                try {
                    when (chineseConverterType) {
                        1 -> mContent = ChineseUtils.t2s(mContent)
                        2 -> mContent = ChineseUtils.s2t(mContent)
                    }
                } catch (_: Exception) {
                    // 词库加载失败就不转，不能带垮整章
                }
            }

            val useHtmlMap = mutableMapOf<String, String>()
            mContent = AppPattern.useHtmlRegex.replace(mContent) { matchResult ->
                val placeholder = "特殊格式的占位不应该被看见${useHtmlMap.size}。"
                useHtmlMap[placeholder] = "\n${matchResult.value.replace("\n", "")}\n"
                placeholder
            }

            if (useReplace && book.useReplaceRule) {
                //替换净化：全文替换前先逐行 trim，否则行首尾的空白会让规则匹配不到
                mContent = mContent.lines().joinToString("\n") { it.trim() }
                getContentReplaceRules().forEach { item ->
                    if (item.pattern.isEmpty()) return@forEach
                    try {
                        val tmp = if (item.isRegex) {
                            mContent.replace(
                                item.name,
                                item.regex,
                                item.replacement,
                                item.getValidTimeoutMillisecond(),
                                chapter
                            )
                        } else {
                            mContent.replace(item.pattern, item.replacement)
                        }
                        if (mContent != tmp) mContent = tmp
                    } catch (_: RegexTimeoutException) {
                        // 病态正则，跳过这一条，其余照常
                    } catch (_: Exception) {
                        // 单条规则出错不能带垮整章
                    }
                }
            }

            useHtmlMap.forEach { (placeholder, originalContent) ->
                mContent = mContent.replace(placeholder, originalContent)
            }
        }

        if (includeTitle) {
            mContent = chapter.title + "\n" + mContent
        }

        val contents = arrayListOf<String>()
        mContent.lineSequence().forEach { str ->
            val paragraph = str.trim { it.code <= 0x20 || it == '　' }
            if (paragraph.isNotEmpty()) {
                contents.add(paragraph)
            }
        }
        return contents
    }
}
