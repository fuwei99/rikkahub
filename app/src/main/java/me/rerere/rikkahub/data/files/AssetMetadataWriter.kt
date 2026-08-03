package me.rerere.rikkahub.data.files

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * 把资产元数据（OCR 描述 / 中英文名 / 标签）物理写进图片文件，
 * 以及计算「对元数据免疫」的内容摘要。
 *
 * 为什么需要两套摘要：
 * 往图里写元数据会改变整字节 sha256，于是同一张图在下次入库时被当成新文件，
 * 反复落盘 + 反复上传 R2。[normalizedSha256] 在算之前先剥掉可变的元数据段
 * （PNG 的 tEXt/iTXt/zTXt、JPEG 的 APPn/COM），只对真正的像素数据取摘要，
 * 写多少次元数据它都不动。整字节 sha256 保持原样，历史 R2 对象仍靠它寻址。
 *
 * 刻意手写 PNG chunk / JPEG segment 解析而不用 ExifInterface：
 * ExifInterface 只在 JPEG 上能写，PNG 一律拒绝，而本项目生图产物大半是 PNG；
 * 而且它会顺手重排/丢弃它不认识的段，等于把 normalizedSha256 的前提破坏掉。
 */
object AssetMetadataWriter {
    private const val TAG = "AssetMetadataWriter"

    /** PNG iTXt 关键字前缀，也是「本 App 写的元数据」的识别标记 */
    private const val PNG_KEY_DESCRIPTION = "RikkaHub:Description"
    private const val PNG_KEY_NAME_ZH = "RikkaHub:NameZh"
    private const val PNG_KEY_NAME_EN = "RikkaHub:NameEn"
    private const val PNG_KEY_TAGS = "RikkaHub:Tags"

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** XMP APP1 段的固定命名空间头（含结尾 0x00） */
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

    /** JPEG APP1 段总长上限 64KB，描述过长会写不进去，先截断 */
    private const val XMP_MAX_DESCRIPTION = 8000

    data class Metadata(
        val description: String? = null,
        val nameZh: String? = null,
        val nameEn: String? = null,
        val tags: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = description.isNullOrBlank() && nameZh.isNullOrBlank() &&
                nameEn.isNullOrBlank() && tags.isEmpty()
    }

    // ------------------------------------------------------------------
    // 内容摘要
    // ------------------------------------------------------------------

    /**
     * 剥掉元数据段后的 SHA-256。非图片 / 解析失败时退化为整字节摘要，
     * 保证这个函数永远有返回值 —— 去重列不能因为格式冷门就留空。
     */
    fun normalizedSha256(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val payload = runCatching { stripMetadata(bytes) }.getOrNull() ?: bytes
        return sha256Hex(payload)
    }

    fun normalizedSha256(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()?.let { normalizedSha256(it) }

    /** 剥离可变元数据段，返回用于摘要的字节。不是图片就原样返回。 */
    fun stripMetadata(bytes: ByteArray): ByteArray = when {
        isPng(bytes) -> stripPngTextChunks(bytes)
        isJpeg(bytes) -> stripJpegAppSegments(bytes)
        else -> bytes
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 就地把元数据写进图片文件。
     *
     * @return 写入成功返回 true（文件字节已变化）；不支持的格式 / 无内容可写 / 失败返回 false。
     */
    fun write(file: File, metadata: Metadata): Boolean {
        if (!file.isFile || metadata.isEmpty) return false
        val original = runCatching { file.readBytes() }.getOrNull() ?: return false
        val updated = runCatching {
            when {
                isPng(original) -> writePngMetadata(original, metadata)
                isJpeg(original) -> writeJpegXmp(original, metadata)
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "write: failed for ${file.name}", it)
        }.getOrNull() ?: return false

        if (updated.contentEquals(original)) return false
        return runCatching {
            // 先写临时文件再 rename：中途挂掉不会留下半截图片
            val temp = File(file.parentFile, "${file.name}.meta_tmp")
            temp.writeBytes(updated)
            if (!temp.renameTo(file)) {
                file.writeBytes(updated)
                temp.delete()
            }
            true
        }.onFailure {
            Log.w(TAG, "write: replace failed for ${file.name}", it)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // PNG
    // ------------------------------------------------------------------

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private fun isTextChunk(type: String): Boolean = type == "tEXt" || type == "iTXt" || type == "zTXt"

    /**
     * PNG chunk 结构：[len:4][type:4][data:len][crc:4]，签名后依次排列。
     * 这里把所有文本 chunk 丢掉，其余原样保留。
     */
    private fun stripPngTextChunks(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 8)
        var pos = 8
        var stripped = false
        while (pos + 8 <= bytes.size) {
            val length = readInt(bytes, pos)
            if (length < 0) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val total = 12 + length
            if (pos + total > bytes.size) break
            if (isTextChunk(type)) {
                stripped = true
            } else {
                out.write(bytes, pos, total)
            }
            pos += total
            if (type == "IEND") break
        }
        // 尾部残留（某些工具会在 IEND 后追加垃圾）不参与摘要，正好也算规范化
        return if (stripped) out.toByteArray() else bytes
    }

    private fun writePngMetadata(bytes: ByteArray, metadata: Metadata): ByteArray? {
        val head = ByteArrayOutputStream(bytes.size)
        head.write(bytes, 0, 8)
        var pos = 8
        var sawIend = false
        while (pos + 8 <= bytes.size) {
            val length = readInt(bytes, pos)
            if (length < 0) return null
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val total = 12 + length
            if (pos + total > bytes.size) return null
            when {
                // 覆盖写：先删掉本 App 之前写的同名 iTXt，避免每次 OCR 都追加一份
                isTextChunk(type) && isOwnTextChunk(bytes, pos, length) -> Unit
                type == "IEND" -> {
                    metadata.toPairs().forEach { (key, value) ->
                        head.write(buildItxtChunk(key, value))
                    }
                    head.write(bytes, pos, total)
                    sawIend = true
                }

                else -> head.write(bytes, pos, total)
            }
            pos += total
            if (sawIend) break
        }
        if (!sawIend) return null
        return head.toByteArray()
    }

    private fun isOwnTextChunk(bytes: ByteArray, chunkStart: Int, length: Int): Boolean {
        val dataStart = chunkStart + 8
        val end = minOf(dataStart + length, bytes.size)
        var i = dataStart
        while (i < end && bytes[i] != 0.toByte()) i++
        val keyword = String(bytes, dataStart, i - dataStart, Charsets.ISO_8859_1)
        return keyword.startsWith("RikkaHub:")
    }

    /**
     * iTXt chunk 数据段：keyword\0 compressionFlag(0) compressionMethod(0) langTag\0 translatedKeyword\0 text(UTF-8)
     * 不压缩，图省事也方便别的工具直接读。
     */
    private fun buildItxtChunk(keyword: String, text: String): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(keyword.toByteArray(Charsets.ISO_8859_1))
        data.write(0)
        data.write(0) // compression flag: 未压缩
        data.write(0) // compression method
        data.write(0) // language tag: 空
        data.write(0) // translated keyword: 空
        data.write(text.toByteArray(Charsets.UTF_8))
        val payload = data.toByteArray()

        val chunk = ByteArrayOutputStream(payload.size + 12)
        chunk.write(intToBytes(payload.size))
        val typeAndData = ByteArrayOutputStream(payload.size + 4)
        typeAndData.write("iTXt".toByteArray(Charsets.US_ASCII))
        typeAndData.write(payload)
        val body = typeAndData.toByteArray()
        chunk.write(body)
        val crc = CRC32().apply { update(body) }.value
        chunk.write(intToBytes(crc.toInt()))
        return chunk.toByteArray()
    }

    // ------------------------------------------------------------------
    // JPEG
    // ------------------------------------------------------------------

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /**
     * JPEG segment 结构：0xFF 0xD8 之后是若干 [0xFF marker][len:2][data]，
     * 遇到 SOS(0xDA) 之后是熵编码数据，不再有长度字段，直接整段保留。
     *
     * 这里剥 APPn(0xE0..0xEF) 和 COM(0xFE)：Exif 方向、XMP、注释都在这些段里，
     * 也正是写元数据会动的地方。
     */
    private fun stripJpegAppSegments(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2)
        var pos = 2
        var stripped = false
        while (pos + 4 <= bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) break
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) { // SOS: 剩下全是图像数据
                out.write(bytes, pos, bytes.size - pos)
                pos = bytes.size
                break
            }
            if (marker == 0xD9) { // EOI
                out.write(bytes, pos, 2)
                pos += 2
                break
            }
            val length = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > bytes.size) break
            val total = 2 + length
            if (marker in 0xE0..0xEF || marker == 0xFE) {
                stripped = true
            } else {
                out.write(bytes, pos, total)
            }
            pos += total
        }
        if (pos < bytes.size && pos > 2) out.write(bytes, pos, bytes.size - pos)
        return if (stripped) out.toByteArray() else bytes
    }

    private fun writeJpegXmp(bytes: ByteArray, metadata: Metadata): ByteArray? {
        val xmpSegment = buildXmpApp1(metadata) ?: return null
        val out = ByteArrayOutputStream(bytes.size + xmpSegment.size)
        out.write(bytes, 0, 2)

        var pos = 2
        var inserted = false
        // 先把 APP0/Exif APP1 抄过去，再插入 XMP，最后原样接上其余段与图像数据
        while (pos + 4 <= bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) break
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > bytes.size) break
            val total = 2 + length
            val isXmp = marker == 0xE1 && isXmpSegment(bytes, pos, length)
            when {
                isXmp -> {
                    // 覆盖旧 XMP：不删的话读取方会看到两份互相矛盾的元数据
                    if (!inserted) {
                        out.write(xmpSegment)
                        inserted = true
                    }
                }

                // APP0(JFIF) 与 Exif APP1 原样前置：XMP 按规范排在它们之后,
                // 而且 Exif 里有方向位, 挪到 XMP 后面有解码器会读不到导致图片转向。
                marker == 0xE0 || marker == 0xE1 -> out.write(bytes, pos, total)
                else -> {
                    if (!inserted) {
                        out.write(xmpSegment)
                        inserted = true
                    }
                    out.write(bytes, pos, total)
                }
            }
            pos += total
        }
        if (!inserted) out.write(xmpSegment)
        if (pos < bytes.size) out.write(bytes, pos, bytes.size - pos)
        return out.toByteArray()
    }

    private fun isXmpSegment(bytes: ByteArray, pos: Int, length: Int): Boolean {
        val dataStart = pos + 4
        val nsBytes = XMP_NS.toByteArray(Charsets.US_ASCII)
        if (dataStart + nsBytes.size > bytes.size || length < nsBytes.size + 2) return false
        return nsBytes.indices.all { bytes[dataStart + it] == nsBytes[it] }
    }

    private fun buildXmpApp1(metadata: Metadata): ByteArray? {
        val packet = buildXmpPacket(metadata)
        val payload = ByteArrayOutputStream()
        payload.write(XMP_NS.toByteArray(Charsets.US_ASCII))
        payload.write(0)
        payload.write(packet.toByteArray(Charsets.UTF_8))
        val data = payload.toByteArray()
        val segmentLength = data.size + 2
        // 64KB 硬上限是 JPEG 段长字段的物理限制，超了只能放弃写入而不是写坏文件
        if (segmentLength > 0xFFFF) return null
        val out = ByteArrayOutputStream(segmentLength + 2)
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun buildXmpPacket(metadata: Metadata): String {
        val description = metadata.description?.take(XMP_MAX_DESCRIPTION)
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
            append(" xmlns:rikkahub=\"https://rikkahub.app/ns/1.0/\">")
            if (!description.isNullOrBlank()) {
                append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                append(description.xmlEscape())
                append("</rdf:li></rdf:Alt></dc:description>")
            }
            val title = metadata.nameZh ?: metadata.nameEn
            if (!title.isNullOrBlank()) {
                append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                append(title.xmlEscape())
                append("</rdf:li></rdf:Alt></dc:title>")
            }
            if (metadata.tags.isNotEmpty()) {
                append("<dc:subject><rdf:Bag>")
                metadata.tags.forEach { append("<rdf:li>${it.xmlEscape()}</rdf:li>") }
                append("</rdf:Bag></dc:subject>")
            }
            metadata.nameZh?.takeIf { it.isNotBlank() }
                ?.let { append("<rikkahub:nameZh>${it.xmlEscape()}</rikkahub:nameZh>") }
            metadata.nameEn?.takeIf { it.isNotBlank() }
                ?.let { append("<rikkahub:nameEn>${it.xmlEscape()}</rikkahub:nameEn>") }
            append("</rdf:Description></rdf:RDF></x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun Metadata.toPairs(): List<Pair<String, String>> = buildList {
        description?.takeIf { it.isNotBlank() }?.let { add(PNG_KEY_DESCRIPTION to it) }
        nameZh?.takeIf { it.isNotBlank() }?.let { add(PNG_KEY_NAME_ZH to it) }
        nameEn?.takeIf { it.isNotBlank() }?.let { add(PNG_KEY_NAME_EN to it) }
        if (tags.isNotEmpty()) add(PNG_KEY_TAGS to tags.joinToString(", "))
    }

    private fun String.xmlEscape(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun readInt(bytes: ByteArray, pos: Int): Int {
        if (pos + 4 > bytes.size) return -1
        val value = ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)
        return if (value < 0) -1 else value
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
