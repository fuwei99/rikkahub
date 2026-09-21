package me.rerere.rikkahub.data.sync.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 存储后端配置（多后端抽象 · Step 1）。
 *
 * 把「同步数据存哪儿」从代码里抽出来变成可插拔配置。当前支持：
 * - [D1]       —— Cloudflare D1（现状，SQL 直连 / 可选 sync-proxy 加速）
 * - [Supabase] —— Supabase PostgREST（RPC 直连，Worker 仅作可选加速）
 *
 * ## 安全约定（与既有 `d1Config` 一致，不引入新风险类别）
 *
 * 本对象**含设备机密**（`apiToken` / `serviceKey`），参与 settings 序列化仅为本地存储方便；
 * 上推云端前必须整体剔除（device-local 段）。`id` 是稳定身份，生成后不可变，
 * 供 `conversations.storage` 之类的列做会话级路由。
 *
 * ## 为什么是 sealed interface 而不是 enum
 *
 * 每种后端的字段集合完全不同（D1 要 accountId+databaseId+token，Supabase 要 projectUrl+key），
 * 用 enum + 一堆可空字段会在调用点到处写 `!!`。sealed 让每个分支自带自己的字段，
 * 且 `when` 分支可被编译器穷尽检查 —— 以后加后端时，漏改的地方会直接编译报错。
 */
@Serializable
sealed interface StorageBackendConfig {

    /** 稳定身份，写入 `conversations.storage` 做会话级路由；生成后不可变 */
    val id: String

    /** 用户起的备注名，如"主库"/"备胎" */
    val alias: String

    /** 总开关。false = 不接收新推送（读取路径不受影响） */
    val enabled: Boolean

    /** 字段是否填齐（不含 [enabled]）。供设置页「测试连接」按钮判断，不要求先开启 */
    val isConfigured: Boolean

    /**
     * 本后端负责的时间段起点（epoch ms，**闭**）。`null` = 不限，即 -∞。
     *
     * ## 为什么按「会话建立时间」分片，而不是按写入时间或滚动窗口
     *
     * 判据必须是**数据的固有属性**，不能是外部状态：
     * - 按写入时间分 → 一条老会话今天被改就得跨库搬家，于是要迁移管道
     * - 存 `conversations.storage` 字段分 → 字段会漂，且改归属要改数据
     * - **按 `createAt` 分 → 纯函数，随时可重算，零迁移、零字段**
     *
     * 代价：一个老而活跃的会话（Schedule Agent 产线）会被钉在老库上、照样烧它的写额度。
     * 这类会话要靠显式「移籍」单独改判，分片规则自动解决不了。
     */
    val rangeStart: Long?

    /** 本后端负责的时间段终点（epoch ms，**开**）。`null` = 不限，即 +∞。区间一律 `[start, end)` */
    val rangeEnd: Long?

    /** 类型名，用于日志与诊断。`when` 作用在 sealed 上，加后端漏补会编译报错 */
    val typeName: String
        get() = when (this) {
            is D1 -> "d1"
            is Supabase -> "supabase"
        }

    /**
     * Cloudflare D1。
     *
     * [proxyUrl] / [proxySecret] 是**可选加速通道**：配了就走 Worker 批量端点
     * （实测 23.4s → 1.0s），不配就直连 REST。代理挂了自动回落，只慢不错。
     */
    @Serializable
    @SerialName("d1")
    data class D1(
        override val id: String = Uuid.random().toString(),
        override val alias: String = "",
        override val enabled: Boolean = false,
        val accountId: String = "",
        val databaseId: String = "",
        val apiToken: String = "",
        val proxyUrl: String = "",
        val proxySecret: String = "",
        /** 代理不可用时是否回落直连 REST。默认 true（可用性优先） */
        val proxyFallbackToRest: Boolean = true,
        /** 单批语句上限，需与 Worker 侧 MAX_STATEMENTS 对齐 */
        val proxyMaxBatchSize: Int = 100,
        /** 代理请求超时（毫秒） */
        val proxyTimeoutMs: Long = 20_000L,
        override val rangeStart: Long? = null,
        override val rangeEnd: Long? = null,
    ) : StorageBackendConfig {
        override val isConfigured: Boolean
            get() = accountId.isNotBlank() && databaseId.isNotBlank() && apiToken.isNotBlank()

        val proxyConfigured: Boolean
            get() = proxyUrl.isNotBlank() && proxySecret.isNotBlank()
    }

    /**
     * Supabase（PostgREST）。
     *
     * [serviceKey] 用 `service_role` / `sb_secret_*`，因为 `anon` 在这套 schema 里
     * **零权限**（表只 GRANT 给 service_role，实测 anon 读表返 `42501 permission denied`）。
     *
     * [proxyUrl] / [proxySecret] 同样是**可选加速通道**：主路径是客户端直连
     * `https://<ref>.supabase.co/rest/v1/`；配了代理则由 Worker 终结 TLS、
     * 复用连接（实测 TLS 握手占 1~2s，是真正的瓶颈）。
     */
    @Serializable
    @SerialName("supabase")
    data class Supabase(
        override val id: String = Uuid.random().toString(),
        override val alias: String = "",
        override val enabled: Boolean = false,
        /** 形如 `https://<ref>.supabase.co`（不带尾斜杠） */
        val projectUrl: String = "",
        /** service_role JWT 或 `sb_secret_*`。**绝不进 APK 常量，只存用户本地设置** */
        val serviceKey: String = "",
        val schema: String = "public",
        val proxyUrl: String = "",
        val proxySecret: String = "",
        override val rangeStart: Long? = null,
        override val rangeEnd: Long? = null,
    ) : StorageBackendConfig {
        override val isConfigured: Boolean
            get() = projectUrl.isNotBlank() && serviceKey.isNotBlank()

        val proxyConfigured: Boolean
            get() = proxyUrl.isNotBlank() && proxySecret.isNotBlank()

        /** PostgREST 根，例如 `https://xxx.supabase.co/rest/v1` */
        val restBase: String
            get() = "${projectUrl.trimEnd('/')}/rest/v1"
    }

    companion object {
        /** 新建设置时的默认后端 */
        fun default(): StorageBackendConfig = D1()
    }
}