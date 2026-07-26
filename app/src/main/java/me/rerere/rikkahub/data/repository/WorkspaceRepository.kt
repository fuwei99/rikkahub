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
import me.rerere.workspace.LEGACY_WORKSPACE_TOOL_CONFIG_PATH
import me.rerere.workspace.WORKSPACE_TOOL_CONFIG_PATH
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceExternalMount
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceRuntimeType
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceToolConfig
import java.io.ByteArrayOutputStream
import java.io.File
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

    suspend fun getToolConfig(id: String): WorkspaceToolConfig = withContext(Dispatchers.IO) {
        val existing = readToolConfigText(id)
        val parsed = existing?.let { raw ->
            runCatching { JsonInstant.decodeFromString<WorkspaceToolConfig>(normalizeJsonc(raw)) }.getOrNull()
        }
        if (parsed != null) return@withContext parsed
        val defaultConfig = WorkspaceToolConfig()
        runCatching { writeToolConfig(id, defaultConfig) }
        defaultConfig
    }

    suspend fun getToolConfigJson(id: String): String = withContext(Dispatchers.IO) {
        readToolConfigText(id) ?: run {
            val config = WorkspaceToolConfig()
            writeToolConfig(id, config)
            defaultToolConfigJsonc(config)
        }
    }

    suspend fun writeToolConfigJson(id: String, rawJson: String) {
        val config = JsonInstant.decodeFromString<WorkspaceToolConfig>(normalizeJsonc(rawJson))
        writeText(
            id = id,
            path = WORKSPACE_TOOL_CONFIG_PATH,
            text = rawJson,
            overwrite = true,
        )
        // Validate by parsing above, but preserve user's comments and formatting in the jsonc file.
        @Suppress("UNUSED_VARIABLE")
        val validated = config
    }

    private suspend fun readToolConfigText(id: String): String? =
        runCatching { readText(id, WORKSPACE_TOOL_CONFIG_PATH) }.getOrNull()
            ?: runCatching { readText(id, LEGACY_WORKSPACE_TOOL_CONFIG_PATH) }.getOrNull()

    private suspend fun writeToolConfig(id: String, config: WorkspaceToolConfig) {
        writeText(
            id = id,
            path = WORKSPACE_TOOL_CONFIG_PATH,
            text = defaultToolConfigJsonc(config),
            overwrite = true,
        )
    }

    private fun defaultToolConfigJsonc(config: WorkspaceToolConfig = WorkspaceToolConfig()): String = """
        {
          "shell": {
            // AI 不传 timeout 时的默认 shell 超时秒数。
            "defaultTimeoutSeconds": ${config.shell.defaultTimeoutSeconds},
            // AI 能请求的最大 shell 超时秒数。
            "maxTimeoutSeconds": ${config.shell.maxTimeoutSeconds},
            // 是否允许后台 shell。后台 shell 用于 dev server、长时间测试、watch 模式。
            "backgroundEnabled": ${config.shell.backgroundEnabled},
            // 每个工作区最多允许几个后台 shell。
            "maxBackgroundProcesses": ${config.shell.maxBackgroundProcesses},
            // 后台命令启动后默认先等待几秒，收集初始输出。
            "backgroundDefaultWaitSeconds": ${config.shell.backgroundDefaultWaitSeconds},
            // AI 单次 wait 最多能等待几秒。
            "backgroundMaxWaitSeconds": ${config.shell.backgroundMaxWaitSeconds},
            // 后台命令最长存活分钟数，超时自动终止。
            "backgroundMaxLifetimeMinutes": ${config.shell.backgroundMaxLifetimeMinutes},
            // shell stdout/stderr 单路最大保留字符数。
            "outputMaxChars": ${config.shell.outputMaxChars},
            // 超长工具输出返回给 LLM 的预览字符数。
            "toolPreviewMaxChars": ${config.shell.toolPreviewMaxChars}
          },
          "readFile": {
            // 默认从第几行开始读。1 表示文件第一行。
            "defaultStartLine": ${config.readFile.defaultStartLine},
            // AI 不指定 line_count 时默认读多少行。
            "defaultLineCount": ${config.readFile.defaultLineCount},
            // 单次 read_file 最多允许读多少行。
            "maxLineCount": ${config.readFile.maxLineCount},
            // AI 不指定 max_chars 时默认最多返回多少字符。
            "defaultMaxChars": ${config.readFile.defaultMaxChars},
            // 单次 read_file 最多允许返回多少字符。
            "hardMaxChars": ${config.readFile.hardMaxChars},
            // read_file 允许读取的最大文件体积，超过会拒绝。
            "maxFileBytes": ${config.readFile.maxFileBytes},
            // 是否在返回内容前加行号，例如：12: text。
            "includeLineNumbers": ${config.readFile.includeLineNumbers}
          },
          "editFile": {
            // 是否启用旧的 edit_file patch 配置预留项。
            "enablePatchMode": ${config.editFile.enablePatchMode},
            // 单次旧 patch 最多允许多少个 edits。
            "maxEditsPerCall": ${config.editFile.maxEditsPerCall},
            // 单次旧 patch JSON 最大字符数。
            "maxPatchChars": ${config.editFile.maxPatchChars},
            // 行号 patch 是否强制要求 old_text 校验。
            "requireOldTextForLinePatch": ${config.editFile.requireOldTextForLinePatch},
            // edit_file 默认是否只预览 diff 不真正写入。
            "dryRunDefault": ${config.editFile.dryRunDefault}
          },
          "patch": {
            // 是否启用 workspace_apply_patch。
            "enabled": ${config.patch.enabled},
            // 单次 unified diff 最大字符数。
            "maxPatchChars": ${config.patch.maxPatchChars},
            // 单次 patch 最多涉及文件数。
            "maxFilesPerPatch": ${config.patch.maxFilesPerPatch},
            // AI 不传 dry_run 时是否默认只预览不写入。
            "dryRunDefault": ${config.patch.dryRunDefault},
            // patch 应用中途失败时是否自动恢复到应用前。false 表示保留已成功应用的部分，并返回 backup_id。
            "rollbackOnFailure": ${config.patch.rollbackOnFailure},
            // 是否允许 git 扩展 unified diff（new file / deleted file / rename）。
            "allowGitExtendedDiff": ${config.patch.allowGitExtendedDiff}
          },
          "backup": {
            // write_file / edit_file / apply_patch / restore 前是否自动备份。
            "enabled": ${config.backup.enabled},
            // 备份保留天数。
            "retentionDays": ${config.backup.retentionDays},
            // 最多保留多少个备份。
            "maxBackups": ${config.backup.maxBackups},
            // 备份总大小上限，超出后删除最旧备份。
            "maxTotalBytes": ${config.backup.maxTotalBytes},
            // 创建备份时是否自动清理过期备份。
            "autoCleanup": ${config.backup.autoCleanup},
            // restore_backup 执行前是否也创建备份，避免撤销操作不可逆。
            "backupBeforeRestore": ${config.backup.backupBeforeRestore}
          }
        }
    """.trimIndent()

    private fun normalizeJsonc(input: String): String = stripTrailingCommas(stripJsonComments(input))

    private fun stripJsonComments(input: String): String {
        val output = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < input.length) {
            val c = input[i]
            if (inString) {
                output.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                output.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < input.length) {
                val next = input[i + 1]
                if (next == '/') {
                    i += 2
                    while (i < input.length && input[i] != '\n') i++
                    if (i < input.length) output.append(input[i++])
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(input.length)
                    continue
                }
            }
            output.append(c)
            i++
        }
        return output.toString()
    }

    private fun stripTrailingCommas(input: String): String {
        val output = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < input.length) {
            val c = input[i]
            if (inString) {
                output.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                output.append(c)
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < input.length && input[j].isWhitespace()) j++
                if (j < input.length && (input[j] == '}' || input[j] == ']')) {
                    i++
                    continue
                }
            }
            output.append(c)
            i++
        }
        return output.toString()
    }


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
            shellStatus = WorkspaceShellStatus.DISABLED.name,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        val finalWorkspace = if (manager.hasRootfs(workspace.root)) {
            workspace.copy(shellStatus = WorkspaceShellStatus.READY.name)
        } else {
            workspace
        }
        dao.upsert(finalWorkspace)
        return finalWorkspace
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
        manager.ensureWorkspace(workspace.root)
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

    suspend fun hasBuiltinRootfs(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.ensureWorkspace(workspace.root)
        manager.hasRootfs(workspace.root)
    }

    suspend fun setExternalMounts(id: String, mounts: List<WorkspaceExternalMount>): Boolean {
        val workspace = dao.getById(id) ?: return false
        val normalized = mounts.map { mount ->
            mount.copy(
                sourcePath = mount.sourcePath.trim(),
                targetPath = mount.normalizedTargetPath(),
                name = mount.name.trim(),
            )
        }.filter { it.isConfigured() }
            .distinctBy { it.normalizedTargetPath() }
        dao.upsert(
            workspace.copy(
                externalMounts = JsonInstant.encodeToString(normalized),
                updatedAt = System.currentTimeMillis(),
            )
        )
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
        workspace.externalWorkspaceMount()?.takeIf { area == WorkspaceStorageArea.FILES }?.let { mount ->
            return@withContext listExternalFiles(mount, path)
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
        workspace.externalWorkspaceMount()?.let { mount ->
            return@withContext externalFile(mount, path).readText(Charsets.UTF_8)
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
        workspace.externalWorkspaceMount()?.let { mount ->
            require(mount.writable) { "External workspace mount is read-only" }
            val file = externalFile(mount, path)
            if (file.exists() && !overwrite) error("File already exists: $path")
            if (file.exists() && !file.isFile) error("Path is not a file: $path")
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            return@withContext file.toWorkspaceEntry(mount)
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
        workspace.externalWorkspaceMount()?.takeIf { area == WorkspaceStorageArea.FILES }?.let { mount ->
            val file = externalFile(mount, path)
            require(file.length() <= MAX_PREVIEW_BYTES) { "文件过大, 无法预览 (${file.length()} bytes)" }
            return@withContext file.readText(Charsets.UTF_8)
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
        workspace.externalWorkspaceMount()?.takeIf { area == WorkspaceStorageArea.FILES }?.let { mount ->
            require(mount.writable) { "External workspace mount is read-only" }
            val safeName = fileName.replace('/', '_').ifBlank { "imported_file" }
            val file = externalFile(mount, listOf(destinationPath.trim('/'), safeName).filter { it.isNotBlank() }.joinToString("/"))
            file.parentFile?.mkdirs()
            inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            return@withContext file.toWorkspaceEntry(mount)
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
        workspace.externalWorkspaceMount()?.takeIf { area == WorkspaceStorageArea.FILES }?.let { mount ->
            val file = externalFile(mount, path)
            require(file.exists()) { "File does not exist: $path" }
            require(file.isFile) { "Path is not a file: $path" }
            return@withContext file.length()
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
        workspace.externalWorkspaceMount()?.takeIf { area == WorkspaceStorageArea.FILES }?.let { mount ->
            val file = externalFile(mount, path)
            require(file.exists()) { "File does not exist: $path" }
            require(file.isFile) { "Path is not a file: $path" }
            outputStream.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
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
            workspace.externalWorkspaceMount()?.takeIf { area == WorkspaceStorageArea.FILES }?.let { mount ->
                require(mount.writable) { "External workspace mount is read-only" }
                val file = externalFile(mount, path)
                return@withContext if (file.isDirectory) file.deleteRecursively() else file.delete()
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
        workspace.externalWorkspaceMount()?.let { mount ->
            require(mount.writable) { "External workspace mount is read-only" }
            val src = externalFile(mount, source)
            val dst = externalFile(mount, target)
            require(src.exists()) { "Source does not exist: $source" }
            if (dst.exists() && !overwrite) error("Target already exists: $target")
            dst.parentFile?.mkdirs()
            if (dst.exists()) {
                if (dst.isDirectory) dst.deleteRecursively() else dst.delete()
            }
            require(src.renameTo(dst)) { "Failed to move: $source" }
            return@withContext dst.toWorkspaceEntry(mount)
        }
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        maxOutputChars: Int = me.rerere.workspace.MAX_OUTPUT_CHARS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor / SSH 轮询并关闭进程
        return runInterruptible(Dispatchers.IO) {
            if (workspace.runtimeTypeValue() == WorkspaceRuntimeType.SSH) {
                workspace.sshClient().execute(command, cwd, timeoutMillis, maxOutputChars, stdin)
            } else {
                manager.ensureWorkspace(workspace.root)
                manager.executeCommand(
                    root = workspace.root,
                    command = command,
                    cwd = cwd,
                    timeoutMillis = timeoutMillis,
                    maxOutputChars = maxOutputChars,
                    stdin = stdin,
                    bindMounts = workspace.externalBindMounts(),
                )
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

    private fun WorkspaceEntity.externalWorkspaceMount(): WorkspaceExternalMount? =
        externalMountConfigs().firstOrNull { it.normalizedTargetPath() == "/workspace" }

    private fun listExternalFiles(mount: WorkspaceExternalMount, path: String): List<WorkspaceFileEntry> {
        val dir = externalFile(mount, path)
        require(dir.exists()) { "Directory does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { it.toWorkspaceEntry(mount) }
    }

    private fun externalFile(mount: WorkspaceExternalMount, relativePath: String): File {
        val source = File(mount.sourcePath).canonicalFile
        val normalized = relativePath.replace('\\', '/').trim().trimStart('/').removePrefix("workspace/")
        require(!normalized.contains('\u0000') && normalized.split('/').none { it == ".." }) {
            "Path escapes external workspace: $relativePath"
        }
        val target = if (normalized.isBlank()) source else File(source, normalized).canonicalFile
        require(target.path == source.path || target.path.startsWith(source.path + File.separator)) {
            "Path escapes external workspace: $relativePath"
        }
        return target
    }

    private fun File.toWorkspaceEntry(mount: WorkspaceExternalMount): WorkspaceFileEntry {
        val source = File(mount.sourcePath).canonicalFile
        val relative = if (canonicalPath == source.canonicalPath) {
            ""
        } else {
            relativeTo(source).path.replace(File.separatorChar, '/')
        }
        return WorkspaceFileEntry(
            path = relative,
            name = if (relative.isBlank()) "/" else name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            updatedAt = lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    fun resolveExternalMountFile(workspace: WorkspaceEntity, rootfsPath: String): Pair<WorkspaceExternalMount, File>? {
        val normalizedPath = rootfsPath.replace('\\', '/').trimEnd('/').ifBlank { "/" }
        val mount = workspace.externalMountConfigs()
            .sortedByDescending { it.normalizedTargetPath().length }
            .firstOrNull { config ->
                val target = config.normalizedTargetPath()
                normalizedPath == target || normalizedPath.startsWith("$target/")
            } ?: return null
        val target = mount.normalizedTargetPath()
        val relative = normalizedPath.removePrefix(target).trimStart('/')
        require(!relative.contains('\u0000') && relative.split('/').none { it == ".." }) {
            "Path escapes external mount: $rootfsPath"
        }
        val source = File(mount.sourcePath).canonicalFile
        val file = if (relative.isBlank()) source else File(source, relative).canonicalFile
        require(file.path == source.path || file.path.startsWith(source.path + File.separator)) {
            "Path escapes external mount: $rootfsPath"
        }
        return mount to file
    }

    private fun WorkspaceEntity.externalBindMounts(): List<WorkspaceBindMount> =
        externalMountConfigs().mapNotNull { mount ->
            val source = File(mount.sourcePath)
            if (source.isDirectory) WorkspaceBindMount(source = source, target = mount.normalizedTargetPath()) else null
        }

    private fun WorkspaceEntity.sshClient(): SshWorkspaceClient =
        SshWorkspaceClient(sshRuntimeConfig())

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
