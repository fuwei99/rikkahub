package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        tempDir(root).mkdirs()
        migrateLegacyRootfs(root)
        sharedRootfsDir().mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    /**
     * A single rootfs is shared by all workspaces.
     *
     * The per-workspace isolation boundary is /workspace ([filesDir]); the Linux userspace is a runtime image,
     * similar to an agent sandbox template, so duplicating it once per workspace wastes hundreds of MB.
     */
    fun linuxDir(root: String): File {
        requireValidRoot(root)
        return sharedRootfsDir()
    }

    fun sharedRootfsDir(): File = File(baseDir, SHARED_ROOTFS_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        bindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    private fun legacyLinuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    /**
     * Move one old per-workspace rootfs into the shared runtime location, then remove duplicate legacy rootfs dirs.
     * This keeps older installs from continuing to consume one rootfs worth of storage per workspace.
     */
    private fun migrateLegacyRootfs(root: String) {
        val legacy = legacyLinuxDir(root)
        if (!legacy.exists()) return
        val shared = sharedRootfsDir()
        if (runCatching { legacy.canonicalFile == shared.canonicalFile }.getOrDefault(false)) return

        val sharedReady = File(shared, "bin/sh").isFile
        val legacyReady = File(legacy, "bin/sh").isFile
        when {
            sharedReady -> legacy.deleteRecursively()
            legacyReady -> {
                shared.parentFile?.mkdirs()
                if (shared.exists() && shared.listFiles()?.isEmpty() != false) {
                    shared.deleteRecursively()
                }
                if (!shared.exists() && legacy.renameTo(shared)) return
                // If rename fails, keep the legacy rootfs instead of doing a Java copy which may break symlinks.
            }
            legacy.listFiles()?.isEmpty() != false -> legacy.deleteRecursively()
        }
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX) || root == SHARED_ROOTFS_DIR) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            migrateLegacyRootfs(root)
        }
        // Shared Rootfs /tmp and /var/tmp
        File(sharedRootfsDir(), "tmp").let { if (it.exists()) it.deleteRecursively() }
        File(sharedRootfsDir(), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val SHARED_ROOTFS_DIR = "_shared-rootfs"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}
