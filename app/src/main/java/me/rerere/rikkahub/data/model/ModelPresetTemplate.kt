package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry

/**
 * 「可用模型」列表（provider /v1/models 拉回来的候选）点 + 添加时使用的预设模板。
 *
 * 语义边界，别再搞混：
 *  - 这里的推断**只在新增模型的那一刻执行一次**，写进 Model 后就是普通用户数据；
 *  - 之后用户在模型详情 UI 怎么勾/怎么删，都是最终结论，任何读取路径都不得再回灌。
 *
 * 历史 bug：SettingsStore 每次读设置都调 withUrlInputIfKnown() 强行补 Modality.URL，
 * 于是用户在 UI 里取消 URL → 保存 → 再读出来又被加回去，表现为"关不掉"。
 */
object ModelPresetTemplate {

    /** 从注册表 + provider 特征推断出一份完整预设，用于新增模型时预填。 */
    fun applyTo(model: Model, provider: ProviderSetting? = null): Model {
        val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
        val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
        return model.copy(
            inputModalities = withProviderUrlHint(inputModalities, provider),
            outputModalities = outputModalities,
            toolCallingStrategy = ModelRegistry.MODEL_TOOL_STRATEGY.getData(model.modelId),
            isReasoningEnabled = ModelRegistry.MODEL_REASONING_ENABLED.getData(model.modelId),
        )
    }

    /** 只推断输入模态（模型 ID 输入框实时变更时用）。 */
    fun inputModalitiesFor(modelId: String, provider: ProviderSetting? = null): List<Modality> =
        withProviderUrlHint(ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId), provider)

    /**
     * 火山方舟（ark）走 OpenAI 协议但本身支持图片 URL 直传，
     * 注册表按 modelId 认不出来，这里靠 baseUrl 兜一层。
     */
    private fun withProviderUrlHint(
        modalities: List<Modality>,
        provider: ProviderSetting?,
    ): List<Modality> {
        val looksLikeArk = provider is ProviderSetting.OpenAI &&
            provider.baseUrl.contains("ark.cn-beijing.volces.com", ignoreCase = true)
        return if (looksLikeArk && Modality.URL !in modalities) {
            modalities + Modality.URL
        } else {
            modalities
        }
    }
}
