package tech.aurikrex.falkeai.monitors

/**
 * Strictly on-device privacy filter run BEFORE any notification is saved locally or synced to cloud.
 * Strips OTPs, 2FA verification codes, bank PINs, BVN/NIN, CVV, card numbers, and passwords.
 */
object PrivacyRedactor {
    private val OTP_CONTEXT_RE = Regex(
        """(?i)\b(otp|one[\s-]?time[\s-]?(?:password|passcode|pin|code)|verification\s*code|security\s*code|auth(?:entication)?\s*code|login\s*code|confirm(?:ation)?\s*code|passcode|token|2fa|mfa|pin)\b([^\d]{0,20})(\d{4,8})\b"""
    )
    private val CODE_BEFORE_KEYWORD_RE = Regex(
        """(?i)\b(\d{4,8})\b(\s+is\s+your\s+(?:otp|verification|security|login|auth|confirm|one[\s-]?time|reset)[\w\s]{0,20}?(?:code|pin|password|passcode))"""
    )
    private val BVN_NIN_RE = Regex("""(?i)\b(bvn|nin|ssn|cvv|cvc)\b([^\d]{0,12})(\d{3,11})\b""")
    private val CARD_NUM_RE = Regex("""\b(?:\d{4}[\s-]?){3}\d{4}\b""")
    private val PASSWORD_RE = Regex("""(?i)\b(password|passwd|pwd|secret\s*key|recovery\s*code)\b(\s*[:=]\s*|\s+is\s+)(\S+)""")
    private val GOOGLE_CODE_RE = Regex("""\bG-\d{5,8}\b""")

    fun redact(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.trim()
        text = GOOGLE_CODE_RE.replace(text, "[REDACTED-OTP]")
        text = OTP_CONTEXT_RE.replace(text) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED-OTP]"
        }
        text = CODE_BEFORE_KEYWORD_RE.replace(text) { m ->
            "[REDACTED-OTP]${m.groupValues[2]}"
        }
        text = BVN_NIN_RE.replace(text) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED-ID]"
        }
        text = CARD_NUM_RE.replace(text, "[REDACTED-CARD]")
        text = PASSWORD_RE.replace(text) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED-SECRET]"
        }
        return text
    }

    fun containsSensitiveOtpOrPin(raw: String): Boolean {
        return redact(raw) != raw.trim()
    }
}
