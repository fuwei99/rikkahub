package me.rerere.rikkahub.data.sync.r2

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File

private const val TAG = "MediaResolver"

/**
 * 发送链路的媒体适配层（P3，plan §3.4 v1.1）：存储层与传输层解耦。
 *
 * - **上行**：[uploadLocalAttachments] 在消息入库前把 file:// / data:image 附件上传 R2，
 *   图片沿用压缩管线，文档/音视频保留原字节；会话 JSON 从此只存 r2:// 引用；
 *   失败时静默保留本地形态（纯本地行为）
 * - **下行（发给 LLM）**：[prepareOutgoingMessages] 按 provider 能力即时重写：
 *   图片 r2:// → 预签名 URL（URL 能力）或 data: base64（base64-only 模型）；
 *   文档/音视频 r2:// → cache 临时 file://，供 DocumentAsPromptTransformer / Google inlineData / base64 编码读取。
 *
 * 能力表（§5.1 #8 的实例化）：
 * Claude → URL；Google（原生 API，含 Vertex）→ URL；
 * OpenAI → 官方域名 URL，自定义网关 base64（第三方网关大概率拉不动我们的预签名 URL）。
 */
class MediaResolver(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val r2MediaStore: R2MediaStore,
    private val database: AppDatabase,
) {
    enum class ImageTransport { URL, BASE64 }

    fun transportFor(provider: ProviderSetting): ImageTransport = when (provider) {
        is ProviderSetting.Claude -> ImageTransport.URL
        is ProviderSetting.Google -> ImageTransport.URL
        is ProviderSetting.OpenAI ->
            if (provider.baseUrl.contains("api.openai.com")) ImageTransport.URL else ImageTransport.BASE64
    }

    // ---------------- 上行：file:// 附件 → R2 ----------------

    /**
     * 发送消息前的附件上云：图片压缩后上传；文档/音视频原字节上传。
     * 无可用账户 / 失败均静默降级为原 part（保持纯本地行为）。
     */
    suspend fun uploadLocalAttachments(parts: List<UIMessagePart>): List<UIMessagePart> {
        if (!r2MediaStore.isConfigured()) return parts
        val hasUploadable = parts.any { it.isUploadableLocalMedia() }
        if (!hasUploadable) return parts
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Image -> if (part.isUploadableLocalMedia()) {
                    uploadImage(part.url)?.let { uploaded ->
                        part.copy(
                            url = uploaded.ref.toString(),
                            metadata = mergeMeta(part.metadata, uploaded.mime),
                        )
                    } ?: part
                } else part

                is UIMessagePart.Document -> if (part.isUploadableLocalMedia()) {
                    uploadRawFile(part.url, part.mime)?.let { ref -> part.copy(url = ref.toString()) } ?: part
                } else part

                is UIMessagePart.Video -> if (part.isUploadableLocalMedia()) {
                    uploadRawFile(part.url, "video/mp4")?.let { ref -> part.copy(url = ref.toString()) } ?: part
                } else part

                is UIMessagePart.Audio -> if (part.isUploadableLocalMedia()) {
                    uploadRawFile(part.url, "audio/mpeg")?.let { ref -> part.copy(url = ref.toString()) } ?: part
                } else part

                else -> part
            }
        }
    }

    private fun UIMessagePart.isUploadableLocalMedia(): Boolean = when (this) {
        is UIMessagePart.Image -> url.startsWith("file://") || url.startsWith("data:image/")
        is UIMessagePart.Document -> url.startsWith("file://")
        is UIMessagePart.Video -> url.startsWith("file://")
        is UIMessagePart.Audio -> url.startsWith("file://")
        else -> false
    }

    private data class Uploaded(val ref: R2Ref, val mime: String)

    private suspend fun uploadImage(fileUrl: String): Uploaded? {
        val (bytes, mime) = if (fileUrl.startsWith("data:image/")) {
            val header = fileUrl.substringBefore(',', missingDelimiterValue = "")
            val base64 = fileUrl.substringAfter(',', missingDelimiterValue = "")
            val mime = header.removePrefix("data:").substringBefore(';').takeIf { it.startsWith("image/") }
                ?: "image/png"
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
            bytes to mime
        } else {
            val encoded = UIMessagePart.Image(url = fileUrl).encodeBase64(withPrefix = false).getOrNull()
                ?: return null
            val bytes = runCatching { Base64.decode(encoded.base64, Base64.DEFAULT) }.getOrNull()
                ?: return null
            bytes to encoded.mimeType
        }
        val ref = r2MediaStore.upload(bytes, mime, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()
            ?: return null
        if (fileUrl.startsWith("file://")) {
            backfillManagedFile(fileUrl, ref)
        }
        return Uploaded(ref, mime)
    }

    private suspend fun uploadRawFile(fileUrl: String, mime: String): R2Ref? {
        val file = runCatching { fileUrl.toUri().toFile() }.getOrNull() ?: return null
        if (!file.exists() || !file.isFile) return null
        val ref = r2MediaStore.upload(file.readBytes(), mime, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()
            ?: return null
        backfillManagedFile(fileUrl, ref)
        return ref
    }

    /** managed_files 里若已有该本地文件的登记行，回填 r2 归属（文件管理页双端可见） */
    private suspend fun backfillManagedFile(fileUrl: String, ref: R2Ref) {
        runCatching {
            val path = fileUrl.toUri().path ?: return@runCatching
            val filesDir = context.filesDir.absolutePath + File.separator
            if (!path.startsWith(filesDir)) return@runCatching
            val relative = path.removePrefix(filesDir)
            database.managedFileDao().getByPath(relative)?.let { row ->
                database.managedFileDao().update(row.copy(r2Key = ref.key, r2Acct = ref.acctId))
            }
        }.onFailure { Log.w(TAG, "backfillManagedFile failed for $fileUrl", it) }
    }

    // ---------------- 下行：r2:// → 可发送形态 ----------------

    suspend fun prepareOutgoingMessages(
        messages: List<UIMessage>,
        transport: ImageTransport,
    ): List<UIMessage> {
        val hasR2 = messages.any { msg ->
            msg.parts.any { it.containsR2Media() }
        }
        if (!hasR2) return messages
        return messages.map { msg ->
            msg.copy(parts = msg.parts.map { part -> resolvePart(part, transport) })
        }
    }

    private fun UIMessagePart.containsR2Media(): Boolean = when (this) {
        is UIMessagePart.Image -> R2Ref.parse(url) != null
        is UIMessagePart.Document -> R2Ref.parse(url) != null
        is UIMessagePart.Video -> R2Ref.parse(url) != null
        is UIMessagePart.Audio -> R2Ref.parse(url) != null
        is UIMessagePart.Tool -> output.any { it.containsR2Media() }
        else -> false
    }

    private suspend fun resolvePart(part: UIMessagePart, transport: ImageTransport): UIMessagePart =
        when (part) {
            is UIMessagePart.Image -> resolveImage(part, transport)
            is UIMessagePart.Document -> resolveDocument(part)
            is UIMessagePart.Video -> resolveVideo(part)
            is UIMessagePart.Audio -> resolveAudio(part)
            // 工具输出里嵌的图片（如生图结果回传）同样要解析成可发送形态
            is UIMessagePart.Tool -> part.copy(
                output = part.output.map { resolvePart(it, transport) }
            )

            else -> part
        }

    private suspend fun resolveImage(part: UIMessagePart.Image, transport: ImageTransport): UIMessagePart {
        val ref = R2Ref.parse(part.url) ?: return part
        return when (transport) {
            ImageTransport.URL -> r2MediaStore.presign(ref).fold(
                onSuccess = { part.copy(url = it) },
                onFailure = { e ->
                    Log.w(TAG, "presign failed for ${part.url}", e)
                    part
                }
            )

            ImageTransport.BASE64 -> runCatching {
                val bytes = r2MediaStore.downloadBytes(ref).getOrThrow()
                val mime = metaMime(part.metadata) ?: mimeFromKey(ref.key) ?: "image/jpeg"
                part.copy(url = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
            }.getOrElse { e ->
                Log.w(TAG, "download failed for ${part.url}", e)
                part
            }
        }
    }


    private suspend fun resolveDocument(part: UIMessagePart.Document): UIMessagePart.Document {
        val ref = R2Ref.parse(part.url) ?: return part
        return downloadToTemp(ref, part.fileName, part.mime)?.let { part.copy(url = it) } ?: part
    }

    private suspend fun resolveVideo(part: UIMessagePart.Video): UIMessagePart.Video {
        val ref = R2Ref.parse(part.url) ?: return part
        return downloadToTemp(ref, ref.key.substringAfterLast('/'), "video/mp4")?.let { part.copy(url = it) } ?: part
    }

    private suspend fun resolveAudio(part: UIMessagePart.Audio): UIMessagePart.Audio {
        val ref = R2Ref.parse(part.url) ?: return part
        return downloadToTemp(ref, ref.key.substringAfterLast('/'), "audio/mpeg")?.let { part.copy(url = it) } ?: part
    }

    private suspend fun downloadToTemp(ref: R2Ref, fileName: String, mime: String): String? {
        return runCatching {
            val bytes = r2MediaStore.downloadBytes(ref).getOrThrow()
            val dir = File(context.cacheDir, "r2_media").apply { mkdirs() }
            val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
                .takeIf { it.length in 1..8 }
                ?.let { ".$it" }
                ?: mimeToExt(mime)
            val safe = ref.toString().replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "$safe$ext")
            file.writeBytes(bytes)
            file.toUri().toString()
        }.getOrElse { e ->
            Log.w(TAG, "download temp failed for $ref", e)
            null
        }
    }

    // ---------------- 工具 ----------------

    private fun mergeMeta(old: JsonObject?, mime: String): JsonObject = buildJsonObject {
        old?.forEach { (k, v) -> put(k, v) }
        put("r2_mime", mime)
    }

    private fun metaMime(metadata: JsonObject?): String? =
        metadata?.get("r2_mime")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("image/") }

    private fun mimeFromKey(key: String): String? = when (key.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> null
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "application/pdf" -> ".pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
        "application/epub+zip" -> ".epub"
        "video/mp4" -> ".mp4"
        "audio/mpeg" -> ".mp3"
        else -> ""
    }
}
