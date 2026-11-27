package tech.aurikrex.falkeai.voice

/**
 * Ensures FalkeAI both HEARS and SPEAKS "Falke" using Falcon's exact pronunciation ("Fall-K" / "Fall-Kay").
 */
object PronunciationMapper {
    private val falkeAiRegex = Regex("(?i)\\bfalkeai\\b")
    private val falkeRegex = Regex("(?i)\\bfalke\\b")
    private val aurikrexRegex = Regex("(?i)\\baurikrex\\b")
    private val syncGradeRegex = Regex("(?i)\\bsyncgrade\\b")

    private val heardFalkeAiRegex = Regex("(?i)\\b(?:fall[-\\s]?k|fall[-\\s]?kay|falky|folke)[-\\s]*a[-\\s]*i\\b")
    private val heardFalkeRegex = Regex("(?i)\\b(?:fall[-\\s]?k|fall[-\\s]?kay|falky|folke)\\b")

    /** Prepares clean spoken text with Falcon's "Fall-K" pronunciation for local/custom TTS. */
    fun toSpokenFalke(raw: String, maxChars: Int = 750): String {
        val stripped = raw
            .replace(Regex("[*_`#~•]"), " ")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(falkeAiRegex, "Fall-Kay A I")
            .replace(falkeRegex, "Fall-Kay")
            .replace(aurikrexRegex, "Au-rik-rex")
            .replace(syncGradeRegex, "Sync Grade")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.take(maxChars)
    }

    /** Normalizes STT transcripts ("Hey Fall-K...") back into canonical "Falke" / "FalkeAI". */
    fun normalizeHeardTranscript(raw: String): String {
        val step1 = heardFalkeAiRegex.replace(raw, "FalkeAI")
        return heardFalkeRegex.replace(step1, "Falke")
    }
}
