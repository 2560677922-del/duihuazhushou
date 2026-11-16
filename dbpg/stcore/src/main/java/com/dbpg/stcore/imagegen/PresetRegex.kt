package com.dbpg.stcore.imagegen

import com.dbpg.stcore.JsRegex
import com.dbpg.stcore.RawScript
import com.dbpg.stcore.SUBSTITUTE_REGEX
import com.dbpg.stcore.applyScriptToText

/**
 * 预设自带的一条「正则改写」。
 *
 * 社区预设（尤其酒馆那套）常把正则脚本一起打包：把 `{{char}}` 换成名字、
 * 删掉 `<page>` 包裹、压掉重复空行…… 这些脚本本来是给写作流程用的，
 * 但其中相当一部分直接决定 system 最后长什么样，丢掉会让提示词跟作者
 * 的预期差很远 —— 所以导入时一并收下，默认生效，用户可以在设置里逐条关。
 *
 * ## 为什么只存字符串
 *
 * v1.7.1 的教训：把外部文本当正则直接编译，只要目标环境不认某一种写法
 * （`\{\{char\}\}` 这种在部分引擎上就会抛），**整份预设就导入失败**。
 * 所以这里只存原文（含 `/.../gi` 外壳），真正编译放到 [PresetRegex.apply]
 * 里逐条 runCatching：一条写坏了，丢一条，其余照跑。
 *
 * ## 执行语义不另起一套
 *
 * 编译与替换都走聊天侧同一个 [JsRegex]（`$0` / `{{match}}` / 变长后顾放宽
 * 都在里面），只是入口换成了 [applyScriptToText] —— 生图的 system 是裸文本，
 * 没有消息、没有 depth。
 */
data class PresetRule(
    val id: String,
    /** 脚本名，界面上显示用；空则退化成正则本身。 */
    val name: String,
    /** 原始 `findRegex`，可能带 `/pattern/gi` 外壳。 */
    val find: String,
    /** 替换串，`$1` / `{{match}}` 都支持。 */
    val replace: String,
) {
    /** 这条规则能编译成正则吗；不能就不要往里放。 */
    fun compiles(): Boolean = runCatching { JsRegex.compile(find) }.isSuccess

    internal fun toScript(): RawScript = RawScript(
        id = id,
        scriptName = name,
        name = name,
        findRegex = find,
        replaceString = replace,
        placement = null,
        disabled = false,
        trimStrings = null,
        markdownOnly = false,
        promptOnly = true,
        substituteRegex = SUBSTITUTE_REGEX.NONE,
        minDepth = null,
        maxDepth = null,
    )

    internal fun label(): String =
        name.ifBlank { find.take(40).ifBlank { "(空正则)" } }
}

/**
 * 把一组 [PresetRule] 依次套到文本上。
 *
 * 顺序就是导入时文件里的顺序 —— 正则脚本之间常常有依赖（先删包裹、再换名字），
 * 重排会把结果改掉。
 */
object PresetRegex {

    /**
     * 跑完之后如果剩下的字数不到原来的三成，判定为"规则失控"并整段回滚。
     *
     * 真实场景：某条脚本写的是 `(?s).*` 之类的贪婪匹配，replace 成空 ——
     * 一条就能把整个 system 抹掉。相比"出图时莫名没有引导语"，
     * 回滚到没跑规则的样子更容易被发现和解释。
     */
    private const val MIN_KEEP_RATIO = 0.3

    data class Outcome(
        val text: String,
        /** 真正改动了文本的规则条数。 */
        val applied: Int,
        /** 编译或执行失败的规则名（给界面/日志用）。 */
        val failed: List<String>,
        /** 被判定失控、已回滚。 */
        val rolledBack: Boolean,
    ) {
        companion object {
            fun identity(text: String) = Outcome(text, 0, emptyList(), false)
        }
    }

    /** 单条：编译失败返回 null。导入器的洗宏规则走这里（坏一条跳过，不整段失败）。 */
    fun applyOne(text: String, rule: PresetRule): String? =
        runCatching { applyScriptToText(text, rule.toScript()) }.getOrNull()

    fun apply(text: String, rules: List<PresetRule>): Outcome {
        if (text.isEmpty() || rules.isEmpty()) return Outcome.identity(text)
        var cur = text
        var applied = 0
        val failed = ArrayList<String>(0)
        for (r in rules) {
            val next = runCatching { applyScriptToText(cur, r.toScript()) }.getOrNull()
            if (next == null) {
                failed.add(r.label())
                continue
            }
            if (next != cur) {
                cur = next
                applied++
            }
        }
        val rolledBack = cur.length < text.length * MIN_KEEP_RATIO
        return Outcome(
            text = if (rolledBack) text else cur,
            applied = if (rolledBack) 0 else applied,
            failed = failed,
            rolledBack = rolledBack,
        )
    }
}
