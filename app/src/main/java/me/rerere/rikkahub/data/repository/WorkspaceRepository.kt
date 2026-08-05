package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.TOOL_DEFAULT_ENABLED_PREFIX
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.registry.WorkspaceRecord
import me.rerere.rikkahub.data.registry.WorkspaceRegistryStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.SshWorkspaceClient
import me.rerere.workspace.SshWorkspaceConfig
import me.rerere.workspace.LEGACY_WORKSPACE_TOOL_CONFIG_PATH
import me.rerere.workspace.WORKSPACE_TOOL_CONFIG_PATH
import me.rerere.workspace.WorkspaceBackgroundProcess
import me.rerere.workspace.WorkspaceBackgroundProcessRegistry
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceExternalMount
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceRuntimeType
import me.rerere.workspace.SessionExecResult
import me.rerere.workspace.WorkspaceSessionProtocol
import me.rerere.workspace.WorkspaceSessionChannel
import me.rerere.rikkahub.data.workspace.WorkspacePtySession
import me.rerere.workspace.WorkspaceSessionRegistry
import me.rerere.workspace.WorkspaceSessionState
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceToolConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val registryStore: WorkspaceRegistryStore,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val appContext: android.content.Context,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = registryStore.listFlow().map { records ->
        records.map { it.toEntity() }
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = registryStore.getAll().map { it.toEntity() }
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

    suspend fun getById(id: String): WorkspaceEntity? = registryStore.getById(id)?.toEntity()

    suspend fun getAll(): List<WorkspaceEntity> = registryStore.getAll().map { it.toEntity() }

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
            "toolPreviewMaxChars": ${config.shell.toolPreviewMaxChars},
            // 是否允许交互式 shell 会话(workspace_shell 的 session_id 参数与 workspace_shell_session)。
            "sessionEnabled": ${config.shell.sessionEnabled},
            // 每个工作区最多允许几个并存的交互式会话。会话常驻内存与 fd，不宜过多。
            "maxSessions": ${config.shell.maxSessions},
            // 交互式会话空闲多少分钟后自动回收（有读写即刷新，不受总寿命限制）。
            "sessionIdleTimeoutMinutes": ${config.shell.sessionIdleTimeoutMinutes},
            // 会话内执行命令时，AI 不传 timeout 的默认等待秒数。超时不杀命令，可续读。
            "sessionDefaultTimeoutSeconds": ${config.shell.sessionDefaultTimeoutSeconds},
            // 会话内执行命令时，AI 单次最多能等待几秒。
            "sessionMaxTimeoutSeconds": ${config.shell.sessionMaxTimeoutSeconds}
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
            // 是否启用 workspace_apply_patch 和 workspace_codex_patch。
            "enabled": ${config.patch.enabled},
            // 单次补丁最大字符数（unified diff 与 Codex patch 共用）。
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
          },
          "paths": {
            // 相对路径基准目录，对所有文件工具（read/write/edit/apply_patch/grep）统一生效。
            // 优先级：会话 cwd（在聊天页选目录）> 此项 > "/workspace"。
            // 适合常驻某个子目录的工作区，免去每个会话手动切 cwd。
            // 必须是 /workspace 或已配置挂载点之内的绝对路径，非法值自动回退为 /workspace。
            "relativeBase": "${config.paths.relativeBase}",
            // 相对路径在基准目录下不存在、但在 /workspace 下存在时，是否自动改读后者。
            // 命中时会在工具返回里附 note 说明实际路径，不会静默误导。仅对已存在文件生效。
            "fallbackToWorkspaceRoot": ${config.paths.fallbackToWorkspaceRoot}
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
        registryStore.upsert(WorkspaceRecord.fromEntity(finalWorkspace))
        return finalWorkspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        registryStore.upsert(
            WorkspaceRecord.fromEntity(
                workspace.copy(
                    name = finalName,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return registryStore.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        registryStore.upsert(
            WorkspaceRecord.fromEntity(
                workspace.copy(
                    toolApprovals = JsonInstant.encodeToString(overrides),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
        return true
    }

    suspend fun setToolDefaultEnabled(id: String, toolName: String, defaultEnabled: Boolean): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        val raw = runCatching { JsonInstant.decodeFromString<Map<String, Boolean>>(workspace.toolApprovals) }
            .getOrDefault(emptyMap())
        val overrides = raw + (TOOL_DEFAULT_ENABLED_PREFIX + toolName to defaultEnabled)
        registryStore.upsert(
            WorkspaceRecord.fromEntity(
                workspace.copy(
                    toolApprovals = JsonInstant.encodeToString(overrides),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
        return true
    }

    suspend fun setBuiltinRuntime(id: String): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        manager.ensureWorkspace(workspace.root)
        val status = if (manager.hasRootfs(workspace.root)) {
            WorkspaceShellStatus.READY.name
        } else {
            WorkspaceShellStatus.DISABLED.name
        }
        registryStore.upsert(
            WorkspaceRecord.fromEntity(
                workspace.copy(
                    runtimeType = WorkspaceRuntimeType.BUILTIN_PROOT.name,
                    runtimeConfig = "{}",
                    shellStatus = status,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
        manager.ensureWorkspace(workspace.root)
        return true
    }

    suspend fun setSshRuntime(id: String, config: SshWorkspaceConfig): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        require(config.isConfigured()) {
            "SSH runtime requires host, port, username, and password or private key"
        }
        registryStore.upsert(
            WorkspaceRecord.fromEntity(
                workspace.copy(
                    runtimeType = WorkspaceRuntimeType.SSH.name,
                    runtimeConfig = JsonInstant.encodeToString(config),
                    shellStatus = WorkspaceShellStatus.READY.name,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
        manager.ensureWorkspace(workspace.root)
        return true
    }

    suspend fun hasBuiltinRootfs(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = registryStore.getById(id)?.toEntity() ?: return@withContext false
        manager.ensureWorkspace(workspace.root)
        manager.hasRootfs(workspace.root)
    }

    suspend fun setExternalMounts(id: String, mounts: List<WorkspaceExternalMount>): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        val normalized = mounts.map { mount ->
            mount.copy(
                sourcePath = mount.sourcePath.trim(),
                targetPath = mount.normalizedTargetPath(),
                name = mount.name.trim(),
            )
        }.filter { it.isConfigured() }
            .distinctBy { it.normalizedTargetPath() }
        registryStore.upsert(
            WorkspaceRecord.fromEntity(
                workspace.copy(
                    externalMounts = JsonInstant.encodeToString(normalized),
                    updatedAt = System.currentTimeMillis(),
                )
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
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
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
        val workspace = registryStore.getById(id)?.toEntity() ?: return@withContext emptyList()
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
            val workspace = registryStore.getById(id)?.toEntity() ?: return@withContext false
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
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

    private val backgroundRegistry = WorkspaceBackgroundProcessRegistry()

    /** 启动后台进程(仅内置 proot 运行时)。超过生命周期的旧进程会先被回收 */
    suspend fun startBackgroundCommand(
        id: String,
        processId: String,
        command: String,
        cwd: String = "",
        maxOutputChars: Int = me.rerere.workspace.MAX_OUTPUT_CHARS,
    ): WorkspaceBackgroundProcess {
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
        require(workspace.runtimeTypeValue() != WorkspaceRuntimeType.SSH) {
            "Background processes are not supported on SSH runtimes"
        }
        val config = getToolConfig(id).shell
        return runInterruptible(Dispatchers.IO) {
            backgroundRegistry.reap(config.backgroundMaxLifetimeMinutes * 60_000L)
            check(backgroundRegistry.aliveCount(workspace.root) < config.maxBackgroundProcesses) {
                "Too many background processes (max ${config.maxBackgroundProcesses}). Kill one first."
            }
            manager.ensureWorkspace(workspace.root)
            val process = manager.startBackgroundCommand(
                root = workspace.root,
                id = processId,
                command = command,
                cwd = cwd,
                maxOutputChars = maxOutputChars,
                bindMounts = workspace.externalBindMounts(),
            )
            backgroundRegistry.register(process)
            process
        }
    }

    suspend fun getBackgroundProcess(id: String, processId: String): WorkspaceBackgroundProcess? {
        val workspace = registryStore.getById(id)?.toEntity() ?: return null
        return backgroundRegistry.get(processId)?.takeIf { it.root == workspace.root }
    }

    suspend fun listBackgroundProcesses(id: String): List<WorkspaceBackgroundProcess> {
        val workspace = registryStore.getById(id)?.toEntity() ?: return emptyList()
        return backgroundRegistry.list(workspace.root)
    }

    fun removeBackgroundProcess(processId: String) {
        backgroundRegistry.remove(processId)
        sessionRegistry.remove(processId)
    }

    /** 定时回收: 后台任务按总寿命, 会话按 idle, 已死进程留墓碑 */
    suspend fun reapBackgroundProcesses(id: String) {
        val config = runCatching { getToolConfig(id).shell }.getOrNull() ?: return
        backgroundRegistry.reap(
            maxLifetimeMillis = config.backgroundMaxLifetimeMinutes * 60_000L,
            sessionIdleMillis = config.sessionIdleTimeoutMinutes * 60_000L,
        )
    }

    // ===================== 交互式 Shell 会话 =====================

    private val sessionRegistry = WorkspaceSessionRegistry()

    /** 会话内 bash 的启动命令: 常驻读 stdin, 直到被关闭 */
    private val sessionBootCommand = "exec /bin/bash -l"

    /**
     * pty 会话句柄表。key 为 sessionId。
     * pty 会话不进 [backgroundRegistry](那是 Process 专用), 因此单独存放,
     * 但 reap / 计数需要两边合并考虑。
     */
    private val ptySessions = java.util.concurrent.ConcurrentHashMap<String, WorkspacePtySession>()

    /** 取会话通道: 先查 pty, 再查管道 */
    private fun sessionChannel(sessionId: String, root: String): WorkspaceSessionChannel? {
        ptySessions[sessionId]?.let { return it }
        return backgroundRegistry.get(sessionId)?.takeIf { it.root == root && it.pinned }
    }

    /** 两种会话的存活总数, 用于配额判断 */
    private fun aliveSessionTotal(root: String): Int {
        val ptyCount = ptySessions.count { (sid, session) ->
            sessionRegistry.get(sid)?.root == root && session.isAlive
        }
        return ptyCount + backgroundRegistry.aliveSessionCount(root)
    }

    /**
     * 开一个交互式会话。
     *
     * **优先 pty**: pty 有 line discipline 与前台进程组, `\u0003` 才是真 Ctrl-C,
     * 能中断 `while true` 这类 bash 自身的循环(管道模式实测做不到)。
     * termux JNI 不可用时自动降级为管道会话, 中断能力打折但功能可用。
     */
    suspend fun openSession(
        id: String,
        cwd: String = "",
    ): Pair<WorkspaceSessionChannel, WorkspaceSessionState> {
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
        require(workspace.runtimeTypeValue() != WorkspaceRuntimeType.SSH) {
            "Interactive sessions are not supported on SSH runtimes"
        }
        val config = getToolConfig(id).shell
        require(config.sessionEnabled) { "Interactive sessions are disabled in workspace config" }

        val sessionId = "session_" + Uuid.random().toString().take(8)
        return runInterruptible(Dispatchers.IO) {
            backgroundRegistry.reap(
                maxLifetimeMillis = config.backgroundMaxLifetimeMinutes * 60_000L,
                sessionIdleMillis = config.sessionIdleTimeoutMinutes * 60_000L,
            )
            reapPtySessions(config)
            check(aliveSessionTotal(workspace.root) < config.maxSessions) {
                "Too many sessions (max ${config.maxSessions}). Close one first."
            }
            manager.ensureWorkspace(workspace.root)
            val maxOutput = config.outputMaxChars.coerceIn(1_000, 512 * 1024)

            val pty = if (config.sessionPtyEnabled) {
                runCatching {
                    WorkspacePtySession.start(
                        context = appContext,
                        root = workspace.root,
                        mounts = workspace.externalBindMounts(),
                        cwd = cwd,
                        maxOutputChars = maxOutput,
                    )
                }.onFailure {
                    Log.w(TAG, "pty session start failed, falling back to pipe", it)
                }.getOrNull()
            } else null

            val channel: WorkspaceSessionChannel = pty ?: manager.startBackgroundCommand(
                root = workspace.root,
                id = sessionId,
                command = sessionBootCommand,
                cwd = cwd,
                maxOutputChars = maxOutput,
                bindMounts = workspace.externalBindMounts(),
                pinned = true,
                mergeStderr = true,
            ).also { backgroundRegistry.register(it) }

            if (pty != null) ptySessions[sessionId] = pty

            val state = WorkspaceSessionState(id = sessionId, root = workspace.root).apply {
                usesPty = pty != null
            }
            sessionRegistry.register(state)

            // 初始化: 抑制 prompt/回显, 并上报 rootfs 内 pid
            channel.writeStdin(WorkspaceSessionProtocol.initScript(pty = pty != null))
            val deadline = System.currentTimeMillis() + SESSION_INIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val chunk = channel.readStdoutSince(state.cursor)
                val pid = WorkspaceSessionProtocol.parseSessionPid(chunk.text)
                if (pid != null) {
                    state.sessionPid = pid
                    // 游标推进到初始化输出之后, 避免污染首条命令的结果
                    state.cursor = chunk.cursor
                    break
                }
                if (!channel.isAlive) break
                Thread.sleep(SESSION_POLL_INTERVAL_MS)
            }
            if (!channel.isAlive) {
                val tail = channel.stdoutText().takeLast(500)
                ptySessions.remove(sessionId)
                backgroundRegistry.remove(sessionId)
                sessionRegistry.remove(sessionId)
                error("Session died during initialization: $tail")
            }
            channel to state
        }
    }

    suspend fun getSession(id: String, sessionId: String): Pair<WorkspaceSessionChannel, WorkspaceSessionState>? {
        val workspace = registryStore.getById(id)?.toEntity() ?: return null
        val state = sessionRegistry.get(sessionId)?.takeIf { it.root == workspace.root } ?: return null
        val channel = sessionChannel(sessionId, workspace.root) ?: return null
        return channel to state
    }

    /** 回收超出 idle 的 pty 会话 */
    private fun reapPtySessions(config: WorkspaceToolConfig.Shell) {
        val idleMillis = config.sessionIdleTimeoutMinutes * 60_000L
        val now = System.currentTimeMillis()
        ptySessions.entries.toList().forEach { (sessionId, session) ->
            val expired = idleMillis > 0 && now - session.lastActivityAt > idleMillis
            if (!session.isAlive || expired) {
                runCatching { session.close() }
                ptySessions.remove(sessionId)
                sessionRegistry.remove(sessionId)
            }
        }
    }

    /**
     * 列出会话(pty + 管道统一视图)。
     * pty 会话不在 [backgroundRegistry] 里, 必须两边合并, 否则 list 会漏掉 pty 会话。
     */
    suspend fun listSessions(id: String): List<SessionInfo> {
        val workspace = registryStore.getById(id)?.toEntity() ?: return emptyList()
        val root = workspace.root
        val ptyList = ptySessions.mapNotNull { (sid, session) ->
            val state = sessionRegistry.get(sid)?.takeIf { it.root == root } ?: return@mapNotNull null
            SessionInfo(
                id = sid,
                running = session.isAlive,
                usesPty = true,
                shellPid = state.sessionPid,
                pendingCommand = state.pendingCommand,
                commandRunning = state.pendingNonce != null,
                truncated = session.truncated(),
            )
        }
        val pipeList = backgroundRegistry.listSessions(root).map { process ->
            val state = sessionRegistry.get(process.id)
            SessionInfo(
                id = process.id,
                running = process.isAlive,
                usesPty = false,
                shellPid = state?.sessionPid,
                pendingCommand = state?.pendingCommand,
                commandRunning = state?.pendingNonce != null,
                truncated = process.truncated(),
            )
        }
        return ptyList + pipeList
    }

    /** 会话的对外状态快照, 屏蔽 pty/管道差异 */
    data class SessionInfo(
        val id: String,
        val running: Boolean,
        val usesPty: Boolean,
        val shellPid: Int?,
        val pendingCommand: String?,
        val commandRunning: Boolean,
        val truncated: Boolean,
    )

    suspend fun listBackgroundTasks(id: String): List<WorkspaceBackgroundProcess> {
        val workspace = registryStore.getById(id)?.toEntity() ?: return emptyList()
        return backgroundRegistry.listTasks(workspace.root)
    }

    fun sessionState(sessionId: String): WorkspaceSessionState? = sessionRegistry.get(sessionId)

    suspend fun closeSession(id: String, sessionId: String) {
        val (channel, _) = getSession(id, sessionId) ?: error("No such session: $sessionId")
        runInterruptible(Dispatchers.IO) {
            // 先礼: 让 bash 自己退出, 给 trap/清理逻辑一个机会
            runCatching { channel.writeStdin("exit\n") }
            if (!channel.waitFor(SESSION_CLOSE_GRACE_MS)) channel.kill()
        }
        ptySessions.remove(sessionId)
        backgroundRegistry.remove(sessionId)
        sessionRegistry.remove(sessionId)
    }

    /**
     * 在会话内执行一条命令, 阻塞等待哨兵直到 [timeoutMillis]。
     * 超时**不杀命令**, 返回部分输出 + stillRunning, 之后可用 [readSession] 续读。
     */
    suspend fun execInSession(
        id: String,
        sessionId: String,
        command: String,
        timeoutMillis: Long,
    ): SessionExecResult {
        val (channel, state) = getSession(id, sessionId)
            ?: error("No such session: $sessionId (it may have been closed or reaped)")
        check(channel.isAlive) { SESSION_DEAD_MESSAGE }
        check(state.pendingNonce == null) {
            "Session $sessionId still has a running command" +
                (state.pendingCommand?.let { " (`$it`)" } ?: "") +
                ". Use action=read to continue reading, or action=interrupt to abort it."
        }

        val nonce = WorkspaceSessionProtocol.newNonce()
        return runInterruptible(Dispatchers.IO) {
            state.pendingNonce = nonce
            state.pendingCommand = command.take(120)
            channel.writeStdin(WorkspaceSessionProtocol.wrapCommand(command, nonce))
            pollSentinel(channel, state, nonce, timeoutMillis)
        }
    }

    /** 续读悬挂命令的输出; 无悬挂命令时只是读增量 */
    suspend fun readSession(
        id: String,
        sessionId: String,
        timeoutMillis: Long,
    ): SessionExecResult {
        val (channel, state) = getSession(id, sessionId)
            ?: error("No such session: $sessionId")
        val nonce = state.pendingNonce
        return runInterruptible(Dispatchers.IO) {
            if (nonce == null) {
                // 无悬挂命令: 纯读增量(例如读 REPL 对 write 的回显)
                val chunk = channel.readStdoutSince(state.cursor)
                state.cursor = chunk.cursor
                SessionExecResult(
                    stdout = chunk.text,
                    exitCode = null,
                    stillRunning = false,
                    cursor = chunk.cursor,
                    droppedChars = chunk.dropped,
                )
            } else {
                pollSentinel(channel, state, nonce, timeoutMillis)
            }
        }
    }

    /** 裸写 stdin: 喂 REPL、回答 y/n、输入密码等 */
    suspend fun writeSession(id: String, sessionId: String, data: String) {
        val (channel, _) = getSession(id, sessionId) ?: error("No such session: $sessionId")
        check(channel.isAlive) { SESSION_DEAD_MESSAGE }
        runInterruptible(Dispatchers.IO) { channel.writeStdin(data) }
    }

    /**
     * 中断会话当前的前台命令。按通道能力分流:
     *
     * - **pty 会话**: 直接写 `\u0003`。内核的 line discipline 会把它翻译成 SIGINT
     *   并投递给**整个前台进程组**, 因此 `while true; do sleep 1; done` 这类
     *   bash 自身的循环也能停下 —— 这是真正的 Ctrl-C。
     * - **管道会话(降级)**: 另起一次性 shell 遍历 /proc, 递归杀子孙进程。
     *   **治不了** bash 自身的循环(已实测), 此时会在返回文案里提示改用 close。
     *   严禁 `kill -INT -<pgid>`: proot 下所有进程共享 pgrp, 会波及整个工作区。
     */
    suspend fun interruptSession(id: String, sessionId: String): String {
        val (channel, state) = getSession(id, sessionId) ?: error("No such session: $sessionId")
        check(channel.isAlive) { SESSION_DEAD_MESSAGE }

        if (channel.supportsSignals) {
            return runInterruptible(Dispatchers.IO) {
                channel.writeStdin("\u0003")
                // 给内核投递信号 + bash 收拾现场的时间, 然后看悬挂命令是否真的结束
                Thread.sleep(SESSION_SIGINT_SETTLE_MS)
                val nonce = state.pendingNonce
                if (nonce != null) {
                    // 续读一小段: 命令被 SIGINT 杀死后 bash 仍会打印哨兵(exit code 130),
                    // 读到它才能清掉 pending, 否则下一条 exec 会被误判为“仍在运行”
                    pollSentinel(channel, state, nonce, SESSION_SIGINT_DRAIN_MS)
                }
                if (state.pendingNonce == null) "interrupted (SIGINT via pty)"
                else "SIGINT sent, but the command is still running; use action=close to force stop"
            }
        }

        val pid = state.sessionPid
            ?: error("Session pid unknown; cannot interrupt safely. Use action=close instead.")
        val result = executeCommand(
            id = id,
            command = WorkspaceSessionProtocol.interruptScript(pid),
            timeoutMillis = SESSION_INTERRUPT_TIMEOUT_MS,
        )
        val summary = result.stdout.trim().ifBlank { "interrupted" }
        // 清干净悬挂标记: 命令被杀后 bash 会输出哨兵
        val nonce = state.pendingNonce
        if (nonce != null) {
            runInterruptible(Dispatchers.IO) {
                pollSentinel(channel, state, nonce, SESSION_SIGINT_DRAIN_MS)
            }
        }
        return if (state.pendingNonce == null) summary
        else "$summary; the command is still running (pipe sessions cannot interrupt bash's own " +
            "loops — use action=close to force stop)"
    }

    /**
     * 轮询等待哨兵出现。
     * 命中 → 清除 pending 并返回 exitCode; 超时 → 保留 pending, 返回 partial。
     */
    private fun pollSentinel(
        channel: WorkspaceSessionChannel,
        state: WorkspaceSessionState,
        nonce: String,
        timeoutMillis: Long,
    ): SessionExecResult {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0L)
        val collected = StringBuilder()
        var dropped = 0L
        var cursor = state.cursor
        while (true) {
            val chunk = channel.readStdoutSince(cursor)
            cursor = chunk.cursor
            dropped += chunk.dropped
            collected.append(chunk.text)

            val (body, exitCode) = WorkspaceSessionProtocol.splitBySentinel(collected.toString(), nonce)
            if (exitCode != null) {
                // 哨兵已出现: 游标推进到哨兵之后, 清除悬挂标记
                state.cursor = cursor
                state.pendingNonce = null
                state.pendingCommand = null
                return SessionExecResult(
                    stdout = body,
                    exitCode = exitCode,
                    stillRunning = false,
                    cursor = cursor,
                    droppedChars = dropped,
                )
            }
            if (!channel.isAlive) {
                state.cursor = cursor
                state.pendingNonce = null
                state.pendingCommand = null
                return SessionExecResult(
                    stdout = body,
                    exitCode = null,
                    stillRunning = false,
                    cursor = cursor,
                    droppedChars = dropped,
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                // 超时不杀命令: 推进游标, 保留 pendingNonce 供续读
                state.cursor = cursor
                return SessionExecResult(
                    stdout = body,
                    exitCode = null,
                    stillRunning = true,
                    cursor = cursor,
                    droppedChars = dropped,
                )
            }
            Thread.sleep(SESSION_POLL_INTERVAL_MS)
        }
    }

    /** 在工作区文件区或外部挂载点中搜索文本。SSH 运行时请用 shell grep */
    suspend fun grepFiles(
        id: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        val workspace = registryStore.getById(id)?.toEntity() ?: error("Workspace not found: $id")
        require(workspace.runtimeTypeValue() != WorkspaceRuntimeType.SSH) {
            "grep tool is not supported on SSH runtimes; use workspace_shell with grep instead"
        }
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            val trimmedPath = path.trim()
            val externalMountPair = if (trimmedPath.isNotBlank()) {
                resolveExternalMountFile(workspace, trimmedPath)
            } else null

            if (externalMountPair != null) {
                val (mount, targetFile) = externalMountPair
                require(targetFile.exists()) { "Path does not exist: $path" }
                val mountSource = File(mount.sourcePath).canonicalFile
                val relativeSubPath = if (targetFile.canonicalPath == mountSource.canonicalPath) ""
                    else targetFile.canonicalFile.relativeTo(mountSource).path.replace('\\', '/')
                val rawMatches = manager.grep(mountSource, query, relativeSubPath, regex, ignoreCase, includeGlob)
                val targetPrefix = mount.normalizedTargetPath()
                rawMatches.map { match ->
                    val fullPath = if (match.path.isBlank()) targetPrefix else "$targetPrefix/${match.path}"
                    match.copy(path = fullPath)
                }
            } else {
                val relativeWorkspacePath = trimmedPath.removePrefix("/workspace/").removePrefix("/workspace").trimStart('/')
                manager.grep(workspace.root, query, relativeWorkspacePath, regex, ignoreCase, includeGlob)
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = registryStore.getById(id)?.toEntity() ?: return false
        registryStore.deleteById(id)
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
        registryStore.updateShellStatus(
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

        // ---- 交互式会话 ----
        private const val SESSION_POLL_INTERVAL_MS = 60L
        /** pty 下发出 SIGINT 后, 等内核投递与 bash 收拾现场的时间 */
        private const val SESSION_SIGINT_SETTLE_MS = 250L
        /** 中断后续读哨兵(exit code 130)的等待上限, 用于清掉悬挂标记 */
        private const val SESSION_SIGINT_DRAIN_MS = 2_000L
        private const val SESSION_INIT_TIMEOUT_MS = 15_000L
        private const val SESSION_CLOSE_GRACE_MS = 1_500L
        private const val SESSION_INTERRUPT_TIMEOUT_MS = 15_000L
        private const val SESSION_DEAD_MESSAGE =
            "Session is dead (the workspace runtime may have been restarted). Open a new session."
    }
}
