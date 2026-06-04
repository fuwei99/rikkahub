package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TtsRegexRule
import java.util.UUID

private const val TAG = "TtsController"

/**
 * TTS Controller
 */
class TtsController(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Components
    private val audio = AudioPlayer(context)

    // Native System TTS
    private var nativeTts: TextToSpeech? = null
    private var isNativeTtsInitialized = false

    // Provider & Jobs
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var isPaused = false

    // StateFlows
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // Unified playback state
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // Sync player state to unified state
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
    }

    /** Select/deselect provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    private fun initNativeTts() {
        if (nativeTts == null) {
            nativeTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isNativeTtsInitialized = true
                    nativeTts?.setLanguage(Locale.getDefault())
                    nativeTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.update { true }
                            _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.update { false }
                            _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.update { false }
                            _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                        }
                    })
                }
            }
        }
    }

    private fun getCacheFile(messageId: String, format: AudioFormat = AudioFormat.MP3): File {
        val dir = File(context.cacheDir, "tts_cache")
        if (!dir.exists()) dir.mkdirs()
        val ext = when (format) {
            AudioFormat.PCM, AudioFormat.WAV -> "wav"
            AudioFormat.MP3 -> "mp3"
            else -> format.name.lowercase()
        }
        return File(dir, "tts_${messageId}.$ext")
    }

    private fun getExpirationDurationMs(type: String, customDays: Int): Long? {
        return when (type) {
            "5h" -> 5L * 60 * 60 * 1000
            "1d" -> 24L * 60 * 60 * 1000
            "7d" -> 7L * 24 * 60 * 60 * 1000
            "permanent" -> null
            "custom" -> customDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
            else -> 24L * 60 * 60 * 1000
        }
    }

    private fun checkAndGetCacheFile(
        messageId: String,
        cacheEnabled: Boolean,
        expirationType: String,
        customDays: Int
    ): File? {
        if (!cacheEnabled) return null
        val mp3File = getCacheFile(messageId, AudioFormat.MP3)
        val wavFile = getCacheFile(messageId, AudioFormat.WAV)
        val aacFile = getCacheFile(messageId, AudioFormat.AAC)
        val file = when {
            mp3File.exists() -> mp3File
            wavFile.exists() -> wavFile
            aacFile.exists() -> aacFile
            else -> return null
        }

        val expirationMs = getExpirationDurationMs(expirationType, customDays)
        if (expirationMs != null) {
            val ageMs = System.currentTimeMillis() - file.lastModified()
            if (ageMs > expirationMs) {
                file.delete()
                return null
            }
        }
        return file
    }

    private fun cleanExpiredCaches(expirationType: String, customDays: Int) {
        val dir = File(context.cacheDir, "tts_cache")
        if (!dir.exists() || !dir.isDirectory) return
        val expirationMs = getExpirationDurationMs(expirationType, customDays) ?: return

        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("tts_") && (file.name.endsWith(".mp3") || file.name.endsWith(".wav") || file.name.endsWith(".aac"))) {
                val ageMs = now - file.lastModified()
                if (ageMs > expirationMs) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Speak text
     */
    fun speak(
        text: String,
        flush: Boolean = true,
        messageId: UUID? = null,
        cacheEnabled: Boolean = true,
        expirationType: String = "1d",
        customDays: Int = 1
    ) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        // Clean expired caches asynchronously
        if (cacheEnabled) {
            scope.launch(Dispatchers.IO) {
                cleanExpiredCaches(expirationType, customDays)
            }
        }

        // 1. Try to play from local cache file
        if (messageId != null) {
            val cacheFile = checkAndGetCacheFile(messageId.toString(), cacheEnabled, expirationType, customDays)
            if (cacheFile != null) {
                if (flush) {
                    internalReset()
                }
                _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                _isSpeaking.update { true }
                scope.launch {
                    try {
                        val format = when {
                            cacheFile.name.endsWith(".wav") -> AudioFormat.WAV
                            cacheFile.name.endsWith(".aac") -> AudioFormat.AAC
                            else -> AudioFormat.MP3
                        }
                        val response = TTSResponse(
                            audioData = cacheFile.readBytes(),
                            format = format
                        )
                        audio.play(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to play cached audio", e)
                        _error.update { "Failed to play cached audio" }
                    } finally {
                        _isSpeaking.update { false }
                        _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                    }
                }
                return
            }
        }

        // 2. Route based on provider
        val filteredText = applyRegexFilters(text, provider)

        if (filteredText.isBlank()) return

        if (provider is TTSProviderSetting.SystemTTS) {
            // System TTS uses native Speak streaming output
            if (flush) {
                nativeTts?.stop()
            }
            initNativeTts()
            _isSpeaking.update { true }
            _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
            val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = UUID.randomUUID().toString()
            nativeTts?.speak(filteredText, queueMode, null, utteranceId)
        } else {
            // Cloud TTS: Single call to generateSpeech flow + progressive streaming playback
            if (flush) {
                internalReset()
            }
            _isSpeaking.update { true }
            _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
            
            workerJob = scope.launch {
                try {
                    val flow = ttsManager.generateSpeech(provider, TTSRequest(filteredText))
                    audio.playStream(
                        flow = flow,
                        messageId = messageId?.toString(),
                        cacheEnabled = cacheEnabled,
                        getCacheFileFunc = ::getCacheFile
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming synthesis error", e)
                    _error.update { e.message ?: "TTS synthesis error" }
                } finally {
                    _isSpeaking.update { false }
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }
    private fun applyRegexFilters(text: String, provider: TTSProviderSetting): String {
        var filteredText = text
        try {
            if (provider.filterRegex.isNotEmpty()) {
                filteredText = filteredText.replace(Regex(provider.filterRegex), provider.replaceWith)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply legacy TTS regex filter: ${provider.filterRegex}", e)
        }

        provider.regexRules.forEach { rule ->
            if (rule.enabled && rule.pattern.isNotEmpty()) {
                try {
                    filteredText = filteredText.replace(Regex(rule.pattern), rule.replaceWith)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply TTS regex rule '${rule.name}': ${rule.pattern}", e)
                }
            }
        }
        return filteredText
    }

    /**
     * Synthesize full text in background and save to local persistent cache
     */
    fun cacheSpeechInBackground(
        messageId: UUID,
        text: String,
        cacheEnabled: Boolean,
        expirationType: String,
        customDays: Int
    ) {
        if (!cacheEnabled) return
        val provider = currentProvider ?: return

        val filteredText = applyRegexFilters(text, provider)

        if (filteredText.isBlank()) return

        scope.launch(Dispatchers.IO) {
            try {
                val flow = ttsManager.generateSpeech(provider, TTSRequest(filteredText))
                var format: AudioFormat? = null
                var sampleRate: Int? = 24000
                var cacheOut: FileOutputStream? = null
                var cacheFile: File? = null
                var pcmBytesWritten = 0

                flow.collect { chunk ->
                    if (cacheOut == null) {
                        format = chunk.format
                        sampleRate = chunk.sampleRate ?: 24000
                        val fileFormat = if (chunk.format == AudioFormat.PCM) AudioFormat.WAV else chunk.format
                        val file = getCacheFile(messageId.toString(), fileFormat)
                        cacheFile = file
                        cacheOut = FileOutputStream(file)

                        if (chunk.format == AudioFormat.PCM) {
                            val header = audio.writeWavHeader(sampleRate!!)
                            cacheOut?.write(header)
                        }
                    }
                    cacheOut?.write(chunk.data)
                    if (chunk.format == AudioFormat.PCM) {
                        pcmBytesWritten += chunk.data.size
                    }
                }

                cacheOut?.flush()
                cacheOut?.close()
                
                if (format == AudioFormat.PCM && cacheFile != null) {
                    audio.fixWavHeaderSize(cacheFile!!, pcmBytesWritten)
                }
                Log.d(TAG, "Successfully cached speech in background for message: $messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache speech in background for message: $messageId", e)
            }
        }
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        nativeTts?.stop()
        isPaused = false
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** Pause playback */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** Resume playback */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** Fast forward */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** Set speed */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** Skip next */
    fun skipNext() {
        // No-op in single streaming mode
    }

    /** Stop and clear */
    fun stop() {
        internalReset()
    }

    /** Release resources */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
        nativeTts?.shutdown()
    }
}
