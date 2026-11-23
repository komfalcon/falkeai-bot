package tech.aurikrex.falkeai.voice

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Lightweight Energy Voice Activity Detector + 16kHz 16-bit mono PCM -> WAV encoder.
 */
object EnergyVad {
    const val SAMPLE_RATE = 16_000

    fun rmsEnergy(pcm: ShortArray, count: Int = pcm.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        val n = count.coerceAtMost(pcm.size)
        for (i in 0 until n) {
            val s = pcm[i].toDouble() / 32768.0
            sum += s * s
        }
        return sqrt(sum / n)
    }

    fun encodePcm16ToWav(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = pcm.size * 2
        val totalLen = 36 + dataBytes
        val out = ByteArrayOutputStream(44 + dataBytes)

        fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        writeStr("RIFF")
        writeIntLE(totalLen)
        writeStr("WAVE")
        writeStr("fmt ")
        writeIntLE(16) // PCM fmt chunk size
        writeShortLE(1) // PCM format
        writeShortLE(1) // Mono
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2) // byte rate
        writeShortLE(2) // block align
        writeShortLE(16) // bits per sample
        writeStr("data")
        writeIntLE(dataBytes)

        for (sample in pcm) {
            val v = sample.toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        return out.toByteArray()
    }
}
