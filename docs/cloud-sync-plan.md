# RikkaHub 云锚点同步重构 · 总体设计 Plan

> 版本 v1.1 · 2026-07-28 · 基于 fuwei99/rikkahub `master`（HEAD `b0f729a`）源码逐文件核实
> v1.1：P3 定稿——媒体一律先入 R2 + 发送时能力适配；生图镜像/回退/删除联动；资产所有权 v2（会话解耦）；多 R2 账户（§3.3/§3.4/§5.1 #8-11）
> 所有"现状"描述均有源码出处（文件:行号），不含臆测。
>
> **实施进度**：P0 已完成并推送——commit `93cf8db`：D1Client/D1Config/D1Schema、`AwsSignatureV4.presignGet`、Room v27（sync_outbox/sync_state）、Settings.d1Config；
> hotfix：26→27 改为手写 `Migration_26_27`（仓库 schemas 只到 25.json，AutoMigration 在 CI 上不可用，遵循本仓库手写迁移惯例）。
> P1 主链路 commit `b48d8f9`（SyncEngine/outbox 写钩/生命周期调度）+ hotfix `0bd0a60`/`b0d6df7`（D1Client batch 两处类型修复）；P1 收尾 `fcfd93a`（小表 bundles + 云同步 UI + display 开关）。
> P2 已实施：SyncLockManager（locks 表 CAS，TTL 90s/心跳 30s）+ ChatService 三入口上锁 + 横幅三态 + push final check 孤儿副本化。
> P3 已实施（v1.1）：R2AccountConfig/R2MediaStore/MediaResolver/R2ImageFetcher、生图镜像（tool+imggen 页）、
> Room v28（genmedia/managed_files 加 r2 列）、删除联动 R2、删会话不删资产、R2 账户管理 UI（二次确认硬警告）；
> 顺带修 P0 遗漏：d1Config/r2Accounts 此前未持久化到 DataStore。
> 遗留：managed_files 走 bundle 上云（注册行同步）留待 P3 收尾；视频/音频 part 上云暂不在范围。

---

# 第一部分：RikkaHub 数据结构全景（源码核实版）

## 1.1 持久化介质总览

| 介质 | 位置 | 存放内容 | 出处 |
|---|---|---|---|
| Room 数据库 | `databases/rikka_hub`（WAL 模式） | 8 张实体表 + 1 张 FTS 虚表，schema v26 | `di/DataSourceModule.kt:54-56`、`db/AppDatabase.kt:30-41` |
| Preferences DataStore | `datastore/settings.preferences_pb` | `Settings` 整体序列化为**单个 JSON**，无时间戳 | `data/datastore/PreferencesStore.kt:61-62,113,617` |
| SharedPreferences ×3 | `scheduled_notifications_pref`、`rikkahub.preferences`、CrashHandler prefs | 定时通知列表、UI hooks 杂项、崩溃标记 | `data/ai/tools/local/ScheduledNotificationManager.kt:24-27` 等 |
| `files/` 目录树 | upload/ images/ avatars/ skills/ fonts/ subagents/ tts_cache/ tool_outputs/ workspaces/ logs/ | 见 §1.4 | `data/files/FilesManager.kt:859-867`（`FileFolders`） |
| JSON 注册表 | `files/workspaces/.registry.json` | 工作区注册表（刚从 Room 迁出） | `di/RepositoryModule.kt:78`、`data/registry/WorkspaceRegistryStore.kt:13` |
| cacheDir | `cache/` | 备份临时 zip 等，全部瞬态 | `data/sync/S3Sync.kt:115` |

## 1.2 Room 数据库字段级清单（v26）

**① `conversation`——会话元数据**（`data/db/entity/ConversationEntity.kt:8-35`）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | String PK | Uuid 字符串 |
| `assistant_id` | String | 所属助手，默认 `0950e2dc-...` |
| `title` | String | 会话标题 |
| `nodes` | String(JSON) | 树状结构的节点索引 |
| `create_at` / `update_at` | Long | ⚠️ `update_at` 是同步 LWW 的天然时钟 |
| `suggestions` | String(JSON) | 聊天建议，默认 `[]` |
| `is_pinned` | Boolean | 置顶 |
| `custom_system_prompt` | String | 会话级系统提示词 |
| `mode_injection_ids` / `lorebook_ids` | String(JSON) | 注入/世界书引用 |
| `workspace_cwd` | String | 工作区内绝对路径（**设备语义**） |
| `folder_id` | String | 所属文件夹分组 |

**② `message_node`——树状对话节点**（`MessageNodeEntity.kt:9-32`）：`id` PK、`conversation_id` FK（CASCADE 删除）、`node_index`、`messages` String(JSON 序列化 `List<UIMessage>`)、`select_index`。无独立时间戳；写语义 = `updateConversation` 删光该会话节点整体重写（`ConversationRepository.kt:296-306`）→ **会话即原子修改单位**。

**③ `memory`**（`MemoryEntity.kt:8-15`）：`id` 自增、`assistant_id`、`content`。无时间戳。

**④ `favorites`**（`FavoriteEntity.kt`）：`id`、`type`、`ref_key`、`ref_json`、`snapshot_json`、`meta_json?`、`created_at`、`updated_at`。

**⑤ `conversation_folder`**（`FolderEntity.kt`）：`id`、`assistant_id`、`name`、`sort_index`、`create_at`。

**⑥ `gen_media`**（`GenMediaEntity.kt`）：`id` 自增、`path`（**本地路径**，指向 `files/images/`）、`model_id`、`prompt`、`create_at`、`type`（`image_generation`/`image_edit`）、`source_paths?`。

**⑦ `managed_files`**（`ManagedFileEntity.kt:16-32`）：`id` 自增、`folder`、`relative_path`（**唯一索引**）、`display_name`、`mime_type`、`size_bytes`、`created_at`、`updated_at`。

**⑧ `workspaces`**（`WorkspaceEntity.kt:14-44`）：`id`、`name`、`root`、`shell_status`、时间戳、`tool_approvals` JSON、`runtime_type`、`runtime_config` JSON、`external_mounts` JSON。**正在迁出 Room**（2026-07-27 提交 `8fff06d`），备份时被 `DELETE FROM workspaces` 剔除（`S3Sync.kt:142`）→ 不同步。

**⑨ `message_fts`**（FTS5 虚表，`DataSourceModule.kt:64-97` 回调内创建）：`text` + `node_id/message_id/conversation_id/title/update_at` UNINDEXED 列，jieba 分词（requery SQLite 扩展注入）。**纯派生索引，本地重建，不同步。**

**迁移体系**：v26，含 17 条 AutoMigration + `Migrations_6_7/11_12/.../25_26` 手工迁移（`AppDatabase.kt:41-63`、`DataSourceModule.kt:57-64`）。

## 1.3 领域模型（内存/JSON 序列化形态）

- **`Conversation`**（`data/model/Conversation.kt:20-42`）：`id: Uuid`、`assistantId`、`title`、`messageNodes: List<MessageNode>`、`chatSuggestions`、`isPinned`、`createAt/updateAt: Instant`、`customSystemPrompt?`、`modeInjectionIds`、`lorebookIds`、`workspaceCwd?`、`folderId?`；**`@Transient isTemporary/newConversation` 永不落库**。`files` getter（L47-50）递归收集 parts 中 `file://` 开头的本地附件 URI。
- **`MessageNode`**（同文件 L116-127）：`id`、`messages: List<UIMessage>`（**同一位置的多个回答变体/分支**）、`selectIndex`；`@Transient isFavorite`。
- **`UIMessage`**（`ai/.../ui/Message.kt:20-28`）：`id`、`role`、`parts`、`annotations`（含 `UrlCitation`）、`createdAt/finishedAt: LocalDateTime`、`modelId: Uuid?`、`usage: TokenUsage?`、`translation?`。
- **`UIMessagePart`** sealed（同文件 L352-450+）：⭐关键——
  - `Text`、`Reasoning`、`Search`、`ToolCall`、`ToolResult`、`Tool`（工具调用，output 可嵌套 parts）
  - **`Image(url)` / `Video(url)` / `Audio(url)` / `Document(url)`**：`url` 字段**天然多形态**——`file://` 本地附件、`data:image/...;base64,` 内联、`https://` 远程链接三种形态都在生产使用（`Conversation.kt:167-173`、`ai/.../Message.kt:66`、`OpenAIImageProvider.kt:494`）→ **换成 R2 URL 不需要改 schema，只改生产者**。
- **`ProviderSetting`** sealed（`ai/.../provider/ProviderSetting.kt:26`）：`OpenAI` / `Google` / `Claude` 三子类，含 `id/enabled/name/models/balanceOption`，属 Settings 内嵌。
- **`Assistant`**（`data/model/Assistant.kt:16-26+`）：`id`、`chatModelId?`（null=用全局）、`name`、`avatar: Avatar`（含 URL 形态）、`tags`、`systemPrompt`、`temperature/topP/contextMessageSize` 等。

## 1.4 `files/` 目录树（`FileFolders` 常量，`FilesManager.kt:859-867`）

| 目录 | 内容 | 文件名规则 | 同步价值 |
|---|---|---|---|
| `upload/` | 聊天附件（图/文档/视频/音频） | UUID 名（`buildUuidFileName`） | ⭐ 高（消息引用） |
| `images/` | 模型生成的图片（`gen_media` 指向） | UUID 名 | 高 |
| `avatars/` | 助手/用户自定义头像 | 命名文件 | 小但必须（头像引用） |
| `skills/` | 用户安装的 Skill（多文件目录，`SkillManager`/`SkillPaths` 有路径防护） | 目录树 | 中 |
| `subagents/` | 子代理模板 `*.json`（一模板一文件，缺省时写出厂模板） | `<name>.json` | 中（用户点名要） |
| `fonts/` | 自定义聊天字体 | — | 低（大、可选） |
| `tts_cache/` | 按 messageId 缓存的 TTS 音频 | — | ❌ 可再生缓存 |
| `tool_outputs/` | 工具临时产物（启动清理，`RikkaHubApp.kt:159`） | — | ❌ 瞬态 |
| `workspaces/` | 各工作区 rootfs/编译环境 + `.registry.json` | — | ❌ 铁律隔离（+`logs/` 日志 7 天滚动 ❌） |

## 1.5 Settings 巨无霸对象（`PreferencesStore.kt:617-666`，单 JSON 无版本戳）

- **账号资产（必同步）**：`providers`（含 API key）、`imageProviders`、`assistants`、`assistantTags`、各模型选择与各 prompt（chat/fast/title/image/translate/ocr/compress/suggestion）、`searchServices(+CommonOptions)`、`mcpServers`、`fileProcessingServices`、`ttsProviders`/`asrProviders`、`modeInjections`、`lorebooks`、`quickMessages`、`favoriteModels`、`customThemes`、`s3Config`/`webDavConfig`。
- **设备观感（同 JSON 命运）**：`themeId`/`dynamicColor`、`displaySetting`（`fontSizeRatio`、模糊、音量键等 30+ 字段，`PreferencesStore.kt:686-723`）、`webServer*` 系列。
- **volatile（必须剔除防同步风暴）**：`launchCount`（每次启动 +1，`RikkaHubApp.kt:99-111`）、`backupReminderConfig.lastBackupTime`、`sponsorAlertDismissedAt`。

## 1.6 其余零散数据

- 定时通知 `ScheduledNotificationItem`（`id: Int`（⚠️ `时间戳%1e6` 生成，**双机必撞**）、timeMs、repeatRule、enabled，`ScheduledNotificationManager.kt:13-22,44`）；AlarmManager 注册本机化，**缺 BOOT 重注册 receiver**（`AndroidManifest.xml:143-149`）。
- 工作区 `.registry.json`：`WorkspaceRegistryData{version, workspaces: List<WorkspaceRecord>}`（`WorkspaceRecord.kt:10-19`）——`externalMounts` 将来走单独白名单同步，本轮不动。
- 现有备份链：`S3Sync`/`WebDavSync` 双生实现，全量 zip（settings.json + **裸拷 db + live WAL/SHM** + upload/skills/fonts），恢复后弹框 `exitProcess(0)`（`S3Sync.kt:121-200,234-264`、`BackupDialog.kt:20`）——**WAL 混快照有潜在一致性缺陷**，P4 顺手修。

---

# 第二部分：目标（Goals）

## R 系列需求

| # | 需求 | 验收指标 |
|---|---|---|
| R1 | **云端为唯一事实源**：D1 装全部同步文本，R2 私有桶装全部二进制 | 本地 Room 可随时格式化后全量重建 |
| R2 | **秒级无感 diff**：启动/回前台一次 SQL manifest 比对，仅拉差异 | 无变化时 0 次写、1 次读、<1s；有变化按量 |
| R3 | **防双写互斥锁**：同一会话禁止双设备同时"发消息/编辑"，D1 单语句 CAS | 双开互踢可见（角标+拦截条），被偷锁有善后 |
| R4 | **工作区铁律**：rootfs、`.registry.json`、Workspaces 表永不离开本机 | 审计同步范围内无 workspace 字节 |
| R5 | **附件/图片 R2 化**：发送图压缩、文档/音视频原字节→私有桶；生成图镜像→私有桶→URL 渲染 | 消息 part 的 `url` 为 R2 URL；发送前按类型解析为预签名 URL、data: 或临时 file://；`files/upload、images` 不再做同步源 |
| R6 | **零新增服务端**：无 Worker、无后端，App 直连 D1/R2 | 月成本 = ¥0（免费线内） |
| R7 | **数据安全**：桶私有 + 预签名过期 URL；D1 Time Travel 兜底 | 裸 URL 403；泄漏窗口 ≤1h |

## 非目标（Non-Goals）

多用户/共享协作、实时在线协同编辑、workspace rootfs 迁移、E2E 客户端加密（与"LLM 按 URL 抓图"互斥，已拍板放弃）、TTS 音频全量同步（可再生，允许后续按 URL 模式追加）。

---

# 第三部分：架构

## 3.1 总览图

```
                     ┌──────────────── 唯一事实源 ────────────────┐
                     │  Cloudflare D1（文本）   R2 私有桶（字节）  │
                     │  ┌ conversations      ┐  ┌ files/chat/*  │
                     │  │ bundles(kv)        │  │ files/images/*│
                     │  │ locks              │  │ files/avatars │
                     │  └────────────────────┘  │ snapshots/*   │
                     └───────▲────────────────────────▲──────────┘
              HTTPS+API Token│               SigV4(预签名GET/PUT)│
        ┌────────────────────┴───────────────────────────────────┐
        │  SyncEngine（diff/pull/push 调度器）                    │
        │  ├─ D1Client      （仿 S3Client，ktor）                 │
        │  ├─ R2MediaStore  （压缩上传/镜像转存/presign）          │
        │  └─ SyncLockManager（CAS acquire/heartbeat/release）    │
        ├─ 写钩：ConversationRepository / SettingsStore / 小表Repo │
        ├─ sync_outbox（本地待推队列，v27 新表）+ sync_state       │
        ├─ Room rikka_hub = 可炸毁重建的读缓存 + 读模型（UI 零改） │
        └─ image parts = R2 URL（Coil 现签现渲染，不落持久区）     │
```

## 3.2 D1 Schema（DDL）

```sql
-- 一行 = 一个完整会话（含 message_node 树 JSON），利用"会话即原子单位"现状
CREATE TABLE conversations(
  id         TEXT PRIMARY KEY,
  title      TEXT,
  updated_at INTEGER NOT NULL,      -- 毫秒 epoch，写方时钟
  deleted    INTEGER NOT NULL DEFAULT 0,
  sha        TEXT NOT NULL,          -- data 的 sha-256，diff 快速判等
  data       TEXT NOT NULL           -- Conversation 域模型 JSON
);

-- 一切"小而杂"统一 KV：settings / settings.display(受开关控制) / memory / favorites /
-- folders / genmedia / schedules:<uuid> / subagents:<id> …（条目级 LWW，deleted=墓碑）
CREATE TABLE bundles(
  k          TEXT PRIMARY KEY,
  updated_at INTEGER NOT NULL,
  deleted    INTEGER NOT NULL DEFAULT 0,
  sha        TEXT NOT NULL,
  data       TEXT
);

-- 会话互斥锁（单语句 CAS，利用 D1 全局单写者）
CREATE TABLE locks(
  conv_id    TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL,
  op         TEXT NOT NULL,          -- generating | editing
  expires_at INTEGER NOT NULL        -- 租约到期；过期即可被任何设备接管
);
```

**关键 SQL 模式**：

- diff（启动一次）：`SELECT id,title,updated_at,sha,deleted FROM conversations;` + `SELECT k,updated_at,sha,deleted FROM bundles;`（title 内嵌 → 会话列表可直接渲染）
- 乐观写（冲突即知）：`UPDATE conversations SET ... WHERE id=? AND updated_at=?base;`——影响行数 0 = 云上被改过 → 回拉合并
- 锁获取（真 CAS，无 echo-check）：`INSERT INTO locks(...) VALUES(?,?,?,?) ON CONFLICT(conv_id) DO UPDATE SET ... WHERE locks.expires_at < ?now OR locks.device_id = ?self;` 随后读回校验持有者

## 3.3 R2 对象规划与预签名

```
chat-uploads/<uuid>.*       用户发送附件：图片压缩后上传，文档/音视频保留原字节
gen-images/<uuid>.png       AI 生成图的永久镜像（防渠道 URL 过期）
avatars/<name>              头像（可选同步）
snapshots/backup_*.zip      低频保险快照（P4）
```

- **桶私有**：不绑域名/不开 r2.dev；读 = `AwsSignatureV4` 扩展 presign（query-string，`UNSIGNED-PAYLOAD`，TTL 3600s）；写 = 现有 header 签名 PUT。
- **多 R2 账户（v1.1 已拍板，取代原"多桶扩展位"）**：`r2Accounts: List<R2AccountConfig>`（`id(uuid)`/别名/accountId/accessKeyId/secretAccessKey/bucket/`enabled`），配置于设置→数据页云同步区块：
  - 每个账户手动启停，**仅决定新上传去向**（上传目标 = 第一个 enabled 账户，UI 明示"新上传 → XXX"）；旧对象读取不受影响；
  - **读取路由**：对象引用自带账户 uuid（`r2://<acctUuid>/<key>`；`managed_file`/genmedia 行与 part metadata 均存 acct 字段），渲染/发送按引用找账户现签现用；
  - **删除账户或更换密钥** → 二次确认 + 硬警告「会导致所有指向该桶的附件/生图不可引用」；
  - `r2Accounts` **必须完整随 settings 同步（含 secretAccessKey，与 LLM key 同级敏感度）**，否则其他设备只能拿到 r2:// 引用却无法 presign/download；`d1Config` 保持设备本地（每机填一次，引导锚点）。
- 消息内的 `url` 统一存 R2 **对象引用**（实际形态 `r2://<acctUuid>/<key>`），渲染/发送时**现签现用**——避免把长签名串序列化进同步 JSON；长消息 parts 外迁引用存 metadata，避免 hydrate 失败时把 `r2_parts:` 明文展示给用户。

## 3.4 附件/图片双流

**上行（发给 AI，v1.1 拍板：与 provider 能力解耦）**：一律「先入库后适配」——相册选图/粘贴 `data:` → 图片走现有压缩管线（`FilesManager` 2560/JPEG85），文档/音视频保留原字节 → `R2MediaStore.put`（投向当前上传账户）→ part = `Image/Document/Video/Audio(url=r2://<acct>/<key>)`。`data:` 内联**必须外迁**（D1 单行 ~2MB 上限，base64 图会撑爆会话 JSON）。发送时由 app 层 `MediaResolver`（挂起预处理 pass）按目标 provider 能力重写 part：图片可变成预签名 URL 或 data:base64；Google/Gemini 走 URL transport 时文档/音视频直接使用 R2 预签名 URL 作为 `fileData.fileUri`；其他 provider 或 base64-only 路径才从 R2 下载到 cache 临时 `file://`，再交给 `DocumentAsPromptTransformer`、MinerU、本地 PDF/DOCX/PPTX/EPUB parser 或 provider base64 编码读取。顺带修复存量问题：本地文件丢失 → `FileEncoder.encodeBase64`/文档解析失败；有 R2 兜底后新数据可自愈。

**文档解析缓存（源码核实）**：`DocumentAsPromptTransformer` 的解析结果不是写回会话消息，也不是 Room 实体；它是在发送前把 `Document` 转成一段 `<UploadFile>...markdown/text...</UploadFile>` 的临时 `Text` part 加入 outgoing messages。为避免同一 PDF/DOCX 反复 OCR/MinerU，解析成功后的 Markdown/Text 按稳定 key（优先 `metadata.r2_ref`，否则本地文件 path+size+mtime）永久缓存到 `files/document_parse_cache/<sha>.md`；后续发送同一文档直接读取缓存。MinerU 当前使用默认轻量 Agent API `https://mineru.net/api/v1/agent`，无 Authorization；对 R2 文档优先 presign 后走 `/parse/url`，失败再回退本地临时文件 `/parse/file` 上传模式。

**下行（AI 生成，v1.1 拍板）**：
- URL 返回型：part 先用**原 URL** 渲染（最快），后台立即异步镜像 R2 → 完成后写 `metadata={r2_key, acct, original_url, mirrored_at}`；Coil 加载原 URL 失败（过期 403/404）→ 有 r2_key 自动换预签名 URL 重试。`UIMessagePart` 现成口袋 `metadata: JsonObject?`（`ai/.../ui/Message.kt:364`），JSON 结构零破坏，Video/Audio 同理。
- base64 返回型：直接传 R2——原图**原字节不压缩** + 另存 LLM preview 压缩版（沿用 `createLlmPreviewImageFile` 思路），两对象同属一条 genmedia 记录；本地**不落管理态文件**（显示靠 Coil 磁盘缓存，发送走临时下载）。
- `GenMediaEntity` 增加 `r2_key`/`acct`/`original_url` 列 → 手写 Migration v27→v28。

**所有权与删除（单一所有者 v2，用户拍板，与现状语义同构）**：
- 每个 R2 对象只有一个主人：`genmedia` 行（生图）或 `managed_file` 行（聊天附件）；消息 part 永远只是引用。
- 删除入口只有两个管理页：图库删生图 / 文件管理页删附件 = 删注册行 + DELETE R2 对象 + 清本地缓存；残留消息引用失效可接受（现状 `ImgGenVM.deleteImage` 即此语义）。
- **删会话不删任何资产**（附件与会话解耦，废除云侧 cascade；现状 `deleteChatFiles` 的本地级联同步移除）；`managed_file` 走 bundles 上云 → 另一设备文件管理页可见同一套云资产清单。
- 上传按次建对象、不做内容去重 → 天然单所有者：无引用计数、无级联、无 objects 表。（可选后续：文件管理页"无引用附件"筛选 + 月度对账 GC。）

## 3.5 同步引擎（SyncEngine）

- **写路径**：各 Repository 写钩 → 写 Room + 追加 `sync_outbox`（v27 新表：kind/key/base_mtime/retry）→ 在线立即 flush，失败 WorkManager backoff；settings push 强制防抖 10s（`launchCount` 等 volatile 字段**上云前剔除**，见 §5.2）。
- **读路径**：ProcessLifecycleOwner `ON_START` → manifest diff → 仅拉差异 → DAO upsert → Room Flow **自动刷新 UI**（UI 层零改动，且根治了原计划的 DB 热替换死结）。
- **锁时序**（R3）：发消息/编辑前 `acquire`（拦截条显示对端 `device_name` + 剩余秒数 + "强制接管"）→ 生成中 30s 心跳续期（绑 GenerationHandler 生命周期，`ON_STOP` 不放锁）→ finally `release` →**push 前 final check 锁仍姓我**，被偷锁则本地存孤儿副本不覆盖云端；成功重新 acquire 时清除本机会话的 stolen 标记。
- **`device_id`**：本机生成持久保存，**device-local，绝不入 settings 同步**（唯一会毁掉锁语义的细节）。

## 3.6 各类数据冲突语义

| 数据 | 粒度 | 策略 |
|---|---|---|
| conversation（含 nodes） | 会话级 | `update_at` LWW + 乐观写冲突回拉；本地写入统一刷新 `updateAt`；base=0 撞到远端同 id 时先孤儿化本地内容再采纳远端，避免消息被吞；双写被 R3 锁提前阻断 |
| settings | 整体 | LWW + volatile 剔除；`displaySetting` 独立 `settings.display` bundle，受设备本地开关控制 |
| memory/favorites/folders/genmedia/managed_files | 当前实现：整表 bundle | 云端 payload 作为整表事实源；pull 时清空本地同表后重建，确保删除同步。后续若要真条目级需改为 `type:<id>` key + tombstone |
| schedules | 尚未完整落地 | 当前只补 BOOT receiver；`id→Uuid`、`updatedAt`、同步挂钩、reconcile 仍待做 |
| subagents/skills | 当前实现：subagent 模板整包；skills 未同步 | LWW；出厂模板覆盖/删除语义需继续收敛 |
| 图片/附件字节 | R2 对象 | 随机 UUID 对象；删除由 genmedia/managed_files 注册行入口联动；文档解析结果本机缓存于 files/document_parse_cache；孤儿 reconcile 尚待做 |
| workspaces/registry/FTS/tts_cache/logs/tool_outputs | — | **永不同步**；document_parse_cache 目前也是本机派生缓存，可由 R2 原文档重建 |

---

# 第四部分：改动清单（按 Phase）

## P0 · 基建（新文件为主）

| 动作 | 文件 |
|---|---|
| ➕ D1 HTTP 客户端（仿 S3Client，ktor+Bearer token） | `data/sync/d1/D1Client.kt`、`D1Config.kt` |
| ➕ presign 扩展（query-string SigV4） | 修改 `data/sync/s3/AwsSignatureV4.kt`（+~30 行 `presignGet`） |
| ➕ DDL 与 schema 管理 | `data/sync/d1/Schema.kt`（启动 ensure） |
| ⚠️ SyncClock（用 R2/D1 响应 `Date` 头校准偏移）尚未实现 | `data/sync/clock/SyncClock.kt` |
| ➕ device_id 生成与本机持久化（排除同步） | `data/datastore/PreferencesStore.kt`（或独立 prefs） |
| 🆙 Room v27：`sync_outbox` + `sync_state` 两表 | `db/AppDatabase.kt` + `migration/Migration_26_27.kt` |
| ➕ 同步配置项（D1 token、多桶池） | `data/sync/d1/D1Config.kt` 并入 Settings（device-local 段） |

## P1 · 文本同步主链路

| 动作 | 文件 |
|---|---|
| ➕ SyncEngine（diff/pull/push/outbox flush） | `data/sync/core/SyncEngine.kt`、`OutboxDao` 对接 |
| 🔧 会话写钩（insert/update/delete 三入口） | `data/repository/ConversationRepository.kt:286,296,308` |
| 🔧 设置写钩 + volatile 剔除映射（含 `displaySetting` 拆分为独立 bundle、开关状态剔除） | `data/datastore/PreferencesStore.kt:454`（+`SyncSettingsFilter`） |
| ➕ 界面偏好同步开关（默认 OFF，页顶部） | `ui/pages/setting/SettingPreferencesUIPage.kt` |
| 🔧 小表 bundles 化（memory/favorites/folders/genmedia DAO 写钩） | 各 `db/dao/*.kt` 与 repository |
| ➕ 生命周期调度（ON_START diff / ON_STOP flush+debounce） | `data/sync/core/LifecycleSyncObserver.kt` + `AutoSyncWorker.kt`（依赖已就位：`lifecycle-process`、`workmanager 2.11.2` 已在 `app/build.gradle.kts:155-156`） |
| ➕ 首次装机 seeding（本地全量上推/他机拉取向导） | `ui/pages/backup/tabs/` 新增 `CloudSyncTab` |
| 🔧 DI 注册 | `di/DataSourceModule.kt`、`di/RepositoryModule.kt` |

## P2 · 互斥锁（R3）

| 动作 | 文件 |
|---|---|
| ➕ SyncLockManager（acquire/heartbeat/release/forceTakeover/finalCheck） | `data/sync/core/SyncLockManager.kt` |
| 🔧 发送入口接入（生成前 acquire，finally release，心跳跟着生成走） | `ui/pages/chat/ChatVM.kt:270-307`、`data/ai/GenerationHandler.kt` |
| 🔧 编辑入口接入（重命名/pin/移文件夹/消息编辑，短租约） | `ChatVM.kt updateConversation` 等 |
| 🔧 UI 三态：列表角标 / 发送拦截条（对端机型+剩余秒+强制接管）/ 被偷锁孤儿提示 | 会话列表 item、`ChatPage` |
| 📝 `device_name`（Build.MODEL）进锁对象 | — |

## P3 · 附件/图片 R2 化（R5）

| 动作 | 文件 |
|---|---|
| ➕ R2MediaStore（压缩上传/镜像转存/presign/多桶路由预留） | `data/files/R2MediaStore.kt` |
| 🔧 发送管线：图片压缩、文档/音视频原字节→PUT R2→part.url=R2 引用；发送前文档/音视频下载为临时 file:// 供现有解析器读取 | `service/ChatService.kt` + `data/sync/r2/MediaResolver.kt` |
| 🔧 provider 能力分支（URL vs base64 回退） | `ai/.../providers/ClaudeProvider.kt`、`GoogleProvider.kt`（≥2.5 才用 URL）、`providers/openai/ChatCompletionsAPI.kt` |
| 🔧 生图镜像化（拿到 URL/base64 → 后台转存 → 存 R2 引用） | `ui/pages/imggen/ImgGenVM.kt:330`、`data/ai/tools/ImageGenerationTool.kt:301` |
| 🔧 Coil 预签名渲染拦截 | 图片加载 DI（`di/`）Coil 配置处 |
| ⚠️ 历史数据迁移脚本（file:// → R2 一次性搬运，可断点续传）尚未实现；新发送 file:///data:image 已外迁 | `data/sync/migration/ImageCloudMigrator.kt` |

## P4 · 周边与收尾

| 动作 | 文件 |
|---|---|
| ⚠️ 定时通知：目前仅补 **BOOT_COMPLETED receiver**；`id→Uuid` + `updatedAt` + sync 挂钩 + reconcile 未完成 | `data/ai/tools/local/ScheduledNotificationManager.kt`、`receiver/`、`AndroidManifest.xml` |
| 🔧 子代理模板 bundles 同步（出厂模板内容一致不推；先 pull 后 ensureDefault） | `data/ai/subagent/SubagentTemplateManager.kt:16-82` |
| 🔧 现有 zip 快照：修 WAL 混快照（`wal_checkpoint(TRUNCATE)`/`VACUUM INTO`）+ 降频为每日 | `data/sync/S3Sync.kt:121-160`、`webdav/WebDavSync.kt:136-179`（双生同步修） |
| ➕ 定时快照 Worker；⚠️ 孤儿 R2 对象 reconcile 未完成 | `data/sync/core/SnapshotWorker.kt` |
| ⚠️ 测试：D1Client/锁 CAS/outbox 重试/迁移脚本仍缺专项覆盖 | `app/src/test/` |

---

# 第五部分：决策记录与悬而未决

## 5.1 已拍板（按讨论顺序）

1. ✅ 云锚点 + 本地缓存模型，放弃双向文件同步与 DB 热替换（原 Plan Phase 3 整体作废）
2. ✅ D1 装文本 / R2 私有桶装字节；R2 不当数据库
3. ✅ 定时通知"数据上云、注册本机"；子代理模板纳入同步
4. ✅ 会话级互斥锁（D1 CAS），非全 App 锁，非零锁分支合并
5. ✅ 私有桶 + 预签名 URL（TTL≤1h），拒绝无签名公网访问；文件名保持 UUID 双保险
6. ✅ 生成图必须镜像防渠道 URL 过期；provider 按能力降级 base64
7. ✅ 界面观感字段做 `settings.display` 独立 bundle + 设备级参与开关（默认 OFF），开关置于界面偏好设置页顶部（§5.2）
8. ✅ 图片/文档/音视频字节一律先传 R2（与 provider 能力无关）；发送时挂起 resolver 按能力选预签名 URL、base64，或下载为临时 file:// 供文档解析/Google inlineData 使用；`data:` 内联必须外迁（D1 ~2MB 行上限）
9. ✅ 生图 URL：原 URL 先渲染 + 异步镜像 + 失效自动回退 R2；图库存 R2 版、删除联动删 R2；base64 生图直存 R2（原字节）+ preview；本地无管理态图片文件
10. ✅ 资产所有权收敛：`genmedia`/`managed_file` 注册行是唯一主人，**删会话不删资产**；删除只发生在图库/文件管理两个入口；上传不去重、无 refcount
11. ✅ 多 R2 账户：启停只影响新上传去向；引用携账户 uuid 读时路由；删除账户/换密钥二次确认 + 硬警告；账户配置随 settings 同步，`d1Config` 设备本地
12. ✅ P4 改进项与架构升级定案（2026-07-28 用户定案 + 评审）：
    - **分层存储架构**：D1 存轻量元数据与消息索引，message parts 大 JSON 切分存入 R2（`snapshots/{convUuid}/msgs/{msgUuid}/parts.json`），解决大输出/多次编辑挤爆 D1 单行 2MB 限制；
    - **轻量 D1Table<T> 抽象**：声明列名 + `toRow()/fromRow()` 映射，避免手写拼接 SQL，无需重量级 KSP 注解处理；
    - **快照一致性与 WAL**：导出快照前 `PRAGMA wal_checkpoint(TRUNCATE)` 并采用 `VACUUM INTO`，抛弃旧 `-wal` 裸拷逻辑；
    - **Outbox 防死循环与熔断（Circuit Breaker）**：`SyncOutboxDao` 加 `WHERE retry_count < 5` 隔离毒丸 payload；单项失败会向上抛出，手动同步会同时报告已隔离的毒丸 payload，不再假成功；`SyncEngine` 1 小时内连续失败 >10 次挂起自动同步并横幅报警；D1 多语句 batch 当前按顺序逐条执行，避免手工拼接 SQL + 扁平 params 的兼容性风险。

## 5.2 ✅ 已拍板：界面观感字段「参与同步开关」（2026-07-28 用户定案）

**设计（方案丙，优于甲乙案）**：

- `displaySetting` 整包（`fontSizeRatio`/气泡/音量键/`webServer*` 等）从主 settings JSON 中拆出，独立为 bundle key **`settings.display`**；
- 新增一个设备本地开关 **`syncDisplaySettings`**（默认 **OFF**，自身永不参与同步，与 `device_id`/volatile 字段同属 device-local 段）；
- **OFF（默认）**：该设备既不推也不收 `settings.display`——各机字号各管各的；
- **ON**：`settings.display` 走正常条目级 LWW（推本地变更 / 收云端变更）；
- 组合语义：两台都 OFF＝各自为政都 ON＝LWW 统一；一开一关＝关的那台不受打扰，哪天想统一了再开。
- **UI 落点**：开关置于界面偏好设置页 **顶部**：`ui/pages/setting/SettingPreferencesUIPage.kt`，文案"同步界面偏好设置"，副标题"开启后，字号、气泡等界面偏好将跨设备同步（默认关闭）"。
- P1 设置同步的阻塞解除。

（`images/` 懒拉旧议题已被 R5 取代作废。）

## 5.3 主要风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| D1 API token 上云 | 中 | `d1Config` 设备本地，永不上 D1 bundles |
| R2 secret 泄漏 | 中 | R2 secret 必须随 settings 上云（与 LLM API key 同级），否则其他设备无法读取 r2:// 对象；依赖私有桶 + 最小权限密钥 + Cloudflare 可随时轮换 |
| 软/硬锁边界：心跳期间 D1 不可写 | 低 | 自动重试 + 到期接管 + 强制接管人工门 |
| 历史 file:// 消息在新机看旧图 | 中 | 新发送的 file:// 与 data:image 会外迁 R2；历史 file:// 一次性迁移脚本仍待补 |
| 双机各改小表条目后整包互踩 | 中 | 当前实现是整表 bundle，只保证删除可收敛；真条目级需后续改 `type:<id>` key + tombstone |
| Settings 单 JSON LWW 吞字段 | 中 | volatile 剔除 + 全量序列化前 diff 合并 `SettingsJsonMigrator` 已有先例 |
| D1 免费线：100K 行写/天 | 极低（估算利用率<2%） | 用量埋点 + 快照低频 |

---

## 附：工程量粗估

| Phase | 核心产出 | 量级 |
|---|---|---|
| P0 | D1Client + presign + v27 + 时钟/ID | 小（~0.5k 行） |
| P1 | SyncEngine + 写钩 + 调度 + 装机向导 | 中（~1.5k 行，主战场） |
| P2 | 锁管理器 + 两个入口 + UI 三态 | 小（~0.5k 行） |
| P3 | 附件/图片双流 + provider/文档解析适配 + 迁移 | 中（~1k 行，回归风险最大） |
| P4 | 通知/子代理/快照收尾 + 测试 | 小 |
