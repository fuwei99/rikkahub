package me.rerere.workspace

import kotlinx.serialization.Serializable

const val WORKSPACE_TOOL_CONFIG_PATH = ".rikkahub/workspace_config.jsonc"
const val LEGACY_WORKSPACE_TOOL_CONFIG_PATH = ".rikkahub/workspace_config.json"

@Serializable
data class WorkspaceToolConfig(
    val shell: Shell = Shell(),
    val readFile: ReadFile = ReadFile(),
    val editFile: EditFile = EditFile(),
    val patch: Patch = Patch(),
    val backup: Backup = Backup(),
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
        // ---- 交互式会话 ----
        val sessionEnabled: Boolean = true,
        val maxSessions: Int = 2,
        val sessionIdleTimeoutMinutes: Long = 60,
        val sessionDefaultTimeoutSeconds: Long = 30,
        val sessionMaxTimeoutSeconds: Long = 600,
        /**
         * 会话是否优先使用 pty。
         * pty 才能让 `\u0003` 变成真正的 SIGINT 并投递给整个前台进程组(可中断
         * `while true` 这类 bash 自身的循环); 关掉则退化为管道会话, 中断能力受限。
         * termux JNI 不可用时会自动降级, 无需手动关闭。
         */
        val sessionPtyEnabled: Boolean = true,
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

    @Serializable
    data class Patch(
        val enabled: Boolean = true,
        val maxPatchChars: Int = 200_000,
        val maxFilesPerPatch: Int = 50,
        val dryRunDefault: Boolean = false,
        val rollbackOnFailure: Boolean = false,
        val allowGitExtendedDiff: Boolean = true,
    )

    @Serializable
    data class Backup(
        val enabled: Boolean = true,
        val retentionDays: Int = 7,
        val maxBackups: Int = 100,
        val maxTotalBytes: Long = 512L * 1024 * 1024,
        val autoCleanup: Boolean = true,
        val backupBeforeRestore: Boolean = true,
    )
}
