# TTS 流播放 / 切片播放 模式切换计划

## 现状（已确认）

1. **数据模型**：`TTSProviderSetting` 基类抽象 `filterRegex` / `replaceWith` / `regexRules`，没有播放模式或切片长度字段。
2. **控制器**：`TtsController` 使用 `TextChunker(maxChunkLength=160)` 硬编码分片；`startWorker()` 逐块 `awaitOrCreate()` → `audio.play()`；`prefetchCount=4` 并行预取。
3. **合成器**：`TtsSynthesizer.synthesize()` 把 `Flow<AudioChunk>` 全部收集到 `ByteArrayOutputStream` → `TTSResponse`，不直接流式播放。
4. **播放器**：`AudioPlayer.playStream()` 支持连续流式播放（`StreamingDataSource` + `ExoPlayer`），但控制器未调用。
5. **UI 配置**：`TTSProviderConfigure.kt` 为每个 Provider 提供独立配置面板，没有播放模式或切片长度选项。
6. **引擎实现**：所有 Provider（`OpenAI` / `Gemini` / `SystemTTS` / `MiniMax` / `Qwen` / `Groq` / `XAI` / `MiMo` / `ElevenLabs` / `FishAudio` / `Step` / `Doubao` / `VolcengineAgent`）实现 `generateSpeech()` 返回 `Flow<AudioChunk>`，底层支持流式合成。

## 用户要求

- 每个引擎的设置页加 **两个下拉选择框**：
  1. **播放模式**：`流播放`（默认） | `切片播放`
  2. **切片长度**：支持 **下拉预设值** + **手动输入**，且支持选择 **不切片**（即不分片直接流式或完整合成）
- **不再硬编码** `TextChunker` 的 `maxChunkLength`（原 160），需可配置。
- **流播放时**：不切文本片（或按配置决定是否切片），直接将完整文本送入合成引擎，通过 `AudioPlayer.playStream()` 连续播放合成流。
- **切片播放时**：按用户设置的切片长度分片，逐块合成播放（保留现有预取逻辑）。
- **适配不支持流式的引擎**：即使引擎只返回完整音频（单块 `Flow<AudioChunk>`），控制器仍可根据模式处理。

## 实施计划

### A. 数据模型扩展（`TTSProviderSetting.kt`）

在 `TTSProviderSetting` 基类增加两个抽象属性，并修改 `copyProvider`：

```kotlin
abstract val playbackMode: String  // "stream" | "chunk"
abstract val chunkLength: Int      // 可配置长度；0 代表“不切片”
```

在每个子类中添加对应字段，默认值：
- `playbackMode = "stream"`
- `chunkLength = 160`（保留向后兼容，用户可改）

> **FishAudio 特例**：FishAudio 原本就有 `chunkLength: Int = 300`（作为 API `chunk_length` 参数）。两者语义同源（都是“一次合成的文本片大小”），因此合并为同一个 `override val chunkLength`（默认 300），避免字段重名冲突，且不破坏 FishAudio API 调用。

`chunkLength = 0` 代表“不切片”。UI 层处理下拉预设（150/300/500/800/1200/2000）与手动输入，以及“不切片”映射为 0。

### B. 控制器逻辑重构（`TtsController.kt`）

1. **读取配置**：`speak()` 时从 `currentProvider` 读取 `playbackMode` 和 `chunkLength`。
2. **模式分支**：
   - `stream` 模式（或 `chunkLength <= 0`）：不切片，整段文本作为单个 `TtsChunk` 处理。
   - `chunk` 模式：使用可配置的 `chunkLength` 传入 `TextChunker`，保留逐块播放、预取、缓存逻辑。
3. **预取与缓存**：切片模式保留现有缓存；流模式下整段一次性合成。

### C. 文本分片器（`TextChunker.kt`）

- `maxChunkLength` 由控制器传入（不再硬编码 160）。
- `chunkLength <= 0` 由控制器在调用前拦截（流模式直接整段）。

### D. UI 设置页（`TTSProviderConfigure.kt`）

提取公共可组合组件 `PlaybackModeAndChunkLengthForm()`，在所有引擎配置页统一调用：
1. **播放模式下拉框**：流播放 / 切片播放，默认流播放。
2. **切片长度设置**（下拉 + 手动输入）：
   - 下拉预设：不切片、150、300、500、800、1200、2000。
   - 手动输入框：允许任意正整数。
   - “不切片”映射为 `chunkLength = 0`。

### E. 序列化与兼容性

- 所有 `TTSProviderSetting` 子类使用 `@Serializable`，新增字段提供默认值，确保旧配置反序列化不失败。
- `copyProvider()` 透传新字段。

### F. 验证点

- `playbackMode = "stream"` + 完整文本 → 整段合成播放，无 120ms 间隔。
- `playbackMode = "chunk"` + `chunkLength = 300` → `TextChunker` 按 300 字符切片，逐块播放。
- `chunkLength = 0`（不切片）→ 文本不分片。
- 每个引擎配置页的下拉框和输入框正常保存/加载。
- 缓存文件生成（`tts_$messageId.$ext`）在两种模式下行为一致。

## 风险与注意事项

1. **流模式下的缓存**：`AudioPlayer.playStream()` 支持 `cacheEnabled` 和 `getCacheFileFunc`，控制器需传入正确的 `messageId` 和格式判断（`AudioFormat.PCM` → `wav`，否则用格式名）。
2. **预取在流模式下的意义**：流模式下不需要 `prefetchFrom()` 和 `ConcurrentHashMap` 缓存。
3. **引擎不支持流式的适配**：如果某引擎的 `generateSpeech()` 只返回单个 `AudioChunk`，整段模式仍可正常播放。
4. **FishAudio 双语义**：`chunkLength` 同时影响 FishAudio 服务端 `chunk_length` 与客户端文本切片；二者同源，行为一致。
