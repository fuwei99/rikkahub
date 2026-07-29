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
    URL,
}

@Serializable
enum class ToolCallingStrategy(
    val labelZh: String,
    val labelEn: String,
) {
    NATIVE("原生工具", "Native"),
    CODE_ACTION("代码特化 XML", "Code Action"),
    CUSTOM_PROTOCOL("全自定义 XML", "Custom Protocol"),
    OFF("关闭", "Off"),
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}
