package me.rerere.rikkahub.ui.pages.extensions.packages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ToolPackage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Package Manager（扩展 → 工具包）。
 *
 * 职责：编排哪个工具进哪个包。**不管连接**（那是设置 → MCP 页的事）。
 * 见 plan §2.7。
 */
@Composable
fun ToolPackagesPage() {
    val vm: ToolPackagesVM = koinViewModel()
    val packages by vm.packages.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var editing by remember { mutableStateOf<ToolPackage?>(null) }
    var creating by remember { mutableStateOf(false) }
    val allTools = remember { vm.availableTools() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tool_packages_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.tool_packages_new))
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.tool_packages_page_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (packages.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tool_packages_page_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(packages, key = { it.id }) { pkg ->
                PackageRow(
                    pkg = pkg,
                    onEdit = { editing = pkg },
                    onToggle = { vm.setEnabled(pkg.id, it) },
                )
            }
        }
    }

    val target = editing ?: if (creating) ToolPackage(id = "", name = "") else null
    if (target != null) {
        ToolPackageEditor(
            initial = target,
            isNew = creating,
            allTools = allTools,
            onDismiss = {
                editing = null
                creating = false
            },
            onSave = {
                vm.upsert(it)
                editing = null
                creating = false
            },
            onDelete = {
                vm.delete(it.id)
                editing = null
                creating = false
            },
        )
    }
}

@Composable
private fun PackageRow(
    pkg: ToolPackage,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Puzzle, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pkg.name.ifBlank { pkg.id },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${pkg.id} · ${pkg.tools.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = pkg.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ToolPackageEditor(
    initial: ToolPackage,
    isNew: Boolean,
    allTools: List<ToolOption>,
    onDismiss: () -> Unit,
    onSave: (ToolPackage) -> Unit,
    onDelete: (ToolPackage) -> Unit,
) {
    var id by remember { mutableStateOf(initial.id) }
    var name by remember { mutableStateOf(initial.name) }
    var query by remember { mutableStateOf("") }
    val selected = remember {
        mutableStateListOf<String>().apply { addAll(initial.tools) }
    }

    val filtered = remember(query, allTools) {
        if (query.isBlank()) {
            allTools
        } else {
            allTools.filter {
                it.id.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) {
                    stringResource(R.string.tool_packages_new)
                } else {
                    initial.name.ifBlank { initial.id }
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    enabled = isNew,
                    label = { Text(stringResource(R.string.tool_packages_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tool_packages_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.tool_packages_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tool_packages_tools, selected.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                filtered.forEach { opt ->
                    val checked = selected.contains(opt.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) selected.remove(opt.id) else selected.add(opt.id)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                if (checked) selected.remove(opt.id) else selected.add(opt.id)
                            },
                        )
                        Column {
                            Text(
                                text = opt.id,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = opt.group,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = id.isNotBlank(),
                onClick = {
                    onSave(
                        ToolPackage(
                            id = id.trim(),
                            name = name.trim(),
                            enabled = initial.enabled,
                            tools = selected.toList(),
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.tool_packages_save))
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text(stringResource(R.string.tool_packages_delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.tool_packages_cancel))
                }
            }
        },
    )
}
