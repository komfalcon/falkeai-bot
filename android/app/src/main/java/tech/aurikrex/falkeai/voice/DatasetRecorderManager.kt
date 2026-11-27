package tech.aurikrex.falkeai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

data class TakeQualityReport(
    val durationSec: Float,
    val rms: Float,
    val peak: Float,
    val warning: String? = null
)

/**
 * In-App Voice Cloning Studio & Dataset Builder ($0).
 * Guides Falcon through a phonetically balanced 120-sentence script (split into 8 bite-sized chapters,
 * rich in "Fall-K", Aurikrex, Lagos, numbers, questions, and conversational tone).
 * Exports an LJSpeech-compatible `falke_voice_dataset.zip` (`wavs/*.wav` + `metadata.csv`) ready for
 * 1-click free GPU fine-tuning on Kaggle / Google Colab (`voice-training/train_falcon_voice_colab_kaggle.ipynb`).
 */
class DatasetRecorderManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val datasetRoot = File(context.filesDir, "voice_dataset")
    private val wavsDir = File(datasetRoot, "wavs")

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _liveLevel = MutableStateFlow(0f)
    val liveLevel: StateFlow<Float> = _liveLevel

    private val _recordedIds = MutableStateFlow<Set<Int>>(emptySet())
    val recordedIds: StateFlow<Set<Int>> = _recordedIds

    private val _lastQuality = MutableStateFlow<TakeQualityReport?>(null)
    val lastQuality: StateFlow<TakeQualityReport?> = _lastQuality

    private var recordJob: Job? = null
    private var previewPlayer: MediaPlayer? = null

    init {
        wavsDir.mkdirs()
        refreshRecordedSet()
    }

    fun refreshRecordedSet() {
        val ids = wavsDir.listFiles()
            ?.mapNotNull { f ->
                if (f.name.startsWith("utt_") && f.name.endsWith(".wav") && f.length() > 1000) {
                    f.name.removePrefix("utt_").removeSuffix(".wav").toIntOrNull()
                } else null
            }
            ?.toSet()
            .orEmpty()
        _recordedIds.value = ids
    }

    fun fileForPrompt(index: Int): File =
        File(wavsDir, "utt_${(index + 1).toString().padStart(3, '0')}.wav")

    fun startRecordingPrompt(promptIndex: Int) {
        if (_isRecording.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _lastQuality.value = TakeQualityReport(0f, 0f, 0f, "Microphone permission is required.")
            return
        }
        stopPreview()
        FalkeWakeWordService.setSessionPaused(true)
        _isRecording.value = true
        _lastQuality.value = null

        recordJob = scope.launch(Dispatchers.IO) {
            val sr = STUDIO_SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4410)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                _isRecording.value = false
                FalkeWakeWordService.setSessionPaused(false)
                return@launch
            }

            val samples = ArrayList<Short>(sr * 8)
            val chunk = ShortArray(2205) // 100ms at 22.05 kHz
            var maxPeak = 0f

            try {
                recorder.startRecording()
                while (isActive && _isRecording.value && samples.size < sr * 22) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    val rms = EnergyVad.rmsEnergy(chunk, read).toFloat()
                    _liveLevel.value = (rms * 5.5f).coerceIn(0f, 1f)
                    for (i in 0 until read) {
                        val s = chunk[i]
                        samples.add(s)
                        val p = abs(s.toInt()) / 32768f
                        if (p > maxPeak) maxPeak = p
                    }
                }
            } finally {
                runCatching { recorder.stop(); recorder.release() }
                _isRecording.value = false
                _liveLevel.value = 0f
                FalkeWakeWordService.setSessionPaused(false)
            }

            if (samples.size < sr) {
                _lastQuality.value = TakeQualityReport(
                    durationSec = samples.size.toFloat() / sr,
                    rms = 0f,
                    peak = maxPeak,
                    warning = "Too short (< 1 second) — hold a natural pace and try again."
                )
                return@launch
            }

            val rawPcm = ShortArray(samples.size) { samples[it] }
            val trimmed = VoiceprintMath.trimSilencePcm16(rawPcm, sr, rmsGate = 0.007)
            val overallRms = EnergyVad.rmsEnergy(trimmed, trimmed.size).toFloat()
            val durSec = trimmed.size.toFloat() / sr

            val warning = when {
                maxPeak >= 0.97f -> "Slightly clipped (too close to mic) — hold phone 15–20 cm away."
                overallRms < 0.010f -> "Very quiet — speak at your normal confident conversational volume."
                else -> null
            }

            val outFile = fileForPrompt(promptIndex)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(EnergyVad.encodePcm16ToWav(trimmed, sr))
            writeMetadataCsv()
            refreshRecordedSet()
            _lastQuality.value = TakeQualityReport(durSec, overallRms, maxPeak, warning)
        }
    }

    fun stopRecordingPrompt() {
        _isRecording.value = false
    }

    fun playPromptTake(promptIndex: Int) {
        val f = fileForPrompt(promptIndex)
        if (!f.exists()) return
        stopPreview()
        runCatching {
            previewPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.absolutePath)
                setOnCompletionListener { release(); previewPlayer = null }
                prepare()
                start()
            }
        }
    }

    fun stopPreview() {
        runCatching {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        }
    }

    fun deletePromptTake(promptIndex: Int) {
        stopPreview()
        fileForPrompt(promptIndex).delete()
        writeMetadataCsv()
        refreshRecordedSet()
    }

    fun clearAllTakes() {
        stopPreview()
        datasetRoot.deleteRecursively()
        wavsDir.mkdirs()
        refreshRecordedSet()
    }

    private fun writeMetadataCsv() {
        val lines = mutableListOf<String>()
        STUDIO_PROMPTS.forEachIndexed { idx, promptText ->
            val f = fileForPrompt(idx)
            if (f.exists() && f.length() > 1000) {
                val id = "utt_${(idx + 1).toString().padStart(3, '0')}"
                // Respell "Fall-K" -> spoken phonetic text in training transcript so VITS learns exact phonemes!
                val cleanTranscript = promptText.replace("|", " ").trim()
                lines.add("$id|$cleanTranscript|$cleanTranscript")
            }
        }
        File(datasetRoot, "metadata.csv").writeText(lines.joinToString("\n") + "\n")
    }

    suspend fun exportDatasetZipToUri(targetUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            writeMetadataCsv()
            val metaFile = File(datasetRoot, "metadata.csv")
            val wavFiles = wavsDir.listFiles()?.filter { it.extension == "wav" && it.length() > 1000 }.orEmpty()
            check(wavFiles.isNotEmpty()) { "Record at least a few prompts before exporting." }

            val outStream = context.contentResolver.openOutputStream(targetUri)
                ?: error("Could not open destination file.")
            outStream.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zos ->
                    if (metaFile.exists()) {
                        zos.putNextEntry(ZipEntry("metadata.csv"))
                        metaFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    for (wav in wavFiles.sortedBy { it.name }) {
                        zos.putNextEntry(ZipEntry("wavs/${wav.name}"))
                        wav.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            wavFiles.size
        }
    }

    /**
     * Converts up to `maxCount` recorded Voice Studio WAV takes down to 16 kHz PCM16 so Falcon can
     * enroll Speaker Verification in 1 tap using his existing studio recordings!
     */
    suspend fun loadRecordedTakesAs16kPcm(maxCount: Int = 8): List<ShortArray> = withContext(Dispatchers.IO) {
        val files = wavsDir.listFiles()
            ?.filter { it.extension == "wav" && it.length() > 2000 }
            ?.sortedBy { it.name }
            ?.take(maxCount)
            .orEmpty()
        files.mapNotNull { decodeWavAndResample16k(it.readBytes()) }
    }

    companion object {
        const val STUDIO_SAMPLE_RATE = 22_050

        fun decodeWavAndResample16k(wavBytes: ByteArray): ShortArray? {
            if (wavBytes.size <= 44) return null
            val bb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            val srcRate = bb.getInt(24)
            if (srcRate !in 8_000..48_000) return null
            val pcmBytes = wavBytes.size - 44
            val count = pcmBytes / 2
            val src = ShortArray(count)
            bb.position(44)
            for (i in 0 until count) {
                src[i] = bb.short
            }
            if (srcRate == 16_000) return src
            val outLen = ((count.toLong() * 16_000L) / srcRate.toLong()).toInt().coerceAtLeast(1)
            val ratio = srcRate.toDouble() / 16_000.0
            return ShortArray(outLen) { i ->
                val srcPos = i * ratio
                val idx = srcPos.toInt().coerceIn(0, count - 1)
                val next = (idx + 1).coerceIn(0, count - 1)
                val frac = srcPos - idx
                (src[idx] * (1.0 - frac) + src[next] * frac).toInt().toShort()
            }
        }

        /**
         * 120 phonetically rich sentences across 8 chapters (15 sentences per chapter).
         * Written with "Fall-Kay" phonetic spelling where spoken so Piper learns Falcon's exact cadence & pronunciation.
         */
        val STUDIO_PROMPTS: List<String> = listOf(
            // Chapter 1: Identity & "Fall-K" Signature Phrases (1..15)
            "Hey, it's Fall-Kay here. What's on the agenda for today?",
            "Good morning boss, Fall-Kay has your seven A M briefing ready.",
            "All clear right now, no urgent alerts or missed calls waiting.",
            "I checked all six of your Gmail accounts and filtered out the noise.",
            "Fall-Kay A I is online, locked to your voice, and ready to roll.",
            "You've got two priority emails and one WhatsApp message waiting for review.",
            "Want me to draft a quick reply or archive that thread for you?",
            "Locked in for ninety minutes. Do Not Disturb is active and focus music is playing.",
            "Goodnight boss. Your six thirty wake up alarm is set and verified.",
            "Plug in your phone before you sleep, battery is down to twenty eight percent.",
            "I'm right here boss, flashing the torch so you can spot the phone.",
            "Done. I saved that note straight into your private memory.",
            "Hold on a second while I pull up the latest numbers from your ledger.",
            "Nobody else can trigger Fall-Kay, your voiceprint is active on device.",
            "Let me know if you want the short version or the full breakdown.",

            // Chapter 2: Gmail, Work & Scheduling (16..30)
            "You received an interview invitation for Tuesday afternoon at two P M.",
            "I moved that promotional newsletter to Trash, you have thirty days to undo.",
            "Draft saved in your primary inbox. Say send draft whenever you're ready.",
            "Replied in thread to the recruiter and marked the message as read.",
            "Your next calendar block starts in twenty five minutes.",
            "Should I push your afternoon meeting back by half an hour?",
            "Scheduled your WhatsApp message for tomorrow morning at nine sharp.",
            "I staged three class reminders in your follow up queue for approval.",
            "There are no unread security alerts from GitHub or Heroku today.",
            "Your deployment finished cleanly with zero warnings across all tests.",
            "I searched across every connected mailbox and found two matching receipts.",
            "Starred the offer letter so it stays right at the top of your inbox.",
            "Here is what is currently on your screen, summarized into three quick points.",
            "Say cancel within six seconds if you want me to abort sending that text.",
            "I dismissed the old handoff flags and refreshed your live dashboard.",

            // Chapter 3: Aurikrex, Tech & Academics (31..45)
            "Au-rik-rex Learning Systems covers Physics, Chemistry, and Further Maths.",
            "Sync Grade calculated the semester grade point average cleanly.",
            "A new student just booked a Saturday Physics session for JAMB.",
            "Classes run daily between eleven in the morning and six in the evening, Lagos time.",
            "Let's review the thermodynamics and electromagnetism practice questions.",
            "Calculus, linear algebra, and stoichiometry are all on this week's timetable.",
            "The server health check returned green with active real time streaming.",
            "Every token and voiceprint is encrypted with hardware backed keys.",
            "I never expose private credentials, API keys, or internal configuration.",
            "Let's break the problem down from first principles before writing any code.",
            "Clean architecture beats quick patchwork every single time.",
            "We ship fast, verify every edge case, and keep zero unnecessary dependencies.",
            "The build pipeline compiled the native binary and published the release.",
            "Memory compaction preserved the important context from earlier conversations.",
            "Tell me what you're building tonight and I'll clear the distractions.",

            // Chapter 4: Finance, Ledger & Numbers (46..60)
            "Paystack settled forty five thousand Naira into your business account.",
            "Over the last fourteen days, total inflow exceeded outflow comfortably.",
            "A debit alert of twelve thousand five hundred Naira was logged just now.",
            "Three hundred and eighty dollars arrived via Wise transfer this morning.",
            "No failed payouts, chargebacks, or unauthorized transactions were detected.",
            "One, two, three, four, five, six, seven, eight, nine, ten.",
            "Eleven, twelve, fifteen, twenty, thirty five, forty eight, ninety nine.",
            "Seven fifteen A M, twelve thirty P M, four forty five P M, eleven P M.",
            "Monday, Tuesday, Wednesday, Thursday, Friday, Saturday, and Sunday.",
            "January, February, March, April, May, June, July, August, September.",
            "October, November, December, twenty twenty six, Lagos Nigeria.",
            "Your battery is at eighty four percent and currently charging.",
            "Set a twenty minute Pomodoro timer so we finish this sprint strong.",
            "Zero monthly server cost, running fast and local right on your phone.",
            "Here's your quick seven day summary of transfers and receipts.",

            // Chapter 5: Phone Controls, Calls, Media & Navigation (61..75)
            "Calling Chidi right now on speakerphone.",
            "You missed two calls in the last ten minutes, want me to ring them back?",
            "Sent your text message saying I'm five minutes away, see you soon.",
            "Skipping to the next track and turning the volume down slightly.",
            "Playing your deep focus playlist on Spotify.",
            "Paused playback while I read out this urgent message.",
            "Flashlight is on. Tap again or say turn off torch when you're done.",
            "Switched your phone to priority Do Not Disturb for the next two hours.",
            "Typed your dictated message straight into the active text box.",
            "Opening WhatsApp so you can check the attachment directly.",
            "Bluetooth earbuds connected, hands free readout is now active.",
            "I saved your post call voice note under their contact profile.",
            "Offline mode is active, handling alarms, calls, and texts with zero data.",
            "Wake word detection is running quietly in the background.",
            "Just tap the floating bar on the edge of your screen whenever you need me.",

            // Chapter 6: Rich Phonetic Balance & Natural Cadence (76..90)
            "The quick brown fox jumps smoothly over the lazy dog near the riverbank.",
            "Pack my box with five dozen liquor jugs before the evening train leaves.",
            "Sphinx of black quartz, judge my vow with sharp precision and calm focus.",
            "How vexingly quick daft zebras jump across the bright sunlit meadow.",
            "Fresh breeze swept across Victoria Island just after the heavy rain stopped.",
            "She sells seashells by the seashore while watching ships on the horizon.",
            "Bright stars shone clearly above the quiet highway at midnight.",
            "Please double check the purple folder, the yellow badge, and the silver key.",
            "Why wait until tomorrow when we can test the whole workflow tonight?",
            "Choice, voice, point,royal,measure,treasure,vision,decision,precision.",
            "Think through both options carefully, then pick the cleanest path forward.",
            "Thin threads, thick clouds, smooth leather, sharpswitches, gentle waves.",
            "Can you hear the difference between a rushed answer and a thoughtful one?",
            "Great work today! Take a breather, grab some water, and reset.",
            "Honestly, that turned out way cleaner than the first version we tried.",

            // Chapter 7: Conversational Replies & Bilingual Warmth (91..105)
            "Got it boss, I'm on it right away.",
            "No wahala at all, I've sorted that out for you.",
            "Everything is calm on WhatsApp, nothing urgent needs your attention.",
            "Wait, are you sure you want to delete that thread permanently?",
            "Sounds like a solid plan. Want me to set a reminder so you don't forget?",
            "Welcome back! Here's what happened while you were away from your desk.",
            "Heads up, someone is asking for your approval on a custom tutoring slot.",
            "I kept my reply brief and natural, matching your usual tone.",
            "If anyone else tries to speak to me, I'll stay locked until you verify.",
            "That link looks suspicious, so I blocked it before it could do anything.",
            "Give me two seconds to check live web results and summarize the facts.",
            "All right, timer started. Go crush this session!",
            "Your alarm for seven o'clock tomorrow morning is active.",
            "I couldn't reach the server just now, so I switched to offline mode.",
            "Whenever you're ready for the next prompt, tap record and speak naturally.",

            // Chapter 8: Expressive Questions, Confirmations & Wrap-Up (106..120)
            "Would you like me to read the full email aloud or just the key takeaway?",
            "Should I switch your availability to deep work until five P M?",
            "Who would you like me to message, and what time should it go out?",
            "Confirmed! Your changes are saved and synced across both devices.",
            "Cancelled! Nothing was sent, and the draft stays untouched.",
            "Fall-Kay is standing by whenever you press the button or say the word.",
            "Check your Setup tab, every permission and background check is green.",
            "Your voice dataset is looking clean, balanced, and ready to export.",
            "Once training finishes on Colab, import the zip file right here.",
            "From that moment on, Fall-Kay speaks in your own voice completely offline.",
            "Sharp, loyal, private, and built specifically around how you work.",
            "Let's run one quick check on tomorrow's schedule before wrapping up.",
            "Nothing left hanging in your follow up queue tonight.",
            "Thanks for recording these lines, your custom voice model is going to sound great!",
            "All one hundred and twenty prompts complete. Fall-Kay signing off!"
        )
    }
}
