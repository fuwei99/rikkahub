package me.rerere.rikkahub.data.sync.r2

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.s3.AwsSignatureV4
import me.rerere.rikkahub.data.sync.s3.S3Client
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "R2MediaStore"

/** 对象引用：r2://<acctId>/<key> —— 序列化进会话 JSON / 注册表的唯一云端形态 */
data class R2Ref(
    val acctId: String,
    val key: String,
) {
    override fun toString(): String = "$SCHEME$acctId/$key"

    companion object {
        const val SCHEME = "r2://"

        fun parse(value: String?): R2Ref? {
            if (value == null || !value.startsWith(SCHEME)) return null
            val rest = value.removePrefix(SCHEME)
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            return R2Ref(rest.substring(0, slash), rest.substring(slash + 1))
        }
    }
}

/**
 * R2 媒体存取（P3）：上传 / 镜像转存 / 预签名读 / 删除。
 *
 * - 上传目标 = 第一个 enabled 且配齐字段的账户（v1.1 拍板：与 provider 能力无关，一律先入 R2）；
 * - 读取按引用里的账户现签现用（bucket 私有，不绑域名无公网访问）；
 * - 预签名 URL 内存缓存至过期前 5 分钟，避免页面上每张图一次签名计算（签名本身是纯 CPU，主要省的是代码路径）；
 * - 按 5.1 #10：上传不做内容去重，每次上传 = 新对象（天然单所有者）。
 */
class R2MediaStore(
    private val settingsStore: SettingsStore,
    private val httpClient: HttpClient,
) {
    private data class CachedUrl(val url: String, val expiresAtMs: Long)

    private val presignCache = ConcurrentHashMap<String, CachedUrl>()

    private fun accounts(): List<R2AccountConfig> = settingsStore.settingsFlow.value.r2Accounts

    fun isConfigured(): Boolean = uploadTarget() != null

    /** 新上传去向；null = 没有可用账户（此时一切上传静默跳过，媒体保持本地形态） */
    fun uploadTarget(): R2AccountConfig? = accounts().firstOrNull { it.enabled && it.isConfigured }

    private fun accountOf(acctId: String): R2AccountConfig? = accounts().firstOrNull { it.id == acctId && it.isConfigured }

    private fun clientOf(acct: R2AccountConfig) = S3Client(acct.toS3Config(), httpClient)

    // ---------------- 上传 ----------------

    /**
     * 上传字节流；返回对象引用。无可用账户或失败时返回 failure——调用方应降级为保留本地形态。
     */
    suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        prefix: String = PREFIX_CHAT_UPLOADS,
    ): Result<R2Ref> = withContext(Dispatchers.IO) {
        runCatching {
            val acct = uploadTarget() ?: error("No enabled R2 account")
            val key = "$prefix/${Uuid.random()}${extOf(mimeType)}"
            clientOf(acct).putObject(key, bytes, mimeType).getOrThrow()
            R2Ref(acct.id, key).also { Log.i(TAG, "uploaded $it (${bytes.size}B)") }
        }
    }

    /** 下载外部 URL 原字节并转存（生图防过期镜像；不压缩，保原质量） */
    suspend fun mirror(
        httpUrl: String,
        prefix: String = PREFIX_GEN_IMAGES,
    ): Result<Pair<R2Ref, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val acct = uploadTarget() ?: error("No enabled R2 account")
            val response = httpClient.get(httpUrl)
            val bytes = response.bodyAsBytes()
            require(bytes.isNotEmpty()) { "Empty body for $httpUrl" }
            val mime = response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/") }
                ?: mimeFromExt(httpUrl) ?: "image/png"
            val key = "$prefix/${Uuid.random()}${extOf(mime)}"
            clientOf(acct).putObject(key, bytes, mime).getOrThrow()
            (R2Ref(acct.id, key) to mime).also {
                Log.i(TAG, "mirrored $httpUrl -> ${it.first} (${bytes.size}B)")
            }
        }
    }

    // ---------------- 读取 ----------------

    /**
     * 预签名 GET URL（带内存缓存）。账户被删/被改名同 id 仍在且字段齐即可签；
     * 签不出来（账户被删）返回 failure，UI 显示裂图占位。
     */
    suspend fun presign(ref: R2Ref, ttlSeconds: Long = 3600): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            val cacheKey = ref.toString()
            presignCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
                return@runCatching it.url
            }
            val acct = accountOf(ref.acctId) ?: error("R2 account ${ref.acctId} missing")
            val url = AwsSignatureV4.presignGet(
                config = acct.toS3Config(),
                path = "/${ref.key}",
                expiresSeconds = ttlSeconds,
            )
            presignCache[cacheKey] = CachedUrl(url, System.currentTimeMillis() + (ttlSeconds - 300) * 1000)
            url
        }
    }

    /** 给展示层的一条龙：r2:// → 预签名 URL；其他形态原样透传 */
    suspend fun displayUrl(value: String): String {
        val ref = R2Ref.parse(value) ?: return value
        return presign(ref).getOrElse {
            Log.w(TAG, "presign failed for $value", it)
            value
        }
    }

    suspend fun downloadBytes(ref: R2Ref): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val acct = accountOf(ref.acctId) ?: error("R2 account ${ref.acctId} missing")
            clientOf(acct).getObject(ref.key).getOrThrow()
        }
    }

    // ---------------- 删除 ----------------

    /** 资产归注册行所有：删除 = 对象 + 行一起没；对象删除失败仅记录（留给对账 GC） */
    suspend fun delete(ref: R2Ref): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val acct = accountOf(ref.acctId) ?: error("R2 account ${ref.acctId} missing")
            presignCache.remove(ref.toString())
            clientOf(acct).deleteObject(ref.key).getOrThrow()
            Log.i(TAG, "deleted $ref")
            Unit
        }
    }

    // ---------------- 工具 ----------------

    private fun extOf(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "image/avif" -> ".avif"
        "video/mp4" -> ".mp4"
        "video/webm" -> ".webm"
        "audio/mpeg" -> ".mp3"
        "audio/mp4", "audio/m4a" -> ".m4a"
        "audio/wav", "audio/x-wav" -> ".wav"
        "audio/ogg" -> ".ogg"
        else -> ""
    }

    private fun mimeFromExt(url: String): String? = when (url.substringAfterLast('.', "").lowercase().take(5)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> null
    }

    companion object {
        const val PREFIX_CHAT_UPLOADS = "chat-uploads"
        const val PREFIX_GEN_IMAGES = "gen-images"
        const val PREFIX_GEN_PREVIEWS = "gen-previews"
    }
}
