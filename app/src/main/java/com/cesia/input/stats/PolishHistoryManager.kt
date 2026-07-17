package com.cesia.input.stats

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 语音润色历史记录管理器
 *
 * 单独记录每次语音润色的内容（原文 → 润色后），默认关闭（不记录）。
 * 模式（history_mode）：
 *   - "off"   不记录（关闭历史记录功能，所有记录清空）
 *   - "local" 仅本地记录，不上云、不给 AI 分析
 *   - "ai"    本地记录 + 允许 AI 分析历史记录
 */
class PolishHistoryManager(context: Context) {

    data class PolishRecord(
        val id: Long,
        val original: String,
        val polished: String,
        val aiUsed: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cesia_polish_history", Context.MODE_PRIVATE)

    companion object {
        const val MODE_OFF = "off"
        const val MODE_LOCAL = "local"
        const val MODE_AI = "ai"
        const val PREF_MODE = "history_mode"
        private const val PREF_RECORDS = "records"
        private const val MAX_RECORDS = 500
    }

    fun getMode(): String = prefs.getString(PREF_MODE, MODE_OFF) ?: MODE_OFF

    fun setMode(mode: String) {
        prefs.edit().putString(PREF_MODE, mode).apply()
        if (mode == MODE_OFF) clearAll()
    }

    fun isRecording(): Boolean = getMode() != MODE_OFF

    fun addRecord(original: String, polished: String, aiUsed: Boolean) {
        if (!isRecording()) return
        if (original.isBlank() && polished.isBlank()) return
        val records = getRecords().toMutableList()
        val rec = PolishRecord(
            id = System.currentTimeMillis(),
            original = original,
            polished = polished,
            aiUsed = aiUsed
        )
        records.add(0, rec)
        // 截断保留最近 MAX_RECORDS 条
        val trimmed = if (records.size > MAX_RECORDS) records.take(MAX_RECORDS) else records
        saveRecords(trimmed)
    }

    fun getRecords(): List<PolishRecord> {
        val json = prefs.getString(PREF_RECORDS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<PolishRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PolishRecord(
                        id = obj.optLong("id", 0),
                        original = obj.optString("original", ""),
                        polished = obj.optString("polished", ""),
                        aiUsed = obj.optBoolean("aiUsed", false),
                        timestamp = obj.optLong("timestamp", 0)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearAll() {
        prefs.edit().putString(PREF_RECORDS, "[]").apply()
    }

    private fun saveRecords(records: List<PolishRecord>) {
        try {
            val arr = JSONArray()
            for (r in records) {
                val obj = JSONObject()
                obj.put("id", r.id)
                obj.put("original", r.original)
                obj.put("polished", r.polished)
                obj.put("aiUsed", r.aiUsed)
                obj.put("timestamp", r.timestamp)
                arr.put(obj)
            }
            prefs.edit().putString(PREF_RECORDS, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
