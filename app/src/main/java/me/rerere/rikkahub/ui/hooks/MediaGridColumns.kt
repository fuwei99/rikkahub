package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState

/**
 * 媒体/文件网格每行列数。
 *
 * 该偏好保存在 SharedPreferences("rikkahub.preferences")，属于 **device-local** 段：
 * D1 同步只推送 Settings DataStore + 各数据表，SharedPreferences 从不参与同步，
 * 因此平板 / 手机可以各自设置不同的列数而互不影响。
 */
const val MEDIA_GRID_COLUMNS_KEY = "media_grid_columns"
const val MEDIA_GRID_COLUMNS_DEFAULT = 4
val MEDIA_GRID_COLUMNS_OPTIONS = listOf(1, 2, 3, 4, 5, 6)

@Composable
fun rememberMediaGridColumns(): MutableState<Int> =
    rememberSharedPreferenceInt(MEDIA_GRID_COLUMNS_KEY, MEDIA_GRID_COLUMNS_DEFAULT)
