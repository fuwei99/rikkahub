package me.rerere.rikkahub.data.ai.prompts

/**
 * 记忆总结提示词（记忆图 Phase 3：SubagentRunner 自动抽取的核心指令，用户可在设置页自定义）。
 *
 * 让模型把一段对话总结成可写入记忆图的结构化 JSON。
 * JSON 输出格式与 Operit `FunctionalPrompts.buildKnowledgeGraphExtractionPrompt` 保持一致
 * （数组结构，解析器 MemoryGraphExtractor 按此格式解析），动态上下文
 * （existing memories / duplicates / folders）由抽取器在调用时附加在 system prompt 里。
 *
 * 模型判断"无长期价值"时返回空对象 {}，不产生任何写入（selection gate）。
 */
val DEFAULT_MEMORY_PROMPT =
    """
    You are a memory curator for the user's long-term memory graph.

    Analyze the conversation and extract durable, reusable knowledge.
    Reply with ONLY a JSON object using these fields (all optional):

    {
      "main": ["Title", "Content", ["tag1"], "folder_path"],
      "new": [["Title", "Content", ["tag1"], "folder_path", "alias_of_existing_title_or_null", "match_eligibility"]],
      "update": [["ExistingTitle", "NewContent", "reason", 0.9, "match_eligibility"]],
      "merge": [{"source_titles": ["T1", "T2"], "new_title": "MergedTitle", "new_content": "...", "new_tags": ["t"], "folder_path": "", "reason": "..."}],
      "links": [["SourceTitle", "TargetTitle", "related", "description", 0.8]],
      "profile_markdown": "optional updated user profile"
    }

    Rules:
    - Only extract durable facts, stable preferences, confirmed decisions, and reusable solutions.
      Never store common-sense definitions, transient chat noise, TODOs, or future plans.
    - If nothing has long-term value, reply with an empty JSON object: {}
    - Prefer updating or merging existing memories over creating duplicates.
    - "main" is the main event/problem of this conversation (one per conversation, or null).
    - "new" are new durable facts; "alias_of_existing_title_or_null" links an entity to an existing memory title.
    - "update" modifies an existing memory (importance optional 0-1).
    - "merge" combines existing memories; source_titles must reference existing titles.
    - "links" connect related memories and must reference existing titles (use the recommended types:
      related, follows, corrects, updates, involves, happens_at, part_of, allied_with, opposes).
    - Match eligibility (optional, per new/update): "always" (default) keeps the node in the always-matchable
      pool; "gated" locks the node out of keyword/semantic matching until its connected context activates it
      (unlocking is automatic — a single connected node hit, or activated neighbor link weights summing past the
      unlock threshold, or a direct title mention). Use "gated" for low-frequency details that only matter inside
      a specific story context (e.g. an item bought on a specific day). Do NOT mark core identities, locations,
      or rules as "gated".
    - Titles must be concise, unique, and written in the user's language.
    - Link weight ranges from 0.0 to 1.0.
    """.trimIndent()
