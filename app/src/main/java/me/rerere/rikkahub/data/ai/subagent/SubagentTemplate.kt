package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
import kotlin.uuid.Uuid

@Serializable
data class SubagentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val systemPrompt: String? = null,
    val defaultTools: List<String> = listOf("workspace"),
    val maxSteps: Int = 50,
    val timeoutMinutes: Int = 15,
    val recommendedModel: ModelOverride? = null,

    // ---- 「对话即 Agent」扩展字段（方案 2026-08-07 §4.6，全部带默认值 → 旧模板文件零改动可用）----

    /** 子 agent 可用的本地工具（LocalToolOption serialName），空 = 不给本地工具 */
    val allowedLocalTools: List<String> = emptyList(),
    /** 子 agent 可用的 workspace 工具，空 = 跟随父对话白名单 */
    val allowedWorkspaceTools: List<String> = emptyList(),
    /** 子 agent 可用的 MCP 工具（"serverId/toolName"），空 = 不给 MCP */
    val allowedMcpTools: List<String> = emptyList(),
    /** auto | parent | user；危险工具永远强制回落 user（硬名单在 AgentApprovalMode） */
    val approvalMode: String = "parent",
    /** auto | manual：auto 跑完自动回报给父对话 */
    val reportMode: String = "auto",
    /** conversation | silent：silent 走旧黑盒 SubagentRunner（不落库、不可见） */
    val visibility: String = "conversation",
    /** 子 agent 上下文窗口消息数，0 = 不限制 */
    val contextMessageSize: Int = 0,
    /** 单会话 token 预算 */
    val maxTotalTokens: Int = 128_000,
    /**
     * 自动压缩覆盖（2026-08-21）：spawn 时写进 `Conversation.autoCompressOverride`。
     *
     * 长跑子 agent（多轮工具调用刷屏）靠它自动折叠历史，而不是撞到
     * `maxTotalTokens` 直接终止。null = 跟随助手 autoCompress（默认关）。
     */
    val autoCompress: AutoCompressOverride? = null,
    /** 允许与 peers 平级互发消息 */
    val allowPeerMessaging: Boolean = false,
    /**
     * Settings 里 Model 的 UUID。
     *
     * `Conversation.modelId` 是 `Uuid?` 指向 Settings 中的 Model，
     * **不能**把 [recommendedModel]（providerId/modelId 字符串）直接当它用；
     * null = 跟随父对话模型。
     */
    val modelUuid: Uuid? = null,

    // ---- 声明式权限字段（收敛设计 §7.2，落地 plan Step 5；全部带默认值，旧模板零改动）----

    /** 派生权：该 agent 能否再派子 agent（深度上限仍由 AgentLimits.MAX_DEPTH 兜底） */
    val canSpawn: Boolean = false,
    /** 派生预算：该 agent 最多同时活跃几个子 agent（0 = 不允许；与 canSpawn 取严） */
    val spawnBudget: Int = 0,
    /**
     * 打断权（收敛设计 §7.2）：none | parent | peers | all。
     * 本期只建字段与总闸降级，CALL 抢占的接线在期二；总闸关时强制 none。
     */
    val interruptRight: String = "none",
    /** 通知通道：app | silent（预留 wechat，收敛设计 §9）；总闸关时 wechat→app */
    val notificationChannel: String = "app",
)

@Serializable
data class ModelOverride(
    val providerName: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String? = null, // "off", "on", "auto", "low", "medium", "high", "max"
)
