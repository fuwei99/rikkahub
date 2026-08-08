package me.rerere.rikkahub.data.ai.tools

/**
 * 已知工作区工具的稳定名字集合（监督白/黑名单用，见 PLAN_SUPERVISION_LOCK §4）。
 * 与 [WorkspaceTools.kt] 中各 Tool 的 name 保持同步。
 */
val WORKSPACE_TOOL_NAMES: Set<String> = setOf(
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_codex_patch",
    "workspace_apply_patch",
    "workspace_shell",
    "workspace_shell_session",
    "workspace_grep",
    "workspace_backup",
)

/** 搜索工具（web search + scrape），由 assistant.enableWebSearch 开关控制；列出仅供 UI 展示。 */
val SEARCH_TOOL_NAMES: Set<String> = setOf("search_web", "scrape_web")

/** 生图工具，由 LocalToolOption.ImageGeneration / 模型能力控制。 */
const val IMAGE_GENERATION_TOOL_NAME = "image_generation"
