package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SubagentJobManager"

enum class SubagentJobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED
}

data class SubagentToolTrace(
    val step: Int,
    val toolName: String,
    val argsPreview: String = "",
    val resultPreview: String? = null,
    val durationMs: Long? = null,
    val tokenUsage: TokenUsage? = null,
    val status: String = "started",
)

data class SubagentTraceState(
    val jobId: String,
    val taskBrief: String,
    val templateId: String? = null,
    val status: SubagentJobStatus = SubagentJobStatus.QUEUED,
    val steps: Int = 0,
    val maxSteps: Int = 0,
    val toolCalls: List<SubagentToolTrace> = emptyList(),
    val currentTool: String? = null,
    val tokenUsage: TokenUsage = TokenUsage(),
    val maxTotalTokens: Int = 0,
    val contextMessageSize: Int = 0,
    val summary: String? = null,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

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
    private val _traces = MutableStateFlow<Map<String, SubagentTraceState>>(emptyMap())
    val traces: StateFlow<Map<String, SubagentTraceState>> = _traces.asStateFlow()

    fun submitJob(spec: SubagentSpec, templateId: String? = null): SubagentJob {
        val job = SubagentJob(spec = spec)
        jobs[job.id] = job
        updateTrace(job.id) {
            SubagentTraceState(
                jobId = job.id,
                taskBrief = spec.task.take(160),
                templateId = templateId,
                status = SubagentJobStatus.QUEUED,
                maxSteps = spec.maxSteps,
                maxTotalTokens = spec.maxTotalTokens,
                contextMessageSize = spec.contextMessageSize,
                startedAt = job.createdAt,
            )
        }

        val coroutineJob = scope.launch {
            job.status = SubagentJobStatus.RUNNING
            updateTrace(job.id) { it.copy(status = SubagentJobStatus.RUNNING) }
            try {
                val res = runner.run(
                    spec.copy(
                        onProgress = { trace ->
                            job.steps = trace.steps
                            job.toolCalls = trace.toolCalls.size
                            updateTrace(job.id) {
                                trace.copy(
                                    jobId = job.id,
                                    templateId = templateId,
                                    status = SubagentJobStatus.RUNNING,
                                )
                            }
                            spec.onProgress(trace)
                        }
                    )
                )
                job.result = res
                job.steps = res.steps
                job.toolCalls = res.toolCalls
                job.status = SubagentJobStatus.COMPLETED
                updateTrace(job.id) {
                    it.copy(
                        status = SubagentJobStatus.COMPLETED,
                        steps = res.steps,
                        tokenUsage = res.tokenUsage,
                        summary = res.summary,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                job.status = SubagentJobStatus.TIMED_OUT
                job.error = "Job timed out after ${spec.timeout}"
                updateTrace(job.id) {
                    it.copy(status = SubagentJobStatus.TIMED_OUT, error = job.error, finishedAt = System.currentTimeMillis())
                }
                Log.w(TAG, "submitJob: job ${job.id} timed out")
            } catch (e: Exception) {
                job.status = SubagentJobStatus.FAILED
                job.error = e.message ?: "Unknown error"
                updateTrace(job.id) {
                    it.copy(status = SubagentJobStatus.FAILED, error = job.error, finishedAt = System.currentTimeMillis())
                }
                Log.e(TAG, "submitJob: job ${job.id} failed", e)
            }
        }
        job.coroutineJob = coroutineJob
        return job
    }

    fun getJob(id: String): SubagentJob? = jobs[id]

    fun getTrace(id: String): SubagentTraceState? = traces.value[id]

    fun cancelJob(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.status == SubagentJobStatus.RUNNING || job.status == SubagentJobStatus.QUEUED) {
            job.coroutineJob?.cancel()
            job.status = SubagentJobStatus.CANCELLED
            updateTrace(id) { it.copy(status = SubagentJobStatus.CANCELLED, finishedAt = System.currentTimeMillis()) }
            return true
        }
        return false
    }

    fun cancelAll(): Int {
        var count = 0
        jobs.keys.forEach { id -> if (cancelJob(id)) count++ }
        return count
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

    private fun updateTrace(id: String, transform: (SubagentTraceState) -> SubagentTraceState) {
        _traces.update { current ->
            val old = current[id] ?: SubagentTraceState(jobId = id, taskBrief = "")
            current + (id to transform(old))
        }
    }
}
