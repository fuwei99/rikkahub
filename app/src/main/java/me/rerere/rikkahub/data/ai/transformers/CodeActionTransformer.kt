package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ToolCallingStrategy
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private val TOOL_CALLS_BLOCK_REGEX = Regex(
    """<(?:code_calls|tool_calls)>([\s\S]*?)(?:</(?:code_calls|tool_calls)>|$)""",
    RegexOption.IGNORE_CASE
)

private val INVOKE_REGEX = Regex(
    """<invoke\s+name=["']([^"']+)["']\s*>(.*?)(?:</invoke>|(?=</(?:code_calls|tool_calls)>)|(?=<invoke)|$)""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)

private val PARAM_REGEX = Regex(
    """<parameter\s+name=["']([^"']+)["'](?:\s+string=["'](true|false)["'])?\s*>(.*?)(?:</parameter>|(?=</invoke>)|(?=</(?:code_calls|tool_calls)>)|(?=<parameter)|$)""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)

private val jsonParser = Json { ignoreUnknownKeys = true }

object CodeActionTransformer : InputMessageTransformer, OutputMessageTransformer {

    private val targetCodeActionTools = setOf(
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_apply_patch"
    )

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val strategy = ctx.model.toolCallingStrategy
        if (strategy != ToolCallingStrategy.CODE_ACTION && strategy != ToolCallingStrategy.CUSTOM_PROTOCOL) {
            return messages
        }

        val resultMessages = mutableListOf<UIMessage>()

        for (message in messages) {
            if (message.role == MessageRole.ASSISTANT && message.getTools().isNotEmpty()) {
                val toolsToConvert = message.getTools().filter { tool ->
                    if (strategy == ToolCallingStrategy.CUSTOM_PROTOCOL) {
                        true
                    } else {
                        tool.toolName in targetCodeActionTools
                    }
                }

                if (toolsToConvert.isEmpty()) {
                    resultMessages.add(message)
                    continue
                }

                // 将转化为 XML 协议文本
                val xmlCalls = buildString {
                    val rootTag = if (strategy == ToolCallingStrategy.CODE_ACTION) "code_calls" else "tool_calls"
                    appendLine("<$rootTag>")
                    for (tool in toolsToConvert) {
                        appendLine("  <invoke name=\"${tool.toolName}\">")
                        val argsJson = tool.inputAsJson()
                        if (argsJson is JsonObject) {
                            for ((key, value) in argsJson) {
                                if (value is JsonPrimitive && value.isString) {
                                    appendLine("    <parameter name=\"$key\" string=\"true\">${value.content}</parameter>")
                                } else {
                                    appendLine("    <parameter name=\"$key\" string=\"false\">${value}</parameter>")
                                }
                            }
                        }
                        appendLine("  </invoke>")
                    }
                    append("</$rootTag>")
                }

                val remainingParts = message.parts.filterNot { it in toolsToConvert }.toMutableList()
                val existingTextIdx = remainingParts.indexOfFirst { it is UIMessagePart.Text }
                if (existingTextIdx >= 0) {
                    val textPart = remainingParts[existingTextIdx] as UIMessagePart.Text
                    val newText = if (textPart.text.isBlank()) xmlCalls else "${textPart.text}\n\n$xmlCalls"
                    remainingParts[existingTextIdx] = textPart.copy(text = newText)
                } else {
                    remainingParts.add(0, UIMessagePart.Text(xmlCalls))
                }

                resultMessages.add(message.copy(parts = remainingParts))

                // 对已执行的 tool 转换输出为 user 消息，保证上下文完整
                for (tool in toolsToConvert) {
                    if (tool.isExecuted) {
                        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                        val resultUserMsg = UIMessage.user("[Tool Output for ${tool.toolName} (${tool.toolCallId})]:\n$outputText")
                        resultMessages.add(resultUserMsg)
                    }
                }
            } else {
                resultMessages.add(message)
            }
        }

        return resultMessages
    }

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val strategy = ctx.model.toolCallingStrategy
        if (strategy != ToolCallingStrategy.CODE_ACTION && strategy != ToolCallingStrategy.CUSTOM_PROTOCOL) {
            return messages
        }
        return processMessages(messages, isFinal = false)
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val strategy = ctx.model.toolCallingStrategy
        if (strategy != ToolCallingStrategy.CODE_ACTION && strategy != ToolCallingStrategy.CUSTOM_PROTOCOL) {
            return messages
        }
        return processMessages(messages, isFinal = true)
    }

    private fun processMessages(
        messages: List<UIMessage>,
        isFinal: Boolean
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                val newParts = mutableListOf<UIMessagePart>()
                var hasToolExtracted = false

                message.parts.forEach { part ->
                    if (part is UIMessagePart.Text && containsCodeActionXml(part.text)) {
                        val (cleanText, extractedTools) = extractToolsFromText(part.text, isFinal)
                        if (extractedTools.isNotEmpty()) {
                            hasToolExtracted = true
                            if (cleanText.isNotBlank()) {
                                newParts.add(part.copy(text = cleanText))
                            }
                            newParts.addAll(extractedTools)
                        } else {
                            newParts.add(part)
                        }
                    } else {
                        newParts.add(part)
                    }
                }

                if (hasToolExtracted) {
                    message.copy(parts = newParts)
                } else {
                    message
                }
            } else {
                message
            }
        }
    }

    private fun containsCodeActionXml(text: String): Boolean {
        return text.contains("<code_calls>", ignoreCase = true) ||
                text.contains("<tool_calls>", ignoreCase = true) ||
                text.contains("<invoke name=", ignoreCase = true)
    }

    private fun extractToolsFromText(
        text: String,
        isFinal: Boolean
    ): Pair<String, List<UIMessagePart.Tool>> {
        val tools = mutableListOf<UIMessagePart.Tool>()
        var remainingText = text

        val matches = TOOL_CALLS_BLOCK_REGEX.findAll(text).toList()

        if (matches.isNotEmpty()) {
            for (match in matches) {
                val blockContent = match.groupValues[1]
                val blockFullText = match.value

                val parsedTools = parseInvokes(blockContent)
                if (parsedTools.isNotEmpty()) {
                    tools.addAll(parsedTools)
                    remainingText = remainingText.replace(blockFullText, "")
                }
            }
        } else {
            val parsedTools = parseInvokes(text)
            if (parsedTools.isNotEmpty()) {
                tools.addAll(parsedTools)
                for (invMatch in INVOKE_REGEX.findAll(text)) {
                    remainingText = remainingText.replace(invMatch.value, "")
                }
            }
        }

        return Pair(remainingText.trim(), tools)
    }

    private fun parseInvokes(content: String): List<UIMessagePart.Tool> {
        val tools = mutableListOf<UIMessagePart.Tool>()
        val invokeMatches = INVOKE_REGEX.findAll(content)

        for (invMatch in invokeMatches) {
            val toolName = invMatch.groupValues[1].trim()
            val invContent = invMatch.groupValues[2]

            if (toolName.isBlank()) continue

            val argsMap = mutableMapOf<String, JsonElement>()
            val paramMatches = PARAM_REGEX.findAll(invContent)

            for (pMatch in paramMatches) {
                val pName = pMatch.groupValues[1].trim()
                val isStringAttr = pMatch.groupValues[2]
                val pVal = pMatch.groupValues[3].trim()

                if (pName.isBlank()) continue

                val isString = if (isStringAttr.isNotBlank()) {
                    isStringAttr.equals("true", ignoreCase = true)
                } else {
                    true
                }

                if (isString) {
                    argsMap[pName] = JsonPrimitive(pVal)
                } else {
                    val parsedElement = runCatching {
                        jsonParser.parseToJsonElement(pVal)
                    }.getOrElse {
                        JsonPrimitive(pVal)
                    }
                    argsMap[pName] = parsedElement
                }
            }

            val inputJsonObject = JsonObject(argsMap)
            val toolCallId = "call_${Uuid.random()}"

            tools.add(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = inputJsonObject.toString(),
                    output = emptyList(),
                    approvalState = ToolApprovalState.Auto
                )
            )
        }

        return tools
    }
}
