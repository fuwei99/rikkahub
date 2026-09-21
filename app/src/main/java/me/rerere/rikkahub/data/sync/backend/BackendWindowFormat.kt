package me.rerere.rikkahub.data.sync.backend

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 时间窗的「显示 / 解析」两个纯函数（多后端 · Step I-4）。
 *
 * 列表页与详情页都要用，所以放在这里而不是各自 `private` 一份 —— 日期格式一旦
 * 两边写歪，用户看到的时间段和实际路由结果就会不一致，而这种不一致极难发现。
 *
 * 约定：**显示用空串表示「不限」**，由 UI 层决定是显示成「不限」还是留白；
 * 解析不出结果一律返 `null`，调用方据此保留原值（不要当成清空，否则用户打错
 * 一个字符就把时间窗抹了）。
 */

/** epoch ms → `yyyy-MM-dd`（本机时区）。`null` 返空串 = 「不限」。 */
fun epochToDateText(ms: Long?): String {
    if (ms == null) return ""
    return runCatching {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrDefault("")
}

/** `yyyy-MM-dd` → **当地零点**的 epoch ms。解析不了返 `null`。 */
fun parseDateToEpoch(text: String): Long? = runCatching {
    LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()
