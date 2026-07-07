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
    private const val KEY_SELECTED_SOURCE = "selected_source"
    private const val MAX_ITEMS = 30
    private const val FETCH_TIMEOUT_SECONDS = 15L

    private val client = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ===== 预置国内可访问 RSS 源（去重、去除需翻墙、平衡分类） =====

    data class RssSource(val name: String, val url: String, val category: String)

    val PRESET_SOURCES: List<RssSource> = listOf(
        // 科技（精选 4 个，避免过多）
        RssSource("爱范儿", "https://www.ifanr.com/feed", "科技"),
        RssSource("IT之家", "https://www.ithome.com/rss/", "科技"),
        RssSource("少数派", "https://sspai.com/feed", "科技"),
        RssSource("虎嗅", "https://www.huxiu.com/feed", "科技"),

        // AI（仅保留国内可访问）
        RssSource("量子位", "https://www.qbitai.com/feed", "AI"),
        RssSource("机器之心", "https://www.jiqizhixin.com/rss/news", "AI"),
        RssSource("InfoQ 中文", "https://www.infoq.cn/rss", "AI"),

        // 财经
        RssSource("华尔街见闻", "https://dedicated.wallstreetcn.com/rss.xml", "财经"),
        RssSource("财新网", "https://feedx.net/rss/caixin.xml", "财经"),
        RssSource("雪球热榜", "https://xueqiu.com/statuses/hot.json", "财经"),

        // 新闻/综合（国内主流）
        RssSource("人民日报", "https://feedx.net/rss/people.xml", "新闻"),
        RssSource("新华社", "https://feedx.net/rss/xinhua.xml", "新闻"),
        RssSource("央视新闻", "https://feedx.net/rss/cctv.xml", "新闻"),
        RssSource("澎湃新闻", "https://feedx.net/rss/thepaper.xml", "新闻"),
        RssSource("界面新闻", "https://feedx.net/rss/jiemian.xml", "新闻"),
        RssSource("阮一峰的网络日志", "https://www.ruanyifeng.com/blog/atom.xml", "新闻"),

        // 国际（国内可访问的中文源）
        RssSource("环球时报", "https://feedx.net/rss/huanqiu.xml", "国际"),
        RssSource("多维新闻", "https://feedx.net/rss/duowei.xml", "国际"),

        // 生活/文化
        RssSource("宝玉", "https://baoyu.io/feed.xml", "生活"),
        RssSource("果壳网", "https://www.guokr.com/rss", "生活"),
        RssSource("简书热门", "https://www.jianshu.com/feed", "生活"),
        RssSource("知乎日报", "https://daily.zhihu.com/rss", "生活"),

        // 汽车/出行
        RssSource("电动邦", "https://www.dyb.com/rss.xml", "汽车"),
        RssSource("太平洋汽车", "https://www.pcauto.com.cn/rss/", "汽车"),

        // 游戏/娱乐
        RssSource("游民星空", "https://www.gamersky.com/rss/", "游戏"),
        RssSource("机核网", "https://www.gcores.com/rss", "游戏"),

        // 编程/开发
        RssSource("掘金", "https://juejin.cn/rss", "开发"),
        RssSource("GitHub Trending", "https://github-trending.vercel.app/rss", "开发")
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
                            "item" -> { inItem = true; title = ""; link = "" }
                            "entry" -> { inEntry = true; title = ""; link = "" }
                            "link" -> {
                                linkHref = parser.getAttributeValue(null, "href") ?: ""
                                currentText = ""
                            }
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
                                }
                                if (inEntry && linkHref.isNotEmpty()) {
                                    link = linkHref
                                }
                            }
                            "item", "entry" -> {
                                if (title.isNotEmpty() && link.isNotEmpty()) {
                                    items.add(NewsItem(title, link))
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
                list.add(RssSource(
                    obj.getString("name"),
                    obj.getString("url"),
                    "自定义"
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse custom sources error: ${e.message}")
        }
        return list
    }

    /** 添加自定义源（去重） */
    fun addCustomSource(context: Context, name: String, url: String): Boolean {
        if (name.isBlank() || url.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCustomSources(context).toMutableList()

        // 去重：同名或同 URL 不重复添加
        if (current.any { it.name == name || it.url == url }) return false

        current.add(RssSource(name, url, "自定义"))
        val json = StringBuilder().apply {
            append("[")
            current.forEachIndexed { index, s ->
                if (index > 0) append(",")
                append("{\"name\":\"${s.name.replace("\"", "\\\"")}\",\"url\":\"${s.url.replace("\"", "\\\"")}\"}")
            }
            append("]")
        }.toString()
        prefs.edit().putString(KEY_CUSTOM_SOURCES, json).apply()
        return true
    }

    /** 删除自定义源 */
    fun removeCustomSource(context: Context, name: String, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCustomSources(context).filterNot { it.name == name && it.url == url }.toMutableList()
        val json = StringBuilder().apply {
            append("[")
            current.forEachIndexed { index, s ->
                if (index > 0) append(",")
                append("{\"name\":\"${s.name.replace("\"", "\\\"")}\",\"url\":\"${s.url.replace("\"", "\\\"")}\"}")
            }
            append("]")
        }.toString()
        prefs.edit().putString(KEY_CUSTOM_SOURCES, json).apply()
    }

    /** 获取所有源（预置 + 自定义），新闻类置顶 */
    fun getAllSources(context: Context): List<RssSource> {
        val all = mutableListOf<RssSource>()
        all.addAll(PRESET_SOURCES)
        all.addAll(getCustomSources(context))
        
        // 排序：新闻类(新闻/综合)置顶，其次按分类首字母排序，自定义在最后
        return all.sortedWith(compareByDescending<RssSource> { it.category == "新闻" }
            .thenByDescending { it.category == "综合" }
            .thenBy { it.category }
            .thenBy { it.name }
        )
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