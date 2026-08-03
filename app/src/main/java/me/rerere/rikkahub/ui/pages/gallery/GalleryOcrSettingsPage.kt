package me.rerere.rikkahub.ui.pages.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 相册 OCR 设置页：OCR 模型 / 思考强度 / 提示词 / 标签白名单。
 *
 * 与「模型设置」(SettingModelPage / SettingModelPromptPage) 共用同一份
 * Settings(DataStore)；标签白名单跳转到 GalleryTagSettingPage 的同一列表，
 * 两处改一处生效。全部字段随 D1 settings bundle 整包同步。
 */
@Composable
fun GalleryOcrSettingsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.gallery_ocr_settings_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OcrModelItem(settings = settings, vm = vm)
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.assistant_page_thinking_budget)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.gallery_ocr_thinking_desc)) },
                        trailingContent = {
                            ReasoningButton(
                                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.ocrThinkingBudget),
                                onUpdateReasoningLevel = {
                                    vm.updateSettings(settings.copy(ocrThinkingBudget = it.budgetTokens))
                                },
                            )
                        },
                    )
                }
            }
            item {
                OcrPromptItem(settings = settings, vm = vm)
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.gallery_ocr_tags_title)) }) {
                    item(
                        onClick = { navController.navigate(Screen.GalleryTags) },
                        headlineContent = { Text(stringResource(R.string.gallery_ocr_tags_title)) },
                        supportingContent = { Text(stringResource(R.string.gallery_ocr_tags_desc)) },
                        trailingContent = {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OcrModelItem(settings: Settings, vm: SettingVM) {
    val title = stringResource(R.string.gallery_ocr_model_title)
    val state = rememberModelListState(
        modelId = settings.ocrModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        }
        Text(
            text = stringResource(R.string.gallery_ocr_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) })
}

@Composable
private fun OcrPromptItem(settings: Settings, vm: SettingVM) {
    var showEditor by remember { mutableStateOf(false) }

    CardGroup(title = { Text(stringResource(R.string.gallery_ocr_prompt_title)) }) {
        item(
            onClick = { showEditor = true },
            headlineContent = { Text(stringResource(R.string.gallery_ocr_prompt_title)) },
            trailingContent = {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }

    if (showEditor) {
        ModalBottomSheet(onDismissRequest = { showEditor = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.gallery_ocr_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.gallery_ocr_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.ocrPrompt,
                    onValueChange = { vm.updateSettings(settings.copy(ocrPrompt = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                )
                TextButton(onClick = { vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT)) }) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}
