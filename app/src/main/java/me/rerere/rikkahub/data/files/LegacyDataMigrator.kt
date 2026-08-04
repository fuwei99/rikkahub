package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import java.io.File

/**
 * 一次性迁移：旧版本数据位于 data/data/<pkg>/files 与 databases/（内部存储，深藏不可备份），
 * 本版本起统一迁移到外部专属目录 /storage/emulated/0/Android/data/<pkg>/files。
 *
 * ⚠️ 必须在 Koin / Room / DataStore 初始化之前调用（RikkaHubApp.onCreate 最前）。
 *
 * 策略：递归复制（目标已存在则跳过，不覆盖新数据）→ 写迁移完成标记 → 后台线程清理旧目录释放空间。
 * 迁移失败时保留旧数据不动，下次启动自动重试。
 */
object LegacyDataMigrator {
    private const val TAG = "LegacyDataMigrator"
    private const val MARKER = ".rikkahub_external_migrated"

    /** rootfs 目录：含符号链接/特殊文件，FUSE 外部存储无法承载，禁止迁移 */
    private const val EXCLUDED_DIR = "workspaces"

    fun migrate(context: Context) {
        val external = AppPaths.filesDir(context)
        val internal = context.filesDir
        if (File(external, MARKER).exists()) return // 已完成过（或新装已标记）
        runCatching { if (external.canonicalPath == internal.canonicalPath) return }

        val legacyDbDir = context.getDatabasePath("rikka_hub").parentFile
        val hasLegacyFiles = internal.exists() && internal.list()?.isNotEmpty() == true
        val hasLegacyDb = legacyDbDir != null && legacyDbDir.exists() && legacyDbDir.list()?.isNotEmpty() == true
        if (!hasLegacyFiles && !hasLegacyDb) {
            markDone(external)
            return
        }

        Log.i(TAG, "开始迁移旧数据: files=$internal, databases=$legacyDbDir -> $external")
        val t0 = System.currentTimeMillis()
        try {
            if (hasLegacyFiles) copyContents(internal, external)
            if (hasLegacyDb && legacyDbDir != null) copyContents(legacyDbDir, File(external, "databases"))
            markDone(external)
            Log.i(TAG, "迁移完成，耗时 ${System.currentTimeMillis() - t0}ms")

            // 异步清理旧目录（释放空间）；失败仅残留空间，不影响运行。workspaces 保留不动。
            Thread {
                runCatching {
                    internal.listFiles()?.forEach { if (it.name != EXCLUDED_DIR) it.deleteRecursively() }
                    legacyDbDir?.listFiles()?.forEach { it.deleteRecursively() }
                }
                Log.i(TAG, "旧数据清理完成")
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "迁移失败，保留旧数据，下次启动重试", e)
        }
    }

    private fun markDone(external: File) {
        runCatching { File(external, MARKER).writeText(System.currentTimeMillis().toString()) }
    }

    private fun copyContents(src: File, dst: File) {
        src.listFiles()?.forEach { child ->
            if (child.name == EXCLUDED_DIR) return@forEach // 跳过 rootfs（不可迁移）
            val target = File(dst, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                copyContents(child, target)
            } else if (child.isFile && !target.exists()) {
                child.copyTo(target, overwrite = false)
            }
        }
    }
}
