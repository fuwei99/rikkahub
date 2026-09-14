package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    val context = LocalContext.current
    // 直接编辑 JSON：对象 {"Name":"Value"} 或数组 [{"name":..,"value":..}]。
    // 存储仍是 List<CustomHeader>，与后端序列化格式保持一致。
    var headerJson by remember(headers) { mutableStateOf(encodeHeadersAsJson(headers)) }
    var jsonParseError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = headerJson,
            onValueChange = { newString ->
                headerJson = newString
                try {
                    val parsed = parseHeaderJson(newString)
                    onUpdate(parsed)
                    jsonParseError = null
                } catch (e: Exception) {
                    jsonParseError = context.getString(
                        R.string.assistant_page_invalid_json,
                        e.message?.take(100) ?: ""
                    )
                }
            },
            placeholder = { Text("{\"User-Agent\": \"claude-code/0.4.0\"}") },
            modifier = Modifier.fillMaxWidth(),
            isError = jsonParseError != null,
            supportingText = {
                if (jsonParseError != null) {
                    Text(jsonParseError!!)
                }
            },
            minLines = 3,
            maxLines = 10,
            visualTransformation = HighlightCodeVisualTransformation(
                language = "json",
                highlighter = LocalHighlighter.current,
                darkMode = LocalDarkMode.current
            ),
            textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
        )
    }
}

/** List<CustomHeader> → 单行 JSON 对象文本，便于直接编辑。 */
private fun encodeHeadersAsJson(headers: List<CustomHeader>): String {
    if (headers.isEmpty()) return ""
    val obj = JsonObject(
        headers.filter { it.name.isNotBlank() }
            .associate { it.name to JsonPrimitive(it.value) }
    )
    return jsonCompact.encodeToString(JsonObject.serializer(), obj)
}

/** 接受 JSON 对象 {"Name":"Value"} 或数组 [{"name":..,"value":..}]。 */
private fun parseHeaderJson(text: String): List<CustomHeader> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    return when (val element = jsonLenient.parseToJsonElement(trimmed)) {
        is JsonObject -> element.entries.map { (name, value) ->
            CustomHeader(name, (value as? JsonPrimitive)?.content ?: value.toString())
        }

        is JsonArray -> element.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            CustomHeader(
                (obj["name"] as? JsonPrimitive)?.content ?: "",
                (obj["value"] as? JsonPrimitive)?.content ?: "",
            )
        }

        else -> throw IllegalArgumentException("expect JSON object or array")
    }
}

private val jsonCompact = Json { prettyPrint = false }

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: (List<CustomBody>) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        customBodies.forEachIndexed { index, body ->
            var bodyKey by remember(body.key) { mutableStateOf(body.key) }
            var bodyValueString by remember(body.value) {
                mutableStateOf(jsonLenient.encodeToString(JsonElement.serializer(), body.value))
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = bodyKey,
                                onValueChange = {
                                    bodyKey = it
                                    val updatedBodies = customBodies.toMutableList()
                                    updatedBodies[index] = updatedBodies[index].copy(key = it.trim())
                                    onUpdate(updatedBodies)
                                },
                                label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = bodyValueString,
                                onValueChange = { newString ->
                                    bodyValueString = newString
                                    try {
                                        val newJsonValue = jsonLenient.parseToJsonElement(newString)
                                        val updatedBodies = customBodies.toMutableList()
                                        updatedBodies[index] =
                                            updatedBodies[index].copy(value = newJsonValue)
                                        onUpdate(updatedBodies)
                                        jsonParseError = null
                                    } catch (e: Exception) {
                                        jsonParseError =
                                            context.getString(
                                                R.string.assistant_page_invalid_json,
                                                e.message?.take(100) ?: ""
                                            )
                                    }
                                },
                                label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = jsonParseError != null,
                                supportingText = {
                                    if (jsonParseError != null) {
                                        Text(jsonParseError!!)
                                    }
                                },
                                minLines = 3,
                                maxLines = 5,
                                visualTransformation = HighlightCodeVisualTransformation(
                                    language = "json",
                                    highlighter = LocalHighlighter.current,
                                    darkMode = LocalDarkMode.current
                                ),
                                textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies.removeAt(index)
                            onUpdate(updatedBodies)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_body)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
