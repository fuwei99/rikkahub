package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.FileCompressSetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingPreferencesFilePage(vm: SettingVM = koinInject()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsState()
    val compressSetting = settings.fileCompressSetting

    fun updateCompressSetting(newSetting: FileCompressSetting) {
        vm.updateSettings(settings.copy(fileCompressSetting = newSetting))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("文件设置") },
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
        }
    }
}
