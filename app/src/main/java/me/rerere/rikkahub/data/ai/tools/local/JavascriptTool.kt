package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val MIN_TIMEOUT_MS = 100L
private const val MAX_TIMEOUT_MS = 30_000L
private const val DEFAULT_TIMEOUT_MS = 5_000L

private val DESCRIPTION = """
    Run JavaScript in an embedded QuickJS engine (ES2020), returning the last expression's value like a REPL.
    - State: same `session_id` keeps globals across calls; declare with `var`/`function`/`globalThis.x`
      (top-level `let`/`const` do not persist). Omit it for a one-shot context.
    - Exact math: use the bundled decimal.js via `D(x)`, `dsum([...])`, `round(x, n)` instead of
      native float arithmetic or `toFixed`, which misround.
    - Sorting: always pass a comparator; `asc`, `desc`, `byKey('field')` are predefined.
    - `fetch(url, options)` is SYNCHRONOUS: never `await` it, call `.text()`/`.json()` directly.
      `btoa`/`atob` are available.
    - No event loop, no timers, no DOM/Node APIs - write synchronous code.
    - `console.*` output is returned in `logs`.
""".trimIndent()

internal fun buildJavascriptTool(sessionManager: JsSessionManager): Tool = Tool(
    name = "eval_javascript",
    description = DESCRIPTION,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "JavaScript code to execute.")
                })
                put("session_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Reuse the same id to keep state across calls; omit for a one-shot eval."
                    )
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Execution limit in ms, $MIN_TIMEOUT_MS-$MAX_TIMEOUT_MS. Default $DEFAULT_TIMEOUT_MS."
                    )
                })
            },
            required = listOf("code")
        )
    },
    execute = { params ->
        val obj = params.jsonObject
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (code.isNullOrBlank()) {
            listOf(UIMessagePart.Text(errorPayload("`code` is required and must not be empty")))
        } else {
            val sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val timeout = (obj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS)
                .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

            val outcome = sessionManager.evaluate(code, sessionId, timeout)
            val payload = buildJsonObject {
                sessionId?.let { put("session_id", JsonPrimitive(it)) }
                when (outcome) {
                    is JsEvalOutcome.Success -> {
                        putLogs(outcome.logs)
                        put("result", JsonPrimitive(outcome.result))
                    }

                    is JsEvalOutcome.Failure -> {
                        putLogs(outcome.logs)
                        put("error", JsonPrimitive(outcome.error))
                    }

                    is JsEvalOutcome.Timeout -> {
                        putLogs(outcome.logs)
                        put(
                            "error",
                            JsonPrimitive(
                                "Execution timed out after ${outcome.millis}ms and was abandoned. " +
                                    "State in this session was lost and the session has been reset, " +
                                    "so the same session_id is usable again from a clean slate. " +
                                    "Check for an unbounded loop, or raise timeout_ms."
                            )
                        )
                    }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    }
)

private fun kotlinx.serialization.json.JsonObjectBuilder.putLogs(logs: List<String>) {
    if (logs.isNotEmpty()) {
        put("logs", JsonPrimitive(logs.joinToString("\n").take(8_000)))
    }
}

private fun errorPayload(message: String): String =
    buildJsonObject { put("error", JsonPrimitive(message)) }.toString()
