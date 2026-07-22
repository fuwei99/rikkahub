package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ImageProviderSetting
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
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = "",
        enabled = true,
    )
)
