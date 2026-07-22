package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import me.rerere.rikkahub.utils.toJavaLocalDateTime

class TemplateTransformer(
    private val engine: PebbleEngine,
    private val settingsStore: SettingsStore
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = engine.getTemplate(ctx.assistant.id.toString())
        val javaZoneId = ZoneId.systemDefault()
        val placeholderCtx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            model = ctx.model,
            assistant = ctx.assistant
        )

        return messages.map { message ->
            // 将消息本身的 createdAt 转化为 Java Instant/ZonedDateTime，保障基于消息原生的确定性求值
            val msgInstant = runCatching {
                Instant.ofEpochMilli(
                    message.createdAt.toJavaLocalDateTime().atZone(javaZoneId).toInstant().toEpochMilli()
                )
            }.getOrNull() ?: Instant.now()

            val msgZonedDateTime = msgInstant.atZone(javaZoneId)

            val msgDateStr = DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(msgZonedDateTime)

            val msgTimeStr = DateTimeFormatter
                .ofLocalizedTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(msgZonedDateTime)

            val msgDateTimeStr = DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(msgZonedDateTime)

            val placeholdersMap = DefaultPlaceholderProvider.placeholders.mapValues { (key, info) ->
                when (key) {
                    "cur_date" -> msgDateStr
                    "cur_time" -> msgTimeStr
                    "cur_datetime" -> msgDateTimeStr
                    else -> runCatching { info.resolver(placeholderCtx) }.getOrDefault("")
                }
            }

            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            val evalMap = mutableMapOf<String, Any>(
                                "message" to part.text,
                                "role" to message.role.name.lowercase(),
                                "time" to msgTimeStr,
                                "date" to msgDateStr,
                                "msg_id" to message.id.toString(),
                            )
                            evalMap.putAll(placeholdersMap)
                            template.evaluate(result, evalMap)
                            part.copy(
                                text = result.toString()
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        return StringReader(content)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
