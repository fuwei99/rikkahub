package me.rerere.rikkahub.data.sync.exporter

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

@Serializable
data class AssistantPackage(
    val version: Int = 1,
    val assistant: Assistant,
    val conversations: List<Conversation> = emptyList()
)

object AssistantExporter {
    /**
     * 导出单助手及其关联的聊天记录数据包 (.rikka / .zip)
     */
    fun exportAssistantPackage(
        assistant: Assistant,
        conversations: List<Conversation>,
        outputFile: File
    ) {
        val pkg = AssistantPackage(
            assistant = assistant,
            conversations = conversations
        )
        val jsonStr = JsonInstant.encodeToString(pkg)

        ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
            val entry = ZipEntry("assistant_package.json")
            zipOut.putNextEntry(entry)
            zipOut.write(jsonStr.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        }
    }

    /**
     * 解析导入单助手数据包
     */
    fun importAssistantPackage(file: File): AssistantPackage {
        val jsonStr = ZipFile(file).use { zip ->
            val entry = zip.getEntry("assistant_package.json")
                ?: throw IllegalArgumentException("Invalid assistant package: assistant_package.json not found")
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
        return JsonInstant.decodeFromString<AssistantPackage>(jsonStr)
    }
}
