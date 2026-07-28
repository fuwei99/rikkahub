package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.SshWorkspaceConfig
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceExternalMount
import me.rerere.workspace.WorkspaceRuntimeType
import me.rerere.workspace.WorkspaceShellStatus

const val TOOL_DEFAULT_ENABLED_PREFIX = "__default_enabled__:"

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo("runtime_type", defaultValue = "BUILTIN_PROOT")
    val runtimeType: String = WorkspaceRuntimeType.BUILTIN_PROOT.name,
    @ColumnInfo("runtime_config", defaultValue = "{}")
    val runtimeConfig: String = "{}",
    @ColumnInfo("external_mounts", defaultValue = "[]")
    val externalMounts: String = "[]",
) {
    fun runtimeTypeValue(): WorkspaceRuntimeType = runCatching {
        WorkspaceRuntimeType.valueOf(runtimeType)
    }.getOrDefault(WorkspaceRuntimeType.BUILTIN_PROOT)

    fun sshRuntimeConfig(): SshWorkspaceConfig = runCatching {
        JsonInstant.decodeFromString<SshWorkspaceConfig>(runtimeConfig)
    }.getOrDefault(SshWorkspaceConfig())

    fun externalMountConfigs(): List<WorkspaceExternalMount> = runCatching {
        JsonInstant.decodeFromString<List<WorkspaceExternalMount>>(externalMounts)
    }.getOrDefault(emptyList())
        .filter { it.isConfigured() }
        .distinctBy { it.normalizedTargetPath() }

    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())
        .filterKeys { !it.startsWith(TOOL_DEFAULT_ENABLED_PREFIX) }

    fun toolDefaultEnabledOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())
        .filterKeys { it.startsWith(TOOL_DEFAULT_ENABLED_PREFIX) }
        .mapKeys { it.key.removePrefix(TOOL_DEFAULT_ENABLED_PREFIX) }

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
