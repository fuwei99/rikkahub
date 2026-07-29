package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.workspace.WorkspaceShellStatus
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.math.absoluteValue

private const val TAG = "WorkspaceScheduledProcessManager"
private const val CONFIG_PATH = ".rikkahub/scheduled_processes.json"
private const val NOTIFICATION_ID_RUNNING = 0x525350 // RSP
private const val NOTIFICATION_ID_FAILED = 0x525351

@Serializable
data class WorkspaceScheduledProcessesConfig(
    val version: Int = 1,
    val processes: List<WorkspaceScheduledProcessConfig> = emptyList(),
)

@Serializable
data class WorkspaceScheduledProcessConfig(
    val id: String,
    val name: String = id,
    val enabled: Boolean = true,
    val command: String,
    val cwd: String = "",
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val startMinutes: Int = 0,
    val endMinutes: Int = 24 * 60,
    val restartIfMissing: Boolean = true,
    val maxConsecutiveStartFailures: Int = 3,
)

private data class LocalProcessState(
    val failures: Int = 0,
    val blocked: Boolean = false,
    val lastError: String? = null,
)

class WorkspaceScheduledProcessManager(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val localStates = mutableMapOf<String, LocalProcessState>()

    suspend fun reconcileAll() = withContext(Dispatchers.IO) {
        val runningNames = mutableListOf<String>()
        workspaceRepository.getAll().forEach { workspace ->
            if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return@forEach
            val config = readConfig(workspace.id) ?: return@forEach
            config.processes.forEach { process ->
                val key = localKey(workspace.id, process.id)
                val runtimeId = runtimeProcessId(process.id)
                val background = workspaceRepository.getBackgroundProcess(workspace.id, runtimeId)
                val shouldRun = process.enabled && process.isInWindow()
                if (!shouldRun) {
                    if (background?.isAlive == true) {
                        runCatching { background.kill() }
                        workspaceRepository.removeBackgroundProcess(runtimeId)
                    }
                    return@forEach
                }
                if (background?.isAlive == true) {
                    runningNames += process.name.ifBlank { process.id }
                    localStates[key] = localStates[key].orEmpty().copy(failures = 0, blocked = false)
                    return@forEach
                }
                if (!process.restartIfMissing) return@forEach
                val state = localStates[key].orEmpty()
                if (state.blocked) return@forEach
                runCatching {
                    workspaceRepository.startBackgroundCommand(
                        id = workspace.id,
                        processId = runtimeId,
                        command = process.command,
                        cwd = process.cwd.toWorkspaceRelativeCwd(),
                    )
                }.onSuccess {
                    localStates[key] = LocalProcessState()
                    runningNames += process.name.ifBlank { process.id }
                    Log.i(TAG, "started scheduled process ${process.id} in workspace ${workspace.id}")
                }.onFailure { error ->
                    val failures = state.failures + 1
                    val blocked = failures >= process.maxConsecutiveStartFailures.coerceAtLeast(1)
                    localStates[key] = LocalProcessState(
                        failures = failures,
                        blocked = blocked,
                        lastError = error.message ?: error.toString(),
                    )
                    Log.e(TAG, "failed to start scheduled process ${process.id} ($failures)", error)
                    if (blocked) {
                        notifyFailure(process, error, failures)
                    }
                }
            }
        }
        notifyRunning(runningNames.distinct())
    }

    private suspend fun readConfig(workspaceId: String): WorkspaceScheduledProcessesConfig? {
        val raw = runCatching { workspaceRepository.readText(workspaceId, CONFIG_PATH) }.getOrNull()
            ?: return null
        return runCatching {
            me.rerere.rikkahub.utils.JsonInstant.decodeFromString<WorkspaceScheduledProcessesConfig>(raw)
        }.onFailure {
            Log.e(TAG, "failed to parse $CONFIG_PATH for workspace $workspaceId", it)
        }.getOrNull()
    }

    private fun notifyRunning(names: List<String>) {
        if (names.isEmpty()) {
            context.cancelNotification(NOTIFICATION_ID_RUNNING)
            return
        }
        context.sendNotification(WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID_RUNNING) {
            title = "Workspace processes running"
            content = names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_SERVICE
            useBigTextStyle = true
        }
    }

    private fun notifyFailure(process: WorkspaceScheduledProcessConfig, error: Throwable, failures: Int) {
        context.sendNotification(WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID_FAILED + process.id.hashCode().absoluteValue % 10000) {
            title = "Workspace process failed"
            content = "${process.name.ifBlank { process.id }} failed to start $failures times: ${error.message ?: error}"
            category = NotificationCompat.CATEGORY_ERROR
            useBigTextStyle = true
            autoCancel = true
        }
    }

    private fun WorkspaceScheduledProcessConfig.isInWindow(now: ZonedDateTime = ZonedDateTime.now(zoneId())): Boolean {
        val dayValue = now.dayOfWeek.value
        if (daysOfWeek.isNotEmpty() && dayValue !in daysOfWeek) return false
        val minute = now.toLocalTime().toSecondOfDay() / 60
        val start = startMinutes.coerceIn(0, 24 * 60)
        val end = endMinutes.coerceIn(0, 24 * 60)
        if (start == 0 && end == 24 * 60) return true
        return if (start <= end) minute in start until end else minute >= start || minute < end
    }

    private fun WorkspaceScheduledProcessConfig.zoneId(): ZoneId = ZoneId.systemDefault()

    private fun String.toWorkspaceRelativeCwd(): String =
        replace('\\', '/')
            .removePrefix("/workspace/")
            .removePrefix("/workspace")
            .trim('/')

    private fun runtimeProcessId(processId: String): String =
        "scheduled_" + processId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)

    private fun localKey(workspaceId: String, processId: String): String = "$workspaceId/$processId"

    private fun LocalProcessState?.orEmpty(): LocalProcessState = this ?: LocalProcessState()
}
