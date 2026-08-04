package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.KeyStrategy
import me.rerere.ai.provider.apiKeyTokens
import me.rerere.ai.provider.keyStrategy
import me.rerere.ai.provider.withApiKeyTokens
import me.rerere.ai.provider.withKeyStrategy
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import kotlin.reflect.KClass

@Composable
fun ImageProviderConfigure(
    provider: ImageProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        if (!provider.builtIn) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ImageProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ImageProviderSetting.Types.size
                        ),
                        label = {
                            Text(
                                when (type) {
                                    ImageProviderSetting.OpenAI::class -> "OpenAI"
                                    ImageProviderSetting.NewAPI::class -> "NewAPI"
                                    ImageProviderSetting.Volcengine::class -> "火山方舟"
                                    ImageProviderSetting.Wavespeed::class -> "WaveSpeed"
                                    ImageProviderSetting.TokenRhythm::class -> "TokenRhythm"
                                    else -> ""
                                }
                            )
                        },
                        selected = provider::class == type,
                        onClick = { onEdit(provider.convertTo(type)) }
                    )
                }
            }
        }

        when (provider) {
            is ImageProviderSetting.OpenAI -> ImageProviderConfigureOpenAI(provider, onEdit)
            is ImageProviderSetting.NewAPI -> ImageProviderConfigureNewAPI(provider, onEdit)
            is ImageProviderSetting.Volcengine -> ImageProviderConfigureVolcengine(provider, onEdit)
            is ImageProviderSetting.Wavespeed -> ImageProviderConfigureWavespeed(provider, onEdit)
            is ImageProviderSetting.TokenRhythm -> ImageProviderConfigureTokenRhythm(provider, onEdit)
        }
    }
}

fun ImageProviderSetting.convertTo(type: KClass<out ImageProviderSetting>): ImageProviderSetting {
    if (this::class == type) return this

    val apiKey = when (this) {
        is ImageProviderSetting.OpenAI -> this.apiKey
        is ImageProviderSetting.NewAPI -> this.apiKey
        is ImageProviderSetting.Volcengine -> this.apiKey
        is ImageProviderSetting.Wavespeed -> this.apiKey
        is ImageProviderSetting.TokenRhythm -> this.apiKey
    }
    val keyStrategy = this.keyStrategy
    val convertedBaseUrl = when (type) {
        ImageProviderSetting.OpenAI::class -> ImageProviderSetting.OpenAI().baseUrl
        ImageProviderSetting.NewAPI::class -> ImageProviderSetting.NewAPI().baseUrl
        ImageProviderSetting.Volcengine::class -> ImageProviderSetting.Volcengine().baseUrl
        ImageProviderSetting.Wavespeed::class -> ImageProviderSetting.Wavespeed().baseUrl
        ImageProviderSetting.TokenRhythm::class -> ImageProviderSetting.TokenRhythm().baseUrl
        else -> error("Unsupported type: $type")
    }

    return when (type) {
        ImageProviderSetting.OpenAI::class -> ImageProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy
        )
        ImageProviderSetting.NewAPI::class -> ImageProviderSetting.NewAPI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy
        )
        ImageProviderSetting.Volcengine::class -> ImageProviderSetting.Volcengine(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy
        )
        ImageProviderSetting.Wavespeed::class -> ImageProviderSetting.Wavespeed(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy
        )
        ImageProviderSetting.TokenRhythm::class -> ImageProviderSetting.TokenRhythm(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy
        )
        else -> error("Unsupported type: $type")
    }
}

@Composable
private fun ImageProviderConfigureOpenAI(
    provider: ImageProviderSetting.OpenAI,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.OpenAI().baseUrl)) }) {
                Text("重置", fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    ImageProviderStrategySection(provider, onEdit)
    ApiTokenListSection(provider, onEdit)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("启用此生图服务商")
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }
}

@Composable
private fun ImageProviderConfigureNewAPI(
    provider: ImageProviderSetting.NewAPI,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.NewAPI().baseUrl)) }) {
                Text("重置", fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    ImageProviderStrategySection(provider, onEdit)
    ApiTokenListSection(provider, onEdit)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("启用此生图服务商")
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }
}

@Composable
private fun ImageProviderConfigureVolcengine(
    provider: ImageProviderSetting.Volcengine,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.Volcengine().baseUrl)) }) {
                Text("重置", fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    ImageProviderStrategySection(provider, onEdit)
    ApiTokenListSection(provider, onEdit)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("启用此生图服务商")
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }
}

@Composable
private fun ImageProviderConfigureWavespeed(
    provider: ImageProviderSetting.Wavespeed,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.Wavespeed().baseUrl)) }) {
                Text("重置", fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    ImageProviderStrategySection(provider, onEdit)
    ApiTokenListSection(provider, onEdit)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("启用此生图服务商")
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }
}

@Composable
private fun ImageProviderConfigureTokenRhythm(
    provider: ImageProviderSetting.TokenRhythm,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.TokenRhythm().baseUrl)) }) {
                Text("重置", fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    ImageProviderStrategySection(provider, onEdit)
    ApiTokenListSection(provider, onEdit)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("启用此生图服务商")
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }
}

/** Token 轮询策略选择：轮询 / 随机 / 失败切换。 */
@Composable
private fun ImageProviderStrategySection(
    provider: ImageProviderSetting,
    onEdit: (ImageProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Token 轮询策略", style = MaterialTheme.typography.titleSmall)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            KeyStrategy.entries.forEachIndexed { index, strategy ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = KeyStrategy.entries.size
                    ),
                    selected = provider.keyStrategy == strategy,
                    onClick = { onEdit(provider.withKeyStrategy(strategy)) },
                    label = {
                        Text(
                            when (strategy) {
                                KeyStrategy.ROUND_ROBIN -> "轮询"
                                KeyStrategy.RANDOM -> "随机"
                                KeyStrategy.FAILOVER -> "失败切换"
                            }
                        )
                    }
                )
            }
        }

        Text(
            when (provider.keyStrategy) {
                KeyStrategy.ROUND_ROBIN -> "按顺序循环使用所有 Token，尽量均衡分摊每个账号的额度。"
                KeyStrategy.RANDOM -> "每次请求随机挑选一个 Token。"
                KeyStrategy.FAILOVER -> "固定使用第一个可用 Token，仅遇到 401/403/429/422 才自动切换到下一个；其中 422 视为额度耗尽，该 Token 会被自动删除。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 多 Token 编辑器：每行一个，支持添加 / 删除 / 批量粘贴。 */
@Composable
private fun ApiTokenListSection(
    provider: ImageProviderSetting,
    onEdit: (ImageProviderSetting) -> Unit
) {
    var tokens by remember(provider.id) { mutableStateOf(provider.apiKeyTokens) }
    var passwordVisible by remember(provider.id) { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("API Tokens (${tokens.size})", style = MaterialTheme.typography.titleSmall)
            if (tokens.isNotEmpty()) {
                TextButton(
                    onClick = {
                        tokens = emptyList()
                        onEdit(provider.withApiKeyTokens(emptyList()))
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            "每个 Token 一行，可直接批量粘贴（空格/逗号/换行分隔）。多账号额度池，配合上方策略使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        tokens.forEachIndexed { index, token ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { raw ->
                        val updated = updateTokens(tokens, index, raw)
                        tokens = updated
                        onEdit(provider.withApiKeyTokens(updated))
                    },
                    label = { Text("Token ${index + 1}") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) HugeIcons.View else HugeIcons.ViewOff, null)
                        }
                    },
                )
                IconButton(
                    onClick = {
                        val updated = tokens.filterIndexed { i, _ -> i != index }
                        tokens = updated
                        onEdit(provider.withApiKeyTokens(updated))
                    }
                ) {
                    Icon(HugeIcons.Delete01, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        OutlinedButton(
            onClick = {
                val updated = tokens + ""
                tokens = updated
                onEdit(provider.withApiKeyTokens(updated))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("添加 Token")
        }
    }
}

/** 编辑单个 Token；若输入中出现换行/逗号（如批量粘贴），则按分隔符拆分重排整个列表。 */
private fun updateTokens(tokens: List<String>, index: Int, raw: String): List<String> {
    if (raw.contains('\n') || raw.contains(',') || raw.contains(' ')) {
        val buffer = buildString {
            tokens.take(index).forEach { append(it); append('\n') }
            append(raw)
            tokens.drop(index + 1).forEach { append('\n'); append(it) }
        }
        return buffer.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.distinct()
    }
    return tokens.toMutableList().apply { this[index] = raw }
}
