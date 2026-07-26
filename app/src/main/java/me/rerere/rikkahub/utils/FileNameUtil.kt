package me.rerere.rikkahub.utils

/**
 * Makes a string safe for use as a single file name segment.
 * Model display names / IDs like "wavespeed-ai/flux-dev" would otherwise
 * create (non-existent) sub-directories or invalid file names.
 */
fun String.sanitizeFileName(maxLength: Int = 48): String =
    replace(Regex("""[/\\:*?"<>|\s]+"""), "_")
        .trim('_', '.')
        .take(maxLength)
        .ifBlank { "unnamed" }
