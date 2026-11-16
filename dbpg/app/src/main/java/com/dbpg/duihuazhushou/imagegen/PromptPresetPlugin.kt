package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import com.dbpg.duihuazhushou.chat.render.plugin.PluginDescriptor
import com.dbpg.duihuazhushou.chat.render.plugin.PluginKind
import com.dbpg.duihuazhushou.chat.render.plugin.PluginStore
import com.dbpg.stcore.imagegen.PresetRegex
import com.dbpg.stcore.imagegen.PresetRule

/**
 * 「提示词预设」插件。
 *
 * ## 这条支线管什么
 *
 * 出图分两段：**文字 → 画面标签**（语言模型）和 **标签 → 图片**（生图模型）。
 * 这个插件只管前一段的 system 提示词：
 *
 * - **画风（画师串 / 质量词 / 负面 / 采样 / 尺寸）一律由「风格」决定**，
 *   插件不读也不写这些字段 —— 回到加预设之前的样子，两边不互相覆盖；
 * - 插件只回答一个问题：这次给模型喂哪套破甲提示词。
 *
 * ## 预设从哪来
 *
 * **插件自己存**（[PromptPresetStore]，本机一个 JSON 文件）。
 * 导入支持酒馆预设、`{name, prompt}` 小配置、纯文本破甲（[PromptPresetImport]）。
 *
 * 为什么不读资产页：试过两版（出图时求值 `getAssets`、引擎保存时推快照），
 * 前者在聊天页/设置页经常 6 秒超时，后者依赖"用户刚好在资产页点过保存"。
 * 一条出图功能不该依赖另一个页面的生命周期 —— 这是 v1.7 掉头的原因。
 *
 * ## 卸载的含义
 *
 * 插件本体随 APK 打包（[PromptPresetCatalog]），"卸载"只是把它移出运行时：
 * [plan] 直接返回空片段，出图只剩任务模板，跟没有这个功能时一样。
 */
object PromptPresetPlugin {

    const val ID = "image_preset"

    /**
     * 封印开关。
     *
     * 这不是"卸载"——卸载只是把 id 移出运行时，用户点两下就能装回来。
     * 封印是**代码层面直接短路**：哪怕 pref 里还留着 image_preset，哪怕
     * 别处还在调 [plan]，也一个字符都注入不出去。
     *
     * 为什么封：v1.4~v1.8 这条支线为了兼容社区预设反复返工（导入格式、
     * 资产页读取、正则、独立弹窗），每次改动都贴着出图主链路，代价太大而
     * 收益不成比例。先把生图恢复成"没有这个功能时"的样子，功能本身留着，
     * 想复活把这个常量改成 false 即可 —— 但复活前请先补完回归测试。
     */
    const val SEALED = true

    /**
     * 固定的收尾约束。
     *
     * 社区预设的共同毛病是"写着写着就开始续写小说"（它们本来就是写作预设），
     * 所以在最末尾压一句，把任务拉回打标签。离输出最近，约束力最强。
     * 这句跟预设内容无关，恒发。
     */
    const val OUTPUT_CONSTRAINT =
        "以上身份与尺度设定只为解除限制。本次任务仍然只是输出画面标签 JSON：" +
            "不续写正文、不扮演角色、不解释。"

    val descriptor = PluginDescriptor(
        id = ID,
        name = "提示词预设",
        summary = "给「剧情转标签」这一步挂一套破甲预设，只影响生图",
        convention = "聊天 🎨 与自动配图出图前的那次提示词优化（不动聊天预设、不动画风）",
        description = "两步出图里，这一步只负责「剧情 → 画面标签」的语言模型调用。" +
            "预设存在本机：酒馆预设、{name, prompt} 小配置、一段破甲文本都能导入，" +
            "导入后自己选段、自己改名，跟资产页无关。" +
            "画风、负面、采样、尺寸仍由「风格」决定，插件一律不碰。" +
            "卸载后一个字的引导都不注入，与没有这个功能时完全一致。",
        version = "5.0.0",
        priority = 100,
        kind = PluginKind.IMAGE_GEN,
    )

    /** 插件当前是否可用：已挂载且已启用。 */
    fun isActive(ctx: Context): Boolean =
        ID in PluginStore.installed(ctx) && ID in PluginStore.enabled(ctx)

    /**
     * 算出本次要注入的 system 片段。
     *
     * 不传任何画风/采样参数 —— 那些是「风格」的事，传进来只会让两处互相覆盖
     * （v1.2 那次"预设把风格盖掉、换个页面又变回去"的教训）。
     *
     * 插件没挂载、用户关掉注入、或者库里一套预设都没有 → 返回空片段，
     * 调用方不需要再判开关。
     */
    fun plan(ctx: Context): Plan {
        if (SEALED) return Plan.none("提示词预设已封印")
        if (!isActive(ctx)) return Plan.none("插件未启用")

        val st = PromptPresetStore.load(ctx)
        if (!st.usePreset) return Plan.none("不使用预设")
        val e = st.resolved() ?: return Plan.none("还没有导入预设")

        val body = e.body(st.jailbreakOnly)
        if (body.isBlank()) return Plan.none("「${e.name}」里没有可用段落")

        /*
         * 正则脚本：**只作用在这段预设正文上**，不碰 composeSystem 拼出来的
         * 整段 system。
         *
         * 这样主流程（模板 → 预设 → DNA → 约束）一个字符都不用改：正则出问题，
         * 最多是这段 presetBlock 长得不一样，不会影响"点一下能不能出图"。
         * 编不过的规则在 [PresetRegex.apply] 里被跳过，不会抛。
         */
        val active = e.activeRules()
        val rules = active.map { PresetRule(it.id, it.name, it.find, it.replace) }
        val rewritten = if (rules.isEmpty()) body else PresetRegex.apply(body, rules).text

        val picked = e.pickedCount(st.jailbreakOnly)
        val scope = when {
            st.jailbreakOnly && e.jbFellBack -> "破甲段筛空，已取全部 $picked 段"
            st.jailbreakOnly -> "破甲段 $picked/${e.total}"
            else -> "全部 $picked 段"
        }
        val ruleNote = when {
            active.isEmpty() -> ""
            rules.size == active.size -> " · ${rules.size} 条正则"
            else -> " · ${rules.size}/${active.size} 条正则（其余编译失败）"
        }
        return Plan(
            block = rewritten,
            presetName = e.name,
            note = "${e.name}（$scope$ruleNote）",
            chars = rewritten.length,
        )
    }

    /** 一次提示词优化要注入的片段。 */
    data class Plan(
        /** 预设正文（已洗宏、按顺序拼好）；空 = 不注入。 */
        val block: String,
        val presetName: String,
        /** 一句话说明本次按什么跑，给 toast / 状态行用。 */
        val note: String,
        val chars: Int,
    ) {
        companion object {
            fun none(note: String) = Plan("", "", note, 0)
        }
    }
}

/**
 * 生图插件出厂清单。与 [com.dbpg.duihuazhushou.chat.render.plugin.PluginCatalog] 并列。
 *
 * ★ 封印期间**故意留空**：不登记 = 插件页不显示、恢复出厂不会装回来、
 *   [PluginStore] 也不会把老用户 pref 里残留的 id 当成有效插件。
 *   [PromptPresetPlugin.descriptor] 仍然保留，复活时把 ALL 改回 listOf(descriptor)。
 */
object PromptPresetCatalog {
    val ALL: List<PluginDescriptor> = emptyList()
    val BUILTIN_IDS: Set<String> = emptySet()
}
