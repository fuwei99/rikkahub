# Plan: 专注监督锁（Supervision Lock / Anti-Vibe-Coding）

> 状态：设计稿，未开工。
>
> 目标：在用户自设的时间段内把 Rikkahub 锁成「只能学习」——
> 只允许打开白名单里的学习助手；禁止新建 / 复制 / 导入助手；
> 锁定学习助手的 system prompt 与工具配置；按黑名单 / 白名单限制
> 本地工具、工作区工具、MCP 工具，禁止添加新 MCP；
> 监督生效期间**只允许加强，不允许减弱**。

---

## 0. 现状梳理（已核实的代码事实）

| 主题 | 位置 | 与本特性的关系 |
|---|---|---|
| 设置单一真源 | `data/datastore/PreferencesStore.kt` — `class SettingsStore.update(settings: Settings)`（L689） | **所有 settings 写操作的唯一漏斗**：UI/VM、MCP OAuth、Skill 管理、备份导入、云同步下拉最终都走它。监督闸门放这里覆盖面最大 |
| `Settings` 数据类 | 同文件 L1062 | 所有配置都在这里序列化进 DataStore（外部专属目录 `files/datastore/settings.preferences_pb`） |
| 助手定义 | `data/model/Assistant.kt` | 含 `systemPrompt / localTools / mcpServers / enabledSkills / enableWebSearch / workspaceId` 等字段 |
| 当前选中助手 | `Settings.assistantId`（已被 `SyncSettingsFilter` 标为设备本地，不云同步） | 抽屉切助手最终调 `settingsStore.updateAssistant(id)` → 只写这个字段 |
| 助手增删 | `ui/pages/assistant/AssistantVM.addAssistant / removeAssistant / copyAssistant`；`BackupVM.restoreAssistantPackage` 等 | 需要在监督期屏蔽「新建 / 复制 / 导入」三个入口 |
| 工具组装 | `service/ChatService.kt` L868–1000（`buildList { ... }`）：`localTools.getTools(...)` / `createWorkspaceToolsIfReady(...)` / `mcpManager.getAllAvailableTools()` | 运行时按「助手配置 ∪ 会话覆盖」拼工具；监督过滤器在这里收口最自然 |
| 工具覆盖（per-conversation） | `ChatService.sendMessage` / `regenerateAtMessage` 支持 `enabledLocalTools / enabledWorkspaceTools / enabledMcpTools`，存进三个 ConcurrentHashMap（L209–211、L589–644） | 聊天页输入框有临时开关，监督期必须同样被过滤，否则成为绕过口 |
| 本地工具枚举 | `data/ai/tools/local/LocalToolOption.kt`（13 个 `data object`） | 监督白 / 黑名单直接针对这些稳定的 `@SerialName` 字符串 |
| 工作区工具名 | `data/ai/tools/WorkspaceTools.kt`：`workspace_read_file / write_file / edit_file / shell / shell_session / codex_patch / apply_patch / grep / backup` | 名字稳定；`createWorkspaceTools(enabledTools: Set<String>?)` 已支持按集合过滤 |
| MCP 工具 | `McpManager.getAllAvailableTools()` 返回 `List<Triple<serverId, serverName, tool>>`；`McpServerConfig.commonOptions.tools` 里每个工具有 `enable` | 监督层只需在最终 selected 集合上做集合运算即可 |
| 云同步过滤 | `data/sync/core/SyncSettingsFilter.kt`：`forUpload` / `mergeRemote` | 监督配置**随云同步跨设备生效**（两台设备一起锁），LWW 合并；但监督期内同步下来的"改弱"会被 Gate 自动加强回去（见 §3.6） |
| 设置 JSON 导入导出 | `data/datastore/SettingsJsonExchange.kt`：把 Settings 切片成 30+ 个 JSON 文件落到 `files/setting-json/`，可「整目录覆写」 | 最大绕过点——必须在 `importAllAndSync()` 之前过监督闸门 |
| 整包备份恢复 | `WebDavSync.restoreFromLocalFile`（zip 内含完整 `settings.json`）、S3、`BackupVM` | 同样必须过闸门；监督期应**禁止整体恢复**（会覆盖监督配置本身） |
| Web API | `web/routes/SettingsRoutes.kt`（POST `/assistant`、`/assistant/mcp` 等） | 全部经 `settingsStore.update`，自动被覆盖；监督期可额外早返回 403 |
| 路由 / 页面 | `RouteActivity.kt` 中 `sealed interface Screen` + `entryProvider`；`ui/pages/setting/SettingPage.kt` 列表入口 | 新增「专注监督」入口和页面，照抄 `SettingCommunicationPage` 等子页模式 |
| 内置助手保护 | `DEFAULT_ASSISTANTS_IDS` / `AGENTS_ASSISTANT_ID` 不可删 | 可参考此模式保护「被锁的学习助手」不被删除 |

**结论**：积木都齐。工作量集中在（1）新增监督设置数据结构与闸门，（2）在
`ChatService` 工具组装处加一层过滤，（3）助手 / MCP / 工具三个 UI 页面的
只读与按钮隐藏，（4）设置页 UI。不需要动生成内核、DB schema、迁移。

---

## 1. 需求拆解与边界

### 1.1 必须做（MVP）

1. **时间段**：按周历排，多个区间；支持每天不同；命中当前时间即「监督中」。
2. **学习助手白名单**：监督期内只能在指定助手集合里切换；其他助手在抽屉中置灰
   并显示「专注中不可用」。
3. **禁止新建 / 复制 / 导入助手**：监督期内 `+` 按钮、复制菜单项、`.rikka` 导入、
   Chatbox 导入、整包恢复中的 assistant 合并 全部禁用或失败。
4. **锁定学习助手配置**：监督期内，白名单助手的以下字段只读：
   - `systemPrompt` / `presetMessages` / `messageTemplate`
   - `localTools` / `mcpServers` / `enabledSkills` / `enableWebSearch` / `workspaceId`
   - `customHeaders` / `customBodies` / `temperature / topP / maxTokens`（防止用 header
     注入绕过后端）/ `chatModelId`（防止切到一个 coding 模型）
   - `allowConversationSystemPrompt` / `allowConversationPromptInjection`
   - 其余外观字段（头像、背景、标签、名称）允许改。
5. **工具管控（黑名单 *或* 白名单，用户自选）**：三档——
   - 本地工具（`LocalToolOption`）
   - 工作区工具（`workspace_*`）
   - MCP 工具（按 `serverId/toolName` 字符串）
   - 内置搜索 / 记忆 / 生图工具由助手开关控制，第 4 点已经锁住，不单独管。
6. **MCP 总闸**：监督期禁止新增 / 删除 MCP server，禁止启用此前禁用的 MCP 工具，
   禁止安装新 MCP（UI 按钮、OAuth 回调、Skill 自动装 MCP 三条路径都拦）。
7. **监督设置本身只读 + 可加强**：监督期 `SupervisionSettings` 字段不允许改弱；
   具体「加强」语义见 §3.3。

### 1.2 非目标（本版不做）

- **真正的防篡改**：不做 root/重装级防破解。用户若想 vibe coding，
  「清除应用数据」「卸载重装」「直接删 `datastore/settings.preferences_pb`」
  仍能绕过。这是一个**自律**工具，不是安全沙箱——目标是提高即时冲动的摩擦成本，
  配合你已装的「不做手机控」足够。
- PIN / 密码解锁：自律场景下，密码只会被自己重置；用「只许加强」+「时段外可改」
  的规则更省心。以后如需可加可选 PIN。
- 系统级防截屏 / 防分屏：不需要。
- 监督对话内容（例如检测用户在写代码）：不做语义审查；我们只从工具层把
  「能写代码」这件事禁掉。

---

## 2. 数据模型

新增文件 `app/src/main/java/me/rerere/rikkahub/data/model/SupervisionSettings.kt`：

```kotlin
@Serializable
data class SupervisionSettings(
    val enabled: Boolean = false,
    /** 周历时间段；命中任意一个即「监督中」。 */
    val schedules: List<SupervisionSchedule> = emptyList(),

    /** 学习助手白名单（监督期只允许这些助手）。空列表 = 不限制（向后兼容）。 */
    val allowedAssistantIds: Set<Uuid> = emptySet(),

    /** 本地工具过滤模式 + 集合（存 @SerialName 字符串）。 */
    val localToolFilter: ToolFilter = ToolFilter.DEFAULT,
    /** 工作区工具过滤，同上。 */
    val workspaceToolFilter: ToolFilter = ToolFilter.DEFAULT,
    /** MCP 工具过滤，集合元素为 "${serverId}/${toolName}"。 */
    val mcpToolFilter: ToolFilter = ToolFilter.DEFAULT,

    /** 监督期是否允许添加 / 启用新 MCP（默认 true = 禁止加强外的任何变更）。 */
    val lockMcpServers: Boolean = true,

    /**
     * 紧急冷却：在监督期内请求解锁后，需要等待多少分钟才能真正生效。
     * 0 = 不允许中途解锁（默认）；>0 = 「我点了解锁，N 分钟后才解锁」，
     * 给冲动一个缓冲。N 分钟内可取消。
     */
    val cooldownMinutes: Int = 0,
    /** 冷却到期的 epoch millis；0 表示无待处理解锁。 */
    val pendingUnlockAt: Long = 0L,
    /** LWW 时间戳（云同步合并用，见 §3.6）。 */
    val updatedAt: Long = 0L,
)

@Serializable
data class SupervisionSchedule(
    val id: Uuid = Uuid.random(),
    /** 1..7 对应周一..周日，与 java.time.DayOfWeek 对齐。 */
    val daysOfWeek: Set<Int> = emptySet(),
    /** 分钟数，0..1440。 */
    val startMinute: Int = 0,
    val endMinute: Int = 0,
)

@Serializable
data class ToolFilter(
    val mode: Mode = Mode.BLACKLIST,
    /** 黑名单时为「禁用」集合；白名单时为「唯一允许」集合。 */
    val items: Set<String> = emptySet(),
) {
    @Serializable
    enum class Mode { BLACKLIST, WHITELIST }

    fun allows(toolName: String): Boolean = when (mode) {
        Mode.BLACKLIST -> toolName !in items
        Mode.WHITELIST -> toolName in items
    }

    companion object {
        val DEFAULT = ToolFilter()
    }
}
```

在 `Settings` 末尾追加：

```kotlin
val supervision: SupervisionSettings = SupervisionSettings(),
```

`Uuid` 序列化在仓库中已通过 `kotlinx.serialization` + 自定义 serializer 支持
（与 assistant 字段一致，不需要额外配置）。

### 2.1 「监督中」判定

```kotlin
fun SupervisionSettings.isActiveNow(now: Instant = Clock.System.now()): Boolean {
    if (!enabled) return false
    if (pendingUnlockAt in 1..now.toEpochMilliseconds()) return false // 冷却已到
    val dt = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val minuteOfDay = dt.hour * 60 + dt.minute
    val dow = dt.dayOfWeek.isoDayNumber // 1..7
    return schedules.any { s ->
        dow in s.daysOfWeek &&
            (if (s.startMinute <= s.endMinute) {
                minuteOfDay in s.startMinute until s.endMinute
            } else {
                // 跨夜：22:00 - 06:00
                minuteOfDay >= s.startMinute || minuteOfDay < s.endMinute
            })
    }
}
```

注意：

- 用系统本地时区。学习计划本来就是按本地时间走的。
- **判定要在每次写入 / 每次生成时实时算**，不能缓存为「启动时是否监督中」——
  跨时段必须立即生效，不然 22:00 开始的监督 22:10 还能用。
- 工具过滤处（`ChatService`）每次生成都读 `settingsStore.settingsFlow.value`，
  已经是最新值，不需要额外推送。

---

## 3. 核心闸门

### 3.1 `SettingsStore.update` 拦截

在 `PreferencesStore.kt` L689 的 `suspend fun update(settings: Settings)` 里，
在 `stampChangedListSettings(...)` 之前加一步：

```kotlin
suspend fun update(settings: Settings) {
    if (settings.init) { Log.w(TAG, "Cannot update dummy settings"); return }
    val current = settingsFlow.value

    // 监督闸门：监督时段内对传入 settings 做「只许加强」的 sanitize；
    // 若试图减弱监督配置本身 → 抛 RejectedWeakeningException（UI 会 toast）。
    val guarded = if (current.supervision.isActiveNow()) {
        supervisionGate.enforceDuringLock(current, settings)
    } else {
        settings
    }

    val nextSettings = stampChangedListSettings(
        guarded,
        stampChangedProviders(guarded, stampChangedMcpServers(guarded, guarded)),
    )
    settingsFlow.value = nextSettings
    dataStore.edit { ... }
}
```

抽出独立类 `data/datastore/SupervisionGate.kt`：

```kotlin
class SupervisionGate {
    /**
     * 监督期内的 settings 写入清洗。
     * @throws RejectedWeakeningException 当 caller 试图减弱监督配置本身
     * @return 经过清洗后允许落库的 settings
     */
    fun enforceDuringLock(old: Settings, incoming: Settings): Settings { ... }
}
```

这样所有写入路径（UI、Web API、MCP OAuth、Skill 自动更新、云同步、JSON 导入、
备份恢复）都**自动**被约束。

### 3.2 助手层清洗（保护学习助手 + 禁止新建）

```kotlin
private fun sanitizeAssistants(old: Settings, incoming: Settings): Settings {
    val oldById = old.assistants.associateBy { it.id }
    val lockedIds = old.supervision.allowedAssistantIds

    val sanitizedList = incoming.assistants.mapNotNull { new ->
        val oldA = oldById[new.id]
        when {
            // 1) 全新 id：监督期一律拒绝（挡 add/copy/import）
            oldA == null -> null

            // 2) 白名单助手：不可变字段回滚到旧值
            new.id in lockedIds -> new.copy(
                systemPrompt       = oldA.systemPrompt,
                presetMessages     = oldA.presetMessages,
                messageTemplate    = oldA.messageTemplate,
                localTools         = oldA.localTools,
                mcpServers         = oldA.mcpServers,
                enabledSkills      = oldA.enabledSkills,
                enableWebSearch    = oldA.enableWebSearch,
                workspaceId        = oldA.workspaceId,
                customHeaders      = oldA.customHeaders,
                customBodies       = oldA.customBodies,
                chatModelId        = oldA.chatModelId,
                temperature        = oldA.temperature,
                topP               = oldA.topP,
                maxTokens          = oldA.maxTokens,
                allowConversationSystemPrompt    = oldA.allowConversationSystemPrompt,
                allowConversationPromptInjection = oldA.allowConversationPromptInjection,
                // 外观/展示类字段保持 incoming（头像、名字、背景、标签）
            )

            // 3) 非白名单旧助手：保留但不允许切换到（UI 层置灰）。
            //    注意不能删——删了会让已有对话找不到 assistant。
            else -> new
        }
    }

    // 不允许切换到非白名单助手
    val safeAssistantId =
        if (sanitizedList.any { it.id == incoming.assistantId } &&
            (lockedIds.isEmpty() || incoming.assistantId in lockedIds)
        ) incoming.assistantId else old.assistantId

    return incoming.copy(assistants = sanitizedList, assistantId = safeAssistantId)
}
```

### 3.3 「只许加强」的形式化定义

对 `SupervisionSettings` 的任何字段，比较 `old.supervision` 与
`incoming.supervision`：

| 字段 | 加强方向 |
|---|---|
| `enabled` | 只允许 `false → true` |
| `schedules` | 只允许**并集**（UI 上简化为「不可删已有区间，可加新区间」） |
| `allowedAssistantIds` | 只允许缩小（`incoming ⊆ old`）——白名单越缩越严 |
| 三个 `*ToolFilter` | 黑名单：items 只许增；白名单：items 只许减；mode 允许 BLACKLIST→WHITELIST（白名单更严），不允许反向 |
| `lockMcpServers` | 只允许 `false → true` |
| `cooldownMinutes` | 只许增大 |
| `pendingUnlockAt` | 由「请求解锁」流程单独写入；不允许通过普通 update 直接清零 |

违反时抛 `RejectedWeakeningException(fieldName)`，UI 层 catch 后 toast
「监督中不可减弱设置（xxx）」。

> 设计取舍：为什么不靠 UI 隐藏按钮？因为 App 有 Web API、JSON 导入、云同步等
> 多条写入路径，UI 层不可穷尽；闸门在 `SettingsStore.update` 一层就全堵死了。
> UI 只读只是为了用户体验（告诉用户「这个改不了」），不是安全边界。

### 3.4 MCP 服务器层

监督期对 `incoming.mcpServers` 的 sanitize：

```kotlin
private fun sanitizeMcpServers(old: Settings, incoming: Settings): Settings {
    if (!old.supervision.lockMcpServers) return incoming
    val oldById = old.mcpServers.associateBy { it.id }
    val guarded = incoming.mcpServers.mapNotNull { s ->
        val oldS = oldById[s.id] ?: return@mapNotNull null       // 禁止新增
        // 禁止把原本禁用的工具重新启用；但允许把启用的关掉（加强）
        val oldToolsByName = oldS.commonOptions.tools.associateBy { it.name }
        val guardedTools = s.commonOptions.tools.map { t ->
            val wasEnabled = oldToolsByName[t.name]?.enable ?: false
            t.copy(enable = t.enable && wasEnabled) // 只许关，不许开
        }
        s.copy(commonOptions = s.commonOptions.copy(tools = guardedTools))
    }
    return incoming.copy(mcpServers = guarded)
}
```

### 3.5 备份 / 导入路径特殊处理

`SettingsJsonExchange.importAllAndSync()` 与 `WebDavSync.restoreFromLocalFile()` /
`S3Sync.restoreFromS3()` 会用外部数据整体覆盖 settings。监督期内：

- **整体恢复直接拒绝**（抛异常，UI 提示「监督中不可恢复备份」）。
  原因：备份里可能带旧版监督配置 + 一堆新助手 + 新 MCP，逐字段追平很容易漏；
  而「监督中禁止恢复备份」本身符合「只许加强」的直觉。
- `.rikka` 单助手包导入 (`BackupVM.restoreAssistantPackage`) 会新增助手，
  被 §3.2 第 1 条自然挡住（id 不存在）。
- Chatbox 导入：同理，新增的 provider/assistant 被挡；provider 因为不是监督重点
  可以放行，但 `assistants` 列表的变化被吃掉。

实现上在 `BackupVM` / `SettingsJsonExchange` 入口处提前判断
`settingsStore.settingsFlow.value.supervision.isActiveNow()` 即可早失败；
SettingsStore 闸门本身是最后一道防线。

### 3.6 云同步（跨设备监督）

**`supervision` 字段随设置一起上云同步**，不在 `SyncSettingsFilter` 里剥离。
这是用户明确要求：两台设备都要被同一套监督规则约束，避免「手机锁了就掏出平板继续 vibe coding」。

合并策略（`SyncSettingsFilter.mergeRemote`）：

- 监督配置按 LWW（last-write-wins）整体采纳云端版本即可——`SupervisionSettings`
  是单个小对象，不像 assistants/providers 那样需要逐项按 `updatedAt` 合并。
- 为支持 LWW，在 `SupervisionSettings` 里加一个 `updatedAt: Long = 0L` 字段；
  所有改动它的入口（包括 Gate 内部"加强"后的落库）都刷新时间戳。
  merge 时比较 `local.supervision.updatedAt` 与 `remote.supervision.updatedAt`，
  大的赢；相等时保本地，避免无意义闪动。

```kotlin
// SupervisionSettings
val updatedAt: Long = 0L,

// SyncSettingsFilter.mergeRemote：supervision 走 LWW
supervision = if (remote.supervision.updatedAt > local.supervision.updatedAt)
    remote.supervision else local.supervision,
```

**关键安全问题：不能让"云同步下拉"成为绕过闸门的路径。**

云同步下拉发生时，目标设备可能正处于监督时段。如果直接把远端的 `supervision`
写进 DataStore，用户可以在另一台设备上把规则改弱、手动触发一次同步，这台设备
就被解锁了——这正是跨设备同步要防的事。

所以 `SettingsStore.update` 的 Gate 必须能识别"这是云同步合并产生的写入"，并
对 supervision 字段施加**监督期内只许加强**的同款约束：

```kotlin
suspend fun update(settings: Settings, source: UpdateSource = UpdateSource.USER) {
    ...
    val current = settingsFlow.value
    val guarded = if (current.supervision.isActiveNow()) {
        supervisionGate.enforceDuringLock(
            old = current,
            incoming = if (source == UpdateSource.SYNC_PULL) {
                // 同步下来的 supervision 本身也要过"只许加强"：
                // 取 old ∪ incoming 的加强并集，弱的一侧被丢弃
                settings.copy(supervision = current.supervision.strengthenWith(settings.supervision))
            } else settings,
        )
    } else settings
    ...
}

enum class UpdateSource { USER, SYNC_PULL }
```

`strengthenWith` 按 §3.3 的规则取两个配置的"更严"版本：
schedules 取并集、allowedAssistantIds 取交集、黑名单 items 取并集、白名单 items
取交集、布尔锁取 OR、cooldownMinutes 取 max。这样无论从哪台设备改弱，同步到
正在监督的设备都会被自动加强回去；非监督时段则正常 LWW，方便你在平板上改配置
同步到手机。

调用点：`SyncEngine.kt` L1397/L1410 的两处 `settingsStore.update(merged)` 传
`UpdateSource.SYNC_PULL`，其余调用点（UI/VM/MCP OAuth/备份导入/Skill 管理）
用默认 `USER`。`update(fn: (Settings) -> Settings)` 重载同样加 source 参数，
默认 USER。

> `pendingUnlockAt` 也随同步走：在手机上点"申请解锁 N 分钟"，倒计时结束后
> 两台设备同时解锁，符合直觉（你本来就只有两台设备，没有"只解锁这一台"的需求）。

---

## 4. 运行时工具过滤（ChatService）

在 `service/ChatService.kt` L868 `tools = if (!modelSupportsTools) emptyList() else buildList { ... }`
块的末尾、`addAll(...)` 全部完成之后，加一层统一过滤：

```kotlin
}.let { rawTools ->
    val sup = settings.supervision
    if (!sup.isActiveNow()) return@let rawTools

    rawTools.filter { tool ->
        when {
            tool.name in LOCAL_TOOL_NAMES ->
                sup.localToolFilter.allows(tool.name)
            tool.name.startsWith("workspace_") ->
                sup.workspaceToolFilter.allows(tool.name)
            tool.name.startsWith("mcp__") ->
                sup.mcpToolFilter.allows(tool.mcpKey()) // "serverId/toolName"
            // 内置 image_generation 走 LocalToolOption.ImageGeneration；
            // search 由 assistant.enableWebSearch 控制，已经被锁住
            else -> true
        }
    }
}
```

细节：

- `LOCAL_TOOL_NAMES` 在 `LocalToolOption` 里加一个 `val serialName: String`
  （读取 `@SerialName` 注解，或直接手写一个 set），避免反射。
- MCP 工具在 L991 已经构造了 `key = "$serverId/${tool.name}"`，把它存进
  `Tool` 的 metadata 或直接在这里重新拼一遍即可。
- per-conversation 覆盖 map（`localToolsByConversation` 等）**也必须被再过一遍**
  ——上面的过滤是在最终 `buildList` 之后做的，覆盖值已经合并进去了，所以自动覆盖。
- 「禁止添加新 MCP」由 §3.4 挡住服务器配置；运行时 `mcpManager.getAllAvailableTools()`
  只会返回库里已有的服务器，所以不会泄漏新服务器工具。

### 4.1 视觉提示

在聊天页顶栏 / 抽屉，当 `supervision.isActiveNow()` 为 true 时：

- 顶栏助手名旁加一个小锁图标 + 「专注中」；
- 工具被过滤时，在输入框上方的临时工具开关区域显示「监督中，N 个工具被限制」；
- 不可发送纯空消息的逻辑不变。

不做：禁止用户发送「帮我写代码」这类文本——语义拦截不在本版范围。
工具被禁后，模型即便收到写代码的请求，也没有 shell / 文件写入 / patch 可用，
配合学习助手 system prompt 里的「禁止 coding」指令，vibe coding 路径就被掐断了。

---

## 5. UI

### 5.1 设置页入口

在 `ui/pages/setting/SettingPage.kt` 已有的 `CardGroup` 中加一项：

```
图标：HugeIcons.LockKey / Focus
标题：专注监督
副标题：时段内只允许学习助手，限制工具与 MCP
→ navigate(Screen.SettingSupervision)
```

在 `Screen` 与 `entryProvider` 中注册新页面，照抄
`SettingCommunicationPage` / `MemorySearchSettingsPage` 模式。

### 5.2 `SettingSupervisionPage.kt`

布局（`LazyColumn` + `CardGroup`，与其他设置子页一致）：

1. **总开关**：`enabled`
2. **时段卡片**：列出已有 schedules，每行「周一三五 22:00–07:00」+ 删除按钮；
   底部「+ 添加时段」按钮。Chip 组选星期，两个 `TimePicker` 选起止。
   监督期内：删除按钮置灰，只允许新增。
3. **学习助手白名单**：multi-select 列表（来自 `settings.assistants`）。
   监督期内只允许取消勾选（缩小集合），不允许新增。
4. **工具过滤器卡片**（三张折叠卡：本地 / 工作区 / MCP）：
   - SegmentedButton：`黑名单 / 白名单`
   - 全量工具列表，每行一个 Checkbox：
     - 本地工具：枚举 `LocalToolOption`（标题 / 描述复用 `AssistantLocalToolPage`
       的已有字符串）
     - 工作区工具：硬编码工具名 + 中文标签（read / write / edit / shell / grep /
       patch 等）
     - MCP 工具：从 `mcpManager.getAllAvailableTools()` 拉，分组按 server；
       未配置任何 MCP 时显示空状态。
   - 监督期内：黑名单 Checkbox 只许勾上（加严），白名单只许取消（加严），
     mode 切换按 §3.3 规则。
5. **MCP 总闸**：`lockMcpServers` Switch（监督期只许关→开）。
6. **紧急解锁**：
   - `cooldownMinutes` 数字输入（0 / 5 / 15 / 30 / 60）；
   - 监督期内若 >0，显示「申请解锁（N 分钟后生效）」按钮；点击后写入
     `pendingUnlockAt = now + cooldown`，倒计时显示，期间可撤销。
   - 0 时，监督期内按钮显示「监督时段内不可解锁，时段结束后自动恢复」。

所有写操作经 `vm.updateSettings(settings.copy(supervision = ...))`，
被闸门拦下时 catch `RejectedWeakeningException` 并 toast。

### 5.3 助手页（`AssistantPage.kt`）

监督期内：

- 顶部 `+` 新建按钮：隐藏 / 禁用。
- 每个助手 item 的「复制」「删除」菜单项：对**白名单助手**全部隐藏（既不能复制
  出新助手，也不能删）；对其他助手，复制隐藏，删除可以保留（删别的助手不影响
  学习，也不违反「只许加强」）。
- 点非白名单助手的「编辑」：允许进入详情页，但所有受保护字段显示为
  只读（`enabled = false`），顶部 Banner：「专注监督中，学习助手配置已锁定」。
- 抽屉切助手：非白名单助手置灰，点击 toast「专注中只允许使用学习助手」。

### 5.4 助手详情子页

`AssistantPromptPage` / `AssistantLocalToolPage` / `AssistantMcpPage` /
`AssistantExtensionsPage` / `AssistantBasicPage` / `AssistantRequestPage`

监督期 + 助手在白名单内时：

- System prompt 的 `OutlinedTextField` 设置 `enabled = false`；
- Local tool 所有 Switch `enabled = false`；
- MCP server 勾选 `enabled = false`，且不显示「添加 MCP」按钮；
- Skills、Web search、Workspace、模型选择等全部禁用。

实现方式：给 `AssistantDetailVM` 加一个 `val lockedBySupervision: Boolean`
（derived from settings.supervision + assistantId），各子页通过 VM 读，
统一把 editable 控件包一层 `Modifier.enabled(!lockedBySupervision)`。
不需要重新发明一套权限组件。

### 5.5 MCP 页（`SettingMcpPage.kt`）

监督期内：

- 「添加 MCP Server」FAB / 按钮隐藏；
- 每个 server 的「删除」「编辑 URL / 鉴权」禁用；
- 工具列表中 Switch 只许从开到关（与 §3.4 一致），不允许从关到开；
- OAuth 回调若到达（`McpOAuthCoordinator` L244），被闸门拒掉写操作后
  提示「监督中不可绑定新 MCP」。

### 5.6 备份页（`BackupPage`）

监督期内：

- 「从文件恢复」「从 WebDAV 恢复」「从 S3 恢复」「导入 `.rikka` 助手包」
  「导入 Chatbox」按钮全部置灰，subtitle 提示「专注监督中不可恢复备份」。
- 「导出」可以保留（只读操作）。

---

## 6. 与现有模式注入 / 学习模式提示词的关系

仓库里已经有 `LEARNING_MODE_PROMPT`（`data/ai/prompts/LearningMode.kt`）和
`ModeInjection` 机制。监督设置**不替代**它：

- 学习助手的 system prompt / mode injection 由用户自己维护（你的需求里也提到
  「我会在系统提示词加上禁止 coding 的提示词」）。
- 监督锁负责**防止你自己改掉那个提示词和工具**。
- 未来可以加一个一键「把 `LEARNING_MODE_PROMPT` 注入到所选白名单助手」按钮，
  但不阻塞 MVP。

---

## 7. 改动文件清单

新增：

- `app/src/main/java/me/rerere/rikkahub/data/model/SupervisionSettings.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/SupervisionGate.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSupervisionPage.kt`

修改：

- `data/datastore/PreferencesStore.kt`
  - `Settings` 加 `supervision` 字段
  - `update()` 调用 `SupervisionGate`
  - 序列化 / 反序列化新字段（跟着现有字段抄即可，kotlinx.serialization 默认值
    保证旧 PB 可读取）
- `data/sync/core/SyncSettingsFilter.kt`
  - `supervision` **不擦除**，走跨设备同步；在 `mergeRemote` 中对它按 `updatedAt` 做 LWW
- `data/datastore/SettingsJsonExchange.kt`
  - 新增 `supervision.json` 分片（随备份导出/导入；监督期导入仍按 §3.5 整体拒绝）
  - `importAllAndSync()` 监督期直接抛错
- `data/ai/tools/local/LocalToolOption.kt`
  - 加 `serialName` 或 `LOCAL_TOOL_NAMES` 常量集合
- `service/ChatService.kt`
  - L868–1000 工具 `buildList` 后追加监督过滤
  - （可选）`initializeConversation` 在监督期且新对话的 assistantId 不在白名单时
    回退到白名单第一项
- `ui/pages/setting/SettingPage.kt` — 加入口
- `RouteActivity.kt` — 注册 `Screen.SettingSupervision`
- `ui/pages/assistant/AssistantPage.kt` — 隐藏 + / 复制 / 删除
- `ui/pages/assistant/detail/AssistantDetailVM.kt` — 暴露 `lockedBySupervision`
- 六个 `Assistant*Page.kt` — 按 `lockedBySupervision` 禁用控件
- `ui/pages/setting/SettingMcpPage.kt` — 监督期只读 + 只许关
- `ui/pages/backup/BackupVM.kt` / `BackupPage` — 监督期禁止恢复类操作
- `web/routes/SettingsRoutes.kt`（可选）— 监督期写操作返回 403

不需要：

- 数据库迁移（监督配置只在 DataStore，不进 Room）
- 新权限 / 前台服务 / WorkManager（时间判定是即时的，不需要后台轮询）
- 改动 AI provider / 生成内核

---

## 8. 测试要点

因为本地没有 Android 构建环境（`$ANDROID_HOME` 为空），开发时主要靠
`./gradlew assembleDebug` 在你机器上验证。建议补的单测：

- `SupervisionGateTest`（纯 JVM 单测，可放 `app/src/test/`）
  - 非监督期：任意 settings 都能写入
  - 监督期：
    - 新建助手被丢弃
    - 白名单助手 system prompt 被回滚
    - 黑名单 items 增加 → 通过；减少 → 抛异常
    - 白名单 items 减少 → 通过；增加 → 抛异常
    - mode 黑→白允许，白→黑拒绝
    - `assistantId` 切到非白名单被回退
    - MCP server 新增被丢弃；工具 enable false→true 被回退
- `SupervisionSettingsTest`
  - `isActiveNow()` 跨夜 / 非本日 / 边界 00:00 / 23:59
  - `pendingUnlockAt` 到期前后

手工回归场景：

1. 设一个 1 分钟后开始、持续 5 分钟的时段，等它自动生效；
2. 尝试切到非学习助手 → 失败；
3. 尝试编辑学习助手 system prompt → 控件灰；
4. 尝试添加 MCP server → 按钮灰；
5. 尝试在黑名单里取消一个已禁工具 → 失败；
6. 尝试通过 Web API（`curl POST /api/settings/assistant`）改提示词 → 403/无效；
7. 尝试通过 setting-json 目录覆写 assistants.json 后点导入 → 报错；
8. 等时段结束，一切恢复可改。

---

## 9. 工作量估算

- 数据模型 + Gate + 单测：~0.5 天
- ChatService 工具过滤 + LocalToolOption 名字表：~0.5 天
- 设置页 UI（时段 + 助手多选 + 三个工具过滤器 + 冷却）：~1 天
  （MCP 工具动态列表最费事，需要按 server 分组 + 异步加载）
- 助手 / 助手详情 / MCP / 备份四处 UI 只读改造：~0.5–1 天
- 云同步 / JSON 导入 / WebDav 恢复封堵 + 联调：~0.5 天

合计 **3–4 个工作日**，难度**中等偏下**：核心难点不是算法，而是把所有写入
路径和 UI 入口梳理全。最大的风险点是漏一个写入旁路导致被绕过，所以
`SettingsStore.update` 单一闸门是这个设计的关键保险。

---

## 10. 未来增强（不在 MVP）

- 可选 PIN / 生物识别解锁（进一步防冲动）
- 监督期内屏蔽「图像生成」「翻译」等其他可能分心的页面
- 与「不做手机控」等第三方 App 通过 Intent 联动（它锁 App 时同时激活监督）
- 学习时段结束后自动出一份「今天用了哪些工具 / 哪些助手」的小结
- 检测到学习助手被用于 vibe coding（例如出现连续的 `workspace_shell` 调用）
  时给一个温和提醒或自动截断

---

## 11. 紧急解锁：守门员学习 AI 审批（已按用户要求改为 AI 守门）

> 用户需求：紧急解锁交给**指定的学习 AI**（`unlockGrantorAssistantId`，必须是白名单成员，
> 不是所有白名单助手都有此权限）。只有说服它，它调用工具发起解锁，用户最终确认后才生效。

### 11.1 配置

- `SupervisionSettings.unlockGrantorAssistantId: Uuid?`：守门员助手 id；null = 监督期完全不可解锁。
- `SupervisionSettings.cooldownMinutes`：守门员发起后到「用户可确认」的冷却分钟数。
- `SupervisionSettings.pendingUnlock: PendingUnlock?`：待处理/已生效的解锁请求。

`PendingUnlock` 状态机：

```
null ──守门员工具──▶ PENDING ──冷却结束──▶ READY ──用户确认──▶ APPROVED（本时段解锁）
                      │                   │
                      └── 用户取消/拒绝 ──┴──▶ CANCELLED
```

- 守卫点：
  - `SupervisionGate.sanitizePendingUnlock`：只有 `expiresAt` 已到才能 PENDING→READY/APPROVED，
    冷却中只能保持或取消；APPROVED 是终态；CANCELLED/REJECTED 可清空以便下次申请。
  - `isActiveAt`：只有 `status == APPROVED` 且仍处于**发起解锁的本次会话**内才返回「未监督」；
    下一次监督时段开始自动重新锁定（防止一次解锁永久生效，跨夜时段也能正确处理）。
- 云同步：pendingUnlock 随 supervision 走 LWW；approve 会盖 `updatedAt` 同步到另一台设备，
  两台设备一起解锁（符合「俩设备一起锁/一起解锁」的预期）。

### 11.2 守门员工具

新增 `data/ai/tools/local/SupervisionUnlockTool.kt`：

- 工具名：`supervision_request_unlock`
- 挂载条件（`ChatService` 组装工具时调用 `buildSupervisionUnlockTool`）：
  1. 当前处于监督时段；
  2. 当前助手 == `unlockGrantorAssistantId`（**其他助手拿不到此工具**，包括其他白名单助手）；
  3. 没有正在处理中的 PENDING/READY 请求；
- 工具描述里明确要求 AI 先拒绝/追问，只在真正紧急且时间敏感时才调用；
- `execute`：校验 reason 非空 → 写入 `pendingUnlock = PENDING`（带 requestedAt/expiresAt/reason）→
  返回提示「冷却 X 分钟后需用户在设置里确认」。
- **豁免**：该工具名不在本地工具过滤器名单内（`else -> true` 分支），
  不会被黑名单/白名单过滤掉；也天然不会被 MCP/workspace 过滤影响。

### 11.3 UI（设置页「紧急解锁」卡片）

- 冷却选择：0/5/15/30/60 分（监督期 Gate 只许增大）。
- 无 pending：显示「守门员：XXX。监督期内只有它能在对话中发起解锁请求」。
- PENDING 冷却中：显示守门员给的理由 + 「拒绝/取消」。
- PENDING 冷却结束 / READY：显示理由 + 「确认解锁（本时段内生效）」+「拒绝，保持锁定」。
- APPROVED：显示「✅ 本时段已解锁」。
- CANCELLED/REJECTED：显示「已取消/拒绝，保持锁定」。
- 另新增「解锁守门员」卡片（GrantorCard）：从白名单助手中单选守门员；
  监督期只读（Gate 也不允许监督期改派守门员）。

### 11.4 安全边界（诚实说明）

- 守门员 AI 不是真「人」，它可能被用户说服（prompt 说理）后调用工具——这正是设计意图
  （AI 当守门员，比冷冰冰的计时器更灵活，也比用户自己解锁多一道关卡）。
- 用户仍可直接改 `settings.preferences_pb` / 清除数据绕过，这是自律工具不是安全沙箱。
- 冷却机制保证即使 AI 松口，用户也要等冷却 + 手动确认，给冲动留缓冲。
