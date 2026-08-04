package me.rerere.rikkahub.data.ai.prompts

/**
 * 记忆总结提示词（记忆图 Phase 3 前置配置：先落设置 UI，实际调用接线在 Phase 3）。
 *
 * 让模型把一段对话总结成可写入记忆图的结构化 JSON：
 * main / entities / updates / merges / links / profile。
 * 模型判断"无长期价值"时返回空对象 {}，不产生任何写入。
 * 与 Operit 的"结构化决策 + 有序写回"机制同构（见交接文档 §6.5）。
 */
val DEFAULT_MEMORY_PROMPT =
    """
    You are a memory curator for the user's long-term memory graph.

    Analyze the conversation and extract durable, reusable knowledge.
    Reply with ONLY a JSON object, using these optional fields:

    {
      "main": {"title": "...", "content": "...", "tags": ["..."], "folder": "..."},
      "entities": [{"title": "...", "content": "...", "tags": ["..."], "alias_for": null}],
      "updates": [{"title": "...", "content": "...", "reason": "..."}],
      "merges": [{"source_titles": ["..."], "new_title": "...", "content": "...", "reason": "..."}],
      "links": [{"source": "...", "target": "...", "type": "related", "weight": 0.8, "description": "..."}],
      "profile": "..."
    }

    Rules:
    - Only extract durable facts, preferences, and reusable solutions. Never store transient chat noise.
    - If nothing has long-term value, reply with an empty JSON object: {}
    - Prefer updating or merging existing memories over creating duplicates.
    - "main" is the main event/problem of this conversation; "entities" are new durable facts;
      "updates" modify existing memories; "merges" combine existing memories;
      "links" connect related memories and must reference existing titles.
    - Titles must be concise, unique, and written in the user's language.
    - "profile" is an optional updated markdown profile of the user; omit if unchanged.
    - Link "weight" ranges from 0.0 to 1.0.
    """.trimIndent()
