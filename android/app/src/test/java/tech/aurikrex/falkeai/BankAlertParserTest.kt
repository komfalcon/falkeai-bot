package tech.aurikrex.falkeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.aurikrex.falkeai.monitors.BankAlertParser

class BankAlertParserTest {
    @Test
    fun parsesNigerianBankAndPaystackAlerts() {
        val opay = BankAlertParser.parse(
            packageName = "team.opay.pay",
            appLabel = "OPay",
            title = "Debit Alert",
            body = "You sent ₦4,500.00 to Bolt Ride Lagos",
            nowMs = 1_000_000L
        )
        assertNotNull(opay)
        assertEquals("OPay", opay!!.bank)
        assertEquals("debit", opay.direction)
        assertEquals(450_000L, opay.amountMinor)
        assertEquals("NGN", opay.currency)

        val paystack = BankAlertParser.parse(
            packageName = "com.google.android.gm",
            appLabel = "Gmail",
            title = "Paystack Payout Settled",
            body = "Credit of NGN 125,000.00 settled from Aurikrex Subscriptions",
            nowMs = 2_000_000L
        )
        assertNotNull(paystack)
        assertEquals("Paystack", paystack!!.bank)
        assertEquals("credit", paystack.direction)
        assertEquals(12_500_000L, paystack.amountMinor)

        // Pure OTP message from bank must NOT become a fake financial transaction
        val otpOnly = BankAlertParser.parse(
            packageName = "com.gtbank.gtworld",
            appLabel = "GTBank",
            title = "Login Alert",
            body = "Your GTBank OTP is 778899",
            nowMs = 3_000_000L
        )
        assertNull(otpOnly)
    }
}
