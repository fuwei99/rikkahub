package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image03
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

/**
 * Chat composer control for the local image-generation tool.
 *
 * The selected model is persisted as Settings.imageGenerationModelId, which is also
 * the fallback model used by ImageGenerationTool when the model does not pass a
 * `model` argument in its tool call.
 */
@Composable
fun ImageGenerationPickerButton(
    settings: Settings,
    assistant: Assistant,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
    onSelectModel: (Model) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val enabled = assistant.localTools.contains(LocalToolOption.ImageGeneration)
    val selectedModel = settings.findModelById(settings.imageGenerationModelId)

    ToggleSurface(
        modifier = modifier,
        checked = enabled,
        onClick = { showPicker = true },
    ) {
        Icon(
            imageVector = HugeIcons.Image03,
            contentDescription = "Image generation",
            modifier = Modifier.padding(8.dp).size(24.dp),
        )
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("图像生成", style = MaterialTheme.typography.titleLarge)

                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("在当前对话中启用")
                            Text(
                                "让模型可调用图像生成工具",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                onUpdateAssistant(
                                    assistant.copy(
                                        localTools = if (checked) {
                                            assistant.localTools + LocalToolOption.ImageGeneration
                                        } else {
                                            assistant.localTools - LocalToolOption.ImageGeneration
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }

                Text("图像模型", style = MaterialTheme.typography.titleMedium)
                val models = settings.imageProviders
                    .filter { it.enabled }
                    .flatMap { it.models }
                    .filter { it.type == ModelType.IMAGE }

                if (models.isEmpty()) {
                    Text(
                        "尚未配置可用的图像模型。请先在设置中添加图像生成服务和模型。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(models, key = { it.id.toString() }) { model ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectModel(model)
                                        showPicker = false
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedModel?.id == model.id,
                                        onClick = {
                                            onSelectModel(model)
                                            showPicker = false
                                        },
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(model.displayName)
                                        Text(
                                            model.modelId,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
