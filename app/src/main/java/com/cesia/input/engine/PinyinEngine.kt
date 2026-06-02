package com.cesia.input.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 轻量级拼音输入引擎
 * 内置常用字拼音字典（~8000字）+ 常用词组字典（~700条）
 * 支持全拼输入、词组匹配和候选词选择
 */
class PinyinEngine(context: Context) {

    private val pinyinMap = mutableMapOf<String, String>()
    private val phraseMap = mutableMapOf<String, String>()
    private var currentPinyin = StringBuilder()
    private var candidates = listOf<String>()
    private var candidatePages = listOf<List<String>>()
    private var currentPage = 0
    private var dictManager: PinyinDictManager? = null

    // 用户词频记录：pinyin -> {char -> count}
    private val userFreq = mutableMapOf<String, MutableMap<String, Int>>()
    private val prefs = context.getSharedPreferences("cesia_user_freq", Context.MODE_PRIVATE)

    init {
        loadUserFreq()
        val externalLoaded = tryLoadExternalDict(context)
        if (!externalLoaded) {
            loadDictionary(context)
            loadPhrases(context)
        }
    }

    /**
     * 记录用户选择的字/词，用于自动靠前
     */
    fun recordSelection(pinyin: String, selected: String) {
        val freq = userFreq.getOrPut(pinyin) { mutableMapOf() }
        freq[selected] = (freq[selected] ?: 0) + 1
        // 保存到持久化
        saveUserFreq()
    }

    private fun loadUserFreq() {
        try {
            val jsonStr = prefs.getString("freq_data", "{}") ?: "{}"
            val json = JSONObject(jsonStr)
            for (key in json.keys()) {
                val inner = json.getJSONObject(key)
                val freqMap = mutableMapOf<String, Int>()
                for (innerKey in inner.keys()) {
                    freqMap[innerKey] = inner.getInt(innerKey)
                }
                userFreq[key] = freqMap
            }
        } catch (_: Exception) {}
    }

    private fun saveUserFreq() {
        try {
            val json = JSONObject()
            for (key in userFreq.keys) {
                val inner = JSONObject()
                for ((char, count) in userFreq[key]!!) {
                    inner.put(char, count)
                }
                json.put(key, inner)
            }
            prefs.edit().putString("freq_data", json.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * 尝试从外部文件加载词库
     */
    private fun tryLoadExternalDict(context: Context): Boolean {
        try {
            dictManager = PinyinDictManager(context)
            val dictPath = dictManager?.getDictFilePath()
            val phrasesPath = dictManager?.getPhrasesFilePath()

            if (dictPath == null && phrasesPath == null) return false

            var loaded = false

            if (dictPath != null) {
                try {
                    val dictFile = java.io.File(dictPath as String)
                    if (dictPath.endsWith(".json")) {
                        // JSON 格式（旧版兼容）
                        val jsonStr = dictFile.readText()
                        val json = org.json.JSONObject(jsonStr)
                        for (key in json.keys()) {
                            pinyinMap[key] = json.getString(key)
                        }
                    } else {
                        // .dict.yaml 格式：解析 Tab 分隔的词条行
                        // 格式：汉字\t拼音\t权重（权重可选）
                        var headerEnded = false
                        dictFile.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val trimmed = line.trim()
                                if (!headerEnded) {
                                    // 跳过 YAML header 和注释行
                                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---") || trimmed.startsWith("...") ||
                                        trimmed.startsWith("name:") || trimmed.startsWith("version:") || trimmed.startsWith("sort:") ||
                                        trimmed.startsWith("use_") || trimmed.startsWith("max_") || trimmed.startsWith("min_")) return@forEach
                                    // 遇到第一个词条行（含 Tab），header 结束
                                    if (trimmed.contains("\t")) headerEnded = true
                                    else return@forEach
                                }
                                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("\t")) {
                                    val parts = trimmed.split("\t")
                                    if (parts.size >= 2) {
                                        pinyinMap[parts[0]] = parts[1]
                                    }
                                }
                            }
                        }
                    }
                    Log.d("PinyinEngine", "外部拼音字典加载完成: ${pinyinMap.size} 个拼音条目 (" + dictFile.name + ")")
                    loaded = true
                } catch (e: Exception) {
                    Log.e("PinyinEngine", "外部拼音字典加载失败", e)
                }
            }
            if (phrasesPath != null) {
                try {
                    val jsonStr = java.io.File(phrasesPath as String).readText()
                    val json = org.json.JSONObject(jsonStr)
                    for (key in json.keys()) {
                        val value = json.getString(key)
                        // Store as-is: either "词1,词2" compact format or raw string
                        phraseMap[key] = value
                    }
                    Log.d("PinyinEngine", "外部词组字典加载完成: ${phraseMap.size} 个词组条目")
                    loaded = true
                } catch (e: Exception) {
                    Log.e("PinyinEngine", "外部词组字典加载失败", e)
                }
            }

            return loaded
        } catch (e: Exception) {
            Log.e("PinyinEngine", "尝试加载外部词库失败", e)
            return false
        }
    }

    /**
     * 重新加载词库（下载/导入新词库后调用）
     */
    fun reloadDict(context: Context) {
        pinyinMap.clear()
        phraseMap.clear()
        val externalLoaded = tryLoadExternalDict(context)
        if (!externalLoaded) {
            loadDictionary(context)
            loadPhrases(context)
        }
        clear()
    }

    private fun loadDictionary(context: Context) {
        try {
            val jsonStr = context.assets.open("pinyin_dict.json").bufferedReader().readText()
            val json = JSONObject(jsonStr)
            for (key in json.keys()) {
                pinyinMap[key] = json.getString(key)
            }
            Log.d("PinyinEngine", "拼音字典加载完成: ${pinyinMap.size} 个拼音条目")
        } catch (e: Exception) {
            Log.e("PinyinEngine", "拼音字典加载失败", e)
        }
    }

    private fun loadPhrases(context: Context) {
        try {
            val jsonStr = context.assets.open("pinyin_phrases.json").bufferedReader().readText()
            val json = JSONObject(jsonStr)
            for (key in json.keys()) {
                phraseMap[key] = json.getString(key)
            }
            Log.d("PinyinEngine", "词组字典加载完成: ${phraseMap.size} 个词组条目")
        } catch (e: Exception) {
            Log.e("PinyinEngine", "词组字典加载失败", e)
        }
    }

    /**
     * 输入一个字母，返回当前拼音串
     */
    fun inputLetter(c: Char): String {
        if (c in 'a'..'z') {
            currentPinyin.append(c)
            updateCandidates()
        }
        return currentPinyin.toString()
    }

    /**
     * 退格一个字母
     */
    fun backspace(): String {
        if (currentPinyin.isNotEmpty()) {
            currentPinyin.deleteCharAt(currentPinyin.length - 1)
            updateCandidates()
        }
        return currentPinyin.toString()
    }

    /**
     * 清空当前拼音
     */
    fun clear() {
        currentPinyin.clear()
        candidates = emptyList()
        candidatePages = emptyList()
        currentPage = 0
    }

    /**
     * 获取当前拼音串
     */
    fun getCurrentPinyin(): String = currentPinyin.toString()

    /**
     * 获取当前页候选词
     */
    fun getCandidates(): List<String> {
        if (candidatePages.isEmpty()) return emptyList()
        return candidatePages[currentPage]
    }

    /**
     * 获取总页数
     */
    fun getPageCount(): Int = candidatePages.size

    /**
     * 获取当前页码
     */
    fun getCurrentPage(): Int = currentPage

    /**
     * 是否有候选词
     */
    fun hasCandidates(): Boolean = candidates.isNotEmpty()

    /**
     * 是否正在输入拼音
     */
    fun isComposing(): Boolean = currentPinyin.isNotEmpty()

    /**
     * 翻到下一页候选词
     */
    fun nextPage(): List<String> {
        if (currentPage < candidatePages.size - 1) {
            currentPage++
        }
        return getCandidates()
    }

    /**
     * 翻到上一页候选词
     */
    fun prevPage(): List<String> {
        if (currentPage > 0) {
            currentPage--
        }
        return getCandidates()
    }

    /**
     * 选择候选词，返回选中的词/字并清空拼音
     * @param index 全局索引（页码*每页数量+页内索引）
     */
    fun selectCandidate(index: Int): String {
        if (index < 0 || index >= candidates.size) return ""
        val selected = candidates[index]
        val pinyin = currentPinyin.toString()
        // 记录用户选择
        if (pinyin.isNotEmpty()) {
            recordSelection(pinyin, selected)
        }
        clear()
        return selected
    }

    private fun updateCandidates() {
        val pinyin = currentPinyin.toString()
        if (pinyin.isEmpty()) {
            candidates = emptyList()
            candidatePages = emptyList()
            currentPage = 0
            return
        }

        val allCandidates = mutableListOf<String>()

        // 1. 单字精确匹配
        val exactChars = pinyinMap[pinyin]
        if (exactChars != null) {
            exactChars.forEach { allCandidates.add(it.toString()) }
        }

        // 2. 词组精确匹配
        val exactPhrase = phraseMap[pinyin]
        if (exactPhrase != null) {
            exactPhrase.split(",").forEach { phrase ->
                if (phrase.isNotEmpty()) allCandidates.add(phrase)
            }
        }

        // 3. 前缀匹配词组
        for ((key, value) in phraseMap) {
            if (key.startsWith(pinyin) && key != pinyin) {
                value.split(",").forEach { phrase ->
                    if (phrase.isNotEmpty()) allCandidates.add(phrase)
                }
            }
        }

        // 4. 前缀匹配单字
        for ((key, value) in pinyinMap) {
            if (key.startsWith(pinyin) && key != pinyin) {
                value.forEach { allCandidates.add(it.toString()) }
            }
        }

        // 去重
        candidates = allCandidates.distinct()

        // 按用户词频排序（高频靠前）
        val freq = userFreq[pinyin]
        if (freq != null && freq.isNotEmpty()) {
            candidates = candidates.sortedWith(compareByDescending<String> { freq[it] ?: 0 }.thenBy { it })
        }

        // 限制数量并分页
        candidates = candidates.take(40)
        if (candidates.isEmpty()) {
            candidatePages = emptyList()
        } else {
            candidatePages = candidates.chunked(5)
        }
        currentPage = 0
    }
}
