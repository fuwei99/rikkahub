package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SubagentJobManager"

enum class SubagentJobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED
}

class SubagentJob(
    val id: String = UUID.randomUUID().toString().take(8),
    val spec: SubagentSpec,
    @Volatile var status: SubagentJobStatus = SubagentJobStatus.QUEUED,
    @Volatile var steps: Int = 0,
    @Volatile var toolCalls: Int = 0,
    @Volatile var result: SubagentResult? = null,
    @Volatile var error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var coroutineJob: Job? = null,
)

class SubagentJobManager(
    private val runner: SubagentRunner,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, SubagentJob>()

    fun submitJob(spec: SubagentSpec): SubagentJob {
        val job = SubagentJob(spec = spec)
        jobs[job.id] = job

        val coroutineJob = scope.launch {
            job.status = SubagentJobStatus.RUNNING
            try {
                val subagentSpec = SubagentSpec(
                    task = spec.task,
                    context = spec.context,
                    tools = spec.tools,
                    maxSteps = spec.maxSteps,
                    timeout = spec.timeout,
                    settings = spec.settings,
                    model = spec.model,
                    assistant = spec.assistant,
                    workspaceCwd = spec.workspaceCwd,
                    systemPrompt = spec.systemPrompt,
                    processingStatus = spec.processingStatus,
                    onProgress = { s, tc ->
                        job.steps = s
                        job.toolCalls = tc
                    }
                )
                val res = runner.run(subagentSpec)
                job.result = res
                job.steps = res.steps
                job.toolCalls = res.toolCalls
                job.status = SubagentJobStatus.COMPLETED
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                job.status = SubagentJobStatus.TIMED_OUT
                job.error = "Job timed out after ${spec.timeout}"
                Log.w(TAG, "submitJob: job ${job.id} timed out")
            } catch (e: Exception) {
                job.status = SubagentJobStatus.FAILED
                job.error = e.message ?: "Unknown error"
                Log.e(TAG, "submitJob: job ${job.id} failed", e)
            }
        }
        job.coroutineJob = coroutineJob
        return job
    }

    fun getJob(id: String): SubagentJob? = jobs[id]

    fun cancelJob(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.status == SubagentJobStatus.RUNNING || job.status == SubagentJobStatus.QUEUED) {
            job.coroutineJob?.cancel()
            job.status = SubagentJobStatus.CANCELLED
            return true
        }
        return false
    }

    suspend fun waitJobs(ids: List<String>, timeoutSeconds: Int = 600): Map<String, SubagentJob> {
        val targetJobs = ids.mapNotNull { jobs[it] }
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val allDone = targetJobs.all {
                it.status == SubagentJobStatus.COMPLETED ||
                it.status == SubagentJobStatus.FAILED ||
                it.status == SubagentJobStatus.TIMED_OUT ||
                it.status == SubagentJobStatus.CANCELLED
            }
            if (allDone) break
            delay(500)
        }
        return targetJobs.associateBy { it.id }
    }
}
