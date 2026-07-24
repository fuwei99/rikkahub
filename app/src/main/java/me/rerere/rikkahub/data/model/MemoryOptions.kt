package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryOptions(
    val referenceAssistantMemory: Boolean = true,
    val allowEditAssistantMemory: Boolean = false,
    val referenceGlobalMemory: Boolean = true,
    val allowEditGlobalMemory: Boolean = false,
) {
    fun effective(assistant: Assistant): MemoryOptions = copy(
        referenceAssistantMemory = assistant.enableMemory && referenceAssistantMemory,
        allowEditAssistantMemory = assistant.enableMemory && allowEditAssistantMemory,
        referenceGlobalMemory = assistant.enableMemory && assistant.useGlobalMemory && referenceGlobalMemory,
        allowEditGlobalMemory = assistant.enableMemory && assistant.useGlobalMemory && allowEditGlobalMemory,
    )

    fun referencesAny(): Boolean = referenceAssistantMemory || referenceGlobalMemory
    fun editsAny(): Boolean = allowEditAssistantMemory || allowEditGlobalMemory
}
