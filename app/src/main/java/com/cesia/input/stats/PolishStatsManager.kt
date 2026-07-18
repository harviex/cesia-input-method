package com.cesia.input.stats

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class PolishRecord(
    val timestamp: Long,
    val inputText: String,
    val outputText: String,
    val inputChars: Int,
    val outputChars: Int,
    val voiceDurationMs: Long = 0,  // 语音输入时长（毫秒）
    val voiceRawText: String = "",  // 语音识别原文（与最终发出文字对比用）；非语音场景为空
    val type: String = "polish"     // 记录类型：voice=语音(含原文对比) / keyboard=键盘发出 / polish=润色
)

class PolishStatsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cesia_polish_stats", Context.MODE_PRIVATE)
    private val recordsPrefs: SharedPreferences = context.getSharedPreferences("cesia_polish_records", Context.MODE_PRIVATE)

    // 统计数据
    var totalInputChars: Int
        get() = prefs.getInt("total_input_chars", 0)
        set(value) = prefs.edit().putInt("total_input_chars", value).apply()

    var totalOutputChars: Int
        get() = prefs.getInt("total_output_chars", 0)
        set(value) = prefs.edit().putInt("total_output_chars", value).apply()

    var totalPolishCount: Int
        get() = prefs.getInt("total_polish_count", 0)
        set(value) = prefs.edit().putInt("total_polish_count", value).apply()

    // 语音统计
    var totalVoiceDurationMs: Long
        get() = prefs.getLong("total_voice_duration_ms", 0L)
        set(value) = prefs.edit().putLong("total_voice_duration_ms", value).apply()

    var totalVoiceChars: Int
        get() = prefs.getInt("total_voice_chars", 0)
        set(value) = prefs.edit().putInt("total_voice_chars", value).apply()

    // 计算属性：节省的时间（秒）
    // 假设打字速度为每分钟60字，语音输入速度为每分钟200字
    val savedTimeSeconds: Long
        get() {
            if (totalVoiceChars == 0) return 0
            val typingTimeMinutes = totalVoiceChars / 60.0  // 打字需要的时间（分钟）
            val voiceTimeMinutes = totalVoiceDurationMs / 60000.0  // 语音实际用时（分钟）
            val savedMinutes = typingTimeMinutes - voiceTimeMinutes
            return (savedMinutes * 60).toLong().coerceAtLeast(0)
        }

    // 计算属性：语音输入速度（字/分钟）
    val voiceSpeedPerMinute: Int
        get() {
            if (totalVoiceDurationMs == 0L) return 0
            val minutes = totalVoiceDurationMs / 60000.0
            return (totalVoiceChars / minutes).toInt()
        }

    fun addRecord(
        inputText: String,
        outputText: String,
        voiceDurationMs: Long = 0,
        voiceRawText: String = "",
        type: String = "polish"
    ) {
        totalInputChars += inputText.length
        totalOutputChars += outputText.length
        totalPolishCount++
        totalVoiceDurationMs += voiceDurationMs
        totalVoiceChars += inputText.length

        // 保存记录（最多100条）
        val records = getRecords().toMutableList()
        records.add(0, PolishRecord(
            timestamp = System.currentTimeMillis(),
            inputText = inputText,
            outputText = outputText,
            inputChars = inputText.length,
            outputChars = outputText.length,
            voiceDurationMs = voiceDurationMs,
            voiceRawText = voiceRawText,
            type = type
        ))
        val trimmed = records.take(100)
        saveRecords(trimmed)
    }

    fun getRecords(): List<PolishRecord> {
        val json = recordsPrefs.getString("records", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<PolishRecord>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(PolishRecord(
                timestamp = obj.getLong("timestamp"),
                inputText = obj.getString("input"),
                outputText = obj.getString("output"),
                inputChars = obj.getInt("inputChars"),
                outputChars = obj.getInt("outputChars"),
                voiceDurationMs = obj.optLong("voiceDurationMs", 0),
                voiceRawText = obj.optString("voiceRawText", ""),
                type = obj.optString("type", "polish")
            ))
        }
        return list
    }

    fun clearRecords() {
        recordsPrefs.edit().putString("records", "[]").apply()
        prefs.edit().putInt("total_input_chars", 0)
            .putInt("total_output_chars", 0)
            .putInt("total_polish_count", 0)
            .putLong("total_voice_duration_ms", 0L)
            .putInt("total_voice_chars", 0)
            .apply()
    }

    fun deleteRecord(index: Int) {
        val records = getRecords().toMutableList()
        if (index in records.indices) {
            records.removeAt(index)
            saveRecords(records)
        }
    }

    private fun saveRecords(records: List<PolishRecord>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("input", r.inputText)
                put("output", r.outputText)
                put("inputChars", r.inputChars)
                put("outputChars", r.outputChars)
                put("voiceDurationMs", r.voiceDurationMs)
                put("voiceRawText", r.voiceRawText)
                put("type", r.type)
            })
        }
        recordsPrefs.edit().putString("records", arr.toString()).apply()
    }
}
