# RikkaHub 项目 AI 开发交接指南 (Handover Document)

## 基本信息
* **交接日期**：2026-07-22
* **上一阶段开发者**：江锋 & 程天赢
* **仓库地址**：`https://github.com/fuwei99/rikkahub.git`
* **基础框架与版本**：基于原作者上游最新分支 `upstream/master` (v2.4.2)
* **核心目标**：为下一位接手本项目的 AI 开发者提供完整的代码库架构视图、分支分工、模块功能定位及核心定制特性的实现细节，确保开发无缝延续。

---

## 1. Git 分支体系与用途指南

本仓库采用了清晰隔离的分支策略，请在新任务开始前明确各分支的定位：

| 分支名称 | 状态/位置 | 详细用途与说明 |
| :--- | :--- | :--- |
| **`master`** | **当前主干 (Default)** | **核心开发分支**。与上游 2.4.2 基底保持同步，集成了思维链 ON/MAX 档位与网关解耦、确定性 Prompt 缓存、搜索瘦身、零延迟流式 TTS 与离线缓存管理、细粒度数据导出 (.rikka 角色包) 等独家特性。 |
| **`upstream-original`** | **干净对照分支** | **上游原作者代码追踪分支**（基于 `upstream/master` 2.4.2）。纯净未修改，仅用于定期 `git fetch upstream` 后进行 Pull/Diff，提取上游最新 commit，避免误 merge 破坏本地定制逻辑。 |
| **`backup-master-legacy`** | **历史存档分支** | 归档了全量重构前（包含老旧语音引擎与自定义脚本）的所有历史提交。如需查找以往任何旧逻辑，可在此分支检索。 |
| **`feat-native-latex-wrap`** | 归档分支 | 原生 JLatexMath Canvas 物理盒模型与基线对齐的探索合并分支。 |
| **`feat-webview-latex`** | 废弃分支 | 早期 WebView 渲染 LaTeX 的探索分支（因卡顿和焦点问题已被废弃）。 |
| **`sync-upstream-master`** | 临时分支 | 上游合并测试用临时分支。 |

---

## 2. 模块架构与目录分布

项目采用了标准的 Android 多模块 Gradle 架构：

```
rikkahub/
├── app/                  # 主应用模块：UI (Jetpack Compose)、ViewModels、Room 数据库、系统 EventBus、Hilt/Koin DI、数据迁移
├── ai/                   # AI SDK 核心抽象层：支持 OpenAI (ChatCompletions & Response API)、Claude、Google Gemini 等各大 Provider
├── search/               # 联网搜索与网页抓取 SDK：集成 Exa, Tavily, Serper, Zhipu, Bing, SearXNG 等搜索引擎
├── speech/               # 语音服务模块：包含音视频播放管线 (ExoPlayer)、TTS 流式/分段控制器 (TtsController)、ASR 语音识别控制器
├── common/               # 公共工具库：Http 封装、OkHttp SSE 扩展、JsonExpression 语法树解析
├── document/             # 文档解析模块：支持 PDF、DOCX、PPTX、EPUB 文件解析
├── highlight/            # 代码高亮库：用于 Markdown 中的代码语法高亮
├── material3/            # Material 3 动态色彩扩展库
├── web/                  # 嵌入式 Web 服务器模块：Ktor Server 运行底座与 React 前端 (`web-ui/`) 资产托管
├── web-ui/               # React / Tailwind 编写的网页前端项目
└── .test/开发日志/        # 历次迭代的详细 Markdown 工作日志与交接记录
```

---

## 3. 本地开发与 GitHub CI 构建规范

### A. 快速本地编译检查命令
为避免等待几分钟的前端/NDK 编译，本地语法与类型检查请使用以下命令：
```bash
./gradlew :app:compileReleaseKotlin -x :web:buildWebUi --no-daemon
```
*如需本地运行调试 APK*：执行 `./gradlew assembleDebug` 即可。

### B. 调试签名统一
项目在 `app/debug.keystore` 提交了共享 Debug 签名库（密码 `android`），在 `app/build.gradle.kts` 中已配置自动加载，**无需因签名不一致而频繁卸载重装 App**。

### C. GitHub Actions CI
文件路径：`.github/workflows/release.yml`
* 提交代码至 `master` 会自动触发 CI 打包发布 GitHub Release。
* CI 配置了 Mock `google-services.json` 自动容错及 `--no-configuration-cache` 编译标志。

---

## 4. 本项目专属核心定制特性（重点关注）

接手者在修改相关模块时，请严格遵守以下逻辑规范：

### A. 思维链 (Reasoning Level) 控制与 API 网关解耦
* **入口文件**：`ai/.../Reasoning.kt` & `ai/.../ChatCompletionsAPI.kt`
* **规则**：
  * 支持 `OFF`, `ON`, `AUTO`, `LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX` 档位。
  * `getSupportedLevels(modelId)` 会根据 Claude 等模型 ID 动态裁剪允许选择的档位。
  * **网关兼容**：针对第三方 / 自定义 OpenAI 网关，当用户选择 `ON` 或 `AUTO` 时，**绝对不能硬编码强塞** `"thinking": {"type": "disabled"}`，以防对方 API 收到 `reasoning_effort` 时触发 400 崩溃。

### B. 确定性消息模板与 100% Prefix Prompt Caching 命中
* **入口文件**：`app/.../transformers/TemplateTransformer.kt`
* **规则**：
  * 求值 `{{ cur_datetime }}` / `{{ time }}` 等时间变量时，**必须且只能使用 `message.createdAt`**（消息发送时的固有时间戳），**严禁使用 `Instant.now()`**。
  * 保证历史消息转换后的文本完全固定不变，新增消息仅在尾部追加，以确保大模型 API 的 **Prefix Prompt Caching 100% 缓存命中**。

### C. 历史搜索结果上下文瘦身 (Clear History Search)
* **入口文件**：`app/.../transformers/ClearHistorySearchTransformer.kt`
* **规则**：
  * 在发送给 API 之前的内存打包流水线中，自动过滤掉历史消息里的 `search_web` 和 `scrape_web` 长正文。
  * 本地数据库与 UI 界面卡片依然保留原样展示，零损伤体验。

### D. 零延迟流式 TTS 与离线缓存管理
* **入口文件**：`speech/.../AudioPlayer.kt` & `speech/.../TtsController.kt` & `app/.../ChatMessageActions.kt`
* **规则**：
  * 底层基于 `StreamingDataSource` 直连 ExoPlayer，云端 TTS 流推送时**零等待秒开播放**，并同步写入 `context.cacheDir/tts_cache/tts_${messageId}.${ext}`。
  * **绿灯指示**：当 `hasAudioCache(messageId)` 为 true 时，UI 喇叭图标变绿 (`#4CAF50`)，提示存在本地离线音频，可零 Token 秒开重播。
  * **文件管理**：设置 -> 文件管理页面中新增了 `TTS Cache` 选项卡，支持手动单条或批量一键清空音频缓存。

### E. 细粒度数据导出与单助手角色包 (.rikka)
* **入口文件**：`app/.../exporter/AssistantExporter.kt` & `BackupVM.kt`
* **规则**：
  * 支持单独导出设置文件 `settings.json`（仅配置与 Provider）。
  * 助手详情页支持将单助手的配置、Prompt、工具与专属聊天记录节点独立打包导出为 `.rikka` 文件，并支持还原导入。

---

## 5. 接手建议与注意事项

1. **上游同步流程**：
   如果原作者 `rikkahub/rikkahub` 有更新，请先切到 `upstream-original` 分支拉取：
   ```bash
   git checkout upstream-original
   git fetch upstream
   git reset --hard upstream/master
   ```
   然后对比 `upstream-original` 与 `master` 的差异，** cherry-pick 或手动提取代码**，切勿直接 `git merge`。
2. **保持文件只增不删**：
   重构或优化代码时，注意保全旧版 TTS 渠道（如豆包、火山）与历史搜索功能。
3. **查阅历史日志**：
   任何疑难杂症的修复记录均保存在 `.test/开发日志/` 目录下，可随时翻阅相关 Markdown 查看当时的排查思路。
