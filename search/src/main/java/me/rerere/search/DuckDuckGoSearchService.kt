package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Locale

private const val TAG = "DuckDuckGoSearchService"

/**
 * DuckDuckGo 无 JS 版 (html.duckduckgo.com/html) 抓取实现。
 *
 * - 无需 API Key，POST 表单提交，第一页不需要 vqd
 * - kl = 地区代码（wt-wt 为全部地区），df = 时间范围（d/w/m/y）
 * - 结果链接可能被包装为 /l/?uddg=<encoded>，需要解码还原
 * - 广告链接走 y.js，直接跳过
 * - 自带限流（默认 30 次/分钟搜索、20 次/分钟抓取），避免触发反爬
 */
object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {
    override val name: String = "DuckDuckGo"

    private const val ENDPOINT = "https://html.duckduckgo.com/html/"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 抓取正文的长度上限，超出截断，避免塞爆上下文 */
    private const val MAX_SCRAPE_LENGTH = 8000

    private val searchRateLimiter = RateLimiter(requestsPerMinute = 30)
    private val scrapeRateLimiter = RateLimiter(requestsPerMinute = 20)

    @Composable
    override fun Description() {
        Text(stringResource(R.string.duckduckgo_desc))
    }

    override fun parameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("query is required")
            require(query.length < 500) {
                "DuckDuckGo does not accept queries longer than 499 characters"
            }

            searchRateLimiter.acquire()

            val region = serviceOptions.region.trim()
            val timeRange = serviceOptions.timeRange.takeIf { it.isNotBlank() && it != "all" }
            val safeSearchValue = when (serviceOptions.safeSearch) {
                "off" -> "-2"
                "strict" -> "1"
                else -> "-1" // moderate
            }

            val formBody = FormBody.Builder()
                .add("q", query)
                .add("b", "")
                // kl 留空 = 跟随 DDG 自动判断，避免强塞地区反而拿不到结果
                .add("kl", if (region == "auto") "" else region)
                .apply {
                    if (timeRange != null) add("df", timeRange)
                }
                .build()

            val cookie = buildString {
                if (region.isNotBlank() && region != "auto") append("kl=$region; ")
                append("p=$safeSearchValue")
                if (timeRange != null) append("; df=$timeRange")
            }

            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language};q=0.9"

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Origin", "https://html.duckduckgo.com")
                .header("Referer", "https://html.duckduckgo.com/")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1")
                .header("Cookie", cookie)
                .build()

            Log.i(TAG, "search: $query (kl=$region, df=$timeRange)")

            val body = httpClient.newCall(request).await().use { resp ->
                if (!resp.isSuccessful) {
                    error("DuckDuckGo request failed with status ${resp.code}")
                }
                resp.body.string()
            }

            val items = parseResults(body, commonOptions.resultSize)

            require(items.isNotEmpty()) {
                "No results found. This may be caused by DuckDuckGo's bot detection, " +
                    "or the query has no match. Try rephrasing it or retry in a few minutes."
            }

            SearchResult(items = items)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("url is required")

            scrapeRateLimiter.acquire()
            Log.i(TAG, "scrape: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                .build()

            val html = httpClient.newCall(request).await().use { resp ->
                if (!resp.isSuccessful) {
                    error("Failed to fetch the page (${resp.code})")
                }
                resp.body.string()
            }

            val doc = Jsoup.parse(html, url)
            // 剔掉噪音节点再取正文
            doc.select("script, style, noscript, nav, header, footer, aside, iframe, svg").remove()

            val content = doc.body().text()
                .replace(Regex("\\s+"), " ")
                .trim()
                .let {
                    if (it.length > MAX_SCRAPE_LENGTH) {
                        it.take(MAX_SCRAPE_LENGTH) + "... [content truncated]"
                    } else {
                        it
                    }
                }

            require(content.isNotBlank()) { "No readable content extracted from $url" }

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = content,
                        metadata = ScrapedResultMetadata(
                            title = doc.title().ifBlank { null },
                            description = doc.selectFirst("meta[name=description]")
                                ?.attr("content")
                                ?.ifBlank { null },
                            language = doc.selectFirst("html")?.attr("lang")?.ifBlank { null }
                        )
                    )
                )
            )
        }
    }

    /**
     * 解析 DDG-html 返回的页面（抽出来方便做纯离线单测）
     */
    internal fun parseResults(html: String, resultSize: Int): List<SearchResultItem> {
        val doc = Jsoup.parse(html, ENDPOINT)

        require(!doc.isCaptcha()) {
            "DuckDuckGo returned an anti-bot challenge (CAPTCHA), please retry later"
        }

        return doc.select("div.result, div.web-result")
            .asSequence()
            .filterNot { it.hasClass("result--ad") || it.hasClass("result--ad-u") }
            .mapNotNull { element ->
                val anchor = element.selectFirst("h2.result__title a.result__a")
                    ?: element.selectFirst("a.result__a")
                    ?: return@mapNotNull null
                val rawHref = anchor.attr("href")
                // 广告统一走 y.js，直接跳过
                if (rawHref.contains("y.js")) return@mapNotNull null

                val link = unwrapRedirect(rawHref)
                    .let { if (it.startsWith("http")) it else anchor.absUrl("href") }
                val title = anchor.text().trim()
                val snippet = element.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                if (title.isBlank() || link.isBlank()) return@mapNotNull null

                SearchResultItem(
                    title = title,
                    url = link,
                    text = snippet
                )
            }
            .distinctBy { it.url }
            .take(resultSize)
            .toList()
    }

    /**
     * DDG 的反爬墙：返回的是一张带 CAPTCHA 表单的页面，而不是空结果列表。
     * 特征参考 searxng 的实现：body/form 下的 filters 表格。
     */
    private fun Document.isCaptcha(): Boolean {
        if (selectFirst("body > form div.filters table") != null) return true
        if (selectFirst("#challenge-form, .anomaly-modal__mask, form[action*=challenge]") != null) return true
        if (select("div.result").isNotEmpty()) return false
        val text = body().text()
        return text.contains("unusual traffic", ignoreCase = true) ||
            text.contains("anomaly", ignoreCase = true)
    }

    /**
     * 把 //duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com 还原成真实地址
     */
    private fun unwrapRedirect(url: String): String {
        val absolute = if (url.startsWith("//")) "https:$url" else url
        if (!absolute.contains("uddg=")) return absolute
        return runCatching {
            val encoded = absolute.substringAfter("uddg=").substringBefore("&")
            URLDecoder.decode(encoded, "UTF-8")
        }.getOrElse { absolute }
    }

    /**
     * 滑动窗口限流：一分钟内超过 requestsPerMinute 次就等到窗口让出位置。
     * 抄 Operit 的思路，DDG 对高频请求相当敏感。
     */
    private class RateLimiter(private val requestsPerMinute: Int) {
        private val mutex = Mutex()
        private val timestamps = ArrayDeque<Long>()

        suspend fun acquire() {
            val waitTime = mutex.withLock {
                val now = System.currentTimeMillis()
                while (timestamps.isNotEmpty() && now - timestamps.first() >= 60_000L) {
                    timestamps.removeFirst()
                }
                val wait = if (timestamps.size >= requestsPerMinute) {
                    60_000L - (now - timestamps.first())
                } else {
                    0L
                }
                // 提前占位，避免并发下同时挤过阈值
                timestamps.addLast(now + wait)
                wait
            }
            if (waitTime > 0) {
                Log.i(TAG, "rate limited, waiting ${waitTime}ms")
                delay(waitTime)
            }
        }
    }
}
