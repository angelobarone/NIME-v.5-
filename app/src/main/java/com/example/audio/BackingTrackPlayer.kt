package com.example.audio

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gapless Backing Track Player for NIME.
 *
 * For WAV streams (MusicGen's 30s ambient tracks), downloads once with ngrok headers,
 * decodes and builds an in-memory 4.5-second equal-power crossfade loop via [SeamlessLoopProcessor],
 * and plays it from a sample-accurate circular `AudioTrack` buffer so looping is completely
 * uninterrupted and free of fade dips or seek gaps.
 *
 * Falls back automatically to Media3 ExoPlayer (`REPEAT_MODE_ONE`) for non-WAV formats.
 */
class BackingTrackPlayer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val SAMPLE_RATE = SeamlessLoopProcessor.TARGET_SAMPLE_RATE
        private const val BLOCK_FRAMES = 1024
        private const val MUTE_SLEW_COEFF = 0.003f
        private const val BACKING_TRACK_MAX_GAIN = 0.22f
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // Primary sample-accurate PCM loop engine
    private var pcmAudioTrack: AudioTrack? = null
    private var pcmLoopThread: Thread? = null
    private val isPcmPlaying = AtomicBoolean(false)

    // Fallback ExoPlayer for non-WAV formats
    private var exoPlayer: ExoPlayer? = null
    private var activeListener: Player.Listener? = null

    private var loadAndFadeJob: Job? = null

    @Volatile private var currentFadeGain: Float = 0f
    @Volatile private var targetMuteGain: Float = 1f
    @Volatile private var isMuted: Boolean = false

    @OptIn(UnstableApi::class)
    private fun ensureExoPlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("ngrok-skip-browser-warning" to "true"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(audioAttributes, false)
            .build()
            .also { exoPlayer = it }
    }

    /**
     * Fetches [url], prepares a gapless equal-power crossfaded loop (or ExoPlayer fallback),
     * starts playback at volume 0.0, and smoothly ramps volume to 1.0 over [fadeDurationMs].
     */
    fun playLoopWithFadeIn(
        url: String,
        fadeDurationMs: Long = 3000L,
        initiallyMuted: Boolean = false,
        onFadeProgress: (Float) -> Unit = {},
        onFadeCompleted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stop()
        isMuted = initiallyMuted
        targetMuteGain = if (initiallyMuted) 0f else 1f
        currentFadeGain = 0f

        loadAndFadeJob = scope.launch {
            try {
                val decodedWav = withContext(Dispatchers.IO) {
                    downloadAndDecodeWav(url)
                }

                if (decodedWav != null) {
                    val loopBuffer = withContext(Dispatchers.Default) {
                        SeamlessLoopProcessor.buildCrossfadeLoop(
                            rawSamples = decodedWav.samples,
                            sourceSampleRate = decodedWav.sampleRate,
                            targetSampleRate = SAMPLE_RATE
                        )
                    }
                    if (loopBuffer.isNotEmpty()) {
                        startPcmLoopTrack(loopBuffer)
                        runFadeInCurve(
                            fadeDurationMs = fadeDurationMs,
                            onFadeProgress = onFadeProgress,
                            onFadeCompleted = onFadeCompleted
                        )
                        return@launch
                    }
                }

                // Fallback to ExoPlayer if stream isn't standard PCM WAV
                startExoPlayerLoop(
                    url = url,
                    fadeDurationMs = fadeDurationMs,
                    onFadeProgress = onFadeProgress,
                    onFadeCompleted = onFadeCompleted,
                    onError = onError
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onError(e.message ?: "Backing track playback failed")
            }
        }
    }

    private fun downloadAndDecodeWav(url: String): WavDecoder.DecodedAudio? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("ngrok-skip-browser-warning", "true")
                .header("Accept", "audio/wav, audio/*, */*")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                WavDecoder.decodeWav(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startPcmLoopTrack(loopBuffer: FloatArray) {
        stopPcmLoop()

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BLOCK_FRAMES * 2)
        val bufferSize = ((maxOf(minBuf * 2, BLOCK_FRAMES * 4 * 2)) + 1) and 1.inv()

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AndroidAudioAttributes.Builder()
                    .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                    .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release()
            } catch (_: Exception) {}
            throw IllegalStateException("AudioTrack initialization failed for backing track")
        }

        pcmAudioTrack = track
        isPcmPlaying.set(true)
        track.play()

        pcmLoopThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Throwable) {}

            val shortChunk = ShortArray(BLOCK_FRAMES)
            val loopLen = loopBuffer.size
            var readIdx = 0
            var smoothedMuteGain = targetMuteGain
            var smoothedFadeGain = currentFadeGain

            while (isPcmPlaying.get()) {
                val activeTrack = pcmAudioTrack ?: break
                val tgtFade = currentFadeGain
                val tgtMute = targetMuteGain

                for (i in 0 until BLOCK_FRAMES) {
                    smoothedFadeGain += (tgtFade - smoothedFadeGain) * MUTE_SLEW_COEFF
                    smoothedMuteGain += (tgtMute - smoothedMuteGain) * MUTE_SLEW_COEFF
                    val sample = loopBuffer[readIdx] * smoothedFadeGain * smoothedMuteGain * BACKING_TRACK_MAX_GAIN
                    shortChunk[i] = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    readIdx++
                    if (readIdx >= loopLen) readIdx = 0
                }

                val written = activeTrack.write(shortChunk, 0, BLOCK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        }, "NimeBackingLoopThread").apply {
            start()
        }
    }

    private fun startExoPlayerLoop(
        url: String,
        fadeDurationMs: Long,
        onFadeProgress: (Float) -> Unit,
        onFadeCompleted: () -> Unit,
        onError: (String) -> Unit
    ) {
        val player = ensureExoPlayer()
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0f

        var fadeStarted = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !fadeStarted) {
                    fadeStarted = true
                    scope.launch(Dispatchers.Main.immediate) {
                        runFadeInCurve(fadeDurationMs, onFadeProgress, onFadeCompleted)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(error.message ?: "Backing track playback failed")
            }
        }

        activeListener = listener
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    private suspend fun runFadeInCurve(
        fadeDurationMs: Long,
        onFadeProgress: (Float) -> Unit,
        onFadeCompleted: () -> Unit
    ) {
        val steps = 60
        val stepDelayMs = (fadeDurationMs / steps).coerceAtLeast(16L)
        for (step in 1..steps) {
            val t = step.toFloat() / steps.toFloat()
            val gain = t * t * (3f - 2f * t)
            currentFadeGain = gain
            exoPlayer?.volume = if (isMuted) 0f else gain * BACKING_TRACK_MAX_GAIN
            onFadeProgress(gain)
            delay(stepDelayMs)
        }
        currentFadeGain = 1f
        exoPlayer?.volume = if (isMuted) 0f else BACKING_TRACK_MAX_GAIN
        onFadeProgress(1f)
        onFadeCompleted()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        targetMuteGain = if (muted) 0f else 1f
        exoPlayer?.volume = if (muted) 0f else currentFadeGain * BACKING_TRACK_MAX_GAIN
    }

    private fun stopPcmLoop() {
        isPcmPlaying.set(false)
        try {
            pcmLoopThread?.join(400)
        } catch (_: Exception) {}
        pcmLoopThread = null

        try {
            pcmAudioTrack?.pause()
            pcmAudioTrack?.flush()
            pcmAudioTrack?.stop()
            pcmAudioTrack?.release()
        } catch (_: Exception) {}
        pcmAudioTrack = null
    }

    fun stop() {
        loadAndFadeJob?.cancel()
        loadAndFadeJob = null
        currentFadeGain = 0f
        stopPcmLoop()

        exoPlayer?.let { player ->
            activeListener?.let { listener ->
                player.removeListener(listener)
            }
            activeListener = null
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.volume = 0f
        }
    }

    fun release() {
        stop()
        exoPlayer?.release()
        exoPlayer = null
    }
}
