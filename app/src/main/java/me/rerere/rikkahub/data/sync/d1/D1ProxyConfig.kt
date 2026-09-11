package me.rerere.rikkahub.data.sync.d1

/**
 * Sync Proxy Worker 的连接参数（值对象）。
 *
 * 真值存在 `config/sync_advanced.json`（[me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig]），
 * 由设置页和挂载盘上的 Agent 共同维护；这里只是把与 D1Client 相关的那几个字段
 * 拎出来传给客户端，避免 d1 层反向依赖 core 层。
 */
data class D1ProxyConfig(
    /** 代理总开关。关掉即回到 REST 直连，行为与接入本功能之前完全一致。 */
    val enabled: Boolean = false,
    /** Worker 根地址，例如 `https://sync-proxy.example.com`（不带尾斜杠） */
    val baseUrl: String = "",
    /** Bearer token，与 Worker 侧 `SYNC_SECRET` 一致 */
    val secret: String = "",
    /**
     * 代理不可用时是否自动回落 REST 直连。
     *
     * 默认 true（可用性优先）。关掉它意味着「宁可这轮同步失败也不要慢链路」：
     * 排查代理问题时很有用 —— 开着 fallback 的话，Worker 挂了只会表现为
     * 「同步突然变慢」，你根本不知道它已经没在工作了。
     */
    val fallbackToRest: Boolean = true,
    /** 单批语句上限，超出自动分块。需与 Worker 侧 MAX_STATEMENTS 对齐。 */
    val maxBatchSize: Int = 100,
    /** 请求超时（毫秒） */
    val timeoutMs: Long = 20_000L,
) {
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && secret.isNotBlank()

    fun endpoint(path: String): String = "${baseUrl.trimEnd('/')}/$path"

    companion object {
        val DISABLED = D1ProxyConfig()
    }
}
