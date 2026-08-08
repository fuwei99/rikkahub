package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryGraphScope {
    ASSISTANT,
    GLOBAL,
}

/**
 * 图种类。ASSISTANT / GLOBAL 为内置图（builtin，不可删只能清空），CUSTOM 为用户或 AI 自建。
 * 迁移后内置图在注册表里就是两条普通记录，链路代码不为它们留 if-else 分支。
 */
@Serializable
enum class MemoryGraphKind {
    ASSISTANT,
    GLOBAL,
    CUSTOM;

    companion object {
        fun fromWire(value: String?): MemoryGraphKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CUSTOM
    }
}

/** 建图来源，用于管理页「一键清理 AI 创建的空图」与 AI 建图配额。 */
@Serializable
enum class MemoryGraphCreator {
    USER,
    AI;

    companion object {
        fun fromWire(value: String?): MemoryGraphCreator =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: USER
    }
}

/**
 * 图注册表元数据（`memory_graph` 表的领域模型）。
 *
 * [id] 是 canonical graph id，**同时就是 memory_graph_node/link 的 scope**；
 * [slug] 只是给用户与 tool 用的可读引用，解析（[MemoryGraphRegistry.resolve]）之后
 * 全链路只传 [id]，不允许 repository / 注入 / trace 同时接受 id/slug/别名。
 */
@Serializable
data class MemoryGraphMeta(
    val id: String,
    val slug: String,
    val name: String,
    val description: String = "",
    val kind: MemoryGraphKind = MemoryGraphKind.CUSTOM,
    val boundAssistantId: String? = null,
    val emoji: String? = null,
    val builtin: Boolean = false,
    val createdBy: MemoryGraphCreator = MemoryGraphCreator.USER,
    val sortOrder: Int = 0,
    /** 自动提炼落点候选（显式字段，取代「writable + sortOrder 最高」的隐式非确定规则） */
    val autoExtractTarget: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 注入块 / 目录里给模型看的短标识：优先 slug，退化到 id。 */
    val wireId: String get() = slug.ifBlank { id }
}

/**
 * 助手 / 对话对某张图的绑定。只有两个开关，对应 UI 上的「启用」与「可编辑」。
 */
@Serializable
data class MemoryGraphBinding(
    val graphId: String,
    /** 参与注入检索 */
    val enabled: Boolean = true,
    /** AI 可用 memory_tool 增删改此图 */
    val writable: Boolean = false,
)

/** Resolver 输出：本轮真正生效的绑定（已解析成 canonical meta，已应用运行时总闸与图数上限）。 */
data class ResolvedGraphBinding(
    val meta: MemoryGraphMeta,
    val enabled: Boolean,
    val writable: Boolean,
)

/**
 * 匹配资格分层（match eligibility tier）：
 * - [ALWAYS]：常驻池，始终参与关键词/语义匹配；
 * - [GATED]：门控池，默认不参与任何匹配，直到关联节点激活它（邻居激活制：
 *   单连边被命中、或激活邻居的 link 权重和达到解锁阈值、或 query 直接点名标题）。
 *
 * 这是「节点匹配资格分层」方案的核心：随着节点增多，事件明细/一次性物品等
 * 低频细节默认进锁池，关键词与语义检索只扫常驻池，上下文增长被锁死在
 * 「已激活语境」内（否则一个角色名能命中整张图）。
 */
object MemoryGraphMatchEligibility {
    const val ALWAYS = 0
    const val GATED = 1

    fun wire(value: Int?): String? = when (value) {
        ALWAYS -> "always"
        GATED -> "gated"
        else -> null
    }

    fun fromWire(value: String?): Int = when (value?.trim()?.lowercase()) {
        "gated" -> GATED
        "always", "" -> ALWAYS
        else -> ALWAYS
    }
}

@Serializable
data class MemoryGraphNode(
    val id: Long,
    val scope: String,
    val title: String,
    val content: String,
    val importance: Float = 0.5f,
    /** [MemoryGraphMatchEligibility]: ALWAYS / GATED */
    val matchEligibility: Int = MemoryGraphMatchEligibility.ALWAYS,
    val folderPath: String? = null,
)

@Serializable
data class MemoryGraphLink(
    val id: Long,
    val scope: String,
    val sourceId: Long,
    val targetId: Long,
    val sourceTitle: String = "",
    val targetTitle: String = "",
    val type: String = "related",
    val weight: Float = 0.7f,
    val description: String = "",
)

data class MemoryGraphData(
    val nodes: List<MemoryGraphNode> = emptyList(),
    val links: List<MemoryGraphLink> = emptyList(),
)

data class MemoryGraphSearchHit(
    val node: MemoryGraphNode,
    val score: Float,
)
