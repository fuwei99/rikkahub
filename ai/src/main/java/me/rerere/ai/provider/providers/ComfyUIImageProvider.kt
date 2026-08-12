package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.isString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageProvider
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.util.json
import me.rerere.ai.util.toImageDataUriOrRemote
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

private const val TAG = "ComfyUIImageProvider"

/**
 * ComfyUI 生图 Provider。
 *
 * 与云端自建 ComfyUI 通过官方 HTTP API 对接：
 *   POST   /prompt                 提交工作流（API 格式 JSON）
 *   GET    /history/{prompt_id}    轮询执行结果
 *   GET    /view?filename=...      下载输出图片
 *
 * ## 工作流模板与占位符
 * 模板是 ComfyUI 的 API 格式工作流 JSON（字符串字段里可嵌入占位符）：
 *   格式：  ¥%变量名%(变量说明)¥
 *   示例：  "text": "¥%prompt%(用户提示词)¥"
 *           "seed": "¥%seed%(随机种子)¥"
 * 模板来源优先级：模型级 imageWorkflowTemplate > provider 级 workflowTemplate > 内置 Anima 模板。
 *
 * 内置变量：
 *   prompt          用户提示词（字符串）
 *   negative_prompt 负面提示词（默认质量标签串，可在模型自定义参数中覆盖）
 *   seed            随机种子（默认随机，可在模型自定义参数中固定）
 *   width / height  输出尺寸（由调用方 size 解析，如 1024x1536）
 *   num_images / batch_size  生成张数
 *   steps / cfg     采样步数 / CFG（默认 30 / 4，可在模型自定义参数中覆盖）
 *   image / image_1..n  参考图 data URI（仅图生图模板使用）
 *
 * 模型「自定义参数」（imageParameters，key = 变量名，defaultValue = 值）会作为自定义变量
 * 自动注入模板，实现 "改模板适配任意节点流 + 改参数适配任意变量"。
 */
class ComfyUIImageProvider(
    private val client: OkHttpClient,
) : ImageProvider<ImageProviderSetting.ComfyUI> {

    /** 占位符：¥%name%(description)¥ */
    private val placeholderRegex = Regex("¥%([\\w-]+)%\\(([^)]*)\\)¥")

    /** 默认负面提示词（质量标签），未配置 negative_prompt 变量时使用。 */
    private val defaultNegativePrompt =
        "worst quality, low quality, score_1, score_2, score_3, blurry, jpeg artifacts, lowres, bad anatomy, bad hands"

    /** 内置 Anima 文生图模板（Qwen-Image 架构，整合版 checkpoint + turbo LoRA 开关）。 */
    private val defaultTemplate: String = "{"46":{"inputs":{"filename_prefix":"Anima","images":["90:73",0]},"class_type":"SaveImage","_meta":{"title":"保存图像"}},"90:71":{"inputs":{"clip_name":"anima_clip.safetensors","type":"stable_diffusion","device":"default"},"class_type":"CLIPLoader","_meta":{"title":"加载CLIP"}},"90:72":{"inputs":{"vae_name":"anima_vae.safetensors"},"class_type":"VAELoader","_meta":{"title":"加载VAE"}},"90:73":{"inputs":{"samples":["90:76",0],"vae":["90:72",0]},"class_type":"VAEDecode","_meta":{"title":"VAE解码"}},"90:74":{"inputs":{"width":"¥%width%(图像宽度)¥","height":"¥%height%(图像高度)¥","batch_size":"¥%num_images%(生成数量)¥"},"class_type":"EmptyLatentImage","_meta":{"title":"空Latent图像"}},"90:75":{"inputs":{"text":"¥%negative_prompt%(负面提示词，默认已含质量标签)¥","clip":["90:71",0]},"class_type":"CLIPTextEncode","_meta":{"title":"CLIP Text Encode (Negative Prompt)"}},"90:76":{"inputs":{"seed":"¥%seed%(随机种子)¥","steps":["90:85",0],"cfg":["90:87",0],"sampler_name":"euler","scheduler":"simple","denoise":1,"model":["90:84",0],"positive":["90:77",0],"negative":["90:75",0],"latent_image":["90:74",0]},"class_type":"KSampler","_meta":{"title":"K采样器"}},"90:77":{"inputs":{"text":"¥%prompt%(用户提示词，例如：1girl, anime style, masterpiece)¥","clip":["90:71",0]},"class_type":"CLIPTextEncode","_meta":{"title":"CLIP Text Encode (Positive Prompt)"}},"90:78":{"inputs":{"ckpt_name":"anima_model.safetensors"},"class_type":"CheckpointLoaderSimple","_meta":{"title":"加载Checkpoint(整合版)"}},"90:79":{"inputs":{"value":"¥%steps%(采样步数)¥"},"class_type":"PrimitiveInt","_meta":{"title":"Int (Steps)"}},"90:81":{"inputs":{"value":8},"class_type":"PrimitiveInt","_meta":{"title":"Int (Steps)"}},"90:83":{"inputs":{"lora_name":"anima-turbo-lora-v0.2/anima-turbo-lora-v0.2.safetensors","strength_model":1,"model":["90:78",0]},"class_type":"LoraLoaderModelOnly","_meta":{"title":"LoRA加载器（仅模型）"}},"90:84":{"inputs":{"switch":["90:89",0],"on_false":["90:78",0],"on_true":["90:83",0]},"class_type":"ComfySwitchNode","_meta":{"title":"切换"}},"90:85":{"inputs":{"switch":["90:89",0],"on_false":["90:79",0],"on_true":["90:81",0]},"class_type":"ComfySwitchNode","_meta":{"title":"切换"}},"90:86":{"inputs":{"value":"¥%cfg%(CFG 引导强度)¥"},"class_type":"PrimitiveFloat","_meta":{"title":"Float (CFG)"}},"90:87":{"inputs":{"switch":["90:89",0],"on_false":["90:86",0],"on_true":["90:88",0]},"class_type":"ComfySwitchNode","_meta":{"title":"切换"}},"90:88":{"inputs":{"value":1},"class_type":"PrimitiveFloat","_meta":{"title":"Float (CFG)"}},"90:89":{"inputs":{"value":"¥%turbo%(turbo极速模式，true=8步/CFG1+极速LoRA)"¥},"class_type":"PrimitiveBoolean","_meta":{"title":"布尔值"}}}"

    override suspend fun generateImage(
        providerSetting: ImageProviderSetting.ComfyUI,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val variables = buildVariables(
            model = params.model,
            prompt = params.prompt,
            size = params.size,
            numOfImages = params.numOfImages,
            edit = false,
            referenceImages = emptyList(),
        )
        val workflow = resolveTemplateWorkflow(providerSetting, params.model, variables)
        val images = submitAndPoll(providerSetting, workflow)
        images.forEach { item -> emit(item) }
    }

    override suspend fun editImage(
        providerSetting: ImageProviderSetting.ComfyUI,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        val refs = params.images.map { it.toImageDataUriOrRemote() }
        val variables = buildVariables(
            model = params.model,
            prompt = params.prompt,
            size = params.size,
            numOfImages = params.numOfImages,
            edit = true,
            referenceImages = refs,
        )
        val workflow = resolveTemplateWorkflow(providerSetting, params.model, variables)
        val images = submitAndPoll(providerSetting, workflow)
        images.forEach { item -> emit(item) }
    }

    /** 组装占位符变量表：内置变量 -> 模型自定义参数覆盖。 */
    private fun buildVariables(
        model: me.rerere.ai.provider.Model,
        prompt: String,
        size: String,
        numOfImages: Int,
        edit: Boolean,
        referenceImages: List<String>,
    ): Map<String, JsonElement> {
        val (w, h) = parseSize(size)
        val vars = mutableMapOf<String, JsonElement>()
        vars["prompt"] = JsonPrimitive(prompt)
        vars["negative_prompt"] = JsonPrimitive(defaultNegativePrompt)
        vars["seed"] = JsonPrimitive(Random.nextLong())
        vars["width"] = JsonPrimitive(w)
        vars["height"] = JsonPrimitive(h)
        vars["num_images"] = JsonPrimitive(params.numOfImages)
        vars["batch_size"] = JsonPrimitive(params.numOfImages)
        vars["steps"] = JsonPrimitive(30)
        vars["cfg"] = JsonPrimitive(4)
        vars["turbo"] = JsonPrimitive(false)
        if (edit) {
            vars["image"] = JsonPrimitive(referenceImages.firstOrNull() ?: "")
            referenceImages.forEachIndexed { i, uri -> vars["image_${i + 1}"] = JsonPrimitive(uri) }
        }
        // 模型自定义参数（变量）：覆盖内置值；prompt 不允许被覆盖
        model.imageParameters.forEach { p ->
            if (p.key.isNotBlank() && p.key != "prompt") {
                p.defaultValue?.let { vars[p.key] = it }
            }
        }
        return vars
    }

    /** 选择模板并替换占位符，返回可直接提交的 API 工作流 JSON。 */
    private fun resolveTemplateWorkflow(
        providerSetting: ImageProviderSetting.ComfyUI,
        model: me.rerere.ai.provider.Model,
        variables: Map<String, JsonElement>,
    ): JsonObject {
        val template = model.imageWorkflowTemplate
            .takeIf { it.isNotBlank() }
            ?: providerSetting.workflowTemplate
            ?.takeIf { it.isNotBlank() }
            ?: defaultTemplate
        val root = try {
            json.parseToJsonElement(template)
        } catch (e: Exception) {
            throw IllegalArgumentException("ComfyUI 工作流模板不是合法 JSON：${e.message}")
        }
        return replacePlaceholders(root, variables).jsonObject
    }

    /** 递归替换占位符；整串恰好等于一个占位符且值为数字时保持数字类型。 */
    private fun replacePlaceholders(element: JsonElement, vars: Map<String, JsonElement>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> replacePlaceholders(v, vars) })
        is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.JsonArray(element.map { replacePlaceholders(it, vars) })
        is JsonPrimitive -> {
            val text = element.contentOrNull ?: return element
            val matches = placeholderRegex.findAll(text).toList()
            if (matches.isEmpty()) return element
            // 整串恰好一个占位符：直接换成变量（数字保持数字）
            if (matches.size == 1 && matches[0].range.first == 0 && matches[0].range.last == text.length - 1) {
                val name = matches[0].groupValues[1]
                return vars[name] ?: element
            }
            // 部分匹配：按字符串拼接
            var result = text
            matches.forEach { m ->
                val name = m.groupValues[1]
                val v = vars[name] ?: return@forEach
                val valueStr = when {
                    v is JsonPrimitive && v.isString -> v.content
                    v is JsonPrimitive -> v.content
                    else -> v.toString()
                }
                result = result.replace(m.value, valueStr)
            }
            JsonPrimitive(result)
        }
        else -> element
    }

    /** 解析 "1024x1536" / "1024*1536" 尺寸；解析失败回退 1024x1024。 */
    private fun parseSize(size: String): Pair<Int, Int> {
        val normalized = size.trim().lowercase().replace('*', 'x')
        val parts = normalized.split('x')
        if (parts.size == 2) {
            val w = parts[0].toIntOrNull()
            val h = parts[1].toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) return w to h
        }
        return 1024 to 1024
    }

    /** 提交工作流并轮询结果，返回下载后的图片（base64）。 */
    private suspend fun submitAndPoll(
        providerSetting: ImageProviderSetting.ComfyUI,
        workflow: JsonObject,
    ): List<ImageGenerationItem> {
        val base = providerSetting.baseUrl.trimEnd('/')
        require(base.isNotBlank()) { "ComfyUI Base URL 未配置" }

        val payload = json.encodeToString(
            buildJsonObject {
                put("prompt", workflow)
                put("client_id", "rikkahub-" + UUID.randomUUID().toString().take(8))
            }
        )
        val submitReq = Request.Builder()
            .url("$base/prompt")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        Log.i(TAG, "submit workflow to $base")
        val submitResp = withContext(Dispatchers.IO) { client.newCall(submitReq).await() }
        val submitBody = submitResp.body.string()
        if (!submitResp.isSuccessful) {
            throw IllegalStateException("ComfyUI 提交失败 (HTTP ${submitResp.code}): $submitBody")
        }
        val promptId = json.parseToJsonElement(submitBody).jsonObject["prompt_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("ComfyUI 响应缺少 prompt_id: $submitBody")
        Log.i(TAG, "prompt_id = $promptId")

        // 轮询 /history/{prompt_id}
        val deadlineMs = System.currentTimeMillis() + providerSetting.imageTimeoutSec * 1000L
        var pollDelayMs = 1500L
        while (System.currentTimeMillis() < deadlineMs) {
            delay(pollDelayMs)
            pollDelayMs = (pollDelayMs * 1.5).toLong().coerceAtMost(5000L)

            val histReq = Request.Builder().url("$base/history/$promptId").get().build()
            val histResp = withContext(Dispatchers.IO) { client.newCall(histReq).await() }
            val histBody = histResp.body.string()
            if (!histResp.isSuccessful) continue

            val history = json.parseToJsonElement(histBody).jsonObject[promptId]?.jsonObject ?: continue
            val status = history["status"]?.jsonObject
            val statusStr = status?.get("status_str")?.jsonPrimitive?.contentOrNull
            if (statusStr == "error") {
                val msgs = status["messages"]?.jsonArray?.joinToString("; ") { it.toString() }
                throw IllegalStateException("ComfyUI 执行出错: $msgs")
            }
            val outputs = history["outputs"]?.jsonObject ?: continue
            val images = outputs.values.mapNotNull { out ->
                (out.jsonObject["images"]?.jsonArray ?: return@mapNotNull null)
                    .firstOrNull()?.jsonObject
            }
            if (images.isNotEmpty()) {
                Log.i(TAG, "got ${images.size} image(s)")
                return images.mapNotNull { imgObj ->
                    val filename = imgObj["filename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val type = imgObj["type"]?.jsonPrimitive?.contentOrNull ?: "output"
                    val subfolder = imgObj["subfolder"]?.jsonPrimitive?.contentOrNull ?: ""
                    val viewUrl = "$base/view?filename=$filename&type=$type&subfolder=$subfolder"
                    val viewReq = Request.Builder().url(viewUrl).get().build()
                    val viewResp = withContext(Dispatchers.IO) { client.newCall(viewReq).await() }
                    if (!viewResp.isSuccessful) {
                        throw IllegalStateException("ComfyUI 下载图片失败 (HTTP ${viewResp.code})")
                    }
                    val bytes = viewResp.body.bytes()
                    ImageGenerationItem(
                        data = Base64.getEncoder().encodeToString(bytes),
                        mimeType = mimeTypeFor(filename),
                    )
                }
            }
        }
        throw IllegalStateException("ComfyUI 生成超时（${providerSetting.imageTimeoutSec}s）")
    }

    private fun mimeTypeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }
}
