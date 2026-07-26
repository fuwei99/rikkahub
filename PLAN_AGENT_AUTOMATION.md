# Plan: Subagent + 定时任务 (Agent Automation)

> 状态: 设计稿, 未开工。前提: 用户愿意授予后台常驻 / 前台服务 / 常驻通知 / 忽略电池优化等权限。
> 分支建议: 在 `feat/latex-engine-migration` 合并后, 分别开 `feat/subagent` 与 `feat/scheduled-tasks`,
> 先做 subagent (定时任务的执行层会复用它的"无 UI 跑一轮 agent"能力)。

---

## 0. 现状梳理 (代码事实, 已核实)

| 组件 | 位置 | 关键点 |
|---|---|---|
| ChatService | `app/.../service/ChatService.kt` (Koin 单例) | 会话 = `ConversationSession`(ConcurrentHashMap 缓存, 引用计数+空闲回收); 生成任务跑在 `appScope`, **不依赖 UI 存活** |
| GenerationHandler | `app/.../data/ai/GenerationHandler.kt` | `generateText(...)` 返回 `Flow<GenerationChunk>`, 内部自带 maxSteps 工具循环。**已经是纯 headless 的 agent loop**, 与 UI 零耦合 |
| 通知 | `ChatNotificationManager` (`createdAtStart=true`) | 已解耦: ChatService 发事件 → 通知管理器消费。后台完成通知的通路现成 |
| 前台服务先例 | `WebServerService` (`foregroundServiceType="specialUse"`) | manifest 写法、通知渠道、启动/停止模式可直接照抄 |
| WorkManager | 已引入 (`workmanager = 2.11.2` + koin-androidx-workmanager), 目前无 Worker | 定时任务的调度底座现成 |
| DB | Room v26, 8 个 entity | 加表需要 migration (26→27) |
| 工具创建 | `createWorkspaceTools(workspaceId, repo, cwd)` + `LocalTools` + MCP | 工具集组装逻辑集中在 `ChatService.handleMessageComplete` 附近, subagent 需要复用 |

结论: **"跑一轮 agent"的全部积木都已存在**, 两个 feature 的本质都是给
`GenerationHandler.generateText` 套一个新的调用方, 不需要动生成内核。

---

## 1. Feature A: Subagent (`spawn_agent` 工具)

### 目标

主对话中的模型可以派生一个隔离上下文的子 agent 执行子任务
(如"把这 50 个文件逐个翻译"、"跑测试并修到全绿"), 结束后只把**摘要**返回主对话,
避免长过程污染主上下文。

### 1.1 核心设计

新增 `SubagentRunner` (Koin 单例, `app/.../data/ai/subagent/SubagentRunner.kt`):

```
suspend fun run(spec: SubagentSpec): SubagentResult
```

- 直接调 `generationHandler.generateText(...)` 收集 Flow 至完成, **不创建 Conversation 实体**,
  消息列表只存在内存 + 运行记录表 (见 1.4)
- `SubagentSpec`: system prompt(任务指令) + 初始 user 消息 + 允许的工具集 + maxSteps + 模型(默认继承主对话)
- 复用主对话的 workspace 绑定: 子 agent 拿到同一套 `createWorkspaceTools` 工具, 共享 /workspace
- 结果 = 子 agent 最后一条 assistant 消息文本 (约定其为任务总结) + 统计(步数/token/工具调用数)

### 1.2 工具定义 (`SubagentTools.kt`, 挂在 LocalTools 或独立注册)

```
spawn_agent:
  task        string  必填, 子任务指令(会作为子 agent 的 user 消息)
  context     string  可选, 需要传递的背景信息
  tools       string  可选, "workspace" | "search" | "all" (默认 workspace)
  max_steps   int     可选, 默认 50, 上限 100
```

同步阻塞版先行 (主对话的这次工具调用等待子 agent 完成)。
理由: GenerationHandler 的工具循环天然支持 suspend 到底; 异步 fire-and-forget
需要轮询工具 + 状态管理, 第二期再加 (`spawn_agent_async` / `check_agent`)。

### 1.3 防失控措施 (必须第一版就有)

- **禁止套娃**: 子 agent 的工具集里剔除 `spawn_agent` (递归深度=1 硬限制)
- maxSteps 上限 100; 子 agent 运行总时长上限 (默认 15min, 超时取消协程)
- 并发上限: 同时最多 2 个子 agent, 超出直接报错让主模型排队
- 工具审批: 子 agent 内的工具**不走 UI 审批弹窗** (无人值守), 因此默认工具集只给
  已被用户在该 workspace 标记为免审批的工具; 需要审批的工具调用直接拒绝并返回错误文本
- 取消传播: 主对话生成被用户停止时, 通过协程结构化取消自动终止子 agent

### 1.4 可观测性

- 新 Room 表 `SubagentRunEntity` (26→27 migration):
  `id, parentConversationId, task, status, startedAt, finishedAt, steps, resultSummary, transcript(JSON)`
- 工具调用 UI: `SpawnAgentToolUI` 注册到 `ToolUIRegistry` —
  Summary 显示任务+实时状态(运行中步数), 详情页可展开完整 transcript
- 运行中把进度写入 `processingStatus` flow, 聊天界面顶部已有该状态的展示位

### 1.5 交付切分

1. `SubagentRunner` + `spawn_agent` 工具 + 防失控 (可用最小闭环)
2. Room 记录表 + ToolUI transcript 查看
3. (二期) async 模式 + 多子任务并行 fan-out

---

## 2. Feature B: 定时任务 (Scheduled Tasks)

### 目标

"每天 8:00 用助手 X 执行 prompt Y"(晨报/汇率/RSS 摘要), 或"每 N 小时跑一次某 agent 任务
(检查 CI、拉取并总结邮件)", 结果发通知, 点开进入对应对话。

### 2.1 调度层

- **WorkManager 为主**: `ScheduledTaskWorker : CoroutineWorker` (koin-androidx-workmanager 注入依赖),
  `PeriodicWorkRequest` + `ExistingPeriodicWorkPolicy.UPDATE`, uniqueName = taskId
- 精确时刻需求 (每天 8:00 整): WorkManager periodic 有 flex 漂移,
  改用 `AlarmManager.setExactAndAllowWhileIdle` → BroadcastReceiver → enqueue OneTimeWork,
  执行完再排下一次闹钟 (自调度链)。需要 `SCHEDULE_EXACT_ALARM` 权限 + `RECEIVE_BOOT_COMPLETED`
  开机重排 (manifest 目前两者都没有, 要加)
- 用户已同意忽略电池优化: 设置页加引导跳转 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### 2.2 执行层 (复用 Subagent 的积木)

`ScheduledTaskExecutor`:

1. 读任务配置 → 找到 assistant (模型/工具/workspace 均沿用该助手配置)
2. 两种落地模式, 任务配置里选:
   - **对话模式**: 通过 `ChatService.sendMessage` 往固定对话(或每次新建)发 prompt,
     结果留在聊天记录里, 通知点击跳转该对话 — 复用现有后台生成+通知全链路, 几乎零新代码
   - **静默模式**: 走 `SubagentRunner.run`, 只留运行记录和通知, 不产生对话
3. 执行期间起前台服务 `TaskExecutionService` (照抄 WebServerService 的 specialUse 写法),
   常驻通知显示"正在执行定时任务: xxx", 防止进程被杀; 结束即 stopSelf

### 2.3 数据模型

Room 表 `ScheduledTaskEntity` (与 SubagentRunEntity 同一次 migration):

```
id, name, enabled,
trigger:      JSON { type: "daily"|"interval"|"cron-lite", hour, minute, intervalMinutes, daysOfWeek }
assistantId, prompt,
mode:         "conversation" | "silent"
conversationPolicy: "reuse" | "new"   (对话模式下)
lastRunAt, lastStatus, nextRunAt
```

### 2.4 UI

- 设置/扩展页新增 "定时任务" 入口 (`ui/pages/extensions/tasks/`):
  列表 + 开关 + 新建/编辑表单 (名称、触发时间、助手选择、prompt、模式) + 运行历史
- 权限引导卡片: 精确闹钟 / 电池优化白名单 / 通知权限, 缺哪个提示哪个

### 2.5 交付切分

1. Entity + DAO + migration + 任务管理 UI (只存不跑)
2. WorkManager/AlarmManager 调度 + 对话模式执行 + 通知 (最有感知的闭环)
3. 前台服务保活 + 开机重排 + 权限引导
4. 静默模式 (依赖 Feature A 的 SubagentRunner 落地)

---

## 3. 依赖关系与顺序

```
Subagent.1 (Runner+工具) ──→ Subagent.2 (记录+UI)
      │
      └────────────→ Tasks.4 (静默模式)
Tasks.1 (数据+UI) ──→ Tasks.2 (调度+执行+通知) ──→ Tasks.3 (保活)
```

- 两条线可并行, 但 **SubagentRunner 先做** — 它是唯一被两边共用的新构件
- Room migration 合并成一次 26→27 (两张新表一起加), 避免连续两次升版

## 4. 风险清单

| 风险 | 对策 |
|---|---|
| 子 agent 无人值守乱写文件 | 只放行免审批工具; workspace 备份系统已有, 可回滚 |
| 定时任务执行时模型 API 失败 | 记录 lastStatus=failed + 失败通知; 不自动重试 (LLM 调用非幂等且花钱) |
| 厂商 ROM 杀后台 (国产 ROM 尤甚) | 前台服务 + 电池白名单 + 开机重排; 文档里说明无法 100% 保证 |
| WorkManager 与 AlarmManager 双轨复杂化 | interval 型走 WorkManager, 精确时刻型走 AlarmManager, 界面上不暴露实现差异 |
| token 消耗失控 | 任务/子 agent 记录步数与 token; 定时任务默认单次 maxSteps 较低 (20) |
