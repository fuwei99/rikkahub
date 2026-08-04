package me.rerere.rikkahub.data.files

import android.content.Context
import java.io.File

/**
 * 统一数据根目录：优先外部专属存储，避免 data/data 深藏不可备份/不可恢复。
 *
 * 外部路径：/storage/emulated/0/Android/data/<pkg>/files（文件管理器 / ADB 可直接访问备份）
 * 外部存储不可用时（未挂载等）回退内部 filesDir。
 *
 * 全项目所有 filesDir 语义的目录都必须走这里，禁止再直接使用 context.filesDir。
 */
object AppPaths {
    @Volatile
    private var cachedFilesDir: File? = null

    /** 应用数据根目录（filesDir 语义），全局唯一入口 */
    fun filesDir(context: Context): File {
        cachedFilesDir?.let { return it }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        cachedFilesDir = dir
        return dir
    }

    /** Room 数据库文件（外部目录下 databases/rikka_hub） */
    fun databaseFile(context: Context): File =
        File(filesDir(context), "databases/rikka_hub")

    /** 工作区根目录（proot rootfs + 各 workspace 文件）。
     * ⚠️ 必须留在内部存储 data/data：rootfs 依赖符号链接/特殊文件，
     * 外部 FUSE（Android/data、公共目录）不支持创建 symlink，复制/迁移会损坏 rootfs。
     * 工作区用户文件可通过云同步/导出保护，rootfs 重新初始化即可。 */
    fun workspacesDir(context: Context): File =
        File(context.filesDir, "workspaces")

    /** 清空缓存（测试用） */
    fun reset() {
        cachedFilesDir = null
    }
}
