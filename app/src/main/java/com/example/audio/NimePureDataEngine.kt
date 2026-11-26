package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.example.model.FilterType
import com.example.model.MusicalScale
import com.example.model.OscillatorType
import com.example.model.ScaleNote
import com.example.model.ScaleNoteBank
import com.example.model.SynthParams
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real-Time Polyphonic Parametric Synthesizer Engine for NIME.
 * Runs on a dedicated URGENT_AUDIO thread using AudioTrack PCM streaming at 44.1 kHz.
 *
 * Signal chain:
 * 1. PolyBLEP / Phase-continuous Oscillator (`sine`, `square`, `triangle`, `sawtooth`, `pwm`, `fmsine`)
 *    with silky legato portamento across the active 10-note [ScaleNote] bank (`scale`),
 *    full ADSR envelope, and Cytomic 2-Pole State-Variable Filter swept along Y (`OPEN` -> `WARM`).
 * 2. BitCrusher (`bitcrusher`: 0 = off, 1..8 = sample-and-hold + bit-depth quantization).
 * 3. Gain-compensated soft-clipping Overdrive (`distortion_mix`: 0.0..1.0).
 * 4. Damped Feedback Delay (`delay_time`: 0.0 = off, 0.1..1.0s) + Ensemble Chorus.
 * 5. Tilt-Modulated Schroeder Hall Reverb anchored on `reverb_size` -> Master Soft Limiter.
 */
class NimePureDataEngine {

    companion object {
        const val SAMPLE_RATE = 44100
        const val SCOPE_SAMPLE_COUNT = 72
        private const val SCOPE_WINDOW_SAMPLES = 256
        private const val RENDER_BLOCK_FRAMES = 256
        private const val CONTROL_RATE_FRAMES = 32
        private const val TWO_PI = (2.0 * PI).toFloat()

        private const val PITCH_GLIDE_STAGE1_COEFF = 0.00055f
        private const val PITCH_GLIDE_STAGE2_COEFF = 0.00045f
        private const val BRIGHTNESS_SLEW_COEFF = 0.0045f
        private const val TILT_SLEW_COEFF = 0.004f
        private const val POLY_HEADROOM_SLEW = 0.0025f
        private const val CHORUS_BUFFER_SIZE = 4096

        /**
         * Combines the LLM's baseline `reverb_wet` (at neutral wrist tilt = 0.5)
         * with live tilt modulation (`0.0` pulls drier, `1.0` expands into cathedral tail).
         */
        fun computeEffectiveReverbWet(baseReverbWet: Float, tilt: Float): Float {
            val base = baseReverbWet.coerceIn(0f, 1f)
            val t = tilt.coerceIn(0f, 1f)
            return if (t <= 0.5f) {
                val frac = t / 0.5f
                (base * (0.20f + 0.80f * frac)).coerceIn(0f, 1f)
            } else {
                val frac = (t - 0.5f) / 0.5f
                (base + (1f - base) * 0.75f * frac).coerceIn(0f, 1f)
            }
        }

        internal inline fun midiToFreqHz(midi: Float): Float {
            return (440.0 * 2.0.pow(((midi - 69f) / 12f).toDouble())).toFloat()
        }
    }

    private enum class EnvStage {
        ATTACK, DECAY, SUSTAIN, RELEASE, DONE
    }

    private class PolyVoice(
        val pointerId: Long,
        initialX: Float,
        initialMidi: Float,
        @Volatile var targetBrightness: Float,
        phaseSeed: Long
    ) {
        @Volatile var targetX: Float = initialX.coerceIn(0f, 1f)
        @Volatile var targetMidi: Float = initialMidi
        var smoothedMidiStage1: Float = initialMidi
        var smoothedMidi: Float = initialMidi
        var currentFreqHz: Float = midiToFreqHz(initialMidi)

        // Normalized oscillator phase accumulators [0.0, 1.0)
        var phase: Double = ((phaseSeed % 7L) * 0.13) % 1.0
        var subPhase: Double = ((phaseSeed % 5L) * 0.19) % 1.0
        var modPhase: Double = ((phaseSeed % 11L) * 0.09) % 1.0

        // ADSR envelope state
        @Volatile var envStage: EnvStage = EnvStage.ATTACK
        var envLevel: Float = 0f

        // Cytomic SVF state & smoothed coefficients
        var smoothedBrightness: Float = targetBrightness.coerceIn(0f, 1f)
        var svfIc1eq: Float = 0f
        var svfIc2eq: Float = 0f
        var svfA1: Float = 0.1f
        var svfA2: Float = 0.05f
        var svfA3: Float = 0.02f
        var svfK: Float = 1.4f
        var levelContour: Float = 0.95f
    }

    private var audioTrack: AudioTrack? = null
    private var synthThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Active parametric patch + dynamic 10-note scale grid
    @Volatile
    var synthParams: SynthParams = SynthParams()
        private set

    @Volatile
    var activeScaleNotes: List<ScaleNote> = ScaleNoteBank.build(MusicalScale.PENTATONIC_MINOR, 48)
        private set

    // Engine parameters (also kept accessible for telemetry & tests)
    @Volatile var tiltModulation: Float = 0.5f
    @Volatile var baseFilterCutoff: Float = 2000f
    @Volatile var currentScale: MusicalScale = MusicalScale.PENTATONIC_MINOR
    @Volatile var rootMidiNote: Int = 48

    // Polyphonic voice pool keyed by pointerId
    private val voices = ConcurrentHashMap<Long, PolyVoice>()
    private val voiceSpawnCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val activeVoiceSnapshot = ArrayList<PolyVoice>(16)

    private var smoothedTilt: Float = 0.5f
    private var smoothedPolyHeadroom: Float = 0.78f
    private var polyHeadroomStep: Float = 0f
    private var smoothedChorusWet: Float = 0.22f
    private var chorusWetStep: Float = 0f
    private var currentReverbWet: Float = 0.20f
    private var reverbWetStep: Float = 0f
    private var currentReverbFeedback: Float = 0.45f
    private var reverbFeedbackStep: Float = 0f

    // Zero-allocation dual-tap harmonic ensemble chorus buffer
    private val chorusBuffer = FloatArray(CHORUS_BUFFER_SIZE)
    private var chorusWriteIdx: Int = 0
    private var chorusPhaseA: Double = 0.0
    private var chorusPhaseB: Double = 0.35
    private var currentDelayA: Float = 490f
    private var delayAStep: Float = 0f
    private var currentDelayB: Float = 1030f
    private var delayBStep: Float = 0f

    // Zero-allocation BitCrusher state
    private var bitcrushCounter: Int = 0
    private var bitcrushHeldSample: Float = 0f

    // Zero-allocation Damped Feedback Delay line (up to 1.0s @ 44.1kHz)
    private val delayBuffer = FloatArray(SAMPLE_RATE + 1)
    private var delayWriteIdx: Int = 0
    private var delayDampState: Float = 0f
    private var currentDelaySamples: Float = 0f
    private var delaySamplesStep: Float = 0f
    private var currentDelayWet: Float = 0f
    private var delayWetStep: Float = 0f

    // Zero-allocation Hall Schroeder reverb tank (18ms pre-delay + 4 damped prime combs + 2 allpass diffusers)
    private val preDelayBuffer = FloatArray(794)
    private var preDelayIdx: Int = 0
    private val combBuffers = arrayOf(
        FloatArray(1987),
        FloatArray(2411),
        FloatArray(2897),
        FloatArray(3463)
    )
    private val combIndices = IntArray(4)
    private val combDampStates = FloatArray(4)
    private val allpassBuffers = arrayOf(
        FloatArray(337),
        FloatArray(743)
    )
    private val allpassIndices = IntArray(2)

    // Live Session Recording
    private val recordedChunks = ArrayList<FloatArray>()
    private var recordedSampleCount: Int = 0
    @Volatile var isRecordingToScrapbook = false

    // Double-buffered real-time audio snapshot for zero-allocation oscilloscope rendering
    private val scopeBufferA = FloatArray(SCOPE_SAMPLE_COUNT)
    private val scopeBufferB = FloatArray(SCOPE_SAMPLE_COUNT)
    private var writeScopeToA = true
    @Volatile private var latestWaveformSamples: FloatArray = FloatArray(SCOPE_SAMPLE_COUNT)
    @Volatile private var latestWaveformRms: Float = 0f

    init {
        applySynthParams(NimeLocalSynthesizer.analyzeMoodToSynthParams("Ethereal"), 48)
    }

    fun applySynthParams(params: SynthParams, rootMidi: Int = 48) {
        val clean = params.sanitized()
        val safeRoot = rootMidi.coerceIn(36, 72)
        val builtNotes = ScaleNoteBank.build(clean.scale, safeRoot)

        synthParams = clean
        rootMidiNote = safeRoot
        currentScale = clean.scale
        baseFilterCutoff = clean.filterCutoff.toFloat()
        activeScaleNotes = builtNotes

        // Update existing voices' target pitch to match the updated scale grid cleanly
        voices.values.forEach { voice ->
            val mappedNote = ScaleNoteBank.noteForX(builtNotes, voice.targetX)
            voice.targetMidi = mappedNote.midiNote.toFloat()
        }
    }

    fun readWaveformSnapshot(dest: FloatArray): Float {
        val snapshot = latestWaveformSamples
        val copyLen = minOf(dest.size, snapshot.size)
        for (i in 0 until copyLen) {
            dest[i] = snapshot[i]
        }
        return latestWaveformRms
    }

    fun start() {
        if (isRunning.get()) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(RENDER_BLOCK_FRAMES * 2)
        val rawBufferSize = maxOf(minBufferSize, RENDER_BLOCK_FRAMES * 2 * 2)
        val bufferSize = (rawBufferSize + 1) and 1.inv()

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = trackBuilder.build()

        audioTrack = track
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release()
            } catch (_: Exception) {}
            audioTrack = null
            return
        }

        isRunning.set(true)
        track.play()

        synthThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {}
            audioLoop()
        }, "NimeParametricAudioThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            synthThread?.join(500)
        } catch (_: Exception) {}
        synthThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        latestWaveformSamples = FloatArray(SCOPE_SAMPLE_COUNT)
        latestWaveformRms = 0f
    }

    data class TouchNoteState(
        val note: ScaleNote,
        val filterBrightness: Float
    )

    fun onTouchDown(touchId: Long, x: Float, y: Float): TouchNoteState {
        val clampedX = x.coerceIn(0f, 1f)
        val scaleNotes = activeScaleNotes
        val note = ScaleNoteBank.noteForX(scaleNotes, clampedX)
        val brightness = (1f - y).coerceIn(0f, 1f)
        val seed = if (voices.isEmpty()) 0L else voiceSpawnCounter.incrementAndGet()
        val voice = PolyVoice(
            pointerId = touchId,
            initialX = clampedX,
            initialMidi = note.midiNote.toFloat(),
            targetBrightness = brightness,
            phaseSeed = seed
        )
        voices[touchId] = voice
        return TouchNoteState(note = note, filterBrightness = brightness)
    }

    fun onTouchMove(touchId: Long, x: Float, y: Float): TouchNoteState {
        val clampedX = x.coerceIn(0f, 1f)
        val scaleNotes = activeScaleNotes
        val note = ScaleNoteBank.noteForX(scaleNotes, clampedX)
        val brightness = (1f - y).coerceIn(0f, 1f)
        val voice = voices[touchId]
        if (voice != null) {
            if (voice.envStage == EnvStage.RELEASE || voice.envStage == EnvStage.DONE) {
                voice.envStage = EnvStage.ATTACK
            }
            voice.targetBrightness = brightness
            voice.targetX = clampedX
            voice.targetMidi = note.midiNote.toFloat()
        } else {
            val seed = if (voices.isEmpty()) 0L else voiceSpawnCounter.incrementAndGet()
            voices[touchId] = PolyVoice(
                pointerId = touchId,
                initialX = clampedX,
                initialMidi = note.midiNote.toFloat(),
                targetBrightness = brightness,
                phaseSeed = seed
            )
        }
        return TouchNoteState(note = note, filterBrightness = brightness)
    }

    fun onTouchUp(touchId: Long) {
        voices[touchId]?.let { voice ->
            if (voice.envStage != EnvStage.DONE) {
                voice.envStage = EnvStage.RELEASE
            }
        }
    }

    fun clearAllNotes() {
        voices.values.forEach { voice ->
            if (voice.envStage != EnvStage.DONE) {
                voice.envStage = EnvStage.RELEASE
            }
        }
    }

    internal fun activeVoiceCount(): Int =
        voices.values.count { it.envStage != EnvStage.RELEASE && it.envStage != EnvStage.DONE }

    internal fun activeNotesForTest(): Set<ScaleNote> {
        val scaleNotes = activeScaleNotes
        return voices.values
            .filter { it.envStage != EnvStage.RELEASE && it.envStage != EnvStage.DONE }
            .map { ScaleNoteBank.noteForX(scaleNotes, it.targetX) }
            .toSet()
    }

    fun startScrapbookRecording() {
        synchronized(recordedChunks) {
            recordedChunks.clear()
            recordedSampleCount = 0
            isRecordingToScrapbook = true
        }
    }

    fun stopScrapbookRecording(outputWavFile: File): ScrapbookAudioResult {
        isRecordingToScrapbook = false
        val bufferCopy: FloatArray
        synchronized(recordedChunks) {
            if (recordedSampleCount <= 0) {
                recordedChunks.clear()
                return ScrapbookAudioResult(0f, FloatArray(0), false)
            }
            bufferCopy = FloatArray(recordedSampleCount)
            var writePos = 0
            for (chunk in recordedChunks) {
                System.arraycopy(chunk, 0, bufferCopy, writePos, chunk.size)
                writePos += chunk.size
            }
            recordedChunks.clear()
            recordedSampleCount = 0
        }

        saveFloatBufferToWav(bufferCopy, SAMPLE_RATE, outputWavFile)
        val duration = bufferCopy.size.toFloat() / SAMPLE_RATE

        val peaks = FloatArray(40)
        val chunkSize = maxOf(1, bufferCopy.size / 40)
        for (i in 0 until 40) {
            var maxVal = 0f
            val start = i * chunkSize
            val end = minOf(bufferCopy.size, start + chunkSize)
            for (j in start until end) {
                val absVal = abs(bufferCopy[j])
                if (absVal > maxVal) maxVal = absVal
            }
            peaks[i] = maxVal.coerceIn(0.05f, 1f)
        }

        return ScrapbookAudioResult(duration, peaks, true)
    }

    data class ScrapbookAudioResult(
        val durationSeconds: Float,
        val waveformPeaks: FloatArray,
        val success: Boolean
    )

    private fun computeDynamicCutoffHz(
        brightness: Float,
        baseCutoff: Float,
        fundamentalHz: Float,
        filterType: FilterType
    ): Float {
        val x = brightness.coerceIn(0f, 1f)
        val shaped = 0.75f * x + 0.25f * (x * x * (3f - 2f * x))

        val (lowFloor, highCeiling) = when (filterType) {
            FilterType.LOWPASS -> {
                val floor = maxOf(380f, fundamentalHz * 1.25f)
                val ceil = minOf(9600f, maxOf(7200f, baseCutoff * 2.1f))
                floor to ceil
            }
            FilterType.HIGHPASS -> {
                val floor = 140f
                val ceil = minOf(4200f, maxOf(1800f, baseCutoff * 1.8f))
                floor to ceil
            }
            FilterType.BANDPASS -> {
                val floor = maxOf(260f, fundamentalHz * 1.1f)
                val ceil = minOf(6800f, maxOf(3800f, baseCutoff * 2.0f))
                floor to ceil
            }
        }

        val midAnchor = baseCutoff.coerceIn(lowFloor * 1.15f, highCeiling * 0.88f)
        val yCutoff = if (shaped < 0.5f) {
            val t = (shaped / 0.5f).coerceIn(0f, 1f)
            lowFloor * (midAnchor / lowFloor).toDouble().pow(t.toDouble()).toFloat()
        } else {
            val t = ((shaped - 0.5f) / 0.5f).coerceIn(0f, 1f)
            midAnchor * (highCeiling / midAnchor).toDouble().pow(t.toDouble()).toFloat()
        }
        return yCutoff.coerceIn(120f, 10000f)
    }

    private inline fun fastTanh(x: Float): Float {
        if (x >= 3f) return 1f
        if (x <= -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    private inline fun chunkSlew(perSampleCoeff: Float, chunkSamples: Int): Float {
        return 1f - (1f - perSampleCoeff).toDouble().pow(chunkSamples.toDouble()).toFloat()
    }

    private inline fun polyBlep(phase: Double, dt: Double): Float {
        if (dt <= 0.0) return 0f
        if (phase < dt) {
            val t = (phase / dt).toFloat()
            return t + t - t * t - 1f
        } else if (phase > 1.0 - dt) {
            val t = ((phase - 1.0) / dt).toFloat()
            return t * t + t + t + 1f
        }
        return 0f
    }

    private fun updateVoiceControlRate(
        voice: PolyVoice,
        chunkSamples: Int,
        params: SynthParams
    ) {
        // 2-stage S-curve continuous pitch glide toward target scale note
        val slew1 = chunkSlew(PITCH_GLIDE_STAGE1_COEFF, chunkSamples)
        val slew2 = chunkSlew(PITCH_GLIDE_STAGE2_COEFF, chunkSamples)
        voice.smoothedMidiStage1 += (voice.targetMidi - voice.smoothedMidiStage1) * slew1
        voice.smoothedMidi += (voice.smoothedMidiStage1 - voice.smoothedMidi) * slew2
        voice.currentFreqHz = midiToFreqHz(voice.smoothedMidi).coerceIn(27.5f, 4186f)

        // Smooth Y-axis brightness and compute Cytomic SVF coefficients
        voice.smoothedBrightness += (voice.targetBrightness - voice.smoothedBrightness) *
            chunkSlew(BRIGHTNESS_SLEW_COEFF, chunkSamples)

        val b = voice.smoothedBrightness.coerceIn(0f, 1f)
        val cutoff = computeDynamicCutoffHz(
            brightness = b,
            baseCutoff = params.filterCutoff.toFloat(),
            fundamentalHz = voice.currentFreqHz,
            filterType = params.filterType
        )
        val g = tan((PI * cutoff.toDouble()) / SAMPLE_RATE.toDouble()).toFloat().coerceIn(0.01f, 0.90f)
        val k = when (params.filterType) {
            FilterType.BANDPASS -> (0.65f - 0.15f * sin(PI * b).toFloat()).coerceIn(0.35f, 0.85f)
            FilterType.HIGHPASS -> (1.35f - 0.25f * b).coerceIn(0.9f, 1.5f)
            FilterType.LOWPASS -> (1.55f - 0.35f * sin(PI * b).toFloat()).coerceIn(1.05f, 1.65f)
        }
        val a1 = 1.0f / (1.0f + g * (g + k))
        val a2 = g * a1
        val a3 = g * a2

        voice.svfK = k
        voice.svfA1 = a1
        voice.svfA2 = a2
        voice.svfA3 = a3

        voice.levelContour = when (params.filterType) {
            FilterType.LOWPASS -> 1.06f - b * 0.18f
            FilterType.HIGHPASS -> 0.92f + (1f - b) * 0.14f
            FilterType.BANDPASS -> 1.15f
        }
    }

    private inline fun renderOscillatorSample(
        voice: PolyVoice,
        oscType: OscillatorType,
        dtPhase: Double
    ): Float {
        val p = voice.phase
        val modP = voice.modPhase

        val sample = when (oscType) {
            OscillatorType.SINE -> {
                sin((TWO_PI * p).toDouble()).toFloat()
            }
            OscillatorType.TRIANGLE -> {
                (1.0 - 4.0 * abs(p - 0.5)).toFloat()
            }
            OscillatorType.SAWTOOTH -> {
                val raw = (2.0 * p - 1.0).toFloat()
                raw - polyBlep(p, dtPhase)
            }
            OscillatorType.SQUARE -> {
                val raw = if (p < 0.5) 1.0f else -1.0f
                val pShifted = p + 0.5
                val pWrapped = if (pShifted >= 1.0) pShifted - 1.0 else pShifted
                raw + polyBlep(p, dtPhase) - polyBlep(pWrapped, dtPhase)
            }
            OscillatorType.PWM -> {
                val duty = 0.5 + 0.28 * sin(TWO_PI * modP)
                val raw = if (p < duty) 1.0f else -1.0f
                var pShift = p + (1.0 - duty)
                if (pShift >= 1.0) pShift -= 1.0
                val pulse = raw + polyBlep(p, dtPhase) - polyBlep(pShift, dtPhase)
                val sub = sin(TWO_PI * voice.subPhase).toFloat() * 0.22f
                (pulse * 0.78f + sub)
            }
            OscillatorType.FMSINE -> {
                val fmIndex = 0.65f + voice.envLevel * 1.75f
                val mod = sin(TWO_PI * voice.subPhase).toFloat() * fmIndex
                val carrier = sin(TWO_PI * p + mod).toFloat()
                val fifth = sin(TWO_PI * ((p * 1.5) % 1.0)).toFloat() * 0.12f
                (carrier * 0.88f + fifth)
            }
        }

        var nextP = p + dtPhase
        if (nextP >= 1.0) nextP -= 1.0
        voice.phase = nextP

        val subDt = if (oscType == OscillatorType.FMSINE) dtPhase * 2.0 else dtPhase * 0.5
        var nextSub = voice.subPhase + subDt
        while (nextSub >= 1.0) nextSub -= 1.0
        voice.subPhase = nextSub

        var nextMod = modP + (0.65 / SAMPLE_RATE.toDouble())
        if (nextMod >= 1.0) nextMod -= 1.0
        voice.modPhase = nextMod

        return sample
    }

    private inline fun advanceAdsrEnvelope(
        voice: PolyVoice,
        attackStep: Float,
        decayStep: Float,
        sustainLevel: Float,
        releaseCoeff: Float
    ): Float {
        when (voice.envStage) {
            EnvStage.ATTACK -> {
                voice.envLevel += attackStep
                if (voice.envLevel >= 1.0f) {
                    voice.envLevel = 1.0f
                    voice.envStage = EnvStage.DECAY
                }
            }
            EnvStage.DECAY -> {
                if (voice.envLevel > sustainLevel) {
                    voice.envLevel = (voice.envLevel - decayStep).coerceAtLeast(sustainLevel)
                } else {
                    voice.envLevel = sustainLevel
                    voice.envStage = EnvStage.SUSTAIN
                }
                if (voice.envLevel <= sustainLevel + 0.0005f) {
                    voice.envLevel = sustainLevel
                    voice.envStage = EnvStage.SUSTAIN
                }
            }
            EnvStage.SUSTAIN -> {
                voice.envLevel += (sustainLevel - voice.envLevel) * 0.002f
            }
            EnvStage.RELEASE -> {
                voice.envLevel *= releaseCoeff
                if (voice.envLevel < 0.0004f) {
                    voice.envLevel = 0f
                    voice.envStage = EnvStage.DONE
                }
            }
            EnvStage.DONE -> {
                voice.envLevel = 0f
            }
        }
        return voice.envLevel
    }

    private inline fun processReverbSample(input: Float, feedback: Float): Float {
        val preDelayed = preDelayBuffer[preDelayIdx]
        preDelayBuffer[preDelayIdx] = input
        val nextPreIdx = preDelayIdx + 1
        preDelayIdx = if (nextPreIdx >= preDelayBuffer.size) 0 else nextPreIdx

        var combSum = 0f
        for (c in 0 until 4) {
            val buf = combBuffers[c]
            val idx = combIndices[c]
            val delayed = buf[idx]
            val damped = delayed * 0.80f + combDampStates[c] * 0.20f
            combDampStates[c] = damped
            buf[idx] = fastTanh((preDelayed + damped * feedback) * 0.5f) * 2.0f
            val nextIdx = idx + 1
            combIndices[c] = if (nextIdx >= buf.size) 0 else nextIdx
            combSum += delayed
        }
        var diff = combSum * 0.42f
        for (a in 0 until 2) {
            val buf = allpassBuffers[a]
            val idx = allpassIndices[a]
            val delayed = buf[idx]
            val v = diff + 0.55f * delayed
            diff = delayed - 0.55f * v
            buf[idx] = v
            val nextIdx = idx + 1
            allpassIndices[a] = if (nextIdx >= buf.size) 0 else nextIdx
        }
        return fastTanh(diff * 0.7f) * 1.35f
    }

    private inline fun readChorusTap(delaySamples: Float): Float {
        var readPos = chorusWriteIdx - delaySamples
        while (readPos < 0f) readPos += CHORUS_BUFFER_SIZE
        val idx0 = readPos.toInt() % CHORUS_BUFFER_SIZE
        val idx1 = (idx0 + 1) % CHORUS_BUFFER_SIZE
        val frac = readPos - readPos.toInt()
        return chorusBuffer[idx0] * (1f - frac) + chorusBuffer[idx1] * frac
    }

    private inline fun readDelayTap(delaySamples: Float): Float {
        val bufLen = delayBuffer.size
        var readPos = delayWriteIdx - delaySamples
        while (readPos < 0f) readPos += bufLen
        val idx0 = readPos.toInt() % bufLen
        val idx1 = (idx0 + 1) % bufLen
        val frac = readPos - readPos.toInt()
        return delayBuffer[idx0] * (1f - frac) + delayBuffer[idx1] * frac
    }

    internal fun renderAudioFramesForTest(frameCount: Int): FloatArray {
        val out = FloatArray(frameCount)
        renderAudioBlock(shortBuffer = null, mixedOutBuffer = out, frameCount = frameCount)
        return out
    }

    internal fun renderMixSnapshotForTest(
        coords: Collection<Pair<Float, Float>>,
        frameCount: Int,
        startOffset: Int = 0
    ): FloatArray {
        val prevVoices = voices.values.toList()
        voices.clear()
        val testIds = ArrayList<Long>(coords.size)
        coords.forEachIndexed { idx, (x, y) ->
            val id = 90000L + idx
            testIds.add(id)
            onTouchDown(id, x, y)
        }
        if (startOffset > 0) {
            renderAudioFramesForTest(startOffset)
        }
        val result = renderAudioFramesForTest(frameCount)
        testIds.forEach { voices.remove(it) }
        prevVoices.forEach { voices[it.pointerId] = it }
        return result
    }

    private fun renderAudioBlock(
        shortBuffer: ShortArray?,
        mixedOutBuffer: FloatArray,
        frameCount: Int
    ): Double {
        val dt = 1.0 / SAMPLE_RATE
        val targetTilt = tiltModulation
        val params = synthParams
        val oscType = params.oscillatorType
        val filterType = params.filterType

        // ADSR per-sample rates (minimum attack 4ms prevents click transients)
        val attackSamples = maxOf(4f, params.attack.coerceIn(0.004f, 2.0f) * SAMPLE_RATE)
        val attackStep = 1.0f / attackSamples
        val decaySamples = maxOf(44f, params.decay.coerceIn(0.05f, 2.0f) * SAMPLE_RATE)
        val sustainLevel = params.sustain.coerceIn(0f, 1f)
        val decayStep = (1.0f - sustainLevel) / decaySamples
        val releaseSamples = maxOf(882f, params.release.coerceIn(0.02f, 5.0f) * SAMPLE_RATE)
        // Exponential decay reaching ~-60dB (0.001) at releaseSamples
        val releaseCoeff = 0.001.pow(1.0 / releaseSamples.toDouble()).toFloat()

        // BitCrusher parameters (0 = off, 1..8 = quantized & sample-held)
        val bitcrushBits = params.bitcrusher.coerceIn(0, 8)
        val bitcrushLevels = if (bitcrushBits > 0) (1 shl bitcrushBits).toFloat() else 1f
        val bitcrushHoldPeriod = when {
            bitcrushBits <= 0 -> 1
            bitcrushBits <= 2 -> 10
            bitcrushBits <= 4 -> 5
            bitcrushBits <= 6 -> 3
            else -> 2
        }

        // Gain-compensated distortion parameters (`effects.distortion_mix`)
        val distortionMix = params.distortionAmount.coerceIn(0f, 1f)
        val distDrive = 1.0f + distortionMix * 8.5f
        val distNormInv = if (distortionMix > 0.005f) 1.0f / fastTanh(distDrive).coerceAtLeast(0.1f) else 1.0f

        // Delay parameters (`effects.delay_time`: 0.0 = off, 0.1..1.0s)
        val delayEnabled = params.delayTime >= 0.05f
        val targetDelaySamples = if (delayEnabled) {
            (params.delayTime.coerceIn(0.1f, 1.0f) * SAMPLE_RATE).coerceIn(44f, SAMPLE_RATE.toFloat())
        } else {
            currentDelaySamples.coerceAtLeast(4410f)
        }
        val targetDelayWet = if (delayEnabled) 0.36f else 0.0f

        // Snapshot active voices without heap allocation
        activeVoiceSnapshot.clear()
        val iter = voices.values.iterator()
        while (iter.hasNext()) {
            activeVoiceSnapshot.add(iter.next())
        }
        val voiceCount = activeVoiceSnapshot.size

        var sumSquares = 0.0
        var offset = 0

        while (offset < frameCount) {
            val chunk = minOf(CONTROL_RATE_FRAMES, frameCount - offset)
            val invChunk = 1f / chunk.toFloat()

            // Control-rate global reverb modulation: LLM reverb_size as base modulated by tilt
            smoothedTilt += (targetTilt - smoothedTilt) * chunkSlew(TILT_SLEW_COEFF, chunk)
            val reverbAmount = computeEffectiveReverbWet(params.reverbWet, smoothedTilt)
            val targetReverbWet = reverbAmount * reverbAmount * 0.48f + reverbAmount * 0.50f
            val targetReverbFeedback = 0.18f + reverbAmount * 0.72f
            reverbWetStep = (targetReverbWet - currentReverbWet) * invChunk
            reverbFeedbackStep = (targetReverbFeedback - currentReverbFeedback) * invChunk

            // Control-rate delay smoothing
            val nextDelaySamples = if (currentDelaySamples <= 1f && delayEnabled) {
                targetDelaySamples
            } else {
                currentDelaySamples + (targetDelaySamples - currentDelaySamples) * chunkSlew(0.0015f, chunk)
            }
            delaySamplesStep = (nextDelaySamples - currentDelaySamples) * invChunk
            val nextDelayWet = currentDelayWet + (targetDelayWet - currentDelayWet) * chunkSlew(0.003f, chunk)
            delayWetStep = (nextDelayWet - currentDelayWet) * invChunk

            var effectiveVoiceLoad = 0f

            for (v in 0 until voiceCount) {
                val voice = activeVoiceSnapshot[v]
                if (voice.envStage == EnvStage.DONE) {
                    voices.remove(voice.pointerId, voice)
                    continue
                }
                updateVoiceControlRate(
                    voice = voice,
                    chunkSamples = chunk,
                    params = params
                )
                effectiveVoiceLoad += voice.envLevel
            }

            // Smooth polyphonic headroom based on active sounding envelopes
            val targetHeadroom = 0.88f / effectiveVoiceLoad.coerceAtLeast(1f).toDouble().pow(0.58).toFloat()
            val nextHeadroom = smoothedPolyHeadroom +
                (targetHeadroom - smoothedPolyHeadroom) * chunkSlew(POLY_HEADROOM_SLEW, chunk)
            polyHeadroomStep = (nextHeadroom - smoothedPolyHeadroom) * invChunk

            val targetChorusWet = if (oscType == OscillatorType.PWM || oscType == OscillatorType.FMSINE) 0.24f else 0.16f
            val nextChorusWet = smoothedChorusWet +
                (targetChorusWet - smoothedChorusWet) * chunkSlew(0.0025f, chunk)
            chorusWetStep = (nextChorusWet - smoothedChorusWet) * invChunk

            chorusPhaseA = (chorusPhaseA + 0.43 * dt * chunk) % 1.0
            chorusPhaseB = (chorusPhaseB + 0.69 * dt * chunk) % 1.0
            val targetDelayA = 490f + 115f * sin(2.0 * PI * chorusPhaseA).toFloat()
            val targetDelayB = 1030f + 160f * sin(2.0 * PI * chorusPhaseB).toFloat()
            delayAStep = (targetDelayA - currentDelayA) * invChunk
            delayBStep = (targetDelayB - currentDelayB) * invChunk

            val chunkEnd = offset + chunk
            for (i in offset until chunkEnd) {
                currentReverbWet += reverbWetStep
                currentReverbFeedback += reverbFeedbackStep
                currentDelaySamples += delaySamplesStep
                currentDelayWet += delayWetStep
                smoothedPolyHeadroom += polyHeadroomStep
                smoothedChorusWet += chorusWetStep
                currentDelayA += delayAStep
                currentDelayB += delayBStep

                var frameSum = 0f

                for (v in 0 until voiceCount) {
                    val voice = activeVoiceSnapshot[v]
                    if (voice.envStage == EnvStage.DONE) continue

                    val env = advanceAdsrEnvelope(
                        voice = voice,
                        attackStep = attackStep,
                        decayStep = decayStep,
                        sustainLevel = sustainLevel,
                        releaseCoeff = releaseCoeff
                    )
                    if (env <= 0.0001f && voice.envStage == EnvStage.DONE) continue

                    val dtPhase = voice.currentFreqHz.toDouble() / SAMPLE_RATE.toDouble()
                    val oscSample = renderOscillatorSample(voice, oscType, dtPhase)

                    // Cytomic 2-Pole State-Variable Filter (LP / HP / BP)
                    val v3 = oscSample - voice.svfIc2eq
                    val v1 = voice.svfA1 * voice.svfIc1eq + voice.svfA2 * v3
                    val v2 = voice.svfIc2eq + voice.svfA2 * voice.svfIc1eq + voice.svfA3 * v3
                    voice.svfIc1eq = 2.0f * v1 - voice.svfIc1eq
                    voice.svfIc2eq = 2.0f * v2 - voice.svfIc2eq

                    val filtered = when (filterType) {
                        FilterType.LOWPASS -> v2
                        FilterType.BANDPASS -> v1
                        FilterType.HIGHPASS -> oscSample - voice.svfK * v1 - v2
                    }

                    frameSum += filtered * voice.levelContour * env * 0.70f
                }

                // 1. Synth Dry Output
                val drySynthSignal = frameSum * smoothedPolyHeadroom

                // 2. BitCrusher Stage (quantization + sample-and-hold)
                val crushedSignal = if (bitcrushBits > 0) {
                    if (bitcrushCounter == 0) {
                        val quantized = kotlin.math.round(drySynthSignal * bitcrushLevels) / bitcrushLevels
                        bitcrushHeldSample = quantized.coerceIn(-1f, 1f)
                    }
                    bitcrushCounter++
                    if (bitcrushCounter >= bitcrushHoldPeriod) bitcrushCounter = 0
                    if (bitcrushBits <= 2) {
                        bitcrushHeldSample * 0.80f + drySynthSignal * 0.20f
                    } else {
                        bitcrushHeldSample
                    }
                } else {
                    bitcrushCounter = 0
                    drySynthSignal
                }

                // 3. Distortion Stage (`effects.distortion_mix`)
                val distortedSignal = if (distortionMix > 0.005f) {
                    val overdriven = fastTanh(crushedSignal * distDrive) * distNormInv
                    crushedSignal * (1f - distortionMix) + overdriven * distortionMix
                } else {
                    crushedSignal
                }

                // 4. Delay Stage (`effects.delay_time`)
                val delayedSignal = if (currentDelayWet > 0.0005f) {
                    val tap = readDelayTap(currentDelaySamples)
                    delayDampState = tap * 0.72f + delayDampState * 0.28f
                    delayBuffer[delayWriteIdx] = fastTanh((distortedSignal + delayDampState * 0.38f) * 0.85f) * 1.15f
                    delayWriteIdx++
                    if (delayWriteIdx >= delayBuffer.size) delayWriteIdx = 0
                    distortedSignal + tap * currentDelayWet
                } else {
                    delayBuffer[delayWriteIdx] = 0f
                    delayWriteIdx++
                    if (delayWriteIdx >= delayBuffer.size) delayWriteIdx = 0
                    delayDampState *= 0.99f
                    distortedSignal
                }

                // Ensemble Chorus thickening
                val tapA = readChorusTap(currentDelayA)
                val tapB = readChorusTap(currentDelayB)
                chorusBuffer[chorusWriteIdx] = fastTanh(delayedSignal * 0.85f) * 1.15f
                chorusWriteIdx++
                if (chorusWriteIdx >= CHORUS_BUFFER_SIZE) chorusWriteIdx = 0

                val ensembleSignal = delayedSignal * (1f - smoothedChorusWet * 0.28f) +
                    ((tapA + tapB) * 0.38f) * smoothedChorusWet

                // 5. Reverb Stage (`effects.reverb_size` + tilt modulation)
                val reverbWet = processReverbSample(ensembleSignal, currentReverbFeedback)
                val dryMix = 1f - currentReverbWet * 0.44f
                val spatialSignal = ensembleSignal * dryMix + reverbWet * currentReverbWet

                // 6. Master Soft Limiter
                val saturated = fastTanh(spatialSignal)
                mixedOutBuffer[i] = saturated
                sumSquares += (saturated * saturated).toDouble()

                if (shortBuffer != null) {
                    shortBuffer[i] = (saturated * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }

            offset = chunkEnd
        }

        if (isRecordingToScrapbook && frameCount > 0) {
            val blockCopy = mixedOutBuffer.copyOf(frameCount)
            synchronized(recordedChunks) {
                if (isRecordingToScrapbook) {
                    recordedChunks.add(blockCopy)
                    recordedSampleCount += frameCount
                }
            }
        }

        return sumSquares
    }

    private fun audioLoop() {
        val shortBuffer = ShortArray(RENDER_BLOCK_FRAMES)
        val mixedOutBuffer = FloatArray(RENDER_BLOCK_FRAMES)

        while (isRunning.get()) {
            val track = audioTrack ?: break
            val sumSquares = renderAudioBlock(shortBuffer, mixedOutBuffer, RENDER_BLOCK_FRAMES)

            // Capture rising-edge zero-crossing triggered window into pre-allocated double buffer
            val rms = sqrt(sumSquares / RENDER_BLOCK_FRAMES).toFloat().coerceIn(0f, 1f)
            val maxTrigger = RENDER_BLOCK_FRAMES - SCOPE_WINDOW_SAMPLES
            var triggerIdx = 0
            if (rms > 0.002f && maxTrigger > 1) {
                for (k in 1 until maxTrigger) {
                    if (mixedOutBuffer[k - 1] <= 0f && mixedOutBuffer[k] > 0f) {
                        triggerIdx = k
                        break
                    }
                }
            }
            val scopeFrame = if (writeScopeToA) scopeBufferA else scopeBufferB
            writeScopeToA = !writeScopeToA

            val step = (SCOPE_WINDOW_SAMPLES - 1).toFloat() / (SCOPE_SAMPLE_COUNT - 1).coerceAtLeast(1)
            for (s in 0 until SCOPE_SAMPLE_COUNT) {
                val srcPos = triggerIdx + s * step
                val idx0 = srcPos.toInt().coerceIn(0, RENDER_BLOCK_FRAMES - 1)
                val idx1 = (idx0 + 1).coerceAtMost(RENDER_BLOCK_FRAMES - 1)
                val frac = srcPos - idx0
                scopeFrame[s] = mixedOutBuffer[idx0] * (1f - frac) + mixedOutBuffer[idx1] * frac
            }
            latestWaveformSamples = scopeFrame
            latestWaveformRms = rms

            val written = track.write(shortBuffer, 0, RENDER_BLOCK_FRAMES, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                break
            }
        }
    }

    private fun saveFloatBufferToWav(buffer: FloatArray, sampleRate: Int, wavFile: File) {
        try {
            wavFile.parentFile?.mkdirs()
            val totalAudioLen = (buffer.size * 2).toLong()
            val totalDataLen = totalAudioLen + 36
            val byteRate = (sampleRate * 2).toLong()

            FileOutputStream(wavFile).use { out ->
                val header = ByteArray(44)
                header[0] = 'R'.code.toByte()
                header[1] = 'I'.code.toByte()
                header[2] = 'F'.code.toByte()
                header[3] = 'F'.code.toByte()
                header[4] = (totalDataLen and 0xffL).toByte()
                header[5] = (totalDataLen shr 8 and 0xffL).toByte()
                header[6] = (totalDataLen shr 16 and 0xffL).toByte()
                header[7] = (totalDataLen shr 24 and 0xffL).toByte()
                header[8] = 'W'.code.toByte()
                header[9] = 'A'.code.toByte()
                header[10] = 'V'.code.toByte()
                header[11] = 'E'.code.toByte()
                header[12] = 'f'.code.toByte()
                header[13] = 'm'.code.toByte()
                header[14] = 't'.code.toByte()
                header[15] = ' '.code.toByte()
                header[16] = 16
                header[17] = 0
                header[18] = 0
                header[19] = 0
                header[20] = 1 // PCM format
                header[21] = 0
                header[22] = 1 // 1 channel Mono
                header[23] = 0
                header[24] = (sampleRate and 0xff).toByte()
                header[25] = (sampleRate shr 8 and 0xff).toByte()
                header[26] = (sampleRate shr 16 and 0xff).toByte()
                header[27] = (sampleRate shr 24 and 0xff).toByte()
                header[28] = (byteRate and 0xffL).toByte()
                header[29] = (byteRate shr 8 and 0xffL).toByte()
                header[30] = (byteRate shr 16 and 0xffL).toByte()
                header[31] = (byteRate shr 24 and 0xffL).toByte()
                header[32] = 2 // Block align
                header[33] = 0
                header[34] = 16 // Bits per sample
                header[35] = 0
                header[36] = 'd'.code.toByte()
                header[37] = 'a'.code.toByte()
                header[38] = 't'.code.toByte()
                header[39] = 'a'.code.toByte()
                header[40] = (totalAudioLen and 0xffL).toByte()
                header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
                header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
                header[43] = (totalAudioLen shr 24 and 0xffL).toByte()

                out.write(header)

                val byteBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (f in buffer) {
                    val pcmVal = (f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    byteBuffer.putShort(pcmVal)
                }
                out.write(byteBuffer.array())
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
