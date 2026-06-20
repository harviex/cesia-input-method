package com.cesia.input.instruction

/**
 * 指令表 - 统一管理所有魔法指令和语音指令
 *
 * 60条指令，每条10-20字，精准描述需求
 * 分类：翻译、语气、长度、格式、内容、特殊、生成
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

    // ==================== 翻译类（10条）====================
    private val translateInstructions = listOf(
        Instruction("trans_en", "翻译", "翻译为英文", "将以下文字翻译为流畅自然的英文，只输出翻译后的英文，不要输出任何解释", listOf("翻译英文", "翻译成英文", "翻译为英文", "英文翻译", "翻英文", "翻成英文")),
        Instruction("trans_ja", "翻译", "翻译为日文", "将以下文字翻译为流畅自然的日文，只输出翻译后的日文，不要输出任何解释", listOf("翻译日文", "翻译成日文", "翻译为日文", "日文翻译", "翻日文", "翻成日文")),
        Instruction("trans_ko", "翻译", "翻译为韩文", "将以下文字翻译为流畅自然的韩文，只输出翻译后的韩文，不要输出任何解释", listOf("翻译韩文", "翻译成韩文", "翻译为韩文", "韩文翻译", "翻韩文", "翻成韩文")),
        Instruction("trans_fr", "翻译", "翻译为法文", "将以下文字翻译为流畅自然的法文，只输出翻译后的法文，不要输出任何解释", listOf("翻译法文", "翻译成法文", "翻译为法文", "法文翻译", "翻法文", "翻成法文")),
        Instruction("trans_de", "翻译", "翻译为德文", "将以下文字翻译为流畅自然的德文，只输出翻译后的德文，不要输出任何解释", listOf("翻译德文", "翻译成德文", "翻译为德文", "德文翻译", "翻德文", "翻成德文")),
        Instruction("trans_es", "翻译", "翻译为西班牙文", "将以下文字翻译为流畅自然的西班牙文，只输出翻译后的西班牙文，不要输出任何解释", listOf("翻译西班牙文", "翻译成西班牙文", "翻译为西班牙文", "西班牙文翻译")),
        Instruction("trans_ru", "翻译", "翻译为俄文", "将以下文字翻译为流畅自然的俄文，只输出翻译后的俄文，不要输出任何解释", listOf("翻译俄文", "翻译成俄文", "翻译为俄文", "俄文翻译")),
        Instruction("trans_ar", "翻译", "翻译为阿拉伯文", "将以下文字翻译为流畅自然的阿拉伯文，只输出翻译后的阿拉伯文，不要输出任何解释", listOf("翻译阿拉伯文", "翻译成阿拉伯文", "翻译为阿拉伯文", "阿拉伯文翻译")),
        Instruction("trans_zh", "翻译", "翻译为中文", "将以下文字翻译为流畅自然的中文，只输出翻译后的中文，不要输出任何解释", listOf("翻译中文", "翻译成中文", "翻译为中文", "中文翻译")),
        Instruction("trans_auto", "翻译", "自动检测语言并翻译", "检测以下文字的语言，如果是中文则翻译为英文，如果是其他语言则翻译为中文，只输出翻译结果，不要输出任何解释", listOf("自动翻译", "智能翻译", "检测翻译"))
    )

    // ==================== 语气类（6条）====================
    private val toneInstructions = listOf(
        Instruction("tone_formal", "语气", "改为正式书面语气", "将以下文字改写为正式书面语气，用词严谨专业，适合公文报告等正式场合，只输出改写后的文字，不要输出任何解释", listOf("正式", "书面", "官方", "正式语气", "改为正式", "正式一点", "正式化")),
        Instruction("tone_casual", "语气", "改为日常口语语气", "将以下文字改写为日常口语语气，用词轻松自然，像朋友聊天一样，只输出改写后的文字，不要输出任何解释", listOf("口语", "随意", "大白话", "口语化", "改为口语", "口语一点", "通俗")),
        Instruction("tone_humorous", "语气", "改为幽默风趣语气", "将以下文字改写为幽默风趣语气，加入适当的趣味表达，让人读起来会心一笑，只输出改写后的文字，不要输出任何解释", listOf("幽默", "搞笑", "有趣", "幽默化", "改为幽默", "幽默一点", "风趣")),
        Instruction("tone_serious", "语气", "改为严肃郑重语气", "将以下文字改写为严肃郑重语气，用词严厉有力，适合批评警告等场合，只输出改写后的文字，不要输出任何解释", listOf("严肃", "严厉", "严肃化", "改为严肃", "严肃一点", "正经")),
        Instruction("tone_gentle", "语气", "改为温柔亲切语气", "将以下文字改写为温柔亲切语气，用词柔和温暖，让人感觉被关怀，只输出改写后的文字，不要输出任何解释", listOf("温柔", "亲切", "柔和", "温柔化", "改为温柔", "温柔一点", "温和")),
        Instruction("tone_authoritative", "语气", "改为权威专业语气", "将以下文字改写为权威专业语气，体现专业性和可信度，适合学术或专业场景，只输出改写后的文字，不要输出任何解释", listOf("权威", "专业", "权威化", "改为权威", "权威一点", "专业化"))
    )

    // ==================== 长度类（4条）====================
    private val lengthInstructions = listOf(
        Instruction("len_expand", "长度", "扩充内容增加细节", "将以下文字进行扩充，增加更多细节描述和具体事例，使内容更加丰富饱满，只输出扩充后的文字，不要输出任何解释", listOf("扩充", "扩写", "展开", "增加内容", "写长一点", "扩充内容", "丰富内容")),
        Instruction("len_compress", "长度", "压缩内容精简表达", "将以下文字进行压缩精简，去掉冗余表达，只保留核心信息，使内容更加精炼，只输出压缩后的文字，不要输出任何解释", listOf("压缩", "精简", "缩短", "写短一点", "压缩内容", "精简内容")),
        Instruction("len_expand_double", "长度", "扩充为原来的两倍", "将以下文字扩充为原来长度的两倍，增加丰富的细节描述和具体事例，使内容更加充实，只输出扩充后的文字，不要输出任何解释", listOf("扩充为两倍", "扩充两倍", "写长一倍", "翻一倍")),
        Instruction("len_compress_tight", "长度", "压缩为最精简短句", "将以下文字压缩为最精简的短句，只保留最核心的意思，去掉所有修饰和冗余，只输出压缩后的文字，不要输出任何解释", listOf("最精简", "压缩到最短", "极简", "一句话概括"))
    )

    // ==================== 格式类（7条）====================
    private val formatInstructions = listOf(
        Instruction("fmt_paragraph", "格式", "按主题分段排版", "将以下文字按主题分成多个段落，每段一个主题，段落之间空一行，只输出分段后的文字，不要输出任何解释", listOf("分段", "分段落", "分成段", "分段排版", "重新分段")),
        Instruction("fmt_punctuate", "格式", "添加标点符号", "为以下文字添加正确的标点符号，包括逗号句号问号感叹号等，使语句通顺易读，只输出添加标点后的文字，不要输出任何解释", listOf("加标点", "添加标点", "标点", "加符号", "添加符号")),
        Instruction("fmt_clean", "格式", "去除语气词和冗余", "去除以下文字中的所有语气词和冗余表达，包括嗯啊呃那个这个就是然后等等，使文字更加干净利落，只输出清理后的文字，不要输出任何解释", listOf("去语气词", "去掉语气词", "去除语气词", "去语气", "去掉语气", "干净", "去冗余")),
        Instruction("fmt_merge", "格式", "合并为一段", "将以下所有段落合并为一段文字，去掉段落之间的空行，使内容连贯流畅，只输出合并后的文字，不要输出任何解释", listOf("合并段落", "合并", "合为一段", "合并成一段")),
        Instruction("fmt_bullet", "格式", "添加数字序号", "为以下文字的每一段添加数字序号（1. 2. 3. ...），使内容条理清晰，只输出添加序号后的文字，不要输出任何解释", listOf("添加序号", "加序号", "编号", "添加编号", "标序号")),
        Instruction("fmt_upper", "格式", "全部转为大写字母", "将以下文字中的英文字母全部转为大写，中文保持不变，只输出转换后的文字，不要输出任何解释", listOf("大写", "全部大写", "转大写", "大写化")),
        Instruction("fmt_lower", "格式", "全部转为小写字母", "将以下文字中的英文字母全部转为小写，中文保持不变，只输出转换后的文字，不要输出任何解释", listOf("小写", "全部小写", "转小写", "小写化"))
    )

    // ==================== 内容类（8条）====================
    private val contentInstructions = listOf(
        Instruction("cnt_summarize", "内容", "概括核心要点", "概括以下文字的核心要点，提炼出最重要的信息，用简洁的语言表达，只输出概括结果，不要输出任何解释", listOf("总结", "概括", "摘要", "概括大意", "总结一下", "归纳", "核心内容")),
        Instruction("cnt_expand", "内容", "扩写增加细节", "对以下文字进行扩写，增加更多细节描述和具体事例，使内容更加丰富生动，只输出扩写后的文字，不要输出任何解释", listOf("扩写", "展开", "扩充", "扩写内容", "写详细", "详细描述")),
        Instruction("cnt_continue", "内容", "续写后续内容", "根据以下文字的风格和主题，续写一段后续内容，保持文风一致，只输出续写部分，不要输出任何解释", listOf("续写", "接着写", "继续写", "往下写", "续写内容")),
        Instruction("cnt_rewrite", "内容", "改写换一种说法", "用不同的表达方式改写以下文字，保持原意不变但用词和句式完全不同，只输出改写后的文字，不要输出任何解释", listOf("改写", "换一种说法", "重新表达", "改写内容", "换个说法")),
        Instruction("cnt_question", "内容", "改为反问句式", "将以下文字改为反问句式，增强表达力度和感染力，只输出修改后的文字，不要输出任何解释", listOf("反问", "改为反问", "反问句", "反问句式")),
        Instruction("cnt_example", "内容", "添加具体例子", "为以下文字中的观点添加具体的例子来说明，使内容更有说服力，只输出添加例子后的文字，不要输出任何解释", listOf("举例", "添加例子", "加例子", "举个例子", "举例说明")),
        Instruction("cnt_quote", "内容", "添加引用或名言", "为以下文字添加相关的引用或名言警句，增强内容的深度和说服力，只输出添加引用后的文字，不要输出任何解释", listOf("引用", "添加引用", "加引用", "引用名言", "添加名言")),
        Instruction("cnt_simplify", "内容", "简化为通俗易懂", "简化以下文字，用更通俗易懂的语言表达，让所有人都能轻松理解，只输出简化后的文字，不要输出任何解释", listOf("简化", "简化内容", "简洁", "简化表达", "写简单一点"))
    )

    // ==================== 特殊类（6条）====================
    private val specialInstructions = listOf(
        Instruction("spc_pinyin", "特殊", "敏感词替换为拼音", "将以下文字中的敏感词替换为拼音首字母缩写，其他内容保持不变，只输出替换后的文字，不要输出任何解释", listOf("敏感词", "拼音", "敏感词转拼音", "拼音首字母", "首字母", "敏感词替换")),
        Instruction("spc_trad2simp", "特殊", "繁体字转为简体字", "将以下文字中的繁体字全部转为简体字，只输出转换后的文字，不要输出任何解释", listOf("繁体转简体", "繁转简", "繁体简化", "转简体")),
        Instruction("spc_simp2trad", "特殊", "简体字转为繁体字", "将以下文字中的简体字全部转为繁体字，只输出转换后的文字，不要输出任何解释", listOf("简体转繁体", "简转繁", "繁体化", "转繁体")),
        Instruction("spc_pinyin_full", "特殊", "添加拼音标注", "为以下文字中的每个汉字添加拼音标注，格式为汉字(拼音)，只输出添加拼音后的文字，不要输出任何解释", listOf("拼音标注", "添加拼音", "加拼音", "拼音", "注音")),
        Instruction("spc_vertical", "特殊", "转为竖排文字格式", "将以下文字转为竖排格式，每列一个字，从右到左排列，只输出竖排后的文字，不要输出任何解释", listOf("竖排", "竖排文字", "纵向排列", "竖着排")),
        Instruction("spc_split", "特殊", "逐词拆分用空格隔开", "将以下文字中的词语逐一分开，用空格隔开每个词，只输出拆分后的文字，不要输出任何解释", listOf("拆词", "分词", "词语分开", "逐词分开", "分词显示"))
    )

    // ==================== 生成类（10条，允许空文本）====================
    private val generateInstructions = listOf(
        Instruction("gen_idea", "生成", "帮我想几个创意点子", "根据以下主题，帮我想出几个创意点子，每个点子用一句话概括，以列表形式输出", listOf("帮我想", "帮我想想", "想点子", "出主意", "给建议"), isGeneration = true),
        Instruction("gen_write", "生成", "帮我写一段文字", "根据以下要求，帮我写一段文字，内容通顺流畅，逻辑清晰", listOf("帮我写", "帮我写一段", "写一段", "写一篇"), isGeneration = true),
        Instruction("gen_email", "生成", "帮我写一封邮件", "根据以下信息，帮我写一封格式规范、语气得体的邮件", listOf("写邮件", "写一封邮件", "帮我写邮件", "邮件"), isGeneration = true),
        Instruction("gen_poem", "生成", "写一首诗", "根据以下主题，写一首有意境的诗，可以是古体诗或现代诗", listOf("写诗", "作诗", "写一首诗", "诗歌"), isGeneration = true),
        Instruction("gen_code", "生成", "写一段代码", "根据以下需求，写一段代码，代码简洁高效，有必要的注释", listOf("写代码", "编程", "写程序", "代码"), isGeneration = true),
        Instruction("gen_table", "生成", "制作一个表格", "根据以下信息，制作一个格式清晰的表格，用markdown格式输出", listOf("做表格", "制表", "表格", "创建表格"), isGeneration = true),
        Instruction("gen_list", "生成", "列一个清单", "根据以下主题，列一个详细的清单，每条用序号标注", listOf("列清单", "列一个清单", "清单", "列表"), isGeneration = true),
        Instruction("gen_story", "生成", "编一个短故事", "根据以下主题，编一个有趣的短故事，有开头发展和结尾", listOf("编故事", "写故事", "讲故事", "编一个故事"), isGeneration = true),
        Instruction("gen_title", "生成", "生成几个标题", "根据以下文字内容，生成几个吸引人的标题，每个标题不超过15个字", listOf("起标题", "写标题", "生成标题", "标题"), isGeneration = true),
        Instruction("gen_summary", "生成", "写一个摘要", "根据以下文字，写一个简洁的摘要，不超过100字，涵盖核心内容", listOf("写摘要", "摘要", "写简介", "简介"), isGeneration = true)
    )

    // ==================== 润色类（9条）====================
    private val polishInstructions = listOf(
        Instruction("polish_general", "润色", "润色优化表达", "对以下文字进行润色优化，使表达更加流畅自然，用词更加精准，只输出润色后的文字，不要输出任何解释", listOf("润色", "优化", "润色一下", "优化表达", "改好一点")),
        Instruction("polish_grammar", "润色", "修正语法错误", "修正以下文字中的语法错误和不通顺的句子，保持原意不变，只输出修正后的文字，不要输出任何解释", listOf("语法", "修正语法", "语法错误", "不通顺", "改错")),
        Instruction("polish_logic", "润色", "优化逻辑结构", "优化以下文字的逻辑结构，使论述更加清晰有条理，段落之间过渡自然，只输出优化后的文字，不要输出任何解释", listOf("逻辑", "优化逻辑", "结构", "条理", "更有条理")),
        Instruction("polish_wordchoice", "润色", "优化用词表达", "优化以下文字的用词，替换重复或平淡的词语，使表达更加生动精准，只输出优化后的文字，不要输出任何解释", listOf("用词", "优化用词", "换词", "词语", "表达更生动")),
        Instruction("polish_concise", "润色", "使表达更加简洁", "使以下文字的表达更加简洁有力，去掉冗余的词语和句子，只保留必要的信息，只输出修改后的文字，不要输出任何解释", listOf("简洁", "更简洁", "精简表达", "言简意赅")),
        Instruction("polish_vivid", "润色", "使表达更加生动", "使以下文字的表达更加生动形象，加入适当的修辞手法，让读者更有画面感，只输出修改后的文字，不要输出任何解释", listOf("生动", "更生动", "形象", "有画面感", "栩栩如生")),
        Instruction("polish_emotion", "润色", "增强情感表达", "增强以下文字的情感表达，使文字更有感染力和共鸣感，只输出修改后的文字，不要输出任何解释", listOf("情感", "更有感情", "感染力", "共鸣", "打动人心")),
        Instruction("polish_professional", "润色", "润色为专业风格", "将以下文字润色为专业风格，用词严谨规范，适合商务或学术场景，只输出润色后的文字，不要输出任何解释", listOf("专业", "商务", "学术", "专业风格", "规范化")),
        Instruction("polish_readable", "润色", "提高可读性", "提高以下文字的可读性，调整句子长度和段落结构，使读者更容易理解和阅读，只输出修改后的文字，不要输出任何解释", listOf("可读性", "易读", "好读", "通俗易懂", "阅读体验"))
    )

    // ==================== 全部指令（魔法书50条，不含生成类） ====================
    val allInstructions: List<Instruction> = listOf(
        translateInstructions,
        toneInstructions,
        lengthInstructions,
        formatInstructions,
        contentInstructions,
        specialInstructions,
        polishInstructions
    ).flatten()

    // ==================== 仅生成类（星星按钮专用） ====================
    val starInstructions: List<Instruction> = generateInstructions

    // ==================== 分类列表 ====================
    val categories: List<String> = listOf(
        "翻译", "语气", "长度", "格式", "内容", "特殊", "润色"
    )

    // ==================== 查询方法 ====================

    /** 根据文本查找匹配的指令 */
    fun findByKeywords(text: String): Instruction? {
        val t = text.trim().replace(" ", "")

        // ========== 翻译类 ==========
        val hasTranslateVerb = t.contains("翻译") || t.startsWith("翻") || t.contains("转为") || t.contains("转成")
        if (hasTranslateVerb) {
            return when {
                t.contains("英") -> findById("trans_en")
                t.contains("日") -> findById("trans_ja")
                t.contains("韩") -> findById("trans_ko")
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

        // ========== 语气类 ==========
        if (t.contains("正式") || t.contains("书面") || t.contains("官方")) return findById("tone_formal")
        if (t.contains("口语") || t.contains("随意") || t.contains("大白话")) return findById("tone_casual")
        if (t.contains("幽默") || t.contains("搞笑") || t.contains("风趣")) return findById("tone_humorous")
        if (t.contains("严肃") || t.contains("严厉") || t.contains("正经")) return findById("tone_serious")
        if (t.contains("温柔") || t.contains("亲切") || t.contains("温和")) return findById("tone_gentle")
        if (t.contains("权威") || t.contains("专业") && !t.contains("专业化")) return findById("tone_authoritative")

        // ========== 长度类 ==========
        if (t.contains("两倍") || t.contains("翻一倍")) return findById("len_expand_double")
        if (t.contains("最精简") || t.contains("压缩到最短") || t.contains("一句话概括")) return findById("len_compress_tight")
        if (t.contains("压缩") || t.contains("精简") || t.contains("缩短")) return findById("len_compress")
        if (t.contains("扩充") || t.contains("扩写") || t.contains("写长") || t.contains("丰富内容")) return findById("len_expand")

        // ========== 格式类 ==========
        if (t.contains("标点") || t.contains("符号")) return findById("fmt_punctuate")
        if (t.contains("语气词") || t.contains("去语气") || t.contains("去冗余")) return findById("fmt_clean")
        if (t.contains("合并")) return findById("fmt_merge")
        if (t.contains("序号") || t.contains("编号")) return findById("fmt_bullet")
        if (t.contains("大写")) return findById("fmt_upper")
        if (t.contains("小写")) return findById("fmt_lower")
        if (t.contains("分段") || t.contains("分段落")) return findById("fmt_paragraph")

        // ========== 内容类 ==========
        if (t.contains("总结") || t.contains("概括") || t.contains("摘要") && !t.contains("写摘要")) return findById("cnt_summarize")
        if (t.contains("续写") || t.contains("接着写")) return findById("cnt_continue")
        if (t.contains("改写") || t.contains("换一种说法")) return findById("cnt_rewrite")
        if (t.contains("反问")) return findById("cnt_question")
        if (t.contains("举例") || t.contains("加例子")) return findById("cnt_example")
        if (t.contains("引用") || t.contains("名言")) return findById("cnt_quote")
        if (t.contains("简化") || t.contains("简洁") && !t.contains("简洁有力")) return findById("cnt_simplify")

        // ========== 特殊类 ==========
        if (t.contains("敏感词") || t.contains("拼音首字母")) return findById("spc_pinyin")
        if (t.contains("繁体转简体") || t.contains("繁转简")) return findById("spc_trad2simp")
        if (t.contains("简体转繁体") || t.contains("简转繁")) return findById("spc_simp2trad")
        if (t.contains("拼音标注") || t.contains("注音")) return findById("spc_pinyin_full")
        if (t.contains("竖排")) return findById("spc_vertical")
        if (t.contains("拆词") || t.contains("分词")) return findById("spc_split")

        // ========== 润色类 ==========
        if (t.contains("语法") || t.contains("改错") || t.contains("不通顺")) return findById("polish_grammar")
        if (t.contains("逻辑") || t.contains("条理") || t.contains("结构")) return findById("polish_logic")
        if (t.contains("用词") || t.contains("换词") || t.contains("词语")) return findById("polish_wordchoice")
        if (t.contains("简洁") || t.contains("言简意赅")) return findById("polish_concise")
        if (t.contains("生动") || t.contains("形象") || t.contains("画面感")) return findById("polish_vivid")
        if (t.contains("情感") || t.contains("感情") || t.contains("感染力") || t.contains("共鸣")) return findById("polish_emotion")
        if (t.contains("专业") && (t.contains("风格") || t.contains("商务") || t.contains("学术") || t.contains("规范"))) return findById("polish_professional")
        if (t.contains("可读性") || t.contains("易读") || t.contains("阅读体验")) return findById("polish_readable")
        if (t.contains("润色") || t.contains("优化") || t.contains("改好")) return findById("polish_general")

        // ========== 生成类 ==========
        if (t.contains("帮我想") || t.contains("出主意")) return findById("gen_idea")
        if (t.contains("帮我写")) return findById("gen_write")
        if (t.contains("邮件")) return findById("gen_email")
        if (t.contains("写诗") || t.contains("作诗")) return findById("gen_poem")
        if (t.contains("写代码") || t.contains("编程")) return findById("gen_code")
        if (t.contains("表格") || t.contains("制表")) return findById("gen_table")
        if (t.contains("清单") || t.contains("列表") && !t.contains("列表形式")) return findById("gen_list")
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

    /** 根据指令构建 prompt */
    fun buildPrompt(instruction: Instruction, currentText: String): String {
        return "${instruction.instruction}\n\n原文：$currentText"
    }
}
