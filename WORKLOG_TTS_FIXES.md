# RikkaHub TTS 模块修复日志

日期：2026-07-26
范围：`speech/` 模块 + `app/` TTS 相关 UI 层
起因：接入自建 voub（豆包 OpenAI 兼容 TTS）时，顺带做了一次 TTS 全链路代码审阅

---

## 背景

原始诉求是把 HF Space 上的 voub TTS 接进 RikkaHub。排查发现 HF 那边是服务端 cookie 被豆包风控标死（`cookies_dead: 4`）导致 503，与客户端无关。但排查过程中暴露出 RikkaHub 客户端本身对「HTTP 200 + 0 字节」这类异常完全没有防御，遂展开完整审阅，共发现 10+ 处问题，本次修复其中 8 处。

---

## 修改清单

### 1. 分段播放的缓存文件被逐段覆盖（严重）

**问题**
`TtsController` 的 worker 每播完一段就把该段音频写进 `tts_$msgId.$ext`，而 `AudioPlayer.play()` 内部是 `FileOutputStream` 覆盖写。长文本切成 10 段后，磁盘上只剩第 10 段。下次点播放命中缓存分支，只会朗读最后一句话。

**为什么这么改**
缓存的语义应该是「整条消息的完整音频」，而不是「最后一个分片」。落盘时机必须推迟到所有分片播完。

**怎么改**
- `AudioPlayer.play()` 移除 `cacheFile` 参数及内部写文件逻辑，回归单一职责：只负责播放
- `TtsController` 新增会话级累积缓冲 `sessionAudio: ByteArrayOutputStream` + `sessionFormat` + `sessionSampleRate`，每段播放前把音频追加进去（仅当格式与首段一致）
- 新增 `flushSessionAudioToCache()`，在 worker 循环正常结束、队列为空时调用，一次性写出完整音频
- 新增 `resetSessionAudio()`，在 `stop()` / `internalReset()` / 落盘后统一清理

### 2. stop() 无法终止缓存播放，isSpeaking 永久卡死（严重）

**问题**
两个叠加缺陷：
1. `speak()` 的缓存播放分支用裸 `scope.launch` 起协程，没有赋给 `workerJob`，而 `stop()` 只 cancel `workerJob`
2. `AudioPlayer` 的 Player.Listener 在 `STATE_IDLE`（即被 `player.stop()` 打断）时只更新状态，**不 resume continuation**

结果：用户点停止 → player 停了但协程永久挂起 → `finally` 块不执行 → `_isSpeaking` 永远是 true，UI 停在「播放中」，listener 泄漏。

**为什么这么改**
`suspendCancellableCoroutine` 的契约是所有终态路径都必须 resume 或 resumeWithException。`STATE_IDLE` 是一个合法终态（被主动停止），漏掉它就是资源泄漏。

**怎么改**
- 缓存播放协程改为赋值给 `workerJob`（并先 cancel 旧的），使其纳入 `stop()` 的管辖
- `AudioPlayer` 中 `playFile` / `play` / `playStream` 三处 `STATE_IDLE` 分支统一补上 `removeListener` + `cont.resume(Unit)`

### 3. 空音频响应静默通过（严重）

**问题**
voub 服务端 cookie 全死时返回 `HTTP 200` + `Content-Type: audio/aac` + **0 字节 body**。`DoubaoTTSProvider` / `OpenAITTSProvider` 都只检查 `isSuccessful`，空 body 照样 emit 给播放器，最终用户看到的是 ExoPlayer 的 `no extractor could read the stream` —— 完全无法定位真实原因。

**为什么这么改**
错误信息必须在最接近错误源头的地方产生。让播放器去报一个网络/鉴权问题，排障成本极高（我自己就是靠 curl 才定位到的）。

**怎么改**
- `DoubaoTTSProvider`：新增 `totalBytes` 计数，流读完后若为 0 则抛出明确异常，提示检查服务端 cookie / voice id
- `OpenAITTSProvider`：`audioData.isEmpty()` 时抛异常；同时补上失败时读取 error body 并打日志（此前只有 Doubao 版有，OpenAI 版排障全靠猜）

### 4. 自动朗读可能读错会话（严重）

**问题**
`TTSAutoPlay` 从 `generationDoneFlow` 拿到了 `conversationId` 却直接丢弃，转而读 `currentConversation` 的最后一条消息。会话 A 在后台生成完成时若用户已切到会话 B，朗读的会是 B 的内容。

**怎么改**
在 collect 开头加一行守卫：`if (conversationId != currentConversation.id) return@collect`

### 5. 合成结果缓存无上限，长文本堆内存

**问题**
`cache: ConcurrentHashMap<UUID, Deferred<TTSResponse>>` 保存的是完整音频字节，原代码注释写着「此处保留，便于重播/重试」，实际直到 `stop()` 才清空。朗读一篇长文可以堆积几十 MB 常驻内存。

**为什么这么改**
所谓「便于重播」是个伪需求 —— 上层根本没有重播单个分片的入口，而现在整条消息的完整音频已经会落盘（见修改 1），重播走的是文件缓存。内存里留着纯属浪费。

**怎么改**
worker 消费完一段后，在 `finally` 中 `cache.remove(chunk.id)`。`skipNext()` 同样清理被跳过分片的缓存并 cancel 其预取协程。

### 6. totalChunks 语义漂移，UI 进度诡异

**问题**
worker 里写的是 `_totalChunks.update { queue.size + 1 }`，总数随播放递减。UI 上呈现为 `3/8 → 4/7 → 5/6` 这种分母不断缩小的怪象。

**怎么改**
改为 `_totalChunks.update { allChunks.size }`，总数在一次会话内恒定。

### 7. TextChunker 对无标点长文本不做硬切

**问题**
分片完全依赖标点。一段没有任何标点的长英文、URL 或代码块会整体作为一个 chunk 发出去，可能超过 provider 的单次字数上限而失败。

**怎么改**
在 fold 之前插入 `.flatMap { seg -> seg.chunked(maxChunkLength).asSequence() }`，先按硬上限切碎，再按标点合并。

### 8. 默认配置泄漏私人凭据 + 死代码清理

- `TTSProviderSetting.Doubao` 的默认值里硬编码了 `apiKey = "sk-wei123"`，默认 `baseUrl` 也是个人的 `localhost:1547`。改为空 key + `localhost:7860`（与 voub 默认端口一致）。**这个如果提 PR 到上游是必须清掉的。**
- `ui/hooks/TTS.kt` 中 `CustomTtsStateImpl` 的 `scope` 和 `currentJob` 从未被实际使用（`scope` 创建后没有任何 launch，且 `cleanup()` 里也没 cancel），连同 4 个相关 import 一并删除。

---

## 未修复项（已识别，留待后续）

### playbackMode = "stream" 是假流式（架构级）

`AudioPlayer.playStream()` 写了一整套 `StreamingDataSource` + 边下边播 + 边写缓存的管线，**但全项目零调用**。当前 "stream" 模式的实际行为只是「不切片，整段合成后再播」——`TtsSynthesizer.collectToResponse()` 会把 provider 的 Flow 全部缓冲成一个 ByteArray 才返回。

这意味着 `DoubaoTTSProvider` 里精心实现的 8KB 分块流式 emit 完全白费，长文本首音延迟等于完整合成时间。接上 `playStream` 后首音延迟可以从数秒降到 1 秒内，但涉及 `TtsController` 的调度逻辑重构（流式模式下没有「分片队列」概念，缓存落盘时机也不同），风险较高，本次未动。

### 其他小项

- 所有 provider 的 `baseUrl` 末尾带 `/` 时会拼出双斜杠
- `TtsController` 中 `_isAvailable` 为 false 时强制把播放状态写成 Idle，把「是否选了 provider」和「播放状态」两个概念混在了一起
- `AudioPlayer.writeWavHeader` 用 `0xFFFFFFFF` 表示未知长度，写成 int 会溢出成负数（ExoPlayer 能容忍，但不如直接写 -1 明确）

---

## 涉及文件

| 文件 | 改动 |
|---|---|
| `speech/.../controller/TtsController.kt` | 会话级音频累积与落盘、缓存释放、workerJob 纳管、totalChunks 修正 |
| `speech/.../controller/AudioPlayer.kt` | play() 移除缓存职责、三处 STATE_IDLE 补 resume、新增 pcmToWavBytes |
| `speech/.../controller/TextChunker.kt` | 超长片段硬切 |
| `speech/.../provider/providers/DoubaoTTSProvider.kt` | 空音频检测 |
| `speech/.../provider/providers/OpenAITTSProvider.kt` | 空音频检测、error body 日志、connectTimeout |
| `speech/.../provider/TTSProviderSetting.kt` | 清除硬编码私人凭据 |
| `app/.../ui/pages/chat/TTSAutoPlay.kt` | 会话 id 校验 |
| `app/.../ui/hooks/TTS.kt` | 死代码清理 |

---

## 验证情况

- 静态检查：括号平衡、无残留的旧签名调用、import 无冗余 —— 均通过
- 未执行 Gradle 编译（当前环境无 Android SDK），建议在 Android Studio 中 `:speech:compileDebugKotlin` 复验
- 端到端验证受阻于 voub 服务端 cookie 失效（提供的 cookie 缺少 HttpOnly 的 `sessionid` 系列字段，豆包返回「登录已过期」），待补齐完整 cookie 后可实测

## 关于 voub 服务端（建议但未改）

`tts-server.py` 有一个设计缺陷：`CookieManager.dead` 集合只在 `load_cookies()` 中清空，而后者仅在 cookie 列表为空时才会被重新调用。由于 cookie 从环境变量加载后列表永远非空，**一旦被标记 dead 就永久 dead，只能重启服务**。叠加 `force_rotate()` 在遇到风控错误时立即标死的激进策略，一次失败请求（内部重试 3 次）就能连杀 3 个 cookie。

建议将 `dead` 改为 `Dict[int, float]` 记录死亡时间戳，`get_cookie()` 时对超过冷却期（如 10 分钟）的条目惰性复活，避免误杀导致服务永久瘫痪。
