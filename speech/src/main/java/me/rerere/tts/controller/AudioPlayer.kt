package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class StreamingDataSource : DataSource {
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var isCompleted = false
    private var currentChunk: ByteArray? = null
    private var currentChunkOffset = 0
    private var opened = false
    private var uri: Uri = Uri.EMPTY

    fun write(data: ByteArray) {
        if (data.isNotEmpty()) {
            queue.put(data)
        }
    }

    fun complete() {
        isCompleted = true
        queue.put(ByteArray(0)) // End marker
    }

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        // Not used
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        opened = true
        return androidx.media3.common.C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return -1

        if (currentChunk == null || currentChunkOffset >= currentChunk!!.size) {
            if (isCompleted && queue.isEmpty()) {
                return -1
            }
            try {
                val next = queue.take()
                if (next.isEmpty()) {
                    return -1
                }
                currentChunk = next
                currentChunkOffset = 0
            } catch (e: InterruptedException) {
                throw java.io.InterruptedIOException(e.message)
            }
        }

        val chunk = currentChunk ?: return -1
        val available = chunk.size - currentChunkOffset
        val toRead = minOf(length, available)
        System.arraycopy(chunk, currentChunkOffset, buffer, offset, toRead)
        currentChunkOffset += toRead
        return toRead
    }

    override fun getUri(): Uri = uri

    override fun close() {
        opened = false
        queue.clear()
        currentChunk = null
    }
}

class AudioPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null

    fun pause() = player.pause()
    fun resume() = player.play()
    fun stop() = player.stop()
    fun clear() = player.clearMediaItems()
    fun release() = player.release()
    fun seekBy(ms: Long) = player.seekTo(player.currentPosition + ms)
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(speed = speed) }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(response: TTSResponse) = suspendCancellableCoroutine<Unit> { cont ->
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000)
        } else response.audioData

        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()

        _playbackState.update {
            it.copy(
                status = PlaybackStatus.Buffering,
                positionMs = 0L,
                durationMs = (response.duration?.times(1000))?.toLong() ?: it.durationMs
            )
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                _playbackState.update { it.copy(status = status) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        }
        player.addListener(listener)
        cont.invokeOnCancellation {
            player.removeListener(listener)
            player.stop()
            stopPositionUpdates()
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun playStream(
        flow: Flow<me.rerere.tts.model.AudioChunk>,
        messageId: String?,
        cacheEnabled: Boolean,
        getCacheFileFunc: (String, AudioFormat) -> File
    ) = suspendCancellableCoroutine<Unit> { cont ->
        var streamingDataSource: StreamingDataSource? = null
        var cacheFile: File? = null
        var cacheOut: FileOutputStream? = null
        var format: AudioFormat? = null
        var sampleRate: Int? = 24000
        var pcmBytesWritten = 0
        var collectJob: Job? = null

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                _playbackState.update { it.copy(status = status) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        }

        player.addListener(listener)

        collectJob = scope.launch(Dispatchers.IO) {
            try {
                flow.collect { chunk ->
                    if (streamingDataSource == null) {
                        format = chunk.format
                        sampleRate = chunk.sampleRate ?: 24000
                        val ds = StreamingDataSource()
                        streamingDataSource = ds

                        if (messageId != null && cacheEnabled) {
                            val fileFormat = if (chunk.format == AudioFormat.PCM) AudioFormat.WAV else chunk.format
                            val file = getCacheFileFunc(messageId, fileFormat)
                            cacheFile = file
                            cacheOut = FileOutputStream(file)
                        }

                        if (chunk.format == AudioFormat.PCM) {
                            val header = writeWavHeader(sampleRate!!)
                            ds.write(header)
                            cacheOut?.write(header)
                        }

                        scope.launch(Dispatchers.Main) {
                            val dataSourceFactory = DataSource.Factory { ds }
                            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
                            player.setMediaSource(mediaSource)
                            player.prepare()
                            player.play()
                            _playbackState.update {
                                it.copy(
                                    status = PlaybackStatus.Buffering,
                                    positionMs = 0L
                                )
                            }
                        }
                    }

                    streamingDataSource?.write(chunk.data)
                    cacheOut?.write(chunk.data)
                    if (chunk.format == AudioFormat.PCM) {
                        pcmBytesWritten += chunk.data.size
                    }
                }

                streamingDataSource?.complete()
                cacheOut?.flush()
                cacheOut?.close()
                cacheOut = null

                if (format == AudioFormat.PCM && cacheFile != null) {
                    fixWavHeaderSize(cacheFile!!, pcmBytesWritten)
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error during stream collect", e)
                streamingDataSource?.complete()
                try {
                    cacheOut?.close()
                } catch (ex: Exception) {}
                cacheOut = null
                cacheFile?.delete()
                scope.launch(Dispatchers.Main) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

        cont.invokeOnCancellation {
            player.removeListener(listener)
            player.stop()
            stopPositionUpdates()
            collectJob?.cancel()
            streamingDataSource?.close()
            try {
                cacheOut?.close()
            } catch (e: Exception) {}
            cacheFile?.delete()
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun writeWavHeader(sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val out = ByteArrayOutputStream()
        val totalSize = 0xFFFFFFFF.toLong() // Unknown length
        val byteRate = sampleRate * channels * bitsPerSample / 8
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes((totalSize - 8).toInt()))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes((totalSize - 44).toInt()))
        }
        return out.toByteArray()
    }

    fun fixWavHeaderSize(file: File, pcmSize: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                // Seek to RIFF size (offset 4)
                raf.seek(4)
                raf.write(intToBytes(pcmSize + 36))
                
                // Seek to data size (offset 40)
                raf.seek(40)
                raf.write(intToBytes(pcmSize))
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Failed to fix WAV header sizes in cache file", e)
        }
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
