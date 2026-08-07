package com.cesia.input.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 汉字 → 拼音映射表（真实读音）。
 *
 * 背景：原先 CesiaInputMethod.getPinyinFirstLetter() 是按 Unicode 区间每 256 码位硬分一个
 * 字母的**假映射**（孙 0x5B59 → "o"、珺 0x73FA → "p"），与真实读音完全无关；
 * getPinyinFull() 更是直接 return getPinyinFirstLetter()。
 * 导致 addUserPhrase() 登记的全拼码/简拼码全是错的 —— 组词「孙珺」后用 786586(sunjun)
 * 或 sj 都召不回。
 *
 * 本类改用 assets/pinyin_dict.json（格式 {"sun":"孙损笋…", "jun":"军君珺…"}，覆盖 16472 字）
 * 反建 字→拼音 索引，提供真实全拼与首字母。
 *
 * 加载策略：后台线程一次性加载（约 55KB JSON），未就绪时返回空串而非错误值 —— 宁可暂时
 * 匹配不到，也不能登记错误的码污染用户词库。
 */
object PinyinMap {

    private const val TAG = "PinyinMap"

    /** 汉字 → 全拼（如 孙→sun）。多音字取 json 中首次出现的读音。 */
    @Volatile
    private var charToPinyin: Map<Char, String> = emptyMap()

    @Volatile
    private var loaded = false

    @Volatile
    private var loading = false

    val isReady: Boolean get() = loaded

    /** 后台加载（幂等）。应用/IME 启动时调用一次。 */
    fun preload(context: Context) {
        synchronized(this) {
            if (loaded || loading) return
            loading = true
        }
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val json = context.assets.open("pinyin_dict.json")
                    .bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                // 预估容量，避免反复扩容
                val map = HashMap<Char, String>(20000)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val py = keys.next()
                    val chars = obj.optString(py)
                    for (c in chars) {
                        // 首次出现的读音优先（json 中常用读音在前）
                        if (!map.containsKey(c)) map[c] = py
                    }
                }
                charToPinyin = map
                loaded = true
                Log.i(TAG, "拼音表加载完成: ${map.size} 字, 耗时 ${System.currentTimeMillis() - t0}ms")
            } catch (e: Throwable) {
                Log.e(TAG, "拼音表加载失败: ${e.message}")
            } finally {
                loading = false
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /** 单字全拼；未知字返回空串 */
    fun full(c: Char): String = charToPinyin[c] ?: ""

    /** 单字拼音首字母；未知字返回空串 */
    fun firstLetter(c: Char): String = charToPinyin[c]?.take(1) ?: ""

    /**
     * 整串转全拼（孙珺 → sunjun）。
     * 字母/数字原样保留（小写）；汉字查表；**任一汉字查不到即返回空串**
     * —— 半截拼音反推出的数字码是错码，宁可不登记。
     */
    fun toFull(text: String): String {
        if (!loaded) return ""
        val sb = StringBuilder(text.length * 3)
        for (c in text) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c.lowercaseChar())
                c.code in 0x4E00..0x9FFF -> {
                    val py = charToPinyin[c] ?: return ""
                    sb.append(py)
                }
                // 其它字符（标点等）跳过
            }
        }
        return sb.toString()
    }

    /**
     * 整串转拼音首字母（孙珺 → sj）。
     * 规则同 toFull：任一汉字查不到即返回空串。
     */
    fun toFirstLetters(text: String): String {
        if (!loaded) return ""
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c.lowercaseChar())
                c.code in 0x4E00..0x9FFF -> {
                    val py = charToPinyin[c] ?: return ""
                    sb.append(py[0])
                }
            }
        }
        return sb.toString()
    }

    /**
     * 宽松版首字母（用于剪贴板搜索等模糊匹配场景）：
     * 查不到的字跳过而不是整串失败。
     */
    fun toFirstLettersLoose(text: String): String {
        if (!loaded) return ""
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c.lowercaseChar())
                else -> charToPinyin[c]?.let { sb.append(it[0]) }
            }
        }
        return sb.toString()
    }

    /** 宽松版全拼（用于剪贴板搜索） */
    fun toFullLoose(text: String): String {
        if (!loaded) return ""
        val sb = StringBuilder(text.length * 3)
        for (c in text) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c.lowercaseChar())
                else -> charToPinyin[c]?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }
}
