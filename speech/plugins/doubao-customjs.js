// ============================================================================
// 豆包 TTS → RikkaHub「Custom JS」provider 插件
// ============================================================================
//
// 干什么：在 RikkaHub 进程里直接连豆包 SAMI VoiceGenie 的 WebSocket，拿原生 PCM，
// 拼一个 WAV 头，base64 交回去，**由 RikkaHub 自己播**。
//
// 所以：不起服务、不开端口、不挂 schedule process、不走 /api/audio/stream。
// 系统 TTS Service（那个返回「已播过」但一声不出的坏货）完全不参与。
//
// ── 怎么装 ──────────────────────────────────────────────────────────────────
// 设置 → 语音（TTS）→ 添加 provider → Custom JS
//   · 脚本：把本文件全文粘进去
//   · 变量（vars）：
//       cookie = <豆包网页版完整 Cookie>      ← 必填，过期就换
//       voice  = zh_female_roumeinvyou_emo_v2_mars_bigtts   ← 可选，换音色
//       rate   = 1.0                          ← 可选，语速倍率
//   · 格式：wav    采样率：24000
//   · playbackMode：chunk    chunkLength：0（不切片，整段一次合成；想快点起播就填 200）
//
// ── 契约（宿主 TtsPluginHost）───────────────────────────────────────────────
//   synthesize(req) → { base64, format, sampleRate }   req = { text, format, sampleRate }
//   宿主给：wsConnect/send/recv/close（阻塞式 WS）、bytesToBase64、utf8Encode、vars、logger
//   QuickJS 是同步模型：没有事件循环、没有 Promise、没有定时器。所以下面全是阻塞循环。
//
// 协议（从 TTS Server 插件 doubaotts.js 抠出来 + 2026-10-09 用 Python 版验证过）：
//   wss://frontier-audio-web-ws.doubao.com/api/v2/sami/voicegenie?...commonParams
//   客户端帧 field2=app_key 3=namespace 5=event 6=payload 8=conn_id
//   服务端帧 field1=conn_id 2=sess_id 4=event 5=status 6=errmsg 8=audio(bytes)
//   流程：StartTask → TaskStarted → StartSession → SessionStarted
//         → BidirectionalTTS + EndTTS → TTSResponse* → TTSEnded
// ============================================================================

var APP_KEY = "GOqQpfo1fO7slHv8";
var NAMESPACE = "VoiceGenie";
var WS_HOST = "frontier-audio-web-ws.doubao.com";
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0";
var ORIGIN = "chrome-extension://capohkkfagimodmlpnahjoijgoocdjhd";
var SR = 24000;
var RECV_TIMEOUT_MS = 30000;

// ── 手搓 protobuf（和 Python 版逐行对应）────────────────────────────────────
function varint(v) {
    var out = [];
    while (true) {
        var b = v & 0x7f;
        v >>>= 7;
        if (v) { out.push(b | 0x80); } else { out.push(b); return out; }
    }
}

function readVarint(buf, pos) {
    var val = 0, shift = 0;
    while (true) {
        var b = buf[pos];
        pos += 1;
        val |= (b & 0x7f) << shift;
        shift += 7;
        if (!(b & 0x80)) return [val, pos];
    }
}

function pushBytes(target, arr) {
    for (var i = 0; i < arr.length; i++) target.push(arr[i] & 255);
    return target;
}

function encFrame(event, payload, connId) {
    var d = [];
    var pairs = [[2, APP_KEY], [3, NAMESPACE], [5, event], [6, payload]];
    for (var i = 0; i < pairs.length; i++) {
        var num = pairs[i][0], s = utf8Encode(pairs[i][1]);
        pushBytes(d, varint((num << 3) | 2));
        pushBytes(d, varint(s.length));
        pushBytes(d, s);
    }
    if (connId) {
        var c = utf8Encode(connId);
        pushBytes(d, varint((8 << 3) | 2));
        pushBytes(d, varint(c.length));
        pushBytes(d, c);
    }
    return d;
}

// 服务端帧 → { 字段号: 数字 或 字节数组 }
function decFrame(buf) {
    var pos = 0, out = {};
    while (pos < buf.length) {
        var kd = readVarint(buf, pos);
        var key = kd[0]; pos = kd[1];
        var field = key >> 3, wire = key & 7;
        if (wire === 0) {
            var vd = readVarint(buf, pos);
            out[field] = vd[0]; pos = vd[1];
        } else if (wire === 2) {
            var ld = readVarint(buf, pos);
            var ln = ld[0]; pos = ld[1];
            out[field] = buf.slice(pos, pos + ln);
            pos += ln;
        } else {
            throw new Error("不认识的 wire type " + wire);
        }
    }
    return out;
}

// 宿主只给了 utf8Encode，解码自己写
function utf8Decode(arr) {
    var s = "", i = 0;
    while (i < arr.length) {
        var c = arr[i] & 255;
        if (c < 0x80) { s += String.fromCharCode(c); i += 1; }
        else if (c < 0xE0) { s += String.fromCharCode(((c & 31) << 6) | (arr[i + 1] & 63)); i += 2; }
        else if (c < 0xF0) { s += String.fromCharCode(((c & 15) << 12) | ((arr[i + 1] & 63) << 6) | (arr[i + 2] & 63)); i += 3; }
        else { i += 4; }  // 补充平面（emoji）用不上，跳过
    }
    return s;
}

// ── WAV 头（44 字节）────────────────────────────────────────────────────────
// 豆包吐的是裸 PCM（s16le），裸 PCM 没有播放器认得。所以自己拼个 WAV 头，
// 让 RikkaHub 的 AudioPlayer 当普通 wav 文件播。
function wavHeader(dataLen, sampleRate, channels) {
    var h = [];
    function ascii(s) { for (var i = 0; i < s.length; i++) h.push(s.charCodeAt(i) & 255); }
    function le32(v) { h.push(v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255); }
    function le16(v) { h.push(v & 255, (v >>> 8) & 255); }
    var byteRate = sampleRate * channels * 2;
    ascii("RIFF"); le32(36 + dataLen); ascii("WAVE");
    ascii("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate); le32(byteRate); le16(channels * 2); le16(16);
    ascii("data"); le32(dataLen);
    return h;
}

// ── 连接参数 ───────────────────────────────────────────────────────────────
function randDigits(n) {
    var s = "";
    for (var i = 0; i < n; i++) s += Math.floor(Math.random() * 10);
    return s;
}

function wsUrl() {
    var d = randDigits(19);
    var tab = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx".replace(/x/g, function () {
        return Math.floor(Math.random() * 16).toString(16);
    });
    return "wss://" + WS_HOST + "/api/v2/sami/voicegenie?" +
        "&api_app_key=" + APP_KEY + "&namespace=" + NAMESPACE + "&mode=0&language=zh&browser_language=zh-CN" +
        "&device_platform=web&aid=497858&real_aid=497858&pkg_type=release_version" +
        "&device_id=" + d + "&tea_uuid=" + d + "&web_id=" + d + "&is_new_user=0&region=CN&sys_region=CN" +
        "&use-olympus-account=1&samantha_web=1&version=1.20.1&version_code=20800" +
        "&pc_version=3.21.6&web_platform=browser&web_tab_id=" + tab;
}

// ── 主函数 ─────────────────────────────────────────────────────────────────
function synthesize(req) {
    var cookie = vars.cookie || "";
    if (!cookie) throw new Error("豆包 TTS：请在 provider 变量里填 cookie");
    var voice = vars.voice || "zh_female_roumeinvyou_emo_v2_mars_bigtts";
    var rate = parseFloat(vars.rate || "1.0");
    var text = (req && req.text ? String(req.text) : "").trim();
    if (!text) throw new Error("豆包 TTS：空文本");

    var sessCfg = {
        business: 1,
        tts: {
            speaker: voice,
            audio_config: { bit_rate: 32000, format: "pcm", sample_rate: SR },
            extra: { post_process: { pitch: 0.0, speech_rate: rate } }
        },
        extra: { enable_latex_tn: true, disable_markdown_filter: false, enable_language_detector: true }
    };

    var audio = [];
    var connId = null, sessId = null, step = 0;
    var ws = null;

    function sendFrame(event, payload, cid) {
        ws.send(encFrame(event, payload, cid));
    }

    try {
        ws = wsConnect(wsUrl(), {
            "Cookie": cookie,
            "User-Agent": UA,
            "Origin": ORIGIN
        });

        var deadline = Date.now() + 60000;
        var done = false;
        while (!done) {
            if (Date.now() > deadline) throw new Error("豆包 TTS：合成超时");
            var msg = ws.recv(RECV_TIMEOUT_MS);
            if (msg === null || msg === undefined) throw new Error("豆包 TTS：等消息超时");

            if (msg.type === "open") {
                sendFrame("StartTask", "{}");
                continue;
            }
            if (msg.type === "close") throw new Error("豆包 TTS：连接被关闭 code=" + msg.code + " " + (msg.reason || ""));
            if (msg.type === "error") throw new Error("豆包 TTS：WS 错误 " + msg.message);
            if (msg.type === "text") continue;   // 服务端偶尔发文本，忽略
            if (msg.type !== "binary") continue;

            var f = decFrame(msg.bytes);
            var event = f[4] ? utf8Decode(f[4]) : "";
            var status = f[5];

            if (event === "TaskStarted") {
                connId = f[1] ? utf8Decode(f[1]) : "";
                step = 1;
                sendFrame("StartSession", JSON.stringify(sessCfg), connId);
            } else if (event === "SessionStarted") {
                sessId = f[2] ? utf8Decode(f[2]) : "";
                step = 2;
                sendFrame("BidirectionalTTS", JSON.stringify({ text: text }), sessId);
                sendFrame("EndTTS", "{}", sessId);
            } else if (event === "TTSResponse") {
                if (f[8] && f[8].length) pushBytes(audio, f[8]);
            } else if (event === "TTSEnded" || event === "TTSSentenceEnd") {
                done = true;
            } else if (event === "SessionFailed" || (status && status !== 20000000)) {
                throw new Error("豆包 SAMI " + status + "：" + (f[6] ? utf8Decode(f[6]) : "未知错误"));
            }
        }
    } finally {
        try { if (ws) ws.close(); } catch (e) { }
    }

    if (!audio.length) throw new Error("豆包 TTS：没拿到音频（cookie 大概率过期了）");

    var wav = pushBytes(wavHeader(audio.length, SR, 1), audio);
    logger.i("豆包 TTS：合成 " + text.length + " 字 → " + audio.length + " 字节 PCM");
    return { base64: bytesToBase64(wav), format: "wav", sampleRate: SR };
}
