# 开发日志：恢复历史搜索瘦身开关与 TTS 正则过滤规则管线

## 基本信息
* **日期时间**：2026-07-22 15:58 (UTC+8)
* **开发者**：江锋 & 程天赢
* **主干分支**：`master`
* **目的**：
  1. 纠正“历史搜索结果上下文瘦身 (Clear History Search)”开关配置入口位置，使其从系统通用设置移回对话底部搜索服务设置弹窗 (`SearchPicker`)。
  2. 移植与恢复从 `backup-master-legacy` 分支遗漏的 TTS 正则过滤与自定义多规则替换管线 (`TtsRegexRule`) 及设置 UI。

---

## 变更详情

### 1. 对话搜索弹窗重构 (`app/.../SearchPicker.kt`)
* 恢复在 `AppSearchSettings` 列表顶部展示“保留搜索结果正文”的 Switch 开关。
* 开关状态绑定：`checked = !settings.searchCommonOptions.clearHistorySearch`，修改时持久化至 `SettingsStore`。

### 2. 系统设置页瘦身 (`app/.../SettingSearchPage.kt`)
* 移除 `SettingSearchPage` 中被错误添加的 `setting_page_search_clear_history` 选项，确保全局搜索引擎配置页仅包含全局底层设定。

### 3. TTS 正则过滤与自定义规则链条移植与重构
* **数据模型与 Provider 设置 (`speech/.../TTSProviderSetting.kt`)**：
  * 新增 `TtsRegexRule` 数据结构（包含 `id`、`name`、`pattern`、`replaceWith`、`enabled` 字段与 JSON 序列化注解）。
  * 在 `TTSProviderSetting` 抽象类及所有底层 Provider 实现（OpenAI, Gemini, SystemTTS, MiniMax, Qwen, Groq, XAI, MiMo, ElevenLabs, Step, FishAudio, Doubao, VolcengineAgent）中添加 `filterRegex`、`replaceWith` 以及 `regexRules: List<TtsRegexRule>` 属性支持。
* **控制器文本预处理管线 (`speech/.../TtsController.kt`)**：
  * 在 `TtsController` 的 `speak(...)` 入口处引入 `applyRegexFilters(text, provider)` 校验。
  * 依次执行传统基础 `filterRegex` 正则替换以及 `regexRules` 规则列表中勾选启用的多条正则替换规则，替换完成后再交给 `TextChunker` 进行切片与流式合成。
* **语音服务设置 UI 恢复 (`app/.../TTSProviderConfigure.kt`)**：
  * 为 TTS 配置页增加基础正则过滤配置项 (`filterRegex` / `replaceWith`)。
  * 增加自定义 `TtsRegexRulesSection` 规则列表组件，支持新建/编辑正则规则（实时语法校验）、规则上下排序、独立开关切换、单条删除以及 JSON 格式的导出与粘贴导入。

### 4. 新增 Doubao TTS 与火山方舟Agent (Seed TTS 2.0) 适配
* **新增 Provider 实现**：
  * **`DoubaoTTSProvider.kt`**：流式 AAC 格式音频输出，实现速度和音调调节。
  * **`VolcengineAgentTTSProvider.kt`**：流式对接火山引擎 Agent 专属接口，通过单向流 HTTP Chunked 逐行解析 JSON 格式返回的 Base64 音频并推送流数据。
* **接入与 UI 挂载**：
  * 在 `TTSProviderSetting.kt` 的 `Types` 中注册并支持其配置保存。
  * 在 `TTSManager.kt` 进行生成与绑定调度。
  * 在 `SettingSpeechPage.kt` 与 `TTSProviderConfigure.kt` 中添加对应的选择卡与配置参数项（API Key, Base URL, Resource ID, Speaker 等配置面板）。

---

## 验证与发布

* **Git 提交与推送**：
  * 变动已提交并推至远程仓库 `master` 分支：
    ```bash
    commit 1ac0e9e0: feat(speech): restore TTS regex filtering and rule pipeline configuration
    commit 74a21f5e: feat(speech): add Doubao TTS and Volcengine Agent (Seed TTS 2.0) provider support
    ```
