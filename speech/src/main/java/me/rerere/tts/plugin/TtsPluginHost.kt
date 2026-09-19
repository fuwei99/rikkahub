package me.rerere.tts.plugin

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.js.injectFetch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS 的 JS 插件宿主（QuickJS）。
 *
 * 设计取向与 `search/CustomJsSearchService` 保持一致：**同步模型，无事件循环，无 Promise**。
 * 插件只需实现一个同步函数：
 *
 * ```js
 * function synthesize(req) {
 *   // req = { text, vars, format, sampleRate }
 *   // return { base64: "...", format: "mp3", sampleRate: 24000 }
 * }
 * ```
 *
 * 宿主提供的全局能力：
 * - `fetch(url, options)` —— 同步 HTTP，返回 `{ status, ok, text(), json() }`（来自 common 的
 *   [injectFetch]）。注意它**只适合文本响应**，二进制会被 UTF-8 解码破坏。
 * - `fetchBinary(url, options)` —— 同步 HTTP，专门拿二进制，返回 `{ status, ok, base64(), bytes() }`。
 * - `wsConnect(url, headers)` / `ws.send()` / `ws.recv()` / `ws.close()` —— **阻塞式** WebSocket。
 *   QuickJS 没有事件循环，所以这里把 OkHttp 的回调塞进阻塞队列，`recv()` 直接 `take()`。
 *   拿不到消息时返回 `null`（超时）或 `{type:"close"}`。豆包 SAMI 那种 WS 流式 TTS 靠它跑。
 * - `vars` —— 插件配置变量（`Map<String,String>`），JSON 对象。
 * - `bytesToBase64(arr)` / `base64ToBytes(str)` —— 字节数组与 base64 互转（纯 JS polyfill）。
 * - `console.log` 走宿主日志。
 *
 * 线程模型：**必须跑在 IO 线程**（`generateSpeech` 的 flow 里就是），因为 `recv()` 会阻塞。
 */
class TtsPluginHost(private val httpClient: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    /** 一个 WS 会话：OkHttp 的 WebSocket + 阻塞消息队列。 */
    private class WsSession {
        @Volatile
        var ws: WebSocket? = null
        val queue = LinkedBlockingQueue<String>()
    }

    /**
     * 执行插件脚本并调用 `synthesize(requestJson)`。
     *
     * @param script 用户脚本全文
     * @param requestJson 传给 `synthesize` 的参数（JSON 字面量，非字符串）
     * @param varsJson `vars` 的 JSON 对象字面量
     * @return 插件返回值的 JSON 字符串
     */
    fun run(script: String, requestJson: String, varsJson: String): String {
        val context = QuickJSContext.create()
        val sessions = ConcurrentHashMap<Int, WsSession>()
        val seq = AtomicInteger(0)

        try {
            // ---- HTTP（同步 fetch，common 模块提供） ----
            context.injectFetch(httpClient)

            // ---- 二进制 HTTP ----
            context.globalObject.setProperty("__httpBinary", JSCallFunction { args ->
                val url = args[0] as? String ?: error("url is required")
                val method = (args[1] as? String ?: "GET").uppercase()
                val headersJson = args[2] as? String
                val bodyB64 = args[3] as? String

                val builder = Request.Builder().url(url)
                if (!headersJson.isNullOrBlank() && headersJson != "null") {
                    json.parseToJsonElement(headersJson).jsonObject.forEach { (k, v) ->
                        builder.addHeader(k, v.jsonPrimitive.content)
                    }
                }
                when (method) {
                    "GET" -> builder.get()
                    "HEAD" -> builder.head()
                    else -> {
                        val bytes = if (!bodyB64.isNullOrBlank()) b64Decoder.decode(bodyB64) else ByteArray(0)
                        builder.method(method, bytes.toRequestBodyRaw())
                    }
                }

                val response = httpClient.newCall(builder.build()).execute()
                response.use { resp ->
                    val bytes = resp.body.bytes()
                    json.encodeToString(
                        BinaryHttpResponseDto(
                            status = resp.code,
                            ok = resp.code in 200..299,
                            statusText = resp.message,
                            bodyBase64 = b64Encoder.encodeToString(bytes),
                        )
                    )
                }
            })

            // ---- WebSocket（阻塞式） ----
            context.globalObject.setProperty("__wsConnect", JSCallFunction { args ->
                val url = args[0] as? String ?: error("ws url is required")
                val headersJson = args[1] as? String

                val handle = seq.incrementAndGet()
                val session = WsSession()
                sessions[handle] = session

                val builder = Request.Builder().url(url)
                if (!headersJson.isNullOrBlank() && headersJson != "null") {
                    json.parseToJsonElement(headersJson).jsonObject.forEach { (k, v) ->
                        builder.addHeader(k, v.jsonPrimitive.content)
                    }
                }

                val ws = httpClient.newWebSocket(builder.build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sessions[handle]?.queue?.put("""{"type":"open"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        sessions[handle]?.queue?.put(
                            json.encodeToString(WsTextDto(type = "text", data = text))
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        sessions[handle]?.queue?.put(
                            json.encodeToString(
                                WsBinaryDto(type = "binary", data = b64Encoder.encodeToString(bytes.toByteArray()))
                            )
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        sessions[handle]?.queue?.put(
                            json.encodeToString(WsCloseDto(type = "close", code = code, reason = reason))
                        )
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        sessions[handle]?.queue?.put(
                            json.encodeToString(WsCloseDto(type = "close", code = code, reason = reason))
                        )
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        sessions[handle]?.queue?.put(
                            json.encodeToString(
                                WsErrorDto(type = "error", message = t.message ?: t.javaClass.simpleName)
                            )
                        )
                    }
                })
                session.ws = ws
                handle
            })

            context.globalObject.setProperty("__wsSend", JSCallFunction { args ->
                val handle = (args[0] as? Number)?.toInt() ?: return@JSCallFunction false
                val kind = args[1] as? String ?: "binary"
                val payload = args[2] as? String ?: ""
                val session = sessions[handle] ?: return@JSCallFunction false
                val ws = session.ws ?: return@JSCallFunction false
                if (kind == "text") {
                    ws.send(payload)
                } else {
                    ws.send(b64Decoder.decode(payload).toByteString())
                }
            })

            context.globalObject.setProperty("__wsRecv", JSCallFunction { args ->
                val handle = (args[0] as? Number)?.toInt() ?: return@JSCallFunction null
                val timeoutMs = (args[1] as? Number)?.toLong() ?: 30_000L
                val session = sessions[handle] ?: return@JSCallFunction null
                session.queue.poll(timeoutMs.coerceIn(1L, 300_000L), TimeUnit.MILLISECONDS)
            })

            context.globalObject.setProperty("__wsClose", JSCallFunction { args ->
                val handle = (args[0] as? Number)?.toInt() ?: return@JSCallFunction false
                sessions.remove(handle)?.ws?.close(1000, null)
                true
            })

            // ---- JS polyfill（base64 / fetchBinary / ws 包装） ----
            context.evaluate(POLYFILL)

            // ---- vars ----
            context.evaluate("globalThis.vars = $varsJson;")

            // ---- 用户脚本 ----
            context.evaluate(script)

            val result = context.evaluate("JSON.stringify(synthesize($requestJson))")
            return result as? String ?: error("synthesize() 返回了 null / undefined")
        } finally {
            sessions.values.forEach { s -> runCatching { s.ws?.close(1000, null) } }
            sessions.clear()
            context.destroy()
        }
    }
}

private fun ByteArray.toRequestBodyRaw() =
    this.toRequestBody("application/octet-stream".toMediaType())

@kotlinx.serialization.Serializable
private data class BinaryHttpResponseDto(
    val status: Int,
    val ok: Boolean,
    val statusText: String,
    val bodyBase64: String,
)

@kotlinx.serialization.Serializable
private data class WsTextDto(val type: String, val data: String)

@kotlinx.serialization.Serializable
private data class WsBinaryDto(val type: String, val data: String)

@kotlinx.serialization.Serializable
private data class WsCloseDto(val type: String, val code: Int, val reason: String)

@kotlinx.serialization.Serializable
private data class WsErrorDto(val type: String, val message: String)

/**
 * 注入到 QuickJS 全局的纯 JS 工具层。
 *
 * 注意：Kotlin 的块注释**可以嵌套**，所以这段字符串里绝对不能出现 `/*` 这种相邻字符
 * （踩过一次，整个文件被吞掉，CI 报 Unclosed comment）。下面只用 `//` 行注释。
 */
private val POLYFILL = """
const __B64C = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

globalThis.bytesToBase64 = function (bytes) {
    let out = "";
    for (let i = 0; i < bytes.length; i += 3) {
        const b0 = bytes[i] & 255;
        const has1 = i + 1 < bytes.length;
        const has2 = i + 2 < bytes.length;
        const b1 = has1 ? (bytes[i + 1] & 255) : 0;
        const b2 = has2 ? (bytes[i + 2] & 255) : 0;
        out += __B64C[b0 >> 2];
        out += __B64C[((b0 & 3) << 4) | (b1 >> 4)];
        out += has1 ? __B64C[((b1 & 15) << 2) | (b2 >> 6)] : "=";
        out += has2 ? __B64C[b2 & 63] : "=";
    }
    return out;
};

globalThis.base64ToBytes = function (b64) {
    const map = {};
    for (let i = 0; i < __B64C.length; i++) map[__B64C[i]] = i;
    const clean = String(b64).replace(/[^A-Za-z0-9+/]/g, "");
    const out = [];
    for (let i = 0; i < clean.length; i += 4) {
        const c0 = map[clean[i]] || 0;
        const c1 = map[clean[i + 1]] || 0;
        const c2 = map[clean[i + 2]];
        const c3 = map[clean[i + 3]];
        out.push(((c0 << 2) | (c1 >> 4)) & 255);
        if (c2 !== undefined) out.push(((c1 << 4) | (c2 >> 2)) & 255);
        if (c3 !== undefined) out.push(((c2 << 6) | c3) & 255);
    }
    return out;
};

globalThis.utf8Encode = function (str) {
    const out = [];
    for (let i = 0; i < str.length; i++) {
        const c = str.charCodeAt(i);
        if (c < 128) {
            out.push(c);
        } else if (c < 2048) {
            out.push(192 | (c >> 6));
            out.push(128 | (c & 63));
        } else {
            out.push(224 | (c >> 12));
            out.push(128 | ((c >> 6) & 63));
            out.push(128 | (c & 63));
        }
    }
    return out;
};

globalThis.fetchBinary = function (url, options) {
    options = options || {};
    const headers = options.headers ? JSON.stringify(options.headers) : null;
    let body = null;
    if (options.bodyBytes) {
        body = bytesToBase64(options.bodyBytes);
    } else if (typeof options.body === "string") {
        body = bytesToBase64(utf8Encode(options.body));
    } else if (options.body && typeof options.body === "object") {
        body = bytesToBase64(utf8Encode(JSON.stringify(options.body)));
    }
    const raw = __httpBinary(url, options.method || "GET", headers, body);
    const data = JSON.parse(raw);
    return {
        status: data.status,
        ok: data.ok,
        statusText: data.statusText,
        base64: function () { return data.bodyBase64; },
        bytes: function () { return base64ToBytes(data.bodyBase64); }
    };
};

globalThis.wsConnect = function (url, headers) {
    const handle = __wsConnect(url, headers ? JSON.stringify(headers) : null);
    return {
        send: function (payload) {
            if (typeof payload === "string") return __wsSend(handle, "text", payload);
            return __wsSend(handle, "binary", bytesToBase64(payload));
        },
        recv: function (timeoutMs) {
            const raw = __wsRecv(handle, timeoutMs || 30000);
            if (raw === null || raw === undefined) return null;
            const msg = JSON.parse(raw);
            if (msg.type === "binary") msg.bytes = base64ToBytes(msg.data);
            return msg;
        },
        close: function () { return __wsClose(handle); }
    };
};

globalThis.logger = {
    d: function () { console.log.apply(console, arguments); },
    i: function () { console.log.apply(console, arguments); },
    e: function () { console.log.apply(console, arguments); }
};
""".trimIndent()
