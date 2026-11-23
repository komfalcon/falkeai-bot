package tech.aurikrex.falkeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.voice.EnergyVad
import tech.aurikrex.falkeai.voice.KwsBpeTokenizer

class KwsBpeTokenizerTest {
    @Test
    fun encodesCustomWakeWordsAndWavHeader() {
        val sampleTokens = """
            <blk> 0
            ▁HEY 1
            ▁F 2
            AL 3
            K 4
            E 5
            ▁JAR 6
            VIS 7
            ▁BUDDY 8
            ▁YOU 9
            ▁THERE 10
        """.trimIndent()
        val vocab = KwsBpeTokenizer.loadVocabFromTokensTxt(sampleTokens)
        val buf = KwsBpeTokenizer.buildKeywordsBuffer(
            listOf("Hey Falke", "Jarvis", "Buddy you there"),
            vocab
        )
        assertTrue(buf.contains("▁HEY ▁F AL K E :1.5 #0.25 @Hey Falke"))
        assertTrue(buf.contains("▁JAR VIS :1.5 #0.25 @Jarvis"))
        assertTrue(buf.contains("▁BUDDY ▁YOU ▁THERE :1.5 #0.25 @Buddy you there"))

        val wav = EnergyVad.encodePcm16ToWav(ShortArray(160))
        assertEquals(44 + 320, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
    }
}
