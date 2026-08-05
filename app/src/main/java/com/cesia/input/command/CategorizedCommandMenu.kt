package com.cesia.input.command

import android.content.Context
import com.cesia.input.instruction.InstructionSet

/**
 * 智能写作 / 智能修改 的分类命令菜单（轻量、纯代码、不依赖外部 JSON）。
 *
 * 旧版 CommandLibrary 用 JSON 文件持久化分类，文件缺失/解析失败就整个菜单空白，
 * 且所有生成类指令的 category 都是同一个「生成」，无法按场景展示。本类改为：
 *  - 分类映射直接写在代码里（每条指令 id 归属哪些分类标签），弹窗打开即用，绝不会空白；
 *  - 常用标签按使用次数（SharedPreferences）动态排前 N 条；
 *  - 智能写作 = 6 大场景包，智能修改 = 4 大维度（均按业务文章结构重组）。
 */
object CategorizedCommandMenu {

    /**
     * 指令 id -> 所属分类标签集合。
     * 智能写作（isGen=true）按 6 大场景包分组；智能修改（isGen=false）按 4 大维度分组。
     * 注意：此处标签名必须与下方 [TAB_ORDER_GEN] / [TAB_ORDER_MODIFY] 完全一致。
     */
    private val ID_TO_TABS: Map<String, List<String>> = mapOf(
        // ===== 智能写作 / 生成类：6 大场景包 =====
        // 社媒文案
        "gen_xhs" to listOf("社媒文案"),
        "gen_moments" to listOf("社媒文案"),
        "gen_weibo" to listOf("社媒文案"),
        "gen_linkedin" to listOf("社媒文案"),
        "gen_news" to listOf("社媒文案", "职场办公"),
        // 职场办公
        "gen_weekly" to listOf("职场办公"),
        "gen_meeting" to listOf("职场办公"),
        "gen_ppt" to listOf("职场办公"),
        "gen_okr" to listOf("职场办公"),
        "gen_compete" to listOf("职场办公"),
        "gen_email" to listOf("职场办公"),
        "gen_title" to listOf("职场办公", "学术写作"),
        // 学术写作
        "gen_litreview" to listOf("学术写作"),
        "gen_thesis" to listOf("学术写作"),
        "gen_dedup" to listOf("学术写作"),
        "gen_ref" to listOf("学术写作"),
        "gen_summary" to listOf("学术写作", "职场办公"),
        // 电商运营
        "gen_selling" to listOf("电商运营"),
        "gen_detail" to listOf("电商运营"),
        "gen_live" to listOf("电商运营"),
        "gen_review" to listOf("电商运营"),
        // 编程辅助
        "gen_code" to listOf("编程辅助"),
        "gen_refactor" to listOf("编程辅助"),
        "gen_unittest" to listOf("编程辅助"),
        "gen_apidoc" to listOf("编程辅助"),
        "gen_sql" to listOf("编程辅助"),
        "gen_table" to listOf("编程辅助", "职场办公"),
        "gen_list" to listOf("编程辅助", "职场办公"),
        // 生活实用
        "gen_idea" to listOf("生活实用"),
        "gen_write" to listOf("生活实用"),
        "gen_story" to listOf("生活实用"),
        "gen_poem" to listOf("生活实用"),
        "gen_travel" to listOf("生活实用"),
        "gen_recipe" to listOf("生活实用"),
        "gen_fitness" to listOf("生活实用"),
        "gen_gift" to listOf("生活实用"),
        "gen_decor" to listOf("生活实用"),
        // 跨场景通用生成
        "gen_continue" to listOf("生活实用", "学术写作"),
        "gen_expand" to listOf("生活实用", "学术写作"),
        "gen_rewrite" to listOf("生活实用", "职场办公"),
        "gen_translate_en" to listOf("职场办公", "学术写作"),

        // ===== 智能修改 / 操作类：4 大维度 =====
        // 语言转换（翻译）
        "trans_en" to listOf("语言转换"),
        "trans_ja" to listOf("语言转换"),
        "trans_ko" to listOf("语言转换"),
        "trans_fr" to listOf("语言转换"),
        "trans_de" to listOf("语言转换"),
        "trans_es" to listOf("语言转换"),
        "trans_ru" to listOf("语言转换"),
        "trans_ar" to listOf("语言转换"),
        "trans_zh" to listOf("语言转换"),
        "trans_auto" to listOf("语言转换"),
        // 风格重塑（语气 + 长度 + 格式）
        "tone_formal" to listOf("风格重塑"),
        "tone_casual" to listOf("风格重塑"),
        "tone_humorous" to listOf("风格重塑"),
        "tone_serious" to listOf("风格重塑"),
        "tone_gentle" to listOf("风格重塑"),
        "tone_authoritative" to listOf("风格重塑"),
        "len_expand" to listOf("风格重塑"),
        "len_compress" to listOf("风格重塑"),
        "len_expand_double" to listOf("风格重塑"),
        "len_compress_tight" to listOf("风格重塑"),
        "fmt_paragraph" to listOf("风格重塑"),
        "fmt_punctuate" to listOf("风格重塑"),
        "fmt_clean" to listOf("风格重塑"),
        "fmt_merge" to listOf("风格重塑"),
        "fmt_bullet" to listOf("风格重塑"),
        "fmt_upper" to listOf("风格重塑"),
        "fmt_lower" to listOf("风格重塑"),
        // 内容深化（内容 + 润色）
        "cnt_summarize" to listOf("内容深化"),
        "cnt_question" to listOf("内容深化"),
        "cnt_example" to listOf("内容深化"),
        "cnt_quote" to listOf("内容深化"),
        "cnt_simplify" to listOf("内容深化"),
        "polish_general" to listOf("内容深化"),
        "polish_grammar" to listOf("内容深化"),
        "polish_logic" to listOf("内容深化"),
        "polish_wordchoice" to listOf("内容深化"),
        "polish_concise" to listOf("内容深化"),
        "polish_vivid" to listOf("内容深化"),
        "polish_emotion" to listOf("内容深化"),
        "polish_professional" to listOf("内容深化"),
        "polish_readable" to listOf("内容深化"),
        "polish_paragraph" to listOf("内容深化"),
        "polish_transition" to listOf("内容深化"),
        "polish_opening" to listOf("内容深化"),
        // 特殊处理
        "spc_pinyin" to listOf("特殊处理"),
        "spc_trad2simp" to listOf("特殊处理"),
        "spc_simp2trad" to listOf("特殊处理"),
        "spc_pinyin_full" to listOf("特殊处理"),
        "spc_vertical" to listOf("特殊处理"),
        "spc_split" to listOf("特殊处理")
    )

    private val TAB_ORDER_GEN = listOf(
        "常用", "社媒文案", "职场办公", "学术写作", "电商运营", "编程辅助", "生活实用"
    )
    private val TAB_ORDER_MODIFY = listOf(
        "常用", "语言转换", "风格重塑", "内容深化", "特殊处理"
    )

    /** 返回标签顺序（常用 + 各分类）。isGen=true 写作，false 修改 */
    fun getTabOrder(isGen: Boolean): List<String> = if (isGen) TAB_ORDER_GEN else TAB_ORDER_MODIFY

    /** 取单条指令（来自 InstructionSet） */
    fun getInstruction(id: String): InstructionSet.Instruction? =
        InstructionSet.allInstructions.find { it.id == id }
            ?: InstructionSet.starInstructions.find { it.id == id }

    /** 某标签下的指令 id 列表（按 InstructionSet 原始顺序，保证稳定） */
    fun getCommandIdsForTab(context: Context, isGen: Boolean, tab: String): List<String> {
        if (tab == "常用") return getFrequentIds(context, isGen)
        val source = if (isGen) InstructionSet.starInstructions else InstructionSet.allInstructions
        return source.map { it.id }.filter { id -> ID_TO_TABS[id]?.contains(tab) == true }
    }

    // ===== 常用统计 =====
    private const val PREF_USAGE = "cesia_cmd_usage"
    private const val USAGE_LIMIT = 12

    fun recordUsage(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREF_USAGE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("recent", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(id)
        set.add(id)
        while (set.size > USAGE_LIMIT) set.remove(set.first())
        prefs.edit().putStringSet("recent", set).apply()
    }

    fun getFrequentIds(context: Context, isGen: Boolean): List<String> {
        val prefs = context.getSharedPreferences(PREF_USAGE, Context.MODE_PRIVATE)
        val recent = prefs.getStringSet("recent", emptySet())?.toList() ?: emptyList()
        val source = if (isGen) InstructionSet.starInstructions else InstructionSet.allInstructions
        val sourceIds = source.map { it.id }.toSet()
        return recent.filter { it in sourceIds }
    }
}
