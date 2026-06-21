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
    RssSource("zaobao_china", "联合早报·中国", "https://rsshub.app/zaobao/realtime/china", "中国", "zh",
        listOf("中国", "新加坡", "时事", "社会")),
    RssSource("zaobao_world", "联合早报·国际", "https://rsshub.app/zaobao/realtime/world", "国际", "zh",
        listOf("国际", "政治", "战争", "外交")),
    RssSource("bbc_zh", "BBC中文", "https://rsshub.app/bbc/zhongwen/simp", "国际", "zh",
        listOf("国际", "政治", "社会", "英文")),
    RssSource("reuters_world", "路透社", "https://rsshub.app/reuters/world", "国际", "en",
        listOf("国际", "政治", "经济", "英文")),
    RssSource("36kr", "36氪", "https://rsshub.app/36kr/newsflashes", "科技", "zh",
        listOf("科技", "AI", "创业", "互联网")),
    RssSource("hn", "Hacker News", "https://rsshub.app/hacker-news", "科技", "en",
        listOf("科技", "AI", "编程", "软件", "英文")),
    RssSource("thepaper", "澎湃新闻", "https://rsshub.app/thepaper/channel/25951", "中国", "zh",
        listOf("中国", "新闻", "时事", "社会")),
    RssSource("caixin", "财新", "https://rsshub.app/caixin/article", "财经", "zh",
        listOf("财经", "经济", "金融", "股市")),
    RssSource("wsj_markets", "华尔街日报", "https://rsshub.app/wsj/markets", "财经", "en",
        listOf("财经", "经济", "股市", "英文")),
    RssSource("cnn_world", "CNN World", "https://rsshub.app/cnn/world", "国际", "en",
        listOf("国际", "政治", "社会", "英文")),
    RssSource("ifanr", "爱范儿", "https://rsshub.app/ifanr", "科技", "zh",
        listOf("科技", "AI", "数码", "互联网")),
    RssSource("solidot", "Solidot", "https://rsshub.app/solidot", "科技", "zh",
        listOf("科技", "开源", "软件", "AI"))
)

val RSS_CATEGORIES = listOf("国际", "财经", "科技", "中国", "社会", "综合")
