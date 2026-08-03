package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryOptions(
    val referenceAssistantMemory: Boolean = true,
    val allowEditAssistantMemory: Boolean = false,
    val referenceGlobalMemory: Boolean = true,
    val allowEditGlobalMemory: Boolean = false,
    val referenceRecentChats: Boolean? = null,
    // ---- 记忆图 Phase 2 检索开关（默认关，P2 语义检索/图传播上线后生效）----
    /** 语义向量检索（embedding + hnsw） */
    val semanticSearch: Boolean = false,
    /** 图传播召回（多跳 BFS 邻居 boost） */
    val graphExpansion: Boolean = false,
) {
    fun effective(assistant: Assistant): MemoryOptions {
        val assistantReference = assistant.enableMemory && referenceAssistantMemory
        val globalReference = assistant.enableMemory && assistant.useGlobalMemory && referenceGlobalMemory
        val recentChatsReference = referenceRecentChats ?: assistant.enableRecentChatsReference
        return copy(
            referenceAssistantMemory = assistantReference,
            allowEditAssistantMemory = assistantReference && allowEditAssistantMemory,
            referenceGlobalMemory = globalReference,
            allowEditGlobalMemory = globalReference && allowEditGlobalMemory,
            referenceRecentChats = recentChatsReference,
        )
    }

    fun referencesAny(): Boolean = referenceAssistantMemory || referenceGlobalMemory || (referenceRecentChats == true)
    fun editsAny(): Boolean = allowEditAssistantMemory || allowEditGlobalMemory
}

/** 按作用域分开携带的记忆, 避免两个 scope 拍平后模型无法判断记录归属 */
data class ScopedMemories(
    val assistant: List<AssistantMemory> = emptyList(),
    val global: List<AssistantMemory> = emptyList(),
) {
    fun isEmpty(): Boolean = assistant.isEmpty() && global.isEmpty()

    companion object {
        val Empty = ScopedMemories()
    }
}
