package io.legado.app.model.analyzeRule

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import com.jayway.jsonpath.ReadContext
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}

@Suppress("RegExpRedundantEscape")
class AnalyzeByJSonPath(
    json: Any,
    private val onError: ((Exception) -> Unit)? = null,
) {

    companion object {

        fun parse(json: Any): ReadContext {
            return when (json) {
                is ReadContext -> json
                is String -> JsonPath.parse(json) //JsonPath.parse<String>(json)
                else -> JsonPath.parse(json) //JsonPath.parse<Any>(json)
            }
        }
    }

    private var ctx: ReadContext = parse(json)

    /**
     * 改进解析方法
     * 解决阅读”&&“、”||“与jsonPath支持的”&&“、”||“之间的冲突
     * 解决{$.rule}形式规则可能匹配错误的问题，旧规则用正则解析内容含‘}’的json文本时，用规则中的字段去匹配这种内容会匹配错误.现改用平衡嵌套方法解决这个问题
     * */
    fun getString(rule: String): String? {
        if (rule.isEmpty()) return null
        var result: String
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||")

        if (rules.size == 1) {

            ruleAnalyzes.reSetPos() //将pos重置为0，复用解析器

            result = ruleAnalyzes.innerRule("{$.") { getString(it) } //替换所有{$.rule...}

            if (result.isEmpty()) { //st为空，表明无成功替换的内嵌规则

                try {

                    val ob = ctx.read<Any>(rule)
                    result = if (ob is List<*>) {
                        ob.joinToString("\n")
                    } else {
                        ob.toString()
                    }

                } catch (e: Exception) {
                    onError?.invoke(e) ?: logJsonPathError(rule, e)
                }

            }

            return result

        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(rl)
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            return textList.joinToString("\n")
        }
    }

    internal fun getStringList(rule: String): List<String> {
        val result = ArrayList<String>()
        if (rule.isEmpty()) return result
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {

            ruleAnalyzes.reSetPos() //将pos重置为0，复用解析器

            val st = ruleAnalyzes.innerRule("{$.") { getString(it) } //替换所有{$.rule...}

            if (st.isEmpty()) { //st为空，表明无成功替换的内嵌规则

                try {

                    val obj = ctx.read<Any>(rule) //kotlin的Any型返回值不包含null ，删除赘余 ?: return result

                    if (obj is List<*>) {

                        for (o in obj) result.add(o.toString())

                    } else {
                        result.add(obj.toString())
                    }
                } catch (e: Exception) {
                    onError?.invoke(e) ?: logJsonPathError(rule, e)
                }
            } else {
                result.add(st)
            }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) {
                            if (i < temp.size) {
                                result.add(temp[i])
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        result.addAll(temp)
                    }
                }
            }
            return result
        }
    }

    internal fun getObject(rule: String): Any {
        return ctx.read(rule)
    }

    internal fun getList(rule: String): ArrayList<Any>? {
        val result = ArrayList<Any>()
        if (rule.isEmpty()) return result
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (rules.size == 1) {
            ctx.let {
                try {
                    return ArrayList(it.read<List<Any>>(rules[0]))
                } catch (e: Exception) {
                    onError?.invoke(e) ?: logJsonPathError(rule, e)
                }
            }
        } else {
            val results = ArrayList<ArrayList<*>>()
            for (rl in rules) {
                val temp = getList(rl)
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in 0 until results[0].size) {
                        for (temp in results) {
                            if (i < temp.size) {
                                temp[i]?.let { result.add(it) }
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        result.addAll(temp)
                    }
                }
            }
        }
        return result
    }

}

/**
 * JsonPath 取值失败只记一行。
 *
 * 书源规则里常留着注释（如 "//删掉这行字，vip章节会显示"），
 * 会被当成路径执行并抛 PathNotFoundException；目录每章都试一次，
 * 打完整堆栈会把日志刷爆，而这类失败本来就是预期内的。
 */
private fun logJsonPathError(rule: String, e: Exception) {
    if (e is PathNotFoundException) {
        logger.debug { "JsonPath 未命中: ${rule.take(40)}" }
    } else {
        logger.warn { "JsonPath 解析出错: ${rule.take(40)} - ${e.message}" }
    }
}
