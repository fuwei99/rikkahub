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
