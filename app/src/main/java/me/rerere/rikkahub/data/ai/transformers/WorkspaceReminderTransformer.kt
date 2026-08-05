package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ToolCallingStrategy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val STATIC_CODE_ACTION_PROTOCOL = """
### [CRITICAL] WORKSPACE CODE ACTION PROTOCOL

When creating, editing, or patching files in the workspace, you MUST output code operations using the following raw XML format directly in your text response.

Inside `<parameter name="..." string="true">`, pass raw, UNESCAPED text (DO NOT escape quotes `"`, backslashes `\`, or newlines `\n`). Perfect for code, scripts, and patches.
Inside `<parameter name="..." string="false">`, pass valid JSON for arrays, objects, booleans, or numbers.

AVAILABLE CODE ACTIONS:

1. Write File:
<code_calls>
  <invoke name="workspace_write_file">
    <parameter name="path" string="true">/workspace/hello.py</parameter>
    <parameter name="text" string="true">
def main():
    print("Hello, \"World\"!") # Raw code, NO escaping needed
    </parameter>
  </invoke>
</code_calls>

2. Edit File:
<code_calls>
  <invoke name="workspace_edit_file">
    <parameter name="path" string="true">/workspace/hello.py</parameter>
    <parameter name="old_text" string="true">print("Hello, \"World\"!")</parameter>
    <parameter name="new_text" string="true">print("Hello, RikkaHub!")</parameter>
  </invoke>
</code_calls>

3. Apply Unified Patch:
<code_calls>
  <invoke name="workspace_apply_patch">
    <parameter name="patch" string="true">
--- a/workspace/hello.py
+++ b/workspace/hello.py
@@ -1,2 +1,2 @@
 def main():
-    print("Hello, \"World\"!")
+    print("Hello, RikkaHub!")
    </parameter>
  </invoke>
</code_calls>

RULES:
- DO NOT wrap `<code_calls>` inside markdown code blocks (like ```xml). Output it as raw text.
- Always close all XML tags (`</parameter>`, `</invoke>`, `</code_calls>`).
"""

private fun buildWorkspacePrompt(enabledToolNames: Set<String>): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- Absolute paths always work. Relative paths resolve against the base directory reported as `paths_base` in the `[Environment Context: ...]` line of the latest user message — check it before using a relative path, and prefer absolute paths when in doubt. The same base applies to every file tool, including the `--- a/` / `+++ b/` headers of `workspace_apply_patch`.")
    appendLine("- Only the following workspace tools are currently enabled by the user:")
    enabledToolNames.sorted().forEach { name -> appendLine("  - `$name`") }
    appendLine("- If you know a workspace tool name from earlier context but it is not listed above, do not call it. Tell the user: `The tool is unavailable; it is currently disabled by the user.`")
    if ("workspace_edit_file" in enabledToolNames) appendLine("- Prefer `workspace_edit_file` for single targeted edits.")
    if ("workspace_apply_patch" in enabledToolNames) appendLine("- Prefer `workspace_apply_patch` for multi-file unified diffs.")
    if ("workspace_codex_patch" in enabledToolNames) {
        appendLine("- Prefer `workspace_codex_patch` when using the Codex file-style patch format. Keep the `***` markers inside a code block when writing examples.")
        appendLine("- Minimal Codex patch shape:")
        appendLine("  ```text")
        appendLine("  *** Begin Patch")
        appendLine("  *** Update File: path/to/file")
        appendLine("  @@")
        appendLine("   old line")
        appendLine("  -removed line")
        appendLine("  +added line")
        appendLine("  *** End Patch")
        appendLine("  ```")
    }
    if ("workspace_apply_patch" in enabledToolNames && "workspace_codex_patch" in enabledToolNames) {
        appendLine("- The two patch tools use different formats; choose one by tool name and do not mix them.")
    }
    if ("workspace_shell" in enabledToolNames) appendLine("- Use `workspace_shell` for commands/tests/move/delete operations outside text patches.")
    if ("workspace_shell_session" in enabledToolNames) {
        appendLine("- `workspace_shell_session` opens a persistent shell: run commands with `workspace_shell` + `session_id`. Use it for multi-step work in one directory, activated virtualenvs, or long builds. Close sessions when done.")
    }
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    append("</workspace>")
}.trim()

/**
 * Workspace 系统提示注入转换器
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val enabledToolNames: Set<String>? = null,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages
        val effectiveToolNames = (enabledToolNames ?: workspace.toolDefaultEnabledOverrides()
            .let { overrides ->
                me.rerere.rikkahub.data.ai.tools.WorkspaceToolNames
                    .filter { name -> me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolDefaultEnabled(name, overrides) }
                    .toSet()
            })
            // 归一旧工具名, 避免提示里出现已合并/删除的名字
            .let { me.rerere.rikkahub.data.ai.tools.normalizeWorkspaceToolNames(it) }
        if (effectiveToolNames.isEmpty()) return messages

        val strategy = ctx.model.toolCallingStrategy
        val useCodeActionProtocol = strategy == ToolCallingStrategy.CODE_ACTION || strategy == ToolCallingStrategy.CUSTOM_PROTOCOL
        val staticSystemPrompt = buildString {
            if (useCodeActionProtocol) {
                appendLine(STATIC_CODE_ACTION_PROTOCOL.trim())
                appendLine()
            }
            append(buildWorkspacePrompt(effectiveToolNames))
        }

        // 1. 静态 System Prompt 追加到 System 头部
        val resultMessages = messages.toMutableList()
        val systemIndex = resultMessages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex >= 0) {
            resultMessages[systemIndex] = resultMessages[systemIndex].prependText("$staticSystemPrompt\n\n")
        } else {
            resultMessages.add(0, UIMessage.system(staticSystemPrompt))
        }

        // 2. 动态环境信息绑定到最后一个 User 消息前缀（历史 User 消息保留不动，锁定前缀缓存 Hash）
        val pathsConfig = runCatching { workspaceRepository.getToolConfig(workspaceId).paths }.getOrNull()
        val dynamicContext = buildDynamicContext(workspace, ctx.workspaceCwd, pathsConfig)
        val lastUserIndex = resultMessages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex >= 0 && dynamicContext.isNotBlank()) {
            val lastUserMsg = resultMessages[lastUserIndex]
            // 如果该消息尚未绑定过 Context 标头，才添加前缀（确保幂等与历史固化）
            if (!lastUserMsg.toText().startsWith("[Environment Context:")) {
                resultMessages[lastUserIndex] = lastUserMsg.prependText("$dynamicContext\n\n")
            }
        }

        return resultMessages
    }
}

private fun buildDynamicContext(
    workspace: WorkspaceEntity,
    cwd: String?,
    pathsConfig: me.rerere.workspace.WorkspaceToolConfig.Paths?,
): String = buildString {
    val mounts = workspace.externalMountConfigs()
    append("[Environment Context: workspace=\"${workspace.name}\"")
    if (!cwd.isNullOrBlank()) {
        append(", cwd=\"$cwd\"")
    }
    // 相对路径基准的「实到值」每轮重算, 天然不会过期;
    // 计算优先级必须与 createWorkspaceTools 里的 pathBase 保持一致。
    val pathsBase = cwd?.takeIf { it.isNotBlank() }
        ?: pathsConfig?.relativeBase?.takeIf { it.isNotBlank() }
        ?: "/workspace"
    append(", paths_base=\"$pathsBase\"")
    if (mounts.isNotEmpty()) {
        val mountList = mounts.joinToString(", ") { "${it.normalizedTargetPath()} (${if (it.writable) "rw" else "ro"})" }
        append(", mounts=[$mountList]")
    }
    append("]")
}

private fun UIMessage.prependText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = extra + text.text)
    } else {
        updatedParts.add(0, UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
