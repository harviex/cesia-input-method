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

    // ===== 预置国内可访问 RSS 源（按分类组织，去重、去除需翻墙、平衡分类） =====

    data class RssSource(val name: String, val url: String, val category: String)

    val PRESET_SOURCES: List<RssSource> = listOf(
        // ===== 官方主流媒体 =====
        RssSource("人民日报", "https://plink.anyfeeder.com/people-daily", "官方主流媒体"),
        RssSource("人民网", "https://plink.anyfeeder.com/weixin/people_rmw", "官方主流媒体"),
        RssSource("人民网-国内新闻", "https://plink.anyfeeder.com/people/politics", "官方主流媒体"),
        RssSource("人民网-国际新闻", "https://plink.anyfeeder.com/people/world", "官方主流媒体"),
        RssSource("人民网-英语新闻", "https://plink.anyfeeder.com/people/english", "官方主流媒体"),
        RssSource("新华社新闻_新华网", "https://plink.anyfeeder.com/newscn/whxw", "官方主流媒体"),
        RssSource("新华网", "https://plink.anyfeeder.com/weixin/newsxinhua", "官方主流媒体"),
        RssSource("中国日报: 专栏", "https://plink.anyfeeder.com/chinadaily/column", "官方主流媒体"),
        RssSource("中国日报: 双语新闻", "https://plink.anyfeeder.com/chinadaily/dual", "官方主流媒体"),
        RssSource("中国日报: 时政", "https://plink.anyfeeder.com/chinadaily/china", "官方主流媒体"),
        RssSource("中国日报: 财经", "https://plink.anyfeeder.com/chinadaily/caijing", "官方主流媒体"),
        RssSource("中国日报: 资讯", "https://plink.anyfeeder.com/chinadaily/world", "官方主流媒体"),
        RssSource("侠客岛", "https://plink.anyfeeder.com/weixin/xiake_island", "官方主流媒体"),
        RssSource("光明日报", "https://plink.anyfeeder.com/guangmingribao", "官方主流媒体"),
        RssSource("半月谈", "https://plink.anyfeeder.com/weixin/banyuetan-weixin", "官方主流媒体"),
        RssSource("参考消息", "https://plink.anyfeeder.com/weixin/ckxxwx", "官方主流媒体"),
        RssSource("央视新闻", "https://plink.anyfeeder.com/weixin/cctvnewscenter", "官方主流媒体"),
        RssSource("央视财经", "https://plink.anyfeeder.com/weixin/cctvyscj", "官方主流媒体"),
        RssSource("头条 - 求是网", "https://plink.anyfeeder.com/qstheory", "官方主流媒体"),
        RssSource("新京报 - 好新闻，无止境", "https://plink.anyfeeder.com/bjnews", "官方主流媒体"),
        RssSource("新京报书评周刊", "https://plink.anyfeeder.com/weixin/ibookreview", "官方主流媒体"),
        RssSource("环球时报", "https://plink.anyfeeder.com/weixin/hqsbwx", "官方主流媒体"),
        RssSource("经济观察报", "https://plink.anyfeeder.com/weixin/eeo-com-cn", "官方主流媒体"),
        RssSource("财新网", "https://plink.anyfeeder.com/weixin/caixinwang", "官方主流媒体"),
        RssSource("界面新闻: 新闻", "https://plink.anyfeeder.com/jiemian/news", "官方主流媒体"),

        // ===== 军事国防 =====
        RssSource("解放军报", "https://plink.anyfeeder.com/jiefangjunbao", "军事国防"),
        RssSource("铁血军事", "https://plink.anyfeeder.com/weixin/tiexuejunshi", "军事国防"),

        // ===== 商业财经媒体 =====
        RssSource("21世纪经济报道", "https://plink.anyfeeder.com/weixin/jjbd21", "商业财经媒体"),
        RssSource("哈佛商业评论", "https://plink.anyfeeder.com/weixin/hbrchinese", "商业财经媒体"),
        RssSource("界面新闻: 商业", "https://plink.anyfeeder.com/jiemian/business", "商业财经媒体"),
        RssSource("界面新闻: 财经", "https://plink.anyfeeder.com/jiemian/finance", "商业财经媒体"),
        RssSource("新财富", "https://plink.anyfeeder.com/weixin/newfortune", "商业财经媒体"),
        RssSource("猎云网", "https://plink.anyfeeder.com/lieyunwang", "商业财经媒体"),
        RssSource("财富中文网", "https://plink.anyfeeder.com/fortunechina", "商业财经媒体"),
        RssSource("人人都是产品经理", "https://www.woshipm.com/feed", "商业财经媒体"),
        RssSource("今日话题 - 雪球", "https://xueqiu.com/hots/topic/rss", "商业财经媒体"),

        // ===== 教育考试 =====
        RssSource("InfoQ 推荐", "https://plink.anyfeeder.com/infoq/recommend", "教育考试"),
        RssSource("MOOC", "https://plink.anyfeeder.com/weixin/mooc", "教育考试"),
        RssSource("三节课", "https://plink.anyfeeder.com/weixin/sanjieke01", "教育考试"),
        RssSource("罗辑思维", "https://plink.anyfeeder.com/weixin/luojisw", "教育考试"),

        // ===== 人文历史读物 =====
        RssSource("三联生活周刊", "https://plink.anyfeeder.com/weixin/lifeweek", "人文历史读物"),
        RssSource("人物", "https://plink.anyfeeder.com/weixin/renwumag1980", "人文历史读物"),
        RssSource("单读", "https://plink.anyfeeder.com/weixin/dandureading", "人文历史读物"),
        RssSource("南方周末", "https://plink.anyfeeder.com/weixin/nanfangzhoumo", "人文历史读物"),
        RssSource("南方周末-推荐", "https://plink.anyfeeder.com/infzm/recommends", "人文历史读物"),
        RssSource("南方周末-新闻", "https://plink.anyfeeder.com/infzm/news", "人文历史读物"),
        RssSource("历史研习社", "https://plink.anyfeeder.com/weixin/mingqinghistory", "人文历史读物"),
        RssSource("国家人文历史", "https://plink.anyfeeder.com/weixin/gjrwls", "人文历史读物"),
        RssSource("每日一文", "http://node2.feed43.com/mryw.xml", "人文历史读物"),
        RssSource("简书", "https://plink.anyfeeder.com/weixin/jianshuio", "人文历史读物"),
        RssSource("简书首页", "https://plink.anyfeeder.com/jianshu/home", "人文历史读物"),
        RssSource("观止·每日一文", "https://plink.anyfeeder.com/meiriyiwen", "人文历史读物"),
        RssSource("读库小报", "https://plink.anyfeeder.com/weixin/dukuxiaobao", "人文历史读物"),
        RssSource("青年文摘", "https://plink.anyfeeder.com/weixin/qnwzwx", "人文历史读物"),

        // ===== 科技互联网媒体 =====
        RssSource("36氪", "https://36kr.com/feed", "科技互联网媒体"),
        RssSource("IT之家", "https://www.ithome.com/rss/", "科技互联网媒体"),
        RssSource("品玩", "https://plink.anyfeeder.com/pingwest", "科技互联网媒体"),
        RssSource("奇客Solidot", "https://www.solidot.org/index.rss", "科技互联网媒体"),
        RssSource("少数派", "https://sspai.com/feed", "科技互联网媒体"),
        RssSource("数字尾巴", "https://plink.anyfeeder.com/dgtle", "科技互联网媒体"),
        RssSource("新智元", "https://plink.anyfeeder.com/weixin/AI_era", "科技互联网媒体"),
        RssSource("爱范儿", "https://www.ifanr.com/feed", "科技互联网媒体"),
        RssSource("腾讯科技", "https://plink.anyfeeder.com/weixin/qqtech", "科技互联网媒体"),
        RssSource("虎嗅", "https://rss.huxiu.com/", "科技互联网媒体"),
        RssSource("钛媒体", "https://www.tmtpost.com/feed", "科技互联网媒体"),

        // ===== 科学科普 =====
        RssSource("果壳网", "https://plink.anyfeeder.com/weixin/Guokr42", "科学科普"),
        RssSource("中国国家地理", "https://plink.anyfeeder.com/weixin/dili360", "科学科普"),
        RssSource("地球知识局", "https://plink.anyfeeder.com/weixin/diqiuzhishiju", "科学科普"),
        RssSource("物种日历", "https://plink.anyfeeder.com/weixin/guokrpac", "科学科普"),
        RssSource("环球科学", "https://plink.anyfeeder.com/weixin/ScientificAmerican", "科学科普"),
        RssSource("科学松鼠会", "https://plink.anyfeeder.com/weixin/SquirrelClub", "科学科普"),

        // ===== 体育运动 =====
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