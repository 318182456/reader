package io.legado.app.constant

import java.util.regex.Pattern

object AppPattern {
    val JS_PATTERN: Pattern =
        Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)
    val EXP_PATTERN: Pattern = Pattern.compile("\\{\\{([\\w\\W]*?)\\}\\}")

    //匹配格式化后的图片格式
    val imgPattern: Pattern = Pattern.compile("<img[^>]*src=\"([^\"]*(?:\"[^>]+\\})?)\"[^>]*>")

    //dataURL图片类型
    val dataUriRegex = Regex("data:.*?;base64,(.*)")

    val nameRegex = Regex("\\s+作\\s*者.*|\\s+\\S+\\s+著")
    val authorRegex = Regex("^\\s*作\\s*者[:：\\s]+|\\s+著")
    val fileNameRegex = Regex("[\\\\/:*?\"<>|.]")
    val splitGroupRegex = Regex("[,;，；]")

    // 与 legado 一致：正文按行处理时统一用它切分
    val LFRegex = "\n".toRegex()

    //书源调试信息中的各种符号
    val debugMessageSymbolRegex = Regex("[⇒◇┌└≡]")

    //本地书籍支持类型
    val bookFileRegex = Regex(".*\\.(txt|epub|umd)", RegexOption.IGNORE_CASE)

    /**
     * 所有标点
     */
    val bdRegex = Regex("(\\p{P})+")

    /**
     * 换行
     */
    val rnRegex = Regex("[\\r\\n]")

    /**
     * 不发音段落判断
     */
    val notReadAloudRegex = Regex("^(\\s|\\p{C}|\\p{P}|\\p{Z}|\\p{S})+$")

    // 净化替换用：去重复标题时把标题里的空白当通配
    val spaceRegex = "\\s+".toRegex()

    // 正则元字符，拼动态模式时需要转义
    val regexCharRegex = "[{}()\\[\\].+*?^$\\\\|]".toRegex()

    // <usehtml> 包裹的特殊格式，净化前先换成占位符避免被规则破坏
    val useHtmlRegex = Regex("<usehtml>.*?</usehtml>", RegexOption.DOT_MATCHES_ALL)
}