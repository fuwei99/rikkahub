package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.await
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.datastore.FileProcessingServiceOptions
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.selectedMinerUFileProcessingService
import me.rerere.rikkahub.data.files.AssetReferences
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.security.MessageDigest

/**
 * 把附件文档转成 `<UploadFile>` 提示文本, 供不支持 FILE 模态的模型使用。
 *
 * 取文件内容一律走 [AssetResolver]: Document.url 在 asset 化之后可能是
 * `asset://managed-files/<uuid>`、R2 预签名 https、file:// 甚至纯路径,
 * 直接 `toFile()` 只认 file://, 其余全部失败 —— 这曾导致连 txt 都读不出来。
 */
object DocumentAsPromptTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val providerSetting = ctx.model.findProvider(ctx.settings.providers)
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        val pending = documents.filterNot { it.isNativelySupported(ctx, providerSetting) }
                        if (pending.isEmpty()) return@apply
                        pending.forEach { document ->
                            ctx.processingStatus.value = "正在解析文件 ${document.fileName}..."
                            val content = readDocumentContent(ctx, document)
                            val prompt = """
                              <UploadFile name="${document.fileName}">
                              ```
                              $content
                              ```
                              </UploadFile>
                              """.trimMargin()
                            add(0, UIMessagePart.Text(prompt))
                            // 已经内联成文本, 附件本身没必要再发一遍(尤其它对该模型本来就不合法)
                            remove(document)
                        }
                        ctx.processingStatus.value = null
                    }
                )
            }
        }
    }

    /**
     * 该文档能否原样交给 provider。
     *
     * 只有 Google 系真正吃 `inlineData` + 任意 mime; 其余 provider 的消息构造里
     * Document 干脆被丢掉(见 ChatCompletionsAPI.addNonAssistantMessage 的 `else -> {}`),
     * 所以除 Google 外一律得转文本, 否则附件等于凭空消失。
     *
     * Google 侧也不是全都行: Word/PPT/Excel 不在 Gemini 支持列表里, 必须本地解析,
     * 而 PDF / 纯文本 / 代码文件是原生支持的, 直接透传给模型质量更好。
     */
    private fun UIMessagePart.Document.isNativelySupported(
        ctx: TransformerContext,
        providerSetting: ProviderSetting?,
    ): Boolean {
        if (Modality.FILE !in ctx.model.inputModalities) return false
        if (providerSetting !is ProviderSetting.Google) return false
        return isGeminiNativeMime()
    }

    /** Gemini 原生可解码的附件类型 */
    private fun UIMessagePart.Document.isGeminiNativeMime(): Boolean {
        val lower = mime.lowercase()
        if (lower == "application/pdf") return true
        if (lower.startsWith("image/") || lower.startsWith("audio/") || lower.startsWith("video/")) return true
        // 只认真正的 text/*: .py / .kt 这类系统常给 application/octet-stream,
        // 原样发过去会被 Gemini 拒, 走本地读文本反而稳。
        return lower.startsWith("text/")
    }

    private fun parsePdfAsText(file: File): String = PdfParser.parserPdf(file)

    private fun parseDocxAsText(file: File): String = DocxParser.parse(file)

    private fun parsePptxAsText(file: File): String = PptxParser.parse(file)

    private fun parseEpubAsText(file: File): String = EpubParser.parse(file)

    private suspend fun readDocumentContent(ctx: TransformerContext, document: UIMessagePart.Document): String {
        val assetResolver = get<AssetResolver>()
        val assetIdHint = document.metadata?.get(AssetResolver.METADATA_ASSET_ID)?.jsonPrimitive?.contentOrNull
        val file = runCatching { assetResolver.localFileFor(document.url, assetIdHint) }.getOrNull()

        if (file == null) {
            // 没有本地文件不代表读不到: content:// / http(s) / data: 都还能取字节。
            val bytes = runCatching { assetResolver.readBytes(document.url, assetIdHint) }.getOrNull()
                ?: return "[ERROR, file not readable: ${document.fileName}]"
            return document.decodeBytes(bytes)
        }

        val cacheKey = document.parseCacheKey(file, assetIdHint)
        readParseCache(cacheKey)?.let { return it }

        val content = parseDocumentContent(ctx, document, file)
        if (!content.startsWith("[ERROR,")) {
            writeParseCache(cacheKey, content)
        }
        return content
    }

    private suspend fun parseDocumentContent(
        ctx: TransformerContext,
        document: UIMessagePart.Document,
        file: File,
    ): String {
        val localResult = runCatching { readDocumentContentLocally(document, file) }
        val mineru = ctx.settings.selectedMinerUFileProcessingService()
        if (mineru == null || !document.isMinerUSupported()) {
            return localResult.getOrElse { "[ERROR, failed to read file: ${document.fileName}]" }
        }
        // 纯文本 / 代码文件没有解析成本, 不该为它去等一次网络往返。
        localResult.getOrNull()?.takeIf { document.isPlainTextLike() }?.let { return it }
        return runCatching {
            ctx.processingStatus.value = "正在使用 ${mineru.displayName} 解析 ${document.fileName}..."
            parseWithMinerU(ctx, document, file, mineru)
        }.getOrElse { mineruError ->
            localResult.getOrElse { "[ERROR, MinerU failed: ${mineruError.message ?: mineruError}]" }
        }
    }


    private fun UIMessagePart.Document.parseCacheKey(file: File, assetIdHint: String?): String {
        // asset id 是唯一跨设备/跨轮稳定的标识, 优先用它做缓存键。
        val stableSource = assetIdHint?.let { AssetReferences.assetId(it) }
            ?: AssetReferences.assetId(url)
            ?: metadata?.get("r2_ref")?.jsonPrimitive?.contentOrNull
            ?: "file://${file.absolutePath}:${file.length()}:${file.lastModified()}"
        return sha256Hex("$stableSource|$fileName|$mime")
    }

    private fun parseCacheDir(): File =
        File(get<Context>().filesDir, "document_parse_cache").apply { mkdirs() }

    private fun readParseCache(key: String): String? = runCatching {
        File(parseCacheDir(), "$key.md")
            .takeIf { it.exists() && it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeParseCache(key: String, content: String) {
        runCatching {
            File(parseCacheDir(), "$key.md").writeText(content, Charsets.UTF_8)
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun readDocumentContentLocally(document: UIMessagePart.Document, file: File): String {
        return when (document.mime) {
            "application/pdf" -> parsePdfAsText(file)
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
            "application/epub+zip" -> parseEpubAsText(file)
            else -> document.decodeBytes(file.readBytes())
        }
    }

    /**
     * 纯文本 / 代码类文件: 不需要任何"解析", 直接按文本读出来即可。
     *
     * 判定不能只看 mime —— 系统给 .py / .kt / .ts 之类经常返回
     * `application/octet-stream` 或空, 只认 mime 会把代码文件误判成二进制。
     */
    private fun UIMessagePart.Document.isPlainTextLike(): Boolean {
        val lower = mime.lowercase()
        if (lower.startsWith("text/")) return true
        if (lower in TEXTUAL_MIME_TYPES) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in TEXTUAL_EXTENSIONS
    }

    /**
     * 把字节按文本解码。二进制文件会得到一堆替换字符, 因此先做一次可打印性检查,
     * 免得把整个 zip 塞进 prompt 烧 token。
     */
    private fun UIMessagePart.Document.decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "[EMPTY FILE: $fileName]"
        val text = bytes.toString(Charsets.UTF_8)
        if (isPlainTextLike() || text.looksTextual()) return text
        return "[ERROR, unsupported binary file: $fileName ($mime)]"
    }

    /** NUL 字节 + 替换字符占比是判定二进制最省事且够准的启发式 */
    private fun String.looksTextual(): Boolean {
        if (isEmpty()) return false
        val sample = take(4096)
        if (sample.contains('\u0000')) return false
        val broken = sample.count { it == '\uFFFD' }
        return broken * 100 / sample.length < 5
    }

    private fun UIMessagePart.Document.isMinerUSupported(): Boolean {
        val lowerName = fileName.lowercase()
        return mime == "application/pdf" ||
            mime.startsWith("image/") ||
            lowerName.endsWith(".docx") ||
            lowerName.endsWith(".pptx") ||
            lowerName.endsWith(".xlsx")
    }

    private suspend fun parseWithMinerU(
        ctx: TransformerContext,
        document: UIMessagePart.Document,
        file: File,
        mineru: FileProcessingServiceOptions.MinerU,
    ): String {
        val client = get<OkHttpClient>()
        val baseUrl = mineru.baseUrl.trimEnd('/')
        val taskId = createMinerUTask(ctx, client, baseUrl, document, file, mineru)

        ctx.processingStatus.value = "正在等待 MinerU 解析结果..."
        val markdownUrl = pollMinerUResult(client, baseUrl, taskId)
        val markdownRequest = Request.Builder().url(markdownUrl).get().build()
        val markdownResponse = client.newCall(markdownRequest).await()
        if (!markdownResponse.isSuccessful) {
            error("MinerU markdown download failed: ${markdownResponse.code} ${markdownResponse.body.string()}")
        }
        return markdownResponse.body.string()
    }


    private suspend fun createMinerUTask(
        ctx: TransformerContext,
        client: OkHttpClient,
        baseUrl: String,
        document: UIMessagePart.Document,
        file: File,
        mineru: FileProcessingServiceOptions.MinerU,
    ): String {
        // 走 asset id 拿预签名 URL, 能让 MinerU 直接从云端拉, 省一次上传。
        val assetId = document.metadata?.get(AssetResolver.METADATA_ASSET_ID)?.jsonPrimitive?.contentOrNull
            ?.let { AssetReferences.assetId(it) }
            ?: AssetReferences.assetId(document.url)
        val presignedUrl = assetId?.let {
            runCatching { get<AssetResolver>().presignedUrlFor(it) }.getOrNull()
        }
        if (presignedUrl != null) {
            val urlTaskId = runCatching {
                createMinerUTaskByUrl(client, baseUrl, document, mineru, presignedUrl)
            }.getOrNull()
            if (urlTaskId != null) return urlTaskId
        }
        return createMinerUTaskByUpload(ctx, client, baseUrl, document, file, mineru)
    }

    private suspend fun createMinerUTaskByUrl(
        client: OkHttpClient,
        baseUrl: String,
        document: UIMessagePart.Document,
        mineru: FileProcessingServiceOptions.MinerU,
        url: String,
    ): String {
        val createBody = JsonInstant.encodeToString(
            buildJsonObject {
                put("url", url)
                put("file_name", document.fileName)
                put("language", mineru.language)
                put("enable_table", mineru.enableTable)
                put("enable_formula", mineru.enableFormula)
                put("is_ocr", mineru.ocr)
            }
        )
        val createRequest = Request.Builder()
            .url("$baseUrl/parse/url")
            .post(createBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()
        val createResponse = client.newCall(createRequest).await()
        val createResponseText = createResponse.body.string()
        if (!createResponse.isSuccessful) {
            error("MinerU URL task failed: ${createResponse.code} $createResponseText")
        }
        val createData = JsonInstant.parseToJsonElement(createResponseText).jsonObject["data"]?.jsonObject
            ?: error("MinerU URL task response has no data")
        return createData["task_id"]?.jsonPrimitive?.contentOrNull
            ?: error("MinerU URL task response has no task_id")
    }

    private suspend fun createMinerUTaskByUpload(
        ctx: TransformerContext,
        client: OkHttpClient,
        baseUrl: String,
        document: UIMessagePart.Document,
        file: File,
        mineru: FileProcessingServiceOptions.MinerU,
    ): String {
        val createBody = JsonInstant.encodeToString(
            buildJsonObject {
                put("file_name", document.fileName)
                put("language", mineru.language)
                put("enable_table", mineru.enableTable)
                put("enable_formula", mineru.enableFormula)
                put("is_ocr", mineru.ocr)
            }
        )
        val createRequest = Request.Builder()
            .url("$baseUrl/parse/file")
            .post(createBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()
        val createResponse = client.newCall(createRequest).await()
        val createResponseText = createResponse.body.string()
        if (!createResponse.isSuccessful) {
            error("MinerU create task failed: ${createResponse.code} $createResponseText")
        }
        val createData = JsonInstant.parseToJsonElement(createResponseText).jsonObject["data"]?.jsonObject
            ?: error("MinerU create task response has no data")
        val taskId = createData["task_id"]?.jsonPrimitive?.contentOrNull
            ?: error("MinerU create task response has no task_id")
        val uploadUrl = createData["file_url"]?.jsonPrimitive?.contentOrNull
            ?: error("MinerU create task response has no file_url")

        ctx.processingStatus.value = "正在上传文件到 MinerU..."
        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .put(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val uploadResponse = client.newCall(uploadRequest).await()
        if (!uploadResponse.isSuccessful) {
            error("MinerU upload failed: ${uploadResponse.code} ${uploadResponse.body.string()}")
        }
        return taskId
    }

    private suspend fun pollMinerUResult(client: OkHttpClient, baseUrl: String, taskId: String): String {
        repeat(100) {
            val response = client.newCall(
                Request.Builder().url("$baseUrl/parse/$taskId").get().build()
            ).await()
            val bodyText = response.body.string()
            if (!response.isSuccessful) error("MinerU query failed: ${response.code} $bodyText")
            val data = JsonInstant.parseToJsonElement(bodyText).jsonObject["data"]?.jsonObject
                ?: error("MinerU query response has no data")
            when (val state = data["state"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
                "done" -> return data["markdown_url"]?.jsonPrimitive?.contentOrNull
                    ?: error("MinerU finished but markdown_url is empty")
                "failed" -> error(data["err_msg"]?.jsonPrimitive?.contentOrNull ?: "MinerU failed")
                "waiting-file", "uploading", "pending", "running" -> delay(3000)
                else -> delay(3000)
            }
        }
        error("MinerU parse timeout")
    }

    /** 语义上是文本但 mime 不以 text/ 开头的类型 */
    private val TEXTUAL_MIME_TYPES = setOf(
        "application/json",
        "application/ld+json",
        "application/javascript",
        "application/x-javascript",
        "application/typescript",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/x-sh",
        "application/x-shellscript",
        "application/x-python",
        "application/x-python-code",
        "application/graphql",
        "application/sql",
        "application/x-sql",
        "application/csv",
    )

    /**
     * 扩展名兜底表。与 [me.rerere.rikkahub.utils.isAllowedFileType] 的白名单保持一致:
     * 那边允许上传, 这边就必须能读出来, 否则用户会看到"能选中但发不出去"。
     */
    private val TEXTUAL_EXTENSIONS = setOf(
        "txt", "md", "markdown", "mdx", "csv", "tsv", "log", "json", "jsonl", "json5",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "html", "htm", "css", "scss", "sass",
        "less", "vue", "svelte", "xml", "svg", "yml", "yaml", "toml", "ini", "cfg",
        "conf", "env", "properties", "gradle", "kts", "py", "pyi", "rb", "lua", "sql",
        "java", "kt", "dart", "php", "swift", "go", "rs", "cs", "c", "h", "cpp", "cc",
        "cxx", "hpp", "hh", "hxx", "m", "mm", "scala", "clj", "ex", "exs", "erl", "hs",
        "pl", "r", "jl", "zig", "nim", "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh",
        "fish", "proto", "graphql", "gql", "gitignore", "dockerfile", "makefile", "diff", "patch",
    )
}
