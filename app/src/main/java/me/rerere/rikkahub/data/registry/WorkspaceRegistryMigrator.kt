package me.rerere.rikkahub.data.registry

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File

private const val TAG = "WorkspaceMigrator"
private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

class WorkspaceRegistryMigrator(
    private val registryStore: WorkspaceRegistryStore,
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val workspacesDir: File,
) {
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        // registry.json was introduced as the source of truth.  An empty file is not
        // sufficient evidence that migration already happened: on restore/sync the
        // Room database can be populated after the first boot.  Re-check legacy rows
        // so a one-time empty migration cannot permanently orphan the workspace binding.
        runCatching {
            val existing = registryStore.getAll()
            if (existing.isNotEmpty()) return@runCatching
            val legacyEntities = dao.getAll()
            if (legacyEntities.isNotEmpty()) {
                val records = legacyEntities.map { WorkspaceRecord.fromEntity(it) }
                registryStore.replaceAll(records)
                Log.i(TAG, "Recovered ${records.size} workspaces from Room into registry.json")
            } else if (!registryStore.exists()) {
                registryStore.replaceAll(emptyList())
                Log.i(TAG, "No legacy workspaces in Room; initialized empty registry.json")
            } else {
                Log.i(TAG, "Workspace registry and Room are both empty; keeping registry.json")
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to migrate legacy workspaces from Room database", e)
        }
    }

    suspend fun reconcileAfterRestore() = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Purge any workspace records restored into Room DB from cloud backup
            dao.deleteAll()
            Log.i(TAG, "Purged workspace entities from restored Room database")

            // 2. Reverse scan workspaces directory to recover orphan workspace folders
            val knownRoots = registryStore.getAll().map { it.root }.toSet()
            val entries = workspacesDir.listFiles().orEmpty()
            for (dir in entries) {
                if (!dir.isDirectory) continue
                val rootName = dir.name
                if (rootName == WorkspaceManager.SHARED_ROOTFS_DIR || rootName.startsWith(".")) continue
                if (!rootName.matches(ROOT_NAME_REGEX)) continue

                if (rootName !in knownRoots) {
                    Log.w(TAG, "Found orphan workspace directory on disk: $rootName, auto-recovering...")
                    val now = System.currentTimeMillis()
                    val record = WorkspaceRecord(
                        id = rootName,
                        name = "Recovered ${rootName.take(8)}",
                        root = rootName,
                        shellStatus = WorkspaceShellStatus.BROKEN.name,
                        createdAt = dir.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                    registryStore.upsert(record)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to reconcile workspace registry after restore", e)
        }
    }
}
