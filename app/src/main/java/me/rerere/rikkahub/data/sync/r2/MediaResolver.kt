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
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.sync.core.BUNDLE_MANAGED_FILES
import me.rerere.rikkahub.data.sync.s3.S3Exception
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "MediaResolver"

/**
 * 发送链路的媒体适配层（P3，plan §3.4 v1.1）：存储层与传输层解耦。
 *
 * - **上行**：[uploadLocalAttachments] 在消息入库前把 file:// / data:image 附件上传 R2，
 *   图片沿用压缩管线，文档/音视频保留原字节；会话 JSON 从此只存 r2:// 引用；
 *   失败时静默保留本地形态（纯本地行为）
 * - **下行（发给 LLM）**：[prepareOutgoingMessages] 按模型输入模态即时重写：
 *   只要模型勾选了 [Modality.URL]，r2:// 就预签名成 https URL；
 *   未勾选 URL 时降级为 data: base64 / 临时 file://，报错由具体 provider 暴露。
 */
class MediaResolver(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val r2MediaStore: R2MediaStore,
    private val database: AppDatabase,
) {
    enum class ImageTransport { URL, BASE64 }

    fun transportFor(model: Model): ImageTransport =
        if (Modality.URL in model.inputModalities) ImageTransport.URL else ImageTransport.BASE64

    // ---------------- 上行：file:// 附件 → R2 ----------------

    /**
     * 发送消息前的附件上云：图片压缩后上传；文档/音视频原字节上传。
     * 无可用账户 / 失败均静默降级为原 part（保持纯本地行为）。
     */
    data class UploadLocalAttachmentsResult(
        val parts: List<UIMessagePart>,
        val failures: List<String> = emptyList(),
        val uploadedCount: Int = 0,
    )

    suspend fun uploadLocalAttachments(parts: List<UIMessagePart>): List<UIMessagePart> =
        uploadLocalAttachmentsWithReport(parts).parts

    suspend fun uploadLocalAttachmentsWithReport(parts: List<UIMessagePart>): UploadLocalAttachmentsResult {
        if (!r2MediaStore.isConfigured()) return UploadLocalAttachmentsResult(parts)
        val hasUploadable = parts.any { it.isUploadableLocalMedia() }
        if (!hasUploadable) return UploadLocalAttachmentsResult(parts)
        val failures = mutableListOf<String>()
        var uploadedCount = 0
        val uploadedParts = parts.map { part ->
            when (part) {
                is UIMessagePart.Image -> if (part.isUploadableLocalMedia()) {
                    runCatching { uploadImageOrThrow(part.url) }
                        .onFailure { failures += "图片上传 R2 失败：${it.detailMessage()}" }
                        .getOrNull()
                        ?.let { uploaded ->
                            uploadedCount += 1
                            part.copy(
                                url = uploaded.ref.toString(),
                                metadata = mergeMeta(part.metadata, uploaded.mime),
                            )
                        } ?: part
                } else part

                is UIMessagePart.Document -> if (part.isUploadableLocalMedia()) {
                    runCatching { uploadRawFileOrThrow(part.url, part.mime) }
                        .onFailure { failures += "文件上传 R2 失败（${part.fileName}）：${it.detailMessage()}" }
                        .getOrNull()
                        ?.let { ref ->
                            uploadedCount += 1
                            part.copy(url = ref.toString())
                        } ?: part
                } else part

                is UIMessagePart.Video -> if (part.isUploadableLocalMedia()) {
                    runCatching { uploadRawFileOrThrow(part.url, "video/mp4") }
                        .onFailure { failures += "视频上传 R2 失败：${it.detailMessage()}" }
                        .getOrNull()
                        ?.let { ref ->
                            uploadedCount += 1
                            part.copy(url = ref.toString())
                        } ?: part
                } else part

                is UIMessagePart.Audio -> if (part.isUploadableLocalMedia()) {
                    runCatching { uploadRawFileOrThrow(part.url, "audio/mpeg") }
                        .onFailure { failures += "音频上传 R2 失败：${it.detailMessage()}" }
                        .getOrNull()
                        ?.let { ref ->
                            uploadedCount += 1
                            part.copy(url = ref.toString())
                        } ?: part
                } else part

                else -> part
            }
        }
        return UploadLocalAttachmentsResult(uploadedParts, failures, uploadedCount)
    }

    private fun UIMessagePart.isUploadableLocalMedia(): Boolean = when (this) {
        is UIMessagePart.Image -> url.startsWith("file://") || url.startsWith("data:image/")
        is UIMessagePart.Document -> url.startsWith("file://")
        is UIMessagePart.Video -> url.startsWith("file://")
        is UIMessagePart.Audio -> url.startsWith("file://")
        else -> false
    }

    private data class Uploaded(val ref: R2Ref, val mime: String)

    private suspend fun uploadImageOrThrow(fileUrl: String): Uploaded {
        val (bytes, mime) = if (fileUrl.startsWith("data:image/")) {
            val header = fileUrl.substringBefore(',', missingDelimiterValue = "")
            val base64 = fileUrl.substringAfter(',', missingDelimiterValue = "")
            val mime = header.removePrefix("data:").substringBefore(';').takeIf { it.startsWith("image/") }
                ?: "image/png"
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }
                .getOrElse { error("data:image 解码失败：${it.detailMessage()}") }
            require(bytes.isNotEmpty()) { "data:image 内容为空" }
            bytes to mime
        } else {
            val encoded = UIMessagePart.Image(url = fileUrl).encodeBase64(withPrefix = false)
                .getOrElse { error("读取图片失败：${it.detailMessage()}") }
            val bytes = runCatching { Base64.decode(encoded.base64, Base64.DEFAULT) }
                .getOrElse { error("图片 base64 解码失败：${it.detailMessage()}") }
            require(bytes.isNotEmpty()) { "图片内容为空" }
            bytes to encoded.mimeType
        }
        val ref = r2MediaStore.upload(bytes, mime, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrThrow()
        if (fileUrl.startsWith("file://")) {
            backfillManagedFile(fileUrl, ref)
        }
        return Uploaded(ref, mime)
    }

    private suspend fun uploadRawFileOrThrow(fileUrl: String, mime: String): R2Ref {
        val file = runCatching { fileUrl.toUri().toFile() }
            .getOrElse { error("文件路径无效：${it.detailMessage()}") }
        require(file.exists()) { "本地文件不存在：${file.name}" }
        require(file.isFile) { "不是普通文件：${file.name}" }
        val bytes = file.readBytes()
        require(bytes.isNotEmpty()) { "文件为空：${file.name}" }
        val ref = r2MediaStore.upload(bytes, mime, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrThrow()
        backfillManagedFile(fileUrl, ref)
        return ref
    }

    private fun Throwable.detailMessage(): String = when {
        this is S3Exception && responseBody.isNotBlank() -> "${message ?: javaClass.simpleName}: ${responseBody.take(500)}"
        else -> message ?: cause?.message ?: javaClass.simpleName
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
                // R2 上传成功只回填云端索引，不删除本地缓存：本地文件仍用于最快预览，
                // 用户可在文件管理里手动“删除本地缓存”。
                enqueueManagedFilesBundleSync()
            }
        }.onFailure { Log.w(TAG, "backfillManagedFile failed for $fileUrl", it) }
    }

    private suspend fun enqueueManagedFilesBundleSync() {
        val outbox = database.syncOutboxDao()
        outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_MANAGED_FILES)
        outbox.insert(
            SyncOutboxEntity(
                kind = SyncOutboxEntity.KIND_BUNDLE,
                refKey = BUNDLE_MANAGED_FILES,
                op = SyncOutboxEntity.OP_UPSERT,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    // ---------------- 下行：r2:// → 可发送形态 ----------------

    suspend fun prepareOutgoingMessages(
        messages: List<UIMessage>,
        transport: ImageTransport,
    ): List<UIMessage> {
        val hasResolvable = messages.any { msg ->
            msg.parts.any { it.containsResolvableMedia() }
        }
        if (!hasResolvable) return messages
        return messages.map { msg ->
            msg.copy(parts = msg.parts.map { part -> resolvePart(part, transport) })
        }
    }

    private fun UIMessagePart.containsResolvableMedia(): Boolean = when (this) {
        is UIMessagePart.Image -> R2Ref.parse(url) != null || r2MediaStore.refFromConfiguredUrl(url) != null || url.startsWith("http://", true) || url.startsWith("https://", true) || isUploadableLocalMedia()
        is UIMessagePart.Document -> R2Ref.parse(url) != null || isUploadableLocalMedia()
        is UIMessagePart.Video -> R2Ref.parse(url) != null || isUploadableLocalMedia()
        is UIMessagePart.Audio -> R2Ref.parse(url) != null || isUploadableLocalMedia()
        is UIMessagePart.Tool -> output.any { it.containsResolvableMedia() }
        else -> false
    }

    private suspend fun resolvePart(part: UIMessagePart, transport: ImageTransport): UIMessagePart =
        when (part) {
            is UIMessagePart.Image -> resolveImage(part, transport)
            is UIMessagePart.Document -> resolveDocument(part, transport)
            is UIMessagePart.Video -> resolveVideo(part, transport)
            is UIMessagePart.Audio -> resolveAudio(part, transport)
            // 工具输出里嵌的图片（如生图结果回传）同样要解析成可发送形态
            is UIMessagePart.Tool -> part.copy(
                output = part.output.map { resolvePart(it, transport) }
            )

            else -> part
        }

    private suspend fun resolveImage(part: UIMessagePart.Image, transport: ImageTransport): UIMessagePart {
        val configuredRef = R2Ref.parse(part.url) ?: r2MediaStore.refFromConfiguredUrl(part.url)
        if (configuredRef == null && (part.url.startsWith("http://", true) || part.url.startsWith("https://", true))) {
            return when (transport) {
                ImageTransport.URL -> part
                ImageTransport.BASE64 -> runCatching {
                    val local = downloadExternalImageToLocal(part.url)
                    part.copy(url = local.toUri().toString())
                }.getOrElse { e ->
                    Log.w(TAG, "external image download failed for ${part.url}", e)
                    part
                }
            }
        }

        val ref = configuredRef ?: run {
            if (!part.isUploadableLocalMedia()) return part
            val uploaded = runCatching { uploadImageOrThrow(part.url) }.getOrNull() ?: return part
            return when (transport) {
                ImageTransport.URL -> r2MediaStore.presign(uploaded.ref).getOrNull()?.let { part.copy(url = it) } ?: part
                ImageTransport.BASE64 -> part
            }
        }
        return when (transport) {
            ImageTransport.URL -> r2MediaStore.presign(ref).fold(
                onSuccess = { part.copy(url = it) },
                onFailure = { e ->
                    Log.w(TAG, "presign failed for ${part.url}", e)
                    part
                }
            )

            ImageTransport.BASE64 -> runCatching {
                val mime = metaMime(part.metadata) ?: mimeFromKey(ref.key) ?: "image/jpeg"
                val local = ensureLocalManagedFile(ref, ref.key.substringAfterLast('/'), mime)
                part.copy(url = local.toUri().toString())
            }.getOrElse { e ->
                Log.w(TAG, "download failed for ${part.url}", e)
                part
            }
        }
    }


    private suspend fun resolveDocument(part: UIMessagePart.Document, transport: ImageTransport): UIMessagePart.Document {
        val originalUrl = part.url
        val ref = R2Ref.parse(originalUrl) ?: run {
            if (!part.isUploadableLocalMedia()) return part
            val uploaded = runCatching { uploadRawFileOrThrow(part.url, part.mime) }.getOrNull() ?: return part
            return if (transport == ImageTransport.URL) {
                r2MediaStore.presign(uploaded).getOrNull()?.let {
                    part.copy(url = it, metadata = mergeMeta(part.metadata, "r2_ref", uploaded.toString()))
                } ?: part
            } else part
        }
        if (transport == ImageTransport.URL) {
            return r2MediaStore.presign(ref).getOrNull()?.let {
                part.copy(url = it, metadata = mergeMeta(part.metadata, "r2_ref", originalUrl))
            } ?: part
        }
        return ensureLocalManagedFile(ref, part.fileName, part.mime).let {
            part.copy(url = it.toUri().toString(), metadata = mergeMeta(part.metadata, "r2_ref", originalUrl))
        }
    }

    private suspend fun resolveVideo(part: UIMessagePart.Video, transport: ImageTransport): UIMessagePart.Video {
        val ref = R2Ref.parse(part.url) ?: run {
            if (!part.isUploadableLocalMedia()) return part
            val uploaded = runCatching { uploadRawFileOrThrow(part.url, "video/mp4") }.getOrNull() ?: return part
            return if (transport == ImageTransport.URL) r2MediaStore.presign(uploaded).getOrNull()?.let { part.copy(url = it) } ?: part else part
        }
        if (transport == ImageTransport.URL) {
            return r2MediaStore.presign(ref).getOrNull()?.let { part.copy(url = it) } ?: part
        }
        return ensureLocalManagedFile(ref, ref.key.substringAfterLast('/'), "video/mp4").let { part.copy(url = it.toUri().toString()) }
    }

    private suspend fun resolveAudio(part: UIMessagePart.Audio, transport: ImageTransport): UIMessagePart.Audio {
        val ref = R2Ref.parse(part.url) ?: run {
            if (!part.isUploadableLocalMedia()) return part
            val uploaded = runCatching { uploadRawFileOrThrow(part.url, "audio/mpeg") }.getOrNull() ?: return part
            return if (transport == ImageTransport.URL) r2MediaStore.presign(uploaded).getOrNull()?.let { part.copy(url = it) } ?: part else part
        }
        if (transport == ImageTransport.URL) {
            return r2MediaStore.presign(ref).getOrNull()?.let { part.copy(url = it) } ?: part
        }
        return ensureLocalManagedFile(ref, ref.key.substringAfterLast('/'), "audio/mpeg").let { part.copy(url = it.toUri().toString()) }
    }


    private suspend fun downloadExternalImageToLocal(url: String): File {
        val response = r2MediaStore.downloadExternal(url).getOrThrow()
        val bytes = response.first
        val mime = response.second ?: "image/jpeg"
        val ext = mimeToExt(mime).ifBlank { ".jpg" }
        val now = System.currentTimeMillis()
        val fileName = "${Uuid.random()}$ext"
        val relative = "${FileFolders.UPLOAD}/$fileName"
        val file = File(context.filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        database.managedFileDao().insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = relative,
                displayName = url.substringBefore('?').substringAfterLast('/').ifBlank { fileName },
                mimeType = mime,
                sizeBytes = bytes.size.toLong(),
                createdAt = now,
                updatedAt = now,
            )
        )
        // Try to mirror ordinary external URL to R2 for later sync, but do not block local use.
        runCatching {
            r2MediaStore.upload(bytes, mime, R2MediaStore.PREFIX_CHAT_UPLOADS).getOrNull()?.let { ref ->
                database.managedFileDao().getByPath(relative)?.let { row ->
                    database.managedFileDao().update(row.copy(r2Key = ref.key, r2Acct = ref.acctId))
                }
            }
        }
        return file
    }

    private suspend fun ensureLocalManagedFile(ref: R2Ref, displayName: String, mime: String): File {
        val dao = database.managedFileDao()
        val existing = dao.getByR2Ref(ref.key, ref.acctId)
        existing?.let { row ->
            val file = File(context.filesDir, row.relativePath)
            if (file.isFile) return file
        }
        val bytes = r2MediaStore.downloadBytes(ref).getOrThrow()
        val now = System.currentTimeMillis()
        val folder = folderForR2Key(ref.key)
        val safeName = displayName.substringAfterLast('/').ifBlank { ref.key.substringAfterLast('/') }.ifBlank { "file" }
        val ext = safeName.substringAfterLast('.', missingDelimiterValue = "").takeIf { it.length in 1..8 }?.let { ".$it" } ?: mimeToExt(mime)
        val fileName = "${Uuid.random()}$ext"
        val relative = "$folder/$fileName"
        val file = File(context.filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        if (existing != null) {
            dao.update(
                existing.copy(
                    folder = folder,
                    relativePath = relative,
                    displayName = safeName,
                    mimeType = mime,
                    sizeBytes = bytes.size.toLong(),
                    updatedAt = now,
                    r2Key = ref.key,
                    r2Acct = ref.acctId,
                )
            )
        } else {
            dao.insert(
                ManagedFileEntity(
                    folder = folder,
                    relativePath = relative,
                    displayName = safeName,
                    mimeType = mime,
                    sizeBytes = bytes.size.toLong(),
                    createdAt = now,
                    updatedAt = now,
                    r2Key = ref.key,
                    r2Acct = ref.acctId,
                )
            )
        }
        return file
    }

    private fun folderForR2Key(key: String): String = when {
        key.startsWith("${R2MediaStore.PREFIX_GEN_IMAGES}/") -> FileFolders.IMAGES
        key.startsWith("${R2MediaStore.PREFIX_GEN_PREVIEWS}/") -> FileFolders.LLM_PREVIEWS
        key.startsWith("${R2MediaStore.PREFIX_CHAT_UPLOADS}/") -> FileFolders.UPLOAD
        else -> FileFolders.UPLOAD
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

    private fun mergeMeta(old: JsonObject?, key: String, value: String): JsonObject = buildJsonObject {
        old?.forEach { (k, v) -> put(k, v) }
        put(key, value)
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
