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
 *
 * 召回参数（topK/权重/跳数/注入上限）原先硬编码在 GenerationHandler，现全部下沉到此处，
 * 便于按模型上下文与 token 预算调参。
 */
@Serializable
data class MemorySearchSettings(
    /** 语义检索 embedding 渠道 id（VectorProviderSetting.id，OpenAI 兼容类型） */
    val embeddingChannelId: Uuid? = null,
    /** 语义检索 embedding 模型 id（Model.id，type=EMBEDDING） */
    val embeddingModelId: Uuid? = null,
    /** embedding 输出维度：Qwen3-Embedding-8B 建议 1024（MRL 降维，MTEB 默认评测维）；火山 doubao 2048 可调 */
    val embeddingDimension: Int = 1024,
    /** 关键词检索（标题命中 +3 / 正文命中 +1 的本地打分，不花钱），关掉后只靠语义 */
    val keywordSearch: Boolean = true,
    /** 全局默认开启语义检索（实际生效还需 MemoryOptions.semanticSearch 会话开关） */
    val semanticSearch: Boolean = false,
    /** 全局默认开启图传播召回（实际生效还需 MemoryOptions.graphExpansion） */
    val graphExpansion: Boolean = false,
    /** 检索结果为空时是否全量注入兜底（§6.3 默认关闭：空检索输出占位不装全量，对齐 Operit shared.js:789-802；需要时可开） */
    val fallbackToAllWhenEmpty: Boolean = false,
    /** 每个 scope 的召回条数（关键词与语义各取 topK 后混合再截断） */
    val topK: Int = 8,
    /** 关键词得分权重（关键词原始分 × 该系数） */
    val keywordWeight: Float = 1f,
    /** 语义得分权重（语义 rank 分 × 该系数，默认 2 表示语义优先） */
    val semanticWeight: Float = 2f,
    /** 混合得分低于该阈值的命中直接丢弃（0 表示不过滤） */
    val minScore: Float = 0f,
    /** 图传播跳数：命中节点向外扩展的层数（0 = 不扩展，只注入命中节点；1 = 只要直接邻居） */
    val expansionHops: Int = 1,
    /** 最终注入的节点数上限（含图传播带出的邻居），防止 prompt 爆掉 */
    val maxInjectNodes: Int = 40,
    /** 单个节点 content 注入时的截断长度（0 = 不截断） */
    val nodeContentMaxChars: Int = 0,
    /** 检索 query 取最近用户消息的前 N 字（过长会拖慢 embedding） */
    val queryMaxChars: Int = 200,
    /** 检索 query 参与计算的最近对话轮数（每轮 = 1 条用户消息，可含其后助手回复）。
     *  默认 1 = 只取最后一条用户消息（旧行为）；调大让近几轮上下文一起参与召回，
     *  对「我是程天赢，我爸爸是谁呀」这类依赖上下文的问法更友好。 */
    val queryRecentTurns: Int = 1,
) {
    /** 参数纠偏：UI 输入与旧配置反序列化后统一收口到合法区间。 */
    fun sanitized(): MemorySearchSettings = copy(
        embeddingDimension = embeddingDimension.coerceIn(64, 4096),
        topK = topK.coerceIn(1, 100),
        keywordWeight = keywordWeight.coerceIn(0f, 10f),
        semanticWeight = semanticWeight.coerceIn(0f, 10f),
        minScore = minScore.coerceAtLeast(0f),
        expansionHops = expansionHops.coerceIn(0, 5),
        maxInjectNodes = maxInjectNodes.coerceIn(1, 500),
        nodeContentMaxChars = nodeContentMaxChars.coerceIn(0, 20000),
        queryMaxChars = queryMaxChars.coerceIn(20, 4000),
        queryRecentTurns = queryRecentTurns.coerceIn(1, 20),
    )
}
