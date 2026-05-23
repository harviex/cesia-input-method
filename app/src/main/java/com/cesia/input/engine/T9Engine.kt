package com.cesia.input.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * T9 数字拼音引擎（独立版）
 *
 * T9 键盘布局：
 * ┌─────┬─────┬─────┬─────┐
 * │  1  │ 2   │ 3   │ 4   │
 * │     │ abc │ def │ ghi │
 * ├─────┼─────┼─────┼─────┤
 * │ 5   │ 6   │ 7   │ 8   │
 * │ jkl │ mno │pqrs │ tuv │
 * ├─────┼─────┼─────┼─────┤
 * │ 9   │  0  │     │  ←  │
 * │wxyz │     │space│ del │
 * └─────┴─────┴─────┴─────┘
 *
 * 核心原理：
 * 1. 用户按数字键序列（如 64）
 * 2. 引擎查找反向索引：64 → ["ni", "mg", "mh", "mi", "ng", "nh", "ni", "og", "oh", "oi"...]
 * 3. 过滤出合法拼音（在字典中存在的）：64 → ["ni"]
 * 4. 用合法拼音查字典得候选词：ni → ["你", "尼", "泥", "拟", "逆"...]
 *
 * 反向索引在初始化时构建：遍历所有拼音，将拼音转为数字序列
 * 如 "ni" → "64", "hao" → "426", "zhong" → "94664"
 */
class T9Engine(private val context: Context) {

    companion object {
        // T9 标准映射：数字 → 字母
        val DIGIT_TO_CHARS = mapOf(
            '2' to "abc",
            '3' to "def",
            '4' to "ghi",
            '5' to "jkl",
            '6' to "mno",
            '7' to "pqrs",
            '8' to "tuv",
            '9' to "wxyz"
        )

        // 字母 → 数字的反向映射（代码中动态生成）
        val CHAR_TO_DIGIT: Map<Char, Char> = buildMap {
            for ((digit, chars) in DIGIT_TO_CHARS) {
                for (c in chars) put(c, digit)
            }
        }
    }

    // 单字字典：拼音 → 汉字字符串（如 "ni" → "你尼泥拟逆..."）
    private val charDict = mutableMapOf<String, String>()
    // 词组字典：拼音 → 词组（如 "nihao" → "你好"）
    private val phraseDict = mutableMapOf<String, String>()
    // 反向索引：数字序列 → 合法拼音列表（如 "64" → ["ni", "ng"]）
    private var reverseIndex: Map<String, List<String>> = emptyMap()

    // 当前输入状态
    private var currentDigits = StringBuilder()
    private var candidates = listOf<String>()
    private var candidatePages = listOf<List<String>>()
    private var currentPage = 0

    // 是否已就绪
    var isReady = false
        private set

    init {
        // 后台加载字典 + 构建索引
        Thread {
            try {
                loadDict()
                buildReverseIndex()
                isReady = true
                Log.d("T9Engine", "T9 引擎就绪，索引条目: ${reverseIndex.size}")
            } catch (e: Exception) {
                Log.e("T9Engine", "T9 引擎初始化失败", e)
            }
        }.start()
    }

    // ═══════════════════════ 字典加载 ═══════════════════════

    private fun loadDict() {
        try {
            context.assets.open("pinyin_dict.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                for (key in json.keys()) {
                    charDict[key] = json.getString(key)
                }
            }
            Log.d("T9Engine", "单字字典: ${charDict.size} 条")
        } catch (e: Exception) {
            Log.e("T9Engine", "加载单字字典失败", e)
        }
        try {
            context.assets.open("pinyin_phrases.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                for (key in json.keys()) {
                    phraseDict[key] = json.getString(key)
                }
            }
            Log.d("T9Engine", "词组字典: ${phraseDict.size} 条")
        } catch (e: Exception) {
            Log.e("T9Engine", "加载词组字典失败", e)
        }
    }

    // ═══════════════════════ 反向索引构建 ═══════════════════════

    private fun buildReverseIndex() {
        val index = mutableMapOf<String, MutableList<String>>()

        // 单字拼音 → 数字序列
        for (pinyin in charDict.keys) {
            val digits = pinyinToDigits(pinyin)
            if (digits.isNotEmpty()) {
                index.getOrPut(digits) { mutableListOf() }.add(pinyin)
            }
        }

        // 词组拼音 → 数字序列（避免重复添加已在单字中的）
        for (pinyin in phraseDict.keys) {
            val digits = pinyinToDigits(pinyin)
            if (digits.isNotEmpty()) {
                val list = index.getOrPut(digits) { mutableListOf() }
                if (pinyin !in list) list.add(pinyin)
            }
        }

        reverseIndex = index
        Log.d("T9Engine", "反向索引构建完成")
    }

    private fun pinyinToDigits(pinyin: String): String {
        val sb = StringBuilder()
        for (c in pinyin) {
            val digit = CHAR_TO_DIGIT[c] ?: return "" // 非 a-z 拼音跳过
            sb.append(digit)
        }
        return sb.toString()
    }

    // ═══════════════════════ 公共输入接口 ═══════════════════════

    /** 输入一个数字键（2-9），返回当前数字序列 */
    fun inputDigit(digit: Char): String {
        if (digit in '2'..'9') {
            currentDigits.append(digit)
            updateCandidates()
        }
        return currentDigits.toString()
    }

    /** 退格一个数字 */
    fun backspace(): String {
        if (currentDigits.isNotEmpty()) {
            currentDigits.deleteCharAt(currentDigits.length - 1)
            updateCandidates()
        }
        return currentDigits.toString()
    }

    /** 清空 */
    fun clear() {
        currentDigits.clear()
        candidates = emptyList()
        candidatePages = emptyList()
        currentPage = 0
    }

    fun getCurrentDigits(): String = currentDigits.toString()
    fun isComposing(): Boolean = currentDigits.isNotEmpty()
    fun hasCandidates(): Boolean = candidates.isNotEmpty()
    fun getCandidates(): List<String> = if (candidatePages.isEmpty()) emptyList() else candidatePages[currentPage]
    fun getCurrentPage(): Int = currentPage
    fun getPageCount(): Int = candidatePages.size

    fun nextPage(): List<String> = if (currentPage < candidatePages.size - 1) { currentPage++; getCandidates() } else getCandidates()
    fun prevPage(): List<String> = if (currentPage > 0) { currentPage--; getCandidates() } else getCandidates()

    /** 选择候选词，返回选中的词并清空 */
    fun selectCandidate(index: Int): String {
        if (index < 0 || index >= candidates.size) return ""
        val selected = candidates[index]
        clear()
        return selected
    }

    // ═══════════════════════ 候选词查询 ═══════════════════════

    private fun updateCandidates() {
        val digits = currentDigits.toString()
        if (digits.isEmpty()) {
            candidates = emptyList()
            candidatePages = emptyList()
            currentPage = 0
            return
        }

        val allCandidates = mutableListOf<String>()

        // 1. 精确匹配：数字序列 → 合法拼音 → 候选词
        val exactPinyins = reverseIndex[digits] ?: emptyList()
        for (py in exactPinyins) {
            addCandidatesFromPinyin(py, allCandidates)
        }

        // 2. 前缀匹配：当前数字是某个更长拼音的前缀
        for ((key, pinyins) in reverseIndex) {
            if (key.startsWith(digits) && key != digits && key.length <= digits.length + 2) {
                for (py in pinyins) {
                    // 前缀匹配只加单字候选，不加词组
                    val chars = charDict[py]
                    if (chars != null) {
                        allCandidates.add(chars[0].toString()) // 只加第一个字
                    }
                }
            }
        }

        candidates = allCandidates.distinct().take(40)
        candidatePages = if (candidates.isEmpty()) emptyList() else candidates.chunked(5)
        currentPage = 0
    }

    private fun addCandidatesFromPinyin(pinyin: String, result: MutableList<String>) {
        // 单字
        val chars = charDict[pinyin]
        if (chars != null) {
            chars.forEach { result.add(it.toString()) }
        }
        // 词组
        val phrase = phraseDict[pinyin]
        if (phrase != null) {
            phrase.split(",").filter { it.isNotEmpty() }.forEach { result.add(it) }
        }
    }

    // ═══════════════════════ 获取当前拼音提示 ═══════════════════════

    /**
     * 获取当前数字序列对应的所有合法拼音（用于显示拼音提示）
     */
    fun getCurrentPinyins(): List<String> {
        val digits = currentDigits.toString()
        if (digits.isEmpty()) return emptyList()
        return reverseIndex[digits] ?: emptyList()
    }
}
