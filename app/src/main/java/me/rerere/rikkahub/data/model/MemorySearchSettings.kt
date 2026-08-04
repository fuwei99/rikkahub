package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 记忆检索全局设置（记忆图 Phase 2）。
 *
 * embedding 渠道设计：复用独立的「向量模型服务」配置区块（`Settings.vectorProviders`，
 * 与生图/搜索/语音服务并列；预置火山方舟 Plan 订阅 /api/plan/v3、免费额度 /api/v3、
 * Fireworks qwen3-embedding-8b，也可自添阿里百炼/智谱/OpenAI 等任意 OpenAI 兼容端点）。
 * `embeddingChannelId` + `embeddingModelId` 引用 `Settings.vectorProviders` 里的渠道与
 * EMBEDDING 类型模型，切换渠道零代码改动。
 */
@Serializable
data class MemorySearchSettings(
    /** 语义检索 embedding 渠道 id（VectorProviderSetting.id，OpenAI 兼容类型） */
    val embeddingChannelId: Uuid? = null,
    /** 语义检索 embedding 模型 id（Model.id，type=EMBEDDING） */
    val embeddingModelId: Uuid? = null,
    /** embedding 输出维度：Qwen3-Embedding-8B 建议 1024（MRL 降维，MTEB 默认评测维）；火山 doubao 2048 可调 */
    val embeddingDimension: Int = 1024,
    /** 全局默认开启语义检索（实际生效还需 MemoryOptions.semanticSearch 会话开关） */
    val semanticSearch: Boolean = false,
    /** 全局默认开启图传播召回（实际生效还需 MemoryOptions.graphExpansion） */
    val graphExpansion: Boolean = false,
    /** 检索结果为空时是否全量注入兜底（Plan §4.2 第 5 条） */
    val fallbackToAllWhenEmpty: Boolean = true,
)
