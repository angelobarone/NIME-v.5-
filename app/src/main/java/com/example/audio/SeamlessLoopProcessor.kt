package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prepares a 30s (or arbitrary-length) AI-generated backing track into a gapless,
 * seamless circular PCM loop buffer at [targetSampleRate] Hz:
 * 1. Removes DC offset and resamples via 4-point cubic interpolation.
 * 2. Detects and trims baked-in fade-in / fade-out tails (such as MusicGen's 1s edge fades)
 *    so the loop never drops toward silence.
 * 3. Folds a generous equal-power (`cos` / `sin`) crossfade overlap (up to 4.5s) between
 *    the tail and head, ensuring consecutive sample continuity at both loop wrap boundaries.
 */
object SeamlessLoopProcessor {

    const val TARGET_SAMPLE_RATE = 44100
    private const val MAX_CROSSFADE_SECONDS = 4.5f

    fun buildCrossfadeLoop(
        rawSamples: FloatArray,
        sourceSampleRate: Int,
        targetSampleRate: Int = TARGET_SAMPLE_RATE
    ): FloatArray {
        if (rawSamples.isEmpty()) return FloatArray(0)

        // 1. Remove DC offset on a working copy
        var sum = 0.0
        for (s in rawSamples) sum += s
        val mean = (sum / rawSamples.size).toFloat()
        val dcFree = FloatArray(rawSamples.size) { i -> rawSamples[i] - mean }

        // 2. Resample to targetSampleRate if needed
        val resampled = if (sourceSampleRate > 0 && sourceSampleRate != targetSampleRate) {
            resampleCubic(dcFree, sourceSampleRate, targetSampleRate)
        } else {
            dcFree
        }

        if (resampled.size < targetSampleRate / 2) {
            return resampled
        }

        // 3. Detect & trim baked-in fade-in / fade-out edges so we crossfade full-energy audio
        val (startIdx, endIdx) = detectSteadyBounds(resampled, targetSampleRate)
        val coreLen = (endIdx - startIdx).coerceAtLeast(1)
        val core = if (startIdx == 0 && endIdx == resampled.size) {
            resampled
        } else {
            resampled.copyOfRange(startIdx, endIdx)
        }

        // 4. Fold equal-power crossfade overlap: loop[0..xfade) blends head + tail
        val desiredXfade = (MAX_CROSSFADE_SECONDS * targetSampleRate).toInt()
        val xfade = minOf(desiredXfade, coreLen / 3).coerceAtLeast(1)
        val loopLen = coreLen - xfade
        if (loopLen <= xfade) {
            return core
        }

        val loop = FloatArray(loopLen)
        val invXfade = 1.0 / xfade.toDouble()
        val halfPi = PI * 0.5

        for (i in 0 until xfade) {
            val theta = (i.toDouble() * invXfade) * halfPi
            val headGain = sin(theta).toFloat()
            val tailGain = cos(theta).toFloat()
            loop[i] = (core[i] * headGain + core[loopLen + i] * tailGain).coerceIn(-0.98f, 0.98f)
        }
        System.arraycopy(core, xfade, loop, xfade, loopLen - xfade)

        return loop
    }

    internal fun detectSteadyBounds(samples: FloatArray, sampleRate: Int): Pair<Int, Int> {
        val n = samples.size
        if (n < sampleRate * 6) return 0 to n

        val bodyStart = n / 4
        val bodyEnd = (3 * n) / 4
        val bodyRms = computeRms(samples, bodyStart, bodyEnd)
        if (bodyRms < 0.005f) return 0 to n

        val probeLen = minOf((0.30f * sampleRate).toInt(), n / 12)
        val maxTrim = minOf((1.35f * sampleRate).toInt(), n / 8)
        val winLen = minOf((0.04f * sampleRate).toInt().coerceAtLeast(64), probeLen)
        val steadyThreshold = bodyRms * 0.62f

        var startIdx = 0
        val leadRms = computeRms(samples, 0, probeLen)
        if (leadRms < bodyRms * 0.42f) {
            var pos = 0
            while (pos + winLen <= maxTrim) {
                if (computeRms(samples, pos, pos + winLen) >= steadyThreshold) {
                    startIdx = pos
                    break
                }
                pos += winLen / 2
            }
            if (startIdx == 0) {
                startIdx = minOf(sampleRate, maxTrim)
            }
        }

        var endIdx = n
        val trailRms = computeRms(samples, n - probeLen, n)
        if (trailRms < bodyRms * 0.42f) {
            var pos = n
            val minEnd = n - maxTrim
            while (pos - winLen >= minEnd) {
                if (computeRms(samples, pos - winLen, pos) >= steadyThreshold) {
                    endIdx = pos
                    break
                }
                pos -= winLen / 2
            }
            if (endIdx == n) {
                endIdx = maxOf(n - sampleRate, minEnd)
            }
        }

        if (endIdx - startIdx < sampleRate * 3) {
            return 0 to n
        }
        return startIdx to endIdx
    }

    private fun computeRms(samples: FloatArray, start: Int, end: Int): Float {
        val count = end - start
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in start until end) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / count).toFloat()
    }

    internal fun resampleCubic(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int
    ): FloatArray {
        if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0 || sourceRate == targetRate) {
            return input
        }
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLen = ((input.size.toDouble() / ratio).toInt()).coerceAtLeast(1)
        val out = FloatArray(outLen)
        val maxIdx = input.lastIndex

        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx1 = srcPos.toInt().coerceIn(0, maxIdx)
            val t = (srcPos - idx1).toFloat()

            val y0 = input[(idx1 - 1).coerceIn(0, maxIdx)]
            val y1 = input[idx1]
            val y2 = input[(idx1 + 1).coerceIn(0, maxIdx)]
            val y3 = input[(idx1 + 2).coerceIn(0, maxIdx)]

            // 4-point Catmull-Rom spline interpolation
            val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
            val a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
            val a2 = -0.5f * y0 + 0.5f * y2
            val a3 = y1

            val sample = ((a0 * t + a1) * t + a2) * t + a3
            out[i] = sample.coerceIn(-1.0f, 1.0f)
        }
        return out
    }
}
