package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class ExampleUnitTest {
    @Test
    fun fileOperationsWorkInsideWorkspaceRoot() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        fileSystem.writeText(root, "src/main.txt", "hello\nworkspace")

        assertEquals("hello\nworkspace", fileSystem.readText(root, "src/main.txt"))
        assertEquals(listOf("src"), fileSystem.list(root).map { it.path })
        assertEquals(listOf("src/main.txt"), fileSystem.glob(root, "**/*.txt").map { it.path })
    }

    @Test
    fun pathEscapeIsRejected() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        var rejected = false
        try {
            fileSystem.writeText(root, "../escape.txt", "nope")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rootfsRequiresShellEntryPoint() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        assertFalse(manager.hasRootfs(root))

        File(manager.linuxDir(root), "etc").mkdirs()
        assertFalse(manager.hasRootfs(root))

        File(manager.linuxDir(root), "bin").mkdirs()
        File(manager.linuxDir(root), "bin/sh").writeText("#!/bin/sh\n")
        assertTrue(manager.hasRootfs(root))
    }

    @Test
    fun grepRunsThroughShellAndParsesContentOutput() {
        val baseDir = Files.createTempDirectory("workspace-grep-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        manager.writeFile(root, "src/main.kt", "fun main() {\n    println(\"hello world\")\n}\n")
        manager.writeFile(root, "notes.md", "hello world in markdown\n")

        val content = manager.grepContent(
            root,
            WorkspaceGrepRequest(
                query = "hello world",
                path = manager.filesDir(root).absolutePath,
                outputMode = GrepOutputMode.CONTENT,
            ),
        )
        assertEquals(2, content.matches.size)
        assertTrue(content.matches.any { it.path.endsWith("src/main.kt") && it.line == 2 })
        assertTrue(content.matches.all { it.text.contains("hello world") })
    }

    @Test
    fun grepTypeFilterDoesNotLeakOtherExtensions() {
        val baseDir = Files.createTempDirectory("workspace-grep-type-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        manager.writeFile(root, "a.kt", "needle\n")
        manager.writeFile(root, "b.md", "needle\n")

        // 回归: GNU grep 里 --exclude 会抵消 --include 的收窄语义,
        // 曾导致按 *.kt 过滤时把 .md 也搜出来
        val files = manager.grepContent(
            root,
            WorkspaceGrepRequest(
                query = "needle",
                path = manager.filesDir(root).absolutePath,
                glob = "*.kt",
            ),
        )
        assertEquals(1, files.files.size)
        assertTrue(files.files.single().endsWith("a.kt"))
    }

    @Test
    fun grepTruncationHappensOnOutputNotDuringScan() {
        val baseDir = Files.createTempDirectory("workspace-grep-limit-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        repeat(20) { index -> manager.writeFile(root, "f$index.txt", "needle\n") }

        val limited = manager.grepContent(
            root,
            WorkspaceGrepRequest(
                query = "needle",
                path = manager.filesDir(root).absolutePath,
                headLimit = 5,
            ),
        )
        assertEquals(5, limited.files.size)
        assertTrue(limited.truncated)

        // offset 能翻页, 且与第一页不重叠
        val paged = manager.grepContent(
            root,
            WorkspaceGrepRequest(
                query = "needle",
                path = manager.filesDir(root).absolutePath,
                headLimit = 5,
                offset = 5,
            ),
        )
        assertEquals(5, paged.files.size)
        assertTrue(paged.files.none { it in limited.files })
    }

    @Test
    fun grepQuotesPatternSoShellNeverReinterpretsIt() {
        val baseDir = Files.createTempDirectory("workspace-grep-quote-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        manager.writeFile(root, "danger.txt", "it's $(rm -rf /) `whoami` done\n")

        val result = manager.grepContent(
            root,
            WorkspaceGrepRequest(
                query = "it's $(rm -rf /) `whoami`",
                path = manager.filesDir(root).absolutePath,
                fixedString = true,
                outputMode = GrepOutputMode.CONTENT,
            ),
        )
        assertEquals(1, result.matches.size)
        assertTrue(File(manager.filesDir(root), "danger.txt").isFile)
    }

    @Test
    fun grepCountModeReportsPerFileCounts() {
        val baseDir = Files.createTempDirectory("workspace-grep-count-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        manager.writeFile(root, "many.txt", "x\nx\nx\n")
        manager.writeFile(root, "one.txt", "x\n")

        val counts = manager.grepContent(
            root,
            WorkspaceGrepRequest(
                query = "x",
                path = manager.filesDir(root).absolutePath,
                outputMode = GrepOutputMode.COUNT,
            ),
        ).counts
        assertEquals(3, counts.first { it.path.endsWith("many.txt") }.count)
        assertEquals(1, counts.first { it.path.endsWith("one.txt") }.count)
    }

    @Test
    fun rootfsInstallerDownloadsAndExtractsTarGz() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val installer = RootfsInstaller(manager)
        val archive = tarGz(
            TarTestEntry("bin/", type = '5'),
            TarTestEntry("bin/hello", content = "echo hello\n".toByteArray(), mode = 493),
            TarTestEntry("usr/bin/hello-link", type = '2', linkName = "../../bin/hello"),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rootfs.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
        try {
            val root = "test-workspace"
            installer.install(root, "http://127.0.0.1:${server.address.port}/rootfs.tar.gz")

            val linuxDir = manager.linuxDir(root)
            assertEquals("echo hello\n", File(linuxDir, "bin/hello").readText())
            assertTrue(File(linuxDir, "bin/hello").canExecute())
            assertTrue(Files.isSymbolicLink(File(linuxDir, "usr/bin/hello-link").toPath()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun commandRunsInsideWorkspaceFilesDirectory() {
        val baseDir = Files.createTempDirectory("workspace-command-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "printf hello > command.txt && cat command.txt")

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("hello", File(manager.filesDir(root), "command.txt").readText())
    }

    @Test
    fun commandReceivesStdin() {
        val baseDir = Files.createTempDirectory("workspace-stdin-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root = root,
            command = "cat > stdin.txt",
            stdin = "hello\nstdin".toByteArray(),
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello\nstdin", File(manager.filesDir(root), "stdin.txt").readText())
    }

    @Test
    fun prootRunnerRequiresRootfs() {
        val baseDir = Files.createTempDirectory("workspace-proot-test").toFile()
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(File(baseDir, "native"))
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "cat /etc/os-release")

        assertEquals(127, result.exitCode)
        assertEquals("Rootfs is not installed", result.stderr)
    }

    @Test
    fun commandOutputIsTruncatedAtLimit() {
        val baseDir = Files.createTempDirectory("workspace-truncate-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root,
            "awk 'BEGIN { for (i = 0; i < 300000; i++) printf \"a\" }'",
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
    }

    @Test
    fun rootfsPatcherAppliesAndroidProotDefaults() {
        val linuxDir = Files.createTempDirectory("rootfs-patch-test").toFile()
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/resolv.conf").writeText("nameserver 127.0.0.53\n")
        File(linuxDir, "etc/group").writeText("root:x:0:\n")

        RootfsPatcher().patch(
            linuxDir,
            RootfsPatchOptions(
                nameservers = listOf("9.9.9.9", "8.8.8.8"),
                hostname = "workspace-test",
                groupIds = listOf(3003, 9997),
            )
        )

        assertEquals(
            """
            # Generated by RikkaHub workspace.
            nameserver 9.9.9.9
            nameserver 8.8.8.8
            options edns0 trust-ad

            """.trimIndent(),
            File(linuxDir, "etc/resolv.conf").readText()
        )
        assertTrue(File(linuxDir, "etc/hosts").readText().contains("127.0.0.1 localhost workspace-test"))
        assertEquals("workspace-test\n", File(linuxDir, "etc/hostname").readText())
        assertEquals("LANG=C.UTF-8\n", File(linuxDir, "etc/default/locale").readText())
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_3003:x:3003:"))
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_9997:x:9997:"))
        assertTrue(File(linuxDir, "tmp").canWrite())
        assertTrue(File(linuxDir, "var/tmp").canWrite())
        assertTrue(File(linuxDir, "root").isDirectory)
    }

    private fun tarGz(vararg entries: TarTestEntry): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { entry ->
                gzip.write(tarHeader(entry))
                if (entry.content.isNotEmpty()) {
                    gzip.write(entry.content)
                    gzip.write(ByteArray(entry.content.size.paddingSize()))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return output.toByteArray()
    }

    private fun tarHeader(entry: TarTestEntry): ByteArray {
        val header = ByteArray(512)
        header.writeString(0, 100, entry.name)
        header.writeOctal(100, 8, entry.mode.toLong())
        header.writeOctal(108, 8, 0)
        header.writeOctal(116, 8, 0)
        header.writeOctal(124, 12, entry.content.size.toLong())
        header.writeOctal(136, 12, 0)
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = entry.type.code.toByte()
        header.writeString(157, 100, entry.linkName)
        header.writeString(257, 6, "ustar")
        header.writeString(263, 2, "00")
        val checksum = header.sumOf { it.toUByte().toInt() }
        header.writeOctal(148, 8, checksum.toLong())
        return header
    }

    private fun ByteArray.writeString(offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        bytes.copyInto(this, offset, 0, minOf(bytes.size, length))
    }

    private fun ByteArray.writeOctal(offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        writeString(offset, length, text)
        this[offset + length - 1] = 0
    }

    private fun Int.paddingSize(): Int = (512 - (this % 512)).let {
        if (it == 512) 0 else it
    }

    private data class TarTestEntry(
        val name: String,
        val content: ByteArray = byteArrayOf(),
        val mode: Int = 420,
        val type: Char = '0',
        val linkName: String = "",
    )
}
