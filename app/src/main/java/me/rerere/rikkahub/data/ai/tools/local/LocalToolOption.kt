package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    /** 稳定的字符串 ID，用于监督白/黑名单（见 PLAN_SUPERVISION_LOCK）。 */
    abstract val serialName: String

    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption() {
        override val serialName = "javascript_engine"
    }

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption() {
        override val serialName = "time_info"
    }

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption() {
        override val serialName = "clipboard"
    }

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption() {
        override val serialName = "tts"
    }

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption() {
        override val serialName = "ask_user"
    }

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption() {
        override val serialName = "screen_time"
    }

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption() {
        override val serialName = "calendar"
    }

    @Serializable
    @SerialName("alarm")
    data object Alarm : LocalToolOption() {
        override val serialName = "alarm"
    }

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : LocalToolOption() {
        override val serialName = "image_generation"
    }

    @Serializable
    @SerialName("subagent")
    data object Subagent : LocalToolOption() {
        override val serialName = "subagent"
    }

    @Serializable
    @SerialName("notification")
    data object Notification : LocalToolOption() {
        override val serialName = "notification"
    }

    @Serializable
    @SerialName("inbox")
    data object Inbox : LocalToolOption() {
        override val serialName = "inbox"
    }

    @Serializable
    @SerialName("send")
    data object Send : LocalToolOption() {
        override val serialName = "send"
    }

    companion object {
        /** 所有已知本地工具的稳定 ID 集合（监督过滤器用来识别）。 */
        val ALL_SERIAL_NAMES: Set<String> = setOf(
            JavascriptEngine.serialName,
            TimeInfo.serialName,
            Clipboard.serialName,
            Tts.serialName,
            AskUser.serialName,
            ScreenTime.serialName,
            Calendar.serialName,
            Alarm.serialName,
            ImageGeneration.serialName,
            Subagent.serialName,
            Notification.serialName,
            Inbox.serialName,
            Send.serialName,
        )
    }
}
