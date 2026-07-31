package me.rerere.rikkahub.data.datastore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File

@Serializable
private data class SettingsJsonExchangeBundle(
    val formatVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: Settings,
    val syncAdvancedConfig: SyncAdvancedConfig,
)

data class SettingsJsonExchangeResult(
    val file: File,
)

class SettingsJsonExchange(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val syncAdvancedConfigStore: SyncAdvancedConfigStore,
) {
    private val dir: File = File(context.filesDir, DIR_NAME)
    private val file: File = File(dir, FILE_NAME)

    suspend fun exportAll(): SettingsJsonExchangeResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val bundle = SettingsJsonExchangeBundle(
            settings = settingsStore.settingsFlow.value,
            syncAdvancedConfig = syncAdvancedConfigStore.current,
        )
        writeAtomically(file, JsonInstantPretty.encodeToString(SettingsJsonExchangeBundle.serializer(), bundle))
        SettingsJsonExchangeResult(file)
    }

    suspend fun importAllAndSync(): SettingsJsonExchangeResult = withContext(Dispatchers.IO) {
        require(file.isFile) { "设置 JSON 不存在：${file.absolutePath}" }
        val bundle = JsonInstantPretty.decodeFromString(SettingsJsonExchangeBundle.serializer(), file.readText())
        require(!bundle.settings.init) { "不能导入 init=true 的占位设置" }
        syncAdvancedConfigStore.update { bundle.syncAdvancedConfig }
        settingsStore.update(bundle.settings)
        SettingsJsonExchangeResult(file)
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (target.isFile) {
            val bak = File(target.parentFile, "${target.name}.bak")
            runCatching { target.copyTo(bak, overwrite = true) }
        }
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    companion object {
        const val DIR_NAME = "setting-json"
        const val FILE_NAME = "rikkahub_settings_full.json"
        const val RELATIVE_PATH = "$DIR_NAME/$FILE_NAME"
    }
}
