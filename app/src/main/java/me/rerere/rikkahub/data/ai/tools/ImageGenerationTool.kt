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
        message.parts.filterIsInstance<UIMessagePart.Image>().mapIndexed { index, image ->
            ImageReference("$prefix-round-${round.coerceAtLeast(1)}-ref-${index + 1}.png", image.url)
        }
    }
}

/**
 * Creates the image-generation tool bound to the currently selected model.
 */
fun createImageGenerationTool(
    settings: Settings,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    imageReferences: List<ImageReference> = emptyList(),
): Tool {
    // The image tool is intentionally bound to the model currently selected by the user.
    // Other configured models are neither disclosed to the LLM nor selectable by a tool call.
    val selectedModel = settings.findModelById(settings.imageGenerationModelId)
        ?: throw IllegalStateException("No selected image generation model configured")
    val availableReferencesDescription = imageReferences.takeIf { selectedModel.imageCapabilities.supportsImageEditing }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "- ${it.id}" }

    val selectedModelDescription = buildString {
        append("- ${selectedModel.modelId}: ${selectedModel.displayName}")
        if (selectedModel.waveSpeedLoras.isNotEmpty()) {
            append("; LoRAs: ")
            append(selectedModel.waveSpeedLoras.joinToString { "${it.id} (${it.explanation})" })
        }
        if (selectedModel.imageParameters.isNotEmpty()) {
            append("; custom parameters: ")
            append(selectedModel.imageParameters.joinToString {
                "${it.key} (${it.explanation}; default: ${it.defaultValue ?: "none"})"
            })
        }
    }

    return Tool(
        name = "image_generation",
        description = """
            Generate beautiful images based on a text prompt.
            Use this when the user asks to draw, paint, visualize, or create an image.
            
            Parameters:
            - prompt (string, required): A detailed description of the image to generate.
            - loras (array, optional): WaveSpeed LoRA selections. Each item contains a configured `id` and `scale`.
            - parameters (object, optional): Values for custom parameters configured on the selected image model.
            ${if (availableReferencesDescription != null) "- reference_images (array, optional): Reference image IDs for image editing." else ""}

            The user-selected image model and its available model-specific options:
            $selectedModelDescription
            ${availableReferencesDescription?.let { "\nConversation images available for reference:\n$it" } ?: ""}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed description of the image to generate.")
                    })
                    put("loras", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional WaveSpeed LoRAs: [{id: string, scale: number}]. Maximum 3.")
                    })
                    if (availableReferencesDescription != null) {
                        put("reference_images", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional conversation image reference IDs. Use only the listed IDs; supplying any reference switches to image editing mode.")
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
            val requestedLoras = args.jsonObject["loras"]?.jsonArray.orEmpty()
            val requestedReferenceIds = args.jsonObject["reference_images"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.contentOrNull ?: error("Reference image ID must be a string") }
            val requestedParameters = args.jsonObject["parameters"]?.jsonObject.orEmpty()

            // Bind every invocation to the model selected in image-generation settings.
            val targetModel = selectedModel

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

            val loras = requestedLoras.map { item ->
                val lora = item.jsonObject
                val id = lora["id"]?.jsonPrimitive?.contentOrNull ?: error("LoRA id is required")
                val configured = targetModel.waveSpeedLoras.find { it.id == id }
                    ?: error("Unknown LoRA '$id' for model ${targetModel.displayName}")
                val scale = lora["scale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f
                ImageLoraSelection(path = configured.url, scale = scale)
            }
            if (loras.isNotEmpty()) {
                require(targetModel.imageCapabilities.loraProtocol != WaveSpeedLoraProtocol.NONE) {
                    "The selected image model does not support LoRA"
                }
                require(loras.size <= targetModel.imageCapabilities.maxLoras) {
                    "The selected image model allows at most ${targetModel.imageCapabilities.maxLoras} LoRAs"
                }
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
