package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
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
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
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

object DocumentAsPromptTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val providerSetting = ctx.model.findProvider(ctx.settings.providers)
        if (Modality.FILE in ctx.model.inputModalities && providerSetting is ProviderSetting.Google) {
            return messages
        }
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                ctx.processingStatus.value = "正在解析文件 ${document.fileName}..."
                                val content = readDocumentContent(ctx, document)
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                            ctx.processingStatus.value = null
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String = PdfParser.parserPdf(file)

    private fun parseDocxAsText(file: File): String = DocxParser.parse(file)

    private fun parsePptxAsText(file: File): String = PptxParser.parse(file)

    private fun parseEpubAsText(file: File): String = EpubParser.parse(file)

    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private suspend fun readDocumentContent(ctx: TransformerContext, document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }

        val cacheKey = document.parseCacheKey(file)
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
        return runCatching {
            ctx.processingStatus.value = "正在使用 ${mineru.displayName} 解析 ${document.fileName}..."
            parseWithMinerU(ctx, document, file, mineru)
        }.getOrElse { mineruError ->
            localResult.getOrElse { "[ERROR, MinerU failed: ${mineruError.message ?: mineruError}]" }
        }
    }


    private fun UIMessagePart.Document.parseCacheKey(file: File): String {
        val stableSource = metadata?.get("r2_ref")?.jsonPrimitive?.contentOrNull
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
            else -> file.readText()
        }
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
        val r2Ref = document.metadata?.get("r2_ref")?.jsonPrimitive?.contentOrNull
            ?.let { R2Ref.parse(it) }
        if (r2Ref != null) {
            val r2MediaStore = get<R2MediaStore>()
            val presignedUrl = r2MediaStore.presign(r2Ref).getOrNull()
            if (presignedUrl != null) {
                val urlTaskId = runCatching {
                    createMinerUTaskByUrl(client, baseUrl, document, mineru, presignedUrl)
                }.getOrNull()
                if (urlTaskId != null) return urlTaskId
            }
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
}
