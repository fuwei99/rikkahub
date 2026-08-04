package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.VectorProviderSetting
import kotlin.uuid.Uuid

/**
 * 预置向量模型服务渠道（记忆图 Phase 2）。
 *
 * 全部为 OpenAI 兼容 /embeddings 端点，用户只需填 apiKey 即可用：
 * - 火山方舟 Agent Plan 订阅（/api/plan/v3，doubao-embedding-vision）
 * - 火山方舟免费额度（/api/v3，doubao-embedding 每天 200 万 token 免费、按天重置）
 * - Fireworks（qwen3-embedding-8b，$0.10/M，MTEB 多语 SOTA；可用赠送额度）
 *
 * 其他 OpenAI 兼容渠道（阿里百炼、智谱、OpenAI 等）用户在设置页自行添加。
 */
val DEFAULT_VECTOR_PROVIDERS = listOf(
    VectorProviderSetting.OpenAI(
        id = Uuid.parse("3f1a9d2c-4b7e-4c5d-9a8f-1e2d3c4b5a6f"),
        name = "火山方舟 Plan 订阅",
        baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
        apiKey = "",
        enabled = true,
        models = listOf(
            Model(
                modelId = "doubao-embedding-vision",
                displayName = "Doubao Embedding Vision",
                type = ModelType.EMBEDDING,
            ),
        ),
    ),
    VectorProviderSetting.OpenAI(
        id = Uuid.parse("5a7c3b1d-8e2f-4a6b-9c0d-3e4f5a6b7c8d"),
        name = "火山方舟免费额度",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = "",
        enabled = true,
        models = listOf(
            Model(
                modelId = "doubao-embedding",
                displayName = "Doubao Embedding",
                type = ModelType.EMBEDDING,
            ),
            Model(
                modelId = "doubao-embedding-large-text-250615",
                displayName = "Doubao Embedding Large Text",
                type = ModelType.EMBEDDING,
            ),
        ),
    ),
    VectorProviderSetting.OpenAI(
        id = Uuid.parse("9d2e1f3a-5b6c-4d7e-8f9a-0b1c2d3e4f5a"),
        name = "Fireworks AI",
        baseUrl = "https://api.fireworks.ai/inference/v1",
        apiKey = "",
        enabled = true,
        models = listOf(
            Model(
                modelId = "accounts/fireworks/models/qwen3-embedding-8b",
                displayName = "Qwen3 Embedding 8B",
                type = ModelType.EMBEDDING,
            ),
        ),
    ),
)
