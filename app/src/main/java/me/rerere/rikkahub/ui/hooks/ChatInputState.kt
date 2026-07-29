package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultEnabled
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolNames
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.MemoryOptions
import kotlin.uuid.Uuid

class ChatInputState(initialConversationId: Uuid? = null) {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    var compressImages by mutableStateOf(true)
    private var currentConversationId: Uuid? = null
    private var memoryOptionsState by mutableStateOf(MemoryOptions())
    var memoryOptions: MemoryOptions
        get() = memoryOptionsState
        set(value) {
            memoryOptionsState = value
            currentConversationId?.let { memoryOptionsByConversation[it] = value }
        }
    private var localToolOverrides by mutableStateOf<Map<LocalToolOption, Boolean>>(emptyMap())
    private var workspaceToolOverrides by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var workspaceToolDefaults by mutableStateOf<Set<String>>(WorkspaceToolDefaultEnabled.filterValues { it }.keys)
    private var mcpToolOverrides by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    init {
        initialConversationId?.let { switchConversation(it) }
    }

    fun switchConversation(conversationId: Uuid) {
        currentConversationId = conversationId
        memoryOptionsState = memoryOptionsByConversation[conversationId] ?: MemoryOptions()
        localToolOverrides = localToolOverridesByConversation[conversationId].orEmpty()
        workspaceToolOverrides = workspaceToolOverridesByConversation[conversationId].orEmpty()
        mcpToolOverrides = mcpToolOverridesByConversation[conversationId].orEmpty()
    }

    fun isLocalToolEnabled(option: LocalToolOption, defaultEnabledTools: List<LocalToolOption>): Boolean =
        localToolOverrides[option] ?: (option in defaultEnabledTools)

    fun setLocalToolEnabled(option: LocalToolOption, enabled: Boolean) {
        localToolOverrides = localToolOverrides + (option to enabled)
        currentConversationId?.let { localToolOverridesByConversation[it] = localToolOverrides }
    }

    /**
     * 返回本次发送/重生成时应启用的 local tools。
     *
     * 只有 [CHAT_TOGGLEABLE_LOCAL_TOOLS] 里的项才能在 ChatInput 里被临时开关；
     * 不在白名单里的选项（如 [LocalToolOption.ImageGeneration]、[LocalToolOption.Subagent]）
     * 由各自的 Picker 直接写入 `assistant.localTools`，这里必须原样透传，
     * 否则会把 assistant 上已经启用的这类工具误杀。
     */
    fun activeLocalTools(defaultEnabledTools: List<LocalToolOption>): List<LocalToolOption> {
        val toggleables = CHAT_TOGGLEABLE_LOCAL_TOOLS.filter { isLocalToolEnabled(it, defaultEnabledTools) }
        val nonToggleables = defaultEnabledTools.filter { it !in CHAT_TOGGLEABLE_LOCAL_TOOLS }
        return (toggleables + nonToggleables).distinct()
    }

    fun isWorkspaceToolEnabled(toolName: String, defaultEnabledTools: Set<String>): Boolean =
        workspaceToolOverrides[toolName] ?: (toolName in defaultEnabledTools)

    fun setWorkspaceToolEnabled(toolName: String, enabled: Boolean) {
        workspaceToolOverrides = workspaceToolOverrides + (toolName to enabled)
        currentConversationId?.let { workspaceToolOverridesByConversation[it] = workspaceToolOverrides }
    }

    fun updateWorkspaceToolDefaults(defaultEnabledTools: Set<String>) {
        workspaceToolDefaults = defaultEnabledTools
    }

    fun activeWorkspaceTools(defaultEnabledTools: Set<String> = workspaceToolDefaults): Set<String> =
        WorkspaceToolNames.filter { isWorkspaceToolEnabled(it, defaultEnabledTools) }.toSet()

    fun isMcpToolEnabled(toolKey: String, defaultEnabledTools: Set<String>): Boolean =
        mcpToolOverrides[toolKey] ?: (toolKey in defaultEnabledTools)

    fun setMcpToolEnabled(toolKey: String, enabled: Boolean) {
        mcpToolOverrides = mcpToolOverrides + (toolKey to enabled)
        currentConversationId?.let { mcpToolOverridesByConversation[it] = mcpToolOverrides }
    }

    fun activeMcpTools(availableToolKeys: Set<String>, defaultEnabledTools: Set<String>): Set<String> =
        availableToolKeys.filter { isMcpToolEnabled(it, defaultEnabledTools) }.toSet()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            merged.add(UIMessagePart.Text(text))
                        }

                        part is UIMessagePart.Text -> {
                            merged.add(part)
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isEmpty()
    }

    fun addImageUrl(url: String) {
        messageContent = messageContent + UIMessagePart.Image(url)
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    fun addParts(parts: List<UIMessagePart>) {
        messageContent = messageContent + parts
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    companion object {
        private val memoryOptionsByConversation = mutableMapOf<Uuid, MemoryOptions>()
        private val localToolOverridesByConversation = mutableMapOf<Uuid, Map<LocalToolOption, Boolean>>()
        private val workspaceToolOverridesByConversation = mutableMapOf<Uuid, Map<String, Boolean>>()
        private val mcpToolOverridesByConversation = mutableMapOf<Uuid, Map<String, Boolean>>()

        val CHAT_TOGGLEABLE_LOCAL_TOOLS = listOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Tts,
            LocalToolOption.AskUser,
            LocalToolOption.ScreenTime,
            LocalToolOption.Calendar,
            LocalToolOption.Alarm,
            LocalToolOption.Notification,
        )
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
