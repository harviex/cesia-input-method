package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log

/**
 * 纯 Kotlin 拼音输入引擎（Fallback）
 * 当 Rime native 库不可用时提供基本的中文拼音输入功能
 * 词库来源：pinyin_dict.json（assets 内嵌）
 */
class RimeEngineFallback(private val context: Context) {

    companion object {
        private const val TAG = "RimeFallback"
    }

    private var isReady = false
    private val pinyinMap = LinkedHashMap<String, List<String>>()
    private var currentPinyin = ""
    private var currentPage = 0
    private val pageSize = 5

    val isInitialized: Boolean get() = isReady
    val isComposing: Boolean get() = currentPinyin.isNotEmpty()
    val composingText: String get() = currentPinyin

    val candidates: List<String>
        get() {
            if (currentPinyin.isEmpty()) return emptyList()
            val words = pinyinMap[currentPinyin] ?: return emptyList()
            val from = currentPage * pageSize
            if (from >= words.size) return emptyList()
            val to = minOf(from + pageSize, words.size)
            return words.subList(from, to)
        }

    val hasCandidates: Boolean get() = candidates.isNotEmpty()

    val pageCount: Int
        get() {
            val words = pinyinMap[currentPinyin] ?: return 0
            return (words.size + pageSize - 1) / pageSize
        }

    val currentPageIdx: Int get() = currentPage

    fun initialize(): Boolean {
        if (isReady) return true
        try {
            val json = context.assets.open("pinyin_dict.json")
                .bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(json)
            var totalEntries = 0
            for (key in obj.keys()) {
                val value = obj.getString(key)
                val words = parseWords(value)
                pinyinMap[key] = words
                totalEntries += words.size
            }
            isReady = true
            Log.i(TAG, "纯 Kotlin 拼音引擎初始化成功: ${pinyinMap.size} 个拼音条目, $totalEntries 个候选词")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "拼音引擎初始化失败", e)
            return false
        }
    }

    private fun parseWords(hanziStr: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < hanziStr.length) {
            var matched = false
            for (len in minOf(4, hanziStr.length - i) downTo 2) {
                result.add(hanziStr.substring(i, i + len))
                i += len
                matched = true
                break
            }
            if (!matched) {
                result.add(hanziStr[i].toString())
                i++
            }
        }
        return result
    }

    fun inputLetter(c: Char): String {
        if (!isReady) return ""
        currentPinyin += c
        currentPage = 0
        Log.d(TAG, "inputLetter: $currentPinyin")
        return currentPinyin
    }

    fun backspace(): String {
        if (!isReady) return ""
        if (currentPinyin.isNotEmpty()) {
            currentPinyin = currentPinyin.dropLast(1)
        }
        currentPage = 0
        Log.d(TAG, "backspace: $currentPinyin")
        return currentPinyin
    }

    fun getCurrentPinyin(): String = currentPinyin

    fun selectCandidate(index: Int): String {
        if (!isReady) return ""
        val cands = candidates
        if (index < 0 || index >= cands.size) return ""
        return cands[index]
    }

    fun clear() {
        currentPinyin = ""
        currentPage = 0
    }

    fun nextPage(): List<String> {
        if (currentPage < pageCount - 1) currentPage++
        return candidates
    }

    fun prevPage(): List<String> {
        if (currentPage > 0) currentPage--
        return candidates
    }
}
