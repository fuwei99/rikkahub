package me.rerere.rikkahub.data.ai.schedule

import kotlinx.serialization.Serializable

/**
 * 定时任务会话的「不可破坏」保护（2026-08-21）。
 *
 * 病根：监督查岗这类任务的价值全在**连续性**——用户（也就是被监督的人）
 * 只要在抽屉里点一下删除 / 停止生成 / 分叉 / 重 roll，就能把正在执行的查岗
 * 干掉，而且干掉得干干净净：没人拦、没记录、下一次触发还会因为 DB 里残留的
 * running 状态被跳过。等于「被监督者拥有解除监督的按钮」，制度作废。
 *
 * 所以这里给模板一个声明式的保护开关组。**默认 [enabled] = false**，
 * 所有普通定时任务（辩论赛、代码重构等）行为完全不变；只有显式在模板 JSON 里
 * 写 `"protection": { "enabled": true }` 的任务（监督查岗）才进保护。
 *
 * 注意：这不是安全沙箱（同 PLAN_SUPERVISION_LOCK 的口径）——清数据 / 卸载重装
 * 照样能绕。它拦的是「冲动之下顺手一点」，摩擦成本足够就够了。
 */
@Serializable
data class ScheduleProtection(
    /** 总开关：false = 完全不保护（默认，其他定时任务不受影响）。 */
    val enabled: Boolean = false,

    /** 禁止用户手动停止生成（点停止按钮 / Web API stop）。 */
    val forbidCancel: Boolean = true,

    /** 禁止从该会话消息处创建分支（fork）。 */
    val forbidFork: Boolean = true,

    /** 禁止重新生成（重 roll）任意消息。 */
    val forbidRegenerate: Boolean = true,

    /** 禁止删除该会话里的单条消息记录。 */
    val forbidDeleteMessage: Boolean = true,

    /** 禁止编辑该会话里的消息（编辑 = 变相改查岗结论）。 */
    val forbidEditMessage: Boolean = true,

    /** 禁止删除整条对话。 */
    val forbidDeleteConversation: Boolean = true,

    /** 禁止把对话移动到别的文件夹 / 别的助手（移出「监督」文件夹等于藏起来）。 */
    val forbidMove: Boolean = true,

    /** 提示文案前缀，UI 报错时展示（可在模板里改成自己的口吻）。 */
    val notice: String = "该定时任务对话受保护",
) {
    /** 某个动作是否被禁止。 */
    fun forbids(action: ScheduleAction): Boolean {
        if (!enabled) return false
        return when (action) {
            ScheduleAction.CANCEL -> forbidCancel
            ScheduleAction.FORK -> forbidFork
            ScheduleAction.REGENERATE -> forbidRegenerate
            ScheduleAction.DELETE_MESSAGE -> forbidDeleteMessage
            ScheduleAction.EDIT_MESSAGE -> forbidEditMessage
            ScheduleAction.DELETE_CONVERSATION -> forbidDeleteConversation
            ScheduleAction.MOVE -> forbidMove
        }
    }

    /** 拦截文案（null = 放行）。 */
    fun reasonFor(action: ScheduleAction): String? =
        if (forbids(action)) "$notice：禁止${action.label}。要改请去模板 JSON 关掉对应 protection 开关。" else null

    companion object {
        /** 监督类任务的推荐配置：全锁。 */
        val FULL = ScheduleProtection(enabled = true)
    }
}

/** 受保护会话上可被拦截的用户动作。 */
enum class ScheduleAction(val label: String) {
    CANCEL("停止生成"),
    FORK("创建分支"),
    REGENERATE("重新生成"),
    DELETE_MESSAGE("删除消息"),
    EDIT_MESSAGE("编辑消息"),
    DELETE_CONVERSATION("删除对话"),
    MOVE("移动对话"),
}
