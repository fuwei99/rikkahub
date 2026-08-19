package me.rerere.rikkahub.focus

import android.accessibilityservice.AccessibilityService
import me.rerere.rikkahub.data.model.FocusLockSettings
import me.rerere.rikkahub.data.model.isActiveAt

/**
 * Small, process-local policy engine for the first focus-lock vertical slice.
 *
 * The schedule agent owns the lock state and can call [setLockActive] or
 * [grantTemporary]. The accessibility service only reports foreground package
 * changes; it does not contain schedule logic or poll UsageStats.
 */
object FocusPolicyEngine {
    private val temporaryWhiteList = mutableMapOf<String, Long>()
    private val stateLock = Any()

    @Volatile
    private var configuredSettings: FocusLockSettings = FocusLockSettings()

    @Volatile
    private var manualLockState: Boolean? = null

    /** Packages that remain usable during a focus session. */
    val baseWhiteList: Set<String> = setOf(
        "me.rerere.rikkahub",
        "me.rerere.rikkahub.debug",
        "com.tencent.mm",
        "com.eusoft.eudic",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.miui.home",
    )

    @Volatile
    var isLockActive: Boolean = false
        private set

    fun setLockActive(active: Boolean) {
        manualLockState = active
        refreshLockState()
    }

    fun updateSettings(settings: FocusLockSettings) {
        configuredSettings = settings
        // A persisted task change supersedes a stale agent override when the
        // user turns the lock off. Explicit agent state still wins otherwise.
        if (!settings.enabled) manualLockState = null
        refreshLockState()
    }

    fun refreshLockState() {
        isLockActive = manualLockState ?: configuredSettings.isActiveAt()
    }

    /**
     * Grant a package a short-lived pass. Non-positive durations are rejected
     * rather than accidentally creating an already-expired exception.
     */
    fun grantTemporary(packageName: String, durationMinutes: Int): Boolean {
        if (packageName.isBlank() || durationMinutes <= 0) return false
        val expireAt = System.currentTimeMillis() + durationMinutes * 60_000L
        synchronized(stateLock) {
            temporaryWhiteList[packageName] = expireAt
        }
        return true
    }

    fun revokeTemporary(packageName: String) {
        synchronized(stateLock) {
            temporaryWhiteList.remove(packageName)
        }
    }

    /** Snapshot intended for the supervision tool, with expired entries removed. */
    fun temporaryWhiteListSnapshot(): List<String> {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            temporaryWhiteList.entries.removeAll { it.value < now }
            return temporaryWhiteList.entries
                .sortedBy { it.key }
                .map { (packageName, expireAt) -> "$packageName=${(expireAt - now).coerceAtLeast(0L)}ms" }
        }
    }

    /** Returns true when the package is allowed right now. */
    fun isPackageAllowed(packageName: String): Boolean {
        if (packageName in configuredSettings.additionalAllowedPackages) return true
        if (packageName in baseWhiteList) {
            return configuredSettings.allowLauncherAndSystemUi ||
                packageName == "com.android.systemui" ||
                packageName == "me.rerere.rikkahub" ||
                packageName == "me.rerere.rikkahub.debug"
        }
        synchronized(stateLock) {
            val expireAt = temporaryWhiteList[packageName] ?: return false
            if (System.currentTimeMillis() <= expireAt) return true
            temporaryWhiteList.remove(packageName)
            return false
        }
    }

    /**
     * Handle one TYPE_WINDOW_STATE_CHANGED event. The service's own package is
     * always allowed, including debug builds whose application id has a suffix.
     */
    fun handleAppSwitch(service: AccessibilityService, currentPackage: String) {
        refreshLockState()
        if (!isLockActive || currentPackage.isBlank()) return
        if (!configuredSettings.returnHomeOnViolation) return
        if (currentPackage == service.packageName || isPackageAllowed(currentPackage)) return

        // Phase 1 deliberately uses the reliable system HOME action only.
        // Overlay UI and appeal presentation belong to a later phase.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}
