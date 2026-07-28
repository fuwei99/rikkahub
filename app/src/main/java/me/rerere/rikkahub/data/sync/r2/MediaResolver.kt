package me.rerere.rikkahub.data.sync.r2

import android.content.Context
import android.util.Base64
import android.util.Log
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
 * - **上行**：[uploadLocalAttachments] 在消息入库前把 file:// 图片附件压缩上传 R2，
 *   会话 JSON 从此只存 r2:// 引用；失败时静默保留 file://（纯本地行为）
 * - **下行（发给 LLM）**：[prepareOutgoingMessages] 按 provider 能力即时重写：
 *   r2:// → 预签名 URL（URL 能力）或 data: base64（base64-only 模型，现下载现转），
 *   provider 侧的 http 直通 / fileData / Claude url source 原生接住，零改动
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
     * 发送消息前的附件上云：file:// 图片 → 压缩（复用 encodeBase64 的 2560/JPEG85 管线）→ PUT R2。
     * 无可用账户 / 失败均静默降级为原 part（保持纯本地行为）。
     */
    suspend fun uploadLocalAttachments(parts: List<UIMessagePart>): List<UIMessagePart> {
        if (!r2MediaStore.isConfigured()) return parts
        val hasLocal = parts.any { it is UIMessagePart.Image && (it.url.startsWith("file://") || it.url.startsWith("data:image/")) }
        if (!hasLocal) return parts
        return parts.map { part ->
            if (part is UIMessagePart.Image && (part.url.startsWith("file://") || part.url.startsWith("data:image/"))) {
                uploadOne(part.url)?.let { uploaded ->
                    part.copy(
                        url = uploaded.ref.toString(),
                        metadata = mergeMeta(part.metadata, uploaded.mime),
                    )
                } ?: part
            } else {
                part
            }
        }
    }

    private data class Uploaded(val ref: R2Ref, val mime: String)

    private suspend fun uploadOne(fileUrl: String): Uploaded? {
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
            msg.parts.any { it.containsR2Image() }
        }
        if (!hasR2) return messages
        return messages.map { msg ->
            msg.copy(parts = msg.parts.map { part -> resolvePart(part, transport) })
        }
    }

    private fun UIMessagePart.containsR2Image(): Boolean = when (this) {
        is UIMessagePart.Image -> R2Ref.parse(url) != null
        is UIMessagePart.Tool -> output.any { it.containsR2Image() }
        else -> false
    }

    private suspend fun resolvePart(part: UIMessagePart, transport: ImageTransport): UIMessagePart =
        when (part) {
            is UIMessagePart.Image -> resolveImage(part, transport)
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
}
