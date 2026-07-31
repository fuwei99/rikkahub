package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration

/**
 * 原 `settings.preferences_pb` DataStore 单例。
 *
 * 注：日常读写已切到 [SettingsRepository]（纯 JSON）。
 * 本委托**仅用于一次性迁移**：App 升级后 [SettingsRepository.bootstrapFromDataStore]
 * 通过它读出 PB 全部 Key，转写为 JSON 文件，然后 PB 保留作为回退保险。
 */
internal val Context.settingsStore: androidx.datastore.core.DataStore<Preferences>
    by preferencesDataStore(
        name = "settings",
        produceMigrations = { _ ->
            listOf(
                PreferenceStoreV1Migration(),
                PreferenceStoreV2Migration(),
                PreferenceStoreV3Migration(),
            )
        },
    )
