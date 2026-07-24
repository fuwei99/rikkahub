package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.SshWorkspaceClient
import me.rerere.workspace.SshWorkspaceConfig
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceRuntimeType
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val runtimeType = workspace.runtimeTypeValue()
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                if (runtimeType == WorkspaceRuntimeType.SSH) {
                    // 外部运行时不依赖本地 rootfs，恢复备份后本地目录缺失时补一个空壳即可。
                    manager.ensureWorkspace(workspace.root)
                    continue
                }
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if (runtimeType == WorkspaceRuntimeType.BUILTIN_PROOT &&
                (statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name) &&
                !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun setBuiltinRuntime(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val status = if (manager.hasRootfs(workspace.root)) {
            WorkspaceShellStatus.READY.name
        } else {
            WorkspaceShellStatus.DISABLED.name
        }
        dao.upsert(
            workspace.copy(
                runtimeType = WorkspaceRuntimeType.BUILTIN_PROOT.name,
                runtimeConfig = "{}",
                shellStatus = status,
                updatedAt = System.currentTimeMillis(),
            )
        )
        manager.ensureWorkspace(workspace.root)
        return true
    }

    suspend fun setSshRuntime(id: String, config: SshWorkspaceConfig): Boolean {
        val workspace = dao.getById(id) ?: return false
        require(config.isConfigured()) {
            "SSH runtime requires host, port, username, and password or private key"
        }
        dao.upsert(
            workspace.copy(
                runtimeType = WorkspaceRuntimeType.SSH.name,
                runtimeConfig = JsonInstant.encodeToString(config),
                shellStatus = WorkspaceShellStatus.READY.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
        manager.ensureWorkspace(workspace.root)
        return true
    }

    suspend fun testSshRuntime(config: SshWorkspaceConfig): WorkspaceCommandResult = runInterruptible(Dispatchers.IO) {
        SshWorkspaceClient(config).test()
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        require(workspace.runtimeTypeValue() == WorkspaceRuntimeType.BUILTIN_PROOT) {
            "Rootfs installation is only available for the built-in runtime"
        }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            require(area == WorkspaceStorageArea.FILES) { "SSH runtime only supports the workspace files area" }
            return@withContext workspace.sshClient().listFiles(path)
        }
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            return@withContext workspace.sshClient().readBytes(path).toString(Charsets.UTF_8)
        }
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            return@withContext workspace.sshClient().writeBytes(path, text.toByteArray(Charsets.UTF_8), overwrite)
        }
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            require(area == WorkspaceStorageArea.FILES) { "SSH runtime only supports the workspace files area" }
            val size = workspace.sshClient().fileSize(path)
            require(size <= MAX_PREVIEW_BYTES) {
                "文件过大, 无法预览 (${size} bytes)"
            }
            return@withContext workspace.sshClient().readBytes(path).toString(Charsets.UTF_8)
        }
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            require(area == WorkspaceStorageArea.FILES) { "SSH runtime only supports the workspace files area" }
            return@withContext workspace.sshClient().importFile(destinationPath, fileName, inputStream)
        }
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            require(area == WorkspaceStorageArea.FILES) { "SSH runtime only supports the workspace files area" }
            return@withContext workspace.sshClient().fileSize(path)
        }
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            require(area == WorkspaceStorageArea.FILES) { "SSH runtime only supports the workspace files area" }
            workspace.sshClient().exportFile(path, outputStream)
            return@withContext
        }
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
                require(area == WorkspaceStorageArea.FILES) { "SSH runtime only supports the workspace files area" }
                return@withContext workspace.sshClient().deleteFile(path, recursive)
            }
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
            return@withContext workspace.sshClient().moveFile(source, target, overwrite)
        }
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor / SSH 轮询并关闭进程
        return runInterruptible(Dispatchers.IO) {
            if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
                workspace.sshClient().execute(command, cwd, timeoutMillis, stdin)
            } else {
                manager.ensureWorkspace(workspace.root)
                manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun WorkspaceEntity.sshClient(): SshWorkspaceClient =
        SshWorkspaceClient(sshRuntimeConfig())

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
