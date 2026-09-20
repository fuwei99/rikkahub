package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File

/**
 * 用户配置的工具包（`setting-json/packages.json`）的内存态 + 读写。
 *
 * 与 [ToolPackagesLoader] 的分工：
 * - [ToolPackagesLoader] 是**只读**快照，给 ChatService 装配 tool_manage 时用；
 * - 本类是**可写**的真源，给 Package Manager UI 用（[packages] 是个 StateFlow，UI 直接订阅）。
 *
 * 两者读写同一个文件，所以 UI 改完落盘，下次会话装配时 loader 就能读到。不需要额外同步。
 *
 * 这个文件不在 [SettingsJsonExchange] 的 CONFIG_FILES 里，`export_settings` / `import_settings`
 * 都不会碰它 —— 纯手工 / 本 UI 维护。
 */
class ToolPackagesStore(private val context: Context) {

    private val file: File
        get() = File(File(AppPaths.filesDir(context), SettingsJsonExchange.DIR_NAME), FILE_NAME)

    private val _packages = MutableStateFlow(loadFromDisk())

    /** 当前配置的全部工具包；顺序即 UI 展示顺序，也是包之间的优先级。 */
    val packages: StateFlow<List<ToolPackage>> = _packages.asStateFlow()

    /** 重新从磁盘读一次（外部改过文件时用）。 */
    fun reload() {
        _packages.value = loadFromDisk()
    }

    private fun loadFromDisk(): List<ToolPackage> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        JsonInstant.decodeFromString(ToolPackagesConfig.serializer(), file.readText()).packages
    }.getOrDefault(emptyList())

    /**
     * 覆盖式落盘 + 更新内存态。写失败也会更新内存态（UI 不至于卡在旧值），
     * 只是磁盘上还是旧的 —— 下次启动会回退。调用方负责在 IO 线程执行。
     */
    fun save(list: List<ToolPackage>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                JsonInstantPretty.encodeToString(
                    ToolPackagesConfig.serializer(),
                    ToolPackagesConfig(packages = list),
                )
            )
        }
        _packages.value = list
    }

    companion object {
        const val FILE_NAME = ToolPackagesLoader.FILE_NAME
    }
}
