package me.rerere.rikkahub.data.registry

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

private const val TAG = "WorkspaceRegistryStore"
private const val REGISTRY_FILE_NAME = ".registry.json"

class WorkspaceRegistryStore(
    private val workspacesDir: File,
) {
    private val registryFile = File(workspacesDir, REGISTRY_FILE_NAME)
    private val mutex = Mutex()
    private val _workspacesFlow = MutableStateFlow<List<WorkspaceRecord>>(emptyList())

    init {
        workspacesDir.mkdirs()
        loadInitialData()
    }

    fun listFlow(): Flow<List<WorkspaceRecord>> = _workspacesFlow.asStateFlow()

    fun exists(): Boolean = registryFile.exists()

    suspend fun getById(id: String): WorkspaceRecord? = mutex.withLock {
        _workspacesFlow.value.firstOrNull { it.id == id }
    }

    suspend fun getAll(): List<WorkspaceRecord> = mutex.withLock {
        _workspacesFlow.value
    }

    suspend fun upsert(record: WorkspaceRecord) = mutex.withLock {
        val current = _workspacesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            current[index] = record
        } else {
            current.add(record)
        }
        saveLocked(current)
    }

    suspend fun deleteById(id: String): Boolean = mutex.withLock {
        val current = _workspacesFlow.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            saveLocked(current)
        }
        removed
    }

    suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Boolean = mutex.withLock {
        val current = _workspacesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(
                shellStatus = shellStatus,
                updatedAt = updatedAt,
            )
            saveLocked(current)
            true
        } else {
            false
        }
    }

    suspend fun replaceAll(records: List<WorkspaceRecord>) = mutex.withLock {
        saveLocked(records)
    }

    private fun loadInitialData() {
        if (!registryFile.exists()) {
            _workspacesFlow.value = emptyList()
            return
        }
        runCatching {
            val content = registryFile.readText(Charsets.UTF_8)
            val data = JsonInstant.decodeFromString<WorkspaceRegistryData>(content)
            _workspacesFlow.value = data.workspaces.sortedByDescending { it.updatedAt }
        }.onFailure { e ->
            Log.e(TAG, "Failed to read workspace registry from ${registryFile.absolutePath}", e)
            _workspacesFlow.value = emptyList()
        }
    }

    private fun saveLocked(records: List<WorkspaceRecord>) {
        val sorted = records.sortedByDescending { it.updatedAt }
        val data = WorkspaceRegistryData(version = 1, workspaces = sorted)
        val jsonText = JsonInstant.encodeToString(data)

        runCatching {
            val tmpFile = File(workspacesDir, "$REGISTRY_FILE_NAME.tmp")
            tmpFile.writeText(jsonText, Charsets.UTF_8)
            if (tmpFile.exists()) {
                if (registryFile.exists()) {
                    registryFile.delete()
                }
                tmpFile.renameTo(registryFile)
            }
            _workspacesFlow.value = sorted
        }.onFailure { e ->
            Log.e(TAG, "Failed to save workspace registry to ${registryFile.absolutePath}", e)
        }
    }
}
