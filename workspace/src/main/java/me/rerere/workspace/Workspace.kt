package me.rerere.workspace

import kotlinx.serialization.Serializable

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceRuntimeType {
    BUILTIN_PROOT,
    SSH,
}

@Serializable
data class SshWorkspaceConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val workDir: String = "~/rikkahub-workspaces/default",
    val strictHostKeyChecking: Boolean = false,
    val connectTimeoutMillis: Int = 15_000,
) {
    fun isConfigured(): Boolean =
        host.isNotBlank() && username.isNotBlank() && port in 1..65535 &&
            (password.isNotBlank() || privateKey.isNotBlank())
}


@Serializable
data class WorkspaceExternalMount(
    val name: String = "",
    /**
     * 挂载点说明, 唯一用途是注入 workspace 系统提示词的 [Environment Context] 行。
     *
     * 光给 `/mnt/Flashcard (rw)` 这种裸路径, 模型只能靠猜里面是题库还是备份, 往往要
     * 先试探性 read 一轮才敢动手。一句人话说明就能省掉那一轮。
     *
     * [name] 刻意不进提示词 —— 它是给用户自己看的标签, 与说明重复, 只占 token。
     * 留空则不注入。仅作文档用途, 不参与任何路径校验。
     */
    val description: String = "",
    val sourcePath: String = "",
    val targetPath: String = "",
    val writable: Boolean = false,
    val autoApproveWrites: Boolean = false,
) {
    fun normalizedTargetPath(): String = targetPath.trim().replace('\\', '/').let { path ->
        val withSlash = if (path.startsWith("/")) path else "/$path"
        withSlash.trimEnd('/').ifBlank { "/" }
    }

    fun isConfigured(): Boolean {
        val target = normalizedTargetPath()
        return sourcePath.isNotBlank() &&
            target != "/" &&
            !target.contains('\u0000') &&
            target.split('/').none { it == ".." }
    }
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
