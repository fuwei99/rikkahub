package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ImageModelCapabilities
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
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
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.r2.R2AccountConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"
private val DEEP_MAT_NEWAPI_PROVIDER_ID = Uuid.parse("7c6b5986-23e6-4c1a-9588-0934dd0d15ad")

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

class SettingsStore(
    private val context: Context,
    private val scope: AppScope,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
) : KoinComponent {
    companion object {
        // DataStore PreferencesKey 仅供 settingsRepository.bootstrapFromDataStore() 使用
        // 本类不再依赖 DataStore，全部以 [SettingsRepository] 为持久化层
        @Suppress("unused")
        val VERSION = intPreferencesKey("data_version")
        @Suppress("unused")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        @Suppress("unused")
        val THEME_ID = stringPreferencesKey("theme_id")
        @Suppress("unused")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        @Suppress("unused")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        @Suppress("unused")
        val FILE_COMPRESS_SETTING = stringPreferencesKey("file_compress_setting")
        @Suppress("unused")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        @Suppress("unused")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        @Suppress("unused")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        @Suppress("unused")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        @Suppress("unused")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        @Suppress("unused")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        @Suppress("unused")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        @Suppress("unused")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        @Suppress("unused")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        @Suppress("unused")
        val IMAGE_GENERATION_MODELS = stringPreferencesKey("image_generation_models")
        @Suppress("unused")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        @Suppress("unused")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        @Suppress("unused")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        @Suppress("unused")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        @Suppress("unused")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        @Suppress("unused")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        @Suppress("unused")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        @Suppress("unused")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        @Suppress("unused")
        val PROVIDERS = stringPreferencesKey("providers")
        @Suppress("unused")
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        @Suppress("unused")
        val ASSISTANTS = stringPreferencesKey("assistants")
        @Suppress("unused")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        @Suppress("unused")
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        @Suppress("unused")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        @Suppress("unused")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        @Suppress("unused")
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        @Suppress("unused")
        val FILE_PROCESSING_SERVICES = stringPreferencesKey("file_processing_services")
        @Suppress("unused")
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        @Suppress("unused")
        val S3_CONFIG = stringPreferencesKey("s3_config")
        @Suppress("unused")
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        @Suppress("unused")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        @Suppress("unused")
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        @Suppress("unused")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")
        @Suppress("unused")
        val IMAGE_PROVIDERS = stringPreferencesKey("image_providers")
        @Suppress("unused")
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        @Suppress("unused")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        @Suppress("unused")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        @Suppress("unused")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        @Suppress("unused")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")
        @Suppress("unused")
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        @Suppress("unused")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        @Suppress("unused")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")
        @Suppress("unused")
        val D1_CONFIG = stringPreferencesKey("d1_config")
        @Suppress("unused")
        val R2_ACCOUNTS = stringPreferencesKey("r2_accounts")
        @Suppress("unused")
        val R2_PRESIGN_TTL_SECONDS = intPreferencesKey("r2_presign_ttl_seconds")
        @Suppress("unused")
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")
        @Suppress("unused")
        val LAUNCH_COUNT = intPreferencesKey("launch_count")
        @Suppress("unused")
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
    }

    // ============================================================
    //  源数据不再是 DataStore，而是 SettingsRepository 的内存真源
    //  清洗链（补默认 / 去重 / 过滤无效引用）依然在 SettingsRepository.postProcess() 中负责
    // ============================================================

    val settingsFlowRaw = settingsRepository.settings
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
        val nextSettings = stampChangedMcpServers(settingsFlow.value, settings)
        settingsFlow.value = nextSettings
        // 委托 SettingsRepository 按领域 diff 写盘
        settingsRepository.persistAll(nextSettings)

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

    suspend fun updateAssistant(assistantId: Uuid) {
        // 只更新 selected_assistant，避免走完整 update 路径
        val current = settingsFlow.value
        settingsRepository.saveAssistants(
            assistants = current.assistants,
            tags = current.assistantTags,
            selectedId = assistantId,
        )
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
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val imageProviders: List<ImageProviderSetting> = DEFAULT_IMAGE_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
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
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

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

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
