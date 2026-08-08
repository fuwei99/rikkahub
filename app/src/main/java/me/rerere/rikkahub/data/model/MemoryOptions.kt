package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryOptions(
    val referenceAssistantMemory: Boolean = true,
    /** 允许图谱检索助手 scope；与 legacy 参考开关独立 */
    val referenceAssistantGraph: Boolean = true,
    val allowEditAssistantMemory: Boolean = false,
    val referenceGlobalMemory: Boolean = true,
    /** 允许图谱检索 global scope；与 legacy 参考开关独立 */
    val referenceGlobalGraph: Boolean = true,
    val allowEditGlobalMemory: Boolean = false,
    val referenceRecentChats: Boolean? = null,
    // ---- 记忆图编辑权限（与 legacy 编辑权限分开，见方案 2026-08-05）----
    /** 允许 AI 编辑「助手记忆图」 */
    val allowEditAssistantGraph: Boolean = false,
    /** 允许 AI 编辑「全局记忆图」 */
    val allowEditGlobalGraph: Boolean = false,
    /**
     * 允许 AI 编辑记忆图**总闸**（对话页记忆卡片级，2026-08-12 用户需求）。
     * 关掉时 graph 侧 memory_tool 完全不暴露（连 list_graphs 也不给，除非另开管理）；
     * 记忆图扩展面板里每张图的「可编辑」开关只决定「哪张图可写」，
     * 两者是 AND 关系：总闸开 + 某图可编辑 → 该图进 tool 可写集合。
     */
    val allowEditMemoryGraph: Boolean = false,
    /**
     * 允许 AI 自管理记忆图（list_graphs/create_graph/attach_graph 的暴露开关）。
     * null = 继承助手设置 `assistant.allowManageMemoryGraphs`（原设置只决定默认值），
     * 对话页记忆卡片可对本会话覆盖。
     */
    val allowManageMemoryGraphs: Boolean? = null,
    // ---- 记忆图 Phase 2 检索开关（默认关，P2 语义检索/图传播上线后生效）----
    /** 语义向量检索（embedding + hnsw） */
    val semanticSearch: Boolean = false,
    /** 图传播召回（多跳 BFS 邻居 boost） */
    val graphExpansion: Boolean = false,
    /**
     * 本轮不使用记忆图（运行时总闸，不落库）。
     *
     * 多图体系下扩展面板里的 enabled/writable 是**持久化 binding**，而这里是「这轮别翻我记忆」
     * 的临时意图，两者语义不同不能互相替代（review2 §二.D）。
     * MemoryGraphBindingResolver 会把它作用到全部 binding 的 enabled 上。
     */
    val graphMuted: Boolean = false,
) {
    /** 旧配置只有一个总开关时，两个 scope 都继承它；新配置可分别关闭。 */
    fun assistantGraphEnabled(assistant: Assistant): Boolean =
        assistant.enableAssistantMemoryGraph ||
            (assistant.enableMemoryGraph && !assistant.enableAssistantMemoryGraph && !assistant.enableGlobalMemoryGraph)

    fun globalGraphEnabled(assistant: Assistant): Boolean =
        assistant.enableGlobalMemoryGraph ||
            (assistant.enableMemoryGraph && !assistant.enableAssistantMemoryGraph && !assistant.enableGlobalMemoryGraph)

    fun effective(assistant: Assistant): MemoryOptions {
        val assistantReference = assistant.enableMemory && referenceAssistantMemory
        val globalReference = assistant.enableMemory && referenceGlobalMemory
        // 编辑与参考解耦（2026-08-04 用户需求）：允许编辑只依赖总闸 enableMemory，
        // 关掉「参考记忆」(自动注入) 后模型仍可用 memory_tool 主动管理记忆，两者独立开关。
        val assistantEdit = assistant.enableMemory && allowEditAssistantMemory
        val globalEdit = assistant.enableMemory && allowEditGlobalMemory
        val assistantGraphReference = assistantGraphEnabled(assistant) && referenceAssistantGraph
        val globalGraphReference = globalGraphEnabled(assistant) && referenceGlobalGraph
        val assistantGraphEdit = assistantGraphEnabled(assistant) && allowEditAssistantGraph
        val globalGraphEdit = globalGraphEnabled(assistant) && allowEditGlobalGraph
        val recentChatsReference = referenceRecentChats ?: assistant.enableRecentChatsReference
        return copy(
            referenceAssistantMemory = assistantReference,
            referenceAssistantGraph = assistantGraphReference,
            allowEditAssistantMemory = assistantEdit,
            referenceGlobalMemory = globalReference,
            referenceGlobalGraph = globalGraphReference,
            allowEditGlobalMemory = globalEdit,
            allowEditAssistantGraph = assistantGraphEdit,
            allowEditGlobalGraph = globalGraphEdit,
            // 总闸不 gate 助手级图功能：没绑定图时 writable 集合为空，tool 自然不暴露
            allowEditMemoryGraph = allowEditMemoryGraph,
            // null → 继承助手默认（记忆卡片可对本会话覆盖，原设置只决定默认值）
            allowManageMemoryGraphs = allowManageMemoryGraphs ?: assistant.allowManageMemoryGraphs,
            referenceRecentChats = recentChatsReference,
        )
    }

    fun referencesAny(): Boolean =
        referenceAssistantMemory || referenceGlobalMemory ||
            referenceAssistantGraph || referenceGlobalGraph ||
            (referenceRecentChats == true)
    fun referencesLegacyAny(): Boolean =
        referenceAssistantMemory || referenceGlobalMemory || (referenceRecentChats == true)
    fun referencesGraphAny(): Boolean = referenceAssistantGraph || referenceGlobalGraph
    fun editsAny(): Boolean =
        allowEditAssistantMemory || allowEditGlobalMemory ||
            allowEditAssistantGraph || allowEditGlobalGraph || allowEditMemoryGraph
    fun editsLegacyAny(): Boolean = allowEditAssistantMemory || allowEditGlobalMemory
    fun editsGraphAny(): Boolean =
        allowEditMemoryGraph || allowEditAssistantGraph || allowEditGlobalGraph
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
