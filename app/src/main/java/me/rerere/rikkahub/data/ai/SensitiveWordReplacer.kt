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
 * 敏感词映射替换器（[MessageSanitizer] 实现），按词库分类挂载。
 *
 * 词库：`config/sensitive_words/*.json`，**每个文件 = 一个词库**（词→替换词 map，用户自维护）。
 * 模型在「内容审核出口」里按需勾选挂载哪些词库（如 nsfw / politics，国内国外模型分开挂）。
 * 只替换挂载且模型标记了 [Model.hasContentModeration] 的请求，避免整包被审核拦截
 * （Gemini PROHIBITED_CONTENT / LiteLLM Content Exists Risk 等）。
 *
 * - **动态更新**：每次调用检查目录/文件 mtime，变更即重载 —— 工作区/手机直接改 json 即刻生效；
 * - **长词优先**：按 key 长度降序替换，避免短词先替换破坏长词匹配；
 * - 只替换文本：Text part / 记忆注入块 / 工具结果字符串，图片等媒体不动；
 * - 未勾选任何词库或模型无审核标记时零开销直通。
 */
class SensitiveWordReplacer(context: Context) : MessageSanitizer {
    private val dir: File = File(AppPaths.filesDir(context), "config/sensitive_words")

    @Volatile
    private var cache: Map<String, Map<String, String>>? = null
    private var cachedStamp: Long = -1L

    /** 当前可挂载的词库列表（文件名，不带 .json），供模型设置页勾选。 */
    fun listLibs(): List<String> {
        load()
        return cache?.keys?.sorted() ?: emptyList()
    }

    @Synchronized
    private fun load(): Map<String, Map<String, String>> {
        val stamp = dirStamp()
        val cached = cache
        if (cached != null && stamp == cachedStamp) return cached
        val loaded = dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                val lib = file.nameWithoutExtension
                val words = runCatching {
                    parseToJsonElement(file.readText()).jsonObject.entries
                        .mapNotNull { (k, v) ->
                            val value = v.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            k.takeIf { it.isNotBlank() }?.let { it to value }
                        }
                        .toMap()
                }.getOrDefault(emptyMap())
                if (words.isEmpty()) null else lib to words
            }
            ?.toMap() ?: emptyMap()
        cache = loaded
        cachedStamp = stamp
        return loaded
    }

    /** 目录 + 所有 json 文件 mtime 的汇总戳（任一变化即重载） */
    private fun dirStamp(): Long =
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.fold(dir.lastModified()) { acc, f -> maxOf(acc, f.lastModified()) }
            ?: dir.lastModified()

    private fun mergedFor(model: Model): Map<String, String> {
        if (model.sensitiveWordLibs.isEmpty()) return emptyMap()
        val libs = load()
        return buildMap {
            model.sensitiveWordLibs.forEach { lib -> putAll(libs[lib].orEmpty()) }
        }
    }

    /** 单段文本替换（长词优先）。词库为空原样返回。 */
    fun replace(text: String, libs: List<String> = emptyList()): String {
        val map = if (libs.isEmpty()) load().values.flattenToMap() else mergeLibs(libs)
        if (map.isEmpty() || text.isEmpty()) return text
        var out = text
        map.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
            out = out.replace(k, v)
        }
        return out
    }

    private fun mergeLibs(libs: List<String>): Map<String, String> {
        val all = load()
        return buildMap {
            libs.forEach { lib -> putAll(all[lib].orEmpty()) }
        }
    }

    private fun Collection<Map<String, String>>.flattenToMap(): Map<String, String> = buildMap {
        this@flattenToMap.forEach { putAll(it) }
    }

    override fun sanitize(model: Model, messages: List<UIMessage>): List<UIMessage> {
        if (!model.hasContentModeration) return messages
        val map = mergedFor(model)
        if (map.isEmpty()) return messages
        return messages.map { message ->
            val parts = message.parts.map { part -> part.sanitizePart(map) }
            val injection = message.memoryInjection?.let { replaceWith(it, map) }
            if (parts != message.parts || injection != message.memoryInjection) {
                message.copy(parts = parts, memoryInjection = injection)
            } else {
                message
            }
        }
    }

    private fun replaceWith(text: String, map: Map<String, String>): String {
        if (map.isEmpty() || text.isEmpty()) return text
        var out = text
        map.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
            out = out.replace(k, v)
        }
        return out
    }

    private fun UIMessagePart.sanitizePart(map: Map<String, String>): UIMessagePart = when (this) {
        is UIMessagePart.Text -> copy(text = replaceWith(text, map))
        // 工具结果（如 memory_tool 返回的记忆内容）下轮会重新进 prompt，同样脱敏
        is UIMessagePart.ToolResult -> copy(
            content = when (val c = content) {
                is JsonPrimitive -> if (c.isString) JsonPrimitive(replaceWith(c.content, map)) else c
                else -> c
            }
        )
        else -> this
    }
}
