package com.cesia.input

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * RSS 抓取管理器
 * - 预置国内可访问的 RSS 源（已去重、去除需翻墙源、平衡分类）
 * - 支持自定义 RSS 源（持久化到 SharedPreferences）
 * - 缓存抓取结果供智能写作使用
 */
object RssFetchManager {

    private const val TAG = "RssFetchManager"
    private const val PREFS_NAME = "cesia_rss_sources"
    private const val KEY_CUSTOM_SOURCES = "custom_sources"
    private const val KEY_CUSTOM_PINNED = "custom_pinned"
    private const val KEY_SELECTED_SOURCE = "selected_source"
    private const val KEY_DELETED_PRESETS = "deleted_preset_sources"  // 已删除的预置源黑名单
    private const val MAX_ITEMS = 30
    private const val FETCH_TIMEOUT_SECONDS = 15L

    private val client = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ===== 预置国内可访问 RSS 源（按分类组织，已验证可用性，去重、去除需翻墙、平衡分类） =====

    data class RssSource(val name: String, val url: String, val category: String, val isCustom: Boolean = false)

    val PRESET_SOURCES: List<RssSource> = listOf(
        // ===== 官方主流媒体（均为高产出源，anyfeeder 非微信代理） =====
        RssSource("人民日报", "https://plink.anyfeeder.com/people-daily", "官方主流媒体"),
        RssSource("新华社新闻_新华网", "https://plink.anyfeeder.com/newscn/whxw", "官方主流媒体"),
        RssSource("光明日报", "https://plink.anyfeeder.com/guangmingribao", "官方主流媒体"),
        RssSource("头条 - 求是网", "https://plink.anyfeeder.com/qstheory", "官方主流媒体"),

        // ===== 军事国防 =====
        RssSource("解放军报", "https://plink.anyfeeder.com/jiefangjunbao", "军事国防"),

        // ===== 商业财经媒体 =====
        RssSource("财富中文网", "https://plink.anyfeeder.com/fortunechina", "商业财经媒体"),
        RssSource("人人都是产品经理", "https://www.woshipm.com/feed", "商业财经媒体"),

        // ===== 教育考试 =====
        RssSource("InfoQ 推荐", "https://plink.anyfeeder.com/infoq/recommend", "教育考试"),

        // ===== 人文历史读物 =====
        RssSource("南方周末-推荐", "https://plink.anyfeeder.com/infzm/recommends", "人文历史读物"),
        RssSource("南方周末-新闻", "https://plink.anyfeeder.com/infzm/news", "人文历史读物"),

        // ===== 科技互联网媒体（直连 feed + 高产出代理） =====
        RssSource("虎嗅", "https://rss.huxiu.com/", "科技互联网媒体"),
        RssSource("IT之家", "https://www.ithome.com/rss/", "科技互联网媒体"),
        RssSource("爱范儿", "https://www.ifanr.com/feed", "科技互联网媒体"),
        RssSource("奇客Solidot", "https://www.solidot.org/index.rss", "科技互联网媒体"),
        RssSource("钛媒体", "https://www.tmtpost.com/feed", "科技互联网媒体"),
        RssSource("少数派", "https://sspai.com/feed", "科技互联网媒体"),

        // ===== 科学科普（仅 anyfeeder 微信代理可用，保留代表源） =====
        RssSource("环球科学", "https://plink.anyfeeder.com/weixin/ScientificAmerican", "科学科普"),

        // ===== 体育运动（仅 anyfeeder 微信代理可用，保留代表源） =====
        RssSource("新浪体育", "https://plink.anyfeeder.com/weixin/sports_sina", "体育运动")
    )

    // ===== 数据类 =====

    data class NewsItem(val title: String, val link: String)

    // ===== 核心抓取逻辑 =====

    /**
     * 抓取单个 RSS 源，返回最新 N 条新闻
     */
    suspend fun fetchSource(source: RssSource, count: Int = MAX_ITEMS): List<NewsItem> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", "CesiaIME/1.0 (RSS Reader)")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Fetch failed: ${source.name} HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                parseRssXml(body, count)
            } catch (e: Exception) {
                Log.w(TAG, "Fetch error: ${source.name}: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * 从 RSS XML 解析标题和链接（兼容 RSS + Atom）
     */
    private fun parseRssXml(xml: String, maxCount: Int): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var title = ""
            var link = ""
            var guid = ""
            var channelLink = ""
            var inItem = false
            var inEntry = false
            var currentText = ""
            var linkHref = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT && items.size < maxCount) {
                val tag = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "item" -> { inItem = true; title = ""; link = ""; guid = "" }
                            "entry" -> { inEntry = true; title = ""; link = ""; guid = "" }
                            "link" -> {
                                linkHref = parser.getAttributeValue(null, "href") ?: ""
                                currentText = ""
                            }
                            "guid" -> { currentText = "" }
                            "title" -> { currentText = "" }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        currentText += parser.text ?: ""
                    }
                    XmlPullParser.END_TAG -> {
                        when (tag) {
                            "title" -> {
                                val clean = currentText.trim()
                                if (inItem || inEntry) title = clean
                            }
                            "link" -> {
                                if (inItem && link.isEmpty()) {
                                    link = currentText.trim().ifEmpty { linkHref }
                                } else if (inEntry && linkHref.isNotEmpty()) {
                                    link = linkHref
                                } else if (!inItem && !inEntry) {
                                    channelLink = currentText.trim().ifEmpty { linkHref }
                                }
                            }
                            "guid" -> {
                                if (inItem || inEntry) guid = currentText.trim()
                            }
                            "item", "entry" -> {
                                // link 可选：无 link 时用 guid / channelLink 兜底，确保无 link 的源（如 X Timeline RSS）也能收录
                                val finalLink = link.ifEmpty { guid.ifEmpty { channelLink } }
                                if (title.isNotEmpty()) {
                                    items.add(NewsItem(title, finalLink))
                                }
                                inItem = false
                                inEntry = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
        return items
    }

    // ===== 缓存与选择状态持久化 =====

    /** 抓取并缓存到 SharedPreferences（供智能写作读取） */
    @WorkerThread
    suspend fun fetchAndCache(context: Context, source: RssSource): Boolean {
        val items = fetchSource(source)
        if (items.isEmpty()) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = StringBuilder().apply {
            append("[")
            items.forEachIndexed { index, item ->
                if (index > 0) append(",")
                append("{\"title\":\"${item.title.replace("\"", "\\\"")}\",\"link\":\"${item.link.replace("\"", "\\\"")}\"}")
            }
            append("]")
        }.toString()

        prefs.edit()
            .putString("cached_items", json)
            .putString("cached_source_name", source.name)
            .putString("cached_source_url", source.url)
            .putString("cached_source_category", source.category)
            .putLong("cached_time", System.currentTimeMillis())
            .apply()
        return true
    }

    /** 获取当前选中的源（优先自定义，其次预置） */
    fun getSelectedSource(context: Context): RssSource? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("selected_name", "") ?: ""
        val url = prefs.getString("selected_url", "") ?: ""
        val category = prefs.getString("selected_category", "") ?: ""
        if (name.isEmpty() || url.isEmpty()) return null

        // 先查自定义源
        val custom = getCustomSources(context).find { it.name == name && it.url == url }
        if (custom != null) return RssSource(custom.name, custom.url, "自定义")

        // 再查预置源
        val preset = PRESET_SOURCES.find { it.name == name && it.url == url }
        if (preset != null) return preset

        // 兜底：用保存的分类
        return RssSource(name, url, category.ifEmpty { "自定义" })
    }

    /** 保存选中的源 */
    fun saveSelectedSource(context: Context, source: RssSource) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("selected_name", source.name)
            .putString("selected_url", source.url)
            .putString("selected_category", source.category)
            .apply()
    }

    /** 清除选中的源 */
    fun clearSelectedSource(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("selected_name")
            .remove("selected_url")
            .remove("selected_category")
            .apply()
    }

    /** 获取自定义源列表 */
    fun getCustomSources(context: Context): List<RssSource> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]"
        val list = mutableListOf<RssSource>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cat = if (obj.has("category")) obj.optString("category", "自定义") else "自定义"
                list.add(RssSource(
                    obj.getString("name"),
                    obj.getString("url"),
                    cat,
                    isCustom = true
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse custom sources error: ${e.message}")
        }
        return list
    }

    /** 添加自定义源（去重）；category 默认「自定义」，可在指定分类下添加 */
    fun addCustomSource(context: Context, name: String, url: String, category: String = "自定义"): Boolean {
        if (name.isBlank() || url.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCustomSources(context).toMutableList()

        // 去重：同名或同 URL 不重复添加
        if (current.any { it.name == name || it.url == url }) return false

        current.add(RssSource(name, url, category))
        val json = StringBuilder().apply {
            append("[")
            current.forEachIndexed { index, s ->
                if (index > 0) append(",")
                append("{\"name\":\"${s.name.replace("\"", "\\\"")}\",\"url\":\"${s.url.replace("\"", "\\\"")}\",\"category\":\"${s.category.replace("\"", "\\\"")}\"}")
            }
            append("]")
        }.toString()
        prefs.edit().putString(KEY_CUSTOM_SOURCES, json).apply()
        return true
    }

    /** 删除源（所有源都可删：自定义源从列表移除，预置源加入删除黑名单） */
    fun removeCustomSource(context: Context, name: String, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 自定义源：从 custom_sources 移除
        val custom = getCustomSources(context).filterNot { it.name == name && it.url == url }
        val json = StringBuilder().apply {
            append("[")
            custom.forEachIndexed { index, s ->
                if (index > 0) append(",")
                append("{\"name\":\"${s.name.replace("\"", "\\\"")}\",\"url\":\"${s.url.replace("\"", "\\\"")}\"}")
            }
            append("]")
        }.toString()
        prefs.edit().putString(KEY_CUSTOM_SOURCES, json).apply()
        // 预置源：若有同名同 URL 的预置源，加入删除黑名单（不再展示，但可恢复）
        val preset = PRESET_SOURCES.find { it.name == name && it.url == url }
        if (preset != null) {
            val deleted = getDeletedPresets(context).toMutableSet()
            deleted.add(name to url)
            prefs.edit().putString(KEY_DELETED_PRESETS,
                deleted.joinToString("\u0000") { "${it.first}\u0001${it.second}" }).apply()
        }
        // 若删除的是当前选中源，清空选中态
        val sel = getSelectedSource(context)
        if (sel != null && sel.name == name && sel.url == url) clearSelectedSource(context)
    }

    /** 读取已删除的预置源黑名单（name,url 对集合） */
    fun getDeletedPresets(context: Context): Set<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_DELETED_PRESETS, "") ?: ""
        if (s.isEmpty()) return emptySet()
        return s.split("\u0000").mapNotNull { part ->
            val kv = part.split("\u0001")
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toSet()
    }

    /** 恢复全部预置源（清空删除黑名单） */
    fun restoreAllPresets(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DELETED_PRESETS).apply()
    }

    /** 获取所有源（预置 + 自定义），新闻类置顶。已删除的预置源从黑名单中剔除不再展示 */
    fun getAllSources(context: Context): List<RssSource> {
        val pinned = getCustomPinned(context)
        val deletedPresets = getDeletedPresets(context)
        val all = mutableListOf<RssSource>()
        // 预置源剔除已删除黑名单（通过 name+url 唯一标识）
        all.addAll(PRESET_SOURCES.filterNot { deletedPresets.contains(it.name to it.url) })
        // 自定义源标记为可删
        all.addAll(getCustomSources(context).map { it.copy(isCustom = true) })

        // 排序：新闻类(新闻/综合)置顶，其次按分类首字母排序，自定义在最后；
        // 自定义源内「置顶」的排在非置顶自定义源之前（批量置顶生效）
        return all.sortedWith(compareByDescending<RssSource> { it.category == "新闻" }
            .thenByDescending { it.category == "综合" }
            .thenBy { it.category }
            .thenByDescending { it.category == "自定义" && it.url in pinned }
            .thenBy { it.name }
        )
    }

    /** 读取置顶的自定义源 URL 集合 */
    fun getCustomPinned(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_CUSTOM_PINNED, "") ?: ""
        return if (s.isNotEmpty()) s.split("\u0000").toSet() else emptySet()
    }

    /** 批量置顶：把给定 URL 列表中的自定义源标记为置顶（合并进已有置顶集合） */
    fun pinCustomSources(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        val pinned = getCustomPinned(context).toMutableSet()
        pinned.addAll(urls)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_PINNED, pinned.joinToString("\u0000")).apply()
    }

    /** 取消置顶（可选，暂未直接暴露 UI） */
    fun unpinCustomSources(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        val pinned = getCustomPinned(context).toMutableSet()
        pinned.removeAll(urls.toSet())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_PINNED, pinned.joinToString("\u0000")).apply()
    }

    /** 按分类分组获取源 */
    fun getSourcesByCategory(context: Context): Map<String, List<RssSource>> {
        return getAllSources(context).groupBy { it.category }
            .toSortedMap()
    }

    /** 读取缓存的 RSS 内容（用于智能写作语境） */
    fun readCache(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("cached_items", "[]") ?: "[]"
        val name = prefs.getString("cached_source_name", "") ?: ""
        val time = prefs.getLong("cached_time", 0)
        if (json == "[]" || json.isBlank()) return ""
        try {
            val parser = com.google.gson.JsonParser.parseString(json).asJsonArray
            val sb = StringBuilder()
            if (name.isNotBlank()) sb.append("【$name】\n")
            if (time > 0) {
                val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                sb.append("更新：${sdf.format(java.util.Date(time))}\n\n")
            }
            val count = kotlin.math.min(10, parser.size())
            for (i in 0 until count) {
                val obj = parser[i].asJsonObject
                val title = obj.get("title")?.asString ?: ""
                val link = obj.get("link")?.asString ?: ""
                if (title.isNotBlank()) {
                    sb.append("${i + 1}. $title\n")
                    if (link.isNotBlank()) sb.append("   $link\n")
                    sb.append("\n")
                }
            }
            return sb.toString().trim()
        } catch (_: Exception) {
            return ""
        }
    }
}