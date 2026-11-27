package tech.aurikrex.falkeai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.data.ServerSettingsDto
import tech.aurikrex.falkeai.security.DeviceCredentialGate
import tech.aurikrex.falkeai.voice.DatasetRecorderManager
import tech.aurikrex.falkeai.voice.EnergyVad
import tech.aurikrex.falkeai.voice.PronunciationMapper
import tech.aurikrex.falkeai.voice.VoiceprintMath
import kotlin.math.abs

class VoiceLockAndPronunciationTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun mapsFallKPronunciationAndNormalizesHeardTranscripts() {
        val spoken = PronunciationMapper.toSpokenFalke("Hey **Falke** from `FalkeAI` at Aurikrex!")
        assertTrue(spoken.contains("Fall-Kay"))
        assertTrue(spoken.contains("Fall-Kay A I"))
        assertTrue(spoken.contains("Au-rik-rex"))
        assertFalse(spoken.contains("**"))

        assertEquals(
            "Hey Falke check my priority emails",
            PronunciationMapper.normalizeHeardTranscript("Hey Fall-K check my priority emails")
        )
        assertEquals(
            "Ask FalkeAI to lock in",
            PronunciationMapper.normalizeHeardTranscript("Ask fall kay a i to lock in")
        )
    }

    @Test
    fun computesVoiceprintCentroidAndRejectsImpostorEmbedding() {
        val falconTake1 = floatArrayOf(0.82f, 0.41f, -0.22f, 0.31f)
        val falconTake2 = floatArrayOf(0.80f, 0.44f, -0.20f, 0.29f)
        val falconTake3 = floatArrayOf(0.84f, 0.39f, -0.24f, 0.32f)
        val impostorTake = floatArrayOf(-0.35f, 0.78f, 0.49f, -0.15f)

        val centroid = VoiceprintMath.averageCentroid(listOf(falconTake1, falconTake2, falconTake3))
        assertNotNull(centroid)
        val c = centroid!!

        val consistency = VoiceprintMath.consistencyScore(listOf(falconTake1, falconTake2, falconTake3), c)
        assertTrue("Expected high self-consistency > 0.98, got $consistency", consistency > 0.98f)

        val ownerScore = VoiceprintMath.cosineSimilarity(falconTake1, c)
        val impostorScore = VoiceprintMath.cosineSimilarity(impostorTake, c)
        assertTrue(ownerScore >= 0.98f)
        assertTrue(impostorScore < 0.25f)
    }

    @Test
    fun resamples22kStudioWavDownTo16kForSpeakerEnrollment() {
        assertEquals(120, DatasetRecorderManager.STUDIO_PROMPTS.size)
        // 1 second of 22,050 Hz tone
        val pcm22k = ShortArray(22_050) { i -> ((i % 50) * 300).toShort() }
        val wavBytes = EnergyVad.encodePcm16ToWav(pcm22k, 22_050)
        val resampled16k = DatasetRecorderManager.decodeWavAndResample16k(wavBytes)
        assertNotNull(resampled16k)
        assertTrue(abs(resampled16k!!.size - 16_000) <= 2)
    }

    @Test
    fun gatesSensitiveCommandsBehindPinOrPattern() {
        assertTrue(DeviceCredentialGate.isSensitiveCommand("Send email to sarah@acme.com"))
        assertTrue(DeviceCredentialGate.isSensitiveCommand("Trash email 18f123abc"))
        assertTrue(DeviceCredentialGate.isSensitiveCommand("Call Chidi now"))
        assertTrue(DeviceCredentialGate.isSensitiveCommand("Send SMS to Tunde"))

        assertFalse(DeviceCredentialGate.isSensitiveCommand("Check my unread emails"))
        assertFalse(DeviceCredentialGate.isSensitiveCommand("Catch me up"))
        assertFalse(DeviceCredentialGate.isSensitiveCommand("Set an alarm for 6:30am"))
    }

    @Test
    fun parsesServerSettingsPayloadCleanly() {
        val raw = """{"ok":true,"digest_hour_lagos":8,"reply_mode":"contacts","self_voice_mode":"on","ntfy_configured":true,"gmail_count":6,"paired_devices_count":1}"""
        val dto = json.decodeFromString<ServerSettingsDto>(raw)
        assertTrue(dto.ok)
        assertEquals(8, dto.digestHourLagos)
        assertEquals("contacts", dto.replyMode)
        assertEquals(6, dto.gmailCount)
    }
}
