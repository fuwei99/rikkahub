package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ImageModelCapabilities
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_TEMPLATES
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_TEMPLATE_ID
import me.rerere.rikkahub.data.ai.prompts.legacyCompressTemplateIfCustom
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_INJECT_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.MemoryLogSettings
import me.rerere.rikkahub.data.model.RequestLogSettings
import me.rerere.rikkahub.data.model.MemoryInjectSettings
import me.rerere.rikkahub.data.model.MemorySearchSettings
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.ImageTag
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.clearStaleUnlock
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.data.sync.core.SyncVersionMap
import me.rerere.rikkahub.data.sync.core.stampListChanges
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.r2.R2AccountConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

/** 比较 ProviderSetting 时用于抹平 Composable lambda 引用差异的固定空实现 */
private val EMPTY_COMPOSABLE: @androidx.compose.runtime.Composable () -> Unit = {}
private val DEEP_MAT_NEWAPI_PROVIDER_ID = Uuid.parse("7c6b5986-23e6-4c1a-9588-0934dd0d15ad")

private val settingsStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 设置 DataStore：自定义文件位置（外部专属目录 Android/data/<pkg>/files/datastore/），
 * 避免 DataStore 默认落在内部 filesDir 导致数据不可备份。
 * 注意：不能用 by lazy 顶层扩展（顶层无 this，扩展 receiver 在 lazy 块里不可用），显式传 Context。
 */
private fun createSettingsDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        scope = settingsStoreScope,
        produceFile = { AppPaths.filesDir(context).resolve("datastore/settings.preferences_pb") },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    )

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
    maxOutputImages = maxOutputImages.takeIf { it > 0 } ?: preset.maxOutputImages,
    loraProtocol = loraProtocol.takeUnless { it == me.rerere.ai.provider.WaveSpeedLoraProtocol.NONE }
        ?: preset.loraProtocol,
    maxLoras = maxLoras.takeIf { it > 0 } ?: preset.maxLoras,
    // Never overwrite the user's private token while importing preset capabilities.
    pImageHfApiToken = pImageHfApiToken,
)

class SettingsStore(
    private val context: Context,
    private val scope: AppScope,
    private val database: AppDatabase,
    private val memoryGraphRegistry: MemoryGraphRegistry,
) : KoinComponent {
    private val supervisionGate = SupervisionGate()

    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val FILE_COMPRESS_SETTING = stringPreferencesKey("file_compress_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val IMAGE_GENERATION_MODELS = stringPreferencesKey("image_generation_models")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val MEMORY_MODEL = stringPreferencesKey("memory_model")
        val MEMORY_PROMPT = stringPreferencesKey("memory_prompt")
        val MEMORY_THINKING_BUDGET = intPreferencesKey("memory_thinking_budget")
        val MEMORY_INJECT_MODEL = stringPreferencesKey("memory_inject_model")
        val MEMORY_INJECT_FALLBACK_MODEL = stringPreferencesKey("memory_inject_fallback_model")
        val MEMORY_INJECT_PROMPT = stringPreferencesKey("memory_inject_prompt")
        val MEMORY_INJECT_THINKING_BUDGET = intPreferencesKey("memory_inject_thinking_budget")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val COMPRESS_TEMPLATES = stringPreferencesKey("compress_templates")
        // 注意：不以 DEFAULT_COMPRESS_TEMPLATE_ID 命名，避免与 ai.prompts 包的顶层 Uuid 常量重名遮蔽
        val DEFAULT_COMPRESS_TEMPLATE_ID_KEY = stringPreferencesKey("default_compress_template_id")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 被用户删除的内置渠道墓碑（id -> 删除时间戳），参与云同步，防止默认项复活
        val PROVIDER_TOMBSTONES = stringPreferencesKey("provider_tombstones")

        // 列表类设置的外挂同步版本表（版本号 + 删除墓碑）
        val IMAGE_PROVIDERS_SYNC_META = stringPreferencesKey("image_providers_sync_meta")
        val TTS_PROVIDERS_SYNC_META = stringPreferencesKey("tts_providers_sync_meta")
        val ASR_PROVIDERS_SYNC_META = stringPreferencesKey("asr_providers_sync_meta")
        val SEARCH_SERVICES_SYNC_META = stringPreferencesKey("search_services_sync_meta")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 相册
        val IMAGE_TAGS = stringPreferencesKey("image_tags")
        val GALLERY_FOLDERS = stringPreferencesKey("gallery_folders")
        val OCR_MAX_CONCURRENCY = intPreferencesKey("ocr_max_concurrency")
        val OCR_RATE_PER_MINUTE = intPreferencesKey("ocr_rate_per_minute")
        val OCR_THINKING_BUDGET = intPreferencesKey("ocr_thinking_budget")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // 记忆检索（记忆图 Phase 2）
        val MEMORY_SEARCH_SETTINGS = stringPreferencesKey("memory_search_settings")

        // 记忆调试日志（与记忆检索同级的独立配置块）
        val MEMORY_LOG_SETTINGS = stringPreferencesKey("memory_log_settings")
        val REQUEST_LOG_SETTINGS = stringPreferencesKey("request_log_settings")

        // 注入选择器（方案 2026-08-06：轻量 LLM 挑 id 取代向量检索）
        val MEMORY_INJECT_SETTINGS = stringPreferencesKey("memory_inject_settings")

        // 向量模型服务（记忆图 Phase 2，与生图/搜索/语音服务并列）
        val VECTOR_PROVIDERS = stringPreferencesKey("vector_providers")
        val VECTOR_PROVIDERS_SYNC_META = stringPreferencesKey("vector_providers_sync_meta")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // File Processing
        val FILE_PROCESSING_SERVICES = stringPreferencesKey("file_processing_services")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Image Providers
        val IMAGE_PROVIDERS = stringPreferencesKey("image_providers")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")
        // 外部投递接口（跨平台 Mail MCP 提醒入口）的独立 Bearer key：空 = 接口关闭
        val EXTERNAL_DELIVERY_TOKEN = stringPreferencesKey("external_delivery_token")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 云锚点同步配置
        val D1_CONFIG = stringPreferencesKey("d1_config")
        val R2_ACCOUNTS = stringPreferencesKey("r2_accounts")
        val R2_PRESIGN_TTL_SECONDS = intPreferencesKey("r2_presign_ttl_seconds")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // 专注监督锁
        val SUPERVISION = stringPreferencesKey("supervision")
    }

    private val dataStore = createSettingsDataStore(context)

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            val imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random()
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = imageGenerationModelId,
                imageGenerationModelIds = preferences[IMAGE_GENERATION_MODELS]?.let {
                    JsonInstant.decodeFromString<List<Uuid>>(it)
                }?.ifEmpty { listOf(imageGenerationModelId) } ?: listOf(imageGenerationModelId),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                ocrThinkingBudget = preferences[OCR_THINKING_BUDGET] ?: 0,
                memoryModelId = preferences[MEMORY_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                memoryPrompt = preferences[MEMORY_PROMPT] ?: DEFAULT_MEMORY_PROMPT,
                memoryThinkingBudget = preferences[MEMORY_THINKING_BUDGET] ?: 0,
                memoryInjectModelId = preferences[MEMORY_INJECT_MODEL]?.let { Uuid.parse(it) },
                memoryInjectFallbackModelId = preferences[MEMORY_INJECT_FALLBACK_MODEL]?.let { Uuid.parse(it) },
                memoryInjectPrompt = preferences[MEMORY_INJECT_PROMPT] ?: DEFAULT_MEMORY_INJECT_PROMPT,
                memoryInjectThinkingBudget = preferences[MEMORY_INJECT_THINKING_BUDGET] ?: 0,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                // 模板体系（方案 2026-08-08）：优先读模板列表；老版本无模板时用内置模板，
                // 并把被改过的老 compressPrompt/compressModelId 迁移成「我的模板」，不丢老配置
                compressTemplates = preferences[COMPRESS_TEMPLATES]?.let {
                    runCatching { JsonInstant.decodeFromString<List<CompressTemplate>>(it) }.getOrNull()
                } ?: buildList {
                    addAll(DEFAULT_COMPRESS_TEMPLATES)
                    legacyCompressTemplateIfCustom(
                        compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                        compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) },
                    )?.let { add(it) }
                },
                defaultCompressTemplateId = preferences[DEFAULT_COMPRESS_TEMPLATE_ID_KEY]?.let { Uuid.parse(it) }
                    ?: DEFAULT_COMPRESS_TEMPLATE_ID,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                // withBuiltins: NSFW 是内置标签，老版本存的 JSON 里没有它，
                // 读的时候补上，否则敏感图逻辑没有可挂的标签
                imageTags = ImageTag.withBuiltins(
                    preferences[IMAGE_TAGS]?.let {
                        runCatching { JsonInstant.decodeFromString<List<ImageTag>>(it) }.getOrDefault(emptyList())
                    } ?: emptyList()
                ),
                galleryFolders = preferences[GALLERY_FOLDERS]?.let {
                    runCatching { JsonInstant.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
                } ?: emptyList(),
                ocrMaxConcurrency = preferences[OCR_MAX_CONCURRENCY] ?: 2,
                ocrRatePerMinute = preferences[OCR_RATE_PER_MINUTE] ?: 20,
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                providerTombstones = preferences[PROVIDER_TOMBSTONES]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, Long>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                imageProvidersSyncMeta = preferences[IMAGE_PROVIDERS_SYNC_META]?.let {
                    runCatching { JsonInstant.decodeFromString<SyncVersionMap>(it) }.getOrDefault(SyncVersionMap())
                } ?: SyncVersionMap(),
                ttsProvidersSyncMeta = preferences[TTS_PROVIDERS_SYNC_META]?.let {
                    runCatching { JsonInstant.decodeFromString<SyncVersionMap>(it) }.getOrDefault(SyncVersionMap())
                } ?: SyncVersionMap(),
                asrProvidersSyncMeta = preferences[ASR_PROVIDERS_SYNC_META]?.let {
                    runCatching { JsonInstant.decodeFromString<SyncVersionMap>(it) }.getOrDefault(SyncVersionMap())
                } ?: SyncVersionMap(),
                searchServicesSyncMeta = preferences[SEARCH_SERVICES_SYNC_META]?.let {
                    runCatching { JsonInstant.decodeFromString<SyncVersionMap>(it) }.getOrDefault(SyncVersionMap())
                } ?: SyncVersionMap(),
                vectorProvidersSyncMeta = preferences[VECTOR_PROVIDERS_SYNC_META]?.let {
                    runCatching { JsonInstant.decodeFromString<SyncVersionMap>(it) }.getOrDefault(SyncVersionMap())
                } ?: SyncVersionMap(),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                fileCompressSetting = preferences[FILE_COMPRESS_SETTING]?.let {
                    JsonInstant.decodeFromString<FileCompressSetting>(it)
                } ?: run {
                    val display = JsonInstant.decodeFromString<DisplaySetting>(preferences[DISPLAY_SETTING] ?: "{}")
                    FileCompressSetting(
                        chatImageJpegQuality = display.imageCompressJpegQuality,
                        chatImageSkipBytes = display.imageCompressSkipBytes
                    )
                },
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                fileProcessingServices = preferences[FILE_PROCESSING_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: defaultFileProcessingServices(JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}")),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                d1Config = preferences[D1_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: D1Config(),
                r2Accounts = preferences[R2_ACCOUNTS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                r2PresignTtlSeconds = (preferences[R2_PRESIGN_TTL_SECONDS] ?: 86_400).toLong(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                imageProviders = preferences[IMAGE_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                vectorProviders = preferences[VECTOR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                externalDeliveryToken = preferences[EXTERNAL_DELIVERY_TOKEN] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                memorySearch = preferences[MEMORY_SEARCH_SETTINGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: MemorySearchSettings(),
                memoryLog = preferences[MEMORY_LOG_SETTINGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: MemoryLogSettings(),
                requestLog = preferences[REQUEST_LOG_SETTINGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: RequestLogSettings(),
                memoryInject = preferences[MEMORY_INJECT_SETTINGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: MemoryInjectSettings(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                supervision = preferences[SUPERVISION]?.let {
                    runCatching { JsonInstant.decodeFromString<SupervisionSettings>(it) }.getOrNull()
                } ?: SupervisionSettings(),
            )
        }
        .map {
            val tombstones = it.providerTombstones
            var providers = it.providers.ifEmpty {
                // 首次安装才整体播种；已有墓碑说明用户动过手，不能拿默认列表覆盖
                if (tombstones.isEmpty()) DEFAULT_PROVIDERS else emptyList()
            }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                // 墓碑内的内置渠道不再补种，否则用户删了下一帧就复活
                if (defaultProvider.id.toString() in tombstones) return@forEach
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
            // 默认助手只作为“完全没有助手时”的占位；用户已有任意助手时，不再强行补回默认助手。
            // 但内置 Agents 助手（所有 subagent 会话的宿主容器）必须按 id 差集补齐：
            // ifEmpty 只在全空时生效，老用户列表非空 → 永远拿不到新内置助手，agent 会话就没宿主了。
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            if (assistants.none { assistant -> assistant.id == AGENTS_ASSISTANT_ID }) {
                assistants.add(AGENTS_ASSISTANT)
            }
            val ttsProviders = it.ttsProviders.ifEmpty {
                // 已有墓碑说明用户主动删过，不能拿默认列表整体覆盖
                if (it.ttsProvidersSyncMeta.tombstones.isEmpty()) DEFAULT_TTS_PROVIDERS else emptyList()
            }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                // 墓碑内的默认项不再补种，否则用户删了下一帧就复活
                if (defaultTTSProvider.id.toString() in it.ttsProvidersSyncMeta.tombstones) return@forEach
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            val imageProviders = it.imageProviders.ifEmpty {
                if (it.imageProvidersSyncMeta.tombstones.isEmpty()) DEFAULT_IMAGE_PROVIDERS else emptyList()
            }.toMutableList()
            DEFAULT_IMAGE_PROVIDERS.forEach { defaultImageProvider ->
                if (defaultImageProvider.id.toString() in it.imageProvidersSyncMeta.tombstones) return@forEach
                if (imageProviders.none { provider -> provider.id == defaultImageProvider.id }) {
                    imageProviders.add(defaultImageProvider.copyProvider())
                }
            }
            val vectorProviders = it.vectorProviders.ifEmpty {
                if (it.vectorProvidersSyncMeta.tombstones.isEmpty()) DEFAULT_VECTOR_PROVIDERS else emptyList()
            }.toMutableList()
            DEFAULT_VECTOR_PROVIDERS.forEach { defaultVectorProvider ->
                if (defaultVectorProvider.id.toString() in it.vectorProvidersSyncMeta.tombstones) return@forEach
                if (vectorProviders.none { provider -> provider.id == defaultVectorProvider.id }) {
                    vectorProviders.add(defaultVectorProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
                imageProviders = imageProviders.map { it.withMissingPresetImageMetadata() },
                vectorProviders = vectorProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            // 记忆图注册表在 Room 中，不随 settings 一起存储。读取设置时顺手剪枝，
            // 避免删除图后 Assistant JSON 永远挂着僵尸 binding；注册表读取失败时保守保留，
            // 交给 Resolver 的运行时过滤兜底，不能因为一次临时 DB 错误把用户配置清空。
            val validMemoryGraphIds = runCatching {
                memoryGraphRegistry.list().map { it.id }.toSet()
            }.getOrNull()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
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
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的记忆图 ID（图注册表不在 settings JSON 内）
                        memoryGraphBindings = assistant.memoryGraphBindings.filter { binding ->
                            validMemoryGraphIds == null || binding.graphId in validMemoryGraphIds
                        },
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                imageProviders = settings.imageProviders.distinctBy { it.id }.map { provider ->
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
                                    keyStrategy = provider.keyStrategy,
                                    retryCount = provider.retryCount,
                                    retryIntervalSec = provider.retryIntervalSec,
                                    closeOnCodes = provider.closeOnCodes,
                                    disabledTokens = provider.disabledTokens,
                                    tokenNames = provider.tokenNames,
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
                            // Upgrade the old built-in Ark endpoint to the Coding Plan endpoint.
                            // Any explicitly customized endpoint is left untouched.
                            baseUrl = if (provider.baseUrl == "https://ark.cn-beijing.volces.com/api/v3") {
                                "https://ark.cn-beijing.volces.com/api/plan/v3"
                            } else {
                                provider.baseUrl
                            }
                        )
                        is ImageProviderSetting.Wavespeed -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                        is ImageProviderSetting.TokenRhythm -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                        is ImageProviderSetting.ComfyUI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                vectorProviders = settings.vectorProviders.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is VectorProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                imageGenerationModelIds = settings.imageGenerationModelIds.filter { uuid ->
                    settings.imageProviders.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        val current = settingsFlow.value
        // 专注监督闸门：监督时段内所有写入都要经过「只许加强」的清洗
        // （见 PLAN_SUPERVISION_LOCK §3）。SyncApplyGate.applyingRemote 表示
        // 这次写入来自云同步下拉，需要按 strengthenWith 合并。
        val isSyncPull = SyncApplyGate.applyingRemote
        val gated = if (!current.init) {
            supervisionGate.enforceDuringLock(current, settings, isSyncPull = isSyncPull)
        } else settings
        // 顺手清掉「上个时段批准、早已失效」的解锁记录：留着会让 UI 永远显示
        // "本时段已解锁"，并且守门员解锁工具永久不再挂载（2026-08-17 修复）。
        val guarded = gated.copy(supervision = gated.supervision.clearStaleUnlock())
        val nextSettings = stampChangedListSettings(
            current,
            stampChangedProviders(
                current,
                stampChangedMcpServers(current, guarded),
            ),
        ).let { stamped ->
            // 用户改动监督配置时自动盖 updatedAt 时间戳（云同步 LWW 用）。
            // 不覆盖已经是更晚值的情况（同步下来或 Gate strengthen 后可能更大）。
            if (stamped.supervision != current.supervision &&
                stamped.supervision.updatedAt < System.currentTimeMillis()
            ) {
                stamped.copy(supervision = stamped.supervision.copy(updatedAt = System.currentTimeMillis()))
            } else stamped
        }
        settingsFlow.value = nextSettings
        dataStore.edit { preferences ->
            val settings = nextSettings
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            preferences[FILE_COMPRESS_SETTING] = JsonInstant.encodeToString(settings.fileCompressSetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[IMAGE_GENERATION_MODELS] = JsonInstant.encodeToString(settings.imageGenerationModelIds)
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[OCR_THINKING_BUDGET] = settings.ocrThinkingBudget
            preferences[MEMORY_MODEL] = settings.memoryModelId.toString()
            preferences[MEMORY_PROMPT] = settings.memoryPrompt
            preferences[MEMORY_THINKING_BUDGET] = settings.memoryThinkingBudget
            settings.memoryInjectModelId?.let {
                preferences[MEMORY_INJECT_MODEL] = it.toString()
            } ?: preferences.remove(MEMORY_INJECT_MODEL)
            settings.memoryInjectFallbackModelId?.let {
                preferences[MEMORY_INJECT_FALLBACK_MODEL] = it.toString()
            } ?: preferences.remove(MEMORY_INJECT_FALLBACK_MODEL)
            preferences[MEMORY_INJECT_PROMPT] = settings.memoryInjectPrompt
            preferences[MEMORY_INJECT_THINKING_BUDGET] = settings.memoryInjectThinkingBudget
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt
            preferences[COMPRESS_TEMPLATES] = JsonInstant.encodeToString(settings.compressTemplates)
            settings.defaultCompressTemplateId?.let {
                preferences[DEFAULT_COMPRESS_TEMPLATE_ID_KEY] = it.toString()
            } ?: preferences.remove(DEFAULT_COMPRESS_TEMPLATE_ID_KEY)

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
            preferences[PROVIDER_TOMBSTONES] = JsonInstant.encodeToString(settings.providerTombstones)
            preferences[IMAGE_PROVIDERS_SYNC_META] = JsonInstant.encodeToString(settings.imageProvidersSyncMeta)
            preferences[TTS_PROVIDERS_SYNC_META] = JsonInstant.encodeToString(settings.ttsProvidersSyncMeta)
            preferences[ASR_PROVIDERS_SYNC_META] = JsonInstant.encodeToString(settings.asrProvidersSyncMeta)
            preferences[SEARCH_SERVICES_SYNC_META] = JsonInstant.encodeToString(settings.searchServicesSyncMeta)
            preferences[IMAGE_PROVIDERS] = JsonInstant.encodeToString(settings.imageProviders)
            preferences[VECTOR_PROVIDERS_SYNC_META] = JsonInstant.encodeToString(settings.vectorProvidersSyncMeta)
            preferences[VECTOR_PROVIDERS] = JsonInstant.encodeToString(settings.vectorProviders)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[IMAGE_TAGS] = JsonInstant.encodeToString(settings.imageTags)
            preferences[GALLERY_FOLDERS] = JsonInstant.encodeToString(settings.galleryFolders.distinct())
            preferences[OCR_MAX_CONCURRENCY] = settings.ocrMaxConcurrency.coerceIn(1, 8)
            preferences[OCR_RATE_PER_MINUTE] = settings.ocrRatePerMinute.coerceIn(1, 600)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)
            preferences[MEMORY_SEARCH_SETTINGS] = JsonInstant.encodeToString(settings.memorySearch)
            preferences[MEMORY_LOG_SETTINGS] = JsonInstant.encodeToString(settings.memoryLog.sanitized())
            preferences[REQUEST_LOG_SETTINGS] = JsonInstant.encodeToString(settings.requestLog.sanitized())
            preferences[MEMORY_INJECT_SETTINGS] = JsonInstant.encodeToString(settings.memoryInject.sanitized())

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[FILE_PROCESSING_SERVICES] = JsonInstant.encodeToString(settings.fileProcessingServices)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[D1_CONFIG] = JsonInstant.encodeToString(settings.d1Config)
            preferences[R2_ACCOUNTS] = JsonInstant.encodeToString(settings.r2Accounts)
            preferences[R2_PRESIGN_TTL_SECONDS] = settings.r2PresignTtlSeconds.coerceIn(900L, 90L * 24L * 60L * 60L).toInt()
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[EXTERNAL_DELIVERY_TOKEN] = settings.externalDeliveryToken
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[SUPERVISION] = JsonInstant.encodeToString(settings.supervision)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
        }

        // 云锚点同步写钩（P1）：应用云端变更回写 settings 时由 SyncApplyGate 抑制；
        // displaySetting 走独立 bundle（settings.display），仅在设备开关开启时入队
        if (!SyncApplyGate.applyingRemote) {
            runCatching {
                val now = System.currentTimeMillis()
                val outbox = database.syncOutboxDao()
                outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, "settings")
                outbox.insert(
                    SyncOutboxEntity(
                        kind = SyncOutboxEntity.KIND_BUNDLE,
                        refKey = "settings",
                        op = SyncOutboxEntity.OP_UPSERT,
                        createdAt = now,
                    )
                )
                if (SyncLocalPrefs.isDisplaySyncEnabled(context)) {
                    outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, "settings.display")
                    outbox.insert(
                        SyncOutboxEntity(
                            kind = SyncOutboxEntity.KIND_BUNDLE,
                            refKey = "settings.display",
                            op = SyncOutboxEntity.OP_UPSERT,
                            createdAt = now,
                        )
                    )
                }
            }.onFailure { Log.w(TAG, "enqueue settings sync outbox failed", it) }
        }
    }


    /**
     * 渠道变更打戳 + 删除墓碑：
     * - 内容变了但调用方没动 updatedAt → 盖当前时间，让跨设备 LWW 能分出新旧
     * - 新增的渠道（旧列表里没有、且 updatedAt=0）也要打戳，否则永远输给云端
     * - 消失的渠道写入墓碑；重新出现则消墓碑（重建同名内置渠道的场景）
     */
    /**
     * imageProviders / ttsProviders / asrProviders / searchServices 的统一打戳。
     * 这四者共 40 个 sealed 子类，版本号走外挂 SyncVersionMap 而不内嵌到每个 data class。
     */
    private fun stampChangedListSettings(old: Settings, next: Settings): Settings {
        if (SyncApplyGate.applyingRemote) return next
        // dummy 初始态的列表是默认值占位，不能拿它算删除差集
        if (old.init) return next
        val now = System.currentTimeMillis()
        return next.copy(
            imageProvidersSyncMeta = stampListChanges(
                old = old.imageProviders,
                next = next.imageProviders,
                meta = next.imageProvidersSyncMeta,
                now = now,
                // 读流会做火山 baseUrl 升级 / 预置模型元数据回填，且 builtIn/description
                // 是不参与同步的 @Transient；不抹平这些会每轮误判变更并无限打戳
                normalize = {
                    it.copyProvider(
                        builtIn = false,
                        description = EMPTY_COMPOSABLE,
                        shortDescription = EMPTY_COMPOSABLE,
                    )
                },
            ) { it.id.toString() },
            ttsProvidersSyncMeta = stampListChanges(
                old = old.ttsProviders,
                next = next.ttsProviders,
                meta = next.ttsProvidersSyncMeta,
                now = now,
            ) { it.id.toString() },
            asrProvidersSyncMeta = stampListChanges(
                old = old.asrProviders,
                next = next.asrProviders,
                meta = next.asrProvidersSyncMeta,
                now = now,
            ) { it.id.toString() },
            searchServicesSyncMeta = stampListChanges(
                old = old.searchServices,
                next = next.searchServices,
                meta = next.searchServicesSyncMeta,
                now = now,
            ) { it.id.toString() },
            vectorProvidersSyncMeta = stampListChanges(
                old = old.vectorProviders,
                next = next.vectorProviders,
                meta = next.vectorProvidersSyncMeta,
                now = now,
                // 与 imageProviders 同理：builtIn/description 是 @Transient，不参与同步，不抹平会无限打戳
                normalize = {
                    it.copyProvider(
                        builtIn = false,
                        description = EMPTY_COMPOSABLE,
                        shortDescription = EMPTY_COMPOSABLE,
                    )
                },
            ) { it.id.toString() },
        )
    }

    private fun stampChangedProviders(old: Settings, next: Settings): Settings {
        if (SyncApplyGate.applyingRemote) return next
        // dummy 初始态的 providers 是 DEFAULT_PROVIDERS 占位，不能拿它算删除差集
        if (old.init) return next
        val now = System.currentTimeMillis()
        val oldById = old.providers.associateBy { it.id }
        val stamped = next.providers.map { provider ->
            val oldProvider = oldById[provider.id]
            when {
                oldProvider == null && provider.updatedAt == 0L -> provider.copyProvider(updatedAt = now)
                oldProvider != null &&
                    provider.differsIgnoringRuntimeFields(oldProvider) &&
                    provider.updatedAt == oldProvider.updatedAt ->
                    provider.copyProvider(updatedAt = now)

                else -> provider
            }
        }
        val nextIds = stamped.mapTo(mutableSetOf()) { it.id.toString() }
        val removedIds = old.providers.map { it.id.toString() }.filter { it !in nextIds }
        val tombstones = next.providerTombstones.toMutableMap()
        removedIds.forEach { tombstones[it] = now }
        // 渠道又回来了（用户手动重建）：清墓碑，否则下一次读流又把它滤掉
        nextIds.forEach { tombstones.remove(it) }
        return next.copy(providers = stamped, providerTombstones = tombstones)
    }

    /**
     * 比较时忽略 @Transient 运行时字段。description / shortDescription 是 Composable lambda，
     * data class 的 equals 比的是引用，不归一会把“完全没改”误判为变更并无限打戳。
     */
    private fun ProviderSetting.differsIgnoringRuntimeFields(other: ProviderSetting): Boolean {
        val a = this.copyProvider(
            builtIn = false,
            description = EMPTY_COMPOSABLE,
            shortDescription = EMPTY_COMPOSABLE,
            updatedAt = 0L,
        )
        val b = other.copyProvider(
            builtIn = false,
            description = EMPTY_COMPOSABLE,
            shortDescription = EMPTY_COMPOSABLE,
            updatedAt = 0L,
        )
        return a != b
    }

    private fun stampChangedMcpServers(old: Settings, next: Settings): Settings {
        if (SyncApplyGate.applyingRemote) return next
        val oldById = old.mcpServers.associateBy { it.id }
        val now = System.currentTimeMillis()
        return next.copy(
            mcpServers = next.mcpServers.map { server ->
                val oldServer = oldById[server.id]
                if (oldServer != null && server != oldServer && server.commonOptions.updatedAt == oldServer.commonOptions.updatedAt) {
                    server.clone(commonOptions = server.commonOptions.copy(updatedAt = now))
                } else {
                    server
                }
            }
        )
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    /**
     * 监督管理工具（`supervision_admin`）写监督配置的专用入口。
     *
     * 加锁类操作走普通 [update]（Gate 的并集加严本来就放行加锁）；
     * 解锁类操作必须 [bypassGate]，否则 Gate 的「只许加严」会把移除原地回滚，
     * 表现为「工具说成功了，锁却还在」（PLAN_SUPERVISION_ADMIN_TOOL §2.1 / §6 洞④）。
     *
     * bypass 用协程上下文元素传递，能穿过 [update] 内部的线程切换。
     */
    suspend fun updateSupervisionByAdmin(
        supervision: SupervisionSettings,
        bypassGate: Boolean,
    ) {
        val next = settingsFlow.value.copy(supervision = supervision)
        if (bypassGate) {
            withContext(SupervisionGate.AdminBypass.element()) { update(next) }
        } else {
            update(next)
        }
    }

    /** 设置/轮换外部投递接口的 Bearer key（空 = 关闭外部投递入口） */
    suspend fun updateExternalDeliveryToken(token: String) {
        update { it.copy(externalDeliveryToken = token.trim()) }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        // 专注监督：不允许把当前助手切到非白名单
        val current = settingsFlow.value
        val lockedIds = current.supervision.allowedAssistantIds
        if (current.supervision.isActiveNow() &&
            lockedIds.isNotEmpty() &&
            assistantId !in lockedIds
        ) {
            Log.w(TAG, "updateAssistant blocked by supervision: $assistantId not in whitelist")
            return
        }
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId, updatedAt = System.currentTimeMillis())
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel, updatedAt = System.currentTimeMillis())
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled, updatedAt = System.currentTimeMillis())
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers, updatedAt = System.currentTimeMillis())
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                            updatedAt = System.currentTimeMillis(),
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val fileCompressSetting: FileCompressSetting = FileCompressSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val imageGenerationModelIds: List<Uuid> = emptyList(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    /** OCR 思考预算（budget tokens）：0 = 关闭，越大思考越深。与翻译同款节点滑块，随 D1 settings 整包同步 */
    val ocrThinkingBudget: Int = 0,
    /** 记忆总结模型（记忆图 Phase 3 自动抽取用，配置先落地） */
    val memoryModelId: Uuid = Uuid.random(),
    val memoryPrompt: String = DEFAULT_MEMORY_PROMPT,
    /** 记忆总结思考预算（budget tokens）：0 = 关闭，越大思考越深。与 OCR/翻译同款节点滑块，随 D1 settings 整包同步 */
    val memoryThinkingBudget: Int = 0,
    /** 注入选择器模型（方案 2026-08-06）：未配置时回落记忆总结模型 */
    val memoryInjectModelId: Uuid? = null,
    /** 注入选择器备用模型（2026-08-07）：主模型报错/超时/空回复时切换；两个都失败回落关键词/语义召回 */
    val memoryInjectFallbackModelId: Uuid? = null,
    val memoryInjectPrompt: String = DEFAULT_MEMORY_INJECT_PROMPT,
    /** 注入选择器思考预算（budget tokens）：0 = 关闭 */
    val memoryInjectThinkingBudget: Int = 0,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    /** 压缩模板列表（方案 2026-08-08）：内置 4 模板 + 用户自建；全局默认模板见 [defaultCompressTemplateId] */
    val compressTemplates: List<CompressTemplate> = DEFAULT_COMPRESS_TEMPLATES,
    val defaultCompressTemplateId: Uuid? = DEFAULT_COMPRESS_TEMPLATE_ID,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    /** 已被用户删除的内置渠道墓碑：id.toString() -> 删除时间戳（epoch millis） */
    val providerTombstones: Map<String, Long> = emptyMap(),
    /** 下列四项为外挂同步版本表，避免往 40 个 sealed 子类里逐个塞 updatedAt */
    val imageProvidersSyncMeta: SyncVersionMap = SyncVersionMap(),
    val ttsProvidersSyncMeta: SyncVersionMap = SyncVersionMap(),
    val asrProvidersSyncMeta: SyncVersionMap = SyncVersionMap(),
    val searchServicesSyncMeta: SyncVersionMap = SyncVersionMap(),
    val vectorProvidersSyncMeta: SyncVersionMap = SyncVersionMap(),
    val imageProviders: List<ImageProviderSetting> = DEFAULT_IMAGE_PROVIDERS,
    /** 向量模型服务（记忆图 Phase 2）：OpenAI 兼容 embedding 渠道，与生图/搜索/语音服务并列 */
    val vectorProviders: List<VectorProviderSetting> = DEFAULT_VECTOR_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    /** 相册标签表（含内置 NSFW）。OCR 只能从这里挑，不允许自创 */
    val imageTags: List<ImageTag> = ImageTag.SEED_TAGS,
    /** 相册自定义虚拟分类名称；图片归属仍由 asset_label_ref 管理 */
    val galleryFolders: List<String> = emptyList(),
    /** 批量 OCR 并发上限：视觉模型普遍限流，默认放 2 条 */
    val ocrMaxConcurrency: Int = 2,
    /** 批量 OCR 每分钟请求上限（令牌桶） */
    val ocrRatePerMinute: Int = 20,
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    /** 记忆检索（记忆图 Phase 2）：embedding 渠道/维度 + 检索开关，随 D1 settings 整包同步 */
    val memorySearch: MemorySearchSettings = MemorySearchSettings(),
    /** 记忆调试日志：开关 + 清理策略（与记忆检索同级，不随 D1 同步——日志文件是设备本地的） */
    val memoryLog: MemoryLogSettings = MemoryLogSettings(),
    /** 请求日志：实际发给 LLM 的 header/payload/response 完整落盘（设备本地，不随 D1 同步） */
    val requestLog: RequestLogSettings = RequestLogSettings(),
    /** 注入选择器（方案 2026-08-06）：LLM 挑 id 取代向量检索，随 D1 settings 整包同步 */
    val memoryInject: MemoryInjectSettings = MemoryInjectSettings(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val fileProcessingServices: List<FileProcessingServiceOptions> = listOf(FileProcessingServiceOptions.MinerU()),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // 云锚点同步（D1）配置：含 API Token，属设备机密；P1 上推 settings 前必须剔除
    val d1Config: D1Config = D1Config(),
    // R2 账户表（P3）：含密钥并随 settings 同步；否则其他设备无法预签名读取 r2:// 对象
    val r2Accounts: List<R2AccountConfig> = emptyList(),
    // R2 临时读取链接有效期：参与设置同步，默认 24 小时
    val r2PresignTtlSeconds: Long = 86_400L,
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    /** 外部投递接口（跨平台 Mail MCP）的 Bearer key；空 = 接口关闭 */
    val externalDeliveryToken: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    /**
     * 人类总闸（收敛设计 §7.4）：允许模板自行授予高危权限。
     * 关（默认）：模板里 approvalMode=auto / interruptRight / 非 app 通知通道被降级为保守值，
     * spawn 结果与 UI 明示降级；开：模板说什么就是什么，人类为此负责。
     */
    val subagentMasterGate: Boolean = false,
    /**
     * 通信设置（多 Agent 通信内核期三/期四，2026-08-08）：攒批窗口 / 抢占冷却 / 上限等
     * 数字全部可配，避免硬编码（收敛设计 §5.3 护栏 + 2026-08-08 拍板「搞个设置好放这些数字」）。
     */
    val communication: CommunicationSettings = CommunicationSettings(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    /**
     * 专注监督锁（方案 PLAN_SUPERVISION_LOCK）。
     * 跨设备同步：监督时段内多设备一起锁，只许加强不许减弱。
     */
    val supervision: SupervisionSettings = SupervisionSettings(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

/**
 * 通信参数（随 D1 settings 整包同步：多端行为一致）。
 *
 * - [mailBatchWindowSeconds]：await/join 等第一个结果到达后，窗口内到的其他结果合并一起批返回
 *   （2026-08-08 拍板「多个邮箱一起来最好合并」）；0 = 关闭合并，首封即返回。
 * - [callMergeWindowSeconds]：电话并线窗口——同一目标一次抢占的合并窗口，窗口内到达的其他
 *   CALL 合并进同一轮（会议电话，§5.2），不降级不排队。
 * - [preemptCooldownSeconds]：同一目标两次抢占的最小间隔，防 A↔B 互掐乒乓（§5.3）。
 * - [maxPreemptsPerRound]：单轮抢占次数上限，防某个 agent 反复掐目标（每轮生成结束后重置）。
 * - [maxUnreadPerTarget]：单目标未读上限，超出后新信合并进最后一封（原
 *   AgentLimits.MAX_UNREAD_PER_TARGET 硬编码迁出，收敛设计 §10）。
 * - [defaultAwaitTimeoutSeconds]：await/join 未显式给 timeout 时的默认等待时长。
 */
@Serializable
data class CommunicationSettings(
    val mailBatchWindowSeconds: Int = 3,
    val callMergeWindowSeconds: Int = 3,
    val preemptCooldownSeconds: Int = 60,
    val maxPreemptsPerRound: Int = 2,
    val maxUnreadPerTarget: Int = 20,
    val defaultAwaitTimeoutSeconds: Int = 60,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class FileCompressSetting(
    // 1. 发送图片 (聊天图片附件) 压缩
    val chatImageJpegQuality: Int = 85,
    val chatImageSkipBytes: Long = 1024 * 1024L,
    val chatImageMaxEdge: Int = 2560,

    // 2. 生图反给 AI 的预览图压缩
    val llmPreviewJpegQuality: Int = 68,
    val llmPreviewSkipBytes: Long = 512 * 1024L,
    val llmPreviewMaxEdge: Int = 1280,

    // 3. 文件管理手动压缩按钮
    val manualCompressJpegQuality: Int = 68,
    val manualCompressSkipBytes: Long = 512 * 1024L,
    val manualCompressMaxEdge: Int = 1280,
)

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val imageCompressJpegQuality: Int = 85,
    val imageCompressSkipBytes: Long = 1024 * 1024L,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    /**
     * ask_user 等待回答的超时（分钟，0 = 永久等待）。
     *
     * ask_user 会把生成停在 Pending 上等人回答，人没看见就是永久卡死。
     * 到点后自动以「超时未回答」结掉，让模型自行决策并说明假设。
     */
    val askUserTimeoutMinutes: Int = 5,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val useMineruDocumentParser: Boolean = false,
    val mineruDocumentOcr: Boolean = true,
    val mineruDocumentLanguage: String = "ch",
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val markdownRenderCacheSize: Int = 20,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)


@Serializable
sealed interface FileProcessingServiceOptions {
    val id: Uuid
    val displayName: String
    val enabled: Boolean

    @Serializable
    @SerialName("mineru")
    data class MinerU(
        override val id: Uuid = DEFAULT_MINERU_FILE_PROCESSING_ID,
        override val displayName: String = "MinerU",
        override val enabled: Boolean = false,
        val baseUrl: String = "https://mineru.net/api/v1/agent",
        val ocr: Boolean = true,
        val language: String = "ch",
        val enableTable: Boolean = true,
        val enableFormula: Boolean = true,
    ) : FileProcessingServiceOptions
}

val DEFAULT_MINERU_FILE_PROCESSING_ID: Uuid = Uuid.parse("8b0d8469-6a6a-4baf-a5dd-7a93d5b96b63")

fun defaultFileProcessingServices(displaySetting: DisplaySetting = DisplaySetting()): List<FileProcessingServiceOptions> = listOf(
    FileProcessingServiceOptions.MinerU(
        enabled = displaySetting.useMineruDocumentParser,
        ocr = displaySetting.mineruDocumentOcr,
        language = displaySetting.mineruDocumentLanguage,
    )
)

fun Settings.selectedMinerUFileProcessingService(): FileProcessingServiceOptions.MinerU? =
    fileProcessingServices.filterIsInstance<FileProcessingServiceOptions.MinerU>().firstOrNull { it.enabled }

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { id -> this.providers.findModelById(id) ?: this.imageProviders.findImageModelById(id) }
        ?: fallback?.let { id -> this.providers.findModelById(id) ?: this.imageProviders.findImageModelById(id) }
}

fun List<ImageProviderSetting>.findImageModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

fun Model.findImageProvider(providers: List<ImageProviderSetting>): ImageProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

/**
 * 内置 `Agents` 助手：所有 subagent 会话的宿主容器（方案 2026-08-07「对话即 Agent」§4.1）。
 *
 * - 它自己不用来聊天，抽屉里独立一栏，每个父对话在其下有一个 folder；
 * - 人格不写死在这里，而是 spawn 时把模板 prompt 写进 `Conversation.customSystemPrompt`
 *   （靠 `allowConversationSystemPrompt = true` 生效）；
 * - `workspaceId = null`：助手是全局单例，一个固定 workspaceId 无法同时代表多个父对话，
 *   子对话的 workspace 身份走 ChatService 的 per-conversation 覆盖；
 * - 不可删除（见 AssistantVM.removeAssistant 的 DEFAULT_ASSISTANTS_IDS 保护）。
 */
val AGENTS_ASSISTANT_ID = Uuid.parse("b3f1d6a2-5c47-4e8b-9a30-7d21e5c0af11")

internal val AGENTS_ASSISTANT = Assistant(
    id = AGENTS_ASSISTANT_ID,
    name = "Agents",
    systemPrompt = "",
    allowConversationSystemPrompt = true,
    enableMemory = false,
    enableRecentChatsReference = false,
    localTools = emptyList(),
    workspaceId = null,
)
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id } + AGENTS_ASSISTANT_ID

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
