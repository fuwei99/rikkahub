package me.rerere.rikkahub.data.sync.s3

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object AwsSignatureV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    data class SignedRequest(
        val headers: Map<String, String>,
        val url: String,
    )

    fun sign(
        config: S3Config,
        method: String,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        payload: ByteArray? = null,
        payloadHash: String? = null,
        contentLength: Long? = null,
        contentType: String? = null,
    ): SignedRequest {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val dateStamp = now.format(dateFormatter)
        val amzDate = now.format(timestampFormatter)

        val resolvedPayloadHash = payloadHash ?: payload?.sha256Hex() ?: UNSIGNED_PAYLOAD

        val host = config.host
        val canonicalUri = if (config.pathStyle) {
            "/${config.bucket}$path"
        } else {
            path
        }.let { if (it.isEmpty()) "/" else it }

        val hostAlreadyContainsBucket = host.startsWith("${config.bucket}.")

        val allHeaders = mutableMapOf(
            "host" to when {
                config.pathStyle -> host
                hostAlreadyContainsBucket -> host
                else -> "${config.bucket}.$host"
            },
            "x-amz-content-sha256" to resolvedPayloadHash,
            "x-amz-date" to amzDate,
        )
        contentType?.let { allHeaders["content-type"] = it }
        payload?.let { allHeaders["content-length"] = it.size.toString() }
        contentLength?.let { allHeaders["content-length"] = it.toString() }
        allHeaders.putAll(headers.mapKeys { it.key.lowercase() })

        val signedHeaders = allHeaders.keys.sorted().joinToString(";")
        val canonicalHeaders = allHeaders.entries
            .sortedBy { it.key }
            .joinToString("") { "${it.key}:${it.value.trim()}\n" }

        val canonicalQueryString = queryParams.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }

        val canonicalRequest = buildString {
            appendLine(method)
            appendLine(canonicalUri.urlEncodePath())
            appendLine(canonicalQueryString)
            append(canonicalHeaders)
            appendLine()
            appendLine(signedHeaders)
            append(resolvedPayloadHash)
        }

        val credentialScope = "$dateStamp/${config.region}/$SERVICE/aws4_request"
        val stringToSign = buildString {
            appendLine(ALGORITHM)
            appendLine(amzDate)
            appendLine(credentialScope)
            append(canonicalRequest.sha256Hex())
        }

        val signingKey = getSignatureKey(
            config.secretAccessKey,
            dateStamp,
            config.region,
            SERVICE
        )
        val signature = hmacSha256(signingKey, stringToSign).toHexString()

        val authorizationHeader = buildString {
            append("$ALGORITHM ")
            append("Credential=${config.accessKeyId}/$credentialScope, ")
            append("SignedHeaders=$signedHeaders, ")
            append("Signature=$signature")
        }

        val resultHeaders = allHeaders.toMutableMap()
        resultHeaders["authorization"] = authorizationHeader

        val url = buildString {
            append(if (config.isHttps) "https://" else "http://")
            append(
                when {
                    config.pathStyle -> host
                    hostAlreadyContainsBucket -> host
                    else -> "${config.bucket}.$host"
                }
            )
            append(canonicalUri)
            if (canonicalQueryString.isNotEmpty()) {
                append("?$canonicalQueryString")
            }
        }

        return SignedRequest(
            headers = resultHeaders,
            url = url
        )
    }

    /**
     * 生成 GET 预签名 URL（query-string 版 SigV4，payload 固定 UNSIGNED-PAYLOAD）。
     *
     * 用途：R2 私有桶图片对外临时可达——LLM 按 URL 抓图、Coil 按 URL 渲染，
     * 都是"现签现用"。签名计算纯本地，零 API 调用，零成本。
     *
     * @param path 桶内对象路径（与 [sign] 的 path 语义一致，如 "/chat-uploads/xx.jpg"）
     * @param expiresSeconds 有效期（秒），默认 1 小时
     * @param now 注入时钟便于测试；默认当前 UTC 时间
     */
    fun presignGet(
        config: S3Config,
        path: String,
        expiresSeconds: Long = 3600,
        now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): String {
        val dateStamp = now.format(dateFormatter)
        val amzDate = now.format(timestampFormatter)
        val credentialScope = "$dateStamp/${config.region}/$SERVICE/aws4_request"

        val host = config.host
        val hostValue = when {
            config.pathStyle -> host
            host.startsWith("${config.bucket}.") -> host
            else -> "${config.bucket}.$host"
        }
        val canonicalUri = (if (config.pathStyle) "/${config.bucket}$path" else path)
            .let { if (it.isEmpty()) "/" else it }

        val queryParams = mapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${config.accessKeyId}/$credentialScope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        val canonicalQueryString = queryParams.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }

        val canonicalRequest = buildString {
            appendLine("GET")
            appendLine(canonicalUri.urlEncodePath())
            appendLine(canonicalQueryString)
            append("host:$hostValue\n")
            appendLine()
            appendLine("host")
            append(UNSIGNED_PAYLOAD)
        }

        val stringToSign = buildString {
            appendLine(ALGORITHM)
            appendLine(amzDate)
            appendLine(credentialScope)
            append(canonicalRequest.sha256Hex())
        }

        val signingKey = getSignatureKey(config.secretAccessKey, dateStamp, config.region, SERVICE)
        val signature = hmacSha256(signingKey, stringToSign).toHexString()

        return buildString {
            append(if (config.isHttps) "https://" else "http://")
            append(hostValue)
            append(canonicalUri.urlEncodePath())
            append("?")
            append(canonicalQueryString)
            append("&X-Amz-Signature=")
            append(signature)
        }
    }

    private fun getSignatureKey(
        key: String,
        dateStamp: String,
        region: String,
        service: String
    ): ByteArray {
        val kDate = hmacSha256("AWS4$key".toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(this).toHexString()
    }

    private fun String.sha256Hex(): String {
        return this.toByteArray(Charsets.UTF_8).sha256Hex()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun String.urlEncodePath(): String {
        return split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) segment else segment.urlEncode()
        }
    }
}
