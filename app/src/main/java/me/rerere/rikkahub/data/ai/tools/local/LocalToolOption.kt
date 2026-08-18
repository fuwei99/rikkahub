package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("alarm")
    data object Alarm : LocalToolOption()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : LocalToolOption()

    @Serializable
    @SerialName("subagent")
    data object Subagent : LocalToolOption()

    @Serializable
    @SerialName("notification")
    data object Notification : LocalToolOption()

    @Serializable
    @SerialName("inbox")
    data object Inbox : LocalToolOption()

    @Serializable
    @SerialName("send")
    data object Send : LocalToolOption()

    /**
     * 监督管理工具（PLAN_SUPERVISION_ADMIN_TOOL）。
     *
     * **默认关闭**，且开启后还要满足 `assistantId == supervision.unlockGrantorAssistantId`
     * 才会真正挂载（双重门：用户开闸 + 指定身份）。定时任务侧另有
     * `supervision.adminScheduleAgentIds` 白名单，那条路只允许收紧配置。
     */
    @Serializable
    @SerialName("supervision_admin")
    data object SupervisionAdmin : LocalToolOption()

    companion object {
        /**
         * 所有已知本地工具的稳定 ID（监督过滤器用来识别）。
         *
         * 注意：这里必须直接写字面量，不能引用 TimeInfo.serialName 之类的嵌套 object 字段。
         * 外层 sealed class 的 <clinit> 与嵌套 data object 的初始化存在类初始化循环，
         * release/R8 下会在伴生对象初始化时拿到尚未初始化完成的嵌套 object，触发
         * `LocalToolOption$TimeInfo.getSerialName()` on null 的启动崩溃。
         */
        val ALL_SERIAL_NAMES: Set<String> = setOf(
            "javascript_engine",
            "time_info",
            "clipboard",
            "tts",
            "ask_user",
            "screen_time",
            "calendar",
            "alarm",
            "image_generation",
            "subagent",
            "notification",
            "inbox",
            "send",
            "supervision_admin",
        )
    }
}
