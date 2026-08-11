package me.rerere.rikkahub.utils

import android.os.Build

/**
 * 本机机型名，形如 "HUAWEI DBR-W00"。
 *
 * 单一真源：`{{device_info}}` 占位符（PlaceholderTransformer）与 user 消息落库的
 * [me.rerere.ai.ui.UIMessage.device] 都用它，避免两处各写一份 Build 拼接后漂移
 * ——一旦漂移，模板里显示的设备名和历史工具输出的设备名就对不上，查岗会误判。
 */
fun currentDeviceInfo(): String = listOf(Build.BRAND, Build.MODEL)
    .mapNotNull { it?.trim()?.takeIf { s -> s.isNotBlank() } }
    .joinToString(" ")
    .ifBlank { "Android" }
