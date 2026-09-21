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
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.transformers.buildDynamicContext
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
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
    /**
     * 在哪个工作区里干活（2026-09-22）。
     *
     * HTTP 侧没有会话，也就没有「本对话绑定的工作区」。缺省时
     * [RemoteToolRegistry.resolveWorkspace] 退化成「取唯一的那个工作区」——
     * 本机通常只有一个，够用；多工作区环境请显式传。
     */
    val workspaceId: String? = null,
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
private const val GROUP_CONVERSATION = "conversation"
private const val GROUP_WORKSPACE = "workspace"

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
 * ## chat_history（2026-09-22 加入）
 *
 * 从前它不在名单里，理由是「语义依赖会话」。实测是**不需要的** —— 只读历史、
 * 无副作用，且构造时 `assistantId` / `conversationId` 都传 null，`assistant`
 * 参数的默认档自动退化成「不过滤」。对端（workspace shell / 脚本）问
 * 「最近谁在聊什么」正是主要用途，砍掉它反而逼调用方去翻数据库。
 *
 * ## workspace_* （2026-09-22 加入）
 *
 * 只有四个：`workspace_shell` / `workspace_shell_session` / `workspace_backup` /
 * `workspace_codex_patch`。目的是让**外部脚本 / 对端 agent 能直接在工作区里干活**，
 * 不用先宿主到某个对话里。
 *
 * **⚠️ 安全权重变了，必须写明**：`workspace_shell` 与 `workspace_shell_session`
 * 是**任意代码执行**（工作区 rootfs 内）。本类原有的白名单理由是「token 持有人
 * 随便调也不会出事」，这两个工具**不满足那条**。之所以还是放进来：
 * - 同一个 `shellBridgeToken` 本来就能通过 `/api/shell*` 执行命令（设备 shell），
 *   所以对**已经持有 token 的人**，这没有新增权限；
 * - 但它确实**绕开了 `shellBridgeEnabled` 那道「要不要把 shell 权限交出去」的闸**
 *   （见 [requireDeviceBridgeToken] 的注释：那道闸只管设备 shell，不管这条路）。
 * 也就是说：**开了设备桥 = 开了工作区代码执行**。要收权，就在
 * [WORKSPACE_TOOL_NAMES] 里删掉这两个，只留 backup / codex_patch。
 *
 * 构造方式与对话内一致（同一个 [createWorkspaceTools]），差别只有两点：
 * - `cwd = null`（HTTP 侧没有会话，也就没有会话级 cwd）→ 基准落到工作区配置的
 *   `relativeBase`，再兜 `/workspace`；
 * - `onSetCwd = null` → 不注册 `workspace_cwd`，没有会话可写，挂了也是摆设。
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
    private val conversationRepo: ConversationRepository,
    private val workspaceRepository: WorkspaceRepository,
) {

    /**
     * 暴露给 HTTP 的工作区工具白名单。
     *
     * 刻意**不**包含 read_file / write_file / edit_file / grep：外部调用方要读要写
     * 直接用 `workspace_shell`（cat / rg / 重定向）就够了，少挂四个就是少四份 schema。
     * 要加，往这个集合里加名字即可，其它代码自动跟上。
     */
    private val workspaceToolNames: Set<String> = setOf(
        "workspace_shell",
        "workspace_shell_session",
        "workspace_backup",
        "workspace_codex_patch",
    )
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
            chatHistoryTool,
        ).associateBy { it.name }
    }

    /**
     * `chat_history`（2026-09-22）。**无会话上下文**地构造：assistantId /
     * conversationId 传 null，于是 `assistant` 参数默认「不过滤」、
     * 「排除自身」无从谈起。
     *
     * `assistantsProvider` 是 lambda，执行时才取值 —— 助手列表在运行期会变，
     * 这里不能快照。
     */
    private val chatHistoryTool: Tool by lazy {
        createConversationTools(
            conversationRepo = conversationRepo,
            assistantId = null,
            conversationId = null,
            assistantsProvider = {
                settingsStore.settingsFlow.value.assistants.map { it.id to it.name }
            },
        ).first()
    }

    // ---------------------------------------------------------------- workspace

    /**
     * 解析「在哪个工作区里干活」。
     *
     * HTTP 侧没有会话，也就没有「本对话绑定的工作区」，所以这里只能：
     * 1. 用调用方显式给的 `context.workspace_id`；
     * 2. 否则取**第一个**工作区 —— 本机通常只有一个（如 `rikkahub-jiangfeng`），
     *    退化后依然可用；多工作区环境就别偷懒，显式传。
     * 3. 都没有 → null，工作区工具整组不可用。
     */
    private suspend fun resolveWorkspace(ctx: RemoteToolContext): WorkspaceEntity? {
        val id = ctx.workspaceId?.takeIf { it.isNotBlank() }
            ?: workspaceRepository.getAll().firstOrNull()?.id
            ?: return null
        return workspaceRepository.getById(id)
    }

    /**
     * 现造本机工作区工具。判据与 `ChatService.createWorkspaceToolsIfReady` 对齐：
     * 工作区不存在 / 没就绪 → 空列表（造出来也是空壳，调用必失败）。
     *
     * `cwd = null`：HTTP 侧没有会话级 cwd，基准落到工作区配置的 `relativeBase`，
     * 再兜 `/workspace` —— 与对话内「用户没选 cwd」时同一条路径。
     * `onSetCwd = null`：没有会话可写，`workspace_cwd` 不注册。
     */
    private suspend fun buildWorkspaceTools(ctx: RemoteToolContext): List<Tool> {
        val workspace = resolveWorkspace(ctx) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return emptyList()
        return runCatching {
            createWorkspaceTools(
                workspaceId = workspace.id,
                workspaceRepository = workspaceRepository,
                cwd = null,
                enabledTools = workspaceToolNames,
                onSetCwd = null,
            )
        }.getOrDefault(emptyList())
    }

    /**
     * `[Environment Context: ...]` 那一行（workspace / relative_base / mounts）。
     *
     * 外部调用方靠它知道：挂载了哪些目录、相对路径基准在哪、哪些可写。
     * 没有它，`GET /api/tools` 只是一堆名字，调用方只能瞎猜路径。
     *
     * 与 ChatService 往对话里注入的那条**同源**（同一个 [buildDynamicContext]）。
     */
    suspend fun environment(): String? {
        val workspace = resolveWorkspace(RemoteToolContext()) ?: return null
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return null
        val paths = runCatching { workspaceRepository.getToolConfig(workspace.id).paths }.getOrNull()
        return buildDynamicContext(workspace, cwd = null, pathsConfig = paths)
    }

    /** 列出所有远程可调工具（含 supervision，只要守门员配了）。 */
    suspend fun list(): List<RemoteToolDescriptor> {
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

        buildWorkspaceTools(RemoteToolContext()).sortedBy { it.name }.forEach { tool ->
            out += RemoteToolDescriptor(
                name = tool.name,
                description = tool.description,
                group = GROUP_WORKSPACE,
                parameters = tool.parameters()?.toJsonSchema(),
                // 工作区由服务端自己解析（显式 workspace_id > 唯一那个），调用方不必给
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

    private suspend fun resolveTool(
        name: String,
        arguments: JsonElement,
        ctx: RemoteToolContext,
    ): Tool? {
        staticTools[name]?.let { return it }
        if (name in workspaceToolNames) {
            return buildWorkspaceTools(ctx).firstOrNull { it.name == name }
        }
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
        val known = (staticTools.keys + workspaceToolNames + SUPERVISION_ADMIN_TOOL_NAME)
            .sorted().joinToString(", ")
        return "unknown or unavailable tool: '$name'. available: $known. " +
            "注意 supervision_admin 在未配置守门员助手时不可用；" +
            "workspace_* 在工作区不存在或未就绪（shellStatus != READY）时不可用。"
    }

    private fun groupOf(name: String): String = when (name) {
        "get_screen_time" -> GROUP_SCREEN_TIME
        "notify_toast" -> GROUP_NOTIFY
        "chat_history" -> GROUP_CONVERSATION
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
