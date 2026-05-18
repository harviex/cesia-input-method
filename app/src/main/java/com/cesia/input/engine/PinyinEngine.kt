package com.cesia.input.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 轻量级拼音输入引擎
 * 内置常用字拼音字典（~8000字），支持全拼输入和候选词选择
 */
class PinyinEngine(context: Context) {

    private val pinyinMap = mutableMapOf<String, String>()
    private var currentPinyin = StringBuilder()
    private var candidates = listOf<String>()
    private var candidatePages = listOf<List<String>>()
    private var currentPage = 0

    init {
        loadDictionary(context)
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
     * 选择候选词，返回选中的字并清空拼音
     */
    fun selectCandidate(index: Int): String {
        val cands = getCandidates()
        if (index < 0 || index >= cands.size) return ""
        val selected = cands[index]
        clear()
        return selected
    }

    /**
     * 直接输入标点符号（当拼音串为空时）
     */
    fun isPunctuation(c: Char): Boolean {
        return c in "\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A\u201C\u201D\u2018\u2019\uFF08\uFF09\u3010\u3011\u300A\u300B\u2026\u2014\uFF5E"
    }

    private fun updateCandidates() {
        val pinyin = currentPinyin.toString()
        if (pinyin.isEmpty()) {
            candidates = emptyList()
            candidatePages = emptyList()
            currentPage = 0
            return
        }

        // 精确匹配
        val exactMatch = pinyinMap[pinyin]
        if (exactMatch != null) {
            // 将字符串拆分为单个字符
            val chars = exactMatch.map { it.toString() }
            candidates = chars
        } else {
            // 前缀匹配：找所有以当前拼音开头的条目
            val prefixMatches = mutableListOf<String>()
            for ((key, value) in pinyinMap) {
                if (key.startsWith(pinyin)) {
                    prefixMatches.addAll(value.map { it.toString() })
                }
            }
            // 去重并限制数量
            candidates = prefixMatches.distinct().take(30)
        }

        // 分页，每页9个
        if (candidates.isEmpty()) {
            candidatePages = emptyList()
        } else {
            candidatePages = candidates.chunked(9)
        }
        currentPage = 0
    }
}
