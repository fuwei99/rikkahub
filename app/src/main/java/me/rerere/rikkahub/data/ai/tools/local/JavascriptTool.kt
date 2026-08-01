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
    Execute JavaScript in an embedded QuickJS engine (ES2020) and return the value of the last
    expression, like a REPL.

    State: pass the same `session_id` across calls to keep variables, functions and imported data
    alive between them; declare with `var`/`function`/`globalThis.x` to persist (top-level `let`
    and `const` do not survive across calls). Omit `session_id` for a throwaway one-shot context.

    Numbers: this engine uses IEEE-754 doubles, so `0.1+0.2` is `0.30000000000000004` and integers
    above 2^53 lose precision silently. `toFixed` also misrounds (`1.005.toFixed(2)` is "1.00").
    For money and any exact arithmetic use the bundled decimal.js: `D('0.1').plus('0.2')` gives
    exactly 0.3, and `dsum([...])` totals a list. Use `round(x, n)` for correct half-up rounding.

    Sorting: `Array#sort` compares as strings by default, so always pass a comparator for numbers
    (`asc`, `desc`, `byKey('field')` are predefined).

    Also available: `fetch(url, options)` which returns a Response object SYNCHRONOUSLY (never
    `await` it; call `.text()` or `.json()` directly), plus `btoa`/`atob`. There is no event loop,
    so timers do not exist and promises never settle - write synchronous code. No DOM, no Node APIs.

    Console output is captured and returned in `logs`. Execution is capped by `timeout_ms`.
""".trimIndent().replace("\n", " ").replace(Regex(" {2,}"), " ")

internal fun buildJavascriptTool(sessionManager: JsSessionManager): Tool = Tool(
    name = "eval_javascript",
    description = DESCRIPTION,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
                put("session_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional. Reuse the same id to keep state across calls. " +
                            "Omit for a stateless one-shot evaluation."
                    )
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Optional execution limit, $MIN_TIMEOUT_MS-$MAX_TIMEOUT_MS, " +
                            "default $DEFAULT_TIMEOUT_MS."
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
                                    "Any state in this session is lost; use a new session_id. " +
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
