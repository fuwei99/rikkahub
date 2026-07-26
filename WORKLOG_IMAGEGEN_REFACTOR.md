# 工作日志：生图功能审查与重构

> 日期: 2026-07-26
> 范围: `ai` 模块生图 Provider 层 + `app` 模块生图页 / 聊天生图工具 / 模型编辑器
> 状态: 代码已改完，**未提交**（工作区还有他人未提交的 TTS/ChatDrawer 改动，避免混入）。本机无 Java，需推 CI 验证编译。

---

## 一、背景

用户要求审查 rikkahub 生图功能，目标是"灵活、非硬编码、可扩展的生图大全"。审查发现 8 个 bug + 4 类硬编码/扩展性问题，本次全部处理。

## 二、Bug 修复

### 1. WaveSpeed 图生图上传本地文件路径（严重，必现）
- **现象**: 从生图页走图生图时，`params.images` 是设备本地路径，被原样塞进请求 JSON 的 `images` 数组，WaveSpeed 服务端无法读取，必然失败。聊天工具路径恰好先转了 base64 才没暴露。
- **改法**: 新建共享工具 `ai/util/ImageSource.kt` 的 `String.toImageDataUriOrRemote()`——data URI / http(s) 透传，`file:` URI 和本地路径读文件转 data URI。WaveSpeed / Volcengine / OpenAI 三个 provider 和聊天工具统一改用它。原先 Volcengine 的 `toArkImageReference`、聊天工具的 `toProviderImageSource` 两份重复实现删除。
- **为什么这么改**: 同一件事（归一化图片来源）之前有两份实现、一处漏做。收敛为单一入口后，任何 provider、任何调用方拿到的行为一致，新 provider 免费获得该能力。

### 2. WaveSpeed 校验逻辑嵌在 JSON builder 里 + 死代码
- **现象**: `require(maxReferenceImages...)` 写在 `buildJsonObject` 内部，作为构造请求体的副作用执行；且第一个条件 `!supportsImageEditing` 恒为 false（外层已 require 过）。
- **改法**: 校验提到 `editImage` 开头，先验参数再构造请求体；死条件删除。

### 3. chat/completions 生图响应不支持结构化 content（会抛异常）
- **现象**: 解析器只处理 `message.content` 为字符串的情况；Gemini/NewAPI 桥接常返回 content 数组（多 part 含 `image_url`）或非标准 `message.images[]` 字段，前者直接 `jsonPrimitive` 抛异常，后者取不到图。
- **改法**: `parseChatCompletionsImageResponse` 重写为三层提取：① 结构化 part（`content[]` 与 `images[]`，兼容 `image_url.url` / `image_url` 字符串 / `url` 三种形态）→ ② content 文本中的 Markdown 图片 → ③ 裸 data URI。content 为数组时把 text part 拼起来再走文本提取。
- **为什么**: 注释里明说要兼容 Gemini 桥接，但实现只覆盖了纯文本形态，这是主打场景的兼容缺口。

### 4. 回退失败时吞掉真实异常
- **现象**: `/images/generations` 失败→`runCatching` 回退 chat/completions，回退的异常被丢弃只报原始错误；edit 路径用 `error("...${it.message}")` 丢掉堆栈。
- **改法**: AUTO 模式下用显式 try/catch，回退也失败时 `primary.addSuppressed(fallback)` 后抛出主异常——两个失败原因都保留在异常链里，排查不再靠猜。

### 5. 模型名拼文件名未清洗，含 `/` 写文件必失败
- **现象**: `ImgGenVM` 与 `ImageGenerationTool` 用 displayName 拼文件名；WaveSpeed 模型惯例是 `wavespeed-ai/flux-dev`，含 `/` 时 `File(dir, name)` 指向不存在的子目录。
- **改法**: 新建 `app/utils/FileNameUtil.kt` 的 `String.sanitizeFileName()`（替换 `/ \ : * ? " < > |` 和空白为 `_`，截断 48 字符），三处拼文件名统一套用。

### 6. 聊天工具 `firstOrNull()` 会拿到流式预览半成品（潜伏）
- **现象**: `ImageGenerationItem` 有 `partial` 字段，生图页正确区分了 partial/final，工具却直接取流第一个元素。目前 provider 都不发 partial 所以没炸，一旦支持流式预览就会把预览图当成品。
- **改法**: `flow.toList()` 后 `lastOrNull { !it.partial }`，只认最终图。

### 7. Volcengine：注释与实现矛盾 + 日志泄漏
- **现象**: `DEFAULT_RESPONSE_FORMAT = "url"` 但注释声称 base64；Ark URL ~24h 过期，存 URL 的历史记录会失效。另外 `Log.i` 打整个请求体，图生图时含几 MB 的 base64 参考图。
- **改法**: 默认改为 `b64_json`（与"可靠本地持久化"的既有设计意图一致，用户仍可用 custom body 覆盖回 url）；日志只打请求体字节数。

### 8. WaveSpeed size 语法不兼容
- **现象**: WaveSpeed 用 `1024*1024`（星号），UI 的 `ImageGenSize` 硬编码 `1024x1024`，直传可能被拒。
- **改法**: 新增 `putWaveSpeedSize()`，发送前把 `x` 替换为 `*`，两种写法都接受。同时补上之前遗漏的 generate/edit 路径 size 处理统一。

## 三、去硬编码 / 可扩展性重构

### A. ProviderManager：字符串注册 + when 硬编码映射 → KClass 注册表
- **原状**: 生图 provider 用 `"openai-imggen"` 等魔法字符串注册，`getImageProviderByType` 再用一个 when 按类型映射回字符串——两套标识，新增 provider 要改两处，改漏就运行时 crash。
- **改法**: `registerImageProvider(ImageProviderSetting.OpenAI::class, provider)` 直接按设置类型注册，`getImageProviderByType` 一行 map 查找。**新增 provider 只需在 init 里加一行注册**，无 when、无字符串。
- 删除了不再需要的 `getImageProvider(name)`。

### B. ImageProviderSetting：删掉 4×5 个复制粘贴方法
- **原状**: `addModel/editModel/delModel/moveModel/copyProvider` 在 4 个子类里逐字重复，257 行里约 180 行是样板；新增 provider 又要复制一遍。
- **改法**: 前 4 个方法全部基于 `copyProvider` 在基类实现为 final 方法；子类只需实现 `copyProvider`（data class 的 copy 无法在基类抽象，这是 Kotlin 限制下的最小样板）。文件从 257 行降到 ~185 行，新子类样板减少约 40 行。
- **兼容性**: 序列化字段、`@SerialName`、行为均不变，纯结构收敛。

### C. API 形态从"按 provider 类型猜"改为"按模型配置"
- **原状**: `usesChatCompletionsImageApi = providerSetting is NewAPI`——NewAPI 下挂标准 dall-e 永远走不了 `/images/generations`；OpenAI 类型下的 chat 生图模型只能靠失败回退试出来。这是典型的"用类型硬编码行为"。
- **改法**: `ImageModelCapabilities` 新增 `apiDialect: ImageApiDialect { AUTO, IMAGES_API, CHAT_COMPLETIONS }`（模型级配置，带默认值，旧数据反序列化不受影响）：
  - `AUTO` 保持旧启发式（NewAPI→chat，其他→images API 失败回退 chat），**行为向后兼容**；
  - 用户可为任意模型钉死某种 API，NewAPI 下也能用 Images API，OpenAI 下也能直连 chat 生图，不再靠试错。
  - `ImageModelEditor` "能力"页为 OpenAI 兼容 provider 增加 "API 类型" 下拉。
- 顺带把编辑器参数 `supportsModelIdMapping` 改名 `isOpenAICompatible` 并对 OpenAI 类型也开放模型映射/System Prompt 页（原先只有 NewAPI 有，无理由限制）。

### D. system prompt 特判统一
- **原状**: generate 路径对 NewAPI 照发 system role，edit 路径却把 system prompt 并进 user 消息——同一 provider 两种行为。
- **改法**: 抽出 `buildChatMessages()` 统一处理：NewAPI 桥接合并进 user prompt（多模态请求常丢 system 消息），其他发标准 system role；generate/edit 共用。

### E. 补全 OpenAI 官方 `/images/edits` 端点
- **原状**: editImage 只走 chat/completions，真·OpenAI 的 gpt-image-1 / dall-e-2 图片编辑（multipart）完全不可用——"生图大全"的功能空洞。
- **改法**: 新增 `imagesApiEdit()`：multipart form 上传 `image[]`（参考图经 `toImageDataUriOrRemote` 归一化后解出字节，远程 URL 下载一次），custom body 的标量项作为 form 字段透传。edit 的 AUTO 顺序保持旧行为（chat 优先）再回退 `/images/edits`；钉死 `IMAGES_API` 的模型直连。
- chat 生图路径顺带补发 `n`（此前 `numOfImages` 被静默忽略）。

### F. OpenAIImageProvider 结构重排
按 "路由 → images API → 解析/下载 → chat 桥接" 分段，generate/edit 的 HTTP 发送收敛为 `postChatCompletions()` 一个函数。改完后每条 API 路径都是独立函数，AUTO 只负责编排顺序。

## 四、涉及文件

| 文件 | 改动 |
|---|---|
| `ai/.../util/ImageSource.kt` | **新增** 图片来源归一化 |
| `ai/.../provider/ImageModelConfig.kt` | 新增 `ImageApiDialect` + capabilities 字段 |
| `ai/.../provider/ImageProviderSetting.kt` | 样板方法收敛到基类 |
| `ai/.../provider/ProviderManager.kt` | KClass 注册表 |
| `ai/.../providers/OpenAIImageProvider.kt` | 大改：dialect 分派、/images/edits、解析器重写、异常链 |
| `ai/.../providers/VolcengineImageProvider.kt` | b64_json 默认、日志脱敏、共用图片归一化 |
| `ai/.../providers/WavespeedImageProvider.kt` | 参考图转 data URI、校验前置、size 语法 |
| `app/.../utils/FileNameUtil.kt` | **新增** 文件名清洗 |
| `app/.../pages/imggen/ImgGenVM.kt` | 文件名清洗 |
| `app/.../data/ai/tools/ImageGenerationTool.kt` | partial 过滤、共用归一化、文件名清洗 |
| `app/.../pages/setting/ImageModelEditor.kt` | API 类型下拉、参数改名 |
| `app/.../pages/setting/SettingImageDetailPage.kt` | 调用点适配 |

## 五、遗留事项

- `ImageGenSize` 枚举仍是硬编码预设，但 UI 已有自由输入框兜底，WaveSpeed 语法差异已在 provider 层吸收；模型级自定义尺寸列表可作为后续增强。
- Volcengine 的 `MAX_TOTAL_IMAGES=15` 是 Ark 文档限制，保留。
- 保存文件扩展名固定 `.png`（即使 mime 是 jpeg/webp），显示无碍，未动。
- **待办**: 推分支跑 CI 验证编译；工作区里另有未提交的 TTS/ChatDrawer 改动非本次范围，提交时注意分开。
