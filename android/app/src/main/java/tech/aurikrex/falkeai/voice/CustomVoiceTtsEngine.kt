package tech.aurikrex.falkeai.voice

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import tech.aurikrex.falkeai.data.SecurePrefs
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class CustomVoiceStatus(
    val installed: Boolean,
    val modelSizeKb: Long = 0,
    val hasEspeakData: Boolean = false,
    val active: Boolean = false
)

/**
 * 100% Offline Own-Voice Neural TTS Runtime powered by `sherpa-onnx` VITS / Piper.
 * Loads Falcon's fine-tuned voice package (`falcon_custom_voice.zip` containing `model.onnx`,
 * `tokens.txt`, and optional `espeak-ng-data/` or `lexicon.txt`) trained for $0 on Kaggle/Colab,
 * and synthesizes speech locally on the Infinix CPU with zero server cost!
 */
class CustomVoiceTtsEngine(
    private val context: Context,
    private val prefs: SecurePrefs
) {
    private val voiceDir = File(context.filesDir, "custom_voice")
    private var tts: OfflineTts? = null
    private val lock = Any()

    fun inspectStatus(): CustomVoiceStatus {
        val modelFile = File(voiceDir, "model.onnx")
        val tokensFile = File(voiceDir, "tokens.txt")
        val espeakDir = File(voiceDir, "espeak-ng-data")
        val installed = modelFile.exists() && modelFile.length() > 1024 && tokensFile.exists()
        return CustomVoiceStatus(
            installed = installed,
            modelSizeKb = if (installed) modelFile.length() / 1024L else 0L,
            hasEspeakData = espeakDir.isDirectory,
            active = installed && prefs.customVoiceEnabled
        )
    }

    /**
     * Imports a trained Piper/VITS `.zip` bundle (exported by `voice-training/train_falcon_voice_colab_kaggle.ipynb`)
     * safely into private app storage with strict Zip-Slip path traversal protection.
     */
    fun importVoiceZipFromUri(uri: Uri): Result<CustomVoiceStatus> = runCatching {
        synchronized(lock) {
            releaseInternal()
            voiceDir.deleteRecursively()
            voiceDir.mkdirs()

            val canonicalRoot = voiceDir.canonicalPath + File.separator
            var foundOnnx: File? = null
            var foundTokens: File? = null

            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open selected voice package.")
            input.use { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val cleanName = entry.name.replace('\\', '/').trimStart('/')
                        if (cleanName.isNotEmpty() && !cleanName.contains("..")) {
                            val outFile = File(voiceDir, cleanName)
                            val canonicalOut = outFile.canonicalPath
                            require(canonicalOut.startsWith(canonicalRoot)) {
                                "Blocked unsafe zip entry path: ${entry.name}"
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                                val lower = outFile.name.lowercase()
                                if (lower.endsWith(".onnx") && (foundOnnx == null || outFile.length() > foundOnnx!!.length())) {
                                    foundOnnx = outFile
                                } else if (lower == "tokens.txt" || lower.endsWith("tokens.txt")) {
                                    foundTokens = outFile
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // Normalize top-level filenames (`model.onnx` + `tokens.txt`) if nested inside a subfolder
            val targetModel = File(voiceDir, "model.onnx")
            val targetTokens = File(voiceDir, "tokens.txt")
            foundOnnx?.let { src ->
                if (src.canonicalPath != targetModel.canonicalPath) {
                    src.copyTo(targetModel, overwrite = true)
                }
            }
            foundTokens?.let { src ->
                if (src.canonicalPath != targetTokens.canonicalPath) {
                    src.copyTo(targetTokens, overwrite = true)
                }
            }

            // Also relocate nested `espeak-ng-data` to `voiceDir/espeak-ng-data` if inside a subfolder
            val directEspeak = File(voiceDir, "espeak-ng-data")
            if (!directEspeak.isDirectory) {
                voiceDir.walkTopDown()
                    .firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
                    ?.let { nested ->
                        if (nested.canonicalPath != directEspeak.canonicalPath) {
                            nested.copyRecursively(directEspeak, overwrite = true)
                        }
                    }
            }

            check(targetModel.exists() && targetTokens.exists()) {
                "ZIP must contain both a VITS/Piper .onnx model and tokens.txt"
            }
            prefs.customVoiceEnabled = true
            inspectStatus()
        }
    }

    fun deleteCustomVoice() = synchronized(lock) {
        releaseInternal()
        voiceDir.deleteRecursively()
    }

    private fun ensureTtsLoaded(): OfflineTts? = synchronized(lock) {
        tts?.let { return it }
        val status = inspectStatus()
        if (!status.active) return null
        return runCatching {
            val modelPath = File(voiceDir, "model.onnx").absolutePath
            val tokensPath = File(voiceDir, "tokens.txt").absolutePath
            val lexiconFile = File(voiceDir, "lexicon.txt")
            val espeakDir = File(voiceDir, "espeak-ng-data")

            val vits = OfflineTtsVitsModelConfig(
                model = modelPath,
                lexicon = if (lexiconFile.exists()) lexiconFile.absolutePath else "",
                tokens = tokensPath,
                dataDir = if (espeakDir.isDirectory) espeakDir.absolutePath else "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            val modelCfg = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val cfg = OfflineTtsConfig(
                model = modelCfg,
                maxNumSentences = 2
            )
            OfflineTts(assetManager = null, config = cfg).also { tts = it }
        }.getOrNull()
    }

    /**
     * Synthesizes `rawText` into a temporary 16-bit PCM `.wav` file using Falcon's trained voice.
     * Returns `null` if no custom voice is installed/enabled so caller falls back seamlessly.
     */
    fun synthesizeToWavFile(rawText: String): File? {
        val engine = ensureTtsLoaded() ?: return null
        val spoken = PronunciationMapper.toSpokenFalke(rawText, maxChars = 650)
        if (spoken.isBlank()) return null

        return synchronized(lock) {
            runCatching {
                val audio = engine.generate(
                    text = spoken,
                    sid = 0,
                    speed = prefs.speechSpeed
                )
                if (audio.samples.isEmpty() || audio.sampleRate <= 0) return@runCatching null
                val pcm16 = ShortArray(audio.samples.size) { i ->
                    (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                val wavBytes = EnergyVad.encodePcm16ToWav(pcm16, audio.sampleRate)
                val out = File(context.cacheDir, "falke_ownvoice_${System.currentTimeMillis()}.wav")
                out.writeBytes(wavBytes)
                out
            }.getOrNull()
        }
    }

    private fun releaseInternal() {
        runCatching { tts?.release() }
        tts = null
    }

    fun release() = synchronized(lock) {
        releaseInternal()
    }
}
