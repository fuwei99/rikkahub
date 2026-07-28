package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.core.SyncEngine
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.ServiceConfigBundleIO
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val syncEngine: SyncEngine,
    database: AppDatabase,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    /** 云锚点同步（P1）：待推送的 outbox 积压条数（状态展示用） */
    val syncOutboxCount = database.syncOutboxDao().countFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = 0,
    )

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /** R2 账户表增删改（P3）：随 settings 落盘并上云同步 */
    fun updateR2Accounts(accounts: List<me.rerere.rikkahub.data.sync.r2.R2AccountConfig>) {
        updateSettings(settings.value.copy(r2Accounts = accounts))
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(settings.value.webDavConfig.copy())
        recordBackupTime()
        return file
    }

    /**
     * 单独导出设置/配置 (settings.json) 压缩包
     */
    suspend fun exportSettingsOnlyToFile(): File {
        val file = File.createTempFile("rikkahub_settings_", ".zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(file)).use { zipOut ->
            val entry = java.util.zip.ZipEntry("settings.json")
            zipOut.putNextEntry(entry)
            zipOut.write(me.rerere.rikkahub.utils.JsonInstant.encodeToString(settingsStore.settingsFlow.value).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        }
        return file
    }

    /**
     * 导出服务配置 JSON：聊天/生图/搜索/语音/MCP。
     */
    suspend fun exportServiceConfigJsonToFile(): File {
        val file = File.createTempFile("rikkahub_service_config_", ".json")
        file.writeText(ServiceConfigBundleIO.export(settings.value), Charsets.UTF_8)
        return file
    }

    /**
     * 合并导入服务配置 JSON，并按渠道身份与模型 ID/名称去重。
     */
    suspend fun importServiceConfigJson(file: File) {
        val json = file.readText(Charsets.UTF_8)
        settingsStore.update(ServiceConfigBundleIO.importInto(settings.value, json))
    }

    /**
     * 单独导出单个助手及关联的聊天记录数据包
     */
    suspend fun exportAssistantPackageToFile(assistantId: kotlin.uuid.Uuid): File {
        val assistant = settings.value.assistants.find { it.id == assistantId }
            ?: throw IllegalArgumentException("Assistant not found")
        val conversations = conversationRepository.getRecentConversations(assistantId, limit = 1000)
        val file = File.createTempFile("rikkahub_assistant_${assistant.name}_", ".rikka")
        me.rerere.rikkahub.data.sync.exporter.AssistantExporter.exportAssistantPackage(
            assistant = assistant,
            conversations = conversations,
            outputFile = file
        )
        return file
    }

    /**
     * 恢复/合并导入单助手数据包
     */
    suspend fun restoreAssistantPackage(file: File) {
        val pkg = me.rerere.rikkahub.data.sync.exporter.AssistantExporter.importAssistantPackage(file)
        val newAssistantId = kotlin.uuid.Uuid.random()
        val newAssistant = pkg.assistant.copy(
            id = newAssistantId,
            name = "${pkg.assistant.name} (Imported)"
        )

        // 1. 插入新助手至 Settings
        settingsStore.update(
            settings.value.copy(
                assistants = settings.value.assistants + newAssistant
            )
        )

        // 2. 为该助手重构并导入聊天的 Conversation
        pkg.conversations.forEach { conv ->
            val newConvId = kotlin.uuid.Uuid.random()
            val newConv = conv.copy(
                id = newConvId,
                assistantId = newAssistantId
            )
            conversationRepository.insertConversation(newConv)
        }
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(file, settings.value.webDavConfig)
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = settings.value.assistantId,
            providers = settings.value.providers,
            onConversation = { conversation ->
                if (conversationRepository.existsConversationById(conversation.id)) {
                    skippedExistingConversations++
                } else {
                    conversationRepository.insertConversation(conversation)
                    importedConversations++
                }
            }
        )

        val targetAssistantId = settings.value.assistantId
        settingsStore.update(
            settings.value.copy(
                providers = result.providers + settings.value.providers,
                assistants = settings.value.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        )

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "drop ${result.skippedImageParts} images"
        )
        return ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // 云锚点同步（P1）Cloud Sync methods

    val isSyncCircuitBreakerOpen = syncEngine.isCircuitBreakerOpen

    /** 连通性自检（等价 testS3）；失败时抛出真实错误供 UI 展示 */
    suspend fun testCloudSync() = syncEngine.testConnection()

    /** 立即同步一轮：推积压 + 拉差异（手动触发强制复位熔断器） */
    suspend fun cloudSyncNow() = syncEngine.syncCycle(force = true)

    /** 首次全量上推：本地全部会话 + 各 bundle 入队后同步；返回会话数量 */
    suspend fun cloudSeedAndSync(): Int {
        val count = syncEngine.seedLocalData()
        syncEngine.syncCycle(force = true)
        return count
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)
