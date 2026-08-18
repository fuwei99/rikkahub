package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions when you need clarification, additional information, or confirmation.
        Each question can optionally provide a list of suggested options for the user to choose from.
        The user may select an option or provide their own free-text answer for each question.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of suggested options for the user to choose from"
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    // 正常路径下这里永远不会被调用：needsApproval 恒 true，答案由 HITL 流以
    // ToolApprovalState.Answered 直接充当工具输出（GenerationHandler.resolve）。
    // 但绝不能再 error() 抛异常（2026-08-18）：一旦有任何路径把它当普通工具执行
    // （批准态、旧数据重放、并发分支），抛出的异常会把整条生成炸掉，
    // 定时任务侧还会判定为可重试错误 → 无限重试 + 反复弹窗，白烧 token。
    // 改为返回结构化 error，让模型自己看懂并继续决策。
    execute = {
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put(
                        "error",
                        "ask_user was not routed through the human-in-the-loop flow, " +
                            "so no answer was collected. Do not retry; proceed with your best " +
                            "judgement and state the assumption you made."
                    )
                }.toString()
            )
        )
    }
)
