# Plan: Schedule Agents（定时任务 Agent）

> 状态：**已落地**（2026-08-16 实现，见文末「落地记录」）。
>
> 一句话：**复用 agent 子会话机制，做一个"定时器当虚拟父节点"的通用定时任务接口**——
> 模板 JSON 决定绑哪个助手 / 用什么工具 / 继承什么记忆 / 挂什么 MCP；到点由调度器往
> 该 agent 的可见对话投递一条系统消息（模拟父节点派活），AI 执行完汇报走系统通知
> （没有父对话可汇报），提前终止复用现有"提醒本人继续"机制。
>
> 与现有 subagent 完全同源，甚至更少功能（不需要 spawn 派生 / peer 平级 / 抢占）。

---

## 0. 现状梳理（已核实的代码事实）

| 主题 | 位置 | 关键点 |
|---|---|---|
| agent 子会话创建 | `data/ai/agent/AgentBridge.spawn()`（L182） | 建 `Conversation(assistantId = AGENTS_ASSISTANT_ID, customSystemPrompt = 模板prompt+协议)` + `agent_session` 行 + 按模板分 folder，然后投递任务消息 |
| 可见对话 | agent 子会话就是普通 `Conversation`，在抽屉可见 | 用户已确认：**不要用废弃的不可见 SubagentRunner 黑盒**，用可见对话这套 |
| 投递 + 唤醒 | `AgentBridge.deliver()`（L411）+ `dispatchWake()`（L633） | 消息无条件入 `AgentInboxStore` → `bus.requestWakeAsync` → 目标空闲后 `dispatchWake` 发"system 署名"提示读信 → 触发一轮生成 |
| 完成判定 | `ChatService` 的 `generationDoneFlow` → `AgentBridge.onGenerationDone()`（L995） | 没调 `agent_report` 就正常结束 → `handlePrematureEnd()`（L1080）：前 N 次向 agent 本人发系统提醒"请继续"，超限升级告知父对话 |
| 汇报 | `AgentBridge.reportToParent()`（L689） | `agent_report` 工具 → 汇报投递到 `parentId` → `endChildTurn` 优雅收尾 |
| 记忆 | `AGENT_MEMORY_OPTIONS`（L1213）：**全关**（agent 上下文隔离省 token） | 这是 subagent 的设计选择；Schedule Agent 需要按模板决定是否继承 |
| 助手绑定 | `spawn` 里 `assistantId = AGENTS_ASSISTANT_ID` 硬编码（L315） | Schedule Agent 的**关键差异点**：改成模板可配 |
| 定时基础设施 | `ScheduledNotificationManager`（AlarmManager + PendingIntent + 同步 bundle） | 定时触发模式可直接照抄 |
| WorkManager | 已引入，`AutoSyncWorker` / `SnapshotWorker` 先例 | 周期性任务也可用（两种方案二选一，见 §3） |
| 模板体系 | `data/ai/subagent/SubagentTemplate.kt` + `SubagentTemplateManager`（JSON 文件存 `filesDir/subagents/`） | **用户要求：Schedule Agent 配置同样用 JSON 文件**，AI 可直接改文件，设置页只做简单开关列表 |

---

## 1. 需求拆解（用户原话整理）

1. **通用定时任务接口**：不只是查岗。任何"定时让某个 AI 干活"都能用。
2. **复用 agent 子会话机制**：可见对话、防止提前中断（没汇报就发"继续"）、完成汇报。
3. **不绑死 AGENTS 宿主**：模板决定绑哪个助手——可以绑学习助手（继承它的系统提示词/记忆/记忆图/MCP），也可以绑别的。
4. **无真实父对话**：定时器就是"父节点"。完成汇报没处投 → **直接弹系统通知**。
5. **配置 = JSON 文件**：和 subagent 模板一样，`enabled` 字段开关，AI 帮我改，不需要手动管理 UI。
6. 默认内置一个"查岗"任务（监督期每 10 分钟看屏幕时间 + 搜近期对话 + 查网络，决定是否禁止），但只是众多任务之一。

---

## 2. 配置模型：ScheduleAgentTemplate（JSON）

新增独立文件 `data/ai/schedule/ScheduleAgentTemplate.kt`，JSON 存 `filesDir/schedule-agents/*.json`
（与 `subagents/` 平行；WorkManager/ScheduledNotification 已有先例），字段：

```kotlin
@Serializable
data class ScheduleAgentTemplate(
    val id: String,              // 稳定 id，文件名建议与 id 一致
    val name: String,
    val description: String = "",
    val enabled: Boolean = true, // AI/用户开关任务

    // ---- 定时 ----
    /** 触发周期（分钟）。 */
    val intervalMinutes: Int = 10,
    /** 可选：每天固定时刻触发（HH:mm，优先级高于 intervalMinutes）。 */
    val dailyAt: String? = null,

    // ---- 绑定哪个助手（核心差异）----
    /**
     * 绑定的助手 id。null = 不绑（用下面的 systemPrompt 当人格，assistantId 用 AGENTS_ASSISTANT_ID
     * 或默认助手占位，记忆按 memory 配置走）。
     * 非 null = 绑学习助手：conversation.assistantId 用该助手，
     * **自动继承其 systemPrompt / 记忆 / 记忆图 / 模型 / 工具默认值**。
     */
    val assistantId: String? = null,

    /** 不绑助手时的人格 prompt（绑定助手时此字段被忽略，用助手的 systemPrompt）。 */
    val systemPrompt: String? = null,

    // ---- 工具 / 记忆 / 上下文（在 subagent 模板基础上扩展）----
    /** 本地工具（LocalToolOption serialName），空 = 跟随助手默认 */
    val allowedLocalTools: List<String> = emptyList(),
    /** workspace 工具，空 = 跟随助手默认 */
    val allowedWorkspaceTools: List<String> = emptyList(),
    /** MCP 工具（"serverId/toolName"），空 = 跟随助手默认 */
    val allowedMcpTools: List<String> = emptyList(),

    // ---- 记忆/图（Schedule Agent 区别于 subagent 的关键开关，默认跟随助手）----
    val inheritMemory: Boolean = true,        // 继承助手记忆（绑定助手时才有意义）
    val inheritMemoryGraph: Boolean = true,   // 继承助手记忆图
    val inheritRecentChats: Boolean = false,  // 引用最近对话
    /** 当 inheritMemory=false 且未绑助手时，用全关的隔离上下文（同 AGENT_MEMORY_OPTIONS） */

    // ---- 每次触发时的任务指令模板（Pebble/占位符，同 subagent 的 applyPlaceholders）----
    val taskPrompt: String = "现在是你的一次定时执行。请按当前任务要求工作，完成后调用 agent_report 汇报结果。",

    // ---- 会话复用模式 ----
    /**
     * conversationMode:
     * - "reuse"（默认）：一模板一常驻对话，每次触发往同一对话追加系统消息，
     *   上下文连续（查岗类任务需要"看看你上次说了啥"，靠 auto-compress 压历史）；
     * - "fresh"：每次触发新建一个对话，执行完标记 done/归档，互不干扰
     *   （适合纯一次性任务，历史不积累）。
     */
    val conversationMode: String = "reuse", // reuse | fresh

    // ---- 执行限制 ----
    val maxSteps: Int = 50,
    val timeoutMinutes: Int = 15,
    val maxTotalTokens: Int = 128_000,

    // ---- 汇报 ----
    /** 无父节点：汇报是否弹系统通知（默认 true）。 */
    val notifyOnReport: Boolean = true,
    /** 提前终止（没汇报就结束）提醒次数上限，0 = 用全局默认（AgentLimits.MAX_PREMATURE_END_REMINDERS=2）。 */
    val prematureEndReminders: Int = 0,

    // ---- 监督联动（查岗类任务用）----
    /** 仅监督时段内触发。 */
    val onlyDuringSupervision: Boolean = false,

    // ---- 监督期内是否运行定时任务（存于 SupervisionSettings，非模板字段）----
    // 说明：见 PLAN_SCHEDULE_AGENTS §6.1「监督×定时任务总闸」。
    // 该开关在 SupervisionSettings.scheduleAgentsEnabledDuringSupervision 里，
    // 默认 true（监督期内定时任务照常跑，如查岗）；监督期内 Gate 只许开、不许关。

    val updatedAt: Long = 0L,
)
```

字段设计理由：
- **绑定助手 + 继承记忆/图** = 完全满足"学习助手下的对话，有它的系统提示词和记忆"；
- 不绑助手 + 继承记忆 = 通用"常驻 agent"；
- 全关 = 纯隔离任务（等价旧 subagent 语义）；
- `enabled` = AI 改 JSON 即可开/关，设置页只做开关列表 + 手动添加（可选）。

---

## 3. 调度器：ScheduleAgentScheduler

### 3.1 触发方式（二选一，建议 AlarmManager 优先）

- **AlarmManager**（照抄 `ScheduledNotificationManager`）：
  - 每个 schedule agent 一条 `setExactAndAllowWhileIdle` 的 PendingIntent；
  - `dailyAt` 模式 → 每天固定时刻；`intervalMinutes` 模式 → 到期后自动排下一次；
  - 触发 → `ScheduleAgentRunner.run(id)` → 完成后重新 schedule 下一次；
  - 优点：省电、精确、进程死掉也能靠 `BOOT_COMPLETED` + `rescheduleAll` 恢复（manifest 已有 `RECEIVE_BOOT_COMPLETED`）。
- WorkManager Periodic（`SnapshotWorker` 先例）：适合"每 N 分钟"统一轮询，但最小 15 分钟粒度对查岗不够。

**结论：AlarmManager**，完全复用 ScheduledNotificationManager 的调度骨架。

### 3.2 触发执行（模拟父节点投递）

`ScheduleAgentRunner.run(templateId)`：

```
1. template = manager.getTemplate(id) ?: return
   if (!template.enabled) return
   if (template.onlyDuringSupervision && !settings.supervision.isActiveNow()) {
       重新 schedule 下一次，return   // 非监督时段跳过（查岗任务用）
   }

2. 按 template.conversationMode 确定目标会话：
   - "reuse"：session = agentSessionDao.getByTemplateId(templateId)   // 一模板一常驻会话，复用
     if (session == null) 创建会话（见 §3.3）
   - "fresh"：每次新建一个会话（见 §3.3），执行完自然结束
     （上一次的 done/stopped 会话不清理也行，UI 按 folder 归档；可选自动 archive）

3. 任务文本 = template.taskPrompt 占位符展开（时间/日期等）

4. 投递：模拟父节点发系统消息
   inboxStore.enqueue(
       target = 会话id,
       body = senderHeader(SYSTEM, ...) + "\n[schedule] " + 任务文本,
       kind = AgentMessageKind.SYSTEM,
       source = AgentInboxSource.SYSTEM,   // 已有预留常量
       urgency = AgentUrgency.MAIL,
   )
   bus.requestWakeAsync(会话id)   // 复用 dispatchWake → 触发一轮生成

5. 重新 schedule 下一次
```

### 3.3 会话创建（复用 spawn 的建会话逻辑，改造绑定）

在 `AgentBridge` 加一个 `spawnSchedule(template)`（**不经过 spawn 的 parent 校验**）：

```kotlin
suspend fun spawnSchedule(template: ScheduleAgentTemplate): Uuid {
    val settings = settingsStore.settingsFlow.first()
    val assistant = template.assistantId?.let { settings.getAssistantById(it) }
    val childId = Uuid.random()

    // 模板可配 folder 名：默认 "◆ " + template.name（同 resolveFolder 逻辑），
    // 查岗类模板可在 JSON 里写 folderName = "监督"
    val folderId = folderRepo.findOrCreateFolder(assistant?.id ?: AGENTS_ASSISTANT_ID, template.folderName ?: "◆ ${template.name}")

    val profile = AgentProfile(
        workspaceId = assistant?.workspaceId?.toString(),
        modelId = assistant?.chatModelId?.toString(),   // 绑助手 → 用助手模型
        localTools = ...,
        workspaceTools = ...,
        mcpTools = ...,
        maxSteps = template.maxSteps,
        timeoutMinutes = template.timeoutMinutes,
        maxTotalTokens = template.maxTotalTokens,
        // Schedule Agent 不需要派生权
        canSpawn = false,
        spawnBudget = 0,
        interruptRight = "none",
    )

    val conversation = Conversation(
        id = childId,
        assistantId = assistant?.id ?: AGENTS_ASSISTANT_ID,   // 关键差异：绑学习助手
        title = template.name,
        messageNodes = emptyList(),
        customSystemPrompt = if (assistant == null) {
            buildSystemPrompt(template.systemPrompt ?: DEFAULT_AGENT_PERSONA, ...)  // 不绑 → 模板人格 + agent 协议
        } else null,   // 绑助手 → 不用 customSystemPrompt，直接用助手 systemPrompt
        folderId = folderId,
        modelId = assistant?.chatModelId,
        memoryGraphBindings = if (template.inheritMemoryGraph) null else emptyList(),
    )
    conversationRepo.insertConversation(conversation)
    agentSessionDao.upsert(AgentSessionEntity(
        childId = childId.toString(),
        parentId = SCHEDULE_VIRTUAL_PARENT_ID.toString(),  // 虚拟父（见 §4）
        rootId = childId.toString(),
        templateId = template.id,
        depth = 0,           // 不是真正的层级，depth 无意义但避免触发 MAX_DEPTH
        status = AgentStatuses.IDLE,
        taskBrief = template.name,
        ...
        profileJson = json.encodeToString(profile),
    ))
    return childId
}
```

### 3.4 记忆注入（关键差异点）

现有 `dispatchWake` 里 `memoryOptions = if (profile != null) AGENT_MEMORY_OPTIONS else MemoryOptions()`
——即所有 agent 会话都全关记忆。Schedule Agent 要按模板：

```kotlin
val memoryOptions = if (template != null) {
    // Schedule Agent：按模板继承
    MemoryOptions(
        referenceAssistantMemory = template.inheritMemory,
        referenceAssistantGraph = template.inheritMemoryGraph,
        allowEditAssistantMemory = template.inheritMemory,
        referenceRecentChats = template.inheritRecentChats,
        ... 其余跟随 assistant 默认
    )
} else AGENT_MEMORY_OPTIONS
```

注意：`dispatchWake` 目前拿不到 template，需要从 `agent_session.template_id` 查
ScheduleAgentTemplate（或直接塞进 AgentProfile 新增字段）。

---

## 4. 虚拟父节点 + 汇报通知

### 4.1 虚拟父 id

```kotlin
/** Schedule Agent 的虚拟父节点：一个固定哨兵 Uuid，不会对应任何真实对话 */
val SCHEDULE_VIRTUAL_PARENT_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
```

### 4.2 汇报分流

`AgentBridge.reportToParent()` / `askParent()` / `handlePrematureEnd()` 的升级分支
里，检测到 `parentId == SCHEDULE_VIRTUAL_PARENT_ID` 时：

- **reportToParent**：不投递消息，改为 `postNotification(context, title="定时任务完成", message=summary)`（复用 `NotificationTool.postNotification`，它是 `internal` 同包可调）+ 状态置 `DONE`，会话继续复用等下一次触发。
- **askParent**：没有父可问 → 弹通知把问题转达用户，或直接告知 agent"本任务无父对话，请自行决策"（推荐后者：查岗类任务不需要反问）。
- **handlePrematureEnd**：前 N 次照旧"提醒本人继续"（复用现有 inbox 投递）；超限时**不投递给虚拟父**（不存在），改为弹通知"定时任务 X 多次未汇报"。

实现上在 AgentBridge 加一个 `isVirtualParent(parentId)` 判断，相关分支 if 分流，改动面小。

---

## 5. UI（最小化，符合"不手动管理"）

- 设置页「专注监督」里加一项「定时任务（Schedule Agents）」入口，或独立页面；
- 列表：从 `schedule-agents/*.json` 读，显示 name / 周期 / 启停 Switch（写 JSON 的 `enabled`）；
- 详情：只读展示 JSON 字段 + 提示"可直接修改 JSON 文件让 AI 帮你改"（不内置复杂编辑器）；
- 可选：一个"添加任务"按钮生成模板 JSON 骨架（默认查岗模板已内置在 assets/first-run 写入）。

---

## 5.1 监督 × 定时任务总闸（监督设置里新增）

用户需求：**监督设置里加一个开关——监督期间是否运行定时任务（Schedule Agents）；
默认开启；监督期内只能开启、不能关闭**（符合监督"只许加强"总原则）。

落在 `SupervisionSettings`（与监督锁同源，跨设备同步、Gate 统一管）：

```kotlin
// SupervisionSettings 新增字段
/**
 * 监督期内是否运行定时任务（Schedule Agents）。
 * 默认 true：监督期内查岗等任务照常跑。
 * 监督期内 Gate 只许 true→true（开启）；尝试 false（关闭）会被回滚。
 */
val scheduleAgentsEnabledDuringSupervision: Boolean = true,
```

- **Gate 规则**（`SupervisionGate.strengthenLocalSupervision` 加一行，与
  `lockMcpServers` 同款语义）：
  ```kotlin
  scheduleAgentsEnabledDuringSupervision =
      incoming.scheduleAgentsEnabledDuringSupervision || old.scheduleAgentsEnabledDuringSupervision,
  ```
  → 监督期内想关掉定时任务 = 减弱监督 = 被回滚成开启。
- **strengthenWith**（云同步合并）同样取 OR——任一设备开 = 合并结果开。
- **运行时生效**（`ScheduleAgentRunner.run` 第 1 步加判断）：
  ```kotlin
  val sup = settings.supervision
  if (sup.isActiveNow() && !sup.scheduleAgentsEnabledDuringSupervision) {
      重新 schedule 下一次，return   // 监督期总闸关闭 → 所有定时任务跳过（不只查岗）
  }
  ```
  注意与模板 `onlyDuringSupervision` 的区别：
  - 总闸 = 监督期内**所有** schedule agent 的开关（设置里一把刀）；
  - `onlyDuringSupervision` = 单个任务只在监督期内跑（查岗），非监督期跳过。
- **UI**（`SettingSupervisionPage` 加一张卡）：
  ```
  监督期内运行定时任务  [Switch，默认开]
  副标题：监督时段内 Schedule Agents（查岗等）是否触发；
         监督期内此开关只能保持开启（定时任务是监督的一部分）。
  ```

---

## 6. 默认内置：查岗任务模板

首次启动时 `ScheduleAgentManager` 确保 `filesDir/schedule-agents/` 里有一个
`check-in.json`：```json
{
  "id": "supervision_checkin",
  "name": "监督查岗",
  "enabled": true,
  "intervalMinutes": 10,
  "assistantId": "<用户指定的学习助手，首次运行时如果已设监督白名单则取第一个>",
  "inheritMemory": true,
  "inheritMemoryGraph": true,
  "onlyDuringSupervision": true,
  "folderName": "监督",
  "taskPrompt": "查岗：请查看最近的屏幕使用时间、近期对话，判断用户是否在学习；必要时检查最近访问的网站并决定是否建议加入黑名单。完成后用 agent_report 汇报。",
  "allowedLocalTools": ["screen_time", "ask_user", "time_info", "inbox", "send"],
  "allowedMcpTools": []
}
```

- `assistantId` 留空 → 任务绑 AGENTS 或默认助手，用户在 JSON 里改成自己的学习助手 id（或由设置页"设为守门员"联动自动填）；
- 后续流量监督 tool 上线后，查岗 prompt 自然升级（任务 JSON 由 AI 改，无需动代码）。

---

## 7. 改动文件清单

新增：

- `data/ai/schedule/ScheduleAgentTemplate.kt` — 配置模型 + 默认查岗模板
- `data/ai/schedule/ScheduleAgentManager.kt` — 模板文件读写 / ensureDefault / list / setEnabled（照抄 SubagentTemplateManager）
- `data/ai/schedule/ScheduleAgentScheduler.kt` — AlarmManager 定时触发（照抄 ScheduledNotificationManager）
- `data/ai/schedule/ScheduleAgentRunner.kt` — 触发执行：投递系统消息 + 唤醒（或并入 Scheduler）
- `ui/pages/setting/SettingScheduleAgentsPage.kt` — 任务列表 + 启停开关
- `data/db/migrations/…` — **不需要**（配置走 JSON 文件，agent_session 表复用；`getByTemplateId` 若 DAO 没有就加一个查询方法，Room 查询不需要 migration）

修改：

- `data/ai/agent/AgentBridge.kt`
  - `SCHEDULE_VIRTUAL_PARENT_ID` 哨兵
  - `spawnSchedule(template)` 建会话（绑定助手 / 记忆 / folder）
  - `reportToParent / askParent / handlePrematureEnd / notifyParentSystem` 里虚拟父分流 → 通知
  - `dispatchWake` 记忆选项按模板
- `data/model/SupervisionSettings.kt`
  - 新增 `scheduleAgentsEnabledDuringSupervision: Boolean = true`
  - `strengthenWith` 取 OR
- `data/datastore/SupervisionGate.kt`
  - `strengthenLocalSupervision` 加"只许开"规则（同 lockMcpServers 语义）
- `ui/pages/setting/SettingSupervisionPage.kt`
  - 新增「监督期内运行定时任务」Switch 卡片
- `service/ChatService.kt`
  - `sendMessage` / `handleMessageComplete` 无改动（schedule 会话也是普通会话）
  - `dispatchWake` 相关 memoryOptions 传递（若有）
- `data/sync/core/SyncEngine.kt`（可选）
  - schedule-agents JSON 是否随 D1 同步：**建议不同步**（任务配置跨设备意义不大，且与监督配置不同）；如需同步加一个 bundle，先不做
  - 注意：`scheduleAgentsEnabledDuringSupervision` 在 `SupervisionSettings` 里，**随监督配置正常云同步**（跨设备生效，与监督锁一起）
- `ui/pages/setting/SettingPage.kt` — 加入口（如需要独立页）

---

## 8. 测试要点

- JVM 单测：`ScheduleAgentTemplateTest`（JSON 解析 / 默认查岗模板字段 / conversationMode 校验）
- 手工：
  1. 添加一个 intervalMinutes=1、conversationMode=reuse 的测试任务 → 等 1 分钟 → 抽屉出现该 agent 可见对话 → 收到系统消息 → AI 干活 → 完成弹通知；第二次触发时同一对话追加消息（历史可见）
  2. 改成 conversationMode=fresh → 每次触发新建对话，旧对话归档互不干扰
  3. 让 AI 不调 agent_report 直接结束 → 触发"请继续"提醒 → 再结束 → 超限弹通知
  4. 绑学习助手 → 确认对话继承助手 systemPrompt / 记忆 / 记忆图（prompt 里能看到）
  5. `onlyDuringSupervision=true` → 非监督时段不触发
  6. 重启 App → AlarmManager rescheduleAll 恢复
  7. 监督设置里把「监督期内运行定时任务」关闭 → 监督时段内所有 schedule agent 都不触发
     （Gate 拒绝：监督期内想关会被回滚，UI 上 Switch 直接置灰不可操作；非监督期可自由改）
  8. 该总闸随监督配置云同步（跨设备一起生效）

---

## 9. 工作量

- 配置模型 + Manager + 默认模板：0.5 天
- Scheduler（AlarmManager）+ Runner：0.5–1 天
- AgentBridge 改造（spawnSchedule / 虚拟父分流 / 记忆）：1 天
- UI 列表 + 启停：0.5 天
- 联调（触发 / 汇报通知 / 提前终止）：0.5 天

合计 **3–3.5 天**，其中 AgentBridge 改造是核心，其余全是照抄现有模式。

---

## 10. 落地记录（2026-08-16）

按本 plan 实现完毕，改动与 plan 的差异如下：

- **新增** `data/ai/schedule/` 包：`ScheduleAgentTemplate.kt`（含默认查岗模板）、
  `ScheduleAgentManager.kt`、`ScheduleAgentScheduler.kt`（AlarmManager）、
  `ScheduleAgentRunner.kt`；
- **新增** `receiver/ScheduleAgentReceiver.kt`（闹钟触发入口，先排下一次再异步执行）；
- **AgentBridge**：构造注入 `ScheduleAgentManager`；新增 `spawnSchedule()`（绑助手 /
  继承记忆图 / folder / 强制注入 inbox 工具）、`SCHEDULE_VIRTUAL_PARENT_ID` 哨兵
  （定义在 `AgentModels.kt`）；`reportToParent`（完成弹通知，先于往返上限判断——
  定时任务周期性，往返计数跨触发累积不应按 8 次停）、`askParent`（引导自行决策）、
  `handlePrematureEnd`（提醒本人照抄原逻辑，超限虚拟父→通知，提醒上限可被模板
  `prematureEndReminders` 覆盖）、`notifyParentSystem`（虚拟父→通知）、
  `dispatchWake`（记忆选项按模板开关）；
- **汇报通知通道**：`AppEvent.ScheduleAgentNotification` + `ChatNotificationManager`
  消费（复用现成 sendNotification，无 Context 侵入 AgentBridge）；
- **监督总闸**：`SupervisionSettings.scheduleAgentsEnabledDuringSupervision`（默认 true，
  strengthenWith 取 OR）+ `SupervisionGate.strengthenLocalSupervision`（只许开）+ 
  监督页 Switch 卡片（监督期置灰）+ Runner 第一步过滤；
- **UI**：`SettingScheduleAgentsPage`（列表 + 启停 + 只读详情），设置页「子代理」下方
  加入口，`RouteActivity` 新路由；
- **启动/恢复**：`RikkaHubApp.onCreate` 补默认模板 + rescheduleAll，`BootReceiver`
  开机恢复闹钟，`AndroidManifest` 注册 ScheduleAgentReceiver；
- **DAO**：`AgentSessionDAO.getByTemplateId(templateId, parentId)`（Room 查询，无 migration）；
- **测试**：`ScheduleAgentTemplateTest`（默认模板字段 / JSON 往返 / 兼容旧 schema /
  conversationMode 校验）。

与 plan 的取舍：
- 未做「会话归档自动清理」（fresh 模式旧对话保留在抽屉，status=done）；
- schedule-agents JSON 不同步 D1（同 plan 建议）；
- `spawnSchedule` 审批模式固定 `USER`（定时任务无父对话可代审，危险工具本就在硬名单）；
- 触发 body 用 `<from role="system" title="定时任务：X">` 署名（senderHeader 语义一致）。

