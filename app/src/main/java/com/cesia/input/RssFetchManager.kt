package com.cesia.input

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * RSS 新闻抓取管理器（轻量化单文件缓存版）
 *
 * 设计：
 * - 预置众多国内 RSS 源（按分类组织），用户可自定义添加 URL
 * - 用户选择一个源后立即抓取，覆盖写入同一个缓存文件（rss_cache.txt）
 * - 每小时后台自动刷新当前选中的源
 * - 切换源时清空重写
 * - 所有数据源读取走本地缓存文件，零网络延迟
 */
object RssFetchManager {
    private const val TAG = "RssFetchManager"
    private const val PREFS_NAME = "cesia_rss"
    private const val KEY_SELECTED_SOURCE = "selected_source"     // 当前选中的源名称
    private const val KEY_SELECTED_URL = "selected_url"           // 当前选中的源 URL
    private const val KEY_CUSTOM_SOURCES = "custom_sources"       // 用户自定义源 JSON
    private const val CACHE_FILE_NAME = "rss_cache.txt"           // 缓存文件（单文件）
    private const val MAX_ITEMS = 20                              // 最多缓存条数

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ===== 预置国内 RSS 源（众多，按分类） =====

    data class RssSource(val name: String, val url: String, val category: String)

    val PRESET_SOURCES: List<RssSource> = listOf(
        // 科技
        RssSource("爱范儿", "https://www.ifanr.com/feed", "科技"),
        RssSource("IT之家", "https://www.ithome.com/rss/", "科技"),
        RssSource("36氪", "https://36kr.com/feed", "科技"),
        RssSource("少数派", "https://sspai.com/feed", "科技"),
        RssSource("V2EX", "https://www.v2ex.com/index.xml", "科技"),
        RssSource("Readhub", "https://api.readhub.cn/feed", "科技"),
        RssSource("极客公园", "https://www.geekpark.net/rss", "科技"),
        RssSource("虎嗅", "https://www.huxiu.com/feed", "科技"),
        RssSource("创业邦", "https://www.cyzone.cn/feed", "科技"),
        RssSource("界面新闻", "https://www.jiemian.com/rss/newslist.xml", "科技"),
        RssSource("钛媒体", "https://www.tmtpost.com/rss", "科技"),
        RssSource("PingCode", "https://pingcode.com/rss", "科技"),

        // AI
        RssSource("Hacker News", "https://hnrss.org/frontpage", "AI"),
        RssSource("机器之心", "https://www.jiqizhixin.com/rss", "AI"),
        RssSource("量子位", "https://www.qbitai.com/feed", "AI"),
        RssSource("OpenAI Blog", "https://openai.com/blog/rss.xml", "AI"),
        RssSource("Google AI Blog", "https://blog.google/technology/ai/rss/", "AI"),

        // 财经
        RssSource("华尔街见闻", "https://wallstreetcn.com/rss", "财经"),
        RssSource("财新", "https://www.caixin.com/rss/10000.xml", "财经"),
        RssSource("第一财经", "https://www.yicai.com/rss", "财经"),
        RssSource("经济观察网", "http://www.eeo.com.cn/rss", "财经"),

        // 新闻/综合
        RssSource("BBC中文网", "https://feeds.bbci.co.uk/zhongwen/simp/rss.xml", "新闻"),
        RssSource("纽约时报中文网", "https://cn.nytimes.com/rss/society.xml", "新闻"),
        RssSource("阮一峰的网络日志", "https://www.ruanyifeng.com/blog/atom.xml", "新闻"),
        RssSource("人民日报", "https://feedx.net/rss/people.xml", "新闻"),
        RssSource("新华社", "https://www.xinhuanet.com/rss", "新闻"),
        RssSource("宝玉", "https://baoyu.io/feed.xml", "新闻"),

        // 生活/其他
        RssSource("煎蛋", "https://jandan.net/feed", "生活"),
        RssSource("生活周刊", "https://www.lifeweekly.com/rss", "生活")
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
                                if (title.isNotEmpty()) {
                                    items.add(NewsItem(title, link))
                                }
                                if (tag == "item") inItem = false
                                if (tag == "entry") inEntry = false
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

    // ===== 单文件缓存操作 =====

    /**
     * 获取缓存文件
     */
    private fun getCacheFile(context: Context): java.io.File {
        return java.io.File(context.filesDir, CACHE_FILE_NAME)
    }

    /**
     * 读取缓存文件内容
     */
    fun readCache(context: Context): String {
        return try {
            getCacheFile(context).readText()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 写入缓存文件（覆盖，限 MAX_ITEMS 条）
     */
    fun writeCache(context: Context, sourceName: String, items: List<NewsItem>) {
        try {
            val sb = StringBuilder()
            sb.appendLine("【$sourceName】")
            sb.appendLine("更新时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())}")
            sb.appendLine()
            items.forEach { item ->
                sb.appendLine("• ${item.title}")
                if (item.link.isNotEmpty()) sb.appendLine("  ${item.link}")
            }
            getCacheFile(context).writeText(sb.toString())
            Log.d(TAG, "Cache written: ${items.size} items from $sourceName")
        } catch (e: Exception) {
            Log.e(TAG, "Write cache failed: ${e.message}")
        }
    }

    /**
     * 抓取并写入缓存（完整流程）
     */
    suspend fun fetchAndCache(context: Context, source: RssSource): Boolean {
        val items = fetchSource(source, MAX_ITEMS)
        if (items.isEmpty()) return false
        writeCache(context, source.name, items)
        setSelectedSource(context, source.name, source.url)
        return true
    }

    // ===== 源管理 =====

    /**
     * 获取当前选中的源
     */
    fun getSelectedSource(context: Context): RssSource? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SELECTED_SOURCE, null) ?: return null
        val url = prefs.getString(KEY_SELECTED_URL, null) ?: return null
        // 从预置列表或自定义列表中查找
        val preset = PRESET_SOURCES.find { it.name == name }
        if (preset != null) return preset
        // 检查自定义源
        val custom = getCustomSources(context)
        return custom.find { it.name == name }?.let { RssSource(it.name, it.url, "自定义") }
            ?: RssSource(name, url, "自定义")
    }

    /**
     * 设置选中的源
     */
    fun setSelectedSource(context: Context, name: String, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_SOURCE, name)
            .putString(KEY_SELECTED_URL, url)
            .apply()
    }

    /**
     * 获取用户自定义源列表
     */
    fun getCustomSources(context: Context): List<RssSource> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]"
        return try {
            val list = mutableListOf<RssSource>()
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(RssSource(obj.getString("name"), obj.getString("url"), "自定义"))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 添加自定义源
     */
    fun addCustomSource(context: Context, name: String, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        arr.put(org.json.JSONObject().apply {
            put("name", name)
            put("url", url)
        })
        prefs.edit().putString(KEY_CUSTOM_SOURCES, arr.toString()).apply()
    }

    /**
     * 删除自定义源
     */
    fun removeCustomSource(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val newArr = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("name") != name) newArr.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_SOURCES, newArr.toString()).apply()
    }

    /**
     * 获取所有可用源（预置 + 自定义）
     */
    fun getAllSources(context: Context): List<RssSource> {
        return PRESET_SOURCES + getCustomSources(context)
    }

    /**
     * 获取所有源按分类分组
     */
    fun getSourcesByCategory(context: Context): Map<String, List<RssSource>> {
        return getAllSources(context).groupBy { it.category }
    }
}
