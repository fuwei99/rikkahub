package me.rerere.ai.provider

import kotlinx.serialization.Serializable

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}
