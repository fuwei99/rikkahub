package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/**
 * 远程工具调用（HTTP）的上下文。
 *
 * 关键矛盾：**工具原本是给「某个对话里的模型」用的，而 HTTP 侧根本没有对话。**
 * 大部分工具不在乎（查屏幕时间、弹提示），但 `supervision_admin` 需要知道
 * 「申诉落到哪个会话」「以哪个助手的身份」。所以显式开这个口子，而不是
 * 偷偷编一个假会话 —— 编假会话会让申诉投进黑洞，事后没人查得出来。
 */
data class RemoteToolContext(
    /** 发起方会话：申诉材料投到这里。缺省时见 [RemoteToolRegistry.resolveInitiator]。 */
    val conversationId: Uuid? = null,
    /** 以哪个助手的身份调用。缺省 = 监督配置里的守门员助手。 */
    val assistantId: Uuid? = null,
)

/** `GET /api/tools` 的清单条目。 */
@Serializable
data class RemoteToolDescriptor(
    val name: String,
    val description: String,
    val group: String,
    /** 工具的 JSON Schema；null = 无参数。 */
    val parameters: JsonElement? = null,
    /** true = 必须由调用方补 `context`，否则调用会失败。 */
    val needsContext: Boolean = false,
)

/** 一次远程调用的结果。 */
data class RemoteToolCallResult(
    val ok: Boolean,
    val text: String,
    val durationMs: Long,
)

private const val GROUP_SUPERVISION = "supervision"
private const val GROUP_SCREEN_TIME = "screen_time"
private const val GROUP_NOTIFY = "notify"
private const val GROUP_DEVICE = "device"

private const val ACTION_LOCK_CONVERSATION = "lock_conversation"
private const val ACTION_UNLOCK_CONVERSATION = "unlock_conversation"

/**
 * 远程可调工具的注册表（2026-09-19）。
 *
 * ## 为什么要有「清单 + 调用」两条路
 *
 * 调用方（对端设备上的脚本 / workspace shell）不可能硬编码每个工具的参数结构，
 * 那样每加一个工具就得改两边。所以先 `GET /api/tools` 拿 schema，再
 * `POST /api/tools/call` 发 —— 加工具只要往这里注册，调用方零改动。
 *
 * ## 为什么要白名单，不能「把 LocalTools 全端出去」
 *
 * 有些工具**放到 HTTP 上会出事**：
 * - `ask_user` —— 它卡的是整条生成，HTTP 侧没有生成可卡，答案也无处投递
 * - `javascript_engine` —— 任意代码执行，不该由一个 Bearer 就放行
 * - `subagent` / `inbox` / `send` —— 会凭空开对话、往信箱塞消息，语义依赖会话
 * - `tool_manage` —— 能改别的工具的开关，等于把闸门本身交出去
 * - `image_generation` —— 依赖 provider / 文件管理器，且烧钱
 *
 * 所以这里是**显式白名单**，不是黑名单。加新工具要过一遍「它放到局域网上
 * 被 token 持有人随便调，会不会出事」这个问题。
 *
 * ## supervision_admin 的特殊处理
 *
 * 它不在 [LocalTools] 里 —— `ChatService` 按「会话 + 助手身份」双重门现建，
 * 不满足条件连 Tool 对象都不存在。HTTP 这边没有会话，所以：
 * - `assistant_id` 缺省取监督配置的守门员（`unlockGrantorAssistantId`），
 *   没有配守门员 = 这个工具在 HTTP 上也不可用
 * - `conversation_id`（申诉落点）缺省取**加锁目标本身** —— 会话被锁，
 *   申诉落回这个会话，语义是自洽的；路径锁必须显式给，给不出就拒绝
 *
 * 注意这**没有绕过** `buildSupervisionAdminTool` 内部的身份闸：如果调用方
 * 显式传了一个非守门员的 `assistant_id`，那个函数照样返回 null。
 */
class RemoteToolRegistry(
    private val localTools: LocalTools,
    private val settingsStore: SettingsStore,
    private val settingsJsonExchange: SettingsJsonExchange,
    private val lockCoordinator: SupervisionLockCoordinator,
) {
    /**
     * 白名单里的静态工具（构造一次就够，不依赖调用上下文）。
     *
     * 用 `associateBy { it.name }` 而不是手写 map：工具改名字时这里自动跟上，
     * 不会出现「注册表写着 A，实际工具叫 B」的静默失联。
     */
    private val staticTools: Map<String, Tool> by lazy {
        listOf(
            localTools.screenTimeTool,
            localTools.notifyToastTool,
            localTools.timeTool,
            localTools.clipboardTool,
            localTools.notificationTool,
        ).associateBy { it.name }
    }

    /** 列出所有远程可调工具（含 supervision，只要守门员配了）。 */
    fun list(): List<RemoteToolDescriptor> {
        val out = mutableListOf<RemoteToolDescriptor>()

        staticTools.values.sortedBy { it.name }.forEach { tool ->
            out += RemoteToolDescriptor(
                name = tool.name,
                description = tool.description,
                group = groupOf(tool.name),
                parameters = tool.parameters()?.toJsonSchema(),
                needsContext = false,
            )
        }

        buildSupervisionTool(
            ctx = RemoteToolContext(conversationId = LIST_PROBE_CONVERSATION),
            arguments = JsonObject(emptyMap()),
        )?.let { tool ->
            out += RemoteToolDescriptor(
                name = tool.name,
                description = tool.description,
                group = GROUP_SUPERVISION,
                parameters = tool.parameters()?.toJsonSchema(),
                // 路径锁拿不到默认申诉落点，调用方必须自己给
                needsContext = true,
            )
        }

        return out
    }

    /**
     * 解析并执行一个工具。
     *
     * @throws IllegalArgumentException 工具不在白名单、或缺少必要上下文。
     *   路由层把它转成 400，别让它冒成 500 —— 那是调用方的输入问题。
     */
    suspend fun call(
        name: String,
        arguments: JsonElement,
        ctx: RemoteToolContext,
    ): RemoteToolCallResult {
        val tool = resolveTool(name, arguments, ctx)
            ?: throw IllegalArgumentException(describeUnavailable(name))

        val startedAt = System.currentTimeMillis()
        val parts = tool.execute(arguments)
        val elapsed = System.currentTimeMillis() - startedAt

        return RemoteToolCallResult(
            ok = true,
            text = parts.joinToString("\n") { it.textOrEmpty() },
            durationMs = elapsed,
        )
    }

    private fun resolveTool(
        name: String,
        arguments: JsonElement,
        ctx: RemoteToolContext,
    ): Tool? {
        staticTools[name]?.let { return it }
        if (name == SUPERVISION_ADMIN_TOOL_NAME) {
            return buildSupervisionTool(ctx, arguments)
        }
        return null
    }

    private fun buildSupervisionTool(ctx: RemoteToolContext, arguments: JsonElement): Tool? {
        val supervision = settingsStore.settingsFlow.value.supervision
        // 缺省身份 = 守门员。没配守门员 = 这台机器压根没开监督，工具不存在。
        val assistantId = ctx.assistantId ?: supervision.unlockGrantorAssistantId ?: return null
        val initiator = resolveInitiator(ctx, arguments) ?: return null

        return buildSupervisionAdminTool(
            settingsStore = settingsStore,
            settingsJsonExchange = settingsJsonExchange,
            lockCoordinator = lockCoordinator,
            conversationId = initiator,
            assistantId = assistantId,
            scheduleTemplateId = null,
        )
    }

    /**
     * 申诉落点（发起方会话）怎么定：
     * 1. 调用方显式给了 `context.conversation_id` → 用它
     * 2. 否则，如果这是会话锁 → 用**被锁的那个会话**（申诉落回被锁会话，自洽）
     * 3. 其余情况（路径锁等）→ 给不出，拒绝。宁可报错，也不把申诉投进黑洞。
     */
    private fun resolveInitiator(ctx: RemoteToolContext, arguments: JsonElement): Uuid? {
        ctx.conversationId?.let { return it }

        val action = arguments.jsonObject["action"]
            ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (action != ACTION_LOCK_CONVERSATION && action != ACTION_UNLOCK_CONVERSATION) {
            return null
        }

        return arguments.jsonObject["conversation_id"]
            ?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }

    private fun describeUnavailable(name: String): String {
        val known = (staticTools.keys + SUPERVISION_ADMIN_TOOL_NAME).sorted().joinToString(", ")
        return "unknown or unavailable tool: '$name'. available: $known. " +
            "注意 supervision_admin 在未配置守门员助手时不可用。"
    }

    private fun groupOf(name: String): String = when (name) {
        "get_screen_time" -> GROUP_SCREEN_TIME
        "notify_toast" -> GROUP_NOTIFY
        else -> GROUP_DEVICE
    }

    private companion object {
        /**
         * `list()` 只是要拿 schema，不真执行。supervision 工具在**构造期**用不到
         * conversationId（只有 execute 里用），所以这里塞个占位值即可 —— 但绝不能
         * 塞 null 后忘了处理，否则工具会从清单里静默消失。
         */
        val LIST_PROBE_CONVERSATION: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")
    }
}

/** 把工具的 InputSchema 转成标准 JSON Schema，供外部调用方自描述。 */
private fun InputSchema.toJsonSchema(): JsonElement = when (this) {
    is InputSchema.Obj -> buildJsonObject {
        put("type", "object")
        put("properties", properties)
        required?.let { req ->
            put(
                "required",
                buildJsonArray { req.forEach { add(it) } },
            )
        }
    }
}

private fun UIMessagePart.textOrEmpty(): String = when (this) {
    is UIMessagePart.Text -> text
    else -> ""
}
