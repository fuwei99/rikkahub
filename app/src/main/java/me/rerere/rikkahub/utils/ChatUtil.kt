package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
    folderId: Uuid? = null,
    temporary: Boolean = false,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
            folderId = folderId?.toString(),
            temporary = temporary,
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

private val ALLOWED_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip"
)

private val ALLOWED_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "mdx", "csv", "tsv", "log", "json", "jsonl", "json5",
    "js", "jsx", "mjs", "cjs", "ts", "tsx",
    "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "xml",
    "py", "pyi", "rb", "lua", "sql", "java", "kt", "kts",
    "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "m", "mm",
    "rs", "cs", "scala", "clj", "ex", "exs", "erl", "hs", "pl", "r", "jl", "zig", "nim",
    "toml", "ini", "cfg", "conf", "env", "gradle", "properties",
    "proto", "graphql", "gql", "yml", "yaml",
    "gitignore", "dockerfile", "makefile", "diff", "patch",
)

fun isAllowedFileType(fileName: String, mime: String): Boolean {
    if (mime in ALLOWED_MIME_TYPES || mime.startsWith("text/")) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in ALLOWED_FILE_EXTENSIONS
}
