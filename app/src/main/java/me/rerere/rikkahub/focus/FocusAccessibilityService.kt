package me.rerere.rikkahub.focus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Captures window changes without a polling loop.
 *
 * 注意：这里**不做**任何判定，包名解析与白名单判定全在 [FocusPolicyEngine]。
 * 事件的 packageName 只作为线索传下去——它可能是输入法、系统弹窗、Toast 的包名，
 * 直接拿来当前台应用会导致白名单失效（2026-08-20 bug）。
 */
class FocusAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val relevant = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!relevant) return
        FocusPolicyEngine.handleWindowEvent(
            service = this,
            eventPackage = event.packageName?.toString().orEmpty(),
            eventClassName = event.className?.toString().orEmpty(),
        )
    }

    override fun onInterrupt() = Unit
}
