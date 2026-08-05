package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
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
import me.rerere.rikkahub.data.ai.prompts.OCR_PROMPT_TAGS_PLACEHOLDER
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.repository.GenMediaRepository
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
                messages.map { message -> message.replaceImagesWithOcrAndAnnotate() }
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

    /**
     * 图片被 OCR 文本替换后, 传输层 metadata 里的 asset id 会随之丢弃,
     * 纯文本模型就拿不到「这张图是谁」的稳定地址。替换完成后补一行
     * `[image_asset_id]: # 1 <id>  # 2 <id>`(与 AssetIdAnnotationTransformer 同格式),
     * 让模型仍能精确指认第几张图, 也能在回复里用 asset:// 或裸 uuid 引用原图。
     */
    private suspend fun UIMessage.replaceImagesWithOcrAndAnnotate(): UIMessage {
        // 替换前先从原始 media part 收集 asset id(metadata 由 MediaResolver 留下)。
        val assetIds = parts.mapNotNull { it.assetIdOrNull() }
        val replaced = copy(
            parts = parts.map { part -> part.replaceLocalImagesWithOcr() }
        )
        if (assetIds.isEmpty() || AssetIdAnnotationTransformer.hasAnnotation(replaced.parts)) {
            return replaced
        }
        val line = AssetIdAnnotationTransformer.buildAnnotationLine(assetIds) ?: return replaced
        return replaced.copy(parts = replaced.parts + UIMessagePart.Text(line))
    }

    suspend fun performOcr(part: UIMessagePart.Image, force: Boolean = false): String = runCatching {
        var assetId = AssetUri.parse(part.url)
        val assetResolver = runCatching { get<AssetResolver>() }.getOrNull()
        if (assetId == null && assetResolver != null && part.url.startsWith("file:", ignoreCase = true)) {
            // 对话附件是 file:// 临时路径，AssetUri 解析不出 id，OCR 缓存会被整个绕过。
            // 附件都在 filesDir 下登记过，按相对路径反查回托管资产，
            // 已 OCR 过的图直接命中 ocrText 缓存，不再重复调用视觉模型。
            assetId = runCatching { assetResolver.findAssetByLocalPath(part.url)?.id }.getOrNull()
        }
        if (assetId != null && assetResolver != null) {
            if (!force) assetResolver.getOcrText(assetId)?.let { cached ->
                Log.i(TAG, "performOcr: Using asset OCR cache for $assetId")
                return cached
            }
        }
        // LLM preview 是原图的低清副本，内容一致：绝不单独再花一次 OCR 调用。
        // 正常情况 OCR 结果已同步到 preview(ocrText)，这里兜底去原图找。
        if (assetId != null && assetResolver != null) {
            val asset = runCatching { assetResolver.getAsset(assetId) }.getOrNull()
            if (asset?.folder == FileFolders.LLM_PREVIEWS) {
                val originalId = runCatching {
                    get<GenMediaRepository>().getAllMediaList()
                        .firstOrNull { it.previewAssetId == assetId }
                        ?.originalAssetId
                }.getOrNull()
                if (originalId != null && !force) {
                    runCatching { assetResolver.getOcrText(originalId) }
                        .getOrNull()
                        ?.let { Log.i(TAG, "performOcr: Reusing original asset OCR for preview $assetId"); return it }
                }
                return "[Image]"
            }
        }
        // Check cache first
        if (!force) cache.get(part.url)?.let { cachedResult ->
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

        // 标签白名单按 asset 所属分类过滤：作用域覆盖该分类的标签；空作用域 = 未使用，不进白名单。
        // 拿不到 asset(比如纯 file:// 引用)时给全部已使用的标签。
        val asset = if (assetId != null && assetResolver != null) {
            runCatching { assetResolver.getAsset(assetId) }.getOrNull()
        } else null
        val folder = asset?.folder
        val allowedTags = settings.imageTags
            .filter { it.scopes.isNotEmpty() && (folder == null || folder in it.scopes) }
        val basePrompt = settings.ocrPrompt.replace(
            OCR_PROMPT_TAGS_PLACEHOLDER,
            allowedTags.joinToString(", ") { it.name }.ifBlank { "(none)" },
        )
        // AI 绘制的图片：把生成 prompt 作为参考注入，帮模型理解画面意图。
        // 但 prompt 只是辅助上下文 —— 最终描述/命名/标签必须以生成的图片内容为准。
        val prompt = asset?.prompt?.takeIf { it.isNotBlank() }?.let { genPrompt ->
            """
            $basePrompt

            <reference_prompt>
            This image was AI-generated with the following prompt. Use it ONLY as context to help understand the image; the final description, file name and tags must be based on what is actually visible in the generated result, not on the prompt itself.
            $genPrompt
            </reference_prompt>
            """.trimIndent()
        } ?: basePrompt

        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(prompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(imagePart.url))
                )
            ),
            params = TextGenerationParams(
                model = model,
                // 思考强度与翻译同款：0 = 关，由用户在图库 OCR 设置里调
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.ocrThinkingBudget),
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText() ?: "[ERROR, OCR failed]"
        Log.i(TAG, "performOcr: $content")

        val parsed = OcrResultParser.parse(content, allowedTags.map { it.name })
        // 只把 description 喂给对话：名字和标签是相册用的元数据，
        // 塞进上下文只会挤占 token 并干扰模型。
        val ocrResult = """
            <image_file_ocr>
               ${parsed.description}
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Cache the result
        cache.put(part.url, ocrResult)
        if (assetId != null && assetResolver != null) {
            val matchedTags = parsed.tags.mapNotNull { name ->
                allowedTags.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
            assetResolver.saveOcrResult(
                assetId = assetId,
                ocrText = ocrResult,
                description = parsed.description,
                nameZh = parsed.nameZh,
                nameEn = parsed.nameEn,
                tagIds = matchedTags.map { it.id.toString() },
                // 写进图片文件里的是标签名而不是 uuid：文件被导出后 uuid 谁也解不开
                tagNames = matchedTags.map { it.name },
            )
        }
        return ocrResult
    }.getOrElse {
        "[ERROR, OCR failed: $it]"
    }
}
