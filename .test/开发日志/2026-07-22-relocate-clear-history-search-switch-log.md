# 开发日志：恢复历史搜索瘦身开关至对话搜索设置弹窗

## 基本信息
* **日期时间**：2026-07-22 15:44 (UTC+8)
* **主干分支**：`master`
* **目的**：纠正“历史搜索结果上下文瘦身 (Clear History Search)”开关的配置入口位置，使其从系统通用设置中移回对话底部的搜索服务设置弹窗 (`SearchPicker`) 中，恢复与旧版一致的用户交互体验。

---

## 变更详情

### 1. 对话搜索弹窗重构 (`app/src/main/java/me/rerere/rikkahub/ui/components/ai/SearchPicker.kt`)
* 恢复在 `AppSearchSettings` 列表顶部展示“保留搜索结果正文”的 Switch 开关。
* 开关状态绑定为：`checked = !settings.searchCommonOptions.clearHistorySearch`。
* 状态更新逻辑：当用户切换开关时，修改 `clearHistorySearch = !checked` 并持久化到 `SettingsStore` 中。

### 2. 系统设置页瘦身 (`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt`)
* 移除 `CommonOptions` 中被错误添加的 `setting_page_search_clear_history` 选项卡，确保全局搜索引擎配置页仅包含“结果数量”等全局底层设定。

---

## 验证与发布
* 本地编译测试正常（代码逻辑结构正确无警告）。
* 相关代码均已成功提交并推至远程仓库：
  ```bash
  7b4ccd5a.. origin/master
  ```
