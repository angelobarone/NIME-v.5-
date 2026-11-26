package com.example

import com.example.audio.NimeLocalSynthesizer
import com.example.audio.NimePureDataEngine
import com.example.model.FilterType
import com.example.model.MusicalScale
import com.example.model.OscillatorType
import com.example.model.ScaleNoteBank
import com.example.model.SynthParams
import com.example.network.MusicGenResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class NimeInstrumentUnitTest {

    @Test
    fun testDynamicTenNoteScaleGenerationAcrossScales() {
        val minorPent = ScaleNoteBank.build(MusicalScale.PENTATONIC_MINOR, rootMidi = 48)
        assertEquals(10, minorPent.size)
        assertEquals(listOf("C3", "Eb3", "F3", "G3", "Bb3", "C4", "Eb4", "F4", "G4", "Bb4"), minorPent.map { it.noteName })
        assertTrue(minorPent[5].isOctaveStart)

        val majorPent = ScaleNoteBank.build(MusicalScale.PENTATONIC_MAJOR, rootMidi = 48)
        assertEquals(10, majorPent.size)
        assertEquals(listOf("C3", "D3", "E3", "G3", "A3", "C4", "D4", "E4", "G4", "A4"), majorPent.map { it.noteName })

        val dorian = ScaleNoteBank.build(MusicalScale.DORIAN, rootMidi = 48)
        assertEquals(10, dorian.size)
        assertEquals(listOf("C3", "D3", "Eb3", "F3", "G3", "A3", "Bb3", "C4", "D4", "Eb4"), dorian.map { it.noteName })
        assertTrue(dorian[7].isOctaveStart)

        val lydian = ScaleNoteBank.build(MusicalScale.LYDIAN, rootMidi = 48)
        assertEquals(10, lydian.size)
        assertEquals("F#3", lydian[3].noteName)

        val phrygian = ScaleNoteBank.build(MusicalScale.PHRYGIAN, rootMidi = 48)
        assertEquals(10, phrygian.size)
        assertEquals("C#3", phrygian[1].noteName)
    }

    @Test
    fun testBackendSynthParamsJsonParsing() {
        val rawJson = """
            {
              "job_id": "abc-123",
              "status": "completed",
              "progress": 1.0,
              "message": "Parametri generati con successo!",
              "scale": "lydian",
              "synth_params": {
                "oscillator_type": "fmsine",
                "attack": 0.02,
                "decay": 0.45,
                "sustain": 0.35,
                "release": 1.8,
                "filter_cutoff": 3400,
                "filter_type": "bandpass",
                "distortion_amount": 0.25,
                "reverb_wet": 0.65,
                "scale": "lydian"
              }
            }
        """.trimIndent()

        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(MusicGenResponse::class.java)
        val parsed = adapter.fromJson(rawJson)

        assertNotNull(parsed)
        assertNotNull(parsed!!.synthParams)
        val domain = parsed.synthParams!!.toDomain(parsed.scale)
        assertEquals(OscillatorType.FMSINE, domain.oscillatorType)
        assertEquals(0.02f, domain.attack, 0.001f)
        assertEquals(0.45f, domain.decay, 0.001f)
        assertEquals(0.35f, domain.sustain, 0.001f)
        assertEquals(1.8f, domain.release, 0.001f)
        assertEquals(3400, domain.filterCutoff)
        assertEquals(FilterType.BANDPASS, domain.filterType)
        assertEquals(0.25f, domain.distortionAmount, 0.001f)
        assertEquals(0.65f, domain.reverbWet, 0.001f)
        assertEquals(MusicalScale.LYDIAN, domain.scale)
    }

    @Test
    fun testAllSixOscillatorTypesProduceCleanFiniteAudio() {
        OscillatorType.entries.forEach { osc ->
            val engine = NimePureDataEngine()
            engine.tiltModulation = 0.5f
            engine.applySynthParams(
                SynthParams(
                    oscillatorType = osc,
                    attack = 0.01f,
                    decay = 0.2f,
                    sustain = 0.85f,
                    release = 0.5f,
                    filterCutoff = 4000,
                    filterType = FilterType.LOWPASS,
                    distortionAmount = 0.1f,
                    reverbWet = 0.2f,
                    scale = MusicalScale.DORIAN
                )
            )

            val mix = engine.renderMixSnapshotForTest(listOf(0.25f to 0.25f), 4096, startOffset = 1024)
            val rms = sqrt(mix.map { it * it }.average())
            assertTrue("$osc samples must be finite and within [-1, 1]", mix.all { it.isFinite() && it in -1f..1f })
            assertTrue("$osc must produce audible signal (rms=$rms)", rms > 0.05)
        }
    }

    @Test
    fun testAllFilterTypesRespondToYAxisExpression() {
        FilterType.entries.forEach { filterType ->
            val engine = NimePureDataEngine()
            engine.tiltModulation = 0.5f
            engine.applySynthParams(
                SynthParams(
                    oscillatorType = OscillatorType.SAWTOOTH,
                    attack = 0.008f,
                    decay = 0.2f,
                    sustain = 0.9f,
                    release = 0.3f,
                    filterCutoff = 2200,
                    filterType = filterType,
                    distortionAmount = 0.0f,
                    reverbWet = 0.0f,
                    scale = MusicalScale.PENTATONIC_MINOR
                )
            )

            val topOpen = engine.renderMixSnapshotForTest(listOf(0.55f to 0.05f), 2048, startOffset = 1024)
            val bottomWarm = engine.renderMixSnapshotForTest(listOf(0.55f to 0.95f), 2048, startOffset = 1024)

            assertTrue("$filterType top OPEN must be audible", topOpen.any { abs(it) > 0.04f })
            assertTrue("$filterType bottom WARM must be audible", bottomWarm.any { abs(it) > 0.04f })
            val diff = topOpen.indices.map { abs(topOpen[it] - bottomWarm[it]) }.average()
            assertTrue("$filterType Y-axis movement must change timbre cleanly (diff=$diff)", diff > 0.02)
        }
    }

    @Test
    fun testAdsrEnvelopeAttackDecaySustainReleaseBehavior() {
        val engine = NimePureDataEngine()
        engine.tiltModulation = 0.0f
        engine.applySynthParams(
            SynthParams(
                oscillatorType = OscillatorType.TRIANGLE,
                attack = 0.15f,   // ~6615 samples
                decay = 0.15f,
                sustain = 0.25f,
                release = 0.35f,
                filterCutoff = 4000,
                filterType = FilterType.LOWPASS,
                distortionAmount = 0.0f,
                reverbWet = 0.0f,
                scale = MusicalScale.PENTATONIC_MAJOR
            )
        )

        engine.onTouchDown(1L, 0.35f, 0.25f)
        val first2ms = engine.renderAudioFramesForTest(90)
        val attackPeakBlock = engine.renderAudioFramesForTest(6800) // reaches peak near 150ms
        val decayedBlock = engine.renderAudioFramesForTest(10000)   // decays down to 0.25 sustain

        val initialPeak = first2ms.maxOf { abs(it) }
        val peakRms = sqrt(attackPeakBlock.takeLast(1024).map { it * it }.average())
        val sustainRms = sqrt(decayedBlock.takeLast(1024).map { it * it }.average())

        assertTrue("Attack must start cleanly without click transient (initialPeak=$initialPeak)", initialPeak < 0.03f)
        assertTrue("Decay must drop from peak toward lower sustain (peakRms=$peakRms, sustainRms=$sustainRms)", peakRms > sustainRms * 1.8)

        engine.onTouchUp(1L)
        engine.renderAudioFramesForTest(22050) // ~0.5s > 0.35s release
        assertEquals(0, engine.activeVoiceCount())
    }

    @Test
    fun testReverbWetBaseCombinedWithTiltModulation() {
        // Verify pure combination curve
        val base = 0.50f
        val dry = NimePureDataEngine.computeEffectiveReverbWet(base, 0.0f)
        val mid = NimePureDataEngine.computeEffectiveReverbWet(base, 0.5f)
        val wet = NimePureDataEngine.computeEffectiveReverbWet(base, 1.0f)
        assertEquals(base, mid, 0.001f)
        assertTrue("Dry tilt must reduce below reverb_wet (dry=$dry, mid=$mid)", dry < mid)
        assertTrue("Wet tilt must expand above reverb_wet (mid=$mid, wet=$wet)", wet > mid)

        fun renderTailEnergy(baseReverb: Float, tilt: Float): Double {
            val engine = NimePureDataEngine()
            engine.tiltModulation = tilt
            engine.applySynthParams(
                SynthParams(
                    oscillatorType = OscillatorType.SINE,
                    attack = 0.01f,
                    decay = 0.1f,
                    sustain = 0.8f,
                    release = 0.08f,
                    filterCutoff = 3200,
                    filterType = FilterType.LOWPASS,
                    distortionAmount = 0.0f,
                    reverbWet = baseReverb,
                    scale = MusicalScale.LYDIAN
                )
            )
            engine.onTouchDown(1L, 0.50f, 0.25f)
            engine.renderAudioFramesForTest(16000)
            engine.onTouchUp(1L)
            engine.renderAudioFramesForTest(8000) // skip short release
            val tail = engine.renderAudioFramesForTest(8192)
            return tail.map { it * it }.average()
        }

        val lowReverbTail = renderTailEnergy(baseReverb = 0.20f, tilt = 0.50f)
        val highReverbTail = renderTailEnergy(baseReverb = 0.80f, tilt = 0.50f)
        val tiltedWetTail = renderTailEnergy(baseReverb = 0.40f, tilt = 0.98f)
        val tiltedDryTail = renderTailEnergy(baseReverb = 0.40f, tilt = 0.02f)

        assertTrue("Higher LLM reverb_wet must yield richer tail (low=$lowReverbTail, high=$highReverbTail)", highReverbTail > lowReverbTail * 2.5)
        assertTrue("Tilt must modulate around reverb_wet (dry=$tiltedDryTail, wet=$tiltedWetTail)", tiltedWetTail > tiltedDryTail * 4.0)
    }

    @Test
    fun testPolyphonicMultiTouchAndLegatoScaleGlide() {
        val engine = NimePureDataEngine()
        engine.applySynthParams(
            SynthParams(
                oscillatorType = OscillatorType.PWM,
                attack = 0.02f,
                decay = 0.2f,
                sustain = 0.85f,
                release = 0.4f,
                filterCutoff = 3200,
                filterType = FilterType.LOWPASS,
                distortionAmount = 0.15f,
                reverbWet = 0.45f,
                scale = MusicalScale.DORIAN
            )
        )

        val t1 = engine.onTouchDown(10L, 0.05f, 0.20f) // lane 0 -> C3
        val t2 = engine.onTouchDown(11L, 0.25f, 0.50f) // lane 2 -> Eb3 in Dorian
        val t3 = engine.onTouchDown(12L, 0.75f, 0.15f) // lane 7 -> C4 in Dorian

        assertEquals("C3", t1.note.noteName)
        assertEquals("Eb3", t2.note.noteName)
        assertEquals("C4", t3.note.noteName)
        assertEquals(3, engine.activeVoiceCount())

        val block1 = engine.renderAudioFramesForTest(6000)
        val moved = engine.onTouchMove(10L, 0.95f, 0.30f) // glide to lane 9 -> Eb4
        assertEquals("Eb4", moved.note.noteName)
        val block2 = engine.renderAudioFramesForTest(8192)

        val combined = block1 + block2
        assertTrue("Polyphonic glide must remain finite and within [-1, 1]", combined.all { it.isFinite() && it in -1f..1f })
        var steadyMaxDelta = 0f
        for (i in 256 until block1.size) {
            val d = abs(block1[i] - block1[i - 1])
            if (d > steadyMaxDelta) steadyMaxDelta = d
        }
        var glideMaxDelta = 0f
        for (i in 1 until block2.size) {
            val d = abs(block2[i] - block2[i - 1])
            if (d > glideMaxDelta) glideMaxDelta = d
        }
        val boundaryDelta = abs(block2.first() - block1.last())
        assertTrue(
            "Legato pitch glide must remain smooth without click transients (boundary=$boundaryDelta, steady=$steadyMaxDelta, glide=$glideMaxDelta)",
            boundaryDelta <= maxOf(steadyMaxDelta * 1.25f, 0.20f) && glideMaxDelta < 0.25f
        )

        engine.clearAllNotes()
        assertEquals(0, engine.activeVoiceCount())
    }

    @Test
    fun testScaleFromBackend() {
        assertEquals(MusicalScale.PENTATONIC_MINOR, MusicalScale.fromBackend("minor_pentatonic"))
        assertEquals(MusicalScale.PENTATONIC_MAJOR, MusicalScale.fromBackend("major_pentatonic"))
        assertEquals(MusicalScale.DORIAN, MusicalScale.fromBackend("dorian"))
        assertEquals(MusicalScale.LYDIAN, MusicalScale.fromBackend("lydian"))
        assertEquals(MusicalScale.PHRYGIAN, MusicalScale.fromBackend("phrygian"))
    }

    @Test
    fun testLocalSynthesizerMoodAnalysis() {
        val darkAcid = NimeLocalSynthesizer.analyzeMoodToSynthParams("Dark heavy distorted acid cyber")
        val brightBell = NimeLocalSynthesizer.analyzeMoodToSynthParams("Crystal bell chime star")
        val warmPad = NimeLocalSynthesizer.analyzeMoodToSynthParams("Calm floating ambient pad")

        assertTrue(darkAcid.distortionAmount > brightBell.distortionAmount)
        assertEquals(OscillatorType.FMSINE, brightBell.oscillatorType)
        assertTrue(warmPad.attack > darkAcid.attack)
        assertTrue(warmPad.release > darkAcid.release)
    }

    @Test
    fun testOrientationAwareTiltMapping() {
        val ax = 4.2f
        val ay = 6.8f
        assertEquals(ax, com.example.viewmodel.NimeViewModel.mapTiltAxisForRotation(ax, ay, android.view.Surface.ROTATION_0), 0.001f)
        assertEquals(-ay, com.example.viewmodel.NimeViewModel.mapTiltAxisForRotation(ax, ay, android.view.Surface.ROTATION_90), 0.001f)
        assertEquals(-ax, com.example.viewmodel.NimeViewModel.mapTiltAxisForRotation(ax, ay, android.view.Surface.ROTATION_180), 0.001f)
        assertEquals(ay, com.example.viewmodel.NimeViewModel.mapTiltAxisForRotation(ax, ay, android.view.Surface.ROTATION_270), 0.001f)
    }

    @Test
    fun testMoodWordConstraintValidation() {
        assertTrue(com.example.viewmodel.NimeUiState(moodInput = "Ethereal").isMoodValid)
        assertTrue(com.example.viewmodel.NimeUiState(moodInput = "Warm Dusk").isMoodValid)
        assertTrue(!com.example.viewmodel.NimeUiState(moodInput = "   ").isMoodValid)
    }

    @Test
    fun testHybridBackendEffectsAndBackingTrackUrlParsing() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(MusicGenResponse::class.java)

        val immediateJson = """
            {
              "job_id": "job-42",
              "status": "generating_background",
              "progress": 0.5,
              "scale": "dorian",
              "synth_params": {
                "oscillator_type": "sawtooth",
                "attack": 0.05,
                "decay": 0.25,
                "sustain": 0.4,
                "release": 1.4,
                "filter_cutoff": 2600
              },
              "effects": {
                "distortion_mix": 0.35,
                "reverb_size": 0.75,
                "delay_time": 0.45,
                "bitcrusher": 4
              },
              "backing_track_url": null
            }
        """.trimIndent()

        val immediate = adapter.fromJson(immediateJson)
        assertNotNull(immediate)
        assertEquals("generating_background", immediate!!.status)
        assertNotNull(immediate.effects)
        val domain = immediate.synthParams!!.toDomain(immediate.scale, immediate.effects)
        assertEquals(OscillatorType.SAWTOOTH, domain.oscillatorType)
        assertEquals(MusicalScale.DORIAN, domain.scale)
        assertEquals(0.35f, domain.distortionAmount, 0.001f)
        assertEquals(0.75f, domain.reverbWet, 0.001f)
        assertEquals(0.45f, domain.delayTime, 0.001f)
        assertEquals(4, domain.bitcrusher)

        val completedJson = """
            {
              "job_id": "job-42",
              "status": "completed",
              "progress": 1.0,
              "backing_track_url": "https://example.ngrok-free.dev/audio/job-42_backing_track.wav"
            }
        """.trimIndent()
        val completed = adapter.fromJson(completedJson)
        assertNotNull(completed)
        assertEquals("completed", completed!!.status)
        assertEquals("https://example.ngrok-free.dev/audio/job-42_backing_track.wav", completed.backingTrackUrl)
    }

    @Test
    fun testBitCrusherAndDelayEffectsChainProduceCleanFiniteAudio() {
        val dryEngine = NimePureDataEngine()
        dryEngine.tiltModulation = 0.5f
        val baseParams = SynthParams(
            oscillatorType = OscillatorType.SINE,
            attack = 0.008f,
            decay = 0.12f,
            sustain = 0.85f,
            release = 0.2f,
            filterCutoff = 5000,
            filterType = FilterType.LOWPASS,
            distortionAmount = 0.0f,
            reverbWet = 0.0f,
            delayTime = 0.0f,
            bitcrusher = 0,
            scale = MusicalScale.PENTATONIC_MINOR
        )
        dryEngine.applySynthParams(baseParams)

        val crushedEngine = NimePureDataEngine()
        crushedEngine.tiltModulation = 0.5f
        crushedEngine.applySynthParams(
            baseParams.copy(bitcrusher = 3, distortionAmount = 0.45f, delayTime = 0.15f)
        )

        val dryMix = dryEngine.renderMixSnapshotForTest(listOf(0.45f to 0.20f), 8192, startOffset = 512)
        val fxMix = crushedEngine.renderMixSnapshotForTest(listOf(0.45f to 0.20f), 8192, startOffset = 512)

        assertTrue("FX audio must be finite and within [-1, 1]", fxMix.all { it.isFinite() && it in -1f..1f })
        val fxRms = sqrt(fxMix.map { it * it }.average())
        assertTrue("FX audio must be audible (rms=$fxRms)", fxRms > 0.05)

        val delta = dryMix.indices.map { abs(dryMix[it] - fxMix[it]) }.average()
        assertTrue("BitCrusher + Distortion + Delay must audibly shape signal (delta=$delta)", delta > 0.03)

        // Verify delay tail persists briefly after touch release
        crushedEngine.onTouchDown(55L, 0.45f, 0.20f)
        crushedEngine.renderAudioFramesForTest(12000)
        crushedEngine.onTouchUp(55L)
        crushedEngine.renderAudioFramesForTest(9000) // past short 0.2s release
        val echoTail = crushedEngine.renderAudioFramesForTest(4096)
        val echoRms = sqrt(echoTail.map { it * it }.average())
        assertTrue("Delay line must produce trailing echo energy (echoRms=$echoRms)", echoRms > 0.002)
    }

    @Test
    fun testSeamlessLoopProcessorHidesEdgeFadesAndCrossfadesCleanly() {
        val srcRate = 32000
        val durationSec = 30
        val n = srcRate * durationSec
        val raw = FloatArray(n) { i ->
            val t = i.toDouble() / srcRate.toDouble()
            // Rich ambient drone chord (C3 + G3 + D4)
            (0.40 * kotlin.math.sin(2.0 * Math.PI * 130.81 * t) +
                0.30 * kotlin.math.sin(2.0 * Math.PI * 196.00 * t) +
                0.20 * kotlin.math.sin(2.0 * Math.PI * 293.66 * t)).toFloat()
        }
        // Apply MusicGen's 1-second linear fade-in & fade-out
        val fadeSamples = srcRate
        for (i in 0 until fadeSamples) {
            val g = i.toFloat() / fadeSamples.toFloat()
            raw[i] *= g
            raw[n - 1 - i] *= g
        }

        val loop = com.example.audio.SeamlessLoopProcessor.buildCrossfadeLoop(raw, srcRate, 44100)
        assertTrue("Loop buffer must be non-empty and trimmed/folded (~23-24s)", loop.size in (44100 * 20)..(44100 * 26))
        assertTrue("Loop samples must be finite and within [-1, 1]", loop.all { it.isFinite() && it in -1f..1f })

        // Verify no silent dip across every 0.25s slice AND across the wrap boundary
        val win = 44100 / 4
        var minWindowRms = Double.MAX_VALUE
        var pos = 0
        while (pos + win <= loop.size) {
            var sumSq = 0.0
            for (k in pos until pos + win) {
                sumSq += (loop[k] * loop[k]).toDouble()
            }
            val rms = sqrt(sumSq / win)
            if (rms < minWindowRms) minWindowRms = rms
            pos += win
        }

        // Also check across the wrap seam [loop.size - win/2 .. loop.size) + [0 .. win/2)
        var wrapSumSq = 0.0
        val halfWin = win / 2
        for (k in (loop.size - halfWin) until loop.size) wrapSumSq += (loop[k] * loop[k]).toDouble()
        for (k in 0 until halfWin) wrapSumSq += (loop[k] * loop[k]).toDouble()
        val wrapRms = sqrt(wrapSumSq / (halfWin * 2))

        assertTrue("Loop must never dip toward silence (minWindowRms=$minWindowRms)", minWindowRms > 0.18)
        assertTrue("Wrap boundary energy must stay full and seamless (wrapRms=$wrapRms)", wrapRms > 0.18)

        // Check sample-to-sample continuity at wrap boundary
        val wrapJump = abs(loop.first() - loop.last())
        assertTrue("Wrap boundary must have zero click discontinuity (wrapJump=$wrapJump)", wrapJump < 0.06f)
    }
}
