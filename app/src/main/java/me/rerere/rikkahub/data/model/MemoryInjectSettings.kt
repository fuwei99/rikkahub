package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 注入选择器设置（方案 2026-08-06「LLM 注入选择器替代向量检索」）。
 *
 * 思路：记忆图规模很小（几十个节点、上千字），根本不需要向量库——
 * 把整份「节点目录 + 关系行 + 最近几轮对话」发给一个免费的轻量 LLM，
 * 让它直接回答「本轮该注入哪些 id」，比 embedding + HNSW 那套状态机可靠得多。
 *
 * 开关关闭时链路与旧版完全一致（关键词 + 可选语义向量），代码不删一行，随时可切回。
 */
@Serializable
data class MemoryInjectSettings(
    /** 用 LLM 选择器做注入召回（取代关键词/语义打分；失败可回落） */
    val enabled: Boolean = false,
    /** 目录里最多列多少个节点（超出按 importance 高→低截断） */
    val maxCandidateNodes: Int = 300,
    /**
     * 目录节点总预算（全部图合计）。
     *
     * maxCandidateNodes 是 **per-graph** 的，挂 10 张图就是 10×N 节点全文进 selector prompt，
     * 而注入模型每轮都调 —— 这条直接烧钱。故加一个全局硬预算，按 sortOrder 逐图吃额度，
     * 单图另受 min(maxCandidateNodes, ceil(total/graphCount)) 约束。
     */
    val catalogTotalMaxNodes: Int = 300,
    /** 目录里每条正文截断长度（0 = 全量） */
    val candidateContentMaxChars: Int = 200,
    /** 目录是否附带关系行（`a -type-> b`），帮模型顺着关系挑人 */
    val includeLinks: Boolean = true,
    /** 发给选择器的最近对话轮数（1 轮 = 1 条用户消息 + 其后助手回复） */
    val recentTurns: Int = 3,
    /** 发给选择器的对话上下文总字数上限 */
    val contextMaxChars: Int = 2000,
    /** 选择器最多可选出的节点数（防止它无脑全选） */
    val maxSelectNodes: Int = 12,
    /** 单次选择调用超时（秒） */
    val timeoutSeconds: Int = 45,
    /** 调用/解析失败时回落到旧的关键词 + 语义召回 */
    val fallbackToKeywordOnFailure: Boolean = true,
) {
    fun sanitized(): MemoryInjectSettings = copy(
        maxCandidateNodes = maxCandidateNodes.coerceIn(1, 5000),
        catalogTotalMaxNodes = catalogTotalMaxNodes.coerceIn(1, 5000),
        candidateContentMaxChars = candidateContentMaxChars.coerceIn(0, 20000),
        recentTurns = recentTurns.coerceIn(1, 20),
        contextMaxChars = contextMaxChars.coerceIn(50, 20000),
        maxSelectNodes = maxSelectNodes.coerceIn(1, 500),
        timeoutSeconds = timeoutSeconds.coerceIn(5, 600),
    )
}
