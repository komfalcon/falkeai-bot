package tech.aurikrex.falkeai.voice

/**
 * Pure-Kotlin greedy longest-match SentencePiece BPE tokenizer for `sherpa-onnx` GigaSpeech KWS.
 * Encodes ANY custom user wake phrase (e.g. "Hey Falke", "Jarvis", "Hey Jarvis", "FalkeAI",
 * "Buddy you there", or anything Falcon adds later) into valid `sherpa-onnx` `keywords` lines
 * matching `tokens.txt` (`▁` prefix = U+2581 word boundary).
 */
object KwsBpeTokenizer {
    private const val SPIECE_UNDERLINE = '\u2581'

    fun loadVocabFromTokensTxt(tokensTxtContent: String): Set<String> {
        val vocab = HashSet<String>(1024)
        for (line in tokensTxtContent.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val token = trimmed.substringBefore(' ').trim()
            if (token.isNotEmpty() && !token.startsWith("<")) {
                vocab.add(token)
            }
        }
        return vocab
    }

    fun encodeWord(rawWord: String, vocab: Set<String>): List<String> {
        val upper = rawWord.uppercase().filter { it in 'A'..'Z' || it == '\'' }
        if (upper.isEmpty()) return emptyList()
        if (vocab.isEmpty()) return listOf("$SPIECE_UNDERLINE$upper")

        val prefixed = "$SPIECE_UNDERLINE$upper"
        val out = mutableListOf<String>()
        var idx = 0
        while (idx < prefixed.length) {
            var matched: String? = null
            var end = prefixed.length
            while (end > idx) {
                val sub = prefixed.substring(idx, end)
                if (vocab.contains(sub)) {
                    matched = sub
                    break
                }
                end--
            }
            if (matched != null) {
                out.add(matched)
                idx = end
            } else {
                // Skip unknown character cleanly (e.g. apostrophe if absent from vocab)
                val ch = prefixed[idx].toString()
                if (vocab.contains(ch)) out.add(ch)
                idx++
            }
        }
        return out
    }

    fun encodePhraseToKeywordLine(
        phrase: String,
        vocab: Set<String>,
        boostingScore: Float = 1.5f,
        threshold: Float = 0.25f
    ): String? {
        val cleanLabel = phrase.trim().replace(Regex("\\s+"), " ")
        if (cleanLabel.isEmpty()) return null
        val words = cleanLabel.split(' ').filter { it.isNotBlank() }
        val pieces = mutableListOf<String>()
        for (w in words) {
            pieces.addAll(encodeWord(w, vocab))
        }
        if (pieces.isEmpty()) return null
        return "${pieces.joinToString(" ")} :$boostingScore #$threshold @$cleanLabel"
    }

    fun buildKeywordsBuffer(phrases: List<String>, vocab: Set<String>): String {
        return phrases
            .mapNotNull { encodePhraseToKeywordLine(it, vocab) }
            .distinct()
            .joinToString("\n")
    }
}
