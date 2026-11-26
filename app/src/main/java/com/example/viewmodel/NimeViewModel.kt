package com.example.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Display
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.BackingTrackPlayer
import com.example.audio.NimeLocalSynthesizer
import com.example.audio.NimePureDataEngine
import com.example.model.BackingTrackStatus
import com.example.model.FilterType
import com.example.model.MoodPreset
import com.example.model.MusicalScale
import com.example.model.OscillatorType
import com.example.model.ScaleNote
import com.example.model.ScaleNoteBank
import com.example.model.SynthParams
import com.example.model.TouchPoint
import com.example.network.FastApiClient
import com.example.network.MusicGenRequest
import com.example.network.MusicGenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class NimeScreen {
    HOME_SETUP,
    PLAY_SURFACE
}

data class NimeUiState(
    val currentScreen: NimeScreen = NimeScreen.HOME_SETUP,
    val moodInput: String = "Ethereal",
    val activePreset: MoodPreset = DEFAULT_PRESETS[0],
    val activeSynthParams: SynthParams = DEFAULT_PRESETS[0].synthParams,
    val activeScale: MusicalScale = DEFAULT_PRESETS[0].scale,
    val activeScaleNotes: List<ScaleNote> = ScaleNoteBank.build(
        DEFAULT_PRESETS[0].scale,
        DEFAULT_PRESETS[0].rootMidi
    ),
    val activeTouches: Map<Long, TouchPoint> = emptyMap(),
    val tiltZ: Float = 0.5f,
    val tiltRawAngle: Float = 0f,
    val effectiveReverbWet: Float = NimePureDataEngine.computeEffectiveReverbWet(
        DEFAULT_PRESETS[0].synthParams.reverbWet,
        0.5f
    ),
    val isTiltManualOverride: Boolean = false,
    val isScrapbookRecording: Boolean = false,
    val recordingDuration: Float = 0f,
    val statusMessage: String? = null,
    val isGenerating: Boolean = false,
    val isAborting: Boolean = false,
    val activeJobId: String? = null,
    val generationProgress: Float = 0f,
    val generationStage: Int = 0,
    val generationStatusText: String = "",
    val baseCutoffHz: Int = DEFAULT_PRESETS[0].synthParams.filterCutoff,
    val backendUrl: String = FastApiClient.getBaseUrl(),
    val isBackendErrorDialogShown: Boolean = false,
    val backendErrorMessage: String? = null,
    val backingTrackStatus: BackingTrackStatus = BackingTrackStatus.IDLE,
    val backingTrackUrl: String? = null,
    val isBackingTrackMuted: Boolean = false,
    val backingTrackFadeProgress: Float = 0f
) {
    val moodWordCount: Int
        get() {
            val trimmed = moodInput.trim()
            return if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
        }

    val isMoodValid: Boolean
        get() = moodWordCount >= 1
}

val DEFAULT_PRESETS = listOf(
    MoodPreset(
        id = "ethereal",
        name = "Ethereal",
        description = "Airy, floating PWM pad with shimmering Lydian harmonics",
        synthParams = SynthParams(
            oscillatorType = OscillatorType.PWM,
            attack = 0.012f,
            decay = 0.80f,
            sustain = 0.85f,
            release = 2.20f,
            filterCutoff = 3500,
            filterType = FilterType.LOWPASS,
            distortionAmount = 0.05f,
            reverbWet = 0.65f,
            scale = MusicalScale.LYDIAN
        ),
        rootMidi = 48
    ),
    MoodPreset(
        id = "energetic",
        name = "Energetic",
        description = "Crisp, dynamic saw lead for punchy expressive lines",
        synthParams = SynthParams(
            oscillatorType = OscillatorType.SAWTOOTH,
            attack = 0.005f,
            decay = 0.28f,
            sustain = 0.78f,
            release = 0.42f,
            filterCutoff = 4800,
            filterType = FilterType.LOWPASS,
            distortionAmount = 0.28f,
            reverbWet = 0.22f,
            scale = MusicalScale.PENTATONIC_MINOR
        ),
        rootMidi = 48
    ),
    MoodPreset(
        id = "contemplative",
        name = "Contemplative",
        description = "Deep warm sine tones for peaceful Dorian unwinding",
        synthParams = SynthParams(
            oscillatorType = OscillatorType.SINE,
            attack = 0.014f,
            decay = 0.90f,
            sustain = 0.72f,
            release = 2.60f,
            filterCutoff = 1800,
            filterType = FilterType.LOWPASS,
            distortionAmount = 0.0f,
            reverbWet = 0.55f,
            scale = MusicalScale.DORIAN
        ),
        rootMidi = 48
    ),
    MoodPreset(
        id = "mystic",
        name = "Mystic",
        description = "Phrygian mystic intervals with FM bell resonance",
        synthParams = SynthParams(
            oscillatorType = OscillatorType.FMSINE,
            attack = 0.005f,
            decay = 0.55f,
            sustain = 0.32f,
            release = 1.80f,
            filterCutoff = 2600,
            filterType = FilterType.LOWPASS,
            distortionAmount = 0.10f,
            reverbWet = 0.58f,
            scale = MusicalScale.PHRYGIAN
        ),
        rootMidi = 48
    ),
    MoodPreset(
        id = "dreamy",
        name = "Dreamy",
        description = "Smooth triangle waves gliding through major pentatonic reflections",
        synthParams = SynthParams(
            oscillatorType = OscillatorType.TRIANGLE,
            attack = 0.012f,
            decay = 0.65f,
            sustain = 0.75f,
            release = 1.90f,
            filterCutoff = 2200,
            filterType = FilterType.LOWPASS,
            distortionAmount = 0.04f,
            reverbWet = 0.50f,
            scale = MusicalScale.PENTATONIC_MAJOR
        ),
        rootMidi = 48
    )
)

class NimeViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val audioEngine = NimePureDataEngine()

    private val _uiState = MutableStateFlow(NimeUiState())
    val uiState: StateFlow<NimeUiState> = _uiState.asStateFlow()

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val displayManager = application.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val backingTrackPlayer = BackingTrackPlayer(application, viewModelScope)

    private var recordingTimerJob: Job? = null
    private var generationJob: Job? = null
    private var backingTrackPollingJob: Job? = null
    private var lastTickHapticTimeMs: Long = 0L

    init {
        audioEngine.start()
        applyPreset(DEFAULT_PRESETS[0])

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        backingTrackPollingJob?.cancel()
        backingTrackPlayer.release()
        sensorManager.unregisterListener(this)
        audioEngine.stop()
    }

    fun navigateTo(screen: NimeScreen) {
        if (screen != NimeScreen.PLAY_SURFACE) {
            audioEngine.clearAllNotes()
            stopBackingTrackSession()
            _uiState.update { it.copy(activeTouches = emptyMap()) }
        }
        _uiState.update { it.copy(currentScreen = screen, statusMessage = null) }
    }

    fun updateMoodInput(text: String) {
        _uiState.update { it.copy(moodInput = text) }
    }

    fun selectPreset(preset: MoodPreset) {
        applyPreset(preset)
        val currentTilt = _uiState.value.tiltZ
        _uiState.update {
            it.copy(
                activePreset = preset,
                activeSynthParams = preset.synthParams,
                activeScale = preset.scale,
                activeScaleNotes = audioEngine.activeScaleNotes,
                moodInput = preset.name,
                baseCutoffHz = preset.synthParams.filterCutoff,
                effectiveReverbWet = NimePureDataEngine.computeEffectiveReverbWet(
                    preset.synthParams.reverbWet,
                    currentTilt
                )
            )
        }
        triggerHapticTick()
    }

    private fun applyPreset(preset: MoodPreset) {
        audioEngine.applySynthParams(preset.synthParams, preset.rootMidi)
    }

    fun dismissBackendError() {
        _uiState.update { it.copy(isBackendErrorDialogShown = false, backendErrorMessage = null) }
    }

    fun retryBackendGeneration() {
        dismissBackendError()
        tuneAndPlay(_uiState.value.moodInput)
    }

    fun updateBackendUrl(newUrl: String) {
        val normalized = FastApiClient.setBaseUrl(newUrl)
        _uiState.update {
            it.copy(
                backendUrl = normalized,
                statusMessage = "Server URL updated"
            )
        }
    }

    /**
     * "Synthesize & Play" action (Dual-Track Hybrid Mode):
     * 1. Calls `POST /generate` and receives `synth_params` + `effects` in ~1-2 seconds.
     * 2. Immediately configures the live synthesizer and opens the Play Surface so the user can play.
     * 3. Polls `GET /status/{job_id}` every 3 seconds in the background for `backing_track_url`,
     *    then loops it via ExoPlayer with a 3-second volume fade-in.
     */
    fun tuneAndPlay(moodPrompt: String = _uiState.value.moodInput) {
        if (_uiState.value.isGenerating) return

        val promptClean = moodPrompt.trim()
        if (promptClean.isEmpty()) return

        stopBackingTrackSession()

        val matchingPreset = DEFAULT_PRESETS.find { it.name.equals(promptClean, ignoreCase = true) }
            ?: MoodPreset(
                id = UUID.randomUUID().toString(),
                name = promptClean,
                description = "Parametric synth patch designed for $promptClean",
                synthParams = NimeLocalSynthesizer.analyzeMoodToSynthParams(promptClean),
                rootMidi = 48
            )

        _uiState.update {
            it.copy(
                isGenerating = true,
                isAborting = false,
                activeJobId = null,
                generationProgress = 0.15f,
                generationStage = 1,
                generationStatusText = "Connecting to parametric synth server...",
                moodInput = promptClean,
                isBackendErrorDialogShown = false,
                backendErrorMessage = null
            )
        }

        generationJob = viewModelScope.launch {
            try {
                val activeUrl = _uiState.value.backendUrl
                val isReachable = withContext(Dispatchers.IO) {
                    FastApiClient.isServerReachable(activeUrl)
                }
                if (_uiState.value.isAborting) return@launch
                if (!isReachable) {
                    activateLocalSynthesizerFallback(promptClean, matchingPreset)
                    return@launch
                }

                val service = FastApiClient.getService(activeUrl)

                _uiState.update {
                    it.copy(
                        generationProgress = 0.45f,
                        generationStage = 2,
                        generationStatusText = "Designing live synth & effects for '$promptClean'..."
                    )
                }

                val response = withContext(Dispatchers.IO) {
                    try {
                        service.generateMusic(
                            MusicGenRequest(
                                prompt = promptClean,
                                duration = 6,
                                temperature = 1.0f
                            )
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }

                if (_uiState.value.isAborting) return@launch
                if (response == null || !response.isSuccessful || response.body() == null) {
                    activateLocalSynthesizerFallback(promptClean, matchingPreset)
                    return@launch
                }

                val body = response.body()!!
                val remoteJobId = body.jobId

                // Dual-track fast path: backend returned synth_params / effects immediately
                if (body.synthParams != null || body.effects != null ||
                    body.status.equals("generating_background", ignoreCase = true) ||
                    body.status.equals("completed", ignoreCase = true)
                ) {
                    val resolvedParams = resolveBackendParams(body, promptClean, matchingPreset)
                    enterPlaySurfaceWithParams(
                        resolvedParams = resolvedParams,
                        matchingPreset = matchingPreset,
                        jobId = remoteJobId,
                        backingTrackStatus = if (body.status.equals("completed", ignoreCase = true) && !body.backingTrackUrl.isNullOrBlank()) {
                            BackingTrackStatus.FADING_IN
                        } else if (body.status.equals("failed", ignoreCase = true)) {
                            BackingTrackStatus.UNAVAILABLE
                        } else {
                            BackingTrackStatus.GENERATING
                        }
                    )

                    if (body.status.equals("completed", ignoreCase = true) && !body.backingTrackUrl.isNullOrBlank()) {
                        startBackingTrackPlayback(body.backingTrackUrl)
                    } else if (!body.status.equals("failed", ignoreCase = true)) {
                        startBackingTrackPolling(remoteJobId, activeUrl)
                    }
                    return@launch
                }

                // Legacy fallback if a server still queues synth_params asynchronously
                _uiState.update {
                    it.copy(
                        activeJobId = remoteJobId,
                        generationProgress = 0.55f,
                        generationStage = 2,
                        generationStatusText = "Waiting for AI synth parameters..."
                    )
                }

                var resolvedResponse: MusicGenResponse? = null
                var consecutiveFailures = 0

                for (step in 1..40) {
                    delay(1000L)
                    if (_uiState.value.isAborting) return@launch

                    val statusRes = withContext(Dispatchers.IO) {
                        try {
                            service.getJobStatus(remoteJobId)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (_uiState.value.isAborting) return@launch

                    if (statusRes != null && statusRes.isSuccessful && statusRes.body() != null) {
                        consecutiveFailures = 0
                        val statusBody = statusRes.body()!!
                        when (statusBody.status.lowercase()) {
                            "completed", "ready", "generating_background" -> {
                                resolvedResponse = statusBody
                                break
                            }
                            "cancelled", "canceled" -> {
                                resetCancelledGenerationState()
                                return@launch
                            }
                            "error", "failed" -> {
                                activateLocalSynthesizerFallback(promptClean, matchingPreset)
                                return@launch
                            }
                        }
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= 6) break
                    }
                }

                if (_uiState.value.isAborting) return@launch
                if (resolvedResponse == null) {
                    activateLocalSynthesizerFallback(promptClean, matchingPreset)
                    return@launch
                }

                val resolvedParams = resolveBackendParams(resolvedResponse, promptClean, matchingPreset)
                val readyUrl = resolvedResponse.backingTrackUrl
                val isTrackReady = resolvedResponse.status.lowercase() in setOf("completed", "ready") && !readyUrl.isNullOrBlank()
                enterPlaySurfaceWithParams(
                    resolvedParams = resolvedParams,
                    matchingPreset = matchingPreset,
                    jobId = remoteJobId,
                    backingTrackStatus = if (isTrackReady) BackingTrackStatus.FADING_IN else BackingTrackStatus.GENERATING
                )
                if (isTrackReady && readyUrl != null) {
                    startBackingTrackPlayback(readyUrl)
                } else {
                    startBackingTrackPolling(remoteJobId, activeUrl)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                e.printStackTrace()
                if (!_uiState.value.isAborting) {
                    activateLocalSynthesizerFallback(promptClean, matchingPreset)
                }
            }
        }
    }

    private fun resolveBackendParams(
        response: MusicGenResponse,
        promptClean: String,
        matchingPreset: MoodPreset
    ): SynthParams {
        return if (response.synthParams != null) {
            response.synthParams.toDomain(
                fallbackScale = response.scale,
                effects = response.effects
            )
        } else {
            val local = NimeLocalSynthesizer.analyzeMoodToSynthParams(promptClean, matchingPreset)
            val backendScale = response.scale?.let { MusicalScale.fromBackend(it) } ?: local.scale
            local.copy(
                scale = backendScale,
                distortionAmount = response.effects?.distortionMix ?: local.distortionAmount,
                reverbWet = response.effects?.reverbSize ?: local.reverbWet,
                delayTime = response.effects?.delayTime ?: local.delayTime,
                bitcrusher = response.effects?.bitcrusher ?: local.bitcrusher
            ).sanitized()
        }
    }

    private suspend fun enterPlaySurfaceWithParams(
        resolvedParams: SynthParams,
        matchingPreset: MoodPreset,
        jobId: String,
        backingTrackStatus: BackingTrackStatus
    ) {
        audioEngine.applySynthParams(resolvedParams, matchingPreset.rootMidi)
        val builtScaleNotes = audioEngine.activeScaleNotes
        val configuredPreset = matchingPreset.copy(synthParams = resolvedParams)
        val currentTilt = _uiState.value.tiltZ
        val effectiveReverb = NimePureDataEngine.computeEffectiveReverbWet(
            resolvedParams.reverbWet,
            currentTilt
        )
        val statusMsg = "Synth ready: ${resolvedParams.oscillatorType.displayLabel} · ${resolvedParams.scale.displayName}"

        _uiState.update {
            it.copy(
                generationProgress = 1.0f,
                generationStage = 4,
                generationStatusText = "Opening Play Surface..."
            )
        }
        delay(120)
        if (_uiState.value.isAborting) return

        _uiState.update {
            it.copy(
                isGenerating = false,
                isAborting = false,
                activeJobId = jobId,
                generationProgress = 0f,
                generationStage = 0,
                generationStatusText = "",
                activePreset = configuredPreset,
                activeSynthParams = resolvedParams,
                activeScale = resolvedParams.scale,
                activeScaleNotes = builtScaleNotes,
                baseCutoffHz = resolvedParams.filterCutoff,
                effectiveReverbWet = effectiveReverb,
                backingTrackStatus = backingTrackStatus,
                backingTrackUrl = null,
                isBackingTrackMuted = false,
                backingTrackFadeProgress = 0f,
                currentScreen = NimeScreen.PLAY_SURFACE,
                statusMessage = statusMsg
            )
        }
    }

    private fun startBackingTrackPolling(jobId: String, serverUrl: String) {
        backingTrackPollingJob?.cancel()
        backingTrackPollingJob = viewModelScope.launch {
            val service = FastApiClient.getService(serverUrl)
            var consecutiveFailures = 0

            for (attempt in 1..60) {
                delay(3000L)
                if (_uiState.value.currentScreen != NimeScreen.PLAY_SURFACE) {
                    return@launch
                }

                val statusRes = withContext(Dispatchers.IO) {
                    try {
                        service.getJobStatus(jobId)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    }
                }

                if (_uiState.value.currentScreen != NimeScreen.PLAY_SURFACE) {
                    return@launch
                }

                if (statusRes != null && statusRes.isSuccessful && statusRes.body() != null) {
                    consecutiveFailures = 0
                    val statusBody = statusRes.body()!!
                    when (statusBody.status.lowercase()) {
                        "completed", "ready" -> {
                            val trackUrl = statusBody.backingTrackUrl
                            if (!trackUrl.isNullOrBlank()) {
                                startBackingTrackPlayback(trackUrl)
                            } else {
                                _uiState.update {
                                    it.copy(backingTrackStatus = BackingTrackStatus.UNAVAILABLE)
                                }
                            }
                            return@launch
                        }
                        "failed", "error", "cancelled", "canceled" -> {
                            _uiState.update {
                                it.copy(backingTrackStatus = BackingTrackStatus.UNAVAILABLE)
                            }
                            return@launch
                        }
                        else -> {
                            // Continue 3s polling while generating_background
                        }
                    }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 8) {
                        _uiState.update {
                            it.copy(backingTrackStatus = BackingTrackStatus.ERROR)
                        }
                        return@launch
                    }
                }
            }

            _uiState.update { state ->
                if (state.backingTrackStatus == BackingTrackStatus.GENERATING) {
                    state.copy(backingTrackStatus = BackingTrackStatus.UNAVAILABLE)
                } else {
                    state
                }
            }
        }
    }

    private fun startBackingTrackPlayback(url: String) {
        if (_uiState.value.currentScreen != NimeScreen.PLAY_SURFACE) return
        val muted = _uiState.value.isBackingTrackMuted
        _uiState.update {
            it.copy(
                backingTrackUrl = url,
                backingTrackStatus = if (muted) BackingTrackStatus.MUTED else BackingTrackStatus.FADING_IN,
                backingTrackFadeProgress = 0f
            )
        }
        backingTrackPlayer.playLoopWithFadeIn(
            url = url,
            fadeDurationMs = 3000L,
            initiallyMuted = muted,
            onFadeProgress = { gain ->
                _uiState.update { state ->
                    state.copy(backingTrackFadeProgress = gain)
                }
            },
            onFadeCompleted = {
                _uiState.update { state ->
                    val resolved = if (state.isBackingTrackMuted) {
                        BackingTrackStatus.MUTED
                    } else {
                        BackingTrackStatus.PLAYING
                    }
                    state.copy(
                        backingTrackStatus = resolved,
                        backingTrackFadeProgress = 1f
                    )
                }
            },
            onError = { msg ->
                _uiState.update { state ->
                    state.copy(
                        backingTrackStatus = BackingTrackStatus.ERROR,
                        statusMessage = "Atmosphere stream unavailable: $msg"
                    )
                }
            }
        )
    }

    fun toggleBackingTrackMute() {
        val state = _uiState.value
        if (state.backingTrackStatus != BackingTrackStatus.PLAYING &&
            state.backingTrackStatus != BackingTrackStatus.FADING_IN &&
            state.backingTrackStatus != BackingTrackStatus.MUTED
        ) {
            return
        }
        val nextMuted = !state.isBackingTrackMuted
        backingTrackPlayer.setMuted(nextMuted)
        val nextStatus = when {
            nextMuted -> BackingTrackStatus.MUTED
            state.backingTrackFadeProgress < 0.99f -> BackingTrackStatus.FADING_IN
            else -> BackingTrackStatus.PLAYING
        }
        _uiState.update {
            it.copy(
                isBackingTrackMuted = nextMuted,
                backingTrackStatus = nextStatus
            )
        }
        triggerHapticTick()
    }

    private fun stopBackingTrackSession() {
        backingTrackPollingJob?.cancel()
        backingTrackPollingJob = null
        backingTrackPlayer.stop()
        _uiState.update {
            it.copy(
                backingTrackStatus = BackingTrackStatus.IDLE,
                backingTrackUrl = null,
                isBackingTrackMuted = false,
                backingTrackFadeProgress = 0f
            )
        }
    }

    /**
     * Aborts the active backend generation via `POST /abort/{job_id}`,
     * cancels polling immediately, and returns cleanly to Home Setup.
     */
    fun abortGeneration() {
        val currentState = _uiState.value
        if (!currentState.isGenerating || currentState.isAborting) return

        val jobIdToAbort = currentState.activeJobId
        val activeUrl = currentState.backendUrl

        _uiState.update {
            it.copy(
                isAborting = true,
                generationStatusText = "Cancelling generation..."
            )
        }
        triggerHapticTick()

        generationJob?.cancel()
        generationJob = null

        if (!jobIdToAbort.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    FastApiClient.getService(activeUrl).abortJob(jobIdToAbort)
                } catch (_: Exception) {
                    // Best-effort abort notification; UI still returns cleanly to Home
                }
            }
        }

        resetCancelledGenerationState()
    }

    private fun resetCancelledGenerationState() {
        _uiState.update {
            it.copy(
                isGenerating = false,
                isAborting = false,
                activeJobId = null,
                generationProgress = 0f,
                generationStage = 0,
                generationStatusText = "",
                currentScreen = NimeScreen.HOME_SETUP,
                statusMessage = "Generation cancelled"
            )
        }
    }

    private suspend fun activateLocalSynthesizerFallback(
        promptClean: String,
        matchingPreset: MoodPreset
    ) {
        if (_uiState.value.isAborting) return

        _uiState.update {
            it.copy(
                generationProgress = 0.55f,
                generationStage = 2,
                generationStatusText = "Server offline — designing local parametric patch..."
            )
        }

        val knownPreset = DEFAULT_PRESETS.find { it.name.equals(promptClean, ignoreCase = true) }
        val localParams = withContext(Dispatchers.Default) {
            NimeLocalSynthesizer.analyzeMoodToSynthParams(promptClean, knownPreset)
        }

        if (_uiState.value.isAborting) return

        _uiState.update {
            it.copy(
                generationProgress = 0.85f,
                generationStage = 3,
                generationStatusText = "Tuning ${localParams.scale.displayName} (10 notes)..."
            )
        }

        audioEngine.applySynthParams(localParams, matchingPreset.rootMidi)
        val builtScaleNotes = audioEngine.activeScaleNotes

        val configuredPreset = matchingPreset.copy(
            synthParams = localParams
        )
        val currentTilt = _uiState.value.tiltZ
        val effectiveReverb = NimePureDataEngine.computeEffectiveReverbWet(
            localParams.reverbWet,
            currentTilt
        )

        _uiState.update {
            it.copy(
                generationProgress = 1.0f,
                generationStage = 4,
                generationStatusText = "Local patch ready. Opening Play Surface..."
            )
        }
        delay(200)
        if (_uiState.value.isAborting) return

        _uiState.update {
            it.copy(
                isGenerating = false,
                isAborting = false,
                activeJobId = null,
                generationProgress = 0f,
                generationStage = 0,
                generationStatusText = "",
                activePreset = configuredPreset,
                activeSynthParams = localParams,
                activeScale = localParams.scale,
                activeScaleNotes = builtScaleNotes,
                baseCutoffHz = localParams.filterCutoff,
                effectiveReverbWet = effectiveReverb,
                currentScreen = NimeScreen.PLAY_SURFACE,
                statusMessage = "Offline synth: ${localParams.oscillatorType.displayLabel} · ${localParams.scale.displayName}"
            )
        }
    }

    // Continuous X/Y Polyphonic Touch Interaction (X = 10-Note Active Scale, Y = Filter Brightness)
    fun onTouchStart(pointerId: Long, x: Float, y: Float) {
        val touchState = audioEngine.onTouchDown(pointerId, x, y)
        val point = TouchPoint(
            id = pointerId,
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            activeNote = touchState.note,
            filterBrightness = touchState.filterBrightness,
            pressStartTime = System.currentTimeMillis()
        )
        _uiState.update { state ->
            state.copy(activeTouches = state.activeTouches + (pointerId to point))
        }
        triggerHapticTouchDown()
    }

    fun onTouchMove(pointerId: Long, x: Float, y: Float) {
        val clampedX = x.coerceIn(0f, 1f)
        val clampedY = y.coerceIn(0f, 1f)
        val touchState = audioEngine.onTouchMove(pointerId, clampedX, clampedY)
        val existing = _uiState.value.activeTouches[pointerId] ?: return
        val noteChanged = existing.activeNote.index != touchState.note.index
        if (!noteChanged &&
            kotlin.math.abs(existing.x - clampedX) < 0.0015f &&
            kotlin.math.abs(existing.y - clampedY) < 0.0015f
        ) {
            return
        }
        _uiState.update { state ->
            val current = state.activeTouches[pointerId] ?: return@update state
            val updatedPoint = current.copy(
                x = clampedX,
                y = clampedY,
                activeNote = touchState.note,
                filterBrightness = touchState.filterBrightness
            )
            state.copy(activeTouches = state.activeTouches + (pointerId to updatedPoint))
        }
        if (noteChanged) {
            triggerHapticTick()
        }
    }

    fun onTouchEnd(pointerId: Long) {
        val wasActive = _uiState.value.activeTouches.containsKey(pointerId)
        audioEngine.onTouchUp(pointerId)
        _uiState.update { state ->
            state.copy(activeTouches = state.activeTouches - pointerId)
        }
        if (wasActive) {
            triggerHapticTouchUp()
        }
    }

    fun onClearAllTouches() {
        val hadActive = _uiState.value.activeTouches.isNotEmpty()
        audioEngine.clearAllNotes()
        _uiState.update {
            it.copy(activeTouches = emptyMap())
        }
        if (hadActive) {
            triggerHapticTouchUp()
        }
    }

    fun discardRecordingAndNavigateHome() {
        recordingTimerJob?.cancel()
        val context = getApplication<Application>()
        val discardFile = File(context.cacheDir, "nime_discard_${System.currentTimeMillis()}.wav")
        audioEngine.stopScrapbookRecording(discardFile)
        discardFile.delete()
        audioEngine.clearAllNotes()
        stopBackingTrackSession()
        _uiState.update {
            it.copy(
                isScrapbookRecording = false,
                recordingDuration = 0f,
                activeTouches = emptyMap(),
                currentScreen = NimeScreen.HOME_SETUP,
                statusMessage = "Recording discarded"
            )
        }
    }

    // Quick Capture Live Recording to Phone's Download folder
    fun toggleScrapbookRecording(customName: String? = null) {
        val state = _uiState.value
        if (!state.isScrapbookRecording) {
            audioEngine.startScrapbookRecording()
            _uiState.update { it.copy(isScrapbookRecording = true, recordingDuration = 0f) }
            triggerHapticSuccess()

            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                var elapsed = 0f
                while (_uiState.value.isScrapbookRecording) {
                    delay(100)
                    elapsed += 0.1f
                    _uiState.update { it.copy(recordingDuration = elapsed) }
                }
            }
        } else {
            recordingTimerJob?.cancel()
            val context = getApplication<Application>()
            val tempFile = File(context.cacheDir, "nime_temp_${System.currentTimeMillis()}.wav")

            val result = audioEngine.stopScrapbookRecording(tempFile)
            val titleName = customName?.ifBlank { null }
                ?: "${state.activePreset.name}_Synth_${System.currentTimeMillis() % 10000}"
            val sanitizedName = titleName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val fileName = if (sanitizedName.endsWith(".wav", ignoreCase = true)) sanitizedName else "$sanitizedName.wav"

            if (result.success && result.durationSeconds > 0.3f) {
                viewModelScope.launch(Dispatchers.IO) {
                    val savedLocation = saveWavToDownloads(context, tempFile, fileName)
                    tempFile.delete()
                    _uiState.update {
                        it.copy(
                            isScrapbookRecording = false,
                            recordingDuration = 0f,
                            statusMessage = "Saved to Downloads: $savedLocation"
                        )
                    }
                }
            } else {
                tempFile.delete()
                _uiState.update {
                    it.copy(
                        isScrapbookRecording = false,
                        recordingDuration = 0f,
                        statusMessage = if (result.durationSeconds <= 0.3f) "Recording was too short to save" else "Recording failed"
                    )
                }
            }
            triggerHapticSuccess()
        }
    }

    private fun saveWavToDownloads(context: Context, sourceFile: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    return "Download/$fileName"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDownloads != null && (publicDownloads.exists() || publicDownloads.mkdirs())) {
                val destFile = File(publicDownloads, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                return destFile.name
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appDownloads != null && (appDownloads.exists() || appDownloads.mkdirs())) {
                val destFile = File(appDownloads, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                return destFile.name
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return fileName
    }

    fun readAudioWaveform(dest: FloatArray): Float = audioEngine.readWaveformSnapshot(dest)

    // Accelerometer tilt modulation anchored on LLM reverb_wet (orientation-aware)
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || _uiState.value.isTiltManualOverride) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            val tiltAxis = mapTiltAxisForRotation(event.values[0], event.values[1], rotation)
            val normalizedTilt = ((tiltAxis + 4.2f) / 8.4f).coerceIn(0f, 1f)
            audioEngine.tiltModulation = normalizedTilt
            if (kotlin.math.abs(normalizedTilt - _uiState.value.tiltZ) >= 0.004f) {
                _uiState.update { state ->
                    state.copy(
                        tiltZ = normalizedTilt,
                        tiltRawAngle = tiltAxis,
                        effectiveReverbWet = NimePureDataEngine.computeEffectiveReverbWet(
                            state.activeSynthParams.reverbWet,
                            normalizedTilt
                        )
                    )
                }
            }
        }
    }

    companion object {
        internal fun mapTiltAxisForRotation(accelX: Float, accelY: Float, rotation: Int): Float {
            return when (rotation) {
                Surface.ROTATION_90 -> -accelY
                Surface.ROTATION_180 -> -accelX
                Surface.ROTATION_270 -> accelY
                else -> accelX
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun setManualTilt(tilt: Float) {
        val clamped = tilt.coerceIn(0f, 1f)
        audioEngine.tiltModulation = clamped
        _uiState.update { state ->
            state.copy(
                isTiltManualOverride = true,
                tiltZ = clamped,
                tiltRawAngle = (clamped - 0.5f) * 19.6f,
                effectiveReverbWet = NimePureDataEngine.computeEffectiveReverbWet(
                    state.activeSynthParams.reverbWet,
                    clamped
                )
            )
        }
    }

    fun toggleTiltMode() {
        val currentlyManual = _uiState.value.isTiltManualOverride
        if (currentlyManual && accelerometer == null) {
            _uiState.update { it.copy(statusMessage = "Tilt sensor unavailable — manual reverb active") }
            return
        }
        _uiState.update { it.copy(isTiltManualOverride = !currentlyManual) }
        triggerHapticTick()
    }

    private fun triggerHapticTouchDown() {
        vibrateEffect(
            predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_CLICK else 0,
            durationMs = 24L,
            amplitude = 190
        )
    }

    private fun triggerHapticTouchUp() {
        vibrateEffect(
            predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_TICK else 0,
            durationMs = 16L,
            amplitude = 120
        )
    }

    private fun triggerHapticTick() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTickHapticTimeMs < 40L) return
        lastTickHapticTimeMs = now
        vibrateEffect(
            predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_TICK else 0,
            durationMs = 15L,
            amplitude = 110
        )
    }

    private fun triggerHapticSuccess() {
        vibrateEffect(
            predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_HEAVY_CLICK else 0,
            durationMs = 35L,
            amplitude = 210
        )
    }

    private fun vibrateEffect(predefinedId: Int, durationMs: Long, amplitude: Int) {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val effect = VibrationEffect.createPredefined(predefinedId)
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
                vib.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(predefinedId))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}
