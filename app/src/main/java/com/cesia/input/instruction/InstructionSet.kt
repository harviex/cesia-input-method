package com.cesia.input.instruction

/**
 * 指令表 - 统一管理所有魔法指令和语音指令
 * 
 * 分类：
 * - 翻译类：翻译为各种语言
 * - 语气类：正式/口语/幽默等
 * - 长度类：扩充/压缩/指定字数
 * - 格式类：分段/排版/标点等
 * - 内容类：概括/总结/扩写/续写等
 * - 特殊类：敏感词/繁简/拼音等
 * - 生成类：帮我想/帮我写等（允许空文本）
 * 
 * 每条指令包含：
 * - id: 唯一标识
 * - category: 分类
 * - name: 简短名称（显示用）
 * - instruction: 完整指令文本（传给 AI 的 prompt）
 * - keywords: 语音识别匹配关键词
 * - isGeneration: 是否为生成类（允许空文本）
 */
object InstructionSet {

    data class Instruction(
        val id: String,
        val category: String,
        val name: String,
        val instruction: String,
        val keywords: List<String>,
        val isGeneration: Boolean = false
    )

    // ==================== 翻译类 ====================
    private val translateInstructions = listOf(
        Instruction("trans_en", "翻译", "翻译为英文", "将以下文字翻译为英文，只输出翻译后的文字，不要输出任何解释", listOf("翻译英文", "翻译成英文", "翻译为英文", "英文翻译", "翻英文", "翻成英文")),
        Instruction("trans_ja", "翻译", "翻译为日文", "将以下文字翻译为日文，只输出翻译后的文字，不要输出任何解释", listOf("翻译日文", "翻译成日文", "翻译为日文", "日文翻译", "翻日文", "翻成日文")),
        Instruction("trans_ko", "翻译", "翻译为韩文", "将以下文字翻译为韩文，只输出翻译后的文字，不要输出任何解释", listOf("翻译韩文", "翻译成韩文", "翻译为韩文", "韩文翻译", "翻韩文", "翻成韩文")),
        Instruction("trans_fr", "翻译", "翻译为法文", "将以下文字翻译为法文，只输出翻译后的文字，不要输出任何解释", listOf("翻译法文", "翻译成法文", "翻译为法文", "法文翻译", "翻法文", "翻成法文")),
        Instruction("trans_de", "翻译", "翻译为德文", "将以下文字翻译为德文，只输出翻译后的文字，不要输出任何解释", listOf("翻译德文", "翻译成德文", "翻译为德文", "德文翻译", "翻德文", "翻成德文")),
        Instruction("trans_es", "翻译", "翻译为西班牙文", "将以下文字翻译为西班牙文，只输出翻译后的文字，不要输出任何解释", listOf("翻译西班牙文", "翻译成西班牙文", "翻译为西班牙文", "西班牙文翻译")),
        Instruction("trans_ru", "翻译", "翻译为俄文", "将以下文字翻译为俄文，只输出翻译后的文字，不要输出任何解释", listOf("翻译俄文", "翻译成俄文", "翻译为俄文", "俄文翻译")),
        Instruction("trans_ar", "翻译", "翻译为阿拉伯文", "将以下文字翻译为阿拉伯文，只输出翻译后的文字，不要输出任何解释", listOf("翻译阿拉伯文", "翻译成阿拉伯文", "翻译为阿拉伯文", "阿拉伯文翻译")),
        Instruction("trans_zh", "翻译", "翻译为中文", "将以下文字翻译为中文，只输出翻译后的文字，不要输出任何解释", listOf("翻译中文", "翻译成中文", "翻译为中文", "中文翻译")),
        Instruction("trans_auto", "翻译", "自动翻译", "检测以下文字的语言，翻译为中文（如果原文是中文则翻译为英文），只输出翻译结果", listOf("自动翻译", "智能翻译", "检测翻译"))
    )

    // ==================== 语气类 ====================
    private val toneInstructions = listOf(
        Instruction("tone_formal", "语气", "改为正式", "Rewrite the following text in a formal tone. Output only the rewritten text, no explanation.\n\nText:", listOf("正式", "书面", "官方", "正式语气", "改为正式", "正式一点", "正式化")),
        Instruction("tone_casual", "语气", "改为口语", "Rewrite the following text in a casual, conversational tone. Output only the rewritten text, no explanation.\n\nText:", listOf("口语", "随意", "大白话", "口语化", "改为口语", "口语一点", "通俗")),
        Instruction("tone_humorous", "语气", "改为幽默", "Rewrite the following text in a humorous style. Output only the rewritten text, no explanation.\n\nText:", listOf("幽默", "搞笑", "有趣", "幽默化", "改为幽默", "幽默一点", "风趣")),
        Instruction("tone_serious", "语气", "改为严肃", "Rewrite the following text in a serious tone. Output only the rewritten text, no explanation.\n\nText:", listOf("严肃", "严厉", "严肃化", "改为严肃", "严肃一点", "正经")),
        Instruction("tone_gentle", "语气", "改为温柔", "Rewrite the following text in a gentle, warm tone. Output only the rewritten text, no explanation.\n\nText:", listOf("温柔", "亲切", "柔和", "温柔化", "改为温柔", "温柔一点", "温和")),
        Instruction("tone_authoritative", "语气", "改为权威", "Rewrite the following text in an authoritative, professional tone. Output only the rewritten text, no explanation.\n\nText:", listOf("权威", "专业", "权威化", "改为权威", "权威一点", "专业化")),
    )

    // ==================== 长度类 ====================
    private val lengthInstructions = listOf(
        Instruction("len_expand", "长度", "扩充内容", "Expand the following text with more details and descriptions. Output only the expanded text, no explanation.\n\nText:", listOf("扩充", "扩写", "展开", "增加内容", "写长一点", "扩充内容", "丰富内容")),
        Instruction("len_compress", "长度", "压缩内容", "Compress the following text, keeping only the core information. Output only the compressed text, no explanation.\n\nText:", listOf("压缩", "精简", "缩短", "写短一点", "压缩内容", "精简内容")),
        Instruction("len_50", "长度", "扩充到50字", "Expand the following text to about 50 Chinese characters. Output only the expanded text.\n\nText:", listOf("扩充到50字", "50字", "写50字", "50字左右")),
        Instruction("len_100", "长度", "扩充到100字", "Expand the following text to about 100 Chinese characters. Output only the expanded text.\n\nText:", listOf("扩充到100字", "100字", "写100字", "100字左右")),
        Instruction("len_200", "长度", "扩充到200字", "Expand the following text to about 200 Chinese characters. Output only the expanded text.\n\nText:", listOf("扩充到200字", "200字", "写200字", "200字左右")),
        Instruction("len_half", "长度", "压缩为一半", "Compress the following text to half its length, keeping the core information. Output only the compressed text.\n\nText:", listOf("压缩为一半", "压缩一半", "缩短一半", "减半")),
    )

    // ==================== 格式类 ====================
    private val formatInstructions = listOf(
        Instruction("fmt_paragraph", "格式", "分段排版", "Split the following text into paragraphs by topic. Output only the result, no explanation.\n\nText:", listOf("分段", "分段落", "分成段", "分段排版", "重新分段")),
        Instruction("fmt_3para", "格式", "分成三段", "Split the following text into 3 paragraphs by topic. Output only the result.\n\nText:", listOf("分成三段", "三段", "分三段", "三个段落")),
        Instruction("fmt_5para", "格式", "分成五段", "Split the following text into 5 paragraphs by topic. Output only the result.\n\nText:", listOf("分成五段", "五段", "分五段", "五个段落")),
        Instruction("fmt_punctuate", "格式", "添加标点", "Add proper punctuation to the following text. Output only the result.\n\nText:", listOf("加标点", "添加标点", "标点", "加符号", "添加符号")),
        Instruction("fmt_clean", "格式", "去除语气词", "Remove all filler words (嗯、啊、呃、那个、这个、就是、然后 etc.) from the following text. Output only the clean text.\n\nText:", listOf("去语气词", "去掉语气词", "去除语气词", "去语气", "去掉语气", "干净", "去冗余")),
        Instruction("fmt_merge", "格式", "合并段落", "Merge the following paragraphs into one. Output only the merged text.\n\nText:", listOf("合并段落", "合并", "合为一段", "合并成一段")),
        Instruction("fmt_bullet", "格式", "添加序号", "Add numbering (1. 2. 3. ...) to each paragraph. Output only the result.\n\nText:", listOf("添加序号", "加序号", "编号", "添加编号", "标序号")),
        Instruction("fmt_upper", "格式", "全部大写", "将以下文字全部转为大写，只输出转换后的文字", listOf("大写", "全部大写", "转大写", "大写化")),
        Instruction("fmt_lower", "格式", "全部小写", "将以下文字全部转为小写，只输出转换后的文字", listOf("小写", "全部小写", "转小写", "小写化"))
    )

    // ==================== 内容类 ====================
    private val contentInstructions = listOf(
        Instruction("cnt_summarize", "内容", "概括大意", "总结以下文字的核心内容，只输出总结结果", listOf("总结", "概括", "摘要", "概括大意", "总结一下", "归纳", "核心内容")),
        Instruction("cnt_expand", "内容", "扩写内容", "扩写以下文字，增加细节和描述，只输出扩写结果", listOf("扩写", "展开", "扩充", "扩写内容", "写详细", "详细描述")),
        Instruction("cnt_continue", "内容", "续写内容", "根据以下文字的风格和主题，续写一段内容，只输出续写部分", listOf("续写", "接着写", "继续写", "往下写", "续写内容")),
        Instruction("cnt_rewrite", "内容", "改写内容", "用不同的表达方式改写以下文字，保持原意不变，只输出改写后的文字", listOf("改写", "换一种说法", "重新表达", "改写内容", "换个说法")),
        Instruction("cnt_question", "内容", "改为反问", "将以下文字改为反问句式，只输出修改后的文字", listOf("反问", "改为反问", "反问句", "反问句式")),
        Instruction("cnt_example", "内容", "添加例子", "为以下文字添加具体的例子来说明，只输出添加例子后的文字", listOf("举例", "添加例子", "加例子", "举个例子", "举例说明")),
        Instruction("cnt_quote", "内容", "添加引用", "为以下文字添加相关的引用或名言，只输出添加引用后的文字", listOf("引用", "添加引用", "加引用", "引用名言", "添加名言")),
        Instruction("cnt_simplify", "内容", "简化内容", "简化以下文字，使表达更简洁明了，只输出简化后的文字", listOf("简化", "简化内容", "简洁", "简化表达", "写简单一点"))
    )

    // ==================== 特殊类 ====================
    private val specialInstructions = listOf(
        Instruction("spc_pinyin", "特殊", "敏感词转拼音", "将以下文字中的敏感词替换为拼音首字母，只输出替换后的文字", listOf("敏感词", "拼音", "敏感词转拼音", "拼音首字母", "首字母", "敏感词替换")),
        Instruction("spc_trad2simp", "特殊", "繁体转简体", "将以下文字中的繁体字转为简体字，只输出转换后的文字", listOf("繁体转简体", "繁转简", "繁体简化", "转简体")),
        Instruction("spc_simp2trad", "特殊", "简体转繁体", "将以下文字中的简体字转为繁体字，只输出转换后的文字", listOf("简体转繁体", "简转繁", "繁体化", "转繁体")),
        Instruction("spc_pinyin_full", "特殊", "添加拼音标注", "为以下文字中的每个汉字添加拼音标注，只输出添加拼音后的文字", listOf("拼音标注", "添加拼音", "加拼音", "拼音", "注音")),
        Instruction("spc_vertical", "特殊", "竖排文字", "将以下文字转为竖排格式，只输出竖排后的文字", listOf("竖排", "竖排文字", "纵向排列", "竖着排")),
        Instruction("spc_split", "特殊", "拆词", "将以下文字中的词语逐一分开，用空格隔开，只输出拆分后的文字", listOf("拆词", "分词", "词语分开", "逐词分开", "分词显示"))
    )

    // ==================== 生成类（允许空文本） ====================
    private val generateInstructions = listOf(
        Instruction("gen_idea", "生成", "帮我想", "根据以下主题，帮我想出几个创意点子，只输出点子列表", listOf("帮我想", "帮我想想", "想点子", "出主意", "给建议"), isGeneration = true),
        Instruction("gen_write", "生成", "帮我写", "根据以下要求，帮我写一段文字", listOf("帮我写", "帮我写一段", "写一段", "写一篇"), isGeneration = true),
        Instruction("gen_email", "生成", "写邮件", "根据以下信息，帮我写一封邮件", listOf("写邮件", "写一封邮件", "帮我写邮件", "邮件"), isGeneration = true),
        Instruction("gen_poem", "生成", "写诗", "根据以下主题，写一首诗", listOf("写诗", "作诗", "写一首诗", "诗歌"), isGeneration = true),
        Instruction("gen_code", "生成", "写代码", "根据以下需求，写一段代码", listOf("写代码", "编程", "写程序", "代码"), isGeneration = true),
        Instruction("gen_table", "生成", "做表格", "根据以下信息，制作一个表格", listOf("做表格", "制表", "表格", "创建表格"), isGeneration = true),
        Instruction("gen_list", "生成", "列清单", "根据以下主题，列一个清单", listOf("列清单", "列一个清单", "清单", "列表"), isGeneration = true),
        Instruction("gen_story", "生成", "编故事", "根据以下主题，编一个短故事", listOf("编故事", "写故事", "讲故事", "编一个故事"), isGeneration = true),
        Instruction("gen_title", "生成", "起标题", "根据以下文字，生成几个标题", listOf("起标题", "写标题", "生成标题", "标题"), isGeneration = true),
        Instruction("gen_summary", "生成", "写摘要", "根据以下文字，写一个摘要", listOf("写摘要", "摘要", "写简介", "简介"), isGeneration = true)
    )

    // ==================== 全部指令 ====================
    val allInstructions: List<Instruction> = listOf(
        translateInstructions,
        toneInstructions,
        lengthInstructions,
        formatInstructions,
        contentInstructions,
        specialInstructions,
        generateInstructions
    ).flatten()

    // ==================== 分类列表 ====================
    val categories: List<String> = listOf(
        "翻译", "语气", "长度", "格式", "内容", "特殊", "生成"
    )

    // ==================== 查询方法 ====================

    /** 根据文本查找匹配的指令 */
    fun findByKeywords(text: String): Instruction? {
        val t = text.trim().replace(" ", "")

        // ========== 翻译类：精确匹配动词 + 模糊匹配语言 ==========
        // 匹配"翻译""翻""转为""转成"等动词前缀
        val hasTranslateVerb = t.contains("翻译") || t.startsWith("翻") || t.contains("转为") || t.contains("转成")
        if (hasTranslateVerb) {
            // 模糊匹配目标语言：检查是否包含语言关键词
            return when {
                t.contains("英") -> findById("trans_en")
                t.contains("日") -> findById("trans_ja")  // 日文/日语/日本话 → 都含"日"
                t.contains("韩") -> findById("trans_ko")  // 韩文/韩语/韩国话 → 都含"韩"
                t.contains("法") -> findById("trans_fr")
                t.contains("德") -> findById("trans_de")
                t.contains("西班牙") -> findById("trans_es")
                t.contains("俄") -> findById("trans_ru")
                t.contains("阿拉伯") -> findById("trans_ar")
                t.contains("中") -> findById("trans_zh")
                t.contains("自动") || t.contains("智能") -> findById("trans_auto")
                else -> null
            }
        }

        // ========== 其他类：关键词包含匹配 ==========
        // 语气类
        if (t.contains("正式") || t.contains("书面") || t.contains("官方")) return findById("tone_formal")
        if (t.contains("口语") || t.contains("随意") || t.contains("大白话")) return findById("tone_casual")
        if (t.contains("幽默") || t.contains("搞笑") || t.contains("风趣")) return findById("tone_humorous")
        if (t.contains("严肃") || t.contains("严厉") || t.contains("正经")) return findById("tone_serious")
        if (t.contains("温柔") || t.contains("亲切") || t.contains("温和")) return findById("tone_gentle")
        if (t.contains("权威") || t.contains("专业")) return findById("tone_authoritative")

        // 长度类
        if (t.contains("50字") || t.contains("五十")) return findById("len_50")
        if (t.contains("100字") || t.contains("一百")) return findById("len_100")
        if (t.contains("200字") || t.contains("两百")) return findById("len_200")
        if (t.contains("一半") || t.contains("减半")) return findById("len_half")
        if (t.contains("压缩") || t.contains("精简") || t.contains("缩短")) return findById("len_compress")
        if (t.contains("扩充") || t.contains("扩写") || t.contains("写长")) return findById("len_expand")

        // 格式类
        if (t.contains("三段") || t.contains("3段")) return findById("fmt_3para")
        if (t.contains("五段") || t.contains("5段")) return findById("fmt_5para")
        if (t.contains("标点") || t.contains("符号")) return findById("fmt_punctuate")
        if (t.contains("语气词") || t.contains("去语气")) return findById("fmt_clean")
        if (t.contains("合并")) return findById("fmt_merge")
        if (t.contains("序号") || t.contains("编号")) return findById("fmt_bullet")
        if (t.contains("大写")) return findById("fmt_upper")
        if (t.contains("小写")) return findById("fmt_lower")
        if (t.contains("分段") || t.contains("分段落")) return findById("fmt_paragraph")

        // 内容类
        if (t.contains("总结") || t.contains("概括") || t.contains("摘要")) return findById("cnt_summarize")
        if (t.contains("续写") || t.contains("接着写")) return findById("cnt_continue")
        if (t.contains("改写") || t.contains("换一种说法")) return findById("cnt_rewrite")
        if (t.contains("反问")) return findById("cnt_question")
        if (t.contains("举例") || t.contains("加例子")) return findById("cnt_example")
        if (t.contains("引用") || t.contains("名言")) return findById("cnt_quote")
        if (t.contains("简化") || t.contains("简洁")) return findById("cnt_simplify")

        // 特殊类
        if (t.contains("敏感词") || t.contains("拼音首字母")) return findById("spc_pinyin")
        if (t.contains("繁体转简体") || t.contains("繁转简")) return findById("spc_trad2simp")
        if (t.contains("简体转繁体") || t.contains("简转繁")) return findById("spc_simp2trad")
        if (t.contains("拼音标注") || t.contains("注音")) return findById("spc_pinyin_full")
        if (t.contains("竖排")) return findById("spc_vertical")
        if (t.contains("拆词") || t.contains("分词")) return findById("spc_split")

        // 生成类
        if (t.contains("帮我想") || t.contains("出主意")) return findById("gen_idea")
        if (t.contains("帮我写")) return findById("gen_write")
        if (t.contains("邮件")) return findById("gen_email")
        if (t.contains("写诗") || t.contains("作诗")) return findById("gen_poem")
        if (t.contains("写代码") || t.contains("编程")) return findById("gen_code")
        if (t.contains("表格") || t.contains("制表")) return findById("gen_table")
        if (t.contains("清单") || t.contains("列表")) return findById("gen_list")
        if (t.contains("故事")) return findById("gen_story")
        if (t.contains("标题")) return findById("gen_title")
        if (t.contains("写摘要") || t.contains("简介")) return findById("gen_summary")

        return null
    }

    private fun findById(id: String): Instruction? = allInstructions.find { it.id == id }

    /** 根据分类获取指令 */
    fun getByCategory(category: String): List<Instruction> {
        return allInstructions.filter { it.category == category }
    }

    /** 获取所有翻译类指令 */
    fun getTranslateInstructions(): List<Instruction> = translateInstructions

    /** 获取所有生成类指令 */
    fun getGenerateInstructions(): List<Instruction> = generateInstructions

    /** 判断是否为生成类指令 */
    fun isGenerationInstruction(text: String): Boolean {
        val normalized = text.trim().replace(" ", "")
        return generateInstructions.any { inst ->
            inst.keywords.any { keyword -> normalized.contains(keyword.replace(" ", "")) }
        }
    }

    /** 根据指令构建 prompt（与魔法书 buildPolishPrompt 格式一致） */
    fun buildPrompt(instruction: Instruction, currentText: String): String {
        return "原文：$currentText\n\n指令：${instruction.instruction}\n\n请根据指令处理原文，只输出处理后的文本，不要输出任何解释。"
    }
}
