package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Rikkahub API（2026-10-05）
 *
 * Termux:API 风格的设备工具，但**只做不需要 root/Shizuku 的原生部分**：
 * 蓝牙查询/连断、系统 TTS、音量、音频播放、震动、定位查询。
 *
 * 为什么单独开一个工具，而不是扩现有 `text_to_speech`：
 * 1. 现有 `text_to_speech` 走的是 RikkaHub 自己的 provider TTS，**不是系统 TTS**；
 *    这里要的是系统引擎（音量/路由跟随系统，才会落到蓝牙耳机上）。
 * 2. 用户的真实诉求是「定时任务先确认耳机连着再说话，不然当众外放社死」，
 *    所以 [ACT_TTS_SPEAK] / [ACT_AUDIO_PLAY] 都带 `require_headset` 硬闸，
 *    并且**每次都在返回里报当前音频输出路由**，让模型自己也能判断。
 *
 * 实测环境：Redmi 23113RKC6C / Android 15 (SDK 35) / HyperOS V816。
 *
 * 两个必须记住的实现约束：
 * - `BluetoothA2dp#connect/disconnect` 标注 `@hide` + `@UnsupportedAppUsage`（greylist），
 *   **不在公开 SDK 里，编译期不可见 → 只能反射**。反射被拦（未来版本拉黑）时退到公开的
 *   `setConnectionPolicy`：`FORBIDDEN` 语义等价于断开；`ALLOWED` 只是"允许连接"，
 *   不保证立刻连上，所以连接后要轮询状态确认。
 * - 不硬编码任何设备名/包名：耳机由调用方按蓝牙地址或名字子串指定。
 */
private const val ACT_BT_LIST = "bluetooth_list"
private const val ACT_BT_CONNECT = "bluetooth_connect"
private const val ACT_BT_DISCONNECT = "bluetooth_disconnect"
private const val ACT_VOLUME_GET = "volume_get"
private const val ACT_VOLUME_SET = "volume_set"
private const val ACT_AUDIO_PLAY = "audio_play"
private const val ACT_VIBRATE = "vibrate"
private const val ACT_LOCATION_GET = "location_get"
private const val ACT_TTS_SPEAK = "tts_speak"

private val RIKKAHUB_API_ACTIONS = listOf(
    ACT_BT_LIST, ACT_BT_CONNECT, ACT_BT_DISCONNECT,
    ACT_VOLUME_GET, ACT_VOLUME_SET, ACT_AUDIO_PLAY,
    ACT_VIBRATE, ACT_LOCATION_GET, ACT_TTS_SPEAK,
)

/** 音量档位名 → AudioManager stream。全部是公开常量。 */
private val VOLUME_STREAMS: Map<String, Int> = mapOf(
    "call" to AudioManager.STREAM_VOICE_CALL,
    "system" to AudioManager.STREAM_SYSTEM,
    "ring" to AudioManager.STREAM_RING,
    "media" to AudioManager.STREAM_MUSIC,
    "alarm" to AudioManager.STREAM_ALARM,
    "notification" to AudioManager.STREAM_NOTIFICATION,
)

private const val VIA_NATIVE = "native"

/** 蓝牙 profile 代理最长等待；拿不到就退化成"只报配对列表"。 */
private const val PROFILE_PROXY_TIMEOUT_MS = 4_000L

/** connect/disconnect 后轮询状态的最大时长。 */
private const val BT_STATE_POLL_MS = 6_000L

/**
 * 正在播放的 MediaPlayer 必须留引用：本地变量一被 GC，声音立刻断。
 * 单例持有即可，新播放先停旧的（这个工具是"放一段就走"，不做播放队列）。
 */
private object RikkahubAudioHolder {
    var player: MediaPlayer? = null

    fun stopCurrent() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }
}

internal fun buildRikkahubApiTool(context: Context): Tool = Tool(
    name = "rikkahub_api",
    description = """
        Device API (Termux:API style, native subset). Query/connect Bluetooth audio devices, speak via the
        SYSTEM text-to-speech engine, read/set volume, play an audio file, vibrate, and read the precise location.

        IMPORTANT for tts_speak / audio_play: sound comes out of the phone's CURRENT audio route. In a public or
        quiet place, call $ACT_BT_LIST first to confirm a headset is connected, or pass require_headset=true which
        makes the call FAIL (error=headset_not_connected) instead of blasting the loudspeaker. Every speak/play
        result reports audio_outputs so you can verify where the sound actually went.

        Actions:
        - $ACT_BT_LIST: bonded devices + per-device A2DP state + current audio output route (use before TTS).
        - $ACT_BT_CONNECT / $ACT_BT_DISCONNECT: connect/disconnect a bonded device by address or name substring.
        - $ACT_VOLUME_GET: read stream volume (index/max/percent). $ACT_VOLUME_SET: set by level or percent.
        - $ACT_AUDIO_PLAY: play a local path / content:// / http(s) URL through the media stream.
        - $ACT_VIBRATE: one-shot vibration; duration_ms + optional amplitude (1-255, needs hardware support).
        - $ACT_LOCATION_GET: precise location (lat/lon/accuracy/provider) from GPS/NETWORK.
        - $ACT_TTS_SPEAK: speak text with the system TTS engine.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Which device action to run")
                    put("enum", buildJsonArray { RIKKAHUB_API_ACTIONS.forEach { add(it) } })
                })
                put("device", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Bluetooth address (AA:BB:CC:DD:EE:FF) or a name substring, e.g. \"Fit900NB\" or \"Buds 6s\". " +
                            "Required for $ACT_BT_CONNECT; optional for $ACT_BT_DISCONNECT (defaults to the connected one)."
                    )
                })
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "Volume stream: call|system|ring|media|alarm|notification. Default: list all for get, media for set.")
                })
                put("level", buildJsonObject {
                    put("type", "integer")
                    put("description", "Absolute volume index for $ACT_VOLUME_SET")
                })
                put("percent", buildJsonObject {
                    put("type", "integer")
                    put("description", "Volume 0-100 for $ACT_VOLUME_SET (converted to the stream's index range)")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to speak aloud. Plain text, no markdown.")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional BCP-47 language tag for TTS, e.g. zh-CN or en-US. Default: engine's own.")
                })
                put("uri", buildJsonObject {
                    put("type", "string")
                    put("description", "Audio source for $ACT_AUDIO_PLAY: absolute file path, content:// URI, or http(s) URL")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Vibration duration in milliseconds (default 300)")
                })
                put("amplitude", buildJsonObject {
                    put("type", "integer")
                    put("description", "Vibration amplitude 1-255. 0/Omitted = hardware default. Ignored if the device has no amplitude control.")
                })
                put("require_headset", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true = refuse to play/speak unless a headset is on the current audio outputs (default false)")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max wait for a fresh fix in $ACT_LOCATION_GET (default 8000)")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj.str("action") ?: error("action is required")
        val payload = when (action) {
            ACT_BT_LIST -> bluetoothList(context)
            ACT_BT_CONNECT -> bluetoothSetConnected(
                context = context,
                query = obj.str("device"),
                connect = true,
            )
            ACT_BT_DISCONNECT -> bluetoothSetConnected(
                context = context,
                query = obj.str("device"),
                connect = false,
            )
            ACT_VOLUME_GET -> volumeGet(context, obj.str("stream"))
            ACT_VOLUME_SET -> volumeSet(
                context = context,
                stream = obj.str("stream") ?: "media",
                level = obj.int("level"),
                percent = obj.int("percent"),
            )
            ACT_AUDIO_PLAY -> audioPlay(
                context = context,
                uri = obj.str("uri") ?: error("uri is required for $ACT_AUDIO_PLAY"),
                requireHeadset = obj.bool("require_headset") == true,
            )
            ACT_VIBRATE -> vibrate(
                context = context,
                durationMs = obj.int("duration_ms") ?: 300,
                amplitude = obj.int("amplitude") ?: 0,
            )
            ACT_LOCATION_GET -> locationGet(
                context = context,
                timeoutMs = (obj.int("timeout_ms") ?: 8_000).toLong().coerceIn(500L, 30_000L),
            )
            ACT_TTS_SPEAK -> ttsSpeak(
                context = context,
                text = obj.str("text") ?: error("text is required for $ACT_TTS_SPEAK"),
                language = obj.str("language"),
                requireHeadset = obj.bool("require_headset") == true,
            )
            else -> error("unknown action '$action'; expected one of ${RIKKAHUB_API_ACTIONS.joinToString()}")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

// ---------------------------------------------------------------------------
// 通用
// ---------------------------------------------------------------------------

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun Context.granted(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** 统一失败信封，永远带 via + ok，别让模型猜。 */
private fun failure(error: String, extra: JsonObject? = null): JsonObject = buildJsonObject {
    put("ok", false)
    put("via", VIA_NATIVE)
    put("error", error)
    if (extra != null) extra.forEach { (k, v) -> put(k, v) }
}

// ---------------------------------------------------------------------------
// 音频路由 / 耳机检测
// ---------------------------------------------------------------------------

private fun audioManagerOf(context: Context): AudioManager? =
    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

/**
 * 当前**已连接**的输出设备。
 *
 * 这是判断"声音会从哪出来"最可靠的公开信号：AudioDeviceInfo 只列已连接的设备。
 * 注意：已连接 ≠ 一定是当前激活路由（媒体路由由系统按优先级决定），但对
 * "耳机在不在"这个判断足够，且比 BluetoothAdapter 的 profile 状态更贴近实际出声。
 */
private fun connectedOutputs(context: Context): List<AudioDeviceInfo> =
    runCatching { audioManagerOf(context)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }
        .getOrNull().orEmpty()

private fun isHeadsetType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> true

    // BLE 音频（LE Audio）两个类型是 API 31 才有的编译期常量，直接比较即可（常量会被内联，
    // 在低版本上不会真的执行到这里）。
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    -> true

    else -> false
}

private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
    else -> "type_$type"
}

private data class AudioRoute(val headsetConnected: Boolean, val json: JsonObject)

private fun audioRoute(context: Context): AudioRoute {
    val outputs = connectedOutputs(context)
    val json = buildJsonArray {
        outputs.forEach { dev ->
            add(buildJsonObject {
                put("name", dev.productName?.toString() ?: "")
                put("type", audioDeviceTypeName(dev.type))
                // isSink 在本场景恒为 true（查的是 OUTPUTS），留着给别处复用
                put("is_sink", dev.isSink)
                put("headset", isHeadsetType(dev.type))
            })
        }
    }
    return AudioRoute(
        headsetConnected = outputs.any { isHeadsetType(it.type) },
        json = buildJsonObject { put("audio_outputs", json) },
    )
}

// ---------------------------------------------------------------------------
// 蓝牙
// ---------------------------------------------------------------------------

private fun btAdapterOf(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/** Android 12+ 起读蓝牙要 BLUETOOTH_CONNECT 运行时权限，没有就是 SecurityException 满天飞。 */
private fun btPermissionError(context: Context): JsonObject? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !context.granted(Manifest.permission.BLUETOOTH_CONNECT)) {
        failure(
            "permission_denied",
            buildJsonObject {
                put("permission", Manifest.permission.BLUETOOTH_CONNECT)
                put("hint", "Enable the Rikkahub API local tool (or grant Nearby devices) and retry")
            },
        )
    } else {
        null
    }

/**
 * 拿 A2DP profile 代理。getProfileProxy 是异步的，这里用协程包一层并设上限；
 * 拿不到就返回 null，调用方退化成"只报配对列表"。
 */
private suspend fun withA2dpProfile(
    context: Context,
    adapter: BluetoothAdapter,
    block: suspend (BluetoothProfile) -> JsonObject,
): JsonObject? {
    val proxy = withTimeoutOrNull(PROFILE_PROXY_TIMEOUT_MS) {
        suspendCancellableCoroutine<BluetoothProfile?> { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (cont.isActive) cont.resume(proxy)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }
            val requested = runCatching {
                adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
            }.getOrDefault(false)
            if (!requested && cont.isActive) cont.resume(null)
        }
    }
    if (proxy == null) return null
    return try {
        block(proxy)
    } finally {
        runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
    }
}

private fun btStateName(state: Int): String = when (state) {
    BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
    BluetoothProfile.STATE_CONNECTING -> "connecting"
    BluetoothProfile.STATE_CONNECTED -> "connected"
    BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
    else -> "unknown_$state"
}

private suspend fun bluetoothList(context: Context): JsonObject {
    btPermissionError(context)?.let { return it }
    val adapter = btAdapterOf(context) ?: return failure("bluetooth_unavailable")
    val route = audioRoute(context)

    val bonded = runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrNull().orEmpty()

    // 每个已配对设备的 A2DP 状态（公开 API getConnectionState）
    val stateByAddress: Map<String, Int> = withA2dpProfile(context, adapter) { profile ->
        buildJsonObject {
            bonded.forEach { dev ->
                val st = runCatching { profile.getConnectionState(dev) }
                    .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                put(dev.address, st)
            }
        }
    }.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.intOrNull ?: -1 }

    val bondedJson = buildJsonArray {
        bonded.forEach { dev ->
            add(buildJsonObject {
                put("name", deviceName(dev))
                put("address", dev.address)
                val st = stateByAddress[dev.address]
                if (st != null) {
                    put("a2dp_state", btStateName(st))
                    put("a2dp_connected", st == BluetoothProfile.STATE_CONNECTED)
                }
            })
        }
    }

    val connectedNames = bonded.filter {
        stateByAddress[it.address] == BluetoothProfile.STATE_CONNECTED
    }.map { deviceName(it) }

    return buildJsonObject {
        put("ok", true)
        put("via", VIA_NATIVE)
        put("adapter_enabled", adapter.isEnabled)
        put("a2dp_state_available", stateByAddress.isNotEmpty())
        put("bonded_count", bonded.size)
        put("bonded", bondedJson)
        put("a2dp_connected", buildJsonArray { connectedNames.forEach { add(it) } })
        put("headset_connected", route.headsetConnected)
        put("audio_outputs", route.json["audio_outputs"]!!)
        put(
            "note",
            if (route.headsetConnected) {
                "A headset is on the current audio outputs; speaking/playing will go there."
            } else {
                "No headset on the current audio outputs; tts_speak/audio_play will use the loudspeaker."
            },
        )
    }
}

private fun deviceName(dev: BluetoothDevice): String =
    runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address

/**
 * 解析调用方给的 `device`：蓝牙地址优先，其次按名字子串（忽略大小写）匹配已配对设备。
 * 没给 device 时：断开 → 取当前已连接的那台；连接 → 只有唯一配对设备时才自动选。
 */
private suspend fun resolveDevice(
    context: Context,
    adapter: BluetoothAdapter,
    query: String?,
    connect: Boolean,
): Pair<BluetoothDevice, JsonObject>? {
    val bonded = runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrNull().orEmpty()

    val mac = query?.trim()?.uppercase()
    if (mac != null && Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)) {
        val dev = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
            ?: return null
        return dev to buildJsonObject { put("matched_by", "address") }
    }

    if (query != null) {
        val needle = query.trim().lowercase(Locale.ROOT)
        val hits = bonded.filter { deviceName(it).lowercase(Locale.ROOT).contains(needle) }
        val exact = hits.firstOrNull { deviceName(it).lowercase(Locale.ROOT) == needle }
        val chosen = exact ?: hits.firstOrNull()
        return chosen?.let { it to buildJsonObject { put("matched_by", if (exact != null) "name_exact" else "name_substring") } }
    }

    // 没给 device
    if (!connect) {
        val connected = withA2dpProfile(context, adapter) { profile ->
            buildJsonObject {
                val dev = runCatching { profile.connectedDevices?.firstOrNull() }.getOrNull()
                put("address", dev?.address ?: "")
            }
        }
        val addr = connected?.get("address")?.jsonPrimitive?.contentOrNull
        if (!addr.isNullOrBlank()) {
            runCatching { adapter.getRemoteDevice(addr) }.getOrNull()?.let {
                return it to buildJsonObject { put("matched_by", "currently_connected") }
            }
        }
    }
    if (bonded.size == 1) {
        return bonded.first() to buildJsonObject { put("matched_by", "only_bonded_device") }
    }
    return null
}

/**
 * 反射调 `BluetoothA2dp#connect/disconnect`（`@hide` + `@UnsupportedAppUsage`，公开 SDK 里没有）。
 *
 * 返回 null 表示反射不可用（方法被拉黑 / 不存在 / 权限被拦 / 方法签名变了），调用方如实报错，
 * **不要退化到任何“公开兜底”** —— 这个类上唯一的公开相关方法 `setConnectionPolicy`
 * 也是 @hide + @SystemApi（还要 BLUETOOTH_PRIVILEGED），普通 App 用不了。
 */
private fun reflectConnect(proxy: Any, method: String, device: BluetoothDevice): Boolean? = try {
    proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device) as? Boolean
} catch (_: Throwable) {
    null
}

private suspend fun bluetoothSetConnected(
    context: Context,
    query: String?,
    connect: Boolean,
): JsonObject {
    btPermissionError(context)?.let { return it }
    val adapter = btAdapterOf(context) ?: return failure("bluetooth_unavailable")
    if (!adapter.isEnabled) return failure("bluetooth_disabled")

    val resolved = resolveDevice(context, adapter, query, connect)
        ?: return failure(
            if (connect) "device_not_found" else "no_connected_device",
            buildJsonObject {
                put("hint", "Pass device=<address or name substring>. Use $ACT_BT_LIST to see bonded devices.")
                put("query", query ?: "")
            },
        )
    val (device, matchInfo) = resolved
    val target = if (connect) "connect" else "disconnect"
    val wantState = if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED

    val outcome = withA2dpProfile(context, adapter) { profile ->
        val invoked = reflectConnect(profile, target, device)
        if (invoked == null) {
            // connect/disconnect 标注 @hide + @UnsupportedAppUsage（greylist），**不在公开 SDK 里**。
            // 公开的 setConnectionPolicy 同样走不通：它也是 @hide + @SystemApi，还额外要
            // BLUETOOTH_PRIVILEGED，普通 App 根本拿不到 —— 所以反射是唯一的路。
            // 走到这里 = greylist 被拉黑 / 方法不存在 / 权限被拦。
            return@withA2dpProfile buildJsonObject {
                put("api_available", false)
                put(
                    "reason",
                    "android.bluetooth.BluetoothA2dp#$target is hidden; reflection was rejected on this ROM",
                )
            }
        }
        // 公开 API 轮询真实状态：调用被接受 ≠ 真的连上/断开（A2DP 重连是异步的）
        var state = runCatching { profile.getConnectionState(device) }
            .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
        val deadline = System.currentTimeMillis() + BT_STATE_POLL_MS
        while (state != wantState && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(250)
            state = runCatching { profile.getConnectionState(device) }
                .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
        }
        buildJsonObject {
            put("api_available", true)
            put("accepted", invoked)
            put("final_state", btStateName(state))
            put("reached_target", state == wantState)
        }
    } ?: buildJsonObject {
        put("api_available", false)
        put("reason", "A2DP profile proxy unavailable")
    }

    val matchedBy = matchInfo["matched_by"]?.jsonPrimitive?.contentOrNull ?: ""

    if (outcome["api_available"]?.jsonPrimitive?.contentOrNull == "false") {
        return failure(
            "hidden_api_blocked",
            buildJsonObject {
                put("action", if (connect) ACT_BT_CONNECT else ACT_BT_DISCONNECT)
                put("device", deviceName(device))
                put("address", device.address)
                put("matched_by", matchedBy)
                outcome["reason"]?.let { r -> put("reason", r) }
                put(
                    "hint",
                    "BluetoothA2dp.$target is @hide and only reachable via reflection while it stays on the " +
                        "hidden-API greylist. Use the system Bluetooth settings UI, or add the Shizuku path.",
                )
            },
        )
    }

    val route = audioRoute(context)
    val ok = outcome["reached_target"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
    return buildJsonObject {
        put("ok", ok)
        put("via", VIA_NATIVE)
        put("action", if (connect) ACT_BT_CONNECT else ACT_BT_DISCONNECT)
        put("device", deviceName(device))
        put("address", device.address)
        put("matched_by", matchedBy)
        put("audio_outputs", route.json["audio_outputs"]!!)
        put("headset_connected", route.headsetConnected)
        outcome.forEach { (k, v) -> put(k, v) }
        if (!ok) {
            put(
                "hint",
                if (connect) {
                    "Not connected yet. A2DP re-connect is asynchronous; call $ACT_BT_LIST to check."
                } else {
                    "Still connected. Some ROMs need the audio stream stopped before the profile drops."
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 音量
// ---------------------------------------------------------------------------

private fun volumeGet(context: Context, stream: String?): JsonObject {
    val am = audioManagerOf(context) ?: return failure("audio_unavailable")
    val keys = if (stream.isNullOrBlank()) {
        listOf("media", "ring", "alarm", "notification", "call", "system")
    } else {
        listOf(stream.lowercase(Locale.ROOT))
    }
    val unknown = keys.filter { it !in VOLUME_STREAMS }
    if (unknown.isNotEmpty()) {
        return failure("unknown_stream", buildJsonObject { put("stream", unknown.joinToString()) })
    }
    return buildJsonObject {
        put("ok", true)
        put("via", VIA_NATIVE)
        put("streams", buildJsonObject {
            keys.forEach { key ->
                val id = VOLUME_STREAMS.getValue(key)
                val idx = runCatching { am.getStreamVolume(id) }.getOrDefault(-1)
                val max = runCatching { am.getStreamMaxVolume(id) }.getOrDefault(0)
                put(key, buildJsonObject {
                    put("stream_id", id)
                    put("index", idx)
                    put("max", max)
                    put("percent", if (max > 0) (idx * 100 + max / 2) / max else 0)
                    put("muted", idx == 0)
                })
            }
        })
        put("mode", runCatching { am.ringerMode }.getOrDefault(-1))
    }
}

private fun volumeSet(context: Context, stream: String, level: Int?, percent: Int?): JsonObject {
    val am = audioManagerOf(context) ?: return failure("audio_unavailable")
    val key = stream.lowercase(Locale.ROOT)
    val id = VOLUME_STREAMS[key]
        ?: return failure(
            "unknown_stream",
            buildJsonObject { put("stream", key); put("expected", VOLUME_STREAMS.keys.joinToString()) },
        )
    val max = runCatching { am.getStreamMaxVolume(id) }.getOrDefault(0)
    if (max <= 0) return failure("stream_unavailable")

    val target = when {
        level != null -> level
        percent != null -> (percent.coerceIn(0, 100) * max + 50) / 100
        else -> return failure("level_or_percent_required")
    }.coerceIn(0, max)

    return try {
        am.setStreamVolume(id, target, 0)
        buildJsonObject {
            put("ok", true)
            put("via", VIA_NATIVE)
            put("stream", key)
            put("index", target)
            put("max", max)
            put("percent", (target * 100 + max / 2) / max)
            put("muted", target == 0)
        }
    } catch (t: SecurityException) {
        // 典型：勿扰模式下调 ring/notification
        failure("security_exception", buildJsonObject { put("message", t.message ?: "") })
    }
}

// ---------------------------------------------------------------------------
// 播放音频
// ---------------------------------------------------------------------------

private suspend fun audioPlay(context: Context, uri: String, requireHeadset: Boolean): JsonObject {
    val route = audioRoute(context)
    if (requireHeadset && !route.headsetConnected) {
        return failure(
            "headset_not_connected",
            buildJsonObject {
                put("message", "refused to play: no headset on the current audio outputs (require_headset=true)")
                put("audio_outputs", route.json["audio_outputs"]!!)
            },
        )
    }

    return withContext(Dispatchers.IO) {
        RikkahubAudioHolder.stopCurrent()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            when {
                uri.startsWith("content://") ->
                    player.setDataSource(context, Uri.parse(uri))

                uri.startsWith("http://") || uri.startsWith("https://") ->
                    player.setDataSource(uri)

                else -> player.setDataSource(uri.removePrefix("file://"))
            }
            player.prepare()
            player.start()
            RikkahubAudioHolder.player = player
            buildJsonObject {
                put("ok", true)
                put("via", VIA_NATIVE)
                put("uri", uri)
                put("duration_ms", runCatching { player.duration }.getOrDefault(-1))
                put("audio_outputs", route.json["audio_outputs"]!!)
                put("headset_connected", route.headsetConnected)
            }
        } catch (t: Throwable) {
            runCatching { player.release() }
            failure(
                "play_failed",
                buildJsonObject {
                    put("message", t.message ?: t.javaClass.simpleName)
                    put("uri", uri)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 震动
// ---------------------------------------------------------------------------

private fun vibratorOf(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

private fun vibrate(context: Context, durationMs: Int, amplitude: Int): JsonObject {
    val vib = vibratorOf(context) ?: return failure("vibrator_unavailable")
    if (!vib.hasVibrator()) return failure("no_vibrator_hardware")

    val duration = durationMs.coerceIn(1, 60_000)
    val hasAmplitude = runCatching { vib.hasAmplitudeControl() }.getOrDefault(false)
    val amp = if (hasAmplitude && amplitude in 1..255) amplitude else VibrationEffect.DEFAULT_AMPLITUDE

    return try {
        vib.vibrate(VibrationEffect.createOneShot(duration.toLong(), amp))
        buildJsonObject {
            put("ok", true)
            put("via", VIA_NATIVE)
            put("duration_ms", duration)
            put("amplitude_control", hasAmplitude)
            put("amplitude_used", if (amp == VibrationEffect.DEFAULT_AMPLITUDE) "default" else amp.toString())
            if (hasAmplitude && amplitude !in 1..255) {
                put("note", "amplitude ignored: pass 1-255 to use it on this device")
            }
            if (!hasAmplitude) {
                put("note", "this device reports no amplitude control; duration only")
            }
        }
    } catch (t: Throwable) {
        failure("vibrate_failed", buildJsonObject { put("message", t.message ?: "") })
    }
}

// ---------------------------------------------------------------------------
// 定位
// ---------------------------------------------------------------------------

private val inlineExecutor = Executor { command -> command.run() }

private suspend fun locationGet(context: Context, timeoutMs: Long): JsonObject {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return failure("location_unavailable")
    val fine = context.granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!fine && !coarse) {
        return failure(
            "permission_denied",
            buildJsonObject {
                put("permission", Manifest.permission.ACCESS_FINE_LOCATION)
                put("hint", "Grant location permission, and make sure the system location switch is ON")
            },
        )
    }

    val enabledProviders = runCatching { lm.getProviders(true).orEmpty() }.getOrDefault(emptyList())
    val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { lm.isLocationEnabled }.getOrDefault(false)
    } else {
        enabledProviders.isNotEmpty()
    }

    var fresh: Location? = null
    var usedProvider: String? = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        ).filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

        for (p in candidates) {
            val got = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    try {
                        lm.getCurrentLocation(p, null, inlineExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (got != null) {
                fresh = got
                usedProvider = p
                break
            }
        }
    }

    val fallback = fresh ?: enabledProviders.asSequence()
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }

    val loc = fresh ?: fallback
        ?: return failure(
            "no_fix",
            buildJsonObject {
                put("location_enabled", locationEnabled)
                put("enabled_providers", buildJsonArray { enabledProviders.forEach { add(it) } })
                put("hint", "No fix and no cached location. Turn the system location switch on and retry.")
            },
        )

    return buildJsonObject {
        put("ok", true)
        put("via", VIA_NATIVE)
        put("fresh", fresh != null)
        put("provider", usedProvider ?: loc.provider ?: "unknown")
        put("latitude", loc.latitude)
        put("longitude", loc.longitude)
        if (loc.hasAccuracy()) put("accuracy_m", loc.accuracy.toDouble())
        if (loc.hasAltitude()) put("altitude_m", loc.altitude)
        if (loc.hasSpeed()) put("speed_mps", loc.speed.toDouble())
        if (loc.hasBearing()) put("bearing_deg", loc.bearing.toDouble())
        put("fix_time", loc.time)
        put("age_ms", System.currentTimeMillis() - loc.time)
        put("location_enabled", locationEnabled)
        put("enabled_providers", buildJsonArray { enabledProviders.forEach { add(it) } })
    }
}

// ---------------------------------------------------------------------------
// 系统 TTS
// ---------------------------------------------------------------------------

/**
 * 用**系统** TTS 引擎念一段话。
 *
 * 每次调用建一个引擎、念完 shutdown：无状态、不会泄漏，代价是首次 init 有几百毫秒。
 * 对工具调用这种非实时场景完全可以接受，比维护长生命周期引擎安全得多。
 *
 * 关键点：audio attributes 显式设成 USAGE_MEDIA + CONTENT_TYPE_SPEECH，
 * 这样音频走**媒体流**、跟随当前媒体路由（也就是蓝牙耳机），而不是从听筒/扬声器冒出来。
 */
private suspend fun ttsSpeak(
    context: Context,
    text: String,
    language: String?,
    requireHeadset: Boolean,
): JsonObject {
    val route = audioRoute(context)
    if (requireHeadset && !route.headsetConnected) {
        return failure(
            "headset_not_connected",
            buildJsonObject {
                put("message", "refused to speak: no headset on the current audio outputs (require_headset=true)")
                put("audio_outputs", route.json["audio_outputs"]!!)
            },
        )
    }

    val outcome = withTimeoutOrNull(25_000) { speakOnce(context, text, language) }
        ?: return failure(
            "tts_timeout",
            buildJsonObject {
                put("message", "system TTS engine did not finish within 25s (engine missing or stuck)")
                put("audio_outputs", route.json["audio_outputs"]!!)
            },
        )

    return buildJsonObject {
        put("ok", outcome.first)
        put("via", VIA_NATIVE)
        put("spoken_chars", text.length)
        put("audio_outputs", route.json["audio_outputs"]!!)
        put("headset_connected", route.headsetConnected)
        if (!outcome.first) put("error", outcome.second ?: "tts_failed")
    }
}

/** 返回 (成功?, 错误码?)。 */
private suspend fun speakOnce(context: Context, text: String, language: String?): Pair<Boolean, String?> =
    suspendCancellableCoroutine { cont ->
        var engine: TextToSpeech? = null

        fun finish(ok: Boolean, error: String?) {
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
            engine = null
            if (cont.isActive) cont.resume(ok to error)
        }

        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                finish(false, "engine_init_failed")
                return@TextToSpeech
            }
            if (!language.isNullOrBlank()) {
                runCatching { tts.setLanguage(Locale.forLanguageTag(language)) }
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(true, null)

                @Deprecated("deprecated in API 21, still required by the interface")
                override fun onError(utteranceId: String?) = finish(false, "utterance_error")

                override fun onError(utteranceId: String?, errorCode: Int) =
                    finish(false, "utterance_error_$errorCode")
            })
            val queued = tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "rikkahub-${System.nanoTime()}",
            )
            if (queued != TextToSpeech.SUCCESS) finish(false, "speak_rejected")
        }
    }
