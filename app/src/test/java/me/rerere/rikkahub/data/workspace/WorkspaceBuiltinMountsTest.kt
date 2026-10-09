package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.WorkspaceExternalMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂载清单算错 = 路径解析错，所以合并逻辑单独拉出来测（不依赖 Android Context）。
 */
class WorkspaceBuiltinMountsTest {
    private fun mount(target: String, source: String) = WorkspaceExternalMount(
        sourcePath = source,
        targetPath = target,
        writable = true,
    )

    @Test
    fun `builtin mounts are appended after configured mounts`() {
        val merged = mergeMountConfigs(
            configured = listOf(mount("/mnt/obsidian", "/rikkahub-data/obsidian")),
            builtin = listOf(
                mount("/skills", "/rikkahub-data/skills"),
                mount("/tool_outputs", "/rikkahub-data/tool_outputs"),
            ),
        )

        assertEquals(
            listOf("/mnt/obsidian", "/skills", "/tool_outputs"),
            merged.map { it.normalizedTargetPath() },
        )
    }

    @Test
    fun `configured mount wins when it claims a builtin target`() {
        val merged = mergeMountConfigs(
            configured = listOf(mount("/skills", "/custom/skills")),
            builtin = listOf(mount("/skills", "/rikkahub-data/skills")),
        )

        assertEquals(1, merged.size)
        assertEquals("/custom/skills", merged.single().sourcePath)
    }

    @Test
    fun `unconfigured mounts are dropped`() {
        val merged = mergeMountConfigs(
            configured = listOf(mount("/broken", "  ")),
            builtin = emptyList(),
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `nested targets are kept in order for longest-prefix resolution`() {
        val merged = mergeMountConfigs(
            configured = listOf(mount("/mnt/obsidian", "/a/obsidian")),
            builtin = listOf(mount("/mnt", "/a/mnt")),
        )

        assertEquals(listOf("/mnt/obsidian", "/mnt"), merged.map { it.normalizedTargetPath() })
    }
}
