package tech.aurikrex.falkeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.monitors.PrivacyRedactor

class PrivacyRedactorTest {
    @Test
    fun stripsOtpsPinsCardsBvnAndGoogleCodes() {
        val sms1 = "GTBank: Your OTP is 849201. Do not share."
        val out1 = PrivacyRedactor.redact(sms1)
        assertFalse(out1.contains("849201"))
        assertTrue(out1.contains("[REDACTED-OTP]"))

        val sms2 = "482910 is your verification code for Instagram"
        val out2 = PrivacyRedactor.redact(sms2)
        assertFalse(out2.contains("482910"))
        assertTrue(out2.contains("[REDACTED-OTP]"))

        val sms3 = "G-901234 is your Google verification code."
        assertFalse(PrivacyRedactor.redact(sms3).contains("901234"))

        val sms4 = "Card 4539 1234 5678 9012 with CVV 482 and BVN 22334455667"
        val out4 = PrivacyRedactor.redact(sms4)
        assertFalse(out4.contains("4539"))
        assertFalse(out4.contains("482"))
        assertFalse(out4.contains("22334455667"))

        val normal = "Hey Falcon, are we still on for JAMB Physics at 2pm?"
        assertEquals(normal, PrivacyRedactor.redact(normal))
        assertFalse(PrivacyRedactor.containsSensitiveOtpOrPin(normal))
    }
}
