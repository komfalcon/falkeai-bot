package tech.aurikrex.falkeai.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import tech.aurikrex.falkeai.data.SecurePrefs
import java.io.File
import kotlin.math.sqrt

/**
 * Pure-Kotlin vector math for Speaker Verification (100% testable on JVM without JNI).
 */
object VoiceprintMath {
    fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += (v * v).toDouble()
        val norm = sqrt(sumSq).toFloat()
        if (norm < 1e-8f) return vec.copyOf()
        return FloatArray(vec.size) { vec[it] / norm }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        val na = l2Normalize(a)
        val nb = l2Normalize(b)
        var dot = 0f
        for (i in na.indices) {
            dot += na[i] * nb[i]
        }
        return dot.coerceIn(-1f, 1f)
    }

    fun averageCentroid(embeddings: List<FloatArray>): FloatArray? {
        if (embeddings.isEmpty()) return null
        val dim = embeddings.first().size
        if (dim == 0 || embeddings.any { it.size != dim }) return null
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            val normEmb = l2Normalize(emb)
            for (i in 0 until dim) {
                sum[i] += normEmb[i]
            }
        }
        return l2Normalize(sum)
    }

    /** Mean cosine similarity of each enrollment sample against the centroid (0..1 quality metric). */
    fun consistencyScore(embeddings: List<FloatArray>, centroid: FloatArray): Float {
        if (embeddings.isEmpty()) return 0f
        val total = embeddings.sumOf { cosineSimilarity(it, centroid).toDouble() }
        return (total / embeddings.size).toFloat().coerceIn(0f, 1f)
    }

    /** Trims leading & trailing silence frames (< threshold RMS) so CAM++ pools active speech only. */
    fun trimSilencePcm16(pcm: ShortArray, sampleRate: Int = 16_000, rmsGate: Double = 0.010): ShortArray {
        if (pcm.size < sampleRate / 4) return pcm
        val frame = (sampleRate / 20).coerceAtLeast(320) // 50ms window
        var firstSpeech = 0
        var lastSpeech = pcm.size

        var idx = 0
        while (idx + frame <= pcm.size) {
            val slice = pcm.copyOfRange(idx, idx + frame)
            if (EnergyVad.rmsEnergy(slice, slice.size) >= rmsGate) {
                firstSpeech = (idx - frame).coerceAtLeast(0)
                break
            }
            idx += frame
        }

        idx = pcm.size - frame
        while (idx >= firstSpeech) {
            val slice = pcm.copyOfRange(idx, idx + frame)
            if (EnergyVad.rmsEnergy(slice, slice.size) >= rmsGate) {
                lastSpeech = (idx + frame * 2).coerceAtMost(pcm.size)
                break
            }
            idx -= frame
        }
        return if (lastSpeech - firstSpeech >= sampleRate / 4) {
            pcm.copyOfRange(firstSpeech, lastSpeech)
        } else {
            pcm
        }
    }
}

sealed class SpeakerVerifyOutcome {
    data object NotEnabledOrEnrolled : SpeakerVerifyOutcome()
    data object ModelUnavailable : SpeakerVerifyOutcome()
    data class Verified(val similarity: Float, val threshold: Float) : SpeakerVerifyOutcome()
    data class Rejected(val similarity: Float, val threshold: Float) : SpeakerVerifyOutcome()
}

data class EnrollmentSummary(
    val sampleCount: Int,
    val embeddingDim: Int,
    val consistency: Float
)

/**
 * 100% On-Device Speaker Verification ("Only Falcon's Voice Works") powered by `sherpa-onnx` CAM++ (192-D).
 * Runs locally on the Infinix CPU in ~60ms with $0 cloud cost. Rejected voices never leave the device!
 */
class SpeakerVerificationEngine(
    private val context: Context,
    private val prefs: SecurePrefs
) {
    private var extractor: SpeakerEmbeddingExtractor? = null
    private val lock = Any()

    fun isModelAvailable(): Boolean = runCatching {
        val cached = File(context.filesDir, "speaker/$SPEAKER_MODEL_NAME")
        if (cached.exists() && cached.length() > 100_000L) return@runCatching true
        val list = context.assets.list(SPEAKER_ASSET_DIR)?.toSet().orEmpty()
        list.contains(SPEAKER_MODEL_NAME)
    }.getOrDefault(false)

    private fun materializeModelFile(): File? = runCatching {
        val outDir = File(context.filesDir, "speaker").apply { mkdirs() }
        val outFile = File(outDir, SPEAKER_MODEL_NAME)
        if (outFile.exists() && outFile.length() > 100_000L) return@runCatching outFile
        context.assets.open("$SPEAKER_ASSET_DIR/$SPEAKER_MODEL_NAME").use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        outFile.takeIf { it.exists() && it.length() > 100_000L }
    }.getOrNull()

    private fun ensureExtractor(): SpeakerEmbeddingExtractor? = synchronized(lock) {
        extractor?.let { return it }
        val modelFile = materializeModelFile() ?: return null
        return runCatching {
            val cfg = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            SpeakerEmbeddingExtractor(assetManager = null, config = cfg).also {
                extractor = it
            }
        }.getOrNull()
    }

    fun extractEmbedding(pcm16: ShortArray, sampleRate: Int = 16_000): FloatArray? {
        val ext = ensureExtractor() ?: return null
        val trimmed = VoiceprintMath.trimSilencePcm16(pcm16, sampleRate)
        if (trimmed.size < sampleRate / 3) return null // Need at least ~0.35s of active speech
        val floats = FloatArray(trimmed.size) { trimmed[it] / 32768.0f }

        return synchronized(lock) {
            runCatching {
                val stream = ext.createStream()
                try {
                    stream.acceptWaveform(floats, sampleRate)
                    stream.inputFinished()
                    if (!ext.isReady(stream)) return@runCatching null
                    val rawVec = ext.compute(stream)
                    if (rawVec.isEmpty()) null else VoiceprintMath.l2Normalize(rawVec)
                } finally {
                    runCatching { stream.release() }
                }
            }.getOrNull()
        }
    }

    fun verifyUtterance(pcm16: ShortArray, sampleRate: Int = 16_000): SpeakerVerifyOutcome {
        if (!prefs.voiceLockEnabled) return SpeakerVerifyOutcome.NotEnabledOrEnrolled
        val centroid = prefs.enrolledVoiceprint ?: return SpeakerVerifyOutcome.NotEnabledOrEnrolled
        if (!isModelAvailable()) return SpeakerVerifyOutcome.ModelUnavailable

        val candidate = extractEmbedding(pcm16, sampleRate)
            ?: return SpeakerVerifyOutcome.Rejected(similarity = 0f, threshold = prefs.voiceMatchThreshold)

        val sim = VoiceprintMath.cosineSimilarity(candidate, centroid)
        val threshold = prefs.voiceMatchThreshold
        return if (sim >= threshold) {
            SpeakerVerifyOutcome.Verified(sim, threshold)
        } else {
            SpeakerVerifyOutcome.Rejected(sim, threshold)
        }
    }

    fun enrollFromUtterances(utterancesPcm16: List<ShortArray>, sampleRate: Int = 16_000): Result<EnrollmentSummary> =
        runCatching {
            check(isModelAvailable()) { "Speaker verification model (campplus.onnx) is not bundled yet." }
            val vectors = utterancesPcm16.mapNotNull { extractEmbedding(it, sampleRate) }
            check(vectors.size >= 3) {
                "Need at least 3 clear voice recordings (${vectors.size} valid). Speak clearly for 2–4 seconds in a quiet spot."
            }
            val centroid = VoiceprintMath.averageCentroid(vectors)
                ?: error("Could not compute voiceprint centroid.")
            val consistency = VoiceprintMath.consistencyScore(vectors, centroid)
            prefs.enrolledVoiceprint = centroid
            prefs.enrolledSampleCount = vectors.size
            prefs.voiceLockEnabled = true
            EnrollmentSummary(
                sampleCount = vectors.size,
                embeddingDim = centroid.size,
                consistency = consistency
            )
        }

    fun release() = synchronized(lock) {
        runCatching { extractor?.release() }
        extractor = null
    }

    companion object {
        const val SPEAKER_ASSET_DIR = "speaker"
        const val SPEAKER_MODEL_NAME = "campplus.onnx"

        val ENROLLMENT_PHRASES = listOf(
            "Hey Fall-K, check all six of my Gmail accounts right now.",
            "Fall-K, catch me up on urgent WhatsApp messages and payouts.",
            "Set an exact wake up alarm for six thirty tomorrow morning.",
            "Hey Fall-K, lock in for ninety minutes and play focus music.",
            "Goodnight Fall-K, silence notifications until my morning briefing."
        )
    }
}
