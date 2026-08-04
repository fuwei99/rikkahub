package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.VectorProviderConfigure
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingVectorDetailPage(
    providerId: String,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val provider = settings.vectorProviders.firstOrNull { it.id.toString() == providerId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = provider?.name ?: "向量服务")
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (provider == null) {
            Text(
                text = "未找到该向量服务",
                modifier = Modifier.padding(innerPadding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "向量模型服务（OpenAI 兼容 /embeddings）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "维度在「记忆设置」中统一配置（默认 1024）。切换模型/维度后需重建向量索引。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (provider is VectorProviderSetting.OpenAI) {
                            VectorProviderConfigure(provider) { newState ->
                                vm.updateSettings(
                                    settings.copy(
                                        vectorProviders = settings.vectorProviders.map {
                                            if (it.id == newState.id) newState else it
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "模型（${provider.models.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            items(provider.models, key = { it.id }) { model ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = model.displayName.ifBlank { model.modelId },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = model.modelId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                val updated = provider.removeVectorModel(model)
                                vm.updateSettings(
                                    settings.copy(
                                        vectorProviders = settings.vectorProviders.map {
                                            if (it.id == provider.id) updated else it
                                        }
                                    )
                                )
                            }
                        ) {
                            Icon(HugeIcons.Delete01, "删除模型")
                        }
                    }
                }
            }

            item {
                AddVectorModelButton { model ->
                    val updated = provider.addModel(model)
                    vm.updateSettings(
                        settings.copy(
                            vectorProviders = settings.vectorProviders.map {
                                if (it.id == provider.id) updated else it
                            }
                        )
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                OutlinedButton(
                    onClick = {
                        vm.updateSettings(
                            settings.copy(
                                vectorProviders = settings.vectorProviders.filter { it.id != provider.id }
                            )
                        )
                        toaster.show("已删除 ${provider.name}", type = ToastType.Success)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除此向量服务")
                }
            }
        }
    }
}

/** 删除向量模型（保留同名模型 id 时不删，避免内存指向空） */
private fun VectorProviderSetting.removeVectorModel(model: Model): VectorProviderSetting =
    delModel(model)

@Composable
private fun AddVectorModelButton(onAdd: (Model) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.Add01, null)
        Text("添加模型")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text("添加向量模型")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text("模型 ID（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("显示名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = modelId.isNotBlank(),
                    onClick = {
                        onAdd(
                            Model(
                                modelId = modelId.trim(),
                                displayName = displayName.trim().ifBlank { modelId.trim() },
                                type = ModelType.EMBEDDING,
                                id = Uuid.random(),
                            )
                        )
                        modelId = ""
                        displayName = ""
                        showDialog = false
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
