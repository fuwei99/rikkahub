package me.rerere.rikkahub.data.sync.r2

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.sync.s3.S3Config
import kotlin.uuid.Uuid

/**
 * Cloudflare R2 账户（云锚点同步 P3，多账户模型，plan §3.3 v1.1）。
 *
 * - 每个账户手动启停，仅决定**新上传的去向**（上传目标 = 第一个 enabled 且配齐字段的账户）；
 *   旧对象的读取路由看引用里携带的账户 [id]，与开关无关；
 * - 本配置随 settings 同步（与 LLM API key 同敏感度），双端自动互读；
 * - 删除/更换密钥必须二次确认：所有指向该桶的对象引用会失效。
 */
@Serializable
data class R2AccountConfig(
    /** 稳定身份（写入对象引用 r2://<id>/<key>），生成后不可变 */
    val id: String = Uuid.random().toString(),
    /** 用户起的备注名，如"主桶"/"姐姐的桶" */
    val alias: String = "",
    /** Cloudflare Account ID（决定 endpoint） */
    val accountId: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    /** false = 不再接收新上传（读取不受影响） */
    val enabled: Boolean = true,
) {
    val isConfigured: Boolean
        get() = accountId.isNotBlank() &&
            accessKeyId.isNotBlank() &&
            secretAccessKey.isNotBlank() &&
            bucket.isNotBlank()

    /** R2 私有桶的 S3 兼容端点；pathStyle 直连桶子域 */
    val endpoint: String get() = "https://$accountId.r2.cloudflarestorage.com"

    fun toS3Config(): S3Config = S3Config(
        endpoint = endpoint,
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
        bucket = bucket,
        region = "auto",
        pathStyle = true,
    )
}
