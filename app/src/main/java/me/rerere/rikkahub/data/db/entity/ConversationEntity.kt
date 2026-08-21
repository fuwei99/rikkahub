package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    /**
     * 对话级记忆图绑定 JSON。
     * ''   = 未设置（继承助手配置）；'[]' = 明确关闭所有图；非空数组 = 显式绑定。
     * 用空串承载 nullable 语义，避免 Room 增列时还要处理 NULL。
     */
    @ColumnInfo("memory_graph_bindings", defaultValue = "")
    val memoryGraphBindings: String = "",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("model_id", defaultValue = "")
    val modelId: String = "",
    // ---- 对话级能力覆盖（2026-08-18）----
    // 全部用空串承载 nullable 语义（与 memory_graph_bindings 同一套约定）：
    // ''  = 未设置，继承助手默认；非空 = 本对话显式覆盖。
    // 集合类字段的 '[]' 是合法值，表示「本对话明确全部关闭」，不可与 '' 混同。
    @ColumnInfo("reasoning_level", defaultValue = "")
    val reasoningLevel: String = "",
    /** ''=继承, '1'=开, '0'=关 */
    @ColumnInfo("enable_web_search", defaultValue = "")
    val enableWebSearch: String = "",
    @ColumnInfo("enabled_skills", defaultValue = "")
    val enabledSkills: String = "",
    @ColumnInfo("local_tools", defaultValue = "")
    val localTools: String = "",
    @ColumnInfo("workspace_tools", defaultValue = "")
    val workspaceTools: String = "",
    @ColumnInfo("mcp_tools", defaultValue = "")
    val mcpTools: String = "",
    /** 对话级 MCP server 挂载（2026-08-21 下沉）：''=继承助手，'[]'=本对话全不挂 */
    @ColumnInfo("mcp_servers", defaultValue = "")
    val mcpServers: String = "",
    @ColumnInfo("memory_options", defaultValue = "")
    val memoryOptions: String = "",
)
