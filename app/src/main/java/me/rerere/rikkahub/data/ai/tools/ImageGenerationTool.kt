package me.rerere.rikkahub.data.ai.tools

import android.util.Base64
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.defaultImageParameterBodies
import me.rerere.ai.provider.ImageLoraSelection
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.util.toImageDataUriOrRemote
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findImageProvider
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetRef
import me.rerere.rikkahub.data.files.AssetReferences
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.sanitizeFileName
import java.net.URL
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid
import android.content.Context
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref

data class ImageReference(
    val id: String,
    val source: String,
)

/**
 * 读取工作区 / 挂载点文件字节的能力, 由调用方(ChatService)注入。
 * 返回 (字节, mime); 文件不存在或不可读时抛异常。
 *
 * 用 fun interface 而非 typealias: 便于在调用方直接 SAM 构造, 且能挂 suspend。
 */
fun interface ImageFileReader {
    suspend operator fun invoke(path: String): Pair<ByteArray, String>
}

/**
 * **Legacy only.** 为会话里已存在的图片重建 `<role>-round-<N>-ref-<M>.png` 形式的 round tag。
 *
 * 该编址方式已于 2026-08 废除: 轮号由上下文推导, 会随用户连发消息、分支重生成、
 * 上下文裁剪、以及工具图片回灌时插入的临时 USER 消息而漂移, 同一张图在不同时刻
 * 会算出不同的 tag。新逻辑一律使用 Asset ID(见 [AssetReferences])。
 *
 * 这里仅保留**读取**兼容: 让老会话里 AI 已经写下的 `![](assistant-round-1-ref-1.png)`
 * 仍能渲染, 以及 `reference_images` 仍能接受老 tag。请勿在新代码中依赖它。
 */
fun buildConversationImageReferences(messages: List<UIMessage>): List<ImageReference> {
    var round = 0
    return messages.flatMap { message ->
        if (message.role == MessageRole.USER) round++
        val prefix = if (message.role == MessageRole.USER) "user" else "assistant"
        message.collectImageSources().mapIndexed { index, source ->
            ImageReference("$prefix-round-${round.coerceAtLeast(1)}-ref-${index + 1}.png", source)
        }
    }
}

private fun UIMessage.collectImageSources(): List<String> {
    fun UIMessagePart.collect(): List<String> = when (this) {
        is UIMessagePart.Image -> listOf(url).filter { it.isNotBlank() }
        is UIMessagePart.Text -> {
            if (text.contains("asset_uri") || text.contains("preview_asset_uri")) {
                runCatching {
                    val json = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(text).jsonObject
                    json["preview_asset_uri"]?.jsonPrimitive?.contentOrNull
                        ?: json["asset_uri"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.takeIf { AssetUri.isAsset(it) }?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }
        }
        is UIMessagePart.Tool -> output.flatMap { it.collect() }
        else -> emptyList()
    }
    return parts.flatMap { it.collect() }
}


private fun Settings.selectedImageGenerationModels(): List<Model> {
    val selectedIds = imageGenerationModelIds.ifEmpty { listOf(imageGenerationModelId) }.distinct()
    val selectedModels = selectedIds.mapNotNull { findModelById(it) }
    return selectedModels.ifEmpty { listOfNotNull(findModelById(imageGenerationModelId)) }
}

private fun Model.supportsConfiguredLoras(): Boolean =
    imageCapabilities.loraProtocol != WaveSpeedLoraProtocol.NONE &&
        waveSpeedLoras.isNotEmpty()

private fun Model.effectiveMaxLoras(): Int = imageCapabilities.maxLoras.takeIf { it > 0 } ?: 3

private fun Model.loraLimitDescription(): String =
    if (effectiveMaxLoras() == 1) {
        "at most 1 LoRA per request"
    } else {
        "at most ${effectiveMaxLoras()} LoRAs per request"
    }

private fun mimeToImageExt(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    "image/gif" -> ".gif"
    else -> ".png"
}

private fun Model.toImageToolDescription(): String = buildString {
    append("- $modelId: $displayName")
    imageDescription.trim().takeIf { it.isNotBlank() }?.let { description ->
        append("; user description: ")
        append(description)
    }
    if (imageCapabilities.supportsImageEditing) {
        append("; supports reference-image editing")
        if (imageCapabilities.maxReferenceImages > 0) {
            append(" (max ${imageCapabilities.maxReferenceImages} references)")
        }
    }
    if (supportsConfiguredLoras()) {
        append("; LoRA request limit: ${loraLimitDescription()}")
        append("; configured LoRAs available for the model to choose from: ")
        append(waveSpeedLoras.joinToString { "${it.id} (${it.explanation})" })
    }
    if (imageParameters.isNotEmpty()) {
        append("; custom parameters: ")
        append(imageParameters.joinToString {
            "${it.key} (${it.explanation}; default: ${it.defaultValue ?: "none"})"
        })
    }
}


private suspend fun prepareReferenceImageForModel(
    rawSource: String,
    targetModel: Model,
    filesManager: FilesManager,
): String {
    val supportsUrl = Modality.URL in targetModel.inputModalities
    val supportsImage = targetModel.imageCapabilities.supportsImageEditing || Modality.IMAGE in targetModel.inputModalities
    require(supportsUrl || supportsImage) {
        "The selected image model does not support URL or image reference input"
    }

    val assetResolver = getKoin().get<AssetResolver>()
    val indexedPart = assetResolver.indexPartForStorage(UIMessagePart.Image(rawSource)) as? UIMessagePart.Image
        ?: error("Failed to index reference image: $rawSource")

    val resolvedPart = assetResolver.resolvePartForModel(indexedPart, targetModel) as? UIMessagePart.Image
        ?: error("Image reference asset is unavailable for model")

    val resolvedUrl = resolvedPart.url
    if (supportsUrl && (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))) {
        return resolvedUrl
    }

    if (resolvedUrl.startsWith("file://")) {
        val file = resolvedUrl.toUri().toFile()
        return file.absolutePath.toImageDataUriOrRemote()
    }

    return resolvedUrl
}

/** 图片扩展名 → mime, 用于路径引用 */
private fun pathToImageMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> "image/png"
}

/**
 * 把模型给的一个图片引用解析成 `asset://managed-files/<uuid>`(legacy tag 则返回其原始 source)。
 *
 * 接受四种形态, 判定顺序见 [AssetReferences.classify]:
 * - Asset ID / `asset://managed-files/<uuid>` —— 唯一推荐形态
 * - 工作区或挂载点内的文件路径 —— 读入后入库为 Asset
 * - 外部 http(s) 链接
 * - 旧的 round tag —— 仅兼容老会话
 */
private suspend fun resolveReferenceInput(
    raw: String,
    legacyReferences: List<ImageReference>,
    imageFileReader: ImageFileReader?,
): String {
    val ref = AssetReferences.classify(raw) ?: error("Empty image reference")
    return when (ref) {
        is AssetRef.Id -> {
            val database = getKoin().get<AppDatabase>()
            val asset = database.managedFileDao().getById(ref.assetId)?.takeUnless { it.deleted }
                ?: error("Unknown asset id: ${ref.assetId}")
            AssetUri.fromId(asset.id)
        }

        is AssetRef.Legacy -> legacyReferences.find { it.id.equals(ref.tag, ignoreCase = true) }?.source
            ?: error(
                "Unknown legacy image tag: ${ref.tag}. Round tags are deprecated; " +
                    "use the asset id shown next to the image instead."
            )

        is AssetRef.Remote -> ref.url

        is AssetRef.Path -> {
            val reader = imageFileReader
                ?: error(
                    "File paths are not available as image references in this conversation " +
                        "(no workspace attached). Use an asset id instead."
                )
            val (bytes, detectedMime) = runCatching { reader(ref.path) }.getOrElse { e ->
                error("Cannot read image file '${ref.path}': ${e.message ?: "read failed"}")
            }
            require(bytes.isNotEmpty()) { "Image file is empty: ${ref.path}" }
            val mime = detectedMime.takeIf { it.startsWith("image/") } ?: pathToImageMime(ref.path)
            val asset = getKoin().get<AssetResolver>().createFromBytes(
                bytes = bytes,
                displayName = ref.path.substringAfterLast('/').ifBlank { "reference.png" },
                mimeType = mime,
                folder = FileFolders.UPLOAD,
                description = "Image reference from path: ${ref.path}",
            )
            AssetUri.fromId(asset.id)
        }
    }
}

/**
 * Creates the image-generation tool bound to image models explicitly selected by the user.
 */
fun createImageGenerationTool(
    settings: Settings,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    imageReferences: List<ImageReference> = emptyList(),
    imageFileReader: ImageFileReader? = null,
): Tool {
    // The image tool is intentionally limited to the image model(s) selected by the user.
    // Other configured models are neither disclosed to the LLM nor selectable by a tool call.
    val selectedModels = settings.selectedImageGenerationModels()
    if (selectedModels.isEmpty()) {
        throw IllegalStateException("No selected image generation model configured")
    }
    val hasMultipleModels = selectedModels.size > 1
    val hasConfiguredLoraModels = selectedModels.any { it.supportsConfiguredLoras() }
    // 参考图能力只取决于模型是否支持图生图 —— 不再取决于「会话里是否已有图片」,
    // 因为现在还可以直接给文件路径。
    val supportsReferenceImages = selectedModels.any { it.imageCapabilities.supportsImageEditing }

    val selectedModelDescription = selectedModels.joinToString("\n") { it.toImageToolDescription() }
    val selectedModelIdsDescription = selectedModels.joinToString { it.modelId }

    return Tool(
        name = "image_generation",
        description = """
            Generate images from a text prompt${if (supportsReferenceImages) ", or edit an existing image" else ""}.
            Use for draw / paint / visualize requests${if (supportsReferenceImages) "; to edit / colorize / redraw an existing image, pass reference_images" else ""}.

            ## Available models
            $selectedModelDescription
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed description of the image to generate.")
                    })
                    if (hasMultipleModels) {
                        put("model", buildJsonObject {
                            put("type", "string")
                            put("description", "Model to use, one of: $selectedModelIdsDescription. Defaults to the first.")
                        })
                    }
                    if (hasConfiguredLoraModels) {
                        put("loras", buildJsonObject {
                            put("type", "array")
                            put("description", "[{id: string, scale: number}] — only for models listing LoRAs above, using those exact ids. Never exceed that model's stated LoRA limit.")
                            put("items", buildJsonObject {
                                put("type", "object")
                            })
                        })
                    }
                    if (supportsReferenceImages) {
                        put("reference_images", buildJsonObject {
                            put("type", "array")
                            put(
                                "description",
                                "Images to edit. Each entry is either the asset id of an image in this " +
                                    "conversation (`asset://managed-files/<uuid>`), or a readable file path " +
                                    "(e.g. /workspace/a.png, /mnt/obsidian/b.jpg). Never invent an id. " +
                                    "Never exceed the model's stated reference limit."
                            )
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        })
                    }
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("description", "Values for the custom parameters listed for the selected model.")
                    })
                },
                required = listOf("prompt")
            )
        },
        execute = { args ->
            val promptVal = args.jsonObject["prompt"]?.jsonPrimitive?.contentOrNull ?: error("Missing prompt")
            val requestedModelId = args.jsonObject["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val targetModel = if (requestedModelId.isBlank()) {
                selectedModels.first()
            } else {
                selectedModels.find {
                    it.modelId == requestedModelId ||
                        it.displayName == requestedModelId ||
                        it.id.toString() == requestedModelId
                } ?: error("Unknown or unselected image model: $requestedModelId")
            }
            val requestedLoras = args.jsonObject["loras"]?.jsonArray.orEmpty()
            val requestedReferenceIds = args.jsonObject["reference_images"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull ?: error("Reference image ID must be a string") }
            val requestedParameters = args.jsonObject["parameters"]?.jsonObject.orEmpty()

            val targetProviderSetting = targetModel.findImageProvider(settings.imageProviders)
                ?: throw IllegalStateException("Image Provider not found for model: ${targetModel.displayName}")
            val resolvedReferences = requestedReferenceIds.map { id ->
                ImageReference(id, resolveReferenceInput(id, imageReferences, imageFileReader))
            }
            if (resolvedReferences.isNotEmpty()) {
                require(targetModel.imageCapabilities.supportsImageEditing) {
                    "The selected image model does not support reference-image editing"
                }
                val maxReferences = targetModel.imageCapabilities.maxReferenceImages
                require(maxReferences <= 0 || resolvedReferences.size <= maxReferences) {
                    "The selected image model allows at most $maxReferences reference images"
                }
            }

            if (requestedLoras.isNotEmpty()) {
                require(targetModel.supportsConfiguredLoras()) {
                    "The selected image model does not support configured LoRA selections"
                }
            }
            val loras = requestedLoras.map { item ->
                val lora = item.jsonObject
                val id = lora["id"]?.jsonPrimitive?.contentOrNull ?: error("LoRA id is required")
                val configured = targetModel.waveSpeedLoras.find { it.id == id }
                    ?: error("Unknown LoRA '$id' for model ${targetModel.displayName}")
                val scale = lora["scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f
                ImageLoraSelection(path = configured.url, scale = scale)
            }
            // Apply model defaults first, then any user-configured advanced body, then the tool call.
            // Registered parameters provide documentation and defaults, but unregistered parameters
            // are intentionally forwarded too so users can use a provider field before registering it.
            val customBody = targetModel.defaultImageParameterBodies() +
                targetModel.customBodies + requestedParameters.map { (key, value) ->
                CustomBody(key = key, value = value)
            }

            val params = ImageGenerationParams(
                model = targetModel,
                prompt = promptVal,
                numOfImages = 1,
                customHeaders = targetModel.customHeaders,
                customBody = customBody,
                loras = loras,
            )

            val imageItem = runBlocking {
                val provider = providerManager.getImageProviderByType(targetProviderSetting)
                val items = if (resolvedReferences.isEmpty()) {
                    provider.generateImage(targetProviderSetting, params).toList()
                } else {
                    val preparedReferences = resolvedReferences.map { reference ->
                        prepareReferenceImageForModel(reference.source, targetModel, filesManager)
                    }
                    provider.editImage(
                        targetProviderSetting,
                        ImageEditParams(
                            model = targetModel,
                            prompt = promptVal,
                            images = preparedReferences,
                            customHeaders = targetModel.customHeaders,
                            customBody = customBody,
                            loras = loras,
                        ),
                    ).toList()
                }
                // Ignore streaming previews; only final images are results.
                items.lastOrNull { !it.partial }
            } ?: throw IllegalStateException("Failed to generate image: Empty response from provider")

            // Asset 化：生图工具输出只给聊天写 asset://managed-files/<uuid>。
            // R2 是资产的后台同步副本，不阻塞工具结果返回。
            val assetResolver = getKoin().get<AssetResolver>()
            val database = getKoin().get<AppDatabase>()
            val remoteUrl = imageItem.url

            val llmImageLocation: String
            val originalUrl: String?
            val originalAssetId: String
            val previewAssetId: String
            val displayHistoryPath: String

            val timestamp = System.currentTimeMillis()
            val modelName = targetModel.displayName.sanitizeFileName()
            val baseName = "${timestamp}_tool_${modelName}_0"

            if (remoteUrl != null) {
                originalUrl = remoteUrl
                val mime = imageItem.mimeType.takeIf { it.startsWith("image/") } ?: "image/png"
                val downloadedBytes = runCatching {
                    getKoin().get<R2MediaStore>().downloadExternal(remoteUrl).getOrThrow().first
                }.getOrNull() ?: runCatching {
                    withContext(Dispatchers.IO) {
                        java.net.URL(remoteUrl).openStream().use { it.readBytes() }
                    }
                }.getOrNull()

                if (downloadedBytes != null) {
                    val resolvedMime = mime
                    val originalDisplayName = "$baseName${mimeToImageExt(resolvedMime)}"
                    val originalAsset = assetResolver.createFromBytes(
                        bytes = downloadedBytes,
                        displayName = originalDisplayName,
                        mimeType = resolvedMime,
                        folder = FileFolders.IMAGES,
                        prompt = promptVal,
                        externalUrl = remoteUrl,
                    )
                    val originalFile = filesManager.getFile(originalAsset)
                    val previewFile = filesManager.createLlmPreviewImageFile(originalFile) ?: originalFile

                    val previewAsset = if (previewFile == originalFile) {
                        originalAsset
                    } else {
                        filesManager.syncFolder(FileFolders.LLM_PREVIEWS)
                        val previewRow = filesManager.getByRelativePath("${FileFolders.LLM_PREVIEWS}/${previewFile.name}")
                            ?: assetResolver.createFromUri(
                                uri = previewFile.toUri(),
                                folder = FileFolders.LLM_PREVIEWS,
                                displayName = previewFile.name,
                                mimeType = "image/jpeg",
                                prompt = promptVal,
                                description = "LLM preview for generated image ${originalAsset.id}",
                            )
                        previewRow.copy(
                            mimeType = "image/jpeg",
                            prompt = promptVal,
                            description = "LLM preview for generated image ${originalAsset.id}",
                            externalUrl = remoteUrl,
                            updatedAt = System.currentTimeMillis(),
                        ).also { updated ->
                            database.managedFileDao().update(updated)
                            assetResolver.enqueueCloudUpload(updated)
                        }
                    }
                    originalAssetId = originalAsset.id
                    previewAssetId = previewAsset.id
                    displayHistoryPath = if (originalFile.isFile) "${FileFolders.IMAGES}/${originalFile.name}" else remoteUrl
                    llmImageLocation = AssetUri.fromId(previewAsset.id)
                } else {
                    val asset = assetResolver.createFromExternalUrl(
                        url = remoteUrl,
                        displayName = "$baseName${mimeToImageExt(mime)}",
                        mimeType = mime,
                        prompt = promptVal,
                    )
                    originalAssetId = asset.id
                    previewAssetId = asset.id
                    displayHistoryPath = remoteUrl
                    llmImageLocation = AssetUri.fromId(asset.id)
                }
            } else {
                // Base64 模式：先写本地缓存并索引为 Asset，聊天立刻展示 asset preview。
                originalUrl = null
                val resolvedMime = imageItem.mimeType.takeIf { it.startsWith("image/") } ?: "image/png"
                val ext = mimeToImageExt(resolvedMime)
                // 落盘统一走 createFromBytes：物理文件名由 buildUuidFileName 生成 UUID，
                // baseName 只当 display_name。以前这里自己拼 "${timestamp}_tool_${model}_0.png"
                // 直写目录，绕过了 UUID 命名与内容去重，同一秒生成两张就会互相覆盖。
                val decodedBytes = withContext(Dispatchers.IO) {
                    val raw = imageItem.data.let {
                        if (it.startsWith("data:image")) it.substringAfter("base64,") else it
                    }
                    Base64.decode(raw, Base64.DEFAULT)
                }
                val originalAsset = assetResolver.createFromBytes(
                    bytes = decodedBytes,
                    displayName = "$baseName$ext",
                    mimeType = resolvedMime,
                    folder = FileFolders.IMAGES,
                    prompt = promptVal,
                )
                val originalFile = filesManager.getFile(originalAsset)
                val previewFile = filesManager.createLlmPreviewImageFile(originalFile) ?: originalFile

                val previewAsset = if (previewFile == originalFile) {
                    originalAsset
                } else {
                    filesManager.syncFolder(FileFolders.LLM_PREVIEWS)
                    val previewRow = filesManager.getByRelativePath("${FileFolders.LLM_PREVIEWS}/${previewFile.name}")
                        ?: assetResolver.createFromUri(
                            uri = previewFile.toUri(),
                            folder = FileFolders.LLM_PREVIEWS,
                            displayName = previewFile.name,
                            mimeType = "image/jpeg",
                            prompt = promptVal,
                            description = "LLM preview for generated image ${originalAsset.id}",
                        )
                    previewRow.copy(
                        mimeType = "image/jpeg",
                        prompt = promptVal,
                        description = "LLM preview for generated image ${originalAsset.id}",
                        updatedAt = System.currentTimeMillis(),
                    ).also { updated ->
                        database.managedFileDao().update(updated)
                        assetResolver.enqueueCloudUpload(updated)
                    }
                }

                originalAssetId = originalAsset.id
                previewAssetId = previewAsset.id
                displayHistoryPath = originalAsset.relativePath
                llmImageLocation = AssetUri.fromId(previewAsset.id)
            }

            runCatching {
                getKoin().get<GenMediaRepository>().insertMedia(
                    GenMediaEntity(
                        path = displayHistoryPath,
                        modelId = targetModel.displayName,
                        prompt = promptVal,
                        createAt = System.currentTimeMillis(),
                        type = if (resolvedReferences.isEmpty()) {
                            GenMediaEntity.TYPE_IMAGE_GENERATION
                        } else {
                            GenMediaEntity.TYPE_IMAGE_EDIT
                        },
                        sourcePaths = resolvedReferences.takeIf { it.isNotEmpty() }
                            ?.joinToString("\n") { ref -> ref.source },
                        r2Key = null,
                        r2Acct = null,
                        originalUrl = originalUrl,
                        originalAssetId = originalAssetId,
                        previewAssetId = previewAssetId,
                    )
                )
            }

            // 图片地址一律用 Asset ID: 数据库主键, 跨轮/跨分支/裁剪上下文后都不变。
            // 旧的 assistant-round-<N>-ref-<M>.png 已废除(轮号会随上下文漂移), 不再返回。
            val resultPayload = buildJsonObject {
                put("status", "ok")
                put("asset_id", originalAssetId)
                put("asset_uri", AssetUri.fromId(originalAssetId))
                put("preview_asset_uri", AssetUri.fromId(previewAssetId))
                put("markdown", "![](${AssetUri.fromId(originalAssetId)})")
            }

            listOf(
                UIMessagePart.Text(resultPayload.toString())
            )
        }
    )
}

