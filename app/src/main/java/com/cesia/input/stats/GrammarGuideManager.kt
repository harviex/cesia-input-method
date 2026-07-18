package com.cesia.input.stats

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户语法大纲管理器
 *
 * 基于历史润色记录，用 AI 生成/更新用户个人语法纲要。
 * 内容包括：常用句式、词汇偏好、语气风格、标点习惯等。
 * 每次新增历史记录后触发增量更新。
 */
class GrammarGuideManager(private val context: Context) {

    companion object {
        private const val TAG = "GrammarGuide"
        private const val PREFS_NAME = "cesia_grammar_guide"
        private const val KEY_CONTENT = "content"
        private const val KEY_VERSION = "version"
        private const val KEY_LAST_RECORD_COUNT = "last_record_count"
        private const val MAX_RECORDS_FOR_GUIDE = 20  // 取最近20条记录生成大纲
        private const val MAX_GUIDE_LENGTH = 500     // 大纲最大字符数
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前语法大纲内容 */
    var content: String
        get() = prefs.getString(KEY_CONTENT, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_CONTENT, value).apply()

    /** 大纲版本号（递增） */
    var version: Int
        get() = prefs.getInt(KEY_VERSION, 0)
        private set(value) = prefs.edit().putInt(KEY_VERSION, value).apply()

    /** 上次生成大纲时用的记录数 */
    var lastRecordCount: Int
        get() = prefs.getInt(KEY_LAST_RECORD_COUNT, 0)
        private set(value) = prefs.edit().putInt(KEY_LAST_RECORD_COUNT, value).apply()

    /**
     * 检查是否需要更新大纲
     * @param currentRecordCount 当前历史记录总数
     * @return true 表示需要更新
     */
    fun needsUpdate(currentRecordCount: Int): Boolean {
        val current = content
        // 从未生成过
        if (current.isEmpty()) return true
        // 新增记录超过5条时触发更新
        val diff = currentRecordCount - lastRecordCount
        return diff >= 5
    }

    /**
     * 用历史记录生成/更新语法大纲（同步，在后台线程调用）
     * @param records 最近的历史记录列表（最新的在前）
     * @param aiPolish 润色函数: (text, instruction) -> String? （suspend）
     * @return 生成的大纲文本，失败返回 null
     */
    suspend fun generateGuide(
        records: List<PolishRecord>,
        aiPolish: suspend (String, String) -> String?
    ): String? {
        if (records.isEmpty()) return null

        // 取最近 N 条记录
        val recentRecords = records.take(MAX_RECORDS_FOR_GUIDE)

        // 构建输入文本：每条记录展示 语音原文↔最终发出文字（供 AI 借鉴用户表达习惯）
        val sb = StringBuilder()
        for ((i, record) in recentRecords.withIndex()) {
            if (record.voiceRawText.isNotEmpty() && record.voiceRawText != record.outputText) {
                sb.appendLine("【${i + 1}】语音输入：${record.voiceRawText}")
                sb.appendLine("    最终发出：${record.outputText}")
            } else {
                sb.appendLine("【${i + 1}】原文：${record.inputText}")
                sb.appendLine("    润色：${record.outputText}")
            }
            sb.appendLine()
        }

        val inputText = sb.toString().trim()
        if (inputText.isEmpty()) return null

        val prompt = buildGuidePrompt(inputText)

        return try {
            val result = aiPolish(prompt, "语法大纲生成")
            if (!result.isNullOrEmpty() && result.length <= MAX_GUIDE_LENGTH * 2) {
                // 截断到合理长度
                val truncated = if (result.length > MAX_GUIDE_LENGTH) {
                    result.substring(0, MAX_GUIDE_LENGTH) + "..."
                } else {
                    result
                }
                Log.i(TAG, "generateGuide: 成功生成大纲，长度=${truncated.length}")
                truncated
            } else {
                Log.w(TAG, "generateGuide: AI返回结果为空或过长 length=${result?.length}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateGuide: AI生成失败", e)
            null
        }
    }

    /**
     * 保存大纲
     */
    fun saveGuide(guideContent: String) {
        content = guideContent
        version++
        Log.i(TAG, "saveGuide: 大纲已保存，版本=$version，长度=${guideContent.length}")
    }

    /**
     * 更新上次记录数
     */
    fun updateRecordCount(count: Int) {
        lastRecordCount = count
    }

    /**
     * 清空大纲
     */
    fun clear() {
        content = ""
        version = 0
        lastRecordCount = 0
        prefs.edit()
            .remove(KEY_CONTENT)
            .remove(KEY_VERSION)
            .remove(KEY_LAST_RECORD_COUNT)
            .apply()
    }

    /**
     * 本地大纲：不依赖 AI，直接根据历史记录生成可读的中文纲要。
     * 用于无 AI / 无 Key 场景，保证大纲始终有内容。
     */
    fun buildLocalOutline(records: List<PolishRecord>): String {
        if (records.isEmpty()) return ""
        val recent = records.take(MAX_RECORDS_FOR_GUIDE)
        val sb = StringBuilder()
        sb.appendLine("📊 基于 ${records.size} 条历史记录（最近 ${recent.size} 条）自动生成：")
        sb.appendLine()

        // 1. 平均长度
        val avgIn = recent.map { it.inputText.length }.average().toInt()
        val avgOut = recent.map { it.outputText.length }.average().toInt()
        sb.appendLine("• 文本长度：原文平均 ${avgIn} 字，润色后平均 ${avgOut} 字")
        // 2. 标点习惯（统计常见标点出现频率）
        val punct = mapOf("，" to 0, "。" to 0, "、" to 0, "！" to 0, "？" to 0, "；" to 0)
        val merged = recent.joinToString("") { it.outputText }
        val punctCount = punct.mapValues { (k, _) -> merged.count { c -> c.toString() == k } }
        val topPunct = punctCount.filter { it.value > 0 }.maxByOrNull { it.value }
        if (topPunct != null) {
            sb.appendLine("• 标点习惯：常用「${topPunct.key}」(${topPunct.value} 次)")
        }
        // 3. 高频词（2字以上中文词出现≥2次）
        val words = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(merged)
            .map { it.value }.toList()
            .groupingBy { it }.eachCount()
            .filter { it.value >= 2 }.toList().sortedByDescending { it.second }.take(5)
        if (words.isNotEmpty()) {
            sb.appendLine("• 高频词：${words.joinToString("、") { "${it.first}(${it.second})" }}")
        }
        // 4. 示例：最近 2 条 语音原文↔最终发出文字 对比
        sb.appendLine()
        sb.appendLine("📝 最近示例（语音原文 → 最终发出文字）：")
        recent.take(3).forEachIndexed { i, r ->
            if (r.voiceRawText.isNotEmpty() && r.voiceRawText != r.outputText) {
                sb.appendLine("  ${i + 1}. 语音输入：${r.voiceRawText.take(40)}${if (r.voiceRawText.length > 40) "…" else ""}")
                sb.appendLine("     最终发出：${r.outputText.take(40)}${if (r.outputText.length > 40) "…" else ""}")
            } else {
                sb.appendLine("  ${i + 1}. 原文：${r.inputText.take(40)}${if (r.inputText.length > 40) "…" else ""}")
                sb.appendLine("     润色：${r.outputText.take(40)}${if (r.outputText.length > 40) "…" else ""}")
            }
        }
        sb.appendLine()
        sb.appendLine("（提示：配置 AI Key 后可在云端生成更精准的个人语法纲要）")
        return sb.toString().trim().take(MAX_GUIDE_LENGTH)
    }

    private fun buildGuidePrompt(inputText: String): String {
        return "以下是用户的使用记录，包含「语音输入 → 最终发出文字」的对比（用户允许历史记录用于优化 AI 润色）。请据此分析并生成一份简洁的【用户个人语法/表达纲要】，供后续 AI 润色借鉴。\n" +
                "包括：\n" +
                "1. 常用句式和表达习惯（从语音口语化到最终书面语的转化规律）\n" +
                "2. 词汇偏好（喜欢用哪些词、常把哪些口语词改成什么）\n" +
                "3. 语气风格（正式/口语/幽默/简洁等）\n" +
                "4. 标点使用习惯\n" +
                "5. 其他语言特点\n" +
                "\n" +
                "要求：简洁精炼，不超过${MAX_GUIDE_LENGTH}字，用要点列表形式。\n" +
                "\n" +
                "使用记录：\n" +
                inputText
    }
}
