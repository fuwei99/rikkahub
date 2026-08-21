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

/** 工作区工具（workspace_*）的一行说明，供 tool_manage 目录使用；与 [WorkspaceToolNames] 对齐。 */
val WORKSPACE_TOOL_SUMMARIES: Map<String, String> = mapOf(
    "workspace_read_file" to "Read file contents as UTF-8 text (numbered lines) or image preview (PNG/JPG/WEBP).",
    "workspace_write_file" to "Create or overwrite a UTF-8 text file (makes a restorable backup).",
    "workspace_edit_file" to "Targeted edit of a text file via old/new text or multiple edits.",
    "workspace_apply_patch" to "Apply a Git-style unified diff patch to modify/create/delete/rename text files.",
    "workspace_codex_patch" to "Apply an OpenAI Codex-style *** Begin/End Patch to modify/create/delete/rename files.",
    "workspace_backup" to "Inspect or restore automatic backups created by file-changing tools.",
    "workspace_shell" to "Run a bash command in the workspace sandboxed rootfs (long-running via sessions).",
    "workspace_grep" to "ripgrep-style content search over files (regex, glob, line context).",
    "workspace_shell_session" to "Manage persistent bash sessions for multi-step work, activated virtualenvs, or long builds.",
)

/** 搜索工具（web search + scrape），由 assistant.enableWebSearch 开关控制；列出仅供 UI 展示。 */
val SEARCH_TOOL_NAMES: Set<String> = setOf("search_web", "scrape_web")

/** 生图工具，由 LocalToolOption.ImageGeneration / 模型能力控制。 */
const val IMAGE_GENERATION_TOOL_NAME = "image_generation"
