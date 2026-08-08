package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.isString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.parseToJsonElement
import me.rerere.ai.provider.MessageSanitizer
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.AppPaths
import java.io.File

/**
 * 敏感词映射替换器（[MessageSanitizer] 实现）。
 *
 * 读取 `config/sensitive_word_map.json`（词→替换词 map，用户自维护），对发往
 * 「带内容审核模型」（[Model.hasContentModeration]=true）的请求文本做替换，
 * 避免整包被审核拦截（Gemini PROHIBITED_CONTENT / LiteLLM Content Exists Risk 等）。
 *
 * - **动态更新**：每次调用检查文件 mtime，变更即重载 —— 工作区/手机直接改 json 即刻生效，无需重启；
 * - **长词优先**：按 key 长度降序替换，避免短词先替换破坏长词匹配；
 * - **只替换文本**：Text part / 记忆注入块 / 工具结果字符串，图片等媒体不动；
 * - 词库为空或模型无审核标记时零开销直通。
 */
class SensitiveWordReplacer(context: Context) : MessageSanitizer {
    private val file: File = File(AppPaths.filesDir(context), "config/sensitive_word_map.json")

    @Volatile
    private var cache: Map<String, String>? = null
    private var cachedStamp: Long = -1L

    @Synchronized
    private fun load(): Map<String, String> {
        val stamp = file.lastModified()
        val cached = cache
        if (cached != null && stamp == cachedStamp) return cached
        val loaded = runCatching {
            parseToJsonElement(file.readText()).jsonObject.entries
                .mapNotNull { (k, v) ->
                    val value = v.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    k.takeIf { it.isNotBlank() }?.let { it to value }
                }
                .toMap()
        }.getOrDefault(emptyMap())
        cache = loaded
        cachedStamp = stamp
        return loaded
    }

    /** 单段文本替换（长词优先）。词库为空原样返回。 */
    fun replace(text: String): String {
        val map = load()
        if (map.isEmpty() || text.isEmpty()) return text
        var out = text
        map.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
            out = out.replace(k, v)
        }
        return out
    }

    override fun sanitize(model: Model, messages: List<UIMessage>): List<UIMessage> {
        if (!model.hasContentModeration) return messages
        val map = load()
        if (map.isEmpty()) return messages
        return messages.map { message ->
            val parts = message.parts.map { part -> part.sanitizePart() }
            val injection = message.memoryInjection?.let { replace(it) }
            if (parts != message.parts || injection != message.memoryInjection) {
                message.copy(parts = parts, memoryInjection = injection)
            } else {
                message
            }
        }
    }

    private fun UIMessagePart.sanitizePart(): UIMessagePart = when (this) {
        is UIMessagePart.Text -> copy(text = replace(text))
        // 工具结果（如 memory_tool 返回的记忆内容）下轮会重新进 prompt，同样脱敏
        is UIMessagePart.ToolResult -> copy(
            content = when (val c = content) {
                is JsonPrimitive -> if (c.isString) JsonPrimitive(replace(c.content)) else c
                else -> c
            }
        )
        else -> this
    }
}
