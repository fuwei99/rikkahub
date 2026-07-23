package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ImageModelCapabilities
import me.rerere.ai.provider.ImageModelParameter
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import kotlin.uuid.Uuid

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
            Model("wavespeed-ai/z-image/turbo", "Z-Image Turbo", type = ModelType.IMAGE),
            Model(
                modelId = "wavespeed-ai/z-image/turbo-lora",
                displayName = "Z-Image Turbo LoRA",
                type = ModelType.IMAGE,
                imageCapabilities = ImageModelCapabilities(loraProtocol = WaveSpeedLoraProtocol.PATH_SCALE_ARRAY, maxLoras = 3),
            ),
            Model("pruna-ai/p-image/text-to-image", "P-Image Text to Image", type = ModelType.IMAGE),
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
                ),
            ),
            Model("pruna-ai/p-image/text-to-image-lora", "P-Image Text to Image LoRA", type = ModelType.IMAGE),
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
            ),
        ),
    )
)
