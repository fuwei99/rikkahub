# RikkaHub 附件资产架构收敛与渲染修复重构计划 (Plan V2)

> 目标：在已完成的大统一 Asset 架构基础上，解决当前遗留的渲染 Bug，清理冗余与重复造的轮子，彻底收敛资源流。

---

## 🎯 重构与修复目标

1. **修复生图引用 Markdown 渲染失败**：解决 `![xxx](assistant-round-X-ref-Y.png)` 提取不到 `asset_uri` 导致无法渲染的问题。
2. **支持多端挂载路径图片 Markdown 渲染**：支持 `obsidian/`、`/mnt/obsidian/`、`/mnt/BaiduNetdisk/` 等外部挂载点图片的无缝渲染。
3. **清理重复造轮子**：移除 `Base64ImageToLocalFileTransformer`，重构 `ImageGenerationTool` 的参考图加载逻辑。
4. **数据库与门面收敛**：瘦身 `GenMediaEntity`，收敛 `MediaResolver` 门面。

---

## 📋 详细执行阶段

### Phase 1: 紧急渲染 Bug 修复 (P0)

#### 1.1 修复生图 Reference 标签与 Asset URI 的映射
* **关联文件**：
  * `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ImageGenerationTool.kt`
  * `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
* **修复逻辑**：
  1. 修改 `ImageGenerationTool.kt` 中的 `collectImageSources()` 递归逻辑：
     * 不再仅检索 `UIMessagePart.Image`。
     * 当遇到 `UIMessagePart.Text` 或 `UIMessagePart.Tool` 时，解析 Tool Output 返回的 JSON，提取 `asset_uri` 或 `preview_asset_uri`。
  2. 保证 `buildConversationImageReferences()` 扫描消息历史时，能正确建立 `"assistant-round-X-ref-Y.png" -> "asset://managed-files/<uuid>"` 的映射。
  3. `resolveMarkdownImageModel()` 命中匹配后，通过 `AssetResolver.resolveForDisplay` 转化为可显示的本地 URI。

#### 1.2 支持多端挂载路径 (Obsidian / BaiduNetdisk) Markdown 渲染
* **关联文件**：
  * `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
* **修复逻辑**：
  1. 重构 `mapAppLocalImage(context, path, workspaceId)`：
     * **挂载点判空与前缀重定向**：拦截 `obsidian/`、`/mnt/obsidian/`、`BaiduNetdisk/`、`/mnt/BaiduNetdisk/` 等前缀。
     * **宿主物理路径映射**：
       * `/mnt/obsidian` / `obsidian/` ➡️ 映射至物理路径 `/storage/emulated/0/obsidian/`（或本地配置挂载根目录）。
       * `/mnt/BaiduNetdisk` / `BaiduNetdisk/` ➡️ 映射至 `/storage/emulated/0/Download/BaiduNetdisk/`。
     * **排除 Mis-match**：避免将带有 `obsidian/` 的非 `/` 相对路径错误当成 Workspace 私有目录（`workspaces/$workspaceId/files/...`）处理。

---

### Phase 2: 清理重复造轮子与规避死角 (P1)

#### 2.1 彻底移除 / 重构 `Base64ImageToLocalFileTransformer`
* **关联文件**：
  * `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Base64ImageToLocalFileTransformer.kt`
  * `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
* **优化逻辑**：
  1. 删除/禁用 `Base64ImageToLocalFileTransformer` 手写 Base64 转存 `file://` 的过时代码。
  2. 统一将 LLM 输出的 Base64 图片交由 `AssetResolver.indexPartForStorage` 处理，自动转存并生成 `asset://managed-files/<uuid>` 格式，维护系统统一性。

#### 2.2 收拢 `ImageGenerationTool` 的参考图下载与处理逻辑
* **关联文件**：
  * `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ImageGenerationTool.kt`
* **优化逻辑**：
  1. 废弃 `prepareReferenceImageForModel` 中手写的 `loadImageBytes` / `createUploadPreview` 分支。
  2. 统一改用 `AssetResolver.resolvePartForModel(UIMessagePart.Image(source), targetModel)`，由 `AssetResolver` 统一决策走外部 URL、R2 签名还是本地缓存。

---

### Phase 3: 架构收敛与表结构瘦身 (P2)

#### 3.1 `GenMediaEntity` 彻底基于 Asset 外键
* **关联文件**：
  * `app/src/main/java/me/rerere/rikkahub/data/db/entity/GenMediaEntity.kt`
  * `app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenVM.kt`
* **优化逻辑**：
  1. 标注 `path`、`r2_key`、`r2_acct` 为 Deprecated。
  2. 生图历史界面 (`ImgGenVM`) 完全通过 `originalAssetId` / `previewAssetId` 依靠 `AssetResolver` 动态解析渲染。

#### 3.2 融合 `MediaResolver` 门面至 `AssetResolver`
* **关联文件**：
  * `app/src/main/java/me/rerere/rikkahub/data/sync/r2/MediaResolver.kt`
  * `app/src/main/java/me/rerere/rikkahub/data/files/AssetResolver.kt`
* **优化逻辑**：
  1. 将 `uploadLocalAttachmentsWithReport` 与 `prepareOutgoingMessages` 直接内嵌到 `AssetResolver` 中，移除多余的门面转接层。

---

## 🛠️ 验证标准

1. **测试用例 1**：在聊天中生图后，AI 回复 `![窗台上的橘猫](assistant-round-1-ref-1.png)` 能正常展示渲染图片。
2. **测试用例 2**：在 Markdown 中输入 `![测试挂载](obsidian/日记/自己画的漫画.jpg)` 和 `![测试挂载](/mnt/obsidian/日记/自己画的漫画.jpg)`，均能成功定位宿主物理路径并渲染。
3. **测试用例 3**：全局 Grep 不再存在手动写入 raw `file://` 的 Base64 Transformer 逻辑。
