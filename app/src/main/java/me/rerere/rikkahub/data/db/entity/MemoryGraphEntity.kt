package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆图注册表（方案 2026-08-07「记忆图多图体系与动态挂载」阶段一）。
 *
 * 节点表 / 边表的 `scope` 本来就是自由字符串，所以「2 张图 → N 张图」不需要搬一条数据，
 * 只是给每个 scope 补一行索引记录：
 * - [id] = canonical graph id = 节点表的 scope（助手图 = assistant uuid，全局图 = `__global__`，自建 = 随机 uuid）；
 * - [slug] 仅作为用户 / tool 的可读引用入口，resolve 之后全链路只传 [id]。
 *
 * 迁移期 slug 直接写 scope 值（天然唯一，避开 UNIQUE 冲突崩库），
 * 由应用层 `MemoryGraphRegistry.ensureAssistantGraph()` 懒规范化。
 */
@Entity(
    tableName = "memory_graph",
    indices = [Index(value = ["slug"], unique = true), Index(value = ["kind"])],
)
data class MemoryGraphEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("slug")
    val slug: String,
    @ColumnInfo("name")
    val name: String,
    /** 多图选择阶段的唯一依据：空描述的图等于永不被召回，故 registry 强制非空。 */
    @ColumnInfo("description")
    val description: String,
    /** ASSISTANT / GLOBAL / CUSTOM */
    @ColumnInfo("kind")
    val kind: String,
    /** kind=ASSISTANT 时的宿主助手 id，其余为 null */
    @ColumnInfo("bound_assistant_id")
    val boundAssistantId: String? = null,
    @ColumnInfo("emoji")
    val emoji: String? = null,
    /** 内置图（助手图 / 全局图）不可删，只能清空 */
    @ColumnInfo("builtin")
    val builtin: Boolean = false,
    /** USER / AI，用于管理页一键清理 AI 建的空图与 AI 建图配额 */
    @ColumnInfo("created_by")
    val createdBy: String = "USER",
    /** 注入额度分配与目录排序，大者先吃额度 */
    @ColumnInfo("sort_order")
    val sortOrder: Int = 0,
    /** 自动提炼落点候选：显式字段取代「writable + sortOrder 最高」的隐式非确定规则 */
    @ColumnInfo("auto_extract_target")
    val autoExtractTarget: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
