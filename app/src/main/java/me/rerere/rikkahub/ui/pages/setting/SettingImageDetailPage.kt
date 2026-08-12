package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_IMAGE_PROVIDERS
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.setting.components.ImageProviderConfigure
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun SettingImageDetailPage(
    providerId: String,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider = remember(settings.imageProviders, providerId) {
        settings.imageProviders.find { it.id.toString() == providerId }
    } ?: return
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val coroutineScope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除生图服务商") },
            text = { Text("您确定要删除生图服务商 \"${provider.name}\" 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        vm.updateSettings(
                            settings.copy(
                                imageProviders = settings.imageProviders.filter { it.id != provider.id }
                            )
                        )
                        navController.popBackStack()
                        toaster.show("服务商已删除", type = ToastType.Success)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val pagerState = rememberPagerState(pageCount = { 2 })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.name) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(HugeIcons.Delete01, "Delete")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    icon = { Icon(HugeIcons.Tools, null) },
                    label = { Text("配置") }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    icon = { Icon(HugeIcons.Package01, null) },
                    label = { Text("模型列表") }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ImageProviderConfigure(
                            provider = provider,
                            onEdit = { updated ->
                                vm.updateSettings(
                                    settings.copy(
                                        imageProviders = settings.imageProviders.map {
                                            if (it.id == provider.id) updated else it
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
                1 -> {
                    ImageModelListSection(
                        provider = provider,
                        onEditProvider = { updated ->
                            vm.updateSettings(
                                settings.copy(
                                    imageProviders = settings.imageProviders.map {
                                        if (it.id == provider.id) updated else it
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

private fun presetImageModels(provider: ImageProviderSetting): List<Model> = when (provider) {
    is ImageProviderSetting.Volcengine -> DEFAULT_IMAGE_PROVIDERS
        .filterIsInstance<ImageProviderSetting.Volcengine>()
        .firstOrNull()
        ?.models
        .orEmpty()
    is ImageProviderSetting.Wavespeed -> DEFAULT_IMAGE_PROVIDERS
        .filterIsInstance<ImageProviderSetting.Wavespeed>()
        .firstOrNull()
        ?.models
        .orEmpty()
    is ImageProviderSetting.TokenRhythm -> DEFAULT_IMAGE_PROVIDERS
        .filterIsInstance<ImageProviderSetting.TokenRhythm>()
        .firstOrNull()
        ?.models
        .orEmpty()
    is ImageProviderSetting.ComfyUI -> DEFAULT_IMAGE_PROVIDERS
        .filterIsInstance<ImageProviderSetting.ComfyUI>()
        .firstOrNull()
        ?.models
        .orEmpty()
    else -> emptyList()
}

@Composable
private fun ImageModelListSection(
    provider: ImageProviderSetting,
    onEditProvider: (ImageProviderSetting) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onEditProvider(provider.moveModel(from.index, to.index))
    }

    var editingModel by remember { mutableStateOf<Model?>(null) }
    var showAddModelSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("模型列表 (${provider.models.size})", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { showAddModelSheet = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("添加模型")
            }
        }

        val presetModels = remember(provider) {
            presetImageModels(provider).filter { preset -> provider.models.none { it.modelId == preset.modelId } }
        }
        if (presetModels.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("预置模型", style = MaterialTheme.typography.titleSmall)
                    Text("点击即可添加官方预置模型；添加后仍可在模型编辑器中修改能力和参数。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        presetModels.forEach { preset ->
                            Button(
                                onClick = { onEditProvider(provider.addModel(preset)) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(HugeIcons.Add01, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(preset.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(provider.models, key = { it.id }) { model ->
                ReorderableItem(
                    state = reorderableState,
                    key = model.id,
                ) { isDragging ->
                    Card(
                        onClick = { editingModel = model },
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(if (isDragging) 0.95f else 1f),
                        colors = CardDefaults.cardColors(
                            containerColor = CustomColors.listItemColors.containerColor
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AutoAIIcon(name = model.displayName, modifier = Modifier.size(36.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    model.modelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val haptic = LocalHapticFeedback.current
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = "长按拖动排序",
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                ),
                            )
                            IconButton(
                                onClick = {
                                    onEditProvider(provider.delModel(model))
                                }
                            ) {
                                Icon(HugeIcons.Delete01, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddModelSheet) {
        ImageModelEditor(
            initialModel = Model(id = Uuid.random(), displayName = "", modelId = "", type = ModelType.IMAGE),
            isWaveSpeed = provider is ImageProviderSetting.Wavespeed,
            isOpenAICompatible = provider is ImageProviderSetting.OpenAI || provider is ImageProviderSetting.NewAPI,
            onSave = { model ->
                onEditProvider(provider.addModel(model))
                showAddModelSheet = false
            },
            onDismiss = { showAddModelSheet = false },
        )
    }

    editingModel?.let { model ->
        ImageModelEditor(
            initialModel = model,
            isWaveSpeed = provider is ImageProviderSetting.Wavespeed,
            isOpenAICompatible = provider is ImageProviderSetting.OpenAI || provider is ImageProviderSetting.NewAPI,
            onSave = { updated ->
                onEditProvider(provider.editModel(updated))
                editingModel = null
            },
            onDismiss = { editingModel = null },
        )
    }

}
