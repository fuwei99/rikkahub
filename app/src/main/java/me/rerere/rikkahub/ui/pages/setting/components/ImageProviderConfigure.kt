package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.ai.provider.ImageProviderSetting
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
                                    ImageProviderSetting.Volcengine::class -> "火山方舟"
                                    ImageProviderSetting.Wavespeed::class -> "WaveSpeed"
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
            is ImageProviderSetting.Volcengine -> ImageProviderConfigureVolcengine(provider, onEdit)
            is ImageProviderSetting.Wavespeed -> ImageProviderConfigureWavespeed(provider, onEdit)
        }
    }
}

fun ImageProviderSetting.convertTo(type: KClass<out ImageProviderSetting>): ImageProviderSetting {
    if (this::class == type) return this

    val apiKey = when (this) {
        is ImageProviderSetting.OpenAI -> this.apiKey
        is ImageProviderSetting.Volcengine -> this.apiKey
        is ImageProviderSetting.Wavespeed -> this.apiKey
    }
    val convertedBaseUrl = when (type) {
        ImageProviderSetting.OpenAI::class -> ImageProviderSetting.OpenAI().baseUrl
        ImageProviderSetting.Volcengine::class -> ImageProviderSetting.Volcengine().baseUrl
        ImageProviderSetting.Wavespeed::class -> ImageProviderSetting.Wavespeed().baseUrl
        else -> error("Unsupported type: $type")
    }

    return when (type) {
        ImageProviderSetting.OpenAI::class -> ImageProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ImageProviderSetting.Volcengine::class -> ImageProviderSetting.Volcengine(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ImageProviderSetting.Wavespeed::class -> ImageProviderSetting.Wavespeed(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        else -> error("Unsupported type: $type")
    }
}

@Composable
private fun ImageProviderConfigureOpenAI(
    provider: ImageProviderSetting.OpenAI,
    onEdit: (provider: ImageProviderSetting) -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_configure_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.OpenAI().baseUrl)) }) {
                Text("重置", style = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it)) },
        label = { Text("API Key") },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(if (passwordVisible) HugeIcons.View else HugeIcons.ViewOff, null)
            }
        }
    )

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
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_configure_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.Volcengine().baseUrl)) }) {
                Text("重置", style = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it)) },
        label = { Text("API Key / Access Key ID") },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(if (passwordVisible) HugeIcons.View else HugeIcons.ViewOff, null)
            }
        }
    )

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
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_configure_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { onEdit(provider.copy(baseUrl = ImageProviderSetting.Wavespeed().baseUrl)) }) {
                Text("重置", style = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
            }
        }
    )

    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it)) },
        label = { Text("API Key") },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(if (passwordVisible) HugeIcons.View else HugeIcons.ViewOff, null)
            }
        }
    )

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
