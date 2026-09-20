package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/**
 * 用户配置的「工具包」（package）。见 plan §2.6：
 * **package 是用户配置，不是代码硬编码** —— 一个包里的工具可以来自不同来源（local / workspace / 多个 MCP）。
 *
 * - [id]       稳定标识，`tool_manage` 的 `package=` 参数用它；
 * - [name]     展示名（可空，回退到 [id]）；
 * - [enabled]  只掐「发现层」（`list` / `enable`），**不掐「构造层」**（执行解析）；见 plan §3.4。
 *              关掉一个包：新对话里搜不到、挂不了；老对话里已挂的照调不误。
 * - [tools]    该包声明的工具 id（= 模型调用名 `Tool.name`）。
 */
@Serializable
data class ToolPackage(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val tools: List<String> = emptyList(),
)

@Serializable
data class ToolPackagesConfig(
    val version: Int = 1,
    val packages: List<ToolPackage> = emptyList(),
)

/**
 * 从 `setting-json/packages.json` 读用户配置的工具包。
 *
 * 这个文件不是 app 已知的设置分片（不在 [SettingsJsonExchange] 的 CONFIG_FILES 里），
 * 所以 `export_settings` / `import_settings` 都不会碰它 —— 纯手工维护，专门给 tool_manage 用。
 * 读不到 / 解析失败一律回空列表：tool_manage 会退化成「按来源自动成组」，不会把工具全藏掉。
 */
object ToolPackagesLoader {
    const val FILE_NAME = "packages.json"

    fun load(context: Context): List<ToolPackage> = runCatching {
        val dir = File(AppPaths.filesDir(context), SettingsJsonExchange.DIR_NAME)
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return@runCatching emptyList()
        JsonInstant.decodeFromString(ToolPackagesConfig.serializer(), file.readText()).packages
    }.getOrDefault(emptyList())
}
