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
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.sanitizeFileName
import java.io.File
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

/** Creates stable, human-readable references for images already present in this conversation. */
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

private fun Model.toImageToolDescription(): String = buildString {
    append("- $modelId: $displayName")
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
    source: String,
    targetModel: Model,
    filesManager: FilesManager,
): String {
    val supportsUrl = Modality.URL in targetModel.inputModalities
    val supportsImage = targetModel.imageCapabilities.supportsImageEditing || Modality.IMAGE in targetModel.inputModalities
    require(supportsUrl || supportsImage) {
        "The selected image model does not support URL or image reference input"
    }

    if (supportsUrl && (source.startsWith("http://") || source.startsWith("https://"))) {
        return source
    }
    if (supportsUrl && source.startsWith("r2://")) {
        val r2Store = getKoin().get<R2MediaStore>()
        return r2Store.presign(R2Ref.parse(source) ?: error("Invalid R2 reference: $source")).getOrThrow()
    }

    val bytes = loadImageBytes(source)
    val previewFile = createUploadPreview(bytes, filesManager)
    val r2Store = runCatching { getKoin().get<R2MediaStore>() }.getOrNull()
    val r2Ref = if (r2Store?.isConfigured() == true) {
        r2Store.upload(previewFile.readBytes(), "image/jpeg", R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()
    } else null
    if (supportsUrl && r2Store != null && r2Ref != null) {
        return r2Store.presign(r2Ref).getOrThrow()
    }
    return previewFile.absolutePath.toImageDataUriOrRemote()
}

private suspend fun loadImageBytes(source: String): ByteArray = withContext(Dispatchers.IO) {
    val r2Store = runCatching { getKoin().get<R2MediaStore>() }.getOrNull()
    when {
        source.startsWith("r2://") -> {
            val ref = R2Ref.parse(source) ?: error("Invalid R2 reference: $source")
            (r2Store ?: error("R2 store unavailable")).downloadBytes(ref).getOrThrow()
        }
        source.startsWith("http://") || source.startsWith("https://") -> {
            val ref = r2Store?.refFromConfiguredUrl(source)
            if (ref != null) r2Store.downloadBytes(ref).getOrThrow() else URL(source).openStream().use { it.readBytes() }
        }
        source.startsWith("data:") -> Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT)
        source.startsWith("file://") -> source.toUri().toFile().readBytes()
        else -> File(source).readBytes()
    }
}

private suspend fun createUploadPreview(bytes: ByteArray, filesManager: FilesManager): File = withContext(Dispatchers.IO) {
    val temp = kotlin.io.path.createTempFile(prefix = "image_ref_", suffix = ".img").toFile()
    try {
        temp.writeBytes(bytes)
        val previewBytes = filesManager.createLlmPreviewImageBytes(temp) ?: bytes
        val entity = filesManager.saveUploadFromBytes(
            bytes = previewBytes,
            displayName = "image_reference_preview.jpg",
            mimeType = "image/jpeg",
        )
        filesManager.getFile(entity)
    } finally {
        runCatching { temp.delete() }
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
): Tool {
    // The image tool is intentionally limited to the image model(s) selected by the user.
    // Other configured models are neither disclosed to the LLM nor selectable by a tool call.
    val selectedModels = settings.selectedImageGenerationModels()
    if (selectedModels.isEmpty()) {
        throw IllegalStateException("No selected image generation model configured")
    }
    val hasMultipleModels = selectedModels.size > 1
    val hasConfiguredLoraModels = selectedModels.any { it.supportsConfiguredLoras() }
    val availableReferencesDescription = imageReferences
        .takeIf { selectedModels.any { model -> model.imageCapabilities.supportsImageEditing } }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "- ${it.id}" }

    val selectedModelDescription = selectedModels.joinToString("\n") { it.toImageToolDescription() }
    val selectedModelIdsDescription = selectedModels.joinToString { it.modelId }
    val loraLimitsDescription = selectedModels
        .filter { it.supportsConfiguredLoras() }
        .joinToString("\n") { "- ${it.modelId}: ${it.loraLimitDescription()}; choose from all configured LoRAs listed for that model." }

    return Tool(
        name = "image_generation",
        description = """
            Generate or edit images based on a text prompt.
            Use this when the user asks to draw, paint, visualize, create an image, or edit/colorize/redraw an attached conversation image.
            
            Parameters:
            - prompt (string, required): A detailed description of the image to generate.
            ${if (hasMultipleModels) "- model (string, optional): Image model ID to use. Must be one of the selected models: $selectedModelIdsDescription. If omitted, the first selected model is used." else ""}
            ${if (hasConfiguredLoraModels) "- loras (array, optional): WaveSpeed LoRA selections for LoRA-capable selected models only. Each item contains a configured `id` and `scale`. All configured LoRAs are visible below; select only the few needed for this request and obey the per-model request limit. Do not send this field for models without listed LoRAs." else ""}
            - parameters (object, optional): Values for custom parameters configured on the selected image model.
            ${if (availableReferencesDescription != null) "- reference_images (array, optional): Reference image IDs for image editing. Use this whenever the user asks to edit/colorize/redraw an existing or attached image." else ""}

            User-selected image model(s) and available model-specific options:
            $selectedModelDescription
            ${if (hasConfiguredLoraModels) "\nLoRA per-request limits:\n$loraLimitsDescription" else ""}
            ${availableReferencesDescription?.let { "\nConversation images available for reference:\n$it" } ?: ""}
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
                            put("description", "Optional selected image model ID. Use one of: $selectedModelIdsDescription.")
                        })
                    }
                    if (hasConfiguredLoraModels) {
                        put("loras", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional WaveSpeed LoRAs for LoRA-capable selected models only: [{id: string, scale: number}]. Choose from all configured LoRAs listed in the tool description, but send no more than the selected model allows per request. Do not send for non-LoRA models.")
                            put("items", buildJsonObject {
                                put("type", "object")
                            })
                        })
                    }
                    if (availableReferencesDescription != null) {
                        put("reference_images", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional conversation image reference IDs. Use only the listed IDs; supplying any reference switches to image editing mode.")
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        })
                    }
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional model request parameters. Prefer parameters configured on the selected model; unregistered parameters are also forwarded to the provider.")
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
                imageReferences.find { it.id == id }
                    ?: error("Unknown conversation image reference: $id")
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

            // P3 云资产（v1.1 拍板）：生图一律固化进 R2（URL 会过期；base64 原本只落本地）。
            // 消息 part 与图库统一存 r2:// 引用；原 URL 仅存 metadata.original_url。
            // R2 未配置 / 镜像失败时整体回退原行为（URL 直通或本地文件）。
            val r2Store = runCatching { getKoin().get<R2MediaStore>() }.getOrNull()
            val assetResolver = getKoin().get<AssetResolver>()
            val database = getKoin().get<AppDatabase>()
            val remoteUrl = imageItem.url
            var r2Original: R2Ref? = null
            var r2Preview: R2Ref? = null
            var mirroredMime: String? = null

            var originalImageLocation: String
            var llmImageLocation: String
            val originalUrl: String?
            var originalAssetId: String? = null
            var previewAssetId: String? = null

            if (remoteUrl != null) {
                originalUrl = remoteUrl
                if (r2Store?.isConfigured() == true) {
                    r2Store.mirror(remoteUrl, R2MediaStore.PREFIX_GEN_IMAGES).getOrNull()?.let { (ref, mime) ->
                        r2Original = ref
                        mirroredMime = mime
                    }
                }
                val originalRef = r2Original
                if (originalRef != null) {
                    originalImageLocation = originalRef.toString()
                    llmImageLocation = (r2Preview ?: originalRef).toString()
                } else {
                    originalImageLocation = remoteUrl
                    llmImageLocation = remoteUrl
                }
            } else {
                // Base64 模式：优先写到本地磁盘 imagesDir 保证本地渲染零延迟
                originalUrl = null
                val imagesDir = filesManager.getImagesDir()
                val timestamp = System.currentTimeMillis()
                val filename = "${timestamp}_tool_${targetModel.displayName.sanitizeFileName()}_0.png"
                val imageFile = File(imagesDir, filename)
                val originalFile = filesManager.createImageFileFromBase64(imageItem.data, imageFile.absolutePath)
                val previewFile = filesManager.createLlmPreviewImageFile(originalFile) ?: originalFile

                // 同步上传至 R2 用于多端同步，但不阻塞本地直接展示本地文件
                if (r2Store?.isConfigured() == true) {
                    runCatching {
                        r2Store.upload(originalFile.readBytes(), imageItem.mimeType, R2MediaStore.PREFIX_GEN_IMAGES)
                            .getOrNull()
                            ?.let { ref ->
                                r2Original = ref
                                mirroredMime = imageItem.mimeType
                            }
                        if (previewFile != originalFile) {
                            r2Store.upload(previewFile.readBytes(), "image/jpeg", R2MediaStore.PREFIX_GEN_PREVIEWS)
                                .getOrNull()
                                ?.let { r2Preview = it }
                        }
                    }
                }

                if (r2Original != null) {
                    filesManager.syncFolder(FileFolders.IMAGES)
                    filesManager.getByRelativePath("${FileFolders.IMAGES}/${originalFile.name}")
                        ?.let { row -> filesManager.setCloudCopy(row.id, r2Original!!.key, r2Original!!.acctId) }
                }
                if (r2Preview != null && previewFile != originalFile) {
                    filesManager.syncFolder(FileFolders.LLM_PREVIEWS)
                    filesManager.getByRelativePath("${FileFolders.LLM_PREVIEWS}/${previewFile.name}")
                        ?.let { row -> filesManager.setCloudCopy(row.id, r2Preview!!.key, r2Preview!!.acctId) }
                }

                // Keep the tool UI fast: show the local preview immediately. R2 upload only updates
                // genmedia/managed_files for cross-device sync and later turns.
                originalImageLocation = originalFile.absolutePath
                llmImageLocation = previewFile.toUri().toString()
            }

            runCatching {
                val historyPath = if (
                    originalImageLocation.startsWith("http://") ||
                    originalImageLocation.startsWith("https://") ||
                    originalImageLocation.startsWith("r2://")
                ) {
                    originalImageLocation
                } else {
                    "images/${File(originalImageLocation).name}"
                }
                getKoin().get<GenMediaRepository>().insertMedia(
                    GenMediaEntity(
                        path = historyPath,
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
                        r2Key = r2Original?.key,
                        r2Acct = r2Original?.acctId,
                        originalUrl = originalUrl,
                    )
                )
            }

            val currentRound = (imageReferences.mapNotNull { ref ->
                ref.id.takeIf { it.startsWith("user-round-") }
                    ?.substringAfter("user-round-")
                    ?.substringBefore("-ref-")
                    ?.toIntOrNull()
            }.maxOrNull() ?: 0).coerceAtLeast(1)

            val existingAssistantRefsInCurrentRound = imageReferences.count { ref ->
                ref.id.startsWith("assistant-round-$currentRound-")
            }

            val generatedTag = "assistant-round-$currentRound-ref-${existingAssistantRefsInCurrentRound + 1}.png"

            val resultPayload = buildJsonObject {
                put("status", "ok")
                put("tag", generatedTag)
            }

            val imageMeta = buildJsonObject {
                originalUrl?.let { put("original_url", it) }
                mirroredMime?.let { put("r2_mime", it) }
            }

            listOf(
                UIMessagePart.Image(
                    url = llmImageLocation,
                    metadata = if (mirroredMime != null || originalUrl != null) imageMeta else null,
                ),
                UIMessagePart.Text(resultPayload.toString())
            )
        }
    )
}

