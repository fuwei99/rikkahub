package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ImageModelCapabilities
import me.rerere.ai.provider.ImageModelParameter
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.WaveSpeedLora
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01

/** Full model editor for image providers. LoRAs are intentionally a WaveSpeed-only section. */
@Composable
fun ImageModelEditor(
    initialModel: Model,
    isWaveSpeed: Boolean,
    onSave: (Model) -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    var page by remember { mutableStateOf(0) }
    val pages = if (isWaveSpeed) listOf("基本", "能力", "LoRA", "自定义参数") else listOf("基本", "能力", "自定义参数")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialModel.modelId.isBlank()) "添加生图模型" else "编辑生图模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pages.forEachIndexed { index, title ->
                        TextButton(onClick = { page = index }) {
                            Text(
                                text = title,
                                color = if (page == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                when (pages[page]) {
                    "基本" -> ImageModelBasicPage(model = model, onChange = { model = it })
                    "能力" -> ImageModelCapabilitiesPage(model = model, isWaveSpeed = isWaveSpeed, onChange = { model = it })
                    "LoRA" -> WaveSpeedLorasPage(model = model, onChange = { model = it })
                    else -> ImageModelParametersPage(model = model, onChange = { model = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (model.displayName.isNotBlank() && model.modelId.isNotBlank()) onSave(model)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImageModelBasicPage(model: Model, onChange: (Model) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(model.displayName, { onChange(model.copy(displayName = it)) }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model.modelId, { onChange(model.copy(modelId = it)) }, label = { Text("模型 ID (API 标识)") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ImageModelCapabilitiesPage(model: Model, isWaveSpeed: Boolean, onChange: (Model) -> Unit) {
    fun update(transform: (ImageModelCapabilities) -> ImageModelCapabilities) = onChange(model.copy(imageCapabilities = transform(model.imageCapabilities)))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapabilityRow("支持参考图 / 图生图", "允许此模型接收聊天附件作为参考图", model.imageCapabilities.supportsImageEditing) {
            update { capabilities -> capabilities.copy(supportsImageEditing = it) }
        }
        if (model.imageCapabilities.supportsImageEditing) {
            OutlinedTextField(
                value = model.imageCapabilities.maxReferenceImages.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { value ->
                    update { capabilities -> capabilities.copy(maxReferenceImages = value.toIntOrNull()?.coerceAtLeast(1) ?: 0) }
                },
                label = { Text("最多参考图数量（留空代表由 API 默认限制）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isWaveSpeed) {
            var showProtocolMenu by remember { mutableStateOf(false) }
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("LoRA 协议")
                    TextButton(onClick = { showProtocolMenu = true }) {
                        Text(
                            when (model.imageCapabilities.loraProtocol) {
                                WaveSpeedLoraProtocol.NONE -> "无 LoRA"
                                WaveSpeedLoraProtocol.PATH_SCALE_ARRAY -> "Path + Scale 数组"
                                WaveSpeedLoraProtocol.WEIGHT_SCALE -> "P-Image 单权重 + Scale"
                            }
                        )
                    }
                    DropdownMenu(expanded = showProtocolMenu, onDismissRequest = { showProtocolMenu = false }) {
                        listOf(
                            WaveSpeedLoraProtocol.NONE to "无 LoRA",
                            WaveSpeedLoraProtocol.PATH_SCALE_ARRAY to "Path + Scale 数组",
                            WaveSpeedLoraProtocol.WEIGHT_SCALE to "P-Image 单权重 + Scale",
                        ).forEach { (protocol, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    update { capabilities ->
                                        capabilities.copy(
                                            loraProtocol = protocol,
                                            maxLoras = when (protocol) {
                                                WaveSpeedLoraProtocol.PATH_SCALE_ARRAY -> 3
                                                WaveSpeedLoraProtocol.WEIGHT_SCALE -> 1
                                                WaveSpeedLoraProtocol.NONE -> 0
                                            },
                                        )
                                    }
                                    showProtocolMenu = false
                                },
                            )
                        }
                    }
                    if (model.imageCapabilities.loraProtocol == WaveSpeedLoraProtocol.WEIGHT_SCALE) {
                        OutlinedTextField(
                            value = model.imageCapabilities.pImageHfApiToken,
                            onValueChange = { value ->
                                update { capabilities -> capabilities.copy(pImageHfApiToken = value) }
                            },
                            label = { Text("Hugging Face Token（可选）") },
                            placeholder = { Text("仅私有或 gated Hugging Face LoRA 需要") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        when (model.imageCapabilities.loraProtocol) {
                            WaveSpeedLoraProtocol.NONE -> "该模型不接受 LoRA。"
                            WaveSpeedLoraProtocol.PATH_SCALE_ARRAY -> "API：loras: [{ path, scale }]；每次最多 3 个。"
                            WaveSpeedLoraProtocol.WEIGHT_SCALE -> "API：lora_weights + lora_scale；每次只能选 1 个。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun WaveSpeedLorasPage(model: Model, onChange: (Model) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("为此模型登记可调用的 LoRA。LLM 只会看到 ID 与说明，URL 不会暴露给 LLM。")
        if (model.imageCapabilities.loraProtocol == WaveSpeedLoraProtocol.NONE) Text("请先在“能力”页启用 LoRA。")
        LazyColumn {
            itemsIndexed(model.waveSpeedLoras) { index, lora ->
                WaveSpeedLoraCard(
                    lora = lora,
                    protocol = model.imageCapabilities.loraProtocol,
                    onChange = { updated ->
                    onChange(model.copy(waveSpeedLoras = model.waveSpeedLoras.mapIndexed { i, value -> if (i == index) updated else value }))
                }, onDelete = { onChange(model.copy(waveSpeedLoras = model.waveSpeedLoras.filterIndexed { i, _ -> i != index })) })
            }
        }
        val maxLoras = model.imageCapabilities.maxLoras
        TextButton(enabled = maxLoras > 0 && model.waveSpeedLoras.size < maxLoras, onClick = {
            onChange(model.copy(waveSpeedLoras = model.waveSpeedLoras + WaveSpeedLora("", "", "")))
        }) { Icon(HugeIcons.Add01, null); Text("添加 LoRA（最多 $maxLoras 个）") }
    }
}

@Composable
private fun WaveSpeedLoraCard(
    lora: WaveSpeedLora,
    protocol: WaveSpeedLoraProtocol,
    onChange: (WaveSpeedLora) -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LoRA", modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(HugeIcons.Delete01, "删除") }
            }
            OutlinedTextField(lora.id, { onChange(lora.copy(id = it)) }, label = { Text("LoRA ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(lora.explanation, { onChange(lora.copy(explanation = it)) }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                lora.url,
                { onChange(lora.copy(url = it)) },
                label = {
                    Text(
                        if (protocol == WaveSpeedLoraProtocol.WEIGHT_SCALE) {
                            "权重 URL（lora_weights）"
                        } else {
                            "LoRA URL（path）"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImageModelParametersPage(model: Model, onChange: (Model) -> Unit) {
    // Parameter cards can be numerous. Constrain this part of the dialog and make it
    // independently scrollable so the dialog action buttons remain reachable.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("登记模型原生参数及其说明，LLM 可在 Tool 调用时决定是否传入。")
        model.imageParameters.forEachIndexed { index, parameter ->
            // Keep the editor text separate from the parsed JSON value. Parsing while a user is
            // midway through typing otherwise turns an incomplete string into an escaped JSON
            // string and makes ordinary backspace editing impossible.
            var defaultValueText by remember(index, parameter.key) {
                mutableStateOf(parameter.defaultValue.toEditableDefaultValue())
            }
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row { Text("参数", modifier = Modifier.weight(1f)); IconButton(onClick = {
                        onChange(model.copy(imageParameters = model.imageParameters.filterIndexed { i, _ -> i != index }))
                    }) { Icon(HugeIcons.Delete01, "删除") } }
                    OutlinedTextField(parameter.key, { value -> updateImageParameter(model, index, parameter.copy(key = value), onChange) }, label = { Text("参数名") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(parameter.explanation, { value -> updateImageParameter(model, index, parameter.copy(explanation = value), onChange) }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = defaultValueText,
                        onValueChange = { value ->
                            defaultValueText = value
                            val json = value.takeIf { it.isNotBlank() }?.let {
                                runCatching { Json.parseToJsonElement(it) }.getOrElse { JsonPrimitive(value) }
                            }
                            updateImageParameter(model, index, parameter.copy(defaultValue = json), onChange)
                        },
                        label = { Text("默认值（JSON，可选）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        TextButton(onClick = { onChange(model.copy(imageParameters = model.imageParameters + ImageModelParameter("", ""))) }) { Icon(HugeIcons.Add01, null); Text("添加自定义参数") }
    }
}

private fun kotlinx.serialization.json.JsonElement?.toEditableDefaultValue(): String = when (this) {
    null -> ""
    is JsonPrimitive -> content
    else -> toString()
}

private fun updateImageParameter(model: Model, index: Int, parameter: ImageModelParameter, onChange: (Model) -> Unit) {
    onChange(model.copy(imageParameters = model.imageParameters.mapIndexed { i, value -> if (i == index) parameter else value }))
}
