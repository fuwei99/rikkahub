# 大改造后新出现的 bugs 和需求以及修复过程

> 记录时间：2026-07-30  
> 背景：附件/媒体系统大改为 `asset://managed-files/<uuid>` 资产索引后，陆续暴露出 UI、同步、tool 上下文、R2 与头像相关回归。本文记录问题、原因与对应修复，便于后续继续接手。

---

## 1. 合法 asset 附件会闪红“附件不可用”

### 现象

聊天消息中的合法 `asset://managed-files/<uuid>` 图片/文件，在异步解析完成前会先显示红色“附件不可用”，滚动重进视口时也可能闪一下。

### 原因

`ChatMessage` 早期把“解析中”和“确实不可用”都表示为 `null`：

```kotlin
resolvedUrl == null -> UnavailableAttachmentPlaceholder()
```

### 修复

改为三态：

```text
Loading / Resolved(url) / Unavailable
```

- asset 解析中：显示 shimmer 占位；
- asset 解析失败或旧非 asset：显示不可用；
- resolved 后正常渲染。

提交：

```text
2f2f930 fix(asset): polish attachment resolving and media cleanup
```

---

## 2. 聊天图片 mime 被硬编码成 image/png

### 现象

JPG/WEBP/GIF 等聊天图片可能被索引为 `image/png`，影响文件管理显示、导出扩展名、发送给模型的 mime。

### 原因

`AssetResolver.indexPartForStorage(Image)` 在没有 `r2_mime` metadata 时 fallback 到 `image/png`。

### 修复

优先级改为：

```text
metadata.r2_mime -> FilesManager.getFileMimeType(uri) -> image/png
```

提交：

```text
2f2f930 fix(asset): polish attachment resolving and media cleanup
```

---

## 3. 外链 asset 的 sizeBytes 显示成 URL 字符串长度

### 现象

外部 URL 资产在文件管理显示几十字节。

### 原因

`createFromExternalUrl(...)` 把 `sizeBytes` 写成了：

```kotlin
url.toByteArray().size.toLong()
```

### 修复

新建 external URL asset 时写 `0L`，下载缓存后再回填真实大小。

提交：

```text
2f2f930 fix(asset): polish attachment resolving and media cleanup
```

---

## 4. MediaResolver 旧 r2/file/http 兼容死代码过多

### 现象

Asset 改造后，`MediaResolver` 仍保留旧的 r2/file/http 上传/下载/转换逻辑，维护时容易误判链路。

### 修复

`MediaResolver` 只保留两件事：

```text
入库前：AssetResolver.indexPartForStorage(...)
发模型前：AssetResolver.resolvePartForModel(...)
```

DI 改为：

```kotlin
MediaResolver(get())
```

提交：

```text
2f2f930 fix(asset): polish attachment resolving and media cleanup
```

---

## 5. 前台同步不够“同时”，上传队列为 0 也迟迟不拉取

### 现象

一个设备改了内容，另一个设备保持前台时，待自动上传队列是 0，但远端变更很久才出现。

### 原因

旧逻辑：

- 回前台时 full sync 一次；
- 本地 outbox 变化时只 `flushPending()`，只 push 不 pull；
- 前台停留期间没有定时 pull。

### 修复

前台增加定时静默同步：

```text
默认每 30 秒 syncOnce(): push + pull
```

后续又抽到高级设置：

```kotlin
foregroundPullIntervalMs
outboxFlushDebounceMs
```

提交：

```text
7afc008 fix(sync): poll remote changes while foreground
b72f8e9 feat(sync): add local advanced sync settings
```

---

## 6. 同步高级设置位置与存储

### 需求

把影响体验的同步参数放入设置，但不放 SQL，方便 workspace/Agent 直接修改。

### 实现

本地 JSON：

```text
filesDir/config/sync_advanced.json
```

代码：

```kotlin
SyncAdvancedConfigStore.RELATIVE_PATH = "config/sync_advanced.json"
```

本地 JSON 配置项：

```kotlin
foregroundPullIntervalMs
outboxFlushDebounceMs
circuitBreakerFailureThreshold
circuitBreakerCooldownMs
mediaUploadBatchLimit
mediaUploadMaxRetries
mediaUploadMaxBackoffMinutes
```

UI 位置最终调整为：

```text
设置 -> 偏好设置 -> 数据与备份设置
```

提交：

```text
b72f8e9 feat(sync): add local advanced sync settings
7fe1def refactor(settings): move sync advanced options to preferences
```

---

## 7. R2 临时链接有效期需要同步

### 需求

新增：

```text
R2 临时链接有效期
15 分钟 / 1 小时 / 6 小时 / 24 小时 / 7 天 / 30 天 / 90 天
```

默认 24 小时，并且参与 D1 settings 同步。

### 实现

放入 `Settings`：

```kotlin
val r2PresignTtlSeconds: Long = 86_400L
```

`R2MediaStore.presign(...)` 默认读取该设置，并把 TTL 放入 presign cache key，避免修改 TTL 后复用旧缓存 URL。

提交：

```text
3275227 feat(r2): sync configurable presign ttl
```

---

## 8. 生图结果已经入图库，但 Markdown 和工具卡片不显示

### 现象

聊天中出现：

```markdown
![](assistant-round-1-ref-1.png)
```

但图片不显示；工具卡片也不显示。图库里实际有图。

### 原因

`assistant-round-*.png` 解析到了：

```text
asset://managed-files/<uuid>
```

但 Markdown/Coil 不能直接加载 `asset://`，必须先通过 `AssetResolver.resolveForDisplay(...)` 转成本地 file/R2 URL/external URL。

### 修复

- Markdown 图片渲染新增 `rememberMarkdownImageModel(...)`；
- `Markdown.kt` 与 `MarkdownNew.kt` 都接入 asset resolve；
- `ImageGenerationToolUI` 也支持 asset resolve。

提交：

```text
b20e4e7 fix(asset): resolve generated image assets in markdown
```

---

## 9. 发送图片后立即不可用、第二次模型看不到

### 现象

用户发送图片后，第一次模型能看到，但聊天 UI 显示附件不可用；第二次带历史上下文时模型又看不到。

### 早期误判

曾怀疑是 D1 pull 把 `managed_files` 全表覆盖导致 asset 索引丢失，因此改过 `managed_files` pull 合并，不再 `deleteAll()`。

提交：

```text
68d0b95 fix(sync): merge managed file assets on pull
```

### 后续真正高危点

`managed_files.relative_path` 有 unique index。旧 DAO 使用：

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
```

当头像/聊天图片文件已被 `FilesManager.trackManagedFile(...)` 记录，随后 AssetResolver 再以同一路径写入另一条 asset id 时，Room/SQLite 的 REPLACE 可能删除旧行并插入新行，造成消息中保存的 asset id 与实际保留的索引行错位。

### 修复

- `ManagedFileDAO.insert(...)` 改为 `OnConflictStrategy.IGNORE` 并返回插入结果；
- `FilesRepository.insert(...)` 在冲突时回读现有 path/id；
- `AssetResolver.createFromLocalFileUri(...)` 专门处理已有本地文件，不再复制/替换同一路径资产；
- 命中 sha256 复用 asset 时也重新 enqueue cloud upload。

本次提交包含该修复。

---

## 10. Tool output 不能携带 base64/附件撑爆上下文

### 现象/需求

- workspace `read_file` 读图片时，AI 看不到图片；
- 但不能把图片/base64 直接放到 tool result，因为会撑爆上下文，而且不是所有 provider 支持 tool result 附件；
- UI 仍应显示 tool 调用；
- 真正给模型看的媒体应通过不可见 `role = USER` 消息发送。

### 修复

`workspace_read_file` 图片输出现在只返回轻量 JSON：

```json
{
  "status": "ok",
  "path": "...",
  "asset_uri": "asset://managed-files/<uuid>",
  "mime": "image/jpeg",
  "description": "...",
  "transport": "asset"
}
```

不再把 `UIMessagePart.Image` 直接塞进 tool output。

`GenerationHandler` 执行工具后：

- 从 tool output JSON 收集 `asset_uri`；
- 构造内部不可见 user 消息：

```text
[读取文件见下]
<附件 asset>
```

- 该消息参与下一轮模型调用，但不写入聊天 UI/数据库；
- 可见 tool output 中不会包含大 base64。

本次提交包含该修复。

---

## 11. 模型不支持图片时，read_file 图片需要 OCR，但生图不要 OCR

### 需求

- `workspace_read_file` 读图片：
  - 模型支持图片：通过不可见 user 附件发给模型；
  - 模型不支持图片：自动 OCR，并把 OCR 结果写回 tool output；
- `image_generation` 生成图片：
  - 不支持图片的模型不要 OCR；
  - tool output 只要 OK 和 tag/asset id 即可。

### 修复

`GenerationHandler` 中对 tool media 进行了区分：

- `workspace_read_file`：非图像模型会对 asset 解析成本地图片并调用 `OcrTransformer.performOcr(...)`，把 `ocr` 字段合并进 tool output JSON；
- `image_generation`：不会 OCR。若模型不支持图像，则不追加生成图的内部 user 图片上下文。

本次提交包含该修复。

---

## 12. 生图 URL provider（Seedream/WaveSpeed）索引分裂、upload 0B

### 现象

URL 返回型生图 provider 结果被拆成两个资产：

- 一个本地；
- 一个云端/外链，常出现在 upload，size 0B。

### 原因

URL 返回型生图之前优先创建 external URL asset：

```text
folder=upload
sizeBytes=0
externalUrl=providerUrl
```

之后本地下载/云端镜像又产生另一条资产，导致原图/预览/云端关系分裂。

### 修复

URL 返回型 provider 现在优先下载 provider URL 字节：

- 原图 asset：`images`
- LLM 预览 asset：`llm_previews`
- tool output：只返回 OK/tag/asset uri JSON
- 下一轮模型上下文：使用 preview asset

只有下载 provider URL 失败时才 fallback 到 external URL asset。

提交：

```text
db9fe8e fix(asset): send tool media as user context
```

本次继续补齐 tool output 不带附件的约束。

---

## 13. 生图工具卡片 UI

### 需求

参考旧版 UI 风格：一个大图显示当前选中图，一个缩略图区用于切换；未来兼容多图返回。

### 修复

`ImageGenerationToolUI` 改成：

- 大图区域；
- 缩略图 row；
- 点击缩略图切换大图；
- asset URI 先 resolve 再显示。

提交：

```text
db9fe8e fix(asset): send tool media as user context
```

---

## 14. 头像没有进云端 / 改成 asset 后头像不显示

### 现象

- 头像文件能在文件管理看到，但没有上传云端；
- 后来保存为 asset 后，头像 UI 又不显示。

### 原因

1. 头像最初只是 `file://` 本地路径，没进入 asset/outbox；
2. 保存成 `asset://` 后，`UIAvatar` 一开始直接把 `asset://` 交给 Coil，加载失败后 `imageLoadFailed` 被置为 true；之后 asset resolve 成本地/R2 URL，失败状态没有重置，因此继续显示默认头像。

### 修复

- 本地头像选择/裁剪后通过 `AssetResolver.createFromLocalFileUri(...)` 建立 asset；
- URL 头像也通过 asset 索引；
- asset 入 media upload outbox，后台上传 R2；
- 头像显示时先 resolve asset，再交给 Coil；
- resolved URL 变化时重置 `imageLoadFailed`。

本次提交包含该修复。

---

## 15. WaveSpeed LoRA 是否参与 D1 同步

### 结论

已参与。

WaveSpeed LoRA 配置位于：

```text
Settings.imageProviders -> models -> waveSpeedLoras
```

`PreferencesStore` 会保存 `IMAGE_PROVIDERS`，`SyncSettingsFilter` 也没有剥离 `imageProviders`，因此它随 settings bundle 参与 D1 同步。

本次无需额外修改。

---

## 当前仍可继续优化的点

- workspace `read_file` 目前只支持文本和图片；PDF/音频/视频若要通过不可见 user 附件传给模型，需要扩展工具识别与 asset 创建。
- 旧历史里已经丢失 asset 索引的消息无法自动恢复，需要重新选择/发送附件。
- 头像/附件等 asset 已经改成 outbox 上传，上传失败重试策略已可在“数据与备份设置”中调整。
