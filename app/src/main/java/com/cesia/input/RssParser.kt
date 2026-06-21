package com.cesia.input

import android.util.Log
import java.io.StringReader
import java.net.URL
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * RSS/Atom Feed 解析器
 * 支持 RSS 2.0 和 Atom 1.0 格式
 */
object RssParser {

    private const val TAG = "RssParser"

    /**
     * 解析 RSS/Atom feed，返回新闻条目列表
     */
    fun parse(feedXml: String, maxItems: Int = 5): List<RssItem> {
        if (feedXml.isBlank()) return emptyList()

        return try {
            // 尝试检测是 RSS 还是 Atom
            if (feedXml.contains("<feed") && feedXml.contains("xmlns=\"http://www.w3.org/2005/Atom\"")) {
                parseAtom(feedXml, maxItems)
            } else {
                parseRss(feedXml, maxItems)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 从 URL 抓取并解析 RSS
     */
    suspend fun fetchAndParse(feedUrl: String, maxItems: Int = 5): List<RssItem> {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "CesiaIME/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return emptyList()
                parse(body, maxItems)
            } else {
                Log.w(TAG, "HTTP ${response.code} for $feedUrl")
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch error for $feedUrl: ${e.message}")
            emptyList()
        }
    }

    private fun parseRss(xml: String, maxItems: Int): List<RssItem> {
        val items = mutableListOf<RssItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var inItem = false
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && items.size < maxItems) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name
                    if (tagName == "item") {
                        inItem = true
                        title = ""
                        link = ""
                        description = ""
                        pubDate = ""
                    } else if (inItem) {
                        currentTag = tagName
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem) {
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "title" -> title = text
                            "link" -> link = text
                            "description" -> description = text
                            "pubDate" -> pubDate = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tagName = parser.name
                    if (tagName == "item" && inItem) {
                        items.add(RssItem(title.cleanHtml(), link, description.cleanHtml(), pubDate))
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun parseAtom(xml: String, maxItems: Int): List<RssItem> {
        val items = mutableListOf<RssItem>()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xml.byteInputStream())

        val entries = doc.getElementsByTagName("entry")
        for (i in 0 until minOf(entries.length, maxItems)) {
            val entry = entries.item(i) as Element
            val title = entry.getElementsByTagName("title").item(0)?.textContent?.trim() ?: ""
            val link = (entry.getElementsByTagName("link").item(0) as? Element)?.getAttribute("href") ?: ""
            val summary = entry.getElementsByTagName("summary").item(0)?.textContent?.trim()
                ?: entry.getElementsByTagName("content").item(0)?.textContent?.trim() ?: ""
            val published = entry.getElementsByTagName("published").item(0)?.textContent?.trim()
                ?: entry.getElementsByTagName("updated").item(0)?.textContent?.trim() ?: ""
            items.add(RssItem(title.cleanHtml(), link, summary.cleanHtml(), published))
        }
        return items
    }

    /**
     * 清理 HTML 标签和多余空白
     */
    private fun String.cleanHtml(): String {
        return this
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
