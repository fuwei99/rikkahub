package me.rerere.workspace

import kotlinx.serialization.Serializable

const val WORKSPACE_TOOL_CONFIG_PATH = ".rikkahub/workspace_config.jsonc"
const val LEGACY_WORKSPACE_TOOL_CONFIG_PATH = ".rikkahub/workspace_config.json"

@Serializable
data class WorkspaceToolConfig(
    val shell: Shell = Shell(),
    val readFile: ReadFile = ReadFile(),
    val editFile: EditFile = EditFile(),
) {
    @Serializable
    data class Shell(
        val defaultTimeoutSeconds: Long = 30,
        val maxTimeoutSeconds: Long = 600,
        val backgroundEnabled: Boolean = true,
        val maxBackgroundProcesses: Int = 3,
        val backgroundDefaultWaitSeconds: Long = 5,
        val backgroundMaxWaitSeconds: Long = 60,
        val backgroundMaxLifetimeMinutes: Long = 20,
        val outputMaxChars: Int = 128 * 1024,
        val toolPreviewMaxChars: Int = 4 * 1024,
    )

    @Serializable
    data class ReadFile(
        val defaultStartLine: Int = 1,
        val defaultLineCount: Int = 400,
        val maxLineCount: Int = 2_000,
        val defaultMaxChars: Int = 20_000,
        val hardMaxChars: Int = 60_000,
        val maxFileBytes: Long = 8L * 1024 * 1024,
        val includeLineNumbers: Boolean = true,
    )

    @Serializable
    data class EditFile(
        val enablePatchMode: Boolean = true,
        val maxEditsPerCall: Int = 20,
        val maxPatchChars: Int = 120_000,
        val requireOldTextForLinePatch: Boolean = false,
        val dryRunDefault: Boolean = false,
    )
}
