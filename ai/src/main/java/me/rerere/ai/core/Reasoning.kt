package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    @SerialName("off")
    OFF(0, "none"),
    @SerialName("on")
    ON(1, "on"),
    @SerialName("auto")
    AUTO(-1, "auto"),
    @SerialName("low")
    LOW(1_000, "low"),
    @SerialName("medium")
    MEDIUM(2_000, "medium"),
    @SerialName("high")
    HIGH(8_000, "high"),
    @SerialName("xhigh")
    XHIGH(16_000, "xhigh"),
    @SerialName("max")
    MAX(32_000, "max");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull { kotlin.math.abs(it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)) } ?: AUTO
        }

        /**
         * 根据模型 ID 返回该模型支持的推理档位列表.
         * Claude 部分模型支持 max 档位(取代或追加 xhigh):
         *   - Opus 4.5 / 4.6 / Sonnet 4.6: low medium high max (不支持 xhigh)
         *   - Opus 4.7 / 4.8 / Fable 5 / Mythos 5 / Mythos Preview: low medium high xhigh max
         * 其他模型默认使用 xhigh 作为最高档.
         */
        fun getSupportedLevels(modelId: String?): List<ReasoningLevel> {
            if (modelId.isNullOrBlank()) {
                return listOf(OFF, ON, AUTO, LOW, MEDIUM, HIGH, XHIGH)
            }
            val id = modelId.lowercase()
            val base = listOf(OFF, ON, AUTO, LOW, MEDIUM, HIGH)

            val isClaude = id.contains("claude")
            if (!isClaude) {
                return base + XHIGH
            }

            // Claude 模型版本匹配 (支持 4-5 / 4.5 / 4 5 等写法)
            val versionPattern = Regex("4[._\\- ]?([5-8])(?![0-9])")
            val versionMatch = versionPattern.find(id)
            val minorVersion = versionMatch?.groupValues?.get(1)?.toIntOrNull()

            val isFable = id.contains("fable")
            val isMythos = id.contains("mythos")

            // 支持 max 但不支持 xhigh: Opus 4.5, 4.6 / Sonnet 4.6
            val supportsMaxOnly = minorVersion != null && minorVersion in 5..6

            // 同时支持 xhigh 和 max: Opus 4.7, 4.8 / Fable 5 / Mythos 系列
            val supportsXhighAndMax = (minorVersion != null && minorVersion in 7..8) || isFable || isMythos

            return when {
                supportsXhighAndMax -> base + XHIGH + MAX
                supportsMaxOnly -> base + MAX
                else -> base + XHIGH
            }
        }
    }
}
