package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight pure Kotlin WAV file decoder.
 * Extracts PCM audio data and sample rate, normalizing to mono [-1.0 .. 1.0] FloatArray.
 */
object WavDecoder {

    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRate: Int
    )

    fun decodeWav(bytes: ByteArray): DecodedAudio? {
        if (bytes.size < 44) return null

        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // Validate RIFF header
            val riff = String(bytes, 0, 4)
            val wave = String(bytes, 8, 4)
            if (riff != "RIFF" || wave != "WAVE") {
                return null
            }

            var sampleRate = 44100
            var channels = 1
            var bitsPerSample = 16
            var audioFormat = 1 // 1 = PCM, 3 = IEEE float
            var dataOffset = -1
            var dataSize = 0

            var index = 12
            while (index + 8 <= bytes.size) {
                val chunkId = String(bytes, index, 4)
                val chunkSize = buffer.getInt(index + 4)
                val chunkDataStart = index + 8

                if (chunkId == "fmt ") {
                    if (chunkSize >= 16 && chunkDataStart + 16 <= bytes.size) {
                        audioFormat = buffer.getShort(chunkDataStart).toInt() and 0xFFFF
                        channels = buffer.getShort(chunkDataStart + 2).toInt() and 0xFFFF
                        sampleRate = buffer.getInt(chunkDataStart + 4)
                        bitsPerSample = buffer.getShort(chunkDataStart + 14).toInt() and 0xFFFF
                    }
                } else if (chunkId == "data") {
                    dataOffset = chunkDataStart
                    dataSize = minOf(chunkSize, bytes.size - dataOffset)
                    break
                }

                index += 8 + maxOf(0, chunkSize)
                if (index % 2 != 0) index++ // Word align
            }

            if (dataOffset < 0 || dataSize <= 0) return null

            // Decode samples
            val samples: FloatArray
            if (audioFormat == 1 && bitsPerSample == 16) {
                // 16-bit signed PCM
                val bytesPerFrame = 2 * channels
                val totalFrames = dataSize / bytesPerFrame
                samples = FloatArray(totalFrames)

                var byteIdx = dataOffset
                for (frame in 0 until totalFrames) {
                    var frameSum = 0f
                    for (ch in 0 until channels) {
                        val sample16 = buffer.getShort(byteIdx)
                        frameSum += sample16 / 32768.0f
                        byteIdx += 2
                    }
                    samples[frame] = frameSum / channels
                }
            } else if (audioFormat == 3 && bitsPerSample == 32) {
                // 32-bit IEEE float
                val bytesPerFrame = 4 * channels
                val totalFrames = dataSize / bytesPerFrame
                samples = FloatArray(totalFrames)

                var byteIdx = dataOffset
                for (frame in 0 until totalFrames) {
                    var frameSum = 0f
                    for (ch in 0 until channels) {
                        val sampleFloat = buffer.getFloat(byteIdx)
                        frameSum += sampleFloat
                        byteIdx += 4
                    }
                    samples[frame] = frameSum / channels
                }
            } else if (audioFormat == 1 && bitsPerSample == 8) {
                // 8-bit unsigned PCM
                val bytesPerFrame = 1 * channels
                val totalFrames = dataSize / bytesPerFrame
                samples = FloatArray(totalFrames)

                var byteIdx = dataOffset
                for (frame in 0 until totalFrames) {
                    var frameSum = 0f
                    for (ch in 0 until channels) {
                        val sample8 = bytes[byteIdx].toInt() and 0xFF
                        frameSum += (sample8 - 128) / 128.0f
                        byteIdx++
                    }
                    samples[frame] = frameSum / channels
                }
            } else {
                return null
            }

            return DecodedAudio(samples, sampleRate)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
