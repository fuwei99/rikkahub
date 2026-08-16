package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
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
 */
object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {
    override val name: String = "DuckDuckGo"

    private const val ENDPOINT = "https://html.duckduckgo.com/html/"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            require(query.length < 500) {
                "DuckDuckGo does not accept queries longer than 499 characters"
            }

            val region = serviceOptions.region.ifBlank { "wt-wt" }
            val safeSearchValue = when (serviceOptions.safeSearch) {
                "off" -> "-2"
                "strict" -> "1"
                else -> "-1" // moderate
            }

            val formBuilder = FormBody.Builder()
                .add("q", query)
                .add("b", "")
                .add("kl", region)
            if (serviceOptions.timeRange.isNotBlank() && serviceOptions.timeRange != "all") {
                formBuilder.add("df", serviceOptions.timeRange)
            }

            val cookie = buildString {
                append("kl=$region")
                append("; p=$safeSearchValue")
                if (serviceOptions.timeRange.isNotBlank() && serviceOptions.timeRange != "all") {
                    append("; df=${serviceOptions.timeRange}")
                }
            }

            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language};q=0.9"

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(formBuilder.build())
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

            Log.i(TAG, "search: $query (kl=$region)")

            val response = httpClient.newCall(request).await()
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    error("DuckDuckGo request failed with status ${resp.code}")
                }
                resp.body.string()
            }

            val items = parseResults(body, commonOptions.resultSize)

            require(items.isNotEmpty()) {
                "Search failed: no results found"
            }

            SearchResult(items = items)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for DuckDuckGo"))
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
                val anchor = element.selectFirst("a.result__a") ?: return@mapNotNull null
                val title = anchor.text().trim()
                val link = anchor.attr("href")
                    .let { unwrapRedirect(it) }
                    .let { if (it.startsWith("http")) it else anchor.absUrl("href") }
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
     * 把 https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com 还原成真实地址
     */
    private fun unwrapRedirect(url: String): String {
        val absolute = if (url.startsWith("//")) "https:$url" else url
        if (!absolute.contains("uddg=")) return absolute
        return runCatching {
            val encoded = absolute.substringAfter("uddg=").substringBefore("&")
            URLDecoder.decode(encoded, "UTF-8")
        }.getOrElse { absolute }
    }
}
