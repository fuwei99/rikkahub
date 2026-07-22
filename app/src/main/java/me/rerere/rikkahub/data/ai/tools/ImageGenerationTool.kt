package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
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
    return Tool(
        name = "image_generation",
        description = """
            Generate beautiful images based on a text prompt.
            Use this when the user asks to draw, paint, visualize, or create an image.
            
            Parameters:
            - prompt (string, required): A detailed description of the image to generate.
            - model (string, optional): The ID of the image generation model (e.g. 'dall-e-3'). If omitted, a default model is used.
        """.trimIndent(),
        parameters = {
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed description of the image to generate.")
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional model ID.")
                    })
                })
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("prompt"))
                })
            }
        },
        execute = { args ->
            val promptVal = args.jsonObject["prompt"]?.jsonPrimitive?.contentOrNull ?: error("Missing prompt")
            val modelIdVal = args.jsonObject["model"]?.jsonPrimitive?.contentOrNull

            // 查找合适的生图模型与服务商
            val targetModel = if (!modelIdVal.isNullOrBlank()) {
                settings.imageProviders.flatMap { it.models }.find { it.modelId == modelIdVal }
                    ?: settings.findModelById(settings.imageGenerationModelId)
            } else {
                settings.findModelById(settings.imageGenerationModelId)
            } ?: throw IllegalStateException("No default image generation model configured")

            val targetProviderSetting = targetModel.findImageProvider(settings.imageProviders)
                ?: throw IllegalStateException("Image Provider not found for model: ${targetModel.displayName}")

            val params = ImageGenerationParams(
                model = targetModel,
                prompt = promptVal,
                numOfImages = 1,
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
                    url = imageFile.absolutePath,
                    width = 1024,
                    height = 1024
                ),
                UIMessagePart.Text(resultPayload.toString())
            )
        }
    )
}
