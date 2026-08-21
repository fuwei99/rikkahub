package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val hiddenLockedConversationIds = combine(
        settingsStore.settingsFlow,
        tickerFlow(SUPERVISION_TICK_MS),
    ) { settings, _ ->
        val supervision = settings.supervision
        if (supervision.isActiveNow()) supervision.lockedConversationIds else emptySet()
    }

    val conversations = combine(
        assistant.flatMapLatest { assistant ->
            conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
        },
        hiddenLockedConversationIds,
    ) { conversations, hidden ->
        conversations.filterNot { it.id in hidden }
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 删除失败原因（受保护的定时任务会话）；UI 据此弹提示。 */
    private val _deleteError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val deleteError: kotlinx.coroutines.flow.StateFlow<String?> = _deleteError

    fun dismissDeleteError() {
        _deleteError.value = null
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            runCatching { conversationRepo.deleteConversation(conversation) }
                .onFailure { e ->
                    Log.w(TAG, "deleteConversation blocked", e)
                    _deleteError.value = e.message ?: "删除失败"
                }
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.insertConversation(conversation)
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }
}

private const val SUPERVISION_TICK_MS = 30_000L

private fun tickerFlow(periodMs: Long) = kotlinx.coroutines.flow.flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}
