package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    // ---- 记忆图 P3：LLM 自动抽取写回的结构化字段（v36 补列，均可空；代码层 ?: 兜底）----
    /** 标题（抽取器 findMemoryByTitle / merge 去重用；老记忆为 null 时回落 content 首行匹配） */
    @ColumnInfo("title")
    val title: String? = null,
    /** 重要度 0-1（main 节点默认 0.8） */
    @ColumnInfo("importance")
    val importance: Float? = null,
    /** 可信度 0-1（main 节点默认 1.0） */
    @ColumnInfo("credibility")
    val credibility: Float? = null,
    /** 分类文件夹（P1 可空列，抽取器 main/new 可选写入） */
    @ColumnInfo("folder_path")
    val folderPath: String? = null,
    /** update 时的旧内容历史（JSON 数组，最多 10 条），支持回溯（方案 §8.3 第 4 条） */
    @ColumnInfo("history")
    val history: String? = null,
)
