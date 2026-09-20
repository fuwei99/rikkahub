package me.rerere.rikkahub.data.sync.backend

import io.ktor.client.HttpClient

/**
 * 配置 → 后端实例（多后端抽象 · Step 1 收尾）。
 *
 * 上层只认 [StorageBackendConfig]（可序列化、能进设置页），拿到实例靠这里。
 * 加第三个后端时**只需要在这里补一个 `is` 分支** —— `when` 作用在 sealed 接口上，
 * 漏补会直接编译报错，不会静默走进 else。
 *
 * 实例是**按需现造**的（对齐 `S3Client` / `D1Client` 的既有风格）：
 * 内部只存引用，不建连接，构造开销可忽略。这样用户改完设置立刻生效，
 * 不需要任何缓存失效逻辑 —— 缓存后端实例才是 bug 温床。
 */
object StorageBackendFactory {

    fun create(config: StorageBackendConfig, httpClient: HttpClient): StorageBackend =
        when (config) {
            is StorageBackendConfig.D1 -> D1Backend(config, httpClient)
            is StorageBackendConfig.Supabase -> SupabaseBackend(config, httpClient)
        }

    fun createAll(
        configs: List<StorageBackendConfig>,
        httpClient: HttpClient,
    ): List<StorageBackend> = configs.map { create(it, httpClient) }

    fun resolve(
        configs: List<StorageBackendConfig>,
        id: String,
        httpClient: HttpClient,
    ): StorageBackend? = configs.firstOrNull { it.id == id }?.let { create(it, httpClient) }

    /**
     * 上行目标：第一个「已开启且字段填齐」的后端。
     *
     * 与 R2 多账户同款约定（`R2AccountConfig` 注释：「上传目标 = 第一个 enabled 且配齐字段的账户」）
     * —— 旧后端的**读取**路径不受影响，只有新写入会切过去。这样换后端是一次可回退的切换，
     * 而不是一次数据搬迁。
     */
    fun writeTarget(
        configs: List<StorageBackendConfig>,
        httpClient: HttpClient,
    ): StorageBackend? = configs.firstOrNull { it.enabled && it.isConfigured }
        ?.let { create(it, httpClient) }
}
