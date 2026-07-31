package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.FileCompressSetting
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingPreferencesFilePage(
    vm: SettingVM = koinInject(),
    syncAdvancedConfigStore: SyncAdvancedConfigStore = koinInject(),
    settingsJsonExchange: SettingsJsonExchange = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsState()
    val compressSetting = settings.fileCompressSetting
    val syncAdvancedConfig by syncAdvancedConfigStore.configFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    fun updateCompressSetting(newSetting: FileCompressSetting) {
        vm.updateSettings(settings.copy(fileCompressSetting = newSetting))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("数据与备份设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SyncAdvancedSettingsCard(
                    config = syncAdvancedConfig,
                    configPath = SyncAdvancedConfigStore.RELATIVE_PATH,
                    onChange = { transform ->
                        scope.launch { syncAdvancedConfigStore.update(transform) }
                    },
                    onReset = {
                        scope.launch { syncAdvancedConfigStore.reset() }
                    },
                    r2PresignTtlSeconds = settings.r2PresignTtlSeconds,
                    onR2PresignTtlChange = { ttl ->
                        vm.updateSettings(settings.copy(r2PresignTtlSeconds = ttl))
                    },
                )
            }

            // 场景 1：聊天发送图片压缩
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("发送图片压缩 (聊天附件)") },
                ) {
                    item(
                        headlineContent = { Text("图片压缩质量") },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = compressSetting.chatImageJpegQuality.toFloat(),
                                    onValueChange = {
                                        updateCompressSetting(compressSetting.copy(chatImageJpegQuality = it.toInt().coerceIn(1, 100)))
                                    },
                                    valueRange = 50f..100f,
                                    steps = 49,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${compressSetting.chatImageJpegQuality}%")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("小图跳过压缩阈值") },
                        supportingContent = {
                            Column {
                                Text("低于 ${compressSetting.chatImageSkipBytes / 1024 / 1024} MB 且分辨率合规的图片不压缩。")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = (compressSetting.chatImageSkipBytes / 1024 / 1024).toFloat(),
                                        onValueChange = {
                                            updateCompressSetting(compressSetting.copy(chatImageSkipBytes = it.toLong() * 1024L * 1024L))
                                        },
                                        valueRange = 0f..20f,
                                        steps = 19,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${compressSetting.chatImageSkipBytes / 1024 / 1024} MB")
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("最大边长限制") },
                        supportingContent = {
                            Column {
                                Text("超过该边长的图片将被等比缩放。当前: ${compressSetting.chatImageMaxEdge} px")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = compressSetting.chatImageMaxEdge.toFloat(),
                                        onValueChange = {
                                            updateCompressSetting(compressSetting.copy(chatImageMaxEdge = (it / 128).toInt() * 128))
                                        },
                                        valueRange = 1024f..4096f,
                                        steps = 23,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${compressSetting.chatImageMaxEdge} px")
                                }
                            }
                        },
                    )
                }
            }

            // 场景 2：生图反给 AI 的预览图压缩
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("生图反给 AI 预览压缩") },
                ) {
                    item(
                        headlineContent = { Text("预览图压缩质量") },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = compressSetting.llmPreviewJpegQuality.toFloat(),
                                    onValueChange = {
                                        updateCompressSetting(compressSetting.copy(llmPreviewJpegQuality = it.toInt().coerceIn(1, 100)))
                                    },
                                    valueRange = 30f..100f,
                                    steps = 69,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${compressSetting.llmPreviewJpegQuality}%")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("跳过压缩阈值") },
                        supportingContent = {
                            Column {
                                Text("低于 ${compressSetting.llmPreviewSkipBytes / 1024} KB 的预览图不二次压缩。")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = (compressSetting.llmPreviewSkipBytes / 1024).toFloat(),
                                        onValueChange = {
                                            updateCompressSetting(compressSetting.copy(llmPreviewSkipBytes = it.toLong() * 1024L))
                                        },
                                        valueRange = 128f..2048f,
                                        steps = 14,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${compressSetting.llmPreviewSkipBytes / 1024} KB")
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("最大边长限制") },
                        supportingContent = {
                            Column {
                                Text("降低分辨率可大幅节省 AI 视觉模型 Token。当前: ${compressSetting.llmPreviewMaxEdge} px")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = compressSetting.llmPreviewMaxEdge.toFloat(),
                                        onValueChange = {
                                            updateCompressSetting(compressSetting.copy(llmPreviewMaxEdge = (it / 128).toInt() * 128))
                                        },
                                        valueRange = 512f..2560f,
                                        steps = 15,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${compressSetting.llmPreviewMaxEdge} px")
                                }
                            }
                        },
                    )
                }
            }

            // 场景 3：文件管理手动压缩
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("文件管理手动压缩") },
                ) {
                    item(
                        headlineContent = { Text("手动压缩质量") },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = compressSetting.manualCompressJpegQuality.toFloat(),
                                    onValueChange = {
                                        updateCompressSetting(compressSetting.copy(manualCompressJpegQuality = it.toInt().coerceIn(1, 100)))
                                    },
                                    valueRange = 30f..100f,
                                    steps = 69,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${compressSetting.manualCompressJpegQuality}%")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("跳过压缩阈值") },
                        supportingContent = {
                            Column {
                                Text("低于 ${compressSetting.manualCompressSkipBytes / 1024} KB 的文件不重新压缩。")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = (compressSetting.manualCompressSkipBytes / 1024).toFloat(),
                                        onValueChange = {
                                            updateCompressSetting(compressSetting.copy(manualCompressSkipBytes = it.toLong() * 1024L))
                                        },
                                        valueRange = 128f..2048f,
                                        steps = 14,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${compressSetting.manualCompressSkipBytes / 1024} KB")
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("最大边长限制") },
                        supportingContent = {
                            Column {
                                Text("手动压缩后的最大分辨率。当前: ${compressSetting.manualCompressMaxEdge} px")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = compressSetting.manualCompressMaxEdge.toFloat(),
                                        onValueChange = {
                                            updateCompressSetting(compressSetting.copy(manualCompressMaxEdge = (it / 128).toInt() * 128))
                                        },
                                        valueRange = 512f..2560f,
                                        steps = 15,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${compressSetting.manualCompressMaxEdge} px")
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("设置 JSON 交换") },
                ) {
                    item(
                        headlineContent = { Text("导出设置 JSON") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("把当前完整设置导出到 files/${SettingsJsonExchange.RELATIVE_PATH}，方便用 Workspace 或 Agent 编辑。")
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching { settingsJsonExchange.exportAll() }
                                                .onSuccess { result ->
                                                    toaster.show("已导出：${result.file.absolutePath}", type = ToastType.Success)
                                                }
                                                .onFailure { error ->
                                                    toaster.show("导出失败：${error.message ?: error::class.simpleName}", type = ToastType.Error)
                                                }
                                        }
                                    },
                                ) {
                                    Text("导出设置 JSON")
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("应用本地 JSON 并同步") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("读取 files/${SettingsJsonExchange.RELATIVE_PATH}，覆盖当前设置，并通过现有 D1 同步扩散到其他设备。")
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching { settingsJsonExchange.importAllAndSync() }
                                                .onSuccess { result ->
                                                    toaster.show("已应用并进入同步队列：${result.file.absolutePath}", type = ToastType.Success)
                                                }
                                                .onFailure { error ->
                                                    toaster.show("导入失败：${error.message ?: error::class.simpleName}", type = ToastType.Error)
                                                }
                                        }
                                    },
                                ) {
                                    Text("应用本地 JSON 并同步")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
