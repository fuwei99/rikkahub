# 交接文档 (HANDOVER)

> 最后更新: 2026-07-26。供新对话的 agent 快速接手。
> 读完本文件即可继续工作, 不需要翻旧对话。

---

## 1. 项目与环境

- 仓库: `/workspace/rikkahub` — fork 自 rikkahub (Android LLM 聊天客户端, Kotlin/Compose 多模块)
- 远端: `https://github.com/fuwei99/rikkahub`, GitHub token 已存本地 (`~/.git-credentials`, credential.helper store)
- **工作分支: `feat/latex-engine-migration`** (所有改动都在这个分支, 用户要求保持连续性, 不要另开分支, 除非做 subagent/定时任务大 feature)
- CI: push 即触发 `.github/workflows/release.yml`, 构建成功自动打 tag 发 Release (带 APK), 用户手动装 APK 验证
- **本机无 Java, 无法本地编译**。改完直接 push 靠 CI 验证; 编译挂了拉 Action 日志修
- 特殊性: agent 本身就跑在这个 app 里 —— 改的 workspace 工具就是自己在用的工具, 改动需装新 APK 才生效
- 参考项目: `/workspace/kelivo` (Flutter, gpt_markdown, 公式渲染做得好, 已 clone 可查阅)

## 2. Plan 文件位置

| 文件 | 说明 |
|---|---|
| `/workspace/rikkahub/PLAN_AGENT_AUTOMATION.md` | **Subagent + 定时任务设计稿, 未开工**。已核实代码现状: GenerationHandler 是纯 headless agent loop 可复用; WorkManager 已引入无 Worker; 前台服务照抄 WebServerService。计划: latex 分支合并后开 `feat/subagent` 与 `feat/scheduled-tasks`, 先做 subagent。未被 git 追踪, 本地备忘 |
| `/workspace/rikkahub/PLAN_TTS_STREAM_CHUNK.md` | 仓库原有的, 不是我们写的, 无关 |
| `/workspace/rikkahub/HANDOVER.md` | 本文件 |

## 3. 已完成 (全部已 push, HEAD = `ba271f2`)

### Subagent (Feature A, PLAN_AGENT_AUTOMATION.md)
- `ba271f2`: **spawn_agent 工具 + SubagentRunner** (交付切分第 1 步, 最小闭环)
  - `app/.../data/ai/subagent/SubagentRunner.kt`: headless 跑一轮 agent, 复用 generateText, 不建 Conversation, 返回最后 assistant 消息作摘要
  - `app/.../data/ai/subagent/SubagentTools.kt`: spawn_agent 工具定义 + `Tool.unattended()` 包装 (需审批的调用直接拒绝)
  - 防失控: 禁递归(子工具集无 spawn_agent), maxSteps≤100, 15min 超时, 并发≤2
  - 开关: LocalToolOption.Subagent, 助手→本地工具页 (AssistantLocalToolPage 硬编码中文, 跟随闹钟项先例)
  - 接线: ChatService 构造函数加 subagentRunner, AppModule 注册; 工具组装处 ~L582
  - 未做: Room 运行记录表 / 专属 ToolUI / async 模式 (二期)
  - ⚠️ commit 时用了 `git apply --cached` 拆分 ChatService.kt 的 hunks (另一 agent 的临时聊天改动也在同文件), 后续同文件提交都要这样拆

### LaTeX 引擎迁移 + 渲染修复
- `8c3f994` + `9197579`: JLatexMath → `io.github.huarangmeng:latex` **1.4.7** (Compose 原生, KaTeX 字体, 有基线测量 API)。行内公式 AboveBaseline 基线对齐; 块级用 LatexAutoWrap; \xlongequal 降级; mark.html WebView KaTeX 修复
- `dd6efa6`: 用户实测反馈的 8 项修复, 兼容层集中在 `app/.../richtext/LatexText.kt` 的 `processLatex()`:
  - `\middle` 不支持 → 删命令保留定界符 (MIDDLE_REGEX)
  - `\xrightleftharpoons` → overset/underset + `\rightleftharpoons` (replaceXlongequal 泛化成 `replaceExtensibleCommand`)
  - `\{` `\}` → `\lbrace`/`\rbrace`, `\|` → `\Vert` (单遍扫描, 处理 `\\` 转义, 见 `applyCompatReplacements`)
  - 行内 `\lim`/`\max`/`\gcd`/`\operatorname` 等字号过大 → 仅行内模式降级为 `\mathrm` (`downsizeInlineOperators`, 块级不动保留 limits 排版)
  - 行内深公式 (`\cfrac`, `\displaystyle` 积分) 压到下一行 → `computeInlineMathPlacement()`: depth > 0.45em 时改用全高 TextCenter placeholder, 浅公式仍精确基线对齐 (Markdown.kt / MarkdownNew.kt 两处调用点已同步)
  - `~单波浪删除线~` → preProcess 里规范成 `~~`, 词边界保护不误伤 `~10` (SINGLE_TILDE_STRIKE_REGEX, Markdown.kt + MarkdownNew.kt 各一份)
  - `$$` 内 `-`/`*`/`+` 开头被解析成列表 → 之前已在 preProcess 折行处理 (用户测试 6-11 项当时报正常)

### Workspace 工具增强 (给 agent 自己用的)
- `2e742c2`: patch 备份 + restore 工具
- `a68b2b6`: apply_patch 空行上下文容错
- `b680d8f`: edit_file 多编辑 (edits 数组) + shell `\r` 进度条折叠
- `f448072`: read_file 批量读 (paths[], ≤8 个)
- `dd6efa6`: **workspace_grep** (结构化搜索 {path,line,text}, glob 过滤, 复用 WorkspaceFileSystem.grep) + **workspace_shell_background** (start/output/kill/list, 注册表在 `WorkspaceRepository`, 进程封装在 `workspace/.../WorkspaceBackgroundProcess.kt`, ProotShellRunner/HostShellRunner 新增 startProcess())
- rootfs 已装 curl/wget/jq

### 关键文件索引
```
app/.../ui/components/richtext/LatexText.kt      # processLatex 兼容层 + 行内测量/放置策略 (核心)
app/.../ui/components/richtext/Markdown.kt        # 旧渲染路径, INLINE_MATH 调用点 ~L1252
app/.../ui/components/richtext/MarkdownNew.kt     # 新渲染路径(聊天在用), span.math 调用点 ~L990, preProcess ~L97
app/.../ui/components/richtext/MathBlock.kt       # 块级公式 LatexAutoWrap
app/.../data/ai/tools/WorkspaceTools.kt           # 全部 workspace 工具定义 (~1660 行)
app/.../data/repository/WorkspaceRepository.kt    # 后台进程注册表 + grepFiles
workspace/.../WorkspaceShellRunner.kt             # ShellStreamCollector(public), startProcess 接口
```

## 3.5 注意: 工作区有其他 agent 并行工作

- 工作区里未提交的图像 Provider / TTS 相关改动 (`ImageProviderSetting.kt`, `OpenAIImageProvider.kt`, `ImageSource.kt`, `UpdateChecker.kt` 等) 是**另一个 agent 在做的**, 不归我们管
- **不要 stash / 提交 / 修改这些文件**; 自己 commit 时必须逐个指定文件路径, 严禁 `git add -A` / `git commit -a`

## 4. 待办 / 未完成

1. **等 CI 出 `ba271f2` 的 APK**: 用户装后可测 spawn_agent (助手设置→本地工具→子代理开关) + 8 项 LaTeX 修复
2. **定时任务 (Feature B)**: 下一个大件, 见 PLAN_AGENT_AUTOMATION.md §2, 从 Tasks.1 (Entity+DAO+migration+UI) 开始
3. **工具 UI**: workspace_grep / workspace_shell_background / spawn_agent 没有专属 ToolUIRenderer, 现在走默认渲染, 可选优化
4. 备份自动清理策略 (WorkspaceToolConfig.Backup 有配置项, 清理逻辑未验证过) — 低优先级

## 5. 用户偏好 (重要)

- 纯小白, 技术决策全权交给 agent, "按你的来"
- 要求所有改动放同一分支保持连续性
- 不要装 Java/大依赖撑爆手机存储
- 必须出 Release (带 APK), 他手动更新实测后反馈
- 沟通用中文, 简洁直接
