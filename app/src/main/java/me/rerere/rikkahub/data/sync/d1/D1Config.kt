package me.rerere.rikkahub.data.sync.d1

import kotlinx.serialization.Serializable

/**
 * Cloudflare D1 连接配置。
 *
 * 安全约定：apiToken 属于设备机密，参与 settings 序列化仅为本地存储方便；
 * 云端同步（P1）上推 settings 时必须将本对象剔除（device-local 段）。
 */
@Serializable
data class D1Config(
    /** 总开关：未开启则一切云端同步逻辑跳过 */
    val enabled: Boolean = false,
    /** Cloudflare Account ID */
    val accountId: String = "",
    /** D1 Database ID（uuid） */
    val databaseId: String = "",
    /** 作用域 API Token（仅需 D1:edit 权限） */
    val apiToken: String = "",
) {
    /** 是否已填写连接 D1 所需的三项凭据；用于“测试连接”，不要求先开启同步。 */
    val hasRequiredFields: Boolean
        get() = accountId.isNotBlank() &&
            databaseId.isNotBlank() &&
            apiToken.isNotBlank()

    val isConfigured: Boolean
        get() = enabled && hasRequiredFields

    /** REST 端点根（query 与 raw 两个子路径） */
    fun endpoint(action: String): String =
        "https://api.cloudflare.com/client/v4/accounts/$accountId/d1/database/$databaseId/$action"
}
