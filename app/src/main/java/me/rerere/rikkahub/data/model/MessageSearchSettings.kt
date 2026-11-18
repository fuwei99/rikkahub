package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 消息检索索引来源（2026-11-18 江峰）。
 *
 * 用户诉求：不想让 App 内置的 FTS5 全文索引吃 710MB 存储，改由 workspace 上的
 * 常驻检索服务（HTTP 接口）提供检索能力，逻辑外置、可维护、可随意换方案。
 *
 * - [FTS]：App 内置 FTS5（jieba simple tokenizer），索引在 App 本地 SQLite。
 * - [PORT]：外部 HTTP 检索服务，App 只按协议发请求、吃结果，完全不碰本地 FTS 表。
 */
@Serializable
enum class SearchIndexSource {
    FTS,
    PORT,
}

/**
 * 消息检索索引设置。
 *
 * 设计原则（防崩红线）：
 * - 选了 [SearchIndexSource.PORT]，App **绝不加载 / 建表 / 重建** message_fts；
 *   用户删了 fts 表也绝不复活。
 * - 选了 [SearchIndexSource.FTS]，若本地没有索引表，**不贸然建表**——
 *   只有 [autoRebuildIndex] 打开时，才在写入/重建路径上懒建表并重建。
 * - 既没 FTS 表、source 又指向不可用的外部服务 → 搜索返回空，**绝不崩**。
 *
 * @param source 索引来源：App 内置 FTS 或外部端口服务
 * @param portUrl 外部检索服务完整 URL（如 http://127.0.0.1:8998/search），PORT 模式必填
 * @param portToken 外部服务 Bearer Token，留空则不带 Authorization 头
 * @param autoRebuildIndex FTS 模式下：本地无索引时是否自动建表并重建（对齐旧行为）。
 *                         关闭 = 没索引就一直不建，搜索返回空。
 */
@Serializable
data class MessageSearchSettings(
    val source: SearchIndexSource = SearchIndexSource.FTS,
    val portUrl: String = "",
    val portToken: String = "",
    val autoRebuildIndex: Boolean = true,
)
