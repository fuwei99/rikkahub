package me.rerere.rikkahub.data.ai.prompts

/**
 * OCR 提示词。
 *
 * 输出被要求包在 XML 标签里，因为同一次调用要同时服务两个消费方：
 * - 对话链路只取 <description>，多余的名字/标签塞进上下文纯属噪音；
 * - 相册要拿 <name_zh>/<name_en>/<tags> 去做命名和筛选。
 *
 * 让模型一次产出、由 OcrResultParser 拆开，比为了几个字段再多打一次视觉模型划算得多。
 *
 * {{tags}} 会在运行时替换成用户自己维护的标签白名单。
 * 之所以给白名单而不让模型自由生成：模型每次都能发明新词，
 * 标签集会迅速膨胀成互不相交的噪音，筛选器随之失效。
 */
val DEFAULT_OCR_PROMPT =
    """
    You are an OCR and image cataloging assistant.

    Analyze the image and reply with exactly the following XML structure, nothing outside it:

    <description>
    Extract all visible text, and describe non-text elements (icons, shapes, arrows, objects, symbols, emojis).
    For each element, specify the exact text (for text) or a short description (for non-text),
    its approximate position (e.g. 'top left', 'center right', 'bottom middle'),
    and its spatial relationship to nearby elements (e.g. 'above', 'below', 'next to').
    For document-type content, use markdown and latex format.
    If there are objects like buildings or characters, try to identify who they are.
    Keep the original reading order and layout structure as much as possible.
    Do not interpret or translate - only transcribe and describe what is visually present.
    Write the whole description in Simplified Chinese.
    </description>
    <name_zh>A concise Chinese file name, at most 12 characters, no punctuation, no file extension</name_zh>
    <name_en>A concise English file name, at most 6 words, lowercase, words separated by spaces, no extension</name_en>
    <tags>Comma-separated tags chosen ONLY from this list: {{tags}}. Leave empty if none apply. Never invent new tags.</tags>
    """.trimIndent()

/** 提示词里的标签白名单占位符 */
const val OCR_PROMPT_TAGS_PLACEHOLDER = "{{tags}}"
