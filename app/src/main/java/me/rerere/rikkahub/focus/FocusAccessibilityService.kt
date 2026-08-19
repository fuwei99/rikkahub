package me.rerere.rikkahub.focus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Captures foreground window changes without a polling loop. */
class FocusAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString().orEmpty()
        FocusPolicyEngine.handleAppSwitch(this, packageName)
    }

    override fun onInterrupt() = Unit
}
