package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryOptions(
    val referenceAssistantMemory: Boolean = true,
    val allowEditAssistantMemory: Boolean = false,
    val referenceGlobalMemory: Boolean = true,
    val allowEditGlobalMemory: Boolean = false,
) {
    fun effective(assistant: Assistant): MemoryOptions {
        val assistantReference = assistant.enableMemory && referenceAssistantMemory
        val globalReference = assistant.enableMemory && assistant.useGlobalMemory && referenceGlobalMemory
        return copy(
            referenceAssistantMemory = assistantReference,
            allowEditAssistantMemory = assistantReference && allowEditAssistantMemory,
            referenceGlobalMemory = globalReference,
            allowEditGlobalMemory = globalReference && allowEditGlobalMemory,
        )
    }

    fun referencesAny(): Boolean = referenceAssistantMemory || referenceGlobalMemory
    fun editsAny(): Boolean = allowEditAssistantMemory || allowEditGlobalMemory
}
