package tech.aurikrex.falkeai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.aurikrex.falkeai.data.AckRequest
import tech.aurikrex.falkeai.data.DashboardResponse
import tech.aurikrex.falkeai.ui.MarkdownFormatter

class MarkdownFormatterTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun stripsAndTokenizesMarkdownCleanly() {
        val raw = "1. **Primary Gmail**: `falcon@gmail.com`\n- *Urgent* check _now_"
        val segments = MarkdownFormatter.tokenize(raw)

        assertTrue(segments.any { it.text == "Primary Gmail" && it.kind == MarkdownFormatter.SpanKind.BOLD })
        assertTrue(segments.any { it.text == "falcon@gmail.com" && it.kind == MarkdownFormatter.SpanKind.CODE })
        assertTrue(segments.any { it.text == "Urgent" && it.kind == MarkdownFormatter.SpanKind.BOLD })
        assertTrue(segments.any { it.text == "now" && it.kind == MarkdownFormatter.SpanKind.ITALIC })

        val plain = MarkdownFormatter.toPlainText(raw)
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("`"))
        assertTrue(plain.contains("• Urgent check now"))
    }

    @Test
    fun parsesDashboardWithSixGmailAccountsAndNullTolerantFields() {
        val raw = """
            {
              "server_time_ms": 1795640000000,
              "availability": {"mode": "available", "until_ms": 0, "note": null},
              "low_battery_cover": false,
              "catch_up_summary": "⚡ *Catch-Up Briefing*\nAll clear",
              "gmail_accounts": [
                {"index": 1, "label": "Primary Gmail", "email": "one@gmail.com", "status": "connected", "error_hint": null},
                {"index": 6, "label": "Gmail #6", "email": "aurikrexacademy@gmail.com", "status": "connected", "error_hint": null}
              ],
              "pending_flags": [
                {"chat": "2348011122233", "name": "Tunde", "reason": "Needs reply", "last_message": "Hi"}
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<DashboardResponse>(raw)
        assertEquals(2, parsed.gmailAccounts.size)
        assertEquals("one@gmail.com", parsed.gmailAccounts[0].email)
        assertEquals("aurikrexacademy@gmail.com", parsed.gmailAccounts[1].email)
        assertEquals(1, parsed.pendingFlags.size)

        val ack = AckRequest(dismissFlag = "2348011122233")
        assertEquals("2348011122233", ack.dismissFlag)
    }
}
