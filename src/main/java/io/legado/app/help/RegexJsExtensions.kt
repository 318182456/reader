package io.legado.app.help

import io.legado.app.model.analyzeRule.RuleData

/**
 * @js: 净化规则里可用的 java 对象。
 *
 * 与 legado 同名类保持一致的方法签名 —— 规则脚本是从 App 备份带过来的，
 * 少一个方法就会在执行时抛错、整条规则失效，段落数随之对不上。
 * 简繁转换（t2s/s2t）依赖 Android 侧的词库，这里退化为原样返回。
 */
@Suppress("unused")
class RegexJsExtensions(private val name: String) {
    private val ruleData by lazy { RuleData() }

    fun log(msg: Any?): Any? = msg

    fun logType(any: Any?) {
        // 调试用，服务端无需输出
    }

    fun t2s(text: String): String = text

    fun s2t(text: String): String = text

    fun get(key: String): String {
        return ruleData.variableMap[key] ?: ""
    }

    fun put(key: String, value: String): String {
        ruleData.putVariable(key, value)
        return value
    }
}
