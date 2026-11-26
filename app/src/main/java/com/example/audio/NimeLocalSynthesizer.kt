package com.example.audio

import com.example.model.FilterType
import com.example.model.MoodPreset
import com.example.model.MusicalScale
import com.example.model.OscillatorType
import com.example.model.SynthParams
import kotlin.math.abs

/**
 * Offline Mood-to-Parametric-Synth Analyzer for NIME.
 *
 * Translates free-text mood descriptors (or known presets) into a complete
 * [SynthParams] patch matching the backend's `llm_gen.py` schema whenever
 * the remote FastAPI server is unreachable.
 */
object NimeLocalSynthesizer {

    private val brightKeywords = setOf(
        "bright", "sunny", "sun", "light", "ethereal", "airy", "crystal", "glass",
        "shimmer", "sparkle", "dawn", "morning", "sky", "heaven", "star", "cosmic",
        "ice", "glimmer", "clear", "happy", "joy", "uplifting", "radiant", "luminous",
        "etereo", "luminoso", "chiaro", "cristallo", "sole", "alba", "celeste"
    )

    private val darkKeywords = setOf(
        "dark", "dusk", "night", "deep", "abyss", "shadow", "gloom", "heavy",
        "storm", "thunder", "fog", "mist", "melancholy", "sad", "sub", "cave",
        "void", "midnight", "rain", "cold", "bleak", "noir", "brooding",
        "scuro", "notte", "abisso", "pioggia", "malinconico", "cupo", "profondo"
    )

    private val energeticKeywords = setOf(
        "energetic", "energy", "fast", "punchy", "intense", "electric", "cyber",
        "fire", "power", "wild", "sharp", "acid", "rave", "pulse", "drive",
        "dynamic", "aggressive", "fierce", "bold", "laser", "lead", "synthwave",
        "energico", "veloce", "potente", "elettrico", "aggressivo", "forte"
    )

    private val calmKeywords = setOf(
        "calm", "contemplative", "ambient", "dreamy", "dream", "sleep", "soft",
        "slow", "peace", "peaceful", "zen", "gentle", "quiet", "float", "floating",
        "drift", "drifting", "serene", "still", "warm", "meditation", "chill",
        "pad", "drone", "texture", "calmo", "sognante", "lento", "rilassante", "caldo"
    )

    private val mysticKeywords = setOf(
        "mystic", "mystery", "desert", "eastern", "ancient", "ritual", "eerie",
        "alien", "surreal", "strange", "arcane", "sacred", "hypnotic", "temple",
        "oriental", "microtonal", "dune", "moon", "mistico", "misterioso", "deserto"
    )

    private val metallicPluckKeywords = setOf(
        "bell", "bells", "fm", "chime", "metallic", "metal", "crystal", "glass",
        "ice", "pluck", "gong", "steel", "copper", "brass", "star", "celestial",
        "campana", "campane", "metallico", "pizzicato"
    )

    private val grittyKeywords = setOf(
        "distorted", "distortion", "gritty", "dirty", "fuzz", "overdrive", "industrial",
        "cyberpunk", "acid", "harsh", "raw", "crunch", "saturated", "distorto", "sporco"
    )

    fun analyzeMoodToSynthParams(
        moodPrompt: String,
        matchedPreset: MoodPreset? = null
    ): SynthParams {
        val normalized = moodPrompt.trim().lowercase()
        if (matchedPreset != null && normalized == matchedPreset.name.lowercase()) {
            return matchedPreset.synthParams.sanitized()
        }

        val tokens = normalized.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val seed = normalized.hashCode()
        val seedFracA = ((seed and 0xFF) / 255f)
        val seedFracB = (((seed ushr 8) and 0xFF) / 255f)

        var brightScore = 0.5f + (seedFracA - 0.5f) * 0.16f
        var energyScore = 0.45f + (seedFracB - 0.5f) * 0.16f
        var mysticScore = 0.12f
        var pluckBellScore = 0.12f
        var calmPadScore = 0.15f
        var gritScore = 0.0f

        tokens.forEach { token ->
            if (token in brightKeywords) brightScore += 0.24f
            if (token in darkKeywords) brightScore -= 0.24f
            if (token in energeticKeywords) energyScore += 0.26f
            if (token in calmKeywords) {
                energyScore -= 0.22f
                calmPadScore += 0.28f
            }
            if (token in mysticKeywords) mysticScore += 0.35f
            if (token in metallicPluckKeywords) pluckBellScore += 0.38f
            if (token in grittyKeywords) gritScore += 0.35f
        }

        val brightness = brightScore.coerceIn(0.05f, 0.98f)
        val energy = energyScore.coerceIn(0.05f, 0.98f)
        val mystic = mysticScore.coerceIn(0f, 1f)
        val pluckBell = pluckBellScore.coerceIn(0f, 1f)
        val calmPad = calmPadScore.coerceIn(0f, 1f)
        val grit = (gritScore + if (energy > 0.72f) 0.15f else 0f).coerceIn(0f, 0.85f)

        val scale = when {
            mystic >= 0.45f && brightness < 0.50f -> MusicalScale.PHRYGIAN
            mystic >= 0.45f -> MusicalScale.ARABIAN
            brightness >= 0.68f && energy < 0.55f -> MusicalScale.LYDIAN
            brightness >= 0.62f && energy >= 0.55f -> MusicalScale.PENTATONIC_MAJOR
            brightness < 0.38f && energy < 0.48f -> MusicalScale.DORIAN
            energy < 0.30f && mystic > 0.22f -> MusicalScale.WHOLE_TONE
            else -> MusicalScale.PENTATONIC_MINOR
        }

        val oscillatorType = when {
            pluckBell >= 0.42f -> OscillatorType.FMSINE
            energy >= 0.68f && grit >= 0.25f -> OscillatorType.SAWTOOTH
            energy >= 0.62f && brightness < 0.48f -> OscillatorType.SQUARE
            calmPad >= 0.40f && brightness >= 0.48f -> OscillatorType.PWM
            calmPad >= 0.35f && brightness < 0.45f -> OscillatorType.SINE
            brightness >= 0.60f -> OscillatorType.PWM
            else -> OscillatorType.TRIANGLE
        }

        val (attack, decay, sustain, release) = when {
            pluckBell >= 0.42f -> Quad(0.004f, 0.45f, 0.18f, 1.35f)
            energy >= 0.65f -> Quad(0.006f, 0.28f, 0.78f, 0.45f)
            calmPad >= 0.38f -> Quad(0.018f, 0.85f, 0.86f, 2.40f)
            else -> Quad(0.010f, 0.45f, 0.68f, 1.20f)
        }

        val filterType = when {
            brightness >= 0.80f && energy < 0.45f && (abs(seed) % 3 == 0) -> FilterType.BANDPASS
            else -> FilterType.LOWPASS
        }

        val filterCutoff = (700f + brightness * 4200f + energy * 1400f).toInt().coerceIn(400, 8200)
        val reverbWet = when {
            calmPad >= 0.40f -> (0.48f + (1f - energy) * 0.28f).coerceIn(0.25f, 0.85f)
            pluckBell >= 0.42f -> 0.42f
            energy >= 0.70f -> 0.20f
            else -> (0.28f + brightness * 0.24f).coerceIn(0.15f, 0.65f)
        }

        val delayTime = when {
            calmPad >= 0.38f || mystic >= 0.40f -> 0.45f
            pluckBell >= 0.42f -> 0.32f
            else -> 0.0f
        }
        val bitcrusher = when {
            grit >= 0.35f -> 4
            energy >= 0.80f && grit >= 0.20f -> 6
            else -> 0
        }

        return SynthParams(
            oscillatorType = oscillatorType,
            attack = attack,
            decay = decay,
            sustain = sustain,
            release = release,
            filterCutoff = filterCutoff,
            filterType = filterType,
            distortionAmount = grit,
            reverbWet = reverbWet,
            delayTime = delayTime,
            bitcrusher = bitcrusher,
            scale = scale
        ).sanitized()
    }

    private data class Quad(val a: Float, val d: Float, val s: Float, val r: Float)
}
