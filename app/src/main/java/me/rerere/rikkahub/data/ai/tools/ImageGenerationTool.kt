package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.defaultImageParameterBodies
import me.rerere.ai.provider.ImageLoraSelection
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findImageProvider
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * 创建内置的图像生成工具
 */
fun createImageGenerationTool(
    settings: Settings,
    providerManager: ProviderManager,
    filesManager: FilesManager,
): Tool {
    val modelOptionsDescription = settings.imageProviders
        .flatMap { it.models }
        .filter { it.type == ModelType.IMAGE }
        .joinToString("\n") { model ->
            buildString {
                append("- ${model.modelId}: ${model.displayName}")
                if (model.waveSpeedLoras.isNotEmpty()) {
                    append("; LoRAs: ")
                    append(model.waveSpeedLoras.joinToString { "${it.id} (${it.explanation})" })
                }
                if (model.imageParameters.isNotEmpty()) {
                    append("; custom parameters: ")
                    append(model.imageParameters.joinToString {
                        "${it.key} (${it.explanation}; default: ${it.defaultValue ?: "none"})"
                    })
                }
            }
        }

    return Tool(
        name = "image_generation",
        description = """
            Generate beautiful images based on a text prompt.
            Use this when the user asks to draw, paint, visualize, or create an image.
            
            Parameters:
            - prompt (string, required): A detailed description of the image to generate.
            - model (string, optional): The ID of the image generation model (e.g. 'dall-e-3'). If omitted, a default model is used.
            - loras (array, optional): WaveSpeed LoRA selections. Each item contains a configured `id` and `scale`.
            - parameters (object, optional): Values for custom parameters configured on the selected image model.

            Configured image models and their available model-specific options:
            $modelOptionsDescription
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed description of the image to generate.")
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional model ID.")
                    })
                    put("loras", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional WaveSpeed LoRAs: [{id: string, scale: number}]. Maximum 3.")
                    })
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
            val modelIdVal = args.jsonObject["model"]?.jsonPrimitive?.contentOrNull
            val requestedLoras = args.jsonObject["loras"]?.jsonArray.orEmpty()
            val requestedParameters = args.jsonObject["parameters"]?.jsonObject.orEmpty()

            // 查找合适的生图模型与服务商
            val targetModel = if (!modelIdVal.isNullOrBlank()) {
                settings.imageProviders.flatMap { it.models }.find { it.modelId == modelIdVal }
                    ?: settings.findModelById(settings.imageGenerationModelId)
            } else {
                settings.findModelById(settings.imageGenerationModelId)
            } ?: throw IllegalStateException("No default image generation model configured")

            val targetProviderSetting = targetModel.findImageProvider(settings.imageProviders)
                ?: throw IllegalStateException("Image Provider not found for model: ${targetModel.displayName}")

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

            val base64Item = runBlocking {
                providerManager.getImageProviderByType(targetProviderSetting)
                    .generateImage(targetProviderSetting, params)
                    .firstOrNull()
            } ?: throw IllegalStateException("Failed to generate image: Empty response from provider")

            // 保存本地图片
            val imagesDir = filesManager.getImagesDir()
            val timestamp = System.currentTimeMillis()
            val filename = "${timestamp}_tool_${targetModel.displayName}_0.png"
            val imageFile = File(imagesDir, filename)
            filesManager.createImageFileFromBase64(base64Item.data, imageFile.absolutePath)

            // 返回保存的文件路径，供 UI 渲染和对话记录
            val resultPayload = buildJsonObject {
                put("file_paths", imageFile.absolutePath)
                put("prompt", promptVal)
            }

            listOf(
                UIMessagePart.Image(
                    url = imageFile.absolutePath
                ),
                UIMessagePart.Text(resultPayload.toString())
            )
        }
    )
}
