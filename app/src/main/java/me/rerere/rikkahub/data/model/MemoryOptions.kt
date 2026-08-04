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
        // 编辑与参考解耦（2026-08-04 用户需求）：允许编辑只依赖总闸 enableMemory(+useGlobalMemory)，
        // 关掉「参考记忆」(自动注入) 后模型仍可用 memory_tool 主动管理记忆，两者独立开关。
        val assistantEdit = assistant.enableMemory && allowEditAssistantMemory
        val globalEdit = assistant.enableMemory && assistant.useGlobalMemory && allowEditGlobalMemory
        val recentChatsReference = referenceRecentChats ?: assistant.enableRecentChatsReference
        return copy(
            referenceAssistantMemory = assistantReference,
            allowEditAssistantMemory = assistantEdit,
            referenceGlobalMemory = globalReference,
            allowEditGlobalMemory = globalEdit,
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
