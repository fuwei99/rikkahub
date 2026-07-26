package me.rerere.ai.util

import java.io.File
import java.net.URI
import java.net.URLConnection
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Normalizes an arbitrary image source for sending to an image API:
 * - data URIs and http(s) URLs pass through unchanged;
 * - file:// URIs and local paths are read and converted to a data URI at request time.
 *
 * Shared by all image providers so that every entry point (image page, chat tool)
 * accepts the same source formats.
 */
@OptIn(ExperimentalEncodingApi::class)
fun String.toImageDataUriOrRemote(): String {
    if (startsWith("data:") || startsWith("http://") || startsWith("https://")) {
        return this
    }
    val path = if (startsWith("file:")) URI(this).path else this
    val file = File(path)
    require(file.exists() && file.isFile) { "Reference image does not exist: $this" }
    val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "image/png"
    return "data:$mimeType;base64,${Base64.encode(file.readBytes())}"
}
