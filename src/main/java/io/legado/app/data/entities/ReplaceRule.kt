package io.legado.app.data.entities

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 净化替换规则。
 *
 * 字段与 legado 的 replace_rules 表一一对应 —— App 备份出来的 replaceRule.json
 * 直接反序列化到这里，多一个字段少一个字段都会让净化结果和 App 对不上。
 */
// App 备份里可能带着 reader 没定义的字段，遇到就忽略而不是报错
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplaceRule(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var group: String? = null,
    var pattern: String = "",
    var replacement: String = "",
    //作用范围：书名或书源地址，空表示全部
    var scope: String? = null,
    //作用于标题
    @get:JsonProperty("scopeTitle") var scopeTitle: Boolean = false,
    //作用于书源
    @get:JsonProperty("scopeSource") var scopeSource: Boolean = false,
    //作用于正文
    @get:JsonProperty("scopeContent") var scopeContent: Boolean = true,
    //排除范围
    var excludeScope: String? = null,
    @get:JsonProperty("isEnabled") var isEnabled: Boolean = true,
    @get:JsonProperty("isRegex") var isRegex: Boolean = false,
    //单条规则的替换超时，防止病态正则卡死整章
    var timeoutMillisecond: Long = 3000L,
    var order: Int = 0
) {

    override fun equals(other: Any?): Boolean {
        if (other is ReplaceRule) {
            return other.id == id
        }
        return super.equals(other)
    }

    override fun hashCode(): Int = id.hashCode()

    @get:JsonIgnore
    val regex: Regex by lazy { pattern.toRegex() }

    fun isValid(): Boolean {
        if (pattern.isEmpty()) return false
        if (isRegex) {
            try {
                Pattern.compile(pattern)
            } catch (_: PatternSyntaxException) {
                return false
            }
            // 能编译但会替换超时的写法：末尾漏删的 |
            if (pattern.endsWith('|') && !pattern.endsWith("\\|")) return false
        }
        return true
    }

    fun getValidTimeoutMillisecond(): Long {
        if (timeoutMillisecond <= 0) return 3000L
        return timeoutMillisecond
    }
}
