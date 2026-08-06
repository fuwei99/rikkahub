package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File

data class SettingsJsonExchangeResult(
    val file: File,
)

class SettingsJsonExchange(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val syncAdvancedConfigStore: SyncAdvancedConfigStore,
) {
    private val dir: File = File(AppPaths.filesDir(context), DIR_NAME)

    suspend fun exportAll(): SettingsJsonExchangeResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val settingsJson = JsonInstantPretty.encodeToJsonElement(Settings.serializer(), settingsStore.settingsFlow.value).jsonObject
        CONFIG_FILES.forEach { spec ->
            writeAtomically(
                target = File(dir, spec.fileName),
                content = spec.slice(settingsJson).toPrettyJson(),
            )
        }
        writeAtomically(
            target = File(dir, SYNC_ADVANCED_FILE),
            content = JsonInstantPretty.encodeToString(SyncAdvancedConfig.serializer(), syncAdvancedConfigStore.current),
        )
        // Old one-file export is deliberately removed so Agent never edits the huge stale file by mistake.
        File(dir, OLD_FULL_FILE_NAME).delete()
        File(dir, "$OLD_FULL_FILE_NAME.bak").delete()
        File(dir, "$OLD_FULL_FILE_NAME.tmp").delete()
        SettingsJsonExchangeResult(dir)
    }

    suspend fun importAllAndSync(): SettingsJsonExchangeResult = withContext(Dispatchers.IO) {
        require(dir.isDirectory) { "设置 JSON 目录不存在：${dir.absolutePath}" }
        val missing = EXPECTED_FILES.filterNot { File(dir, it).isFile }
        require(missing.isEmpty()) { "设置 JSON 文件不完整，缺少：${missing.joinToString()}" }

        var merged = JsonInstantPretty.encodeToJsonElement(Settings.serializer(), settingsStore.settingsFlow.value).jsonObject
        CONFIG_FILES.forEach { spec ->
            val file = File(dir, spec.fileName)
            val obj = JsonInstantPretty.parseToJsonElement(file.readText()).jsonObject
            merged = buildJsonObject {
                merged.forEach { (key, value) -> put(key, value) }
                obj.forEach { (key, value) -> put(key, value) }
            }
        }
        val nextSettings = JsonInstantPretty.decodeFromJsonElement(Settings.serializer(), merged)
        require(!nextSettings.init) { "不能导入 init=true 的占位设置" }

        val syncAdvanced = JsonInstantPretty.decodeFromString(
            SyncAdvancedConfig.serializer(),
            File(dir, SYNC_ADVANCED_FILE).readText(),
        )
        syncAdvancedConfigStore.update { syncAdvanced }
        settingsStore.update(nextSettings)
        SettingsJsonExchangeResult(dir)
    }

    private fun ConfigFileSpec.slice(settings: JsonObject): JsonObject = buildJsonObject {
        keys.forEach { key ->
            settings[key]?.let { value -> put(key, value) }
        }
    }

    private fun JsonObject.toPrettyJson(): String = JsonInstantPretty.encodeToString(JsonObject.serializer(), this)

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

    private data class ConfigFileSpec(
        val fileName: String,
        val keys: List<String>,
    )

    companion object {
        const val DIR_NAME = "setting-json"
        const val OLD_FULL_FILE_NAME = "rikkahub_settings_full.json"
        const val SYNC_ADVANCED_FILE = "sync_advanced.json"
        const val RELATIVE_PATH = DIR_NAME

        private val CONFIG_FILES = listOf(
            ConfigFileSpec("providers.json", listOf("providers")),
            ConfigFileSpec("image_providers.json", listOf("imageProviders")),
            ConfigFileSpec("vector_providers.json", listOf("vectorProviders")),
            ConfigFileSpec("mcp_servers.json", listOf("mcpServers")),
            ConfigFileSpec("assistants.json", listOf("assistants", "assistantTags", "assistantId")),
            ConfigFileSpec("image_tags.json", listOf("imageTags", "galleryFolders", "ocrMaxConcurrency", "ocrRatePerMinute", "ocrThinkingBudget")),
            ConfigFileSpec("tts_providers.json", listOf("ttsProviders", "selectedTTSProviderId")),
            ConfigFileSpec("asr_providers.json", listOf("asrProviders", "selectedASRProviderId")),
            ConfigFileSpec("search_services.json", listOf("searchServices", "searchCommonOptions", "searchServiceSelected")),
            ConfigFileSpec("file_processing_services.json", listOf("fileProcessingServices")),
            ConfigFileSpec("webdav_config.json", listOf("webDavConfig")),
            ConfigFileSpec("s3_config.json", listOf("s3Config")),
            ConfigFileSpec("d1_config.json", listOf("d1Config")),
            ConfigFileSpec("r2_accounts.json", listOf("r2Accounts", "r2PresignTtlSeconds")),
            ConfigFileSpec("mode_injections.json", listOf("modeInjections")),
            ConfigFileSpec("lorebooks.json", listOf("lorebooks")),
            ConfigFileSpec("quick_messages.json", listOf("quickMessages")),
            ConfigFileSpec("display_setting.json", listOf("displaySetting")),
            ConfigFileSpec("file_compress_setting.json", listOf("fileCompressSetting")),
            ConfigFileSpec("backup_reminder_config.json", listOf("backupReminderConfig")),
            ConfigFileSpec("memory_search_settings.json", listOf("memorySearch", "memoryInject")),
            ConfigFileSpec("custom_themes.json", listOf("customThemes", "themeId", "dynamicColor")),
            ConfigFileSpec(
                "model_selection.json",
                listOf(
                    "favoriteModels",
                    "chatModelId",
                    "fastModelId",
                    "titleModelId",
                    "translateModeId",
                    "suggestionModelId",
                    "imageGenerationModelId",
                    "imageGenerationModelIds",
                    "ocrModelId",
                    "memoryModelId",
                    "memoryInjectModelId",
                    "compressModelId",
                ),
            ),
            ConfigFileSpec(
                "prompts.json",
                listOf(
                    "titlePrompt",
                    "translatePrompt",
                    "translateThinkingBudget",
                    "enableSuggestion",
                    "suggestionPrompt",
                    "ocrPrompt",
                    "memoryPrompt",
                    "memoryThinkingBudget",
                    "memoryInjectPrompt",
                    "memoryInjectThinkingBudget",
                    "compressPrompt",
                ),
            ),
            ConfigFileSpec(
                "web_server.json",
                listOf(
                    "webServerEnabled",
                    "webServerPort",
                    "webServerJwtEnabled",
                    "webServerAccessPassword",
                    "webServerLocalhostOnly",
                ),
            ),
            ConfigFileSpec("misc_settings.json", listOf("developerMode", "launchCount", "sponsorAlertDismissedAt")),
        )

        private val EXPECTED_FILES = CONFIG_FILES.map { it.fileName } + SYNC_ADVANCED_FILE
    }
}
