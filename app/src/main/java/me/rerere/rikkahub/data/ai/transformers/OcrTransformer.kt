package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it.hasLocalImage() }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part -> part.replaceLocalImagesWithOcr() }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }


    private fun UIMessagePart.hasLocalImage(): Boolean = when (this) {
        is UIMessagePart.Image -> url.startsWith("file:") || AssetUri.isAsset(url)
        is UIMessagePart.Tool -> output.any { it.hasLocalImage() }
        else -> false
    }

    private suspend fun UIMessagePart.replaceLocalImagesWithOcr(): UIMessagePart = when (this) {
        is UIMessagePart.Image -> {
            if (url.startsWith("file:") || AssetUri.isAsset(url)) UIMessagePart.Text(performOcr(this)) else this
        }

        is UIMessagePart.Tool -> copy(
            output = output.map { it.replaceLocalImagesWithOcr() }
        )

        else -> this
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        val assetId = AssetUri.parse(part.url)
        val assetResolver = runCatching { get<AssetResolver>() }.getOrNull()
        if (assetId != null && assetResolver != null) {
            assetResolver.getOcrText(assetId)?.let { cached ->
                Log.i(TAG, "performOcr: Using asset OCR cache for $assetId")
                return cached
            }
        }
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val imagePart = if (assetResolver != null) {
            assetResolver.resolveImagePartForOcr(part, model) ?: part
        } else {
            part
        }
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(settings.ocrPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(imagePart.url))
                )
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText() ?: "[ERROR, OCR failed]"
        Log.i(TAG, "performOcr: $content")
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        if (assetId != null && assetResolver != null) {
            assetResolver.saveOcrText(assetId, ocrResult)
        }
        return ocrResult
    }.getOrElse {
        "[ERROR, OCR failed: $it]"
    }
}
