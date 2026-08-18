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
import kotlin.uuid.Uuid

/**
 * 聊天输入框状态。
 *
 * 2026-08-18 重构：这里**不再持有任何能力开关**。
 *
 * 旧实现用 `companion object` 里的四张 `mutableMapOf<Uuid, ...>` 存工具/记忆开关，
 * 后果是「仅内存、杀进程即丢、不跨端同步」，而且默认值仍来自助手 —— 用户在对话里
 * 关掉的东西，重启 App 又自己打开了。
 *
 * 现在这些开关是 `Conversation` 上的持久字段（`localTools` / `workspaceTools` /
 * `mcpTools` / `memoryOptions` / `reasoningLevel` / `enableWebSearch` / `enabledSkills`），
 * 由 `ChatVM.updateConversationOverrides` 写入并落库 + 云同步。
 * 本类只保留真正属于「输入框」的东西：文本、附件、编辑态。
 */
class ChatInputState(initialConversationId: Uuid? = null) {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    var compressImages by mutableStateOf(true)
    private var currentConversationId: Uuid? = null
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    init {
        initialConversationId?.let { switchConversation(it) }
    }

    fun switchConversation(conversationId: Uuid) {
        currentConversationId = conversationId
    }

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
        /**
         * 可在对话页工具弹窗里直接开关的本地工具。
         *
         * 2026-08-18 起这里包含**全部** [LocalToolOption]：原先 ImageGeneration / Subagent /
         * Inbox / Send 被排除在外，只能去改助手配置（= 影响该助手所有对话），
         * 正是「A 类工具」的根源。现在统一由对话级 `localTools` 承载，
         * 各自的 Picker（生图模型选择等）仍保留，但写入的是对话而非助手。
         */
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
            LocalToolOption.ImageGeneration,
            LocalToolOption.Subagent,
            LocalToolOption.Inbox,
            LocalToolOption.Send,
            LocalToolOption.SupervisionAdmin,
        )

        /** 工作区工具的兜底默认集合（workspace 配置未给覆盖项时用） */
        val WORKSPACE_TOOL_FALLBACK_DEFAULTS: Set<String> =
            WorkspaceToolDefaultEnabled.filterValues { it }.keys

        /** 所有工作区工具名，供 UI 遍历渲染 */
        val ALL_WORKSPACE_TOOL_NAMES: List<String> = WorkspaceToolNames
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
