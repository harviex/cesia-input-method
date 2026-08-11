package com.cesia.input.engine

import java.io.File

/**
 * Cesia 自主英文词库加载器（不依赖 Rime 的 en schema）。
 *
 * Rime 的 en schema 在部署时未能编译出词典（build 目录无 en.table.bin），
 * 导致切到英文方案后候选栏为空。改为 Cesia 直接解析雾凇英文词库
 * （en_dicts/en.dict.yaml，标准 Rime 词典格式：每行 `word<TAB>code/weight`），
 * 做前缀联想，完全可控。
 *
 * 词条约 2 万，内存中以排序列表存储，前缀匹配用二分定位区间。
 */
class EnglishDictLoader {

    // 排序词表（去重、全小写）
    private var words: List<String> = emptyList()
    // 与 words 一一对应的 t9 数字串（加载时预计算，避免每次匹配实时转换 2 万词）
    private var t9List: List<String> = emptyList()

    // 字母 -> T9 数字映射（用于九键英文：把单词转成数字串做前缀匹配）
    private val t9Map = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    /** 从设备上的 Rime 词库文件（en_dicts/en.dict.yaml）加载词条 */
    fun loadFromFile(filePath: String): EnglishDictLoader {
        val set = sortedSetOf<String>()
        val file = File(filePath)
        if (file.exists()) {
            var inBody = false
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                for (raw in reader.lineSequence()) {
                    val line = raw.trimEnd()
                    if (line == "...") { inBody = true; continue }
                    if (!inBody) continue
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val word = if ('\t' in line) line.substringBefore('\t').trim()
                               else line.split(Regex("\\s+")).firstOrNull()?.trim() ?: ""
                    if (word.isNotEmpty() && word.all { it.isLetter() }) {
                        set.add(word.lowercase())
                    }
                }
            }
        }
        words = set.toList()
        // 预计算 t9 数字串（加载时一次，约 2 万词，毫秒级），供 t9Match 直接前缀匹配
        t9List = words.map { wordToT9(it) }
        return this
    }

    /** 全键盘：字母前缀匹配 */
    fun prefixMatch(prefix: String, limit: Int = 50): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        var lo = words.binarySearch { it.compareTo(p) }
        if (lo < 0) lo = -lo - 1
        val out = mutableListOf<String>()
        var i = lo
        while (i < words.size && words[i].startsWith(p) && out.size < limit) {
            out.add(words[i]); i++
        }
        return out
    }

    /** 九键：把输入数字序列（如 "228"）匹配 t9 编码以该序列为前缀的单词 */
    fun t9Match(digits: String, limit: Int = 50): List<String> {
        val d = digits.filter { it in '2'..'9' }
        if (d.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (i in words.indices) {
            if (t9List[i].startsWith(d) && out.size < limit) out.add(words[i])
        }
        return out
    }

    private fun wordToT9(w: String): String = buildString {
        for (ch in w) { t9Map[ch]?.let { append(it) } }
    }

    fun size(): Int = words.size
}
