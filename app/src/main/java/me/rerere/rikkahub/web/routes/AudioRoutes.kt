package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.tools.local.audioRoute
import me.rerere.rikkahub.data.audio.PcmStreamPlayer
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException

private const val DEFAULT_SAMPLE_RATE = 44_100
private const val DEFAULT_CHANNELS = 2
private const val DEFAULT_STREAM_ID = "default"

/** 单次读 body 的缓冲。64KB ≈ 0.37s @44.1kHz/立体声/16bit。 */
private const val READ_CHUNK_BYTES = 64 * 1024

/** 上游静默多久算断（ffmpeg 卡住 / curl 被掐）。 */
private const val READ_IDLE_TIMEOUT_MS = 15_000L

/** 单条流的硬上限 —— 防止「永不结束的流」变成一个永不释放的 AudioTrack。 */
private const val MAX_STREAM_MS = 30 * 60 * 1000L

/**
 * 音频输出桥（2026-10-09）—— 给 proot 用的「虚拟声卡」。
 *
 * ## 为什么需要它
 *
 * proot 里**结构上不可能**直接出声：没有 `/dev/snd`、没有 ALSA 硬件、没有 PulseAudio，
 * 而 Android 的出声路径（AudioFlinger ← AudioTrack）在 app 进程里，跨 namespace 摸不到。
 * 所以别去做真·虚拟声卡（那需要内核 `snd-aloop` 或宿主侧 PulseAudio server），
 * 改成**把 PCM 推过来，这边用 AudioTrack 播**。
 *
 * ## 端点
 *
 * - `POST /api/audio/stream?rate=44100&ch=2[&id=default][&require_headset=1]`
 *   body = **裸 PCM**（`s16le`，chunked 即可）。返回本次流的统计。
 * - `GET  /api/audio/status` —— 当前在放什么 + 音频路由（判断声音会从哪出来）
 * - `POST /api/audio/stop[?id=...]` —— 掐掉当前流
 *
 * ## 典型用法（proot 里一行搞定）
 *
 * ```bash
 * ffmpeg -i in.mp3 -f s16le -ar 44100 -ac 2 - \
 *   | curl -sS -X POST --data-binary @- \
 *     "http://127.0.0.1:8080/api/audio/stream?rate=44100&ch=2"
 * ```
 *
 * 任何能往 stdout 写 PCM 的程序都能出声（ffmpeg / sox / 自己写的解码器）。
 * 起播延迟百毫秒级，**不是** MediaPlayer 那种 1-3s 的黑盒缓冲。
 *
 * ## 背压
 *
 * AudioTrack 的 `WRITE_BLOCKING` 满了就阻塞 → body 读取阻塞 → TCP 窗口收窄 → 上游降速。
 * 内存 O(1)，不需要任何手写限流。
 *
 * ## 鉴权
 *
 * 设备桥独立 Bearer（`shellBridgeToken`），与 `/api/tools` `/api/shell` 同一套。
 *
 * ## 安全边界
 *
 * 这条路 = 把**任意音频输出**交给 token 持有人，量级等同 `workspace_shell`（都是
 * 「token 泄露就出事」）。所以：只在可信网络/隧道里开，`require_headset=1` 可以当
 * 「安静场合防社死」闸用（没耳机直接拒绝，不静默降级到外放）。
 */
fun Route.audioRoutes(context: Context, advancedConfigStore: SyncAdvancedConfigStore) {
    route("/audio") {
        /** 当前播放状态 + 音频路由。 */
        get("/status") {
            call.requireDeviceBridgeToken(advancedConfigStore, "audio")
            val session = PcmStreamPlayer.currentSession()
            call.respond(
                HttpStatusCode.OK,
                AudioStatusResult(
                    ok = true,
                    playing = session != null && !session.closed,
                    streamId = session?.id,
                    sampleRate = session?.sampleRate,
                    channels = session?.channels,
                    bytes = session?.bytesWritten,
                    durationMs = session?.durationMs,
                    headsetConnected = audioRoute(context).headsetConnected,
                ),
            )
        }

        /** 推裸 PCM，边收边播。 */
        post("/stream") {
            call.requireDeviceBridgeToken(advancedConfigStore, "audio")

            val rate = call.parameters["rate"]?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
            val channels = call.parameters["ch"]?.toIntOrNull() ?: DEFAULT_CHANNELS
            val id = call.parameters["id"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_STREAM_ID
            val requireHeadset = call.parameters["require_headset"]?.toBooleanStrictOrNull() ?: false
            val route = audioRoute(context)

            if (requireHeadset && !route.headsetConnected) {
                // 与 rikkahub_api 的 require_headset 同款语义：宁可不出声，也别当众外放。
                call.respond(
                    HttpStatusCode.OK,
                    AudioStreamResult(ok = false, error = "headset_not_connected", headsetConnected = false),
                )
                return@post
            }

            val session = try {
                PcmStreamPlayer.open(id, rate, channels)
            } catch (t: Throwable) {
                throw BadRequestException(t.message ?: "cannot open audio output")
            }

            val buffer = ByteArray(READ_CHUNK_BYTES)
            val body = call.receiveChannel()
            var endedBy = "eof"
            try {
                withContext(Dispatchers.IO) {
                    val deadline = System.currentTimeMillis() + MAX_STREAM_MS
                    while (true) {
                        if (System.currentTimeMillis() >= deadline) {
                            endedBy = "max_duration"
                            break
                        }
                        val read = withTimeoutOrNull(READ_IDLE_TIMEOUT_MS) {
                            body.readAvailable(buffer, 0, buffer.size)
                        }
                        if (read == null) {
                            // 上游挂了但连接没断（ffmpeg 卡住）：不能无限期占着音频焦点。
                            endedBy = "idle_timeout"
                            break
                        }
                        if (read <= 0) break
                        if (!session.write(buffer, read)) {
                            endedBy = "superseded"
                            break
                        }
                    }
                }
            } finally {
                PcmStreamPlayer.close(session)
            }

            call.respond(
                HttpStatusCode.OK,
                AudioStreamResult(
                    ok = true,
                    streamId = id,
                    sampleRate = session.sampleRate,
                    channels = session.channels,
                    bytes = session.bytesWritten,
                    durationMs = session.durationMs,
                    endedBy = endedBy,
                    headsetConnected = route.headsetConnected,
                ),
            )
        }

        /** 掐掉当前流（不带 id 就是「全部」，反正只有一块声卡）。 */
        post("/stop") {
            call.requireDeviceBridgeToken(advancedConfigStore, "audio")
            val id = call.parameters["id"]?.trim()?.takeIf { it.isNotEmpty() }
            call.respond(
                HttpStatusCode.OK,
                AudioStopResult(ok = true, stopped = PcmStreamPlayer.stop(id), streamId = id),
            )
        }
    }
}

@Serializable
internal data class AudioStreamResult(
    val ok: Boolean,
    val error: String? = null,
    val streamId: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bytes: Long? = null,
    val durationMs: Long? = null,
    /** eof | idle_timeout | max_duration | superseded */
    val endedBy: String? = null,
    val headsetConnected: Boolean = false,
)

@Serializable
internal data class AudioStopResult(
    val ok: Boolean,
    val stopped: Boolean,
    val streamId: String? = null,
)

@Serializable
internal data class AudioStatusResult(
    val ok: Boolean,
    val playing: Boolean,
    val streamId: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bytes: Long? = null,
    val durationMs: Long? = null,
    val headsetConnected: Boolean = false,
)
