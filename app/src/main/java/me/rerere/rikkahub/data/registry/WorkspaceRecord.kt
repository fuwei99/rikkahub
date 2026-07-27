package me.rerere.rikkahub.data.registry

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceRuntimeType

@Serializable
data class WorkspaceRecord(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    val toolApprovals: String = "{}",
    val runtimeType: String = WorkspaceRuntimeType.BUILTIN_PROOT.name,
    val runtimeConfig: String = "{}",
    val externalMounts: String = "[]",
) {
    fun toEntity(): WorkspaceEntity = WorkspaceEntity(
        id = id,
        name = name,
        root = root,
        shellStatus = shellStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        toolApprovals = toolApprovals,
        runtimeType = runtimeType,
        runtimeConfig = runtimeConfig,
        externalMounts = externalMounts,
    )

    companion object {
        fun fromEntity(entity: WorkspaceEntity): WorkspaceRecord = WorkspaceRecord(
            id = entity.id,
            name = entity.name,
            root = entity.root,
            shellStatus = entity.shellStatus,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            lastAccessAt = entity.lastAccessAt,
            toolApprovals = entity.toolApprovals,
            runtimeType = entity.runtimeType,
            runtimeConfig = entity.runtimeConfig,
            externalMounts = entity.externalMounts,
        )
    }
}

@Serializable
data class WorkspaceRegistryData(
    val version: Int = 1,
    val workspaces: List<WorkspaceRecord> = emptyList(),
)
