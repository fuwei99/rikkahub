package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
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
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.r2.R2AccountConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

// ============================================================
// 领域 JSON bundle —— 顶层字段名严格沿用原 PB Key，
// 以便 AI 看到 JSON 立刻能映射到原 DataStore PreferencesKey。
// ============================================================

/** `assistants` + `assistant_tags` + `select_assistant` 三合一 */
@Serializable
data class AssistantsBundle(
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistant_tags: List<Tag> = emptyList(),
    val select_assistant: Uuid = DEFAULT_ASSISTANT_ID,
)

/**
 * `chat_model` + `fast_model` + `title_model` + `translate_model` + `ocr_model`
 * + `compress_model` + `suggestion_model` + `image_generation_model`
 * + `image_generation_models` + `favorite_models` + `enable_suggestion`
 * + `translate_thinking_budget` 十二合一
 */
@Serializable
data class ModelsBundle(
    val chat_model: Uuid = DEFAULT_AUTO_MODEL_ID,
    val fast_model: Uuid = DEFAULT_AUTO_MODEL_ID,
    val title_model: Uuid? = null,
    val translate_model: Uuid = DEFAULT_AUTO_MODEL_ID,
    val ocr_model: Uuid = Uuid.random(),
    val compress_model: Uuid = DEFAULT_AUTO_MODEL_ID,
    val suggestion_model: Uuid? = null,
    val image_generation_model: Uuid = Uuid.random(),
    val image_generation_models: List<Uuid> = emptyList(),
    val favorite_models: List<Uuid> = emptyList(),
    val enable_suggestion: Boolean = true,
    val translate_thinking_budget: Int = 0,
)

/** `title_prompt` + `translation_prompt` + `suggestion_prompt` + `ocr_prompt` + `compress_prompt` */
@Serializable
data class PromptsBundle(
    val title_prompt: String = DEFAULT_TITLE_PROMPT,
    val translation_prompt: String = DEFAULT_TRANSLATION_PROMPT,
    val suggestion_prompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocr_prompt: String = DEFAULT_OCR_PROMPT,
    val compress_prompt: String = DEFAULT_COMPRESS_PROMPT,
)

/** `search_services` + `search_common` + `search_selected` */
@Serializable
data class SearchServicesBundle(
    val search_services: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val search_common: SearchCommonOptions = SearchCommonOptions(),
    val search_selected: Int = 0,
)

/** `r2_accounts` + `r2_presign_ttl_seconds` */
@Serializable
data class R2Bundle(
    val r2_accounts: List<R2AccountConfig> = emptyList(),
    val r2_presign_ttl_seconds: Long = 86_400L,
)

/** `tts_providers` + `selected_tts_provider` */
@Serializable
data class TTSBundle(
    val tts_providers: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selected_tts_provider: Uuid = DEFAULT_SYSTEM_TTS_ID,
)

/** `asr_providers` + `selected_asr_provider` */
@Serializable
data class ASRBundle(
    val asr_providers: List<ASRProviderSetting> = emptyList(),
    val selected_asr_provider: Uuid? = null,
)

/** `display_setting` + `file_compress_setting` */
@Serializable
data class DisplayBundle(
    val display_setting: DisplaySetting = DisplaySetting(),
    val file_compress_setting: FileCompressSetting = FileCompressSetting(),
)

/** `dynamic_color` + `theme_id` + `custom_themes` + `developer_mode` */
@Serializable
data class UIBundle(
    val dynamic_color: Boolean = true,
    val theme_id: String = "",
    val custom_themes: List<CustomTheme> = emptyList(),
    val developer_mode: Boolean = false,
)

/** `web_server_enabled` + `web_server_port` + `web_server_jwt_enabled` + `web_server_access_password` + `web_server_localhost_only` */
@Serializable
data class WebServerBundle(
    val web_server_enabled: Boolean = false,
    val web_server_port: Int = 8080,
    val web_server_jwt_enabled: Boolean = false,
    val web_server_access_password: String = "",
    val web_server_localhost_only: Boolean = false,
)

/** `backup_reminder_config` + `launch_count` + `sponsor_alert_dismissed_at` */
@Serializable
data class MiscBundle(
    val backup_reminder_config: BackupReminderConfig = BackupReminderConfig(),
    val launch_count: Int = 0,
    val sponsor_alert_dismissed_at: Int = 0,
)
