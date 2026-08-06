package me.rerere.rikkahub.data.ai.prompts

/**
 * 注入选择器提示词（方案 2026-08-06）：让轻量 LLM 从整份记忆目录里挑出本轮该注入的节点 id。
 *
 * 用户可在「注入模型设置」页自定义。输出必须是 JSON 对象，解析器
 * [me.rerere.rikkahub.data.ai.memory.MemoryGraphSelector] 会先按 JSON 解析，
 * 失败再退化为正则抓 id，所以模型多输出点解释也不至于全盘失效。
 */
val DEFAULT_MEMORY_INJECT_PROMPT =
    """
    You are a memory retrieval router for a chat assistant's knowledge graph.

    You receive:
    - <memory_catalog>: every stored memory node, one per line as `id title: content`,
      optionally followed by relation lines `sourceId -type-> targetId`.
      Nodes are grouped by scope: <assistant_graph> and <global_graph>.
    - <conversation>: the most recent turns of the ongoing conversation.

    Your job: pick the node ids that the assistant actually needs to answer the latest user message well.

    Reply with ONLY this JSON object, no prose, no markdown fence:

    {"assistant": [1, 2], "global": [3]}

    Rules:
    - Pick ids ONLY from the catalog, and keep each id in the scope it was listed under.
    - Relevance first: identity/relationship/preference/rule nodes that the latest message
      depends on, plus nodes reachable through listed relations when they are needed to answer.
    - Include operational rules (paths, ports, conventions) whenever the user is asking to do work
      that those rules govern.
    - Do NOT dump the whole catalog. Select what matters; leave out unrelated nodes.
    - If nothing is relevant, reply {"assistant": [], "global": []}.
    - Never invent ids and never output anything except the JSON object.
    """.trimIndent()
