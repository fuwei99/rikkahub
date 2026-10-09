package me.rerere.rikkahub.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 流式 PCM 播放器 —— 一块「虚拟声卡」（2026-10-09）。
 *
 * ## 为什么不用 MediaPlayer
 *
 * MediaPlayer 是「给我一个 URL/文件，我自己缓冲」的黑盒：起播延迟 1-3s、缓冲策略不可控、
 * 而且**完全不支持裸 PCM**。proot 里 ffmpeg 产出的就是 PCM 流，塞不进它。
 * AudioTrack 才是声卡语义 —— 你把 PCM 写进去，它就播，你写多快它播多快。
 *
 * ## 背压是白拿的
 *
 * [PcmStreamSession.write] 用 `AudioTrack.WRITE_BLOCKING`：内部缓冲满了就**阻塞**。
 * 于是 HTTP body 读取阻塞 → TCP 窗口收窄 → 上游（`ffmpeg | curl`）自己降速。
 * 整条链零缓冲堆积，内存 O(1)，**不需要任何手写限流**。
 *
 * ## 单会话
 *
 * 只有一块声卡，新流顶掉旧流。并发多流会互相抢音频焦点、听感稀碎，所以刻意不做混音队列。
 */
object PcmStreamPlayer {
    private val lock = Any()
    private var current: PcmStreamSession? = null

    /**
     * 开一块声卡。会**先停掉**正在播的流（单会话语义）。
     *
     * @throws IllegalArgumentException 采样率/声道数不支持，或这台设备建不出 AudioTrack
     */
    fun open(id: String, sampleRate: Int, channels: Int): PcmStreamSession {
        require(sampleRate in 4_000..192_000) { "unsupported sample rate: $sampleRate" }
        require(channels in 1..2) { "unsupported channel count: $channels (mono/stereo only)" }
        synchronized(lock) {
            current?.close()
            val session = PcmStreamSession(id, sampleRate, channels, buildTrack(sampleRate, channels))
            current = session
            return session
        }
    }

    /** 收工时关掉指定会话（只在它仍是当前会话时清位）。 */
    fun close(session: PcmStreamSession) {
        synchronized(lock) { if (current === session) current = null }
        session.close()
    }

    /** 停当前流；给了 [id] 就只停这一个。@return 是否真停了 */
    fun stop(id: String? = null): Boolean {
        synchronized(lock) {
            val target = current ?: return false
            if (id != null && target.id != id) return false
            current = null
            target.close()
        }
        return true
    }

    /** 当前会话（没有就是 null）。只读快照，别拿它写数据。 */
    fun currentSession(): PcmStreamSession? = synchronized(lock) { current }
}

/** 一次流式播放会话。 */
class PcmStreamSession internal constructor(
    val id: String,
    val sampleRate: Int,
    val channels: Int,
    private val track: AudioTrack,
) {
    /** 一帧 = 所有声道各一个 16bit 采样。半帧写进去 = 声道错位 = 一声「咔」。 */
    val frameSizeBytes: Int = channels * 2

    val startedAtMs: Long = System.currentTimeMillis()

    @Volatile
    var bytesWritten: Long = 0L
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** 已写入时长。按字节算，不受设备时钟漂移影响。 */
    val durationMs: Long
        get() = bytesWritten * 1000L / (sampleRate.toLong() * frameSizeBytes)

    /**
     * 阻塞写。**必须在 IO 线程调用**（缓冲满时它会一直等）。
     *
     * @return false = 会话已被顶替/关闭，调用方该收工了
     */
    fun write(buffer: ByteArray, length: Int): Boolean {
        if (closed) return false
        val usable = alignToFrameBoundary(length, frameSizeBytes)
        if (usable <= 0) return true
        var offset = 0
        while (offset < usable) {
            if (closed) return false
            val written = track.write(buffer, offset, usable - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) return false
            offset += written
        }
        bytesWritten += usable
        return true
    }

    internal fun close() {
        if (closed) return
        closed = true
        runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
        runCatching { track.release() }
    }
}

/**
 * 把字节数截到整帧边界。
 *
 * `WRITE_BLOCKING` 的返回值**可能落在半帧上**（尤其立体声），照抄就会让左右声道错位，
 * 每块数据交界处一声「咔」。所以读取缓冲和写入循环都要按帧对齐。
 */
internal fun alignToFrameBoundary(length: Int, frameSizeBytes: Int): Int =
    if (frameSizeBytes <= 0 || length <= 0) 0 else length - (length % frameSizeBytes)

private fun buildTrack(sampleRate: Int, channels: Int): AudioTrack {
    val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    require(minBuffer > 0) { "AudioTrack.getMinBufferSize failed ($sampleRate Hz / $channels ch)" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
        )
        // 2× 最小缓冲 ≈ 百毫秒级起播延迟。再大延迟明显，再小容易 underrun 爆音。
        .setBufferSizeInBytes(minBuffer * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    track.play()
    return track
}
