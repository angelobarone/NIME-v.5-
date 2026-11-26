package com.example.model

import kotlin.math.pow

/**
 * Oscillator waveforms supported by the parametric backend (`llm_gen.py`)
 * and rendered in real time by [com.example.audio.NimePureDataEngine].
 */
enum class OscillatorType(val backendKey: String, val displayLabel: String) {
    SINE("sine", "Sine"),
    SQUARE("square", "Square"),
    TRIANGLE("triangle", "Triangle"),
    SAWTOOTH("sawtooth", "Sawtooth"),
    PWM("pwm", "PWM"),
    FMSINE("fmsine", "FM Sine");

    companion object {
        fun fromBackend(raw: String?): OscillatorType {
            return when (raw?.trim()?.lowercase()) {
                "sine" -> SINE
                "square" -> SQUARE
                "triangle" -> TRIANGLE
                "sawtooth", "saw" -> SAWTOOTH
                "pwm" -> PWM
                "fmsine", "fm_sine", "fm" -> FMSINE
                else -> TRIANGLE
            }
        }
    }
}

/**
 * State-Variable Filter modes supported by the parametric backend (`llm_gen.py`).
 */
enum class FilterType(val backendKey: String, val displayLabel: String) {
    LOWPASS("lowpass", "Lowpass"),
    HIGHPASS("highpass", "Highpass"),
    BANDPASS("bandpass", "Bandpass");

    companion object {
        fun fromBackend(raw: String?): FilterType {
            return when (raw?.trim()?.lowercase()) {
                "lowpass", "lp" -> LOWPASS
                "highpass", "hp" -> HIGHPASS
                "bandpass", "bp" -> BANDPASS
                else -> LOWPASS
            }
        }
    }
}

/**
 * Complete parametric synthesizer patch returned by `llm_gen.py` or derived locally offline.
 */
data class SynthParams(
    val oscillatorType: OscillatorType = OscillatorType.TRIANGLE,
    val attack: Float = 0.01f,
    val decay: Float = 0.2f,
    val sustain: Float = 0.2f,
    val release: Float = 1.0f,
    val filterCutoff: Int = 2000,
    val filterType: FilterType = FilterType.LOWPASS,
    val distortionAmount: Float = 0.0f,
    val reverbWet: Float = 0.2f,
    val delayTime: Float = 0.0f,
    val bitcrusher: Int = 0,
    val scale: MusicalScale = MusicalScale.PENTATONIC_MINOR
) {
    fun sanitized(): SynthParams = copy(
        attack = attack.coerceIn(0.001f, 2.0f),
        decay = decay.coerceIn(0.05f, 2.0f),
        sustain = sustain.coerceIn(0.0f, 1.0f),
        release = release.coerceIn(0.05f, 5.0f),
        filterCutoff = filterCutoff.coerceIn(200, 10000),
        distortionAmount = distortionAmount.coerceIn(0.0f, 1.0f),
        reverbWet = reverbWet.coerceIn(0.0f, 1.0f),
        delayTime = if (delayTime < 0.05f) 0.0f else delayTime.coerceIn(0.1f, 1.0f),
        bitcrusher = bitcrusher.coerceIn(0, 8)
    )
}

/**
 * Single pitch lane in the dynamic 10-note scale grid on the X-axis.
 */
data class ScaleNote(
    val index: Int,
    val noteName: String,
    val midiNote: Int,
    val frequencyHz: Double,
    val isOctaveStart: Boolean = false
)

object ScaleNoteBank {
    const val LANE_COUNT = 10
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun build(
        scale: MusicalScale,
        rootMidi: Int = 48,
        count: Int = LANE_COUNT
    ): List<ScaleNote> {
        val intervals = if (scale.intervals.isEmpty()) intArrayOf(0, 3, 5, 7, 10) else scale.intervals
        val safeRoot = rootMidi.coerceIn(36, 72)
        return List(count) { idx ->
            val octaveOffset = idx / intervals.size
            val degreeInterval = intervals[idx % intervals.size]
            val midi = safeRoot + octaveOffset * 12 + degreeInterval
            val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
            ScaleNote(
                index = idx,
                noteName = midiToNoteName(midi),
                midiNote = midi,
                frequencyHz = freq,
                isOctaveStart = idx > 0 && (idx % intervals.size == 0)
            )
        }
    }

    fun noteForX(notes: List<ScaleNote>, x: Float): ScaleNote {
        if (notes.isEmpty()) {
            return ScaleNote(0, "C3", 48, 130.81278, false)
        }
        val clamped = x.coerceIn(0f, 0.9999f)
        val index = (clamped * notes.size).toInt().coerceIn(0, notes.lastIndex)
        return notes[index]
    }

    fun midiToNoteName(midi: Int): String {
        val noteIdx = ((midi % 12) + 12) % 12
        val octave = (midi / 12) - 1
        return "${NOTE_NAMES[noteIdx]}$octave"
    }
}

/**
 * Represents an active polyphonic touch point on the continuous X/Y Play Surface.
 * - X (0.0 .. 1.0): selects pitch across the 10 dynamic scale notes
 * - Y (0.0 .. 1.0): controls per-voice filter expression (0.0 top = open, 1.0 bottom = warm)
 */
data class TouchPoint(
    val id: Long,
    val x: Float,
    val y: Float,
    val activeNote: ScaleNote,
    val filterBrightness: Float = (1f - y).coerceIn(0f, 1f),
    val pressStartTime: Long = System.currentTimeMillis()
)

/**
 * Preset configuration defined from mood descriptors.
 */
data class MoodPreset(
    val id: String,
    val name: String,
    val description: String,
    val synthParams: SynthParams,
    val rootMidi: Int = 48 // C3
) {
    val scale: MusicalScale get() = synthParams.scale
    val filterCutoff: Float get() = synthParams.filterCutoff.toFloat()
}

enum class MusicalScale(val displayName: String, val intervals: IntArray) {
    PENTATONIC_MINOR("Minor Pentatonic", intArrayOf(0, 3, 5, 7, 10)),
    PENTATONIC_MAJOR("Major Pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    DORIAN("Dorian Ambient", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    LYDIAN("Lydian Ethereal", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
    PHRYGIAN("Phrygian Mystic", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    ARABIAN("Microtonal Mystic", intArrayOf(0, 1, 4, 5, 7, 8, 11)),
    WHOLE_TONE("Dream Whole-Tone", intArrayOf(0, 2, 4, 6, 8, 10));

    companion object {
        fun fromBackend(name: String?): MusicalScale {
            return when (name?.lowercase()?.trim()) {
                "minor_pentatonic", "pentatonic_minor" -> PENTATONIC_MINOR
                "major_pentatonic", "pentatonic_major" -> PENTATONIC_MAJOR
                "dorian" -> DORIAN
                "lydian" -> LYDIAN
                "phrygian" -> PHRYGIAN
                "arabian" -> ARABIAN
                "whole_tone" -> WHOLE_TONE
                else -> PENTATONIC_MINOR
            }
        }
    }
}

/**
 * Sound Scrapbook recorded audio idea
 */
data class ScrapbookItem(
    val id: String,
    val title: String,
    val moodTag: String,
    val timestamp: Long,
    val durationSeconds: Float,
    val filePath: String? = null,
    val sampleData: FloatArray? = null,
    val isPlaying: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ScrapbookItem
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * FastApi backend generation state and configuration
 */
data class BackendConfig(
    val serverUrl: String = "https://tissue-feast-stabilize.ngrok-free.dev",
    val isConnected: Boolean = false,
    val durationSeconds: Int = 6,
    val temperature: Float = 1.0f
)

data class GenerationJob(
    val id: String,
    val prompt: String,
    val status: GenerationStatus,
    val progress: Float = 0f,
    val errorMessage: String? = null
)

enum class GenerationStatus {
    IDLE, SUBMITTING, GENERATING, READY, CANCELLED, ERROR
}

enum class BackingTrackStatus {
    IDLE,
    GENERATING,
    FADING_IN,
    PLAYING,
    MUTED,
    UNAVAILABLE,
    ERROR
}
