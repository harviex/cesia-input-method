package com.cesia.input

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * OpenCC 简繁转换工具（从 assets/opencc_s2t.json 加载映射表）。
 * 纯静态单例：首次调用 load(assets) 后缓存，全局复用，无状态依赖。
 */
object OpenCCConverter {

    private var SIMP_TO_TRAD: Map<Char, Char>? = null
    private var SIMP_TO_TRAD_PHRASES: Map<String, String>? = null
    private var TRAD_TO_SIMP: Map<Char, Char>? = null
    private var TRAD_TO_SIMP_PHRASES: Map<String, String>? = null
    private var loaded = false

    /** 从 assets 加载 OpenCC 简繁映射表（懒加载，只成功加载一次）。
     * 优先用 AssetManager（生产环境）；拿不到时回退到 classpath 资源（便于 JVM 单元测试）。 */
    @Synchronized
    fun load(assets: AssetManager) {
        if (loaded) return
        val json = try {
            assets.open("opencc_s2t.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // AssetManager 不可用（如 JVM 单元测试环境），回退到 classpath 资源
            OpenCCConverter::class.java.classLoader?.getResourceAsStream("opencc_s2t.json")
                ?.bufferedReader()?.use { it.readText() }
        }
        if (json.isNullOrEmpty()) {
            markEmpty()
            return
        }
        try {
            val obj = JSONObject(json)
            val charObj = obj.getJSONObject("char_map")
            val phraseObj = obj.getJSONObject("phrase_map")
            val charMap = mutableMapOf<Char, Char>()
            val phraseMap = mutableMapOf<String, String>()
            val revCharMap = mutableMapOf<Char, Char>()
            val revPhraseMap = mutableMapOf<String, String>()
            for (key in charObj.keys()) {
                if (key.length == 1) {
                    charMap[key[0]] = charObj.getString(key)[0]
                    revCharMap[charObj.getString(key)[0]] = key[0]
                }
            }
            for (key in phraseObj.keys()) {
                phraseMap[key] = phraseObj.getString(key)
                revPhraseMap[phraseObj.getString(key)] = key
            }
            SIMP_TO_TRAD = charMap
            SIMP_TO_TRAD_PHRASES = phraseMap
            TRAD_TO_SIMP = revCharMap
            TRAD_TO_SIMP_PHRASES = revPhraseMap
            loaded = true
        } catch (e: Exception) {
            markEmpty()
        }
    }

    private fun markEmpty() {
        SIMP_TO_TRAD = emptyMap()
        SIMP_TO_TRAD_PHRASES = emptyMap()
        TRAD_TO_SIMP = emptyMap()
        TRAD_TO_SIMP_PHRASES = emptyMap()
        // 不置 loaded=true，允许后续重试
    }

    /** 简→繁转换：先匹配词组（最长4字），再逐字替换 */
    fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        val charMap = SIMP_TO_TRAD ?: emptyMap()
        val phraseMap = SIMP_TO_TRAD_PHRASES ?: emptyMap()
        val sb = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in minOf(4, text.length - i) downTo 2) {
                val sub = text.substring(i, i + len)
                val trad = phraseMap[sub]
                if (trad != null) {
                    sb.append(trad)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = text[i]
                sb.append(charMap[ch] ?: ch)
                i++
            }
        }
        return sb.toString()
    }

    /** 繁→简转换：先匹配词组（最长4字），再逐字替换 */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        val charMap = TRAD_TO_SIMP ?: emptyMap()
        val phraseMap = TRAD_TO_SIMP_PHRASES ?: emptyMap()
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in minOf(4, text.length - i) downTo 2) {
                val sub = text.substring(i, i + len)
                val simp = phraseMap[sub]
                if (simp != null) {
                    sb.append(simp)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = text[i]
                sb.append(charMap[ch] ?: ch)
                i++
            }
        }
        return sb.toString()
    }
}
