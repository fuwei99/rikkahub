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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.KeyStrategy
import me.rerere.ai.provider.apiKeyTokens
import me.rerere.ai.provider.closeOnCodes
import me.rerere.ai.provider.disabledTokens
import me.rerere.ai.provider.keyStrategy
import me.rerere.ai.provider.retryCount
import me.rerere.ai.provider.retryIntervalSec
import me.rerere.ai.provider.tokenNames
import me.rerere.ai.provider.withApiKeyTokens
import me.rerere.ai.provider.withCloseOnCodes
import me.rerere.ai.provider.withDisabledTokens
import me.rerere.ai.provider.withKeyStrategy
import me.rerere.ai.provider.withRetryCount
import me.rerere.ai.provider.withRetryIntervalSec
import me.rerere.ai.provider.withTokenNames
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
                                    ImageProviderSetting.ComfyUI::class -> "ComfyUI"
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
            is ImageProviderSetting.ComfyUI -> ImageProviderConfigureComfyUI(provider, onEdit)
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
        is ImageProviderSetting.ComfyUI -> this.apiKey
    }
    val keyStrategy = this.keyStrategy
    val retryCount = this.retryCount
    val retryIntervalSec = this.retryIntervalSec
    val closeOnCodes = this.closeOnCodes
    val disabledTokens = this.disabledTokens
    val tokenNames = this.tokenNames
    val convertedBaseUrl = when (type) {
        ImageProviderSetting.OpenAI::class -> ImageProviderSetting.OpenAI().baseUrl
        ImageProviderSetting.NewAPI::class -> ImageProviderSetting.NewAPI().baseUrl
        ImageProviderSetting.Volcengine::class -> ImageProviderSetting.Volcengine().baseUrl
        ImageProviderSetting.Wavespeed::class -> ImageProviderSetting.Wavespeed().baseUrl
        ImageProviderSetting.TokenRhythm::class -> ImageProviderSetting.TokenRhythm().baseUrl
        ImageProviderSetting.ComfyUI::class -> ImageProviderSetting.ComfyUI().baseUrl
        else -> error("Unsupported type: $type")
    }

    return when (type) {
        ImageProviderSetting.OpenAI::class -> ImageProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy,
            retryCount = retryCount, retryIntervalSec = retryIntervalSec,
            closeOnCodes = closeOnCodes, disabledTokens = disabledTokens, tokenNames = tokenNames,
        )
        ImageProviderSetting.NewAPI::class -> ImageProviderSetting.NewAPI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy,
            retryCount = retryCount, retryIntervalSec = retryIntervalSec,
            closeOnCodes = closeOnCodes, disabledTokens = disabledTokens, tokenNames = tokenNames,
        )
        ImageProviderSetting.Volcengine::class -> ImageProviderSetting.Volcengine(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy,
            retryCount = retryCount, retryIntervalSec = retryIntervalSec,
            closeOnCodes = closeOnCodes, disabledTokens = disabledTokens, tokenNames = tokenNames,
        )
        ImageProviderSetting.Wavespeed::class -> ImageProviderSetting.Wavespeed(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy,
            retryCount = retryCount, retryIntervalSec = retryIntervalSec,
            closeOnCodes = closeOnCodes, disabledTokens = disabledTokens, tokenNames = tokenNames,
        )
        ImageProviderSetting.TokenRhythm::class -> ImageProviderSetting.TokenRhythm(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy,
            retryCount = retryCount, retryIntervalSec = retryIntervalSec,
            closeOnCodes = closeOnCodes, disabledTokens = disabledTokens, tokenNames = tokenNames,
        )
        ImageProviderSetting.ComfyUI::class -> ImageProviderSetting.ComfyUI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl, keyStrategy = keyStrategy,
            retryCount = retryCount, retryIntervalSec = retryIntervalSec,
            closeOnCodes = closeOnCodes, disabledTokens = disabledTokens, tokenNames = tokenNames,
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

/** Token 轮询策略 + 失败重试 + 报错即关闭码。 */
@Composable
private fun ImageProviderStrategySection(
    provider: ImageProviderSetting,
    onEdit: (ImageProviderSetting) -> Unit
) {
    TokenStrategySection(
        strategy = provider.keyStrategy,
        onEdit = { onEdit(provider.withKeyStrategy(it)) }
    )

    RetryPolicySection(
        retryCount = provider.retryCount,
        retryIntervalSec = provider.retryIntervalSec,
        onRetryCountChange = { onEdit(provider.withRetryCount(it)) },
        onRetryIntervalSecChange = { onEdit(provider.withRetryIntervalSec(it)) },
    )

    CloseCodesSection(
        closeOnCodes = provider.closeOnCodes,
        onChange = { onEdit(provider.withCloseOnCodes(it)) }
    )
}

/** 多 Token 编辑器：每行带开/关滑动开关，支持添加 / 删除 / 批量添加 / 命名。 */
@Composable
private fun ApiTokenListSection(
    provider: ImageProviderSetting,
    onEdit: (ImageProviderSetting) -> Unit
) {
    TokenPoolSection(
        tokens = provider.apiKeyTokens,
        disabledTokens = provider.disabledTokens,
        tokenNames = provider.tokenNames,
        providerId = provider.id.toString(),
        onChange = { keys, disabled, names ->
            onEdit(
                provider.withApiKeyTokens(keys)
                    .withDisabledTokens(disabled)
                    .withTokenNames(names)
            )
        },
    )
}

/** ComfyUI：无需 API Key，只需 Base URL（自建/ngrok 地址）+ 可选工作流模板。 */
@Composable
private fun ImageProviderConfigureComfyUI(
    provider: ImageProviderSetting.ComfyUI,
    onEdit: (ImageProviderSetting) -> Unit
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
        label = { Text("ComfyUI 地址") },
        supportingText = {
            Text("填写 ComfyUI 服务地址，如 https://xxx.ngrok-free.dev 或 http://127.0.0.1:8188")
        },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = provider.workflowTemplate,
        onValueChange = { onEdit(provider.copy(workflowTemplate = it)) },
        label = { Text("工作流模板 JSON（可选）") },
        supportingText = {
            Text("API 格式工作流，字符串字段可嵌 ¥%变量%(说明)¥ 占位符；留空使用内置 Anima 模板")
        },
        minLines = 6,
        maxLines = 12,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("使用内置 Anima 模板")
        OutlinedButton(onClick = { onEdit(provider.copy(workflowTemplate = "")) }) {
            Text("重置模板")
        }
    }

    OutlinedTextField(
        value = provider.imageTimeoutSec.toString(),
        onValueChange = { it.toIntOrNull()?.let { sec -> onEdit(provider.copy(imageTimeoutSec = sec)) } },
        label = { Text("生成超时（秒）") },
        modifier = Modifier.fillMaxWidth(),
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
