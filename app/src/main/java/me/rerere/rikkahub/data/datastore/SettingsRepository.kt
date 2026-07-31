package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.r2.R2AccountConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "SettingsRepository"

// ============================================================
//  辅助函数（与原 PreferencesStore 中的私有扩展函数一致）
// ============================================================

/**
 * Adds new image model preset metadata to existing built-in image models without replacing
 * user-editable settings, and appends newly shipped preset models to existing built-in providers.
 * Matching user parameters win so users retain their own default and explanation; capabilities
 * are filled only where an old config still has an empty/default value.
 */
private fun ImageProviderSetting.withMissingPresetImageMetadata(): ImageProviderSetting {
    val presetProvider = DEFAULT_IMAGE_PROVIDERS.firstOrNull { it.id == id } ?: return this
    val upgradedModels = models.map { model ->
        val preset = presetProvider.models.firstOrNull { it.modelId == model.modelId } ?: return@map model
        val existingByKey = model.imageParameters.associateBy { it.key }
        val parameters = preset.imageParameters.map { parameter ->
            existingByKey[parameter.key] ?: parameter
        } + model.imageParameters.filter { parameter ->
            preset.imageParameters.none { it.key == parameter.key }
        }
        model.copy(
            imageCapabilities = model.imageCapabilities.withMissingPresetCapabilities(preset.imageCapabilities),
            imageSystemPrompt = model.imageSystemPrompt.ifBlank { preset.imageSystemPrompt },
            imageModelIdMappings = model.imageModelIdMappings.ifEmpty { preset.imageModelIdMappings },
            imageParameters = parameters,
        )
    }
    val missingPresetModels = presetProvider.models.filter { preset ->
        upgradedModels.none { model -> model.modelId == preset.modelId }
    }
    return copyProvider(models = upgradedModels + missingPresetModels)
}

private fun Model.withUrlInputIfKnown(provider: ProviderSetting? = null): Model {
    val registryInput = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId)
    val providerLooksLikeArk = provider is ProviderSetting.OpenAI &&
        provider.baseUrl.contains("ark.cn-beijing.volces.com", ignoreCase = true)
    val shouldEnableUrl = me.rerere.ai.provider.Modality.URL in registryInput || providerLooksLikeArk
    return if (shouldEnableUrl && me.rerere.ai.provider.Modality.URL !in inputModalities) {
        copy(inputModalities = inputModalities + me.rerere.ai.provider.Modality.URL)
    } else {
        this
    }
}

private fun ImageModelCapabilities.withMissingPresetCapabilities(
    preset: ImageModelCapabilities,
): ImageModelCapabilities = copy(
    supportsImageEditing = supportsImageEditing || preset.supportsImageEditing,
    maxReferenceImages = maxReferenceImages.takeIf { it > 0 } ?: preset.maxReferenceImages,
    loraProtocol = loraProtocol.takeUnless { it == me.rerere.ai.provider.WaveSpeedLoraProtocol.NONE }
        ?: preset.loraProtocol,
    maxLoras = maxLoras.takeIf { it > 0 } ?: preset.maxLoras,
    // Never overwrite the user's private token while importing preset capabilities.
    pImageHfApiToken = pImageHfApiToken,
)

/**
 * Settings 的纯 JSON 持久化层。
 *
 * 设计目标：
 * - 把 54 个 DataStore PreferencesKey 全部迁出到 `/rikkahub/files/config/*.json`。
 * - 顶层 JSON 字段名严格沿用原 PB Key 名（`providers` / `image_providers` / `assistants` 等），
 *   方便 Agent / 维护者一眼映射。
 * - 17 个领域文件（按主题合并），原子写（.tmp + renameTo），prettyPrint 输出。
 * - 内存唯一真源：单个 [MutableStateFlow] 保存完整 [Settings]，
 *   不需要外部 combine 多流。
 *
 * 数据流：
 * - 启动时构造器内 `runBlocking { loadAll() }` 把所有 JSON 读到内存
 * - 写盘：[SettingsStore.update] 调 [persistAll] 走 diff，只写变化领域
 * - 外部 Agent 修改：未来由 [SettingsFileWatcher]（Phase 3）调 [reload] 重新读盘
 * - 远端 D1 同步：[SettingsStore.applyRemote] 调 [updateSettings] 直接覆写内存
 */
class SettingsRepository(
    private val context: Context,
    @Suppress("unused") private val scope: AppScope,
) : KoinComponent {

    /** 配置文件目录：`/rikkahub/files/config/` */
    val configDir: File = File(context.filesDir, "config").apply { mkdirs() }

    private val pretty: Json = JsonInstantPretty

    /** 内存唯一真源。初始化为 dummy（init=true），启动后 loadAll() 替换为真实数据 */
    private val _settings = MutableStateFlow(Settings.dummy())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    init {
        // 构造器内同步阻塞启动一次加载，确保 Koin 注入后所有读取方立即拿到真实数据。
        // 加载总量 < 200KB，总耗时 < 50ms，开销可接受。
        runBlocking {
            // 一次性迁移：检测到 config/.migrated 不存在但 PB 文件存在时，从 PB 导出 JSON
            if (!isMigrated()) {
                val pbFile = java.io.File(context.filesDir, "datastore/settings.preferences_pb")
                if (pbFile.isFile) {
                    bootstrapFromDataStore(context.settingsStore.data.first())
                }
            }
            loadAll()
        }
    }

    // ============================================================
    //  公开 API
    // ============================================================

    /**
     * 重新从磁盘加载全部 JSON 到内存。
     * 主要供 Phase 3 的 FileObserver 在外部修改文件后调用。
     */
    suspend fun reload() = withContext(Dispatchers.IO) {
        _settings.value = postProcess(readAllFromDisk())
        invalidatePebbleCache()
    }

    /**
     * 替换内存中的 Settings（远端回写或 FileObserver 重载后调用）。
     * 调用方需要自己确保在 `SyncApplyGate.applyingRemote` 期间调用以避免 Outbox 重复入队。
     */
    fun updateSettings(newSettings: Settings) {
        _settings.value = postProcess(newSettings)
        invalidatePebbleCache()
    }

    /**
     * 持久化：把内存中下一个 [Settings] 与当前 diff，只写实际变化的领域。
     * 单一入口供 [SettingsStore.update] 调用。
     */
    suspend fun persistAll(next: Settings) = withContext(Dispatchers.IO) {
        val prev = _settings.value
        if (prev == next) return@withContext
        diffAndWrite(prev, next)
        _settings.value = postProcess(next)
    }

    /**
     * 单独写 assistants 领域（用于只切换 selected assistant 这种细粒度更新）。
     */
    suspend fun saveAssistants(
        assistants: List<Assistant>,
        tags: List<me.rerere.rikkahub.data.model.Tag>,
        selectedId: Uuid,
    ) = withContext(Dispatchers.IO) {
        writeJsonFile(FILE_ASSISTANTS, mapOf(
            "assistants" to JsonInstant.encodeToJsonElement(assistants),
            "assistant_tags" to JsonInstant.encodeToJsonElement(tags),
            "select_assistant" to JsonPrimitive(selectedId.toString()),
        ))
        _settings.value = postProcess(_settings.value.copy(
            assistants = assistants,
            assistantTags = tags,
            assistantId = selectedId,
        ))
    }

    /**
     * 一次性把 DataStore Protobuf 中的全部 Key 导出为 JSON。
     * 仅在 v4 迁移阶段使用：检测到 `config/providers.json` 缺失但 PB 文件存在时触发。
     */
    suspend fun bootstrapFromDataStore(preferences: Preferences) = withContext(Dispatchers.IO) {
        runCatching {
            val fromPb = buildSettingsFromPreferences(preferences)
            writeAllFromSettings(fromPb)
            Log.i(TAG, "bootstrapFromDataStore: exported PB to JSON files")
        }.onFailure {
            Log.e(TAG, "bootstrapFromDataStore failed", it)
        }
    }

    /**
     * 是否已经迁出为 JSON（用 markers 文件 `config/.migrated` 标记）。
     */
    fun isMigrated(): Boolean = File(configDir, ".migrated").isFile

    /**
     * 把完整 Settings 写出全部 17 个领域 JSON。
     * 主要供 bootstrap 阶段或"全量导出"使用。
     */
    private suspend fun writeAllFromSettings(s: Settings) {
        writeJsonFile(FILE_PROVIDERS, mapOf(KEY_PROVIDERS to JsonInstant.encodeToJsonElement(s.providers)))
        writeJsonFile(FILE_IMAGE_PROVIDERS, mapOf(KEY_IMAGE_PROVIDERS to JsonInstant.encodeToJsonElement(s.imageProviders)))
        writeJsonFile(FILE_ASSISTANTS, assistantsMap(s))
        writeJsonFile(FILE_MODELS, modelsMap(s))
        writeJsonFile(FILE_PROMPTS, promptsMap(s))
        writeJsonFile(FILE_MCP_SERVERS, mapOf(KEY_MCP_SERVERS to JsonInstant.encodeToJsonElement(s.mcpServers)))
        writeJsonFile(FILE_SEARCH_SERVICES, searchMap(s))
        writeJsonFile(FILE_FILE_PROCESSING, mapOf(KEY_FILE_PROCESSING_SERVICES to JsonInstant.encodeToJsonElement(s.fileProcessingServices)))
        writeJsonFile(FILE_WEBDAV, mapOf(KEY_WEBDAV_CONFIG to JsonInstant.encodeToJsonElement(s.webDavConfig)))
        writeJsonFile(FILE_S3, mapOf(KEY_S3_CONFIG to JsonInstant.encodeToJsonElement(s.s3Config)))
        writeJsonFile(FILE_D1, mapOf(KEY_D1_CONFIG to JsonInstant.encodeToJsonElement(s.d1Config)))
        writeJsonFile(FILE_R2, r2Map(s))
        writeJsonFile(FILE_TTS, ttsMap(s))
        writeJsonFile(FILE_ASR, asrMap(s))
        writeJsonFile(FILE_MODE_INJECTIONS, mapOf(KEY_MODE_INJECTIONS to JsonInstant.encodeToJsonElement(s.modeInjections)))
        writeJsonFile(FILE_LOREBOOKS, mapOf(KEY_LOREBOOKS to JsonInstant.encodeToJsonElement(s.lorebooks)))
        writeJsonFile(FILE_QUICK_MESSAGES, mapOf(KEY_QUICK_MESSAGES to JsonInstant.encodeToJsonElement(s.quickMessages)))
        writeJsonFile(FILE_DISPLAY, displayMap(s))
        writeJsonFile(FILE_UI, uiMap(s))
        writeJsonFile(FILE_WEB_SERVER, webServerMap(s))
        writeJsonFile(FILE_MISC, miscMap(s))
        // marker
        File(configDir, ".migrated").writeText(System.currentTimeMillis().toString())
    }

    // ============================================================
    //  内部实现 —— 加载 / 写盘 / 派生清洗
    // ============================================================

    private suspend fun loadAll() = withContext(Dispatchers.IO) {
        _settings.value = postProcess(readAllFromDisk())
        invalidatePebbleCache()
    }

    private fun readAllFromDisk(): Settings {
        val providers = readJsonFile<List<ProviderSetting>>(FILE_PROVIDERS, KEY_PROVIDERS) ?: DEFAULT_PROVIDERS
        val imageProviders = readJsonFile<List<ImageProviderSetting>>(FILE_IMAGE_PROVIDERS, KEY_IMAGE_PROVIDERS) ?: DEFAULT_IMAGE_PROVIDERS

        val assistantsBundle = readJsonFile<AssistantsBundle>(FILE_ASSISTANTS, null)
            ?: AssistantsBundle()

        val modelsBundle = readJsonFile<ModelsBundle>(FILE_MODELS, null) ?: ModelsBundle()
        val promptsBundle = readJsonFile<PromptsBundle>(FILE_PROMPTS, null) ?: PromptsBundle()

        val mcpServers = readJsonFile<List<McpServerConfig>>(FILE_MCP_SERVERS, KEY_MCP_SERVERS) ?: emptyList()

        val searchBundle = readJsonFile<SearchServicesBundle>(FILE_SEARCH_SERVICES, null) ?: SearchServicesBundle()
        val fileProcessing = readJsonFile<List<FileProcessingServiceOptions>>(FILE_FILE_PROCESSING, KEY_FILE_PROCESSING_SERVICES)

        val webDav = readJsonFile<WebDavConfig>(FILE_WEBDAV, KEY_WEBDAV_CONFIG) ?: WebDavConfig()
        val s3 = readJsonFile<S3Config>(FILE_S3, KEY_S3_CONFIG) ?: S3Config()
        val d1 = readJsonFile<D1Config>(FILE_D1, KEY_D1_CONFIG) ?: D1Config()
        val r2 = readJsonFile<R2Bundle>(FILE_R2, null) ?: R2Bundle()

        val tts = readJsonFile<TTSBundle>(FILE_TTS, null) ?: TTSBundle()
        val asr = readJsonFile<ASRBundle>(FILE_ASR, null) ?: ASRBundle()

        val modeInjections = readJsonFile<List<PromptInjection.ModeInjection>>(FILE_MODE_INJECTIONS, KEY_MODE_INJECTIONS)
            ?: DEFAULT_MODE_INJECTIONS
        val lorebooks = readJsonFile<List<Lorebook>>(FILE_LOREBOOKS, KEY_LOREBOOKS) ?: emptyList()
        val quickMessages = readJsonFile<List<QuickMessage>>(FILE_QUICK_MESSAGES, KEY_QUICK_MESSAGES) ?: emptyList()

        val display = readJsonFile<DisplayBundle>(FILE_DISPLAY, null) ?: DisplayBundle()
        val ui = readJsonFile<UIBundle>(FILE_UI, null) ?: UIBundle()
        val webServer = readJsonFile<WebServerBundle>(FILE_WEB_SERVER, null) ?: WebServerBundle()
        val misc = readJsonFile<MiscBundle>(FILE_MISC, null) ?: MiscBundle()

        return Settings(
            // 标量
            dynamicColor = ui.dynamic_color,
            themeId = ui.theme_id.ifEmpty { PresetThemes[0].id },
            customThemes = ui.custom_themes,
            developerMode = ui.developer_mode,
            displaySetting = display.display_setting,
            fileCompressSetting = display.file_compress_setting,
            favoriteModels = modelsBundle.favorite_models,
            chatModelId = modelsBundle.chat_model,
            fastModelId = modelsBundle.fast_model,
            titleModelId = modelsBundle.title_model,
            imageGenerationModelId = modelsBundle.image_generation_model,
            imageGenerationModelIds = if (modelsBundle.image_generation_models.isEmpty()) {
                listOf(modelsBundle.image_generation_model)
            } else modelsBundle.image_generation_models,
            titlePrompt = promptsBundle.title_prompt,
            translateModeId = modelsBundle.translate_model,
            translatePrompt = promptsBundle.translation_prompt,
            translateThinkingBudget = modelsBundle.translate_thinking_budget,
            enableSuggestion = modelsBundle.enable_suggestion,
            suggestionModelId = modelsBundle.suggestion_model,
            suggestionPrompt = promptsBundle.suggestion_prompt,
            ocrModelId = modelsBundle.ocr_model,
            ocrPrompt = promptsBundle.ocr_prompt,
            compressModelId = modelsBundle.compress_model,
            compressPrompt = promptsBundle.compress_prompt,
            assistantId = assistantsBundle.select_assistant,
            providers = providers,
            imageProviders = imageProviders,
            assistants = assistantsBundle.assistants,
            assistantTags = assistantsBundle.assistant_tags,
            searchServices = searchBundle.search_services,
            searchCommonOptions = searchBundle.search_common,
            searchServiceSelected = searchBundle.search_selected,
            mcpServers = mcpServers,
            fileProcessingServices = fileProcessing ?: defaultFileProcessingServices(display.display_setting),
            webDavConfig = webDav,
            s3Config = s3,
            d1Config = d1,
            r2Accounts = r2.r2_accounts,
            r2PresignTtlSeconds = r2.r2_presign_ttl_seconds,
            ttsProviders = tts.tts_providers,
            selectedTTSProviderId = tts.selected_tts_provider,
            asrProviders = asr.asr_providers,
            selectedASRProviderId = asr.selected_asr_provider,
            modeInjections = modeInjections,
            lorebooks = lorebooks,
            quickMessages = quickMessages,
            webServerEnabled = webServer.web_server_enabled,
            webServerPort = webServer.web_server_port,
            webServerJwtEnabled = webServer.web_server_jwt_enabled,
            webServerAccessPassword = webServer.web_server_access_password,
            webServerLocalhostOnly = webServer.web_server_localhost_only,
            backupReminderConfig = misc.backup_reminder_config,
            launchCount = misc.launch_count,
            sponsorAlertDismissedAt = misc.sponsor_alert_dismissed_at,
        )
    }

    // ----- 各领域 map 构造（供 writeAllFromSettings 和 diffAndWrite 共用） -----

    private fun assistantsMap(s: Settings) = mapOf(
        "assistants" to JsonInstant.encodeToJsonElement(s.assistants),
        "assistant_tags" to JsonInstant.encodeToJsonElement(s.assistantTags),
        "select_assistant" to JsonPrimitive(s.assistantId.toString()),
    )

    private fun modelsMap(s: Settings) = mapOf(
        "chat_model" to JsonPrimitive(s.chatModelId.toString()),
        "fast_model" to JsonPrimitive(s.fastModelId.toString()),
        "title_model" to (s.titleModelId?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        "translate_model" to JsonPrimitive(s.translateModeId.toString()),
        "ocr_model" to JsonPrimitive(s.ocrModelId.toString()),
        "compress_model" to JsonPrimitive(s.compressModelId.toString()),
        "suggestion_model" to (s.suggestionModelId?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        "image_generation_model" to JsonPrimitive(s.imageGenerationModelId.toString()),
        "image_generation_models" to JsonInstant.encodeToJsonElement(s.imageGenerationModelIds),
        "favorite_models" to JsonInstant.encodeToJsonElement(s.favoriteModels),
        "enable_suggestion" to JsonPrimitive(s.enableSuggestion),
        "translate_thinking_budget" to JsonPrimitive(s.translateThinkingBudget),
    )

    private fun promptsMap(s: Settings) = mapOf(
        "title_prompt" to JsonPrimitive(s.titlePrompt),
        "translation_prompt" to JsonPrimitive(s.translatePrompt),
        "suggestion_prompt" to JsonPrimitive(s.suggestionPrompt),
        "ocr_prompt" to JsonPrimitive(s.ocrPrompt),
        "compress_prompt" to JsonPrimitive(s.compressPrompt),
    )

    private fun searchMap(s: Settings) = mapOf(
        "search_services" to JsonInstant.encodeToJsonElement(s.searchServices),
        "search_common" to JsonInstant.encodeToJsonElement(s.searchCommonOptions),
        "search_selected" to JsonPrimitive(s.searchServiceSelected),
    )

    private fun r2Map(s: Settings) = mapOf(
        "r2_accounts" to JsonInstant.encodeToJsonElement(s.r2Accounts),
        "r2_presign_ttl_seconds" to JsonPrimitive(s.r2PresignTtlSeconds),
    )

    private fun ttsMap(s: Settings) = mapOf(
        "tts_providers" to JsonInstant.encodeToJsonElement(s.ttsProviders),
        "selected_tts_provider" to JsonPrimitive(s.selectedTTSProviderId.toString()),
    )

    private fun asrMap(s: Settings) = mapOf(
        "asr_providers" to JsonInstant.encodeToJsonElement(s.asrProviders),
        "selected_asr_provider" to (s.selectedASRProviderId?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
    )

    private fun displayMap(s: Settings) = mapOf(
        "display_setting" to JsonInstant.encodeToJsonElement(s.displaySetting),
        "file_compress_setting" to JsonInstant.encodeToJsonElement(s.fileCompressSetting),
    )

    private fun uiMap(s: Settings) = mapOf(
        "dynamic_color" to JsonPrimitive(s.dynamicColor),
        "theme_id" to JsonPrimitive(s.themeId),
        "custom_themes" to JsonInstant.encodeToJsonElement(s.customThemes),
        "developer_mode" to JsonPrimitive(s.developerMode),
    )

    private fun webServerMap(s: Settings) = mapOf(
        "web_server_enabled" to JsonPrimitive(s.webServerEnabled),
        "web_server_port" to JsonPrimitive(s.webServerPort),
        "web_server_jwt_enabled" to JsonPrimitive(s.webServerJwtEnabled),
        "web_server_access_password" to JsonPrimitive(s.webServerAccessPassword),
        "web_server_localhost_only" to JsonPrimitive(s.webServerLocalhostOnly),
    )

    private fun miscMap(s: Settings) = mapOf(
        "backup_reminder_config" to JsonInstant.encodeToJsonElement(s.backupReminderConfig),
        "launch_count" to JsonPrimitive(s.launchCount),
        "sponsor_alert_dismissed_at" to JsonPrimitive(s.sponsorAlertDismissedAt),
    )

    /**
     * Diff 写盘：把 [next] 与 [prev] 按领域比较，只写变化领域。
     */
    private suspend fun diffAndWrite(prev: Settings, next: Settings) {
        if (prev.providers != next.providers) {
            writeJsonFile(FILE_PROVIDERS, mapOf(KEY_PROVIDERS to JsonInstant.encodeToJsonElement(next.providers)))
        }
        if (prev.imageProviders != next.imageProviders) {
            writeJsonFile(FILE_IMAGE_PROVIDERS, mapOf(KEY_IMAGE_PROVIDERS to JsonInstant.encodeToJsonElement(next.imageProviders)))
        }
        if (prev.assistants != next.assistants || prev.assistantTags != next.assistantTags || prev.assistantId != next.assistantId) {
            writeJsonFile(FILE_ASSISTANTS, assistantsMap(next))
        }
        if (prev.chatModelId != next.chatModelId ||
            prev.fastModelId != next.fastModelId ||
            prev.titleModelId != next.titleModelId ||
            prev.translateModeId != next.translateModeId ||
            prev.ocrModelId != next.ocrModelId ||
            prev.compressModelId != next.compressModelId ||
            prev.suggestionModelId != next.suggestionModelId ||
            prev.imageGenerationModelId != next.imageGenerationModelId ||
            prev.imageGenerationModelIds != next.imageGenerationModelIds ||
            prev.favoriteModels != next.favoriteModels ||
            prev.enableSuggestion != next.enableSuggestion ||
            prev.translateThinkingBudget != next.translateThinkingBudget
        ) {
            writeJsonFile(FILE_MODELS, modelsMap(next))
        }
        if (prev.titlePrompt != next.titlePrompt ||
            prev.translatePrompt != next.translatePrompt ||
            prev.suggestionPrompt != next.suggestionPrompt ||
            prev.ocrPrompt != next.ocrPrompt ||
            prev.compressPrompt != next.compressPrompt
        ) {
            writeJsonFile(FILE_PROMPTS, promptsMap(next))
        }
        if (prev.mcpServers != next.mcpServers) {
            writeJsonFile(FILE_MCP_SERVERS, mapOf(KEY_MCP_SERVERS to JsonInstant.encodeToJsonElement(next.mcpServers)))
        }
        if (prev.searchServices != next.searchServices || prev.searchCommonOptions != next.searchCommonOptions || prev.searchServiceSelected != next.searchServiceSelected) {
            writeJsonFile(FILE_SEARCH_SERVICES, searchMap(next))
        }
        if (prev.fileProcessingServices != next.fileProcessingServices) {
            writeJsonFile(FILE_FILE_PROCESSING, mapOf(KEY_FILE_PROCESSING_SERVICES to JsonInstant.encodeToJsonElement(next.fileProcessingServices)))
        }
        if (prev.webDavConfig != next.webDavConfig) {
            writeJsonFile(FILE_WEBDAV, mapOf(KEY_WEBDAV_CONFIG to JsonInstant.encodeToJsonElement(next.webDavConfig)))
        }
        if (prev.s3Config != next.s3Config) {
            writeJsonFile(FILE_S3, mapOf(KEY_S3_CONFIG to JsonInstant.encodeToJsonElement(next.s3Config)))
        }
        if (prev.d1Config != next.d1Config) {
            writeJsonFile(FILE_D1, mapOf(KEY_D1_CONFIG to JsonInstant.encodeToJsonElement(next.d1Config)))
        }
        if (prev.r2Accounts != next.r2Accounts || prev.r2PresignTtlSeconds != next.r2PresignTtlSeconds) {
            writeJsonFile(FILE_R2, r2Map(next))
        }
        if (prev.ttsProviders != next.ttsProviders || prev.selectedTTSProviderId != next.selectedTTSProviderId) {
            writeJsonFile(FILE_TTS, ttsMap(next))
        }
        if (prev.asrProviders != next.asrProviders || prev.selectedASRProviderId != next.selectedASRProviderId) {
            writeJsonFile(FILE_ASR, asrMap(next))
        }
        if (prev.modeInjections != next.modeInjections) {
            writeJsonFile(FILE_MODE_INJECTIONS, mapOf(KEY_MODE_INJECTIONS to JsonInstant.encodeToJsonElement(next.modeInjections)))
        }
        if (prev.lorebooks != next.lorebooks) {
            writeJsonFile(FILE_LOREBOOKS, mapOf(KEY_LOREBOOKS to JsonInstant.encodeToJsonElement(next.lorebooks)))
        }
        if (prev.quickMessages != next.quickMessages) {
            writeJsonFile(FILE_QUICK_MESSAGES, mapOf(KEY_QUICK_MESSAGES to JsonInstant.encodeToJsonElement(next.quickMessages)))
        }
        if (prev.displaySetting != next.displaySetting || prev.fileCompressSetting != next.fileCompressSetting) {
            writeJsonFile(FILE_DISPLAY, displayMap(next))
        }
        if (prev.dynamicColor != next.dynamicColor || prev.themeId != next.themeId ||
            prev.customThemes != next.customThemes || prev.developerMode != next.developerMode
        ) {
            writeJsonFile(FILE_UI, uiMap(next))
        }
        if (prev.webServerEnabled != next.webServerEnabled || prev.webServerPort != next.webServerPort ||
            prev.webServerJwtEnabled != next.webServerJwtEnabled || prev.webServerAccessPassword != next.webServerAccessPassword ||
            prev.webServerLocalhostOnly != next.webServerLocalhostOnly
        ) {
            writeJsonFile(FILE_WEB_SERVER, webServerMap(next))
        }
        if (prev.backupReminderConfig != next.backupReminderConfig || prev.launchCount != next.launchCount ||
            prev.sponsorAlertDismissedAt != next.sponsorAlertDismissedAt
        ) {
            writeJsonFile(FILE_MISC, miscMap(next))
        }
    }

    /**
     * 把 JSON 顶层 map 写出文件，使用 .tmp + renameTo 原子写。
     */
    private fun writeJsonFile(fileName: String, topLevel: Map<String, JsonElement>) {
        runCatching {
            val obj = JsonObject(topLevel)
            val text = pretty.encodeToString(obj)
            val file = File(configDir, fileName)
            file.parentFile?.mkdirs()
            val tmp = File(file.parent, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // 失败：直接覆盖
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure {
            Log.e(TAG, "writeJsonFile failed: $fileName", it)
        }
    }

    /**
     * 从 JSON 文件读取顶层 `<key>` 字段并反序列化为 [T]。
     * - 文件不存在：返回 null
     * - 解析失败：返回 null 并记录日志
     * - key == null：把整个 JSON 对象作为 [T] 反序列化（用于 bundle 类型）
     */
    private inline fun <reified T> readJsonFile(fileName: String, key: String?): T? {
        val file = File(configDir, fileName)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val obj = JsonInstant.parseToJsonElement(text) as? JsonObject ?: return null
            val target: JsonElement = if (key != null) {
                obj[key] ?: return null
            } else {
                obj
            }
            JsonInstant.decodeFromJsonElement<T>(target)
        }.onFailure {
            Log.w(TAG, "readJsonFile failed: $fileName (key=$key)", it)
        }.getOrNull()
    }

    /**
     * 派生清洗链：补默认 + 覆盖 defaultProvider 元数据 + 预设 image provider 补全 + 去重 + 过滤无效引用。
     * 完整复刻原 PreferencesStore 中第二/三个 .map 的逻辑。
     */
    private fun postProcess(input: Settings): Settings {
        // ---- 第二个 .map：补默认 ----
        var providers = input.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
        DEFAULT_PROVIDERS.forEach { defaultProvider ->
            if (providers.none { it.id == defaultProvider.id }) {
                providers.add(defaultProvider.copyProvider())
            }
        }
        providers = providers.map { provider ->
            val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
            if (defaultProvider != null) {
                provider.copyProvider(
                    builtIn = defaultProvider.builtIn,
                    description = defaultProvider.description,
                    shortDescription = defaultProvider.shortDescription,
                )
            } else provider
        }.toMutableList()

        val assistants = input.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
        DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
            if (assistants.none { it.id == defaultAssistant.id }) {
                assistants.add(defaultAssistant.copy())
            }
        }

        val ttsProviders = input.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
        DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
            if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                ttsProviders.add(defaultTTSProvider.copyProvider())
            }
        }

        val imageProviders = input.imageProviders.ifEmpty { DEFAULT_IMAGE_PROVIDERS }.toMutableList()
        DEFAULT_IMAGE_PROVIDERS.forEach { defaultImageProvider ->
            if (imageProviders.none { provider -> provider.id == defaultImageProvider.id }) {
                imageProviders.add(defaultImageProvider.copyProvider())
            }
        }

        var step1 = input.copy(
            providers = providers,
            assistants = assistants,
            ttsProviders = ttsProviders,
            imageProviders = imageProviders.map { it.withMissingPresetImageMetadata() },
        )

        // ---- 第三个 .map：去重 + 过滤无效引用 + 端点升级 ----
        val validMcpServerIds = step1.mcpServers.map { it.id }.toSet()
        val validModeInjectionIds = step1.modeInjections.map { it.id }.toSet()
        val validLorebookIds = step1.lorebooks.map { it.id }.toSet()
        val validQuickMessageIds = step1.quickMessages.map { it.id }.toSet()
        val asrProviders = step1.asrProviders.distinctBy { it.id }

        return step1.copy(
            providers = step1.providers.distinctBy { it.id }.map { provider ->
                when (provider) {
                    is ProviderSetting.OpenAI -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }.map { it.withUrlInputIfKnown(provider) }
                    )
                    is ProviderSetting.Google -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }.map { it.withUrlInputIfKnown(provider) }
                    )
                    is ProviderSetting.Claude -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }.map { it.withUrlInputIfKnown(provider) }
                    )
                }
            },
            assistants = step1.assistants.distinctBy { it.id }.map { assistant ->
                assistant.copy(
                    mcpServers = assistant.mcpServers.filter { serverId -> serverId in validMcpServerIds }.toSet(),
                    modeInjectionIds = assistant.modeInjectionIds.filter { id -> id in validModeInjectionIds }.toSet(),
                    lorebookIds = assistant.lorebookIds.filter { id -> id in validLorebookIds }.toSet(),
                    quickMessageIds = assistant.quickMessageIds.filter { id -> id in validQuickMessageIds }.toSet(),
                )
            },
            ttsProviders = step1.ttsProviders.distinctBy { it.id },
            imageProviders = step1.imageProviders.distinctBy { it.id }.map { provider ->
                when (provider) {
                    is ImageProviderSetting.OpenAI -> {
                        val distinctModels = provider.models.distinctBy { model -> model.id }
                        if (provider.id == DEEP_MAT_NEWAPI_PROVIDER_ID) {
                            ImageProviderSetting.NewAPI(
                                id = provider.id,
                                enabled = provider.enabled,
                                name = provider.name,
                                models = distinctModels,
                                builtIn = provider.builtIn,
                                description = provider.description,
                                shortDescription = provider.shortDescription,
                                apiKey = provider.apiKey,
                                baseUrl = provider.baseUrl,
                            )
                        } else {
                            provider.copy(models = distinctModels)
                        }
                    }
                    is ImageProviderSetting.NewAPI -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }
                    )
                    is ImageProviderSetting.Volcengine -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id },
                        baseUrl = if (provider.baseUrl == "https://ark.cn-beijing.volces.com/api/v3") {
                            "https://ark.cn-beijing.volces.com/api/plan/v3"
                        } else {
                            provider.baseUrl
                        }
                    )
                    is ImageProviderSetting.Wavespeed -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }
                    )
                }
            },
            asrProviders = asrProviders,
            selectedASRProviderId = step1.selectedASRProviderId
                ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                ?: asrProviders.firstOrNull()?.id,
            favoriteModels = step1.favoriteModels.filter { uuid ->
                step1.providers.flatMap { it.models }.any { it.id == uuid }
            },
            imageGenerationModelIds = step1.imageGenerationModelIds.filter { uuid ->
                step1.imageProviders.flatMap { it.models }.any { it.id == uuid }
            },
            modeInjections = step1.modeInjections.distinctBy { it.id },
            lorebooks = step1.lorebooks.distinctBy { it.id },
            quickMessages = step1.quickMessages.distinctBy { it.id },
        )
    }

    private fun invalidatePebbleCache() {
        runCatching { get<PebbleEngine>().templateCache.invalidateAll() }
    }

    /**
     * 把 DataStore Preferences 还原为 Settings（沿用原 PreferencesStore 第一个 .map 的逻辑）。
     * 仅供 bootstrap 阶段使用。
     */
    private fun buildSettingsFromPreferences(preferences: Preferences): Settings {
        val imageGenerationModelId = preferences[K_IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random()
        return Settings(
            favoriteModels = preferences[K_FAVORITE_MODELS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            chatModelId = preferences[K_CHAT_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
            fastModelId = preferences[K_FAST_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
            titleModelId = preferences[K_TITLE_MODEL]?.let { Uuid.parse(it) },
            translateModeId = preferences[K_TRANSLATE_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
            enableSuggestion = preferences[K_ENABLE_SUGGESTION] != false,
            suggestionModelId = preferences[K_SUGGESTION_MODEL]?.let { Uuid.parse(it) },
            imageGenerationModelId = imageGenerationModelId,
            imageGenerationModelIds = preferences[K_IMAGE_GENERATION_MODELS]?.let { JsonInstant.decodeFromString<List<Uuid>>(it) }
                ?.ifEmpty { listOf(imageGenerationModelId) } ?: listOf(imageGenerationModelId),
            titlePrompt = preferences[K_TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
            translatePrompt = preferences[K_TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
            translateThinkingBudget = preferences[K_TRANSLATE_THINKING_BUDGET] ?: 0,
            suggestionPrompt = preferences[K_SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
            ocrModelId = preferences[K_OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
            ocrPrompt = preferences[K_OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
            compressModelId = preferences[K_COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
            compressPrompt = preferences[K_COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
            assistantId = preferences[K_SELECT_ASSISTANT]?.let { Uuid.parse(it) } ?: DEFAULT_ASSISTANT_ID,
            assistantTags = preferences[K_ASSISTANT_TAGS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            providers = JsonInstant.decodeFromString(preferences[K_PROVIDERS] ?: "[]"),
            assistants = JsonInstant.decodeFromString(preferences[K_ASSISTANTS] ?: "[]"),
            dynamicColor = preferences[K_DYNAMIC_COLOR] != false,
            themeId = preferences[K_THEME_ID] ?: PresetThemes[0].id,
            customThemes = preferences[K_CUSTOM_THEMES]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            developerMode = preferences[K_DEVELOPER_MODE] == true,
            displaySetting = JsonInstant.decodeFromString(preferences[K_DISPLAY_SETTING] ?: "{}"),
            fileCompressSetting = preferences[K_FILE_COMPRESS_SETTING]?.let {
                JsonInstant.decodeFromString<FileCompressSetting>(it)
            } ?: run {
                val display = JsonInstant.decodeFromString<DisplaySetting>(preferences[K_DISPLAY_SETTING] ?: "{}")
                FileCompressSetting(
                    chatImageJpegQuality = display.imageCompressJpegQuality,
                    chatImageSkipBytes = display.imageCompressSkipBytes,
                )
            },
            searchServices = preferences[K_SEARCH_SERVICES]?.let { JsonInstant.decodeFromString(it) } ?: listOf(SearchServiceOptions.DEFAULT),
            searchCommonOptions = preferences[K_SEARCH_COMMON]?.let { JsonInstant.decodeFromString(it) } ?: SearchCommonOptions(),
            searchServiceSelected = preferences[K_SEARCH_SELECTED] ?: 0,
            mcpServers = preferences[K_MCP_SERVERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            fileProcessingServices = preferences[K_FILE_PROCESSING_SERVICES]?.let {
                JsonInstant.decodeFromString(it)
            } ?: defaultFileProcessingServices(JsonInstant.decodeFromString(preferences[K_DISPLAY_SETTING] ?: "{}")),
            webDavConfig = preferences[K_WEBDAV_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: WebDavConfig(),
            s3Config = preferences[K_S3_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: S3Config(),
            d1Config = preferences[K_D1_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: D1Config(),
            r2Accounts = preferences[K_R2_ACCOUNTS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            r2PresignTtlSeconds = (preferences[K_R2_PRESIGN_TTL_SECONDS] ?: 86_400).toLong(),
            ttsProviders = preferences[K_TTS_PROVIDERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            selectedTTSProviderId = preferences[K_SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) } ?: DEFAULT_SYSTEM_TTS_ID,
            imageProviders = preferences[K_IMAGE_PROVIDERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            asrProviders = preferences[K_ASR_PROVIDERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            selectedASRProviderId = preferences[K_SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
            modeInjections = preferences[K_MODE_INJECTIONS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            lorebooks = preferences[K_LOREBOOKS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            quickMessages = preferences[K_QUICK_MESSAGES]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
            webServerEnabled = preferences[K_WEB_SERVER_ENABLED] == true,
            webServerPort = preferences[K_WEB_SERVER_PORT] ?: 8080,
            webServerJwtEnabled = preferences[K_WEB_SERVER_JWT_ENABLED] == true,
            webServerAccessPassword = preferences[K_WEB_SERVER_ACCESS_PASSWORD] ?: "",
            webServerLocalhostOnly = preferences[K_WEB_SERVER_LOCALHOST_ONLY] == true,
            backupReminderConfig = preferences[K_BACKUP_REMINDER_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: BackupReminderConfig(),
            launchCount = preferences[K_LAUNCH_COUNT] ?: 0,
            sponsorAlertDismissedAt = preferences[K_SPONSOR_ALERT_DISMISSED_AT] ?: 0,
        )
    }

    companion object {
        // ----- 文件名常量 -----
        private const val FILE_PROVIDERS = "providers.json"
        private const val FILE_IMAGE_PROVIDERS = "image_providers.json"
        private const val FILE_ASSISTANTS = "assistants.json"
        private const val FILE_MODELS = "models.json"
        private const val FILE_PROMPTS = "prompts.json"
        private const val FILE_MCP_SERVERS = "mcp_servers.json"
        private const val FILE_SEARCH_SERVICES = "search_services.json"
        private const val FILE_FILE_PROCESSING = "file_processing_services.json"
        private const val FILE_WEBDAV = "webdav_config.json"
        private const val FILE_S3 = "s3_config.json"
        private const val FILE_D1 = "d1_config.json"
        private const val FILE_R2 = "r2_accounts.json"
        private const val FILE_TTS = "tts_providers.json"
        private const val FILE_ASR = "asr_providers.json"
        private const val FILE_MODE_INJECTIONS = "mode_injections.json"
        private const val FILE_LOREBOOKS = "lorebooks.json"
        private const val FILE_QUICK_MESSAGES = "quick_messages.json"
        private const val FILE_DISPLAY = "display.json"
        private const val FILE_UI = "ui.json"
        private const val FILE_WEB_SERVER = "web_server.json"
        private const val FILE_MISC = "misc.json"

        // ----- 原 PB Key 常量（用于文件内字段名） -----
        const val KEY_PROVIDERS = "providers"
        const val KEY_IMAGE_PROVIDERS = "image_providers"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_FILE_PROCESSING_SERVICES = "file_processing_services"
        const val KEY_WEBDAV_CONFIG = "webdav_config"
        const val KEY_S3_CONFIG = "s3_config"
        const val KEY_D1_CONFIG = "d1_config"
        const val KEY_MODE_INJECTIONS = "mode_injections"
        const val KEY_LOREBOOKS = "lorebooks"
        const val KEY_QUICK_MESSAGES = "quick_messages"

        // ----- DataStore PreferencesKey（仅 bootstrap 阶段使用） -----
        private val K_FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        private val K_CHAT_MODEL = stringPreferencesKey("chat_model")
        private val K_FAST_MODEL = stringPreferencesKey("fast_model")
        private val K_TITLE_MODEL = stringPreferencesKey("title_model")
        private val K_TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        private val K_ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        private val K_SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        private val K_IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        private val K_IMAGE_GENERATION_MODELS = stringPreferencesKey("image_generation_models")
        private val K_TITLE_PROMPT = stringPreferencesKey("title_prompt")
        private val K_TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        private val K_TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        private val K_SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        private val K_OCR_MODEL = stringPreferencesKey("ocr_model")
        private val K_OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        private val K_COMPRESS_MODEL = stringPreferencesKey("compress_model")
        private val K_COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        private val K_PROVIDERS = stringPreferencesKey("providers")
        private val K_IMAGE_PROVIDERS = stringPreferencesKey("image_providers")
        private val K_ASSISTANTS = stringPreferencesKey("assistants")
        private val K_SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        private val K_ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        private val K_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val K_THEME_ID = stringPreferencesKey("theme_id")
        private val K_CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        private val K_DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val K_DISPLAY_SETTING = stringPreferencesKey("display_setting")
        private val K_FILE_COMPRESS_SETTING = stringPreferencesKey("file_compress_setting")
        private val K_SEARCH_SERVICES = stringPreferencesKey("search_services")
        private val K_SEARCH_COMMON = stringPreferencesKey("search_common")
        private val K_SEARCH_SELECTED = intPreferencesKey("search_selected")
        private val K_MCP_SERVERS = stringPreferencesKey("mcp_servers")
        private val K_FILE_PROCESSING_SERVICES = stringPreferencesKey("file_processing_services")
        private val K_WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        private val K_S3_CONFIG = stringPreferencesKey("s3_config")
        private val K_D1_CONFIG = stringPreferencesKey("d1_config")
        private val K_R2_ACCOUNTS = stringPreferencesKey("r2_accounts")
        private val K_R2_PRESIGN_TTL_SECONDS = intPreferencesKey("r2_presign_ttl_seconds")
        private val K_TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        private val K_SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        private val K_ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        private val K_SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")
        private val K_MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        private val K_LOREBOOKS = stringPreferencesKey("lorebooks")
        private val K_QUICK_MESSAGES = stringPreferencesKey("quick_messages")
        private val K_WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        private val K_WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        private val K_WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        private val K_WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        private val K_WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")
        private val K_BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")
        private val K_LAUNCH_COUNT = intPreferencesKey("launch_count")
        private val K_SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // DEEP_MAT_NEWAPI_PROVIDER_ID（与原 PreferencesStore 保持一致）
        private val DEEP_MAT_NEWAPI_PROVIDER_ID = Uuid.parse("7c6b5986-23e6-4c1a-9588-0934dd0d15ad")
    }
}
