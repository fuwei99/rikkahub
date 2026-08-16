package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoSearchServiceTest {

    /**
     * 结构照 html.duckduckgo.com/html 的真实产物：h2.result__title > a.result__a，
     * 广告走 y.js，正常结果被包成 //duckduckgo.com/l/?uddg=
     */
    private val sampleHtml = """
        <html><body>
        <div class="serp__results">
          <div class="result results_links results_links_deep result--ad">
            <div class="links_main"><h2 class="result__title">
              <a class="result__a" href="//duckduckgo.com/y.js?ad_provider=x">AD title</a>
            </h2><a class="result__snippet">ad snippet</a></div>
          </div>
          <div class="result results_links results_links_deep web-result">
            <div class="links_main"><h2 class="result__title">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=abc">First &amp; title</a>
            </h2><a class="result__snippet">First <b>snippet</b> text</a></div>
          </div>
          <div class="result results_links results_links_deep web-result">
            <div class="links_main"><h2 class="result__title">
              <a class="result__a" href="https://plain.example.org/page">Second title</a>
            </h2><a class="result__snippet">Second snippet</a></div>
          </div>
          <div class="result results_links results_links_deep web-result">
            <div class="links_main"><h2 class="result__title">
              <a class="result__a" href="https://plain.example.org/page">Duplicate</a>
            </h2><a class="result__snippet">dup</a></div>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `decodes uddg redirect and unescapes html entities`() {
        val items = DuckDuckGoSearchService.parseResults(sampleHtml, resultSize = 10)

        assertEquals(2, items.size)
        assertEquals("First & title", items[0].title)
        assertEquals("https://example.com/a?x=1", items[0].url)
        assertEquals("First snippet text", items[0].text)
    }

    @Test
    fun `skips y_js ads and duplicated urls`() {
        val items = DuckDuckGoSearchService.parseResults(sampleHtml, resultSize = 10)

        assertTrue(items.none { it.title.contains("AD") })
        assertTrue(items.none { it.url.contains("y.js") })
        assertEquals(1, items.count { it.url == "https://plain.example.org/page" })
    }

    @Test
    fun `respects result size`() {
        val items = DuckDuckGoSearchService.parseResults(sampleHtml, resultSize = 1)
        assertEquals(1, items.size)
    }

    @Test
    fun `captcha page throws`() {
        val captchaHtml = """
            <html><body><form action="/html/">
              <div class="filters"><table><tr><td>Please verify</td></tr></table></div>
            </form></body></html>
        """.trimIndent()

        val error = runCatching { DuckDuckGoSearchService.parseResults(captchaHtml, 10) }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("CAPTCHA"))
    }
}
