package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/**
 * 向量模型服务配置表单（OpenAI 兼容 /embeddings 端点）。
 * 火山方舟 Plan/免费、Fireworks、阿里百炼、智谱、OpenAI 等均为同一表单。
 */
@Composable
fun VectorProviderConfigure(
    provider: VectorProviderSetting.OpenAI,
    modifier: Modifier = Modifier,
    onEdit: (provider: VectorProviderSetting.OpenAI) -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
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
                IconButton(onClick = { onEdit(provider.copy(baseUrl = VectorProviderSetting.OpenAI().baseUrl)) }) {
                    Text("重置", fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.primary)
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
            Text("启用此向量服务")
            Switch(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copy(enabled = it)) }
            )
        }
    }
}
