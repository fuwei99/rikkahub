package me.rerere.rikkahub.data.ai.transformers

/**
 * OCR 结构化输出的解析结果。
 *
 * [description] 一定非空 —— 模型不听话没输出标签时，整段原文就是描述，
 * 这样对话链路永远拿得到东西，不会因为格式跑偏而丢掉 OCR 能力。
 */
data class OcrResult(
    val description: String,
    val nameZh: String? = null,
    val nameEn: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * 解析 OCR 的 XML 输出。
 *
 * 刻意用正则而不是 XML parser：模型输出经常不是合法 XML
 * （标签没闭合、外面裹 ```xml、描述里带 < > 之类），
 * 真正的 parser 一遇到就整体抛异常，反而比宽松匹配脆弱。
 */
object OcrResultParser {
    private fun tagRegex(tag: String) = Regex(
        "<$tag>(.*?)</$tag>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val DESCRIPTION = tagRegex("description")
    private val NAME_ZH = tagRegex("name_zh")
    private val NAME_EN = tagRegex("name_en")
    private val TAGS = tagRegex("tags")

    /**
     * @param allowedTags 用户维护的标签白名单（name -> 原始名）。模型给出白名单外的标签一律丢弃。
     */
    fun parse(raw: String, allowedTags: Collection<String> = emptyList()): OcrResult {
        val text = raw.trim()
        val description = DESCRIPTION.find(text)?.groupValues?.get(1)?.trim()
        val nameZh = NAME_ZH.find(text)?.groupValues?.get(1)?.cleanName()
        val nameEn = NAME_EN.find(text)?.groupValues?.get(1)?.cleanName()

        // 大小写不敏感地对齐到白名单里的原始写法，避免 "nsfw" / "NSFW" 变成两个标签
        val allowedByLower = allowedTags.associateBy { it.lowercase() }
        val tags = TAGS.find(text)?.groupValues?.get(1)
            ?.split(',', '，', '、')
            ?.mapNotNull { candidate ->
                val key = candidate.trim().trim('#').lowercase()
                if (key.isEmpty()) null else allowedByLower[key]
            }
            ?.distinct()
            ?: emptyList()

        return OcrResult(
            // 没解析出 description 就退回整段原文，保证对话链路不受格式影响
            description = description?.takeIf { it.isNotBlank() } ?: text,
            nameZh = nameZh,
            nameEn = nameEn,
            tags = tags,
        )
    }

    /**
     * 清理模型给的名字：去掉路径分隔符和文件系统保留字符。
     * 这个名字虽然只存 DB 不落盘，但会被导出到系统相册当文件名，
     * 带 / 或 : 会让 MediaStore 插入直接失败。
     */
    private fun String.cleanName(): String? {
        val cleaned = trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]"), "")
            .trim()
            .take(40)
        return cleaned.takeIf { it.isNotBlank() }
    }
}
