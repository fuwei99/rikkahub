package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.FileProcessingServiceOptions
import me.rerere.rikkahub.data.datastore.defaultFileProcessingServices
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingFileProcessingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val services = settings.fileProcessingServices.ifEmpty { defaultFileProcessingServices(settings.displaySetting) }
    val mineru = services.filterIsInstance<FileProcessingServiceOptions.MinerU>().firstOrNull()
        ?: FileProcessingServiceOptions.MinerU()

    fun save(updated: FileProcessingServiceOptions.MinerU) {
        val next = services.filterNot { it is FileProcessingServiceOptions.MinerU } + updated
        vm.updateSettings(settings.copy(fileProcessingServices = next))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("文件处理服务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("mineru") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("MinerU", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "给不支持文件多模态的模型解析 PDF / Office / 图片，转成 Markdown 文本再发送。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = mineru.enabled,
                                onCheckedChange = { save(mineru.copy(enabled = it)) },
                            )
                        }

                        OutlinedTextField(
                            value = mineru.displayName,
                            onValueChange = { save(mineru.copy(displayName = it)) },
                            label = { Text("显示名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = mineru.baseUrl,
                            onValueChange = { save(mineru.copy(baseUrl = it)) },
                            label = { Text("Base URL") },
                            supportingText = { Text("轻量 Agent 默认：https://mineru.net/api/v1/agent") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = mineru.language,
                            onValueChange = { save(mineru.copy(language = it)) },
                            label = { Text("语言") },
                            supportingText = { Text("默认 ch。可按 MinerU 文档填写 en、ja、ko 等语言值。") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        FileProcessingSwitchRow(
                            title = "OCR",
                            desc = "扫描件/图片文字识别。",
                            checked = mineru.ocr,
                            onCheckedChange = { save(mineru.copy(ocr = it)) },
                        )
                        FileProcessingSwitchRow(
                            title = "表格识别",
                            desc = "让 MinerU 尽量保留表格结构。",
                            checked = mineru.enableTable,
                            onCheckedChange = { save(mineru.copy(enableTable = it)) },
                        )
                        FileProcessingSwitchRow(
                            title = "公式识别",
                            desc = "识别文档中的公式内容。",
                            checked = mineru.enableFormula,
                            onCheckedChange = { save(mineru.copy(enableFormula = it)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileProcessingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
