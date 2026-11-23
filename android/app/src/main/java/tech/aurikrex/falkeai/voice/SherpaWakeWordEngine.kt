package tech.aurikrex.falkeai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ultra-Lean On-Device 24/7 Multi-Wake-Word Engine (~30–45 MB native RAM — tuned for 4 GB RAM Infinix Hot 10T).
 * Uses `sherpa-onnx` Zipformer 3.3M `int8` KWS with dynamic BPE custom wake words
 * ("Hey Falke", "Jarvis", "Hey Jarvis", "FalkeAI", "Buddy you there", plus user additions).
 * Automatically yields the microphone via `AudioRecordingCallback` whenever phone calls or WhatsApp record audio!
 */
class SherpaWakeWordEngine(
    private val context: Context,
    private val onWakeDetected: (keyword: String) -> Unit
) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var listenJob: Job? = null
    private var vocab: Set<String> = emptySet()
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    @Volatile
    private var pausedForExternalMic = false

    @Volatile
    private var pausedForActiveSession = false

    @Volatile
    private var lastTriggerMs = 0L

    fun isModelAvailable(): Boolean = runCatching {
        val list = context.assets.list(MODEL_DIR)?.toSet().orEmpty()
        list.contains("tokens.txt") && list.any { it.startsWith("encoder") && it.endsWith(".onnx") }
    }.getOrDefault(false)

    fun start(scope: CoroutineScope, wakeWords: List<String>): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        stop()
        if (!initSpotter(wakeWords)) return false
        registerMicYieldCallback()

        listenJob = scope.launch(Dispatchers.IO) {
            val sampleRate = EnergyVad.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            var recorder: AudioRecord? = null
            val shortBuf = ShortArray(1600) // 100ms chunks
            val floatBuf = FloatArray(1600)

            try {
                while (isActive) {
                    if (pausedForExternalMic || pausedForActiveSession) {
                        recorder?.let {
                            runCatching { it.stop(); it.release() }
                            recorder = null
                        }
                        delay(250L)
                        continue
                    }

                    if (recorder == null) {
                        recorder = createRecorder(sampleRate, minBuf * 2)
                        if (recorder == null) {
                            delay(1000L)
                            continue
                        }
                        runCatching { recorder?.startRecording() }
                    }

                    val read = recorder?.read(shortBuf, 0, shortBuf.size) ?: -1
                    if (read <= 0) {
                        delay(40L)
                        continue
                    }

                    // Energy gate: skip heavy neural decode on pure silence (<0.004 RMS) to save battery!
                    val rms = EnergyVad.rmsEnergy(shortBuf, read)
                    if (rms < 0.004) continue

                    for (i in 0 until read) {
                        floatBuf[i] = shortBuf[i] / 32768.0f
                    }

                    val activeSpotter = spotter ?: continue
                    val activeStream = stream ?: continue
                    activeStream.acceptWaveform(floatBuf.copyOf(read), sampleRate)
                    while (activeSpotter.isReady(activeStream)) {
                        activeSpotter.decode(activeStream)
                    }
                    val result = activeSpotter.getResult(activeStream)
                    if (result.keyword.isNotBlank()) {
                        activeSpotter.reset(activeStream)
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerMs > 2500L) {
                            lastTriggerMs = now
                            pausedForActiveSession = true
                            onWakeDetected(result.keyword.trim())
                        }
                    }
                }
            } finally {
                recorder?.let { runCatching { it.stop(); it.release() } }
            }
        }
        return true
    }

    fun setSessionPaused(paused: Boolean) {
        pausedForActiveSession = paused
    }

    fun reloadKeywords(wakeWords: List<String>) {
        val activeSpotter = spotter ?: return
        val buf = KwsBpeTokenizer.buildKeywordsBuffer(wakeWords, vocab)
        runCatching {
            stream?.release()
            stream = activeSpotter.createStream(buf)
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        unregisterMicYieldCallback()
        runCatching { stream?.release() }
        stream = null
        runCatching { spotter?.release() }
        spotter = null
    }

    private fun initSpotter(wakeWords: List<String>): Boolean = runCatching {
        val files = context.assets.list(MODEL_DIR)?.toList().orEmpty()
        val encoder = files.firstOrNull { it.startsWith("encoder") && it.contains("int8") && it.endsWith(".onnx") }
            ?: files.firstOrNull { it.startsWith("encoder") && it.endsWith(".onnx") }
            ?: return false
        val decoder = files.firstOrNull { it.startsWith("decoder") && it.contains("int8") && it.endsWith(".onnx") }
            ?: files.firstOrNull { it.startsWith("decoder") && it.endsWith(".onnx") }
            ?: return false
        val joiner = files.firstOrNull { it.startsWith("joiner") && it.contains("int8") && it.endsWith(".onnx") }
            ?: files.firstOrNull { it.startsWith("joiner") && it.endsWith(".onnx") }
            ?: return false

        val tokensTxt = context.assets.open("$MODEL_DIR/tokens.txt").bufferedReader().use { it.readText() }
        vocab = KwsBpeTokenizer.loadVocabFromTokensTxt(tokensTxt)
        val keywordsBuf = KwsBpeTokenizer.buildKeywordsBuffer(wakeWords, vocab)

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = EnergyVad.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/$encoder",
                    decoder = "$MODEL_DIR/$decoder",
                    joiner = "$MODEL_DIR/$joiner"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2"
            ),
            maxActivePaths = 4,
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2
        )

        val kws = KeywordSpotter(assetManager = context.assets, config = config)
        spotter = kws
        stream = kws.createStream(keywordsBuf)
        true
    }.getOrDefault(false)

    private fun createRecorder(sampleRate: Int, bufferBytes: Int): AudioRecord? = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            rec.release()
            null
        }
    }.getOrNull()

    private fun registerMicYieldCallback() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val otherAppRecording = configs.orEmpty().any { cfg ->
                    cfg.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                        cfg.clientAudioSource == MediaRecorder.AudioSource.VOICE_CALL ||
                        cfg.clientAudioSource == MediaRecorder.AudioSource.MIC ||
                        cfg.clientAudioSource == MediaRecorder.AudioSource.CAMCORDER
                }
                val inPhoneCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
                pausedForExternalMic = otherAppRecording || inPhoneCall
            }
        }
        recordingCallback = cb
        am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun unregisterMicYieldCallback() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        recordingCallback?.let { runCatching { am.unregisterAudioRecordingCallback(it) } }
        recordingCallback = null
    }

    companion object {
        const val MODEL_DIR = "kws"
    }
}
