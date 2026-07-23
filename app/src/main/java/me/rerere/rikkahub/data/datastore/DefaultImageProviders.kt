package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ImageModelCapabilities
import me.rerere.ai.provider.ImageModelParameter
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import kotlin.uuid.Uuid

private fun waveSpeedTaskParameters() = listOf(
    ImageModelParameter(
        key = "size",
        explanation = "输出图像尺寸，格式为“宽*高”。默认 1024*1024。",
        defaultValue = JsonPrimitive("1024*1024"),
    ),
    ImageModelParameter(
        key = "seed",
        explanation = "随机种子；-1 表示每次随机生成。",
        defaultValue = JsonPrimitive(-1),
    ),
    ImageModelParameter(
        key = "output_format",
        explanation = "输出格式：jpeg、png 或 webp。默认 jpeg。",
        defaultValue = JsonPrimitive("jpeg"),
    ),
    ImageModelParameter(
        key = "enable_sync_mode",
        explanation = "是否尝试同步等待生成结果；超出同步等待窗口时任务仍会继续。",
        defaultValue = JsonPrimitive(false),
    ),
    ImageModelParameter(
        key = "enable_base64_output",
        explanation = "是否将输出作为不含 data URI 前缀的 Base64 返回；默认返回 CDN URL。",
        defaultValue = JsonPrimitive(false),
    ),
)

private fun pImageParameters(includeAspectRatio: Boolean = true) = buildList {
    if (includeAspectRatio) {
        add(
            ImageModelParameter(
                key = "aspect_ratio",
                explanation = "输出宽高比；图像编辑模型可使用 match_input_image 以匹配参考图。",
                defaultValue = JsonPrimitive("match_input_image"),
            )
        )
    }
    add(
        ImageModelParameter(
            key = "seed",
            explanation = "随机种子；-1 表示每次随机生成。",
            defaultValue = JsonPrimitive(-1),
        )
    )
    add(
        ImageModelParameter(
            key = "output_format",
            explanation = "输出格式：jpeg、png 或 webp。默认 png。",
            defaultValue = JsonPrimitive("png"),
        )
    )
    add(
        ImageModelParameter(
            key = "enable_sync_mode",
            explanation = "是否尝试同步等待生成结果。",
            defaultValue = JsonPrimitive(false),
        )
    )
    add(
        ImageModelParameter(
            key = "enable_base64_output",
            explanation = "是否将输出作为不含 data URI 前缀的 Base64 返回。",
            defaultValue = JsonPrimitive(false),
        )
    )
}

val DEFAULT_IMAGE_PROVIDERS = listOf(
    ImageProviderSetting.OpenAI(
        id = Uuid.parse("d38df8a2-28e4-4ea0-ba6d-4ee8e89f8121"),
        name = "OpenAI DALL-E",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        enabled = true,
    ),
    ImageProviderSetting.Volcengine(
        id = Uuid.parse("e49e29a3-38e4-4ab2-b2ee-be51e89f8450"),
        name = "火山方舟生图",
        baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
        apiKey = "",
        enabled = true,
        models = listOf(
            Model(
                modelId = "doubao-seedream-5.0-lite",
                displayName = "Doubao Seedream 5.0 Lite",
                type = ModelType.IMAGE,
                imageCapabilities = ImageModelCapabilities(supportsImageEditing = true),
                imageParameters = listOf(
                    ImageModelParameter(
                        key = "size",
                        explanation = "输出图像尺寸，例如 1024x1024。",
                        defaultValue = JsonPrimitive("1024x1024"),
                    ),
                    ImageModelParameter(
                        key = "output_format",
                        explanation = "输出图像格式；默认 png。",
                        defaultValue = JsonPrimitive("png"),
                    ),
                    ImageModelParameter(
                        key = "watermark",
                        explanation = "是否添加平台水印；默认不添加。",
                        defaultValue = JsonPrimitive(false),
                    ),
                    ImageModelParameter(
                        key = "response_format",
                        explanation = "响应图像格式；b64_json 便于 App 直接保存图片。",
                        defaultValue = JsonPrimitive("b64_json"),
                    ),
                ),
            )
        ),
    ),
    ImageProviderSetting.Wavespeed(
        id = Uuid.parse("f50f3ab4-49f5-4bc3-c3ff-cf62f90a9561"),
        name = "WaveSpeed AI",
        baseUrl = "https://api.wavespeed.ai/api/v3",
        apiKey = "",
        enabled = true,
        models = listOf(
            Model(
                modelId = "wavespeed-ai/z-image/turbo",
                displayName = "Z-Image Turbo",
                type = ModelType.IMAGE,
                imageParameters = waveSpeedTaskParameters(),
            ),
            Model(
                modelId = "wavespeed-ai/z-image/turbo-lora",
                displayName = "Z-Image Turbo LoRA",
                type = ModelType.IMAGE,
                imageCapabilities = ImageModelCapabilities(loraProtocol = WaveSpeedLoraProtocol.PATH_SCALE_ARRAY, maxLoras = 3),
                imageParameters = waveSpeedTaskParameters(),
            ),
            Model(
                modelId = "pruna-ai/p-image/text-to-image",
                displayName = "P-Image Text to Image",
                type = ModelType.IMAGE,
                imageParameters = pImageParameters(),
            ),
            Model(
                modelId = "openai/gpt-image-2/text-to-image",
                displayName = "GPT Image 2 Text to Image",
                type = ModelType.IMAGE,
                imageParameters = listOf(
                    ImageModelParameter(
                        key = "resolution",
                        explanation = "输出分辨率：1k、2k 或 4k。默认 1k；更高分辨率成本更高。",
                        defaultValue = JsonPrimitive("1k"),
                    ),
                    ImageModelParameter(
                        key = "quality",
                        explanation = "输出质量：low、medium 或 high。默认 medium；更高质量成本更高。",
                        defaultValue = JsonPrimitive("medium"),
                    ),
                    ImageModelParameter(
                        key = "output_format",
                        explanation = "输出格式：png、jpeg 或 webp。默认 png。",
                        defaultValue = JsonPrimitive("png"),
                    ),
                    ImageModelParameter(
                        key = "enable_sync_mode",
                        explanation = "是否尝试同步等待生成结果。",
                        defaultValue = JsonPrimitive(false),
                    ),
                    ImageModelParameter(
                        key = "enable_base64_output",
                        explanation = "是否将输出作为不含 data URI 前缀的 Base64 返回。",
                        defaultValue = JsonPrimitive(false),
                    ),
                ),
            ),
            Model(
                modelId = "pruna-ai/p-image/text-to-image-lora",
                displayName = "P-Image Text to Image LoRA",
                type = ModelType.IMAGE,
                // Its LoRA payload schema is not assumed here. Configure a protocol manually
                // once the provider's API contract for this model is confirmed.
                imageParameters = pImageParameters(),
            ),
            Model(
                modelId = "wavespeed-ai/qwen-image/edit-2511-lora",
                displayName = "Qwen Image Edit 2511 LoRA",
                type = ModelType.IMAGE,
                imageCapabilities = ImageModelCapabilities(
                    supportsImageEditing = true,
                    maxReferenceImages = 3,
                    loraProtocol = WaveSpeedLoraProtocol.PATH_SCALE_ARRAY,
                    maxLoras = 3,
                ),
                imageParameters = waveSpeedTaskParameters(),
            ),
            Model(
                modelId = "pruna-ai/p-image/edit-lora",
                displayName = "P-Image Edit LoRA",
                type = ModelType.IMAGE,
                imageCapabilities = ImageModelCapabilities(
                    supportsImageEditing = true,
                    maxReferenceImages = 5,
                    loraProtocol = WaveSpeedLoraProtocol.WEIGHT_SCALE,
                    maxLoras = 1,
                ),
                imageParameters = pImageParameters(),
            ),
        ),
    )
)
