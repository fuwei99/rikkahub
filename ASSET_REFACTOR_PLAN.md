# RikkaHub 附件资产索引化大改造 Plan / 交接文档

> 生成时间：2026-07-30
> 当前目标：彻底合并聊天附件、生图、文件管理、R2、本地缓存、外部 URL 的多套逻辑，统一为 Asset 索引模型。
> 用户明确态度：旧附件可不兼容，找不到就显示不可用；消息能加载即可。后续新数据必须走统一资产体系。

---

## 0. 重要工作规则

- **不要在沙箱编译 / 不跑 Gradle**。用户会看 GitHub Actions 反馈。
- 允许：读源码、grep、轻量文本检查、`git diff --check`。
- 改完 commit + push 到 `master`。
- 若要 push，用用户给的临时 token + `GIT_ASKPASS`，不要写持久凭据。

---

## 1. 当前仓库状态与重要提醒

当前已推送到远端的最新 commit：

```text
416b5e9 fix(imggen): show local preview immediately in tool output
```

这个 commit 做了：

- 生图工具 UI 优先读取 `UIMessagePart.Image` 输出显示图片，不再依赖 `file_paths` / `llm_preview` 文本字段。
- Base64 生图分支工具卡片优先展示本地 preview，不等待 R2。

### 1.1 当前工作区有未提交的实验性改动

在写本交接文档前，当前工作区已有一批 **未提交** 的 Asset 改造实验性改动，涉及：

```text
app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt
app/src/main/java/me/rerere/rikkahub/data/db/dao/ManagedFileDAO.kt
app/src/main/java/me/rerere/rikkahub/data/db/entity/ManagedFileEntity.kt
app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_28_29.kt
app/src/main/java/me/rerere/rikkahub/data/files/AssetResolver.kt
app/src/main/java/me/rerere/rikkahub/data/files/AssetUri.kt
app/src/main/java/me/rerere/rikkahub/data/sync/r2/MediaResolver.kt
app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt
app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
```

这些改动是 Phase 1/2 的开头，**尚未完成，未保证可编译，未 push**。新对话接手时：

- 如果继续大改，可基于这些改动继续整理。
- 如果不想接未完成实验，可先保存 diff，然后 `git restore` 回到干净状态再分阶段做。
- 不要把这批半成品直接混进其它小修 commit。

---

## 2. 用户最终需求总结

用户已经删光旧附件，不要求旧附件可用。核心需求：

1. **聊天记录只存附件索引**，不再直接存 `file://`、`r2://`、`http(s)`、`data:image` 等真实引用。
2. 一个附件索引对应一个文件资产。
3. Asset 统一管理：
   - 本地缓存；
   - R2 云端对象；
   - 外部 URL；
   - 文件名 / mime / 大小 / hash / 状态；
   - 以后还能加 prompt、description 等字段，不影响其它模块。
4. 所有入口都走同一套 Asset：
   - 聊天上传；
   - RikkaHub 文件选择器；
   - 生图结果；
   - workspace `read_file` 读取图片；
   - MCP 返回图片；
   - 文件管理；
   - 发送给模型；
   - R2 上传/下载/缓存。
5. **R2 上传用于同步，不应阻塞聊天发送**。
6. 如果模型支持 URL，发送时优先走外部 URL / R2 presigned URL；否则用本地缓存/base64。
7. 如果本地没有缓存，但有 R2/外部 URL，需要下载并保留本地缓存。
8. 如果没有 R2，而本地有文件，需要后台异步上传 R2 并更新 Asset。
9. 旧聊天附件如果不是 asset 引用：显示不可用即可，不用迁移修复。
10. 文件 ID 建议用 **UUID**，不要用自增 Long，避免跨设备/同步重复。

---

## 3. 目标架构

### 3.1 Asset URI

新聊天附件统一写：

```text
asset://managed-files/<uuid>
```

例如：

```text
asset://managed-files/550e8400-e29b-41d4-a716-446655440000
```

对应：

```kotlin
UIMessagePart.Image(url = "asset://managed-files/<uuid>")
UIMessagePart.Document(url = "asset://managed-files/<uuid>", fileName = ..., mime = ...)
UIMessagePart.Audio(url = "asset://managed-files/<uuid>")
UIMessagePart.Video(url = "asset://managed-files/<uuid>")
```

旧的：

```text
file://...
r2://...
http(s)://...
data:image/...
/data/user/0/...
```

不再作为新消息格式。旧消息遇到这些格式时，UI 显示“附件不可用”。

---

### 3.2 ManagedFileEntity 升级为 Asset 表

建议把 `ManagedFileEntity.id` 从 `Long` 改成 `String UUID`。这是一次大迁移，但用户明确倾向 UUID。

目标字段：

```kotlin
@Entity(tableName = "managed_files")
data class ManagedFileEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),

    val folder: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,

    // 本地缓存，可空/可用空字符串表达无本地缓存
    val relativePath: String?,

    // R2 云端对象
    val r2Key: String?,
    val r2Acct: String?,

    // 外部 URL，例如用户贴的图、模型返回的 CDN URL
    val externalUrl: String?,

    // 内容去重
    val sha256: String?,

    // 以后预留
    val prompt: String? = null,
    val description: String? = null,

    val deleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
```

> 如果一次性把 `Long` 主键改 UUID 风险太高，可过渡保留 Long，但用户明确说“文件 ID 建议用 UUID”，最终应换。

---

### 3.3 AssetResolver

新增统一服务：

```text
app/src/main/java/me/rerere/rikkahub/data/files/AssetResolver.kt
```

职责：

```kotlin
class AssetResolver {
    suspend fun createFromUri(...): ManagedFileEntity
    suspend fun createFromBytes(...): ManagedFileEntity
    suspend fun createFromExternalUrl(...): ManagedFileEntity
    suspend fun createFromR2Ref(...): ManagedFileEntity

    suspend fun resolveForDisplay(assetId: String): String?
    suspend fun resolvePartForModel(part: UIMessagePart, model: Model): UIMessagePart?

    suspend fun ensureLocal(assetId: String): File?
    suspend fun ensureCloud(assetId: String): R2Ref?

    suspend fun compress(assetId: String): ManagedFileEntity?
    suspend fun deleteLocal(assetId: String)
    suspend fun deleteCloud(assetId: String)
    suspend fun deleteAsset(assetId: String)
}
```

原则：其它模块不要再自己处理本地路径/R2/外链。只问 AssetResolver。

---

## 4. 发送给模型的统一逻辑

`MediaResolver.prepareOutgoingMessages()` 以后只处理 asset 引用。

### 4.1 模型支持 URL

优先级：

```text
1. externalUrl 如果存在且还可用 -> 直接用 externalUrl
2. r2Key/r2Acct 存在 -> presign R2 URL
3. local cache 存在 -> 本次可用 local/base64，同时后台 enqueue R2 上传
4. 都没有 -> 附件不可用/省略
```

### 4.2 模型不支持 URL

优先级：

```text
1. local cache 存在 -> file:// -> provider 转 base64
2. r2 存在 -> 下载到 local cache -> file://
3. externalUrl 存在 -> 下载到 local cache -> file://，同时后台上传 R2
4. 都没有 -> 附件不可用/省略
```

### 4.3 关键原则

```text
R2 上传不阻塞聊天发送。
```

用户发图时：

```text
保存本地 asset -> 消息写 asset://id -> 立刻发给模型
后台慢慢上传 R2 -> 更新 asset.r2Key/r2Acct
```

---

## 5. R2 上传队列

新增本地表：

```kotlin
MediaUploadOutboxEntity(
    assetId: String,
    createdAt: Long,
    retryCount: Int,
    lastError: String,
)
```

创建/修改 asset 时：

```text
如果有本地文件且 r2 为空 -> enqueue media upload
```

后台上传：

```text
读取 asset
计算 sha256
R2 HEAD
不存在则 PUT
更新 asset.r2Key/r2Acct
同步 managed_files bundle
```

可以复用前台 debounce 或 WorkManager。

---

## 6. 各入口改造目标

### 6.1 聊天上传

当前：

```text
uri -> file:// -> UIMessagePart
```

目标：

```text
uri -> AssetResolver.createFromUri()
-> UIMessagePart(... url = asset://managed-files/<uuid>)
```

涉及：

```text
ChatPage.kt
ChatInputState.kt
FilesPicker.kt
AttachmentChips.kt
```

---

### 6.2 RikkaHub 文件选择器

当前选择器会自己判断 local/r2/http。

目标：

```text
列 managed_files asset
点击 -> asset://managed-files/<uuid>
```

缩略图也通过 AssetResolver.resolveForDisplay。

---

### 6.3 生图工具

当前生图有独立逻辑：

```text
Base64 -> imagesDir
preview -> llm_previews
R2 upload
GenMedia.path/r2Key/r2Acct
UIMessagePart.Image(url = file/r2)
```

目标：

```text
imageItem -> originalAsset
preview -> previewAsset
GenMedia(originalAssetId, previewAssetId)
UIMessagePart.Image(url = asset://previewAssetId 或 originalAssetId)
```

`Text` payload 保持：

```json
{"status":"ok","tag":"assistant-round-..."}
```

不再输出 `r2://`、本地路径、`file_paths`、`llm_preview`。

---

### 6.4 workspace read_file 图片

当前：自己读 bytes、压缩、上传/保存。

目标：

```text
read bytes -> AssetResolver.createFromBytes()
return UIMessagePart.Image(asset://...)
```

参数 `uncompressed` 保留，默认压缩。

---

### 6.5 MCP 图片

当前：MCP 图片 bytes 保存 upload 后返回 file://。

目标：

```text
bytes -> AssetResolver.createFromBytes()
return UIMessagePart.Image(asset://...)
```

---

### 6.6 文件管理

文件管理只看 asset table。

状态：

```text
local exists?
r2 exists?
external exists?
deleted?
```

显示：

```text
本地
云端
外部
本地+云端
本地+外部
云端+外部
不可用
```

操作全部调用 AssetResolver：

```text
压缩
上传云端
下载本地
复制 URL
保存到设备
删除
```

保存路径：

```text
Download/rikkahub/image
Download/rikkahub/video
Download/rikkahub/audio
Download/rikkahub/document
Download/rikkahub/others
```

---

## 7. 旧数据处理

用户已明确：旧附件不用兼容。

所以：

```text
如果 UIMessagePart.Image/Document/Audio/Video 的 url 不是 asset://managed-files/<uuid>
=> UI 显示“附件不可用”
=> 不迁移、不下载、不修复
```

必须保证消息能加载，不崩。

---

## 8. 数据库改造建议

### 8.1 Migration

建议从当前版本新建：

```text
Migration_29_30 或当前实际版本 +1
```

如果当前仍是 28，则应是：

```text
Migration_28_29
```

但注意当前仓库不同 Agent 已经多次改过版本，接手前必须先看 `AppDatabase.version`。

### 8.2 如果改 Long 主键为 UUID

SQLite 直接改主键麻烦，建议：

1. 创建新表 `managed_files_new`；
2. 拷旧数据，给每行生成 UUID；
3. 删除旧表；
4. rename；
5. 重建索引。

但因为用户旧附件已删，旧数据不重要。必要时可以选择清空 `managed_files` 并重建新表。

---

## 9. 当前已完成的相关修复记录

最近相关 commit：

```text
416b5e9 fix(imggen): show local preview immediately in tool output
744a88d fix(imggen): link generated image cache with r2 assets
3844b7a fix(files): save exports under downloads rikkahub folders
a3d4fb8 fix(ui): restore markdown and chat list compile structure
b5e178d fix(imggen): update image locations to R2 references on Base64 image generation
```

重要已完成点：

- 生图工具 UI 优先读 `UIMessagePart.Image`；
- 生图 Text payload 不再输出 `file_paths` / `llm_preview`；
- 保存设备路径已切到 `Download/rikkahub/...`；
- 文件管理部分状态按钮已经有基础版本；
- 但这些还是补丁式逻辑，没有完成统一 Asset 化。

---

## 10. 当前未完成 / 下一步建议

### 下一步建议从 Phase 1 开始

1. 新增/确定 `AssetUri`。
2. 改 `ManagedFileEntity` 为 UUID 资产表。
3. 增 `AssetResolver`。
4. 改上传入口全部返回 asset://。
5. 改 `MediaResolver` 支持 asset://。
6. 改聊天附件渲染支持 asset://，旧 URL 显示不可用。

### 不要再继续修补以下旧分支

```text
file://
r2://
http(s)
data:image
GenMedia.path
llm_preview
file_paths
```

这些在新设计里都不是聊天消息层该直接关心的东西。

---

## 11. 关键决策

- 文件 ID 用 UUID。
- 旧附件不兼容，显示不可用。
- 所有新附件只存 asset://。
- R2 上传异步，不阻塞发送。
- 外部 URL 是 asset 的一个字段，不是聊天消息 URL。
- 生图 original/preview 都是 asset。
- 文件管理、RikkaHub picker、聊天渲染、模型发送，全都通过 AssetResolver。

---

## 12. 给下一位 Agent 的建议

不要直接“大范围搜索替换 URL”。建议按小阶段提交：

1. **DB + AssetResolver 基础**，只保证编译；
2. **新上传走 asset://**；
3. **MediaResolver 走 asset://**；
4. **ChatMessage 显示 asset://**；
5. **生图接入 asset**；
6. **文件管理切 AssetResolver**。

每阶段都 commit/push，让用户看 Actions。

如果中途 Actions 报错，优先修编译，不要继续叠功能。

---

## 9. 阶段进度更新（2026-07-30）

### 阶段 1 / 3：核心 Asset 索引落地（进行中，已写代码）

本阶段目标：先保证“新聊天消息只存 `asset://managed-files/<uuid>`”，旧附件不再走兼容逻辑，消息仍可加载，旧非 asset 附件显示不可用。

已完成代码改动：

- 新增 `AssetUri`：统一格式为：

```text
asset://managed-files/<uuid>
```

- `ManagedFileEntity` 改为 UUID 主键：

```kotlin
@PrimaryKey
val id: String = Uuid.random().toString()
```

- `managed_files` 扩展为资产索引表，新增字段：
  - `external_url`
  - `sha256`
  - `prompt`
  - `description`
  - `deleted`
- 新增破坏性迁移 `Migration_28_29`：直接重建 `managed_files`，旧 managed file 行丢弃；conversation/message 表不动，保证消息能加载。
- `ManagedFileDAO` / `FilesRepository` / `FilesManager` 的文件 ID 参数从 `Long` 改为 `String`。
- 新增 `AssetResolver`，当前已覆盖：
  - `createFromUri(...)`
  - `createFromBytes(...)`
  - `createFromExternalUrl(...)`
  - `createFromR2Ref(...)`
  - `indexPartForStorage(...)`
  - `resolveForDisplay(...)`
  - `resolvePartForModel(...)`
  - `ensureLocal(...)`
  - `ensureCloud(...)`
- `ChatService.sendMessage()` 发送前不再同步等 R2 上传，而是调用资产索引化；新消息附件写入 `asset://managed-files/<uuid>`。
- `MediaResolver.prepareOutgoingMessages(...)` 发送给模型时只解析 `asset://`：
  - 旧 `file://` / `r2://` / `http(s)://` / `data:image` 附件在发送链路中会被省略；
  - asset 缺失或删除也会被省略；
  - URL 模型优先 external URL / R2 presigned URL；否则确保本地缓存。
- `FilesPicker` 选择 RikkaHub 文件时直接生成 `asset://managed-files/<uuid>`。
- `ChatMessage` 渲染聊天历史附件时只解析 `asset://`；旧非 asset 附件显示“附件不可用”。
- Web 文件接口 DTO 的 uploaded file `id` 从 `Long` 改为 `String`。
- `managed_files` 同步 bundle 加入 UUID 与新增资产字段。

当前轻量检查：

```sh
git diff --check
```

已通过。

### 阶段 1 剩余收尾

- 不跑 Gradle（遵守用户要求），但提交前还需要再做一次 `git diff --check` 和 grep 型检查。
- 如 GitHub Actions 报 Room/KSP 或 Kotlin 类型问题，下一轮只修编译错误，不回滚架构。

### 阶段 2 / 3：生图 / workspace / MCP 统一资产输出（未开始）

- 生图结果写 `originalAssetId` / `previewAssetId`，prompt 写入 asset.prompt，不再把 prompt 当文件名。
- Tool output 的 `UIMessagePart.Image` 改为 asset URI。
- workspace `read_file` 图片、MCP 图片返回统一落 asset。

### 阶段 3 / 3：文件管理 + R2 异步队列完善（未开始）

- 独立 media upload outbox，失败可重试；R2 同步不阻塞聊天。
- 文件管理页面以 Asset 为唯一入口处理本地缓存、R2、external URL、删除/恢复/压缩。
- 清理旧 `MediaResolver` 中遗留的 r2/file/http 兼容私有函数。

---

## 10. 阶段进度更新（2026-07-30）

### 阶段 2 / 3：生图工具输出资产化（已完成本阶段提交前代码）

本阶段目标：先把聊天内 `image_generation` 工具输出纳入统一 Asset，不再把生图结果作为聊天附件写 `file://` / `r2://` / `http(s)://`。

已完成代码改动：

- `GenMediaEntity` 增加：
  - `originalAssetId: String?`
  - `previewAssetId: String?`
- DB 升级到 version 30，新增 `Migration_29_30`：
  - `ALTER TABLE GenMediaEntity ADD COLUMN original_asset_id TEXT`
  - `ALTER TABLE GenMediaEntity ADD COLUMN preview_asset_id TEXT`
- GenMedia 云同步 payload 增加：
  - `originalAssetId`
  - `previewAssetId`
- `ImageGenerationTool` 工具输出改为资产 URI：
  - 远程 URL 生图：创建 external URL asset，工具输出 `UIMessagePart.Image(asset://managed-files/<uuid>)`，并异步触发云端副本补全。
  - Base64 生图：原图写入 images，本地 preview 写入 llm_previews，两者都落 managed_files asset；工具输出 preview asset URI。
  - prompt 写入 asset.prompt，不再把 prompt 当文件名。
  - tool result metadata 记录 `original_asset_id` / `preview_asset_id`，保留 `original_url`（如果 provider 返回 URL）。
- 生图参考图准备逻辑支持 `asset://managed-files/<uuid>`：编辑图片时先通过 AssetResolver 按目标模型能力解析，再传给 provider。
- GenMedia 的 `path` 暂时保留显示回退路径/URL，避免图库 UI 在完全 Asset 化前不可见；真正的新主键关系已经写入 `originalAssetId` / `previewAssetId`。

当前轻量检查：

```sh
git diff --check
```

已通过。

### 阶段 2 尚未覆盖的入口（留给阶段 3 或后续小阶段）

- 独立生图页面 `ImgGenVM` 的历史记录仍保留 path 作为显示回退，但 schema 已经预留 asset ID。
- workspace `read_file` 图片和 MCP 图片返回还需要进一步接入 AssetResolver。

### 阶段 3 / 3：文件管理 + R2 异步队列完善（下一步）

- 建独立 media upload outbox / retry，不再用临时 launch 作为长期机制。
- 文件管理统一用 AssetResolver 解析显示、本地缓存、R2、external URL。
- 清理 MediaResolver 中旧 r2/file/http 私有兼容代码，只保留 asset 发送链路。

---

## 11. 阶段进度更新（2026-07-30）

### 阶段 3 / 3：R2 异步上传队列基础版（已完成本阶段提交前代码）

本阶段目标：把“R2 上传用于同步，不阻塞聊天/工具结果”落成可持久化队列，而不是临时直接同步上传。

已完成代码改动：

- 新增 `media_upload_outbox` 本地表：
  - `asset_id TEXT PRIMARY KEY`
  - `created_at`
  - `updated_at`
  - `next_attempt_at`
  - `retry_count`
  - `last_error`
- 新增：
  - `MediaUploadOutboxEntity`
  - `MediaUploadOutboxDAO`
  - `Migration_30_31`
- DB 升级到 version 31，并注册 `Migration_30_31`。
- `AssetResolver.enqueueCloudUpload(asset)` 改为：
  - 只把 assetId 写入 `media_upload_outbox`；
  - 立即异步尝试 flush；
  - 不阻塞聊天发送、生图工具返回、附件索引化。
- `AssetResolver.processCloudUploadOutbox()`：
  - app 内单实例防重入；
  - R2 未配置时直接保留队列；
  - 成功上传后回填 `managed_files.r2_key/r2_acct` 并删除队列项；
  - 失败后写 `last_error`，按指数退避更新 `next_attempt_at`。
- `AssetResolver` 初始化时会尝试处理历史待上传项，避免 App 重启后队列永久不动。
- external URL asset 创建/复用时也会入队：后台下载本地缓存并补 R2 副本。

当前轻量检查：

```sh
git diff --check
```

已通过。

### 大改造三阶段状态

1. 阶段 1：聊天附件资产索引化 + UUID managed_files —— 已完成并推送。
2. 阶段 2：聊天内生图工具输出资产化 —— 已完成并推送。
3. 阶段 3：R2 异步上传 outbox 基础版 —— 本次提交推送后完成。

### 后续可单独继续的小尾巴

- 独立生图页面 `ImgGenVM` 完全改成 AssetResolver 创建资产（目前已有 schema 字段，历史显示仍走 path 回退）。
- workspace `read_file` 图片、MCP 图片返回统一落 asset。
- 文件管理页面进一步改成纯 AssetResolver 操作入口。
- 清理 `MediaResolver` 中阶段 1 保留下来的旧 r2/file/http 私有函数。

---

## 12. 收尾更新（2026-07-30）

### GitHub Actions 编译错误修复

修复：

```text
AssetResolver.kt:68:47 Argument type mismatch: actual type is 'String?', but 'String' was expected.
```

原因：`sha256(bytes)` 返回 `String?`，但 `ManagedFileDAO.getBySha256(...)` 参数是非空 `String`。
处理：`createFromBytes(...)` 对非空 bytes 计算 hash 后转为非空值再查询。

### 资产化收尾

本次继续补齐之前 Plan 中标记的小尾巴：

- workspace `read_file` 图片输出改为创建 Asset：
  - `workspace_read_file` 图片结果返回 `UIMessagePart.Image(asset://managed-files/<uuid>)`
  - tool JSON 里的 `transport` 改为 `asset`
  - R2 上传仍由 AssetResolver/outbox 异步处理
- MCP 图片内容输出改为创建 Asset：
  - `ImageContent` -> `AssetResolver.createFromBytes(...)`
  - 返回 `UIMessagePart.Image(asset://managed-files/<uuid>)`
- 独立生图页面 `ImgGenVM` 保存结果时写入 asset 索引：
  - URL 结果：`createFromExternalUrl(...)`
  - Base64 结果：`createFromBytes(..., folder = FileFolders.IMAGES)`
  - `GenMediaEntity.originalAssetId` / `previewAssetId` 写入 asset id
  - R2 上传不阻塞 UI，交给 media upload outbox

当前轻量检查：

```sh
git diff --check
```

已通过。
