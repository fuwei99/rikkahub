package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.r2.R2AccountConfig
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM

/**
 * R2 账户管理（P3，plan §3.3 v1.1）：
 * - 启停开关只决定新上传去向（第一个 enabled 账户），读取按引用里的账户路由；
 * - 删除账户 / 更换密钥 → 二次确认 + 硬警告（所有指向该桶的引用失效）。
 */
@Composable
fun R2AccountsSection(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accounts = settings.r2Accounts
    val uploadTarget = accounts.firstOrNull { it.enabled && it.isConfigured }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var verifiedAccountIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var testingAccountId by remember { mutableStateOf<String?>(null) }
    var editingAccount by remember { mutableStateOf<R2AccountConfig?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<R2AccountConfig?>(null) }
    var deleteConfirmStep2 by remember { mutableStateOf(false) }
    var pendingEditConfirm by remember { mutableStateOf<R2AccountConfig?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.r2_accounts_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (uploadTarget != null) {
                stringResource(R.string.r2_upload_target, uploadTarget.alias.ifBlank { uploadTarget.bucket })
            } else {
                stringResource(R.string.r2_none)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        accounts.forEach { account ->
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.alias.ifBlank { account.bucket.ifBlank { "?" } },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = account.bucket,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = account.isConfigured && testingAccountId == null,
                        onClick = {
                            testingAccountId = account.id
                            scope.launch {
                                runCatching { vm.testR2Account(account) }
                                    .onSuccess {
                                        verifiedAccountIds = verifiedAccountIds + account.id
                                        toaster.show("R2 测试成功", type = ToastType.Success)
                                    }
                                    .onFailure { error ->
                                        toaster.show("R2 测试失败：${error.message ?: error}", type = ToastType.Error)
                                    }
                                testingAccountId = null
                            }
                        },
                    ) {
                        Text(if (testingAccountId == account.id) "测试中" else "测试")
                    }
                    TextButton(onClick = { editingAccount = account; editingIsNew = false }) {
                        Text(stringResource(R.string.r2_account_edit))
                    }
                    TextButton(onClick = { pendingDelete = account; deleteConfirmStep2 = false }) {
                        Text(stringResource(R.string.r2_account_delete))
                    }
                    Switch(
                        checked = account.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled && account.id !in verifiedAccountIds) {
                                toaster.show("请先测试 R2 连接，测试成功后才能开启", type = ToastType.Warning)
                                return@Switch
                            }
                            vm.updateR2Accounts(
                                accounts.map { if (it.id == account.id) it.copy(enabled = enabled) else it }
                            )
                        },
                    )
                }
            }
        }

        OutlinedButton(onClick = { editingAccount = R2AccountConfig(enabled = false); editingIsNew = true }) {
            Text(stringResource(R.string.r2_account_add))
        }
    }

    // ---- 新增 / 编辑 ----
    editingAccount?.let { account ->
        R2AccountEditDialog(
            initial = account,
            isNew = editingIsNew,
            onDismiss = { editingAccount = null },
            onSave = { edited ->
                editingAccount = null
                if (editingIsNew) {
                    vm.updateR2Accounts(accounts + edited.copy(enabled = false))
                } else {
                    // 修改既有账户（尤其密钥）也要二次确认；保存后需要重新测试才能开启。
                    pendingEditConfirm = edited.copy(enabled = false)
                }
            },
        )
    }

    // ---- 编辑二次确认 ----
    pendingEditConfirm?.let { edited ->
        AlertDialog(
            onDismissRequest = { pendingEditConfirm = null },
            title = { Text(stringResource(R.string.r2_account_edit)) },
            text = { Text(stringResource(R.string.r2_account_edit_warn)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateR2Accounts(accounts.map { if (it.id == edited.id) edited else it })
                    pendingEditConfirm = null
                }) {
                    Text(stringResource(R.string.r2_account_confirm_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEditConfirm = null }) {
                    Text(stringResource(R.string.r2_account_cancel))
                }
            },
        )
    }

    // ---- 删除二次确认（硬警告） ----
    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null; deleteConfirmStep2 = false },
            title = { Text(stringResource(R.string.r2_account_delete)) },
            text = {
                Text(
                    text = if (!deleteConfirmStep2) {
                        stringResource(R.string.r2_account_delete_warn1, account.alias.ifBlank { account.bucket })
                    } else {
                        stringResource(R.string.r2_account_delete_warn2)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!deleteConfirmStep2) {
                        deleteConfirmStep2 = true
                    } else {
                        vm.updateR2Accounts(accounts.filterNot { it.id == account.id })
                        pendingDelete = null
                        deleteConfirmStep2 = false
                    }
                }) {
                    Text(
                        text = stringResource(
                            if (!deleteConfirmStep2) R.string.r2_account_confirm_save else R.string.r2_account_delete_final
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null; deleteConfirmStep2 = false }) {
                    Text(stringResource(R.string.r2_account_cancel))
                }
            },
        )
    }
}

@Composable
private fun R2AccountEditDialog(
    initial: R2AccountConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (R2AccountConfig) -> Unit,
) {
    var alias by remember(initial.id) { mutableStateOf(initial.alias) }
    var accountId by remember(initial.id) { mutableStateOf(initial.accountId) }
    var accessKeyId by remember(initial.id) { mutableStateOf(initial.accessKeyId) }
    var secretAccessKey by remember(initial.id) { mutableStateOf(initial.secretAccessKey) }
    var bucket by remember(initial.id) { mutableStateOf(initial.bucket) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.r2_account_add else R.string.r2_account_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.r2_account_alias)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it.trim() },
                    label = { Text(stringResource(R.string.cloud_sync_account_id)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accessKeyId,
                    onValueChange = { accessKeyId = it.trim() },
                    label = { Text(stringResource(R.string.r2_account_access_key)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = secretAccessKey,
                    onValueChange = { secretAccessKey = it.trim() },
                    label = { Text(stringResource(R.string.r2_account_secret_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it.trim() },
                    label = { Text(stringResource(R.string.r2_account_bucket)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        alias = alias.trim(),
                        accountId = accountId,
                        accessKeyId = accessKeyId,
                        secretAccessKey = secretAccessKey,
                        bucket = bucket,
                    )
                )
            }) {
                Text(stringResource(R.string.r2_account_confirm_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.r2_account_cancel))
            }
        },
    )
}
