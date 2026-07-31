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
import me.rerere.ai.provider.ImageApiDialect
import me.rerere.ai.provider.ImageModelCapabilities
import me.rerere.ai.provider.ImageModelIdMapping
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
    isOpenAICompatible: Boolean = false,
    onSave: (Model) -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    var page by remember { mutableStateOf(0) }
    val pages = buildList {
        add("基本")
        add("能力")
        if (isWaveSpeed) add("LoRA")
        if (isOpenAICompatible) add("模型映射")
        add("自定义参数")
    }

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
                    "基本" -> ImageModelBasicPage(
                        model = model,
                        supportsSystemPrompt = isOpenAICompatible,
                        onChange = { model = it },
                    )
                    "能力" -> ImageModelCapabilitiesPage(
                        model = model,
                        isWaveSpeed = isWaveSpeed,
                        isOpenAICompatible = isOpenAICompatible,
                        onChange = { model = it },
                    )
                    "LoRA" -> WaveSpeedLorasPage(model = model, onChange = { model = it })
                    "模型映射" -> ImageModelIdMappingsPage(model = model, onChange = { model = it })
                    else -> ImageModelParametersPage(model = model, onChange = { model = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (model.displayName.isNotBlank() && model.modelId.isNotBlank()) {
                    onSave(if (isOpenAICompatible) model else model.copy(imageSystemPrompt = ""))
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImageModelBasicPage(
    model: Model,
    supportsSystemPrompt: Boolean,
    onChange: (Model) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(model.displayName, { onChange(model.copy(displayName = it)) }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model.modelId, { onChange(model.copy(modelId = it)) }, label = { Text("模型 ID (API 标识)") }, modifier = Modifier.fillMaxWidth())
        if (supportsSystemPrompt) {
            OutlinedTextField(
                value = model.imageSystemPrompt,
                onValueChange = { onChange(model.copy(imageSystemPrompt = it)) },
                label = { Text("System Prompt（对话生图可选）") },
                placeholder = { Text("用于 chat/completions 生图模型；留空则不发送 system 消息。") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImageModelCapabilitiesPage(
    model: Model,
    isWaveSpeed: Boolean,
    isOpenAICompatible: Boolean,
    onChange: (Model) -> Unit,
) {
    fun update(transform: (ImageModelCapabilities) -> ImageModelCapabilities) = onChange(model.copy(imageCapabilities = transform(model.imageCapabilities)))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isOpenAICompatible) {
            var showDialectMenu by remember { mutableStateOf(false) }
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("API 类型")
                    TextButton(onClick = { showDialectMenu = true }) {
                        Text(
                            when (model.imageCapabilities.apiDialect) {
                                ImageApiDialect.AUTO -> "自动（先试 Images API，失败回退对话生图）"
                                ImageApiDialect.IMAGES_API -> "Images API（/images/generations 与 /images/edits）"
                                ImageApiDialect.CHAT_COMPLETIONS -> "对话生图（/chat/completions，Gemini 等多模态模型）"
                            }
                        )
                    }
                    DropdownMenu(expanded = showDialectMenu, onDismissRequest = { showDialectMenu = false }) {
                        listOf(
                            ImageApiDialect.AUTO to "自动（先试 Images API，失败回退对话生图）",
                            ImageApiDialect.IMAGES_API to "Images API（/images/generations 与 /images/edits）",
                            ImageApiDialect.CHAT_COMPLETIONS to "对话生图（/chat/completions，Gemini 等多模态模型）",
                        ).forEach { (dialect, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    update { capabilities -> capabilities.copy(apiDialect = dialect) }
                                    showDialectMenu = false
                                },
                            )
                        }
                    }
                    Text(
                        "指定此模型使用的 OpenAI 兼容接口。选择固定类型可避免自动探测失败重试。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
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
                                                WaveSpeedLoraProtocol.NONE -> 0
                                                else -> capabilities.maxLoras.takeIf { it > 0 } ?: 3
                                            },
                                        )
                                    }
                                    showProtocolMenu = false
                                },
                            )
                        }
                    }
                    if (model.imageCapabilities.loraProtocol != WaveSpeedLoraProtocol.NONE) {
                        OutlinedTextField(
                            value = model.imageCapabilities.maxLoras.takeIf { it > 0 }?.toString().orEmpty(),
                            onValueChange = { value ->
                                update { capabilities ->
                                    capabilities.copy(
                                        maxLoras = value.toIntOrNull()?.coerceAtLeast(1) ?: 0
                                    )
                                }
                            },
                            label = { Text("每次最多 LoRA 数量") },
                            placeholder = { Text("默认 3，仅作为提示词告知 AI，不在后端校验") },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                            WaveSpeedLoraProtocol.PATH_SCALE_ARRAY -> "API：loras: [{ path, scale }]；这里可登记任意多个；每次最多数量由上方字段提示给 AI。"
                            WaveSpeedLoraProtocol.WEIGHT_SCALE -> "API：lora_weights + lora_scale；这里可登记任意多个；每次最多数量由上方字段提示给 AI。"
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val maxLoras = model.imageCapabilities.maxLoras.takeIf { it > 0 } ?: 3
        Text("为此模型登记可调用的 LoRA。可登记任意多个；LLM 会看到所有 LoRA 的 ID 与说明，但 URL 不会暴露给 LLM。")
        if (model.imageCapabilities.loraProtocol == WaveSpeedLoraProtocol.NONE) {
            Text("请先在“能力”页启用 LoRA。")
        } else {
            Text(
                text = "调用时限制：每次最多选择 $maxLoras 个 LoRA；这里只作为提示词告知 AI，后端不校验数量；这里不限制登记数量。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        model.waveSpeedLoras.forEachIndexed { index, lora ->
            WaveSpeedLoraCard(
                lora = lora,
                protocol = model.imageCapabilities.loraProtocol,
                onChange = { updated ->
                    onChange(
                        model.copy(
                            waveSpeedLoras = model.waveSpeedLoras.mapIndexed { i, value ->
                                if (i == index) updated else value
                            }
                        )
                    )
                },
                onDelete = {
                    onChange(
                        model.copy(
                            waveSpeedLoras = model.waveSpeedLoras.filterIndexed { i, _ -> i != index }
                        )
                    )
                },
            )
        }
        TextButton(enabled = model.imageCapabilities.loraProtocol != WaveSpeedLoraProtocol.NONE, onClick = {
            onChange(model.copy(waveSpeedLoras = model.waveSpeedLoras + WaveSpeedLora("", "", "")))
        }) { Icon(HugeIcons.Add01, null); Text("添加 LoRA（已登记 ${model.waveSpeedLoras.size} 个，调用最多 $maxLoras 个）") }
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
private fun ImageModelIdMappingsPage(model: Model, onChange: (Model) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("按模型参数值改写实际发送给后端的模型 ID。命中的参数不会继续发送给后端，适合 NewAPI 用 resolution 选择 1K / 2K / 4K 模型。")
        model.imageModelIdMappings.forEachIndexed { index, mapping ->
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型 ID 映射", modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            onChange(model.copy(imageModelIdMappings = model.imageModelIdMappings.filterIndexed { i, _ -> i != index }))
                        }) { Icon(HugeIcons.Delete01, "删除") }
                    }
                    OutlinedTextField(
                        value = mapping.parameterKey,
                        onValueChange = { value -> updateImageModelIdMapping(model, index, mapping.copy(parameterKey = value), onChange) },
                        label = { Text("参数名，例如 resolution") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = mapping.parameterValue,
                        onValueChange = { value -> updateImageModelIdMapping(model, index, mapping.copy(parameterValue = value), onChange) },
                        label = { Text("参数值，例如 1K / 2K / 4K") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = mapping.modelId,
                        onValueChange = { value -> updateImageModelIdMapping(model, index, mapping.copy(modelId = value), onChange) },
                        label = { Text("实际发送的模型 ID") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        TextButton(onClick = {
            onChange(
                model.copy(
                    imageModelIdMappings = model.imageModelIdMappings + ImageModelIdMapping(
                        parameterKey = "resolution",
                        parameterValue = "",
                        modelId = "",
                    )
                )
            )
        }) { Icon(HugeIcons.Add01, null); Text("添加模型 ID 映射") }
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

private fun updateImageModelIdMapping(
    model: Model,
    index: Int,
    mapping: ImageModelIdMapping,
    onChange: (Model) -> Unit,
) {
    onChange(
        model.copy(
            imageModelIdMappings = model.imageModelIdMappings.mapIndexed { i, value ->
                if (i == index) mapping else value
            }
        )
    )
}
