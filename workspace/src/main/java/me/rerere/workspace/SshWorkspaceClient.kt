package me.rerere.workspace

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import kotlin.concurrent.thread

/**
 * SSH/SFTP based workspace runtime.
 *
 * The remote [SshWorkspaceConfig.workDir] is exposed to the agent as /workspace.
 * File APIs are constrained to that directory; shell commands run with cwd inside it and get WORKSPACE exported.
 */
class SshWorkspaceClient(
    private val config: SshWorkspaceConfig,
) {
    init {
        require(config.host.isNotBlank()) { "SSH host is required" }
        require(config.port in 1..65535) { "SSH port must be between 1 and 65535" }
        require(config.username.isNotBlank()) { "SSH username is required" }
        require(config.password.isNotBlank() || config.privateKey.isNotBlank()) {
            "SSH password or private key is required"
        }
    }

    fun listFiles(path: String = ""): List<WorkspaceFileEntry> = withSftp { sftp ->
        val root = sftp.workspaceRoot()
        sftp.ensureDirectory(root)
        val relative = normalizeRelativePath(path)
        val remote = joinRemote(root, relative)
        val attrs = sftp.statOrNull(remote) ?: error("Directory does not exist: $path")
        require(attrs.isDir) { "Path is not a directory: $path" }
        @Suppress("UNCHECKED_CAST")
        (sftp.ls(remote) as java.util.Vector<Any>)
            .mapNotNull { it as? ChannelSftp.LsEntry }
            .filterNot { it.filename == "." || it.filename == ".." }
            .map { entry ->
                val childRelative = joinRelative(relative, entry.filename)
                entry.attrs.toEntry(childRelative.ifBlank { entry.filename }, entry.filename)
            }
            .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readBytes(path: String): ByteArray = withSftp { sftp ->
        val remote = sftp.workspacePath(path)
        val attrs = sftp.statOrNull(remote) ?: error("File does not exist: $path")
        require(!attrs.isDir) { "Path is not a file: $path" }
        ByteArrayOutputStream().use { output ->
            sftp.get(remote, output)
            output.toByteArray()
        }
    }

    fun writeBytes(
        path: String,
        bytes: ByteArray,
        overwrite: Boolean = true,
    ): WorkspaceFileEntry = withSftp { sftp ->
        val relative = normalizeRelativePath(path)
        require(relative.isNotBlank()) { "File path is required" }
        val remote = sftp.workspacePath(relative)
        val existing = sftp.statOrNull(remote)
        if (existing != null && !overwrite) error("File already exists: $path")
        if (existing?.isDir == true) error("Path is not a file: $path")
        sftp.ensureDirectory(parentRemote(remote))
        ByteArrayInputStream(bytes).use { input ->
            sftp.put(input, remote)
        }
        sftp.stat(remote).toEntry(relative, relative.substringAfterLast('/'))
    }

    fun importFile(
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withSftp { sftp ->
        val destination = normalizeRelativePath(destinationPath)
        val safeName = normalizeFileName(fileName)
        val relative = joinRelative(destination, safeName)
        val remote = sftp.workspacePath(relative)
        sftp.ensureDirectory(parentRemote(remote))
        inputStream.use { input ->
            sftp.put(input, remote)
        }
        sftp.stat(remote).toEntry(relative, safeName)
    }

    fun fileSize(path: String): Long = withSftp { sftp ->
        val attrs = sftp.statOrNull(sftp.workspacePath(path)) ?: error("File does not exist: $path")
        require(!attrs.isDir) { "Path is not a file: $path" }
        attrs.size
    }

    fun exportFile(path: String, outputStream: OutputStream) = withSftp { sftp ->
        val remote = sftp.workspacePath(path)
        val attrs = sftp.statOrNull(remote) ?: error("File does not exist: $path")
        require(!attrs.isDir) { "Path is not a file: $path" }
        outputStream.use { output ->
            sftp.get(remote, output)
        }
    }

    fun deleteFile(path: String, recursive: Boolean = false): Boolean = withSftp { sftp ->
        val remote = sftp.workspacePath(path)
        val attrs = sftp.statOrNull(remote) ?: return@withSftp false
        if (attrs.isDir) {
            if (recursive) {
                sftp.deleteDirectoryRecursive(remote)
            } else {
                sftp.rmdir(remote)
            }
        } else {
            sftp.rm(remote)
        }
        true
    }

    fun moveFile(source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry = withSftp { sftp ->
        val sourceRelative = normalizeRelativePath(source)
        val targetRelative = normalizeRelativePath(target)
        require(sourceRelative.isNotBlank()) { "Source path is required" }
        require(targetRelative.isNotBlank()) { "Target path is required" }
        val sourceRemote = sftp.workspacePath(sourceRelative)
        val targetRemote = sftp.workspacePath(targetRelative)
        require(sftp.statOrNull(sourceRemote) != null) { "Source does not exist: $source" }
        val targetAttrs = sftp.statOrNull(targetRemote)
        if (targetAttrs != null && !overwrite) error("Target already exists: $target")
        if (targetAttrs != null) {
            if (targetAttrs.isDir) sftp.deleteDirectoryRecursive(targetRemote) else sftp.rm(targetRemote)
        }
        sftp.ensureDirectory(parentRemote(targetRemote))
        sftp.rename(sourceRemote, targetRemote)
        sftp.stat(targetRemote).toEntry(targetRelative, targetRelative.substringAfterLast('/'))
    }

    fun execute(
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val normalizedCwd = normalizeRelativePath(cwd)
        return withSession { session ->
            val channel = session.openChannel("exec") as ChannelExec
            val stdout: InputStream = channel.inputStream
            val stderr: InputStream = channel.errStream
            val stdoutCollector = LimitedStreamCollector(stdout)
            val stderrCollector = LimitedStreamCollector(stderr)
            val stdinWriter = stdin?.let { bytes ->
                ChannelInputWriter(channel.outputStream, bytes)
            }
            val script = buildShellScript(command, normalizedCwd)
            channel.setCommand("sh -lc ${script.shellQuote()}")
            channel.connect(config.connectTimeoutMillis.coerceAtLeast(1_000))
            stdinWriter?.start()

            val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
            var timedOut = false
            while (!channel.isClosed) {
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    channel.disconnect()
                    break
                }
                Thread.sleep(50)
            }

            stdinWriter?.join(1_000)
            stdoutCollector.join(1_000)
            stderrCollector.join(1_000)
            val exitCode = if (timedOut) -1 else channel.exitStatus
            channel.disconnect()
            WorkspaceCommandResult(
                exitCode = exitCode,
                stdout = stdoutCollector.text(),
                stderr = stderrCollector.text(),
                timedOut = timedOut,
                truncated = stdoutCollector.truncated || stderrCollector.truncated,
            )
        }
    }

    fun test(): WorkspaceCommandResult = execute(
        command = "printf 'rikkahub runtime ok\\n'; command -v python3 >/dev/null 2>&1 && python3 --version || true",
        timeoutMillis = 15_000,
    )

    private fun buildShellScript(command: String, cwd: String): String {
        val mappedCommand = command
            .replace("/workspace/", "\$WORKSPACE/")
            .replace("/workspace", "\$WORKSPACE")
        return """
            WORKSPACE_DIR=${config.workDir.trim().ifBlank { "~/rikkahub-workspaces/default" }.shellQuote()}
            case "${'$'}WORKSPACE_DIR" in
              '~') WORKSPACE_DIR="${'$'}HOME" ;;
              '~/'*) WORKSPACE_DIR="${'$'}HOME/${'$'}{WORKSPACE_DIR#~/}" ;;
              /*) ;;
              *) WORKSPACE_DIR="${'$'}HOME/${'$'}WORKSPACE_DIR" ;;
            esac
            mkdir -p -- "${'$'}WORKSPACE_DIR" || exit 1
            cd -- "${'$'}WORKSPACE_DIR" || exit 1
            if [ ${cwd.shellQuote()} != '' ]; then
              cd -- ${cwd.shellQuote()} || exit 1
            fi
            export WORKSPACE="${'$'}WORKSPACE_DIR"
            COMMAND=${mappedCommand.shellQuote()}
            eval "${'$'}COMMAND"
        """.trimIndent()
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T = withSession { session ->
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(config.connectTimeoutMillis.coerceAtLeast(1_000))
        try {
            block(channel)
        } finally {
            channel.disconnect()
        }
    }

    private fun <T> withSession(block: (Session) -> T): T {
        val session = createSession()
        try {
            session.connect(config.connectTimeoutMillis.coerceAtLeast(1_000))
            return block(session)
        } finally {
            session.disconnect()
        }
    }

    private fun createSession(): Session {
        val jsch = JSch()
        if (config.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "rikkahub-workspace-key",
                config.privateKey.toByteArray(Charsets.UTF_8),
                null,
                config.passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8),
            )
        }
        return jsch.getSession(config.username, config.host, config.port).apply {
            if (config.password.isNotBlank()) {
                setPassword(config.password)
            }
            setConfig(Properties().apply {
                put("StrictHostKeyChecking", if (config.strictHostKeyChecking) "yes" else "no")
                put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                put("ServerAliveInterval", "15000")
            })
            setTimeout(config.connectTimeoutMillis.coerceAtLeast(1_000))
        }
    }

    private fun ChannelSftp.workspaceRoot(): String {
        val raw = config.workDir.trim().ifBlank { "~/rikkahub-workspaces/default" }
        val home = pwd().trimEnd('/').ifBlank { "." }
        return when {
            raw == "~" -> home
            raw.startsWith("~/") -> joinRemote(home, raw.removePrefix("~/"))
            raw.startsWith("/") -> raw.trimEnd('/').ifBlank { "/" }
            else -> joinRemote(home, raw)
        }
    }

    private fun ChannelSftp.workspacePath(path: String): String =
        joinRemote(workspaceRoot(), normalizeRelativePath(path))

    private fun ChannelSftp.ensureDirectory(path: String) {
        if (path.isBlank() || path == ".") return
        val absolute = path.startsWith("/")
        var current = if (absolute) "/" else ""
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { part ->
            current = joinRemote(current, part)
            val attrs = statOrNull(current)
            when {
                attrs == null -> mkdir(current)
                attrs.isDir -> Unit
                else -> error("Path exists and is not a directory: $current")
            }
        }
    }

    private fun ChannelSftp.statOrNull(path: String): SftpATTRS? = try {
        stat(path)
    } catch (e: SftpException) {
        null
    }

    private fun ChannelSftp.deleteDirectoryRecursive(path: String) {
        @Suppress("UNCHECKED_CAST")
        val entries = (ls(path) as java.util.Vector<Any>)
            .mapNotNull { it as? ChannelSftp.LsEntry }
            .filterNot { it.filename == "." || it.filename == ".." }
        entries.forEach { entry ->
            val child = joinRemote(path, entry.filename)
            if (entry.attrs.isDir) {
                deleteDirectoryRecursive(child)
            } else {
                rm(child)
            }
        }
        rmdir(path)
    }

    private fun SftpATTRS.toEntry(path: String, name: String): WorkspaceFileEntry = WorkspaceFileEntry(
        path = path,
        name = name.ifBlank { path.substringAfterLast('/').ifBlank { "/" } },
        isDirectory = isDir,
        sizeBytes = size,
        updatedAt = mTime.toLong() * 1_000L,
    )

    private fun normalizeRelativePath(path: String): String {
        val replaced = path.replace('\\', '/').trim()
        require(!replaced.contains('\u0000')) { "Path contains invalid character" }
        val trimmed = replaced.removePrefix("/workspace/").removePrefix("/workspace").trim('/')
        if (trimmed.isBlank()) return ""
        val parts = mutableListOf<String>()
        trimmed.split('/').forEach { raw ->
            val part = raw.trim()
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> error("Path escapes workspace: $path")
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun normalizeFileName(fileName: String): String {
        val normalized = fileName.replace('\\', '/').substringAfterLast('/').trim()
        require(normalized.isNotBlank()) { "File name is required" }
        require(normalized != "." && normalized != ".." && !normalized.contains('\u0000')) {
            "Invalid file name: $fileName"
        }
        return normalized
    }

    private fun joinRelative(base: String, child: String): String = when {
        base.isBlank() -> child.trim('/')
        child.isBlank() -> base.trim('/')
        else -> "${base.trimEnd('/')}/${child.trimStart('/')}"
    }

    private fun joinRemote(base: String, child: String): String = when {
        child.isBlank() -> base.ifBlank { "." }
        base.isBlank() || base == "." -> child.trimStart('/')
        base == "/" -> "/${child.trimStart('/')}"
        else -> "${base.trimEnd('/')}/${child.trimStart('/')}"
    }

    private fun parentRemote(path: String): String =
        path.substringBeforeLast('/', missingDelimiterValue = ".").ifBlank { "/" }

    private fun String.shellQuote(): String =
        "'" + replace("'", "'\"'\"'") + "'"
}

private class ChannelInputWriter(
    private val stream: OutputStream,
    private val bytes: ByteArray,
) {
    private lateinit var worker: Thread

    fun start() {
        worker = thread(isDaemon = true, name = "ssh-stdin-writer") {
            try {
                stream.use { output ->
                    output.write(bytes)
                    output.flush()
                }
            } catch (_: IOException) {
                // Remote command may exit before reading stdin.
            }
        }
    }

    fun join(millis: Long) {
        if (::worker.isInitialized) worker.join(millis)
    }
}

private class LimitedStreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated: Boolean = false
        private set

    private val worker = thread(isDaemon = true, name = "ssh-stream-collector") {
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Keep whatever was collected before disconnect/timeout.
        }
    }

    fun join(millis: Long) = worker.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
