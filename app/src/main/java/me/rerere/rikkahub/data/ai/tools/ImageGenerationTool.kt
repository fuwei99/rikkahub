package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findImageProvider
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File
import java.net.URI
import java.net.URLConnection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

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
                if (resolvedReferences.isEmpty()) {
                    provider.generateImage(targetProviderSetting, params).firstOrNull()
                } else {
                    provider.editImage(
                        targetProviderSetting,
                        ImageEditParams(
                            model = targetModel,
                            prompt = promptVal,
                            images = resolvedReferences.map(ImageReference::toProviderImageSource),
                            customHeaders = targetModel.customHeaders,
                            customBody = customBody,
                            loras = loras,
                        ),
                    ).firstOrNull()
                }
            } ?: throw IllegalStateException("Failed to generate image: Empty response from provider")

            // Preserve provider URLs for remote results. Only providers that return Base64 need
            // a local file, avoiding unnecessary downloads for WaveSpeed and URL-mode providers.
            val imageLocation = imageItem.url ?: run {
                val imagesDir = filesManager.getImagesDir()
                val timestamp = System.currentTimeMillis()
                val filename = "${timestamp}_tool_${targetModel.displayName}_0.png"
                val imageFile = File(imagesDir, filename)
                filesManager.createImageFileFromBase64(imageItem.data, imageFile.absolutePath)
                imageFile.absolutePath
            }

            val resultPayload = buildJsonObject {
                put("file_paths", imageLocation)
                put("prompt", promptVal)
            }

            listOf(
                UIMessagePart.Image(
                    url = imageLocation
                ),
                UIMessagePart.Text(resultPayload.toString())
            )
        }
    )
}

@OptIn(ExperimentalEncodingApi::class)
private fun ImageReference.toProviderImageSource(): String {
    if (source.startsWith("http://") || source.startsWith("https://") || source.startsWith("data:image")) return source
    val file = if (source.startsWith("file:")) File(URI(source)) else File(source)
    require(file.exists() && file.isFile) { "Reference image does not exist: $id" }
    val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "image/png"
    return "data:$mimeType;base64,${Base64.encode(file.readBytes())}"
}
