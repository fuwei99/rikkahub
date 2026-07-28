package me.rerere.rikkahub.data.sync.core

import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.s3.S3Config

/**
 * settings 上推/下拉的双向清洗规则（见 docs/cloud-sync-plan.md §5.2）：
 * - displaySetting：观感字段，走独立 bundle "settings.display"（受设备开关控制）
 * - d1Config / s3Config / r2Accounts secret：含 token/密钥 的设备机密，敏感秘钥不存 D1
 * - launchCount / sponsorAlertDismissedAt：volatile 噪音字段
 */
object SyncSettingsFilter {

    /** 上推前：剥离本机字段与敏感秘钥 */
    fun forUpload(settings: Settings): Settings = settings.copy(
        displaySetting = DisplaySetting(),
        d1Config = D1Config(),
        s3Config = S3Config(),
        r2Accounts = settings.r2Accounts.map { it.copy(secretAccessKey = "") },
        launchCount = 0,
        sponsorAlertDismissedAt = 0,
    )

    /** 下拉后：用本机秘钥与状态覆盖合并 */
    fun mergeRemote(local: Settings, remote: Settings): Settings {
        val localSecretMap = local.r2Accounts.associate { it.id to it.secretAccessKey }
        val mergedR2 = remote.r2Accounts.map { acct ->
            if (acct.secretAccessKey.isBlank()) {
                acct.copy(secretAccessKey = localSecretMap[acct.id] ?: "")
            } else acct
        }
        return remote.copy(
            displaySetting = local.displaySetting,
            d1Config = local.d1Config,
            s3Config = local.s3Config,
            r2Accounts = mergedR2,
            launchCount = local.launchCount,
            sponsorAlertDismissedAt = local.sponsorAlertDismissedAt,
        )
    }
}
