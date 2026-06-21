package com.cesia.input

/**
 * RSS 新闻源数据模型
 */
data class RssSource(
    val id: String,
    val name: String,
    val url: String,
    val category: String = "综合",
    val language: String = "zh",
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true
)

/**
 * RSS 解析后的单条新闻
 */
data class RssItem(
    val title: String,
    val link: String = "",
    val description: String = "",
    val pubDate: String = ""
)

/**
 * 默认推荐的 RSS 源
 */
val DEFAULT_RSS_SOURCES = listOf(
    RssSource("36kr", "36氪", "https://36kr.com/feed", "科技", "zh",
        listOf("科技", "AI", "创业", "互联网", "商业")),
    RssSource("ifanr", "爱范儿", "https://www.ifanr.com/feed", "科技", "zh",
        listOf("科技", "AI", "数码", "互联网", "产品")),
    RssSource("sspai", "少数派", "https://sspai.com/feed", "科技", "zh",
        listOf("科技", "数码", "互联网", "生活", "效率")),
    RssSource("huxiu", "虎嗅", "https://www.huxiu.com/feed", "财经", "zh",
        listOf("财经", "经济", "创业", "互联网", "商业")),
    RssSource("ithome", "IT之家", "https://www.ithome.com/rss/", "科技", "zh",
        listOf("科技", "数码", "硬件", "软件", "互联网")),
    RssSource("thepaper", "澎湃新闻", "https://www.thepaper.cn/rss", "中国", "zh",
        listOf("中国", "新闻", "时事", "社会", "法治")),
    RssSource("smzdm", "什么值得买", "https://www.smzdm.com/feed", "科技", "zh",
        listOf("科技", "数码", "生活", "消费", "推荐")),
    RssSource("zaobao_china", "联合早报·中国", "https://www.zaobao.com/realtime/china/rss", "中国", "zh",
        listOf("中国", "新加坡", "时事", "社会")),
    RssSource("reuters_world", "路透社", "https://www.reutersagency.com/feed/", "国际", "en",
        listOf("国际", "政治", "经济", "英文")),
    RssSource("bbc_zh", "BBC中文", "https://feeds.bbci.co.uk/zhongwen/simp/rss.xml", "国际", "zh",
        listOf("国际", "政治", "社会", "英文"))
)

val RSS_CATEGORIES = listOf("国际", "财经", "科技", "中国", "社会", "综合")
