package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 请求日志设置（日志设置页「请求日志」板块）。
 *
 * 把实际发给 LLM 的整条 HTTP 交互落盘到 `<filesDir>/logs/ai_wire.log`
 * （随 /rikkahub-data 挂载可直接读取），用于排查「发出去的 payload 到底长什么样」
 * 这类问题——例如图片是以 URL 还是 base64 形式发送、多模态 part 位置是否漂移。
 *
 * 与 Logging.recentLogs（内存 100 条、仅供 UI 展示）不同，这里是**完整落盘**：
 * - [enabled]：总开关，默认关（payload 含密钥与全量图片数据，不能默认写盘）；
 * - [maxAgeHours]：超过 N 小时的日志自动清除，默认 1 小时——排查用，不留档；
 * - [maxBodyChars]：单条 body 截断上限，防 base64 图片把日志撑爆；
 * - [includeResponseBody]：是否记录响应体（流式响应会拼接完整 SSE，量较大）。
 */
@Serializable
data class RequestLogSettings(
    /** 落盘总开关（默认关闭：payload 含 Authorization 与图片数据） */
    val enabled: Boolean = false,
    /** 超过 N 小时的日志文件自动清除，默认仅留 1 小时 */
    val maxAgeHours: Int = 1,
    /** 单条 request/response body 截断上限（字符） */
    val maxBodyChars: Int = 200_000,
    /** 是否记录响应体 */
    val includeResponseBody: Boolean = true,
) {
    fun sanitized(): RequestLogSettings = copy(
        maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30),
        maxBodyChars = maxBodyChars.coerceIn(1_000, 5_000_000),
    )
}
