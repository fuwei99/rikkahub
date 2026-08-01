package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.js.injectFetch
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "JsSession"

/** Hard ceiling for a single evaluation. */
private const val DEFAULT_TIMEOUT_MS = 5_000L

/** Sessions idle for longer than this are reaped. */
private const val SESSION_IDLE_MS = 30 * 60 * 1000L

/** Upper bound on concurrently live sessions; least-recently-used is evicted. */
private const val MAX_SESSIONS = 8

/** QuickJS runtime memory ceiling, per session. */
private const val MEMORY_LIMIT_BYTES = 64 * 1024 * 1024

/** Serialized result payload is truncated past this length. */
private const val MAX_RESULT_CHARS = 20_000

sealed interface JsEvalOutcome {
    data class Success(val result: String, val logs: List<String>) : JsEvalOutcome
    data class Failure(val error: String, val logs: List<String>) : JsEvalOutcome
    data class Timeout(val millis: Long, val logs: List<String>) : JsEvalOutcome
}

/**
 * A QuickJS context pinned to its own thread.
 *
 * QuickJSContext.checkSameThread() forbids touching a context from any thread other than the
 * one that created it, so a session owns a single-threaded executor for its whole lifetime and
 * every evaluation is marshalled onto it.
 */
private class JsSession(
    val id: String,
    private val assets: android.content.res.AssetManager,
    private val httpClient: OkHttpClient?,
) {
    @Volatile
    var lastUsedAt: Long = System.currentTimeMillis()
        private set

    /**
     * Set when an evaluation overruns its deadline. The underlying thread may still be spinning
     * inside a runaway script, so the context can never be safely reused or destroyed again.
     */
    @Volatile
    var poisoned: Boolean = false
        private set

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    ) { runnable -> Thread(runnable, "js-session-$id").apply { isDaemon = true } }

    private var context: QuickJSContext? = null

    private fun ensureContext(): QuickJSContext {
        context?.let { return it }
        val ctx = QuickJSContext.create()
        ctx.setMemoryLimit(MEMORY_LIMIT_BYTES)
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) { logs.add("[LOG] ${info.orEmpty()}") }
            override fun info(info: String?) { logs.add("[INFO] ${info.orEmpty()}") }
            override fun warn(info: String?) { logs.add("[WARN] ${info.orEmpty()}") }
            override fun error(info: String?) { logs.add("[ERROR] ${info.orEmpty()}") }
        })
        httpClient?.let { ctx.injectFetch(it) }
        ctx.evaluate(readAsset("js/decimal.min.js"), "decimal.min.js")
        ctx.evaluate(readAsset("js/runtime.js"), "runtime.js")
        context = ctx
        return ctx
    }

    private fun readAsset(path: String): String =
        assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    suspend fun evaluate(code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsEvalOutcome {
        if (poisoned) {
            return JsEvalOutcome.Failure(
                "This session was abandoned after a previous timeout and cannot be reused. " +
                    "Start a new session_id.",
                emptyList(),
            )
        }
        lastUsedAt = System.currentTimeMillis()
        logs.clear()

        val future = executor.submit<String> {
            val ctx = ensureContext()
            // Hand the source over as a property instead of splicing it into a script string,
            // which sidesteps all quoting/escaping hazards.
            ctx.globalObject.setProperty("__rkCode", code)
            ctx.evaluate("__rkRun(__rkCode, $MAX_RESULT_CHARS)") as? String
                ?: """{"ok":false,"error":"runtime returned no value"}"""
        }

        val raw = try {
            withContext(Dispatchers.IO) { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        } catch (_: TimeoutException) {
            future.cancel(true)
            poisoned = true
            lastUsedAt = System.currentTimeMillis()
            return JsEvalOutcome.Timeout(timeoutMs, snapshotLogs())
        } catch (e: Exception) {
            val cause = e.cause ?: e
            return JsEvalOutcome.Failure(cause.message ?: cause.toString(), snapshotLogs())
        }

        val captured = snapshotLogs()
        return runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
            if (ok) {
                JsEvalOutcome.Success(obj["result"]?.jsonPrimitive?.contentOrNull ?: "null", captured)
            } else {
                JsEvalOutcome.Failure(
                    obj["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error",
                    captured,
                )
            }
        }.getOrElse { JsEvalOutcome.Success(raw, captured) }
    }

    private fun snapshotLogs(): List<String> = synchronized(logs) { logs.toList() }

    /**
     * Tears the session down. A poisoned session's thread is still executing user code, so its
     * context is deliberately leaked rather than destroyed from the wrong thread (which would
     * throw) or concurrently (which would crash native code). The thread is a daemon, so it
     * cannot keep the process alive.
     */
    fun close() {
        if (poisoned) {
            Log.w(TAG, "session $id abandoned; leaking context until process death")
            executor.shutdownNow()
            return
        }
        runCatching {
            executor.submit { runCatching { context?.destroy() }; context = null }
                .get(2, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "session $id close failed: ${it.message}") }
        executor.shutdown()
    }
}

/**
 * Owns the lifecycle of every JS session. Sessions are addressed by an opaque caller-supplied
 * id; the special id used for one-shot evaluations is created and destroyed in place so that
 * stateless calls never accumulate native memory.
 */
class JsSessionManager(
    private val appContext: Context,
    private val httpClient: OkHttpClient? = null,
) {
    private val sessions = linkedMapOf<String, JsSession>()

    suspend fun evaluate(code: String, sessionId: String?, timeoutMs: Long): JsEvalOutcome {
        if (sessionId.isNullOrBlank()) {
            // Stateless path: fresh context, always destroyed afterwards.
            val session = JsSession("oneshot-${System.nanoTime()}", appContext.assets, httpClient)
            return try {
                session.evaluate(code, timeoutMs)
            } finally {
                session.close()
            }
        }
        val session = acquire(sessionId)
        return session.evaluate(code, timeoutMs)
    }

    private fun acquire(sessionId: String): JsSession = synchronized(sessions) {
        reapIdle()
        sessions[sessionId]?.let { existing ->
            if (!existing.poisoned) {
                // refresh LRU position
                sessions.remove(sessionId)
                sessions[sessionId] = existing
                return existing
            }
            // a poisoned session is unusable; drop it and start over
            sessions.remove(sessionId)?.close()
        }
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull() ?: break
            sessions.remove(oldest)?.close()
            Log.i(TAG, "evicted LRU session $oldest")
        }
        JsSession(sessionId, appContext.assets, httpClient).also { sessions[sessionId] = it }
    }

    private fun reapIdle() {
        val cutoff = System.currentTimeMillis() - SESSION_IDLE_MS
        sessions.entries.filter { it.value.lastUsedAt < cutoff }.forEach { (key, value) ->
            sessions.remove(key)
            value.close()
            Log.i(TAG, "reaped idle session $key")
        }
    }

    fun reset(sessionId: String): Boolean = synchronized(sessions) {
        sessions.remove(sessionId)?.also { it.close() } != null
    }

    fun activeSessionIds(): List<String> = synchronized(sessions) { sessions.keys.toList() }

    fun closeAll() = synchronized(sessions) {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
