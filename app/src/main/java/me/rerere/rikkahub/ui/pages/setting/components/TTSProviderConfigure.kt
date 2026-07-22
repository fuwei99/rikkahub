package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.FlowRow
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TtsRegexRule

@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        // Provider type selector
        var expanded by remember { mutableStateOf(false) }
        val providers = remember { TTSProviderSetting.Types }

        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_provider_type)) },
            description = { Text(stringResource(R.string.setting_tts_page_provider_type_description)) },
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = when (setting) {
                        is TTSProviderSetting.OpenAI -> "OpenAI"
                        is TTSProviderSetting.Gemini -> "Gemini"
                        is TTSProviderSetting.SystemTTS -> "System TTS"
                        is TTSProviderSetting.MiniMax -> "MiniMax"
                        is TTSProviderSetting.Qwen -> "Qwen"
                        is TTSProviderSetting.Groq -> "Groq"
                        is TTSProviderSetting.XAI -> "xAI"
                        is TTSProviderSetting.MiMo -> "MiMo"
                        is TTSProviderSetting.Step -> "Step"
                        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                        is TTSProviderSetting.FishAudio -> "Fish Audio"
                        is TTSProviderSetting.Doubao -> "Doubao"
                        is TTSProviderSetting.VolcengineAgent -> "火山方舟Agent"
                    },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    providers.forEach { providerClass ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (providerClass) {
                                        TTSProviderSetting.OpenAI::class -> "OpenAI"
                                        TTSProviderSetting.Gemini::class -> "Gemini"
                                        TTSProviderSetting.SystemTTS::class -> "System TTS"
                                        TTSProviderSetting.MiniMax::class -> "MiniMax"
                                        TTSProviderSetting.Qwen::class -> "Qwen"
                                        TTSProviderSetting.Groq::class -> "Groq"
                                        TTSProviderSetting.XAI::class -> "xAI"
                                        TTSProviderSetting.MiMo::class -> "MiMo"
                                        TTSProviderSetting.ElevenLabs::class -> "ElevenLabs"
                                        TTSProviderSetting.FishAudio::class -> "Fish Audio"
                                        TTSProviderSetting.Step::class -> "Step"
                                        TTSProviderSetting.Doubao::class -> "Doubao"
                                        TTSProviderSetting.VolcengineAgent::class -> "火山方舟Agent"
                                        else -> providerClass.simpleName ?: "Unknown"
                                    }
                                )
                            },
                            onClick = {
                                expanded = false
                                val newSetting = when (providerClass) {
                                    TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(
                                        id = setting.id,
                                        name = "OpenAI TTS"
                                    )

                                    TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(
                                        id = setting.id,
                                        name = "Gemini TTS"
                                    )

                                    TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(
                                        id = setting.id,
                                        name = "System TTS"
                                    )

                                    TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(
                                        id = setting.id,
                                        name = "MiniMax TTS"
                                    )

                                    TTSProviderSetting.Qwen::class -> TTSProviderSetting.Qwen(
                                        id = setting.id,
                                        name = "Qwen TTS"
                                    )

                                    TTSProviderSetting.Groq::class -> TTSProviderSetting.Groq(
                                        id = setting.id,
                                        name = "Groq TTS"
                                    )

                                    TTSProviderSetting.XAI::class -> TTSProviderSetting.XAI(
                                        id = setting.id,
                                        name = "xAI TTS"
                                    )

                                    TTSProviderSetting.MiMo::class -> TTSProviderSetting.MiMo(
                                        id = setting.id,
                                        name = "MiMo TTS"
                                    )
                                    TTSProviderSetting.ElevenLabs::class -> TTSProviderSetting.ElevenLabs(
                                        id = setting.id,
                                        name = "ElevenLabs TTS"
                                    )

                                    TTSProviderSetting.FishAudio::class -> TTSProviderSetting.FishAudio(
                                        id = setting.id,
                                        name = "Fish Audio TTS"
                                    )

                                    TTSProviderSetting.Step::class -> TTSProviderSetting.Step(
                                        id = setting.id,
                                        name = "Step TTS"
                                    )

                                    TTSProviderSetting.Doubao::class -> TTSProviderSetting.Doubao(
                                        id = setting.id,
                                        name = "Doubao TTS"
                                    )

                                    TTSProviderSetting.VolcengineAgent::class -> TTSProviderSetting.VolcengineAgent(
                                        id = setting.id,
                                        name = "火山方舟Agent"
                                    )

                                    else -> setting
                                }
                                onValueChange(newSetting)
                            }
                        )
                    }
                }
            }
        }

        // Name
        FormItem(
            label = { Text("名称") },
            description = { Text("配置项在系统内的标识名称") }
        ) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { newName ->
                    onValueChange(setting.copyProvider(name = newName))
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Custom Filter Regex
        FormItem(
            label = { Text("正则过滤") },
            description = { Text("在将文本打包发送给 TTS 渲染前过滤或替换掉文本内容 (默认过滤 [#/*\\$%] 等 Markdown 标点符号)") }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = setting.filterRegex,
                    onValueChange = { newRegex ->
                        onValueChange(setting.copyProvider(filterRegex = newRegex))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("过滤正则表达式") }
                )
                OutlinedTextField(
                    value = setting.replaceWith,
                    onValueChange = { newReplaceWith ->
                        onValueChange(setting.copyProvider(replaceWith = newReplaceWith))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("替换为") }
                )
            }
        }

        // Custom Regex Rules List Section
        TtsRegexRulesSection(setting, onValueChange)

        // Provider-specific fields
        when (setting) {
            is TTSProviderSetting.OpenAI -> OpenAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Gemini -> GeminiTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiniMax -> MiniMaxTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.SystemTTS -> SystemTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Qwen -> QwenTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Groq -> GroqTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.XAI -> XAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiMo -> MiMoTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.ElevenLabs -> ElevenLabsTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.FishAudio -> FishAudioTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Step -> StepTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Doubao -> DoubaoTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.VolcengineAgent -> VolcengineAgentTTSConfiguration(setting, onValueChange)
        }
    }
}

@Composable
private fun OpenAITTSConfiguration(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    // Voice
    var voiceExpanded by remember { mutableStateOf(false) }
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voice,
                onValueChange = { newVoice ->
                    onValueChange(setting.copy(voice = newVoice))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voice = voice))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiMoTTSConfiguration(
    setting: TTSProviderSetting.MiMo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // MiMo 配置均为自由输入 默认值只是占位
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2-tts") }
        )
    }

    // Voice
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voice,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo_default") }
        )
    }
}

@Composable
private fun MiniMaxTTSConfiguration(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.5-hd-preview") }
        )
    }

    // Voice ID
    var voiceIdExpanded by remember { mutableStateOf(false) }
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceIdExpanded,
            onExpandedChange = { voiceIdExpanded = !voiceIdExpanded }
        ) {
            OutlinedTextField(
                value = setting.voiceId,
                onValueChange = { newVoiceId ->
                    onValueChange(setting.copy(voiceId = newVoiceId))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceIdExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceIdExpanded,
                onDismissRequest = { voiceIdExpanded = false }
            ) {
                voiceIds.forEach { voiceId ->
                    DropdownMenuItem(
                        text = { Text(voiceId) },
                        onClick = {
                            voiceIdExpanded = false
                            onValueChange(setting.copy(voiceId = voiceId))
                        }
                    )
                }
            }
        }
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun GeminiTTSConfiguration(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_gemini)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_gemini)) }
        )
    }

    // Voice Name
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceName,
            onValueChange = { newVoiceName ->
                onValueChange(setting.copy(voiceName = newVoiceName))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_voice_name_placeholder)) }
        )
    }
}

@Composable
private fun SystemTTSConfiguration(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // Speech Rate
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        description = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..3.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    // Pitch
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        description = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..2.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

@Composable
private fun QwenTTSConfiguration(
    setting: TTSProviderSetting.Qwen,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-tts-flash") }
        )
    }

    // Voice
    var voiceExpanded by remember { mutableStateOf(false) }
    val voices = listOf(
        "Cherry", "Serene", "Ethan", "Chelsie",
        "Momo", "Vivian", "Moon", "Maia", "Kai",
        "Nofish", "Bella", "Jennifer", "Ryan",
        "Katerina", "Aiden", "Eldric Sage", "Mia",
        "Mochi", "Bellona", "Vincent", "Bunny",
        "Neil", "Elias", "Arthur", "Nini"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voice,
                onValueChange = { newVoice ->
                    onValueChange(setting.copy(voice = newVoice))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voice = voice))
                        }
                    )
                }
            }
        }
    }

    // Language Type
    var languageExpanded by remember { mutableStateOf(false) }
    val languageTypes = listOf("Auto", "Chinese", "English", "Japanese", "Korean")

    FormItem(
        label = { Text("Language Type") },
        description = { Text("Language type for TTS synthesis") }
    ) {
        ExposedDropdownMenuBox(
            expanded = languageExpanded,
            onExpandedChange = { languageExpanded = !languageExpanded }
        ) {
            OutlinedTextField(
                value = setting.languageType,
                onValueChange = { newLanguageType ->
                    onValueChange(setting.copy(languageType = newLanguageType))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = languageExpanded,
                onDismissRequest = { languageExpanded = false }
            ) {
                languageTypes.forEach { languageType ->
                    DropdownMenuItem(
                        text = { Text(languageType) },
                        onClick = {
                            languageExpanded = false
                            onValueChange(setting.copy(languageType = languageType))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroqTTSConfiguration(
    setting: TTSProviderSetting.Groq,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gsk_xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
    }

    // Voice
    var voiceExpanded by remember { mutableStateOf(false) }
    val voices = listOf("austin", "natalie", "kailin")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voice,
                onValueChange = { newVoice ->
                    onValueChange(setting.copy(voice = newVoice))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voice = voice))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun XAITTSConfiguration(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("xai-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") }
        )
    }

    // Voice ID
    var voiceExpanded by remember { mutableStateOf(false) }
    val voices = listOf(
        "eve" to "Eve",
        "ara" to "Ara",
        "rex" to "Rex",
        "sal" to "Sal",
        "leo" to "Leo"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voiceId,
                onValueChange = { newVoiceId ->
                    onValueChange(setting.copy(voiceId = newVoiceId))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                voices.forEach { (voiceId, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voiceId = voiceId))
                        }
                    )
                }
            }
        }
    }

    // Language
    var languageExpanded by remember { mutableStateOf(false) }
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es-ES" to "Spanish (Spain)",
        "es-MX" to "Spanish (Mexico)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "it" to "Italian",
        "ru" to "Russian",
        "ar-EG" to "Arabic (Egypt)",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "bn" to "Bengali"
    )

    FormItem(
        label = { Text("Language") },
    ) {
        ExposedDropdownMenuBox(
            expanded = languageExpanded,
            onExpandedChange = { languageExpanded = !languageExpanded }
        ) {
            OutlinedTextField(
                value = setting.language,
                onValueChange = { newLanguage ->
                    onValueChange(setting.copy(language = newLanguage))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = languageExpanded,
                onDismissRequest = { languageExpanded = false }
            ) {
                languages.forEach { (code, displayName) ->
                    DropdownMenuItem(
                        text = { Text("$displayName ($code)") },
                        onClick = {
                            languageExpanded = false
                            onValueChange(setting.copy(language = code))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ElevenLabsTTSConfiguration(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk_...") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") }
        )
    }

    // Model
    var modelExpanded by remember { mutableStateOf(false) }
    val models = listOf(
        "eleven_multilingual_v2" to "Eleven Multilingual v2",
        "eleven_v3" to "Eleven v3",
        "eleven_flash_v2_5" to "Eleven Flash v2.5"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded }
        ) {
            OutlinedTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                models.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = { Text("$displayName ($modelId)") },
                        onClick = {
                            modelExpanded = false
                            onValueChange(setting.copy(model = modelId))
                        }
                    )
                }
            }
        }
    }

    // Voice ID
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("JBFqnCBsd6RMkjVDRZzb") }
        )
    }

    // Stability
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_stability)) },
        description = { Text(stringResource(R.string.setting_tts_page_stability_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                onValueChange(setting.copy(stability = newStability.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.5",
        )
    }

    // Similarity Boost
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_similarity_boost)) },
        description = { Text(stringResource(R.string.setting_tts_page_similarity_boost_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                onValueChange(setting.copy(similarityBoost = newSimilarityBoost.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.75",
        )
    }
}

@Composable
private fun FishAudioTTSConfiguration(
    setting: TTSProviderSetting.FishAudio,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://fish.audio/app/api-keys") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.fish.audio") }
        )
    }

    // Model (下拉选择框 + 文本输入框，完全同 ElevenLabs 格式)
    var modelExpanded by remember { mutableStateOf(false) }
    val models = listOf(
        "s2.1-pro" to "S2.1-Pro (推荐)",
        "s2.1-pro-free" to "S2.1-Pro Free (免费)",
        "s2-pro" to "S2-Pro",
        "s1" to "S1"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded }
        ) {
            OutlinedTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                models.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = { Text("$displayName ($modelId)") },
                        onClick = {
                            modelExpanded = false
                            onValueChange(setting.copy(model = modelId))
                        }
                    )
                }
            }
        }
    }

    // Voice ID (reference_id)
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.referenceId,
            onValueChange = { newReferenceId ->
                onValueChange(setting.copy(referenceId = newReferenceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("802e3bc2b27e49c2995d23ef70e6ac89") }
        )
    }

    // Temperature
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_temperature)) },
        description = { Text(stringResource(R.string.setting_tts_page_temperature_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.temperature,
            onValueChange = { newTemperature ->
                onValueChange(setting.copy(temperature = newTemperature.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.7",
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_fish_audio_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                onValueChange(setting.copy(speed = newSpeed.coerceIn(0.5f, 2f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "1.0",
        )
    }
}

@Composable
private fun StepTTSConfiguration(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text("从阶跃星辰官网获取密钥: platform.stepfun.com/interface-key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("从阶跃星辰官网获取密钥") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    // Model
    var modelExpanded by remember { mutableStateOf(false) }
    val models = listOf(
        "step-tts-mini" to "step-tts-mini (轻量, 便宜)",
        "step-tts-vivid" to "step-tts-vivid (情感丰富)",
        "stepaudio-2.5-tts" to "stepaudio-2.5-tts (语境感知, 支持 instruction)",
        "step-tts-2" to "step-tts-2 (上一代)",
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded }
        ) {
            OutlinedTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                models.forEach { (modelId, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            modelExpanded = false
                            onValueChange(setting.copy(model = modelId))
                        }
                    )
                }
            }
        }
    }

    // Voice
    var voiceExpanded by remember { mutableStateOf(false) }
    // 部分常用 voice-id, 完整列表见官方开发指南
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices = listOf(
        "elegantgentle-female" to "气质温婉 (elegantgentle-female)",
        "livelybreezy-female" to "活力轻快 (livelybreezy-female)",
        "energeticconfident-female" to "活力自信 (energeticconfident-female)",
        "jingdiannvsheng" to "经典女声 (jingdiannvsheng)",
        "wenroushunv" to "温柔熟女 (wenroushunv)",
        "tianmeinvsheng" to "甜美女声 (tianmeinvsheng)",
        "qingchunshaonv" to "清纯少女 (qingchunshaonv)",
        "wenrounvsheng" to "温柔女声 (wenrounvsheng)",
        "ruanmengnvsheng" to "软萌女生 (ruanmengnvsheng)",
        "youyanvsheng" to "优雅女生 (youyanvsheng)",
        "lengyanyujie" to "冷艳御姐 (lengyanyujie)",
        "shuangkuaijiejie" to "爽快姐姐 (shuangkuaijiejie)",
        "wenjingxuejie" to "文静学姐 (wenjingxuejie)",
        "linjiajiejie" to "邻家姐姐 (linjiajiejie)",
        "linjiameimei" to "邻家妹妹 (linjiameimei)",
        "zhixingjiejie" to "知性姐姐 (zhixingjiejie)",
        "cixingnansheng" to "磁性男声 (cixingnansheng)",
        "wenrounansheng" to "温柔男声 (wenrounansheng)",
        "yuanqinansheng" to "元气男声 (yuanqinansheng)",
        "zhengpaiqingnian" to "正派青年 (zhengpaiqingnian)",
        "ruyananshi" to "儒雅男士 (ruyananshi)",
        "boyinnansheng" to "播音男声 (boyinnansheng)",
        "shenchennanyin" to "深沉男音 (shenchennanyin)",
        "shuangkuainansheng" to "爽快男声 (shuangkuainansheng)",
        "ganliannvsheng" to "干练女声 (ganliannvsheng)",
        "qinhenvsheng" to "亲切女声 (qinhenvsheng)",
        "huolinvsheng" to "活力女声 (huolinvsheng)",
        "jilingshaonv" to "机灵少女 (jilingshaonv)",
        "yuanqishaonv" to "元气少女 (yuanqishaonv)",
        "wenrougongzi" to "温柔公子 (wenrougongzi)",
        "qingniandaxuesheng" to "青年大学生 (qingniandaxuesheng)",
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voice,
                onValueChange = { newVoice ->
                    onValueChange(setting.copy(voice = newVoice))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                voices.forEach { (voiceId, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voice = voiceId))
                        }
                    )
                }
            }
        }
    }

    // Response Format
    var formatExpanded by remember { mutableStateOf(false) }
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    FormItem(
        label = { Text("Response Format") },
        description = { Text("音频编码格式 (注意 StepFun API 字段名为 camelCase)") }
    ) {
        ExposedDropdownMenuBox(
            expanded = formatExpanded,
            onExpandedChange = { formatExpanded = !formatExpanded }
        ) {
            OutlinedTextField(
                value = setting.responseFormat,
                onValueChange = { newFormat ->
                    onValueChange(setting.copy(responseFormat = newFormat))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = formatExpanded,
                onDismissRequest = { formatExpanded = false }
            ) {
                formats.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format) },
                        onClick = {
                            formatExpanded = false
                            onValueChange(setting.copy(responseFormat = format))
                        }
                    )
                }
            }
        }
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text("语速 (0.5 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Volume
    FormItem(
        label = { Text("Volume") },
        description = { Text("音量 (0.1 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Volume"
        )
    }

    // Sample Rate
    var sampleRateExpanded by remember { mutableStateOf(false) }
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    FormItem(
        label = { Text("Sample Rate") },
        description = { Text("采样率 (Hz)") }
    ) {
        ExposedDropdownMenuBox(
            expanded = sampleRateExpanded,
            onExpandedChange = { sampleRateExpanded = !sampleRateExpanded }
        ) {
            OutlinedTextField(
                value = setting.sampleRate.toString(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = sampleRateExpanded,
                onDismissRequest = { sampleRateExpanded = false }
            ) {
                sampleRates.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text("$rate Hz") },
                        onClick = {
                            sampleRateExpanded = false
                            onValueChange(setting.copy(sampleRate = rate))
                        }
                    )
                }
            }
        }
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    FormItem(
        label = { Text("Instruction") },
        description = { Text("全局语境指令, 仅 stepaudio-2.5-tts 生效 (≤200 字符, 留空不下发)") }
    ) {
        OutlinedTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如: 语气温柔, 语速偏慢") },
            minLines = 2,
            maxLines = 4,
        )
    }
}

@Composable
private fun DoubaoTTSConfiguration(
    setting: TTSProviderSetting.Doubao,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-wei123") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://localhost:1547/v1") }
        )
    }

    // Voice ID / Speaker
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voice,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("female-shaonv") }
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.2f..3.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Pitch
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        description = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in -10.0f..10.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

@Composable
private fun VolcengineAgentTTSConfiguration(
    setting: TTSProviderSetting.VolcengineAgent,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text("API Key") },
        description = { Text("火山方舟专属 API Key (X-Api-Key)") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：0723xxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
        )
    }

    // Resource ID
    FormItem(
        label = { Text("Resource ID") },
        description = { Text("接口资源 ID (X-Api-Resource-Id)，默认：seed-tts-2.0") }
    ) {
        OutlinedTextField(
            value = setting.resourceId,
            onValueChange = { newResourceId ->
                onValueChange(setting.copy(resourceId = newResourceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("seed-tts-2.0") }
        )
    }

    // Base URL
    FormItem(
        label = { Text("Base URL") },
        description = { Text("火山引擎 API 基础地址") }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://openspeech.bytedance.com") }
        )
    }

    // Volcengine Voice Clone Integration
    VolcengineVoiceCloneSection(setting, onValueChange)

    // Speaker / Voice
    FormItem(
        label = { Text("音色 (Speaker)") },
        description = { Text("音色标识，支持手动输入，或在上方选择克隆音色/默认音色") }
    ) {
        OutlinedTextField(
            value = setting.speaker,
            onValueChange = { newSpeaker ->
                onValueChange(setting.copy(speaker = newSpeaker))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("zh_female_gaolengyujie_uranus_bigtts") }
        )
    }

    // Format
    FormItem(
        label = { Text("音频格式") },
        description = { Text("返回音频格式 (mp3 或 wav)") }
    ) {
        OutlinedTextField(
            value = setting.format,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(format = newFormat))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mp3") }
        )
    }

    // Sample Rate
    FormItem(
        label = { Text("采样率 (Sample Rate)") },
        description = { Text("音频采样率 (Hz)，例如：24000") }
    ) {
        OutlinedNumberInput(
            value = setting.sampleRate.toFloat(),
            onValueChange = { newRate ->
                onValueChange(setting.copy(sampleRate = newRate.toInt()))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Sample Rate"
        )
    }
}

@Composable
private fun VolcengineVoiceCloneSection(
    setting: TTSProviderSetting.VolcengineAgent,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCloning by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var customSpeakerName by remember { mutableStateOf("") }
    var customSpeakerId by remember { mutableStateOf("") }
    
    // File picker launcher
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (customSpeakerId.isBlank() || customSpeakerName.isBlank()) {
                Toast.makeText(context, "请先填写音色代号与显示名称！", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            
            // Validation check based on the volcengine regex rule
            val regexBlock = Regex("^((?i:S_|ICL_|MIX_|DiT_|BV)|[a-z]{2}_|(?i:(wvae|moon|mercury|venus|earth|mars|jupiter|saturn|uranus|neptune|pluto|umm)_)).*|.*_(?i:bigtts|bigtts_cc|tob|cs_tob|streaming)$|^[^a-zA-Z]|.*[-_]$|^.{0,7}$|^.{257,}$|.*[^a-zA-Z0-9_-].*")
            if (regexBlock.matches(customSpeakerId)) {
                Toast.makeText(context, "音色代号不符合规范，已被系统防冲突拦截！", Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }

            coroutineScope.launch(Dispatchers.IO) {
                isCloning = true
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.size > 10 * 1024 * 1024) {
                        launch(Dispatchers.Main) { Toast.makeText(context, "读取文件失败或文件大于10MB！", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    
                    val payload = JSONObject().apply {
                        put("speaker_id", "custom_speaker_id")
                        put("custom_speaker_id", customSpeakerId)
                        put("audio", JSONObject().apply {
                            put("data", base64Data)
                            // We can try to infer the format from Uri or just default to wav/mp3
                            put("format", "wav")
                        })
                    }

                    val request = okhttp3.Request.Builder()
                        .url("${setting.baseUrl.trimEnd('/')}/api/v3/tts/voice_clone")
                        .addHeader("X-Api-Key", setting.apiKey)
                        .addHeader("X-Api-Request-Id", java.util.UUID.randomUUID().toString())
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val client = okhttp3.OkHttpClient()
                    val response = client.newCall(request).execute()
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val code = json.optInt("code", -1)
                    
                    launch(Dispatchers.Main) {
                        if (response.isSuccessful && code == 0) {
                            Toast.makeText(context, "音色克隆任务提交成功，请点击查询检查状态", Toast.LENGTH_LONG).show()
                            // Store the speaker initially
                            val updatedList = setting.clonedSpeakers + TTSProviderSetting.VolcengineClonedSpeaker(customSpeakerName, customSpeakerId)
                            onValueChange(setting.copy(clonedSpeakers = updatedList))
                        } else {
                            val msg = json.optString("message", "未知错误")
                            Toast.makeText(context, "克隆提交失败: $msg (Code: $code)", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "请求异常: ${e.message}", Toast.LENGTH_LONG).show() }
                } finally {
                    isCloning = false
                }
            }
        }
    }

    FormItem(
        label = { Text("声音克隆与音色库") },
        description = { Text("支持通过上传本地录音克隆专属音色。训练需要一定时间。") }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Cloned speakers list
            if (setting.clonedSpeakers.isNotEmpty()) {
                Text("已保存的克隆音色：", style = MaterialTheme.typography.titleSmall)
                setting.clonedSpeakers.forEach { cloned ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cloned.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text("ID: ${cloned.speakerId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            
                            // Check button
                            TextButton(
                                enabled = !isChecking,
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        isChecking = true
                                        try {
                                            val payload = JSONObject().apply {
                                                put("speaker_id", "custom_speaker_id")
                                                put("custom_speaker_id", cloned.speakerId)
                                            }
                                            val request = okhttp3.Request.Builder()
                                                .url("${setting.baseUrl.trimEnd('/')}/api/v3/tts/get_voice")
                                                .addHeader("X-Api-Key", setting.apiKey)
                                                .addHeader("X-Api-Request-Id", java.util.UUID.randomUUID().toString())
                                                .addHeader("Content-Type", "application/json")
                                                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                                                .build()

                                            val client = okhttp3.OkHttpClient()
                                            val response = client.newCall(request).execute()
                                            val bodyStr = response.body?.string() ?: ""
                                            val json = JSONObject(bodyStr)
                                            val status = json.optInt("status", -1)
                                            
                                            // status interpretation: NotFound = 0, Training = 1, Success = 2, Failed = 3, Active = 4
                                            val statusDesc = when (status) {
                                                0 -> "NotFound (音色未找到)"
                                                1 -> "Training (训练中...)"
                                                2 -> "Success (已训练成功，点击使用)"
                                                3 -> "Failed (训练失败)"
                                                4 -> "Active (已激活可用)"
                                                else -> "未知状态 (${status})"
                                            }

                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, "音色 [${cloned.displayName}] 状态: $statusDesc", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: Exception) {
                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, "查询失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } finally {
                                            isChecking = false
                                        }
                                    }
                                }
                            ) {
                                Text("查询状态")
                            }

                            // Use button
                            TextButton(
                                onClick = {
                                    onValueChange(setting.copy(speaker = cloned.speakerId))
                                    Toast.makeText(context, "已选择音色: ${cloned.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("使用")
                            }

                            // Delete button
                            IconButton(
                                onClick = {
                                    val newList = setting.clonedSpeakers.filter { it.speakerId != cloned.speakerId }
                                    onValueChange(setting.copy(clonedSpeakers = newList))
                                }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Quick default voice templates selector
            Text("推荐预设精品音色：", style = MaterialTheme.typography.titleSmall)
            val defaultVoices = listOf(
                "温柔桃子升级版" to "zh_female_vv_uranus_bigtts",
                "高冷御姐" to "zh_female_gaolengyujie_uranus_bigtts",
                "阳光青年" to "zh_male_yangguangqingnian_uranus_bigtts",
                "故事说书人" to "zh_male_gushishuoshuren_uranus_bigtts",
                "元气少女" to "zh_female_yuanqishaonv_uranus_bigtts",
                "温暖阿虎 2.0" to "zh_male_wennuanahu_uranus_bigtts",
                "磁性男嗓 2.0" to "ICL_uranus_zh_male_cixingnansang_tob",
                "京腔侃爷/Harmony(1.0)" to "zh_male_jingqiangkanye_moon_bigtts"
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                defaultVoices.forEach { (name, id) ->
                    androidx.compose.material3.SuggestionChip(
                        onClick = {
                            onValueChange(setting.copy(speaker = id))
                            Toast.makeText(context, "已载入预设: $name", Toast.LENGTH_SHORT).show()
                        },
                        label = { Text(name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()

            // Cloning Panel Form
            Text("新建克隆任务", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = customSpeakerName,
                onValueChange = { customSpeakerName = it },
                label = { Text("音色显示名称") },
                placeholder = { Text("例如：我自己的声音") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = customSpeakerId,
                onValueChange = { customSpeakerId = it },
                label = { Text("自定义音色唯一代号") },
                placeholder = { Text("例如：custom_myvoice_01") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("以英文字母开头，包含数字/下划线，不可与官方音色冲突") }
            )

            // Manual Add Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = customSpeakerId.isNotBlank() && customSpeakerName.isNotBlank(),
                    onClick = {
                        val updatedList = setting.clonedSpeakers + TTSProviderSetting.VolcengineClonedSpeaker(customSpeakerName, customSpeakerId)
                        onValueChange(setting.copy(clonedSpeakers = updatedList))
                        Toast.makeText(context, "已手动添加音色 [$customSpeakerName] 到库中", Toast.LENGTH_SHORT).show()
                        customSpeakerName = ""
                        customSpeakerId = ""
                    }
                ) {
                    Text("手动保存ID")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isCloning && customSpeakerId.isNotBlank() && customSpeakerName.isNotBlank(),
                    onClick = {
                        filePickerLauncher.launch("audio/*")
                    }
                ) {
                    Text(if (isCloning) "克隆中..." else "选择录音并开始克隆")
                }
            }
        }
    }
}

@Composable
private fun TtsRegexRulesSection(
    setting: TTSProviderSetting,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val rules = setting.regexRules

    // Dialog state for adding/editing a rule
    var showEditDialog by remember { mutableStateOf<TtsRegexRule?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    FormItem(
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("正则过滤规则列表")
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val json = try {
                            Json.encodeToString(
                                ListSerializer(TtsRegexRule.serializer()),
                                rules
                            )
                        } catch (e: Exception) {
                            ""
                        }
                        if (json.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(json))
                            Toast.makeText(context, "规则已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = HugeIcons.Upload02,
                        contentDescription = "导出规则",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = HugeIcons.FileImport,
                        contentDescription = "导入规则",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { showEditDialog = TtsRegexRule(name = "", pattern = "") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = "新建规则",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        description = { Text("自定义TTS播放前的正则表达式替换流程，按顺序依次执行。支持独立开关与排序。") }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (rules.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无自定义正则规则，点击右上角加号添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                rules.forEachIndexed { index, rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Reorder buttons
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = rules.toMutableList()
                                            val temp = mutable[index]
                                            mutable[index] = mutable[index - 1]
                                            mutable[index - 1] = temp
                                            onValueChange(setting.copyProvider(regexRules = mutable))
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.ArrowUp01,
                                        contentDescription = "上移",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < rules.size - 1) {
                                            val mutable = rules.toMutableList()
                                            val temp = mutable[index]
                                            mutable[index] = mutable[index + 1]
                                            mutable[index + 1] = temp
                                            onValueChange(setting.copyProvider(regexRules = mutable))
                                        }
                                    },
                                    enabled = index < rules.size - 1,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.ArrowDown01,
                                        contentDescription = "下移",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Rule info
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = rule.name.ifBlank { "未命名规则" },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "/${rule.pattern}/ -> \"${rule.replaceWith}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Switch enabled status
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { checked ->
                                    val mutable = rules.toMutableList()
                                    mutable[index] = rule.copy(enabled = checked)
                                    onValueChange(setting.copyProvider(regexRules = mutable))
                                }
                            )

                            // Action buttons
                            IconButton(
                                onClick = { showEditDialog = rule },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = HugeIcons.PencilEdit01,
                                    contentDescription = "编辑规则",
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val mutable = rules.toMutableList()
                                    mutable.removeAt(index)
                                    onValueChange(setting.copyProvider(regexRules = mutable))
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = "删除规则",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit/Create Dialog
    showEditDialog?.let { rule ->
        var tempName by remember { mutableStateOf(rule.name) }
        var tempPattern by remember { mutableStateOf(rule.pattern) }
        var tempReplaceWith by remember { mutableStateOf(rule.replaceWith) }
        var errorText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            title = { Text(if (rule.name.isEmpty() && rule.pattern.isEmpty()) "新建正则规则" else "编辑正则规则") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("规则名称") },
                        placeholder = { Text("例如：删除正文") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tempPattern,
                        onValueChange = { 
                            tempPattern = it
                            errorText = try {
                                if (it.isNotEmpty()) Regex(it)
                                ""
                            } catch (e: Exception) {
                                "正则表达式格式错误"
                            }
                        },
                        label = { Text("查找正则表达式") },
                        placeholder = { Text("例如：<content>[\\s\\S]*?<\\/content>") },
                        isError = errorText.isNotEmpty(),
                        supportingText = {
                            if (errorText.isNotEmpty()) {
                                Text(errorText, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tempReplaceWith,
                        onValueChange = { tempReplaceWith = it },
                        label = { Text("替换为") },
                        placeholder = { Text("留空代表直接删除匹配内容") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (errorText.isNotEmpty()) return@TextButton
                        
                        val newRule = rule.copy(
                            name = tempName,
                            pattern = tempPattern,
                            replaceWith = tempReplaceWith
                        )
                        
                        val mutable = rules.toMutableList()
                        val existingIndex = mutable.indexOfFirst { it.id == rule.id }
                        if (existingIndex >= 0) {
                            mutable[existingIndex] = newRule
                        } else {
                            mutable.add(newRule)
                        }
                        onValueChange(setting.copyProvider(regexRules = mutable))
                        showEditDialog = null
                    },
                    enabled = tempPattern.isNotEmpty() && errorText.isEmpty()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        var importJsonText by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入正则规则") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("请在下方输入框中粘贴导出的JSON规则文本：", style = MaterialTheme.typography.bodyMedium)
                    
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { 
                            importJsonText = it
                            importError = ""
                        },
                        label = { Text("JSON 规则文本") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6
                    )
                    
                    if (importError.isNotEmpty()) {
                        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    TextButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                importJsonText = clipText
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("从剪贴板粘贴")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            val importedRules = Json.decodeFromString(
                                ListSerializer(TtsRegexRule.serializer()),
                                importJsonText
                            )
                            val mutable = rules.toMutableList()
                            // Merge by ID or append
                            importedRules.forEach { rule ->
                                val idx = mutable.indexOfFirst { it.id == rule.id }
                                if (idx >= 0) {
                                    mutable[idx] = rule
                                } else {
                                    mutable.add(rule)
                                }
                            }
                            onValueChange(setting.copyProvider(regexRules = mutable))
                            showImportDialog = false
                            Toast.makeText(context, "成功导入了 ${importedRules.size} 条规则", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            importError = "JSON 解析失败，请确认格式正确"
                        }
                    },
                    enabled = importJsonText.isNotEmpty()
                ) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
