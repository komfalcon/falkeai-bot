package tech.aurikrex.falkeai.monitors

import tech.aurikrex.falkeai.data.BankAlertPayload
import kotlin.math.roundToLong

/**
 * Deterministic on-device Nigerian & International Bank / Fintech alert extractor (Feature #4).
 * Supports GTBank, OPay, PalmPay, Moniepoint, Kuda, Access, Zenith, UBA, FirstBank, Fidelity,
 * Stanbic, Wema/ALAT, Paystack, Stripe, and Flutterwave.
 */
object BankAlertParser {
    private val BANK_MAP = listOf(
        "opay" to "OPay",
        "palmpay" to "PalmPay",
        "moniepoint" to "Moniepoint",
        "kuda" to "Kuda",
        "gtbank" to "GTBank",
        "gtworld" to "GTBank",
        "guaranty trust" to "GTBank",
        "access" to "Access Bank",
        "zenith" to "Zenith Bank",
        "uba" to "UBA",
        "firstbank" to "FirstBank",
        "first bank" to "FirstBank",
        "fidelity" to "Fidelity Bank",
        "stanbic" to "Stanbic IBTC",
        "wema" to "Wema/ALAT",
        "alat" to "Wema/ALAT",
        "paystack" to "Paystack",
        "stripe" to "Stripe",
        "flutterwave" to "Flutterwave",
        "wise" to "Wise"
    )

    private val AMOUNT_RE = Regex(
        """(?i)(NGN|₦|N(?=\d)|USD|\$|GBP|£|EUR|€)\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)"""
    )
    private val COUNTERPARTY_RE = Regex(
        """(?i)(?:from|to|by|at|merchant|desc(?:ription)?|narration)[:\s]+([A-Za-z0-9][A-Za-z0-9\s.&'/_-]{2,42})"""
    )

    fun parse(
        packageName: String,
        appLabel: String,
        title: String,
        body: String,
        nowMs: Long = System.currentTimeMillis()
    ): BankAlertPayload? {
        val redactedBody = PrivacyRedactor.redact(body)
        val combined = "$packageName $appLabel $title $redactedBody"
        val lower = combined.lowercase()

        // Never treat OTP/login alerts as financial transactions
        if (lower.contains("[redacted-otp]") || lower.contains("verification code") || lower.contains("login alert")) {
            if (!lower.contains("credit") && !lower.contains("debit") && !lower.contains("received") && !lower.contains("transferred")) {
                return null
            }
        }

        val bank = BANK_MAP.firstOrNull { (k, _) -> lower.contains(k) }?.second ?: return null

        val direction = when {
            listOf("debit", "dr:", "debited", "sent ", "transfer to", "paid ", "purchase", "withdrawn", "charged", "spent")
                .any { lower.contains(it) } -> "debit"
            listOf("credit", "cr:", "credited", "received", "incoming", "payout", "settled", "deposited", "refund")
                .any { lower.contains(it) } -> "credit"
            else -> return null
        }

        val amtMatch = AMOUNT_RE.find("$title $redactedBody") ?: return null
        val currRaw = amtMatch.groupValues[1].uppercase()
        val currency = when {
            currRaw == "₦" || currRaw == "N" || currRaw == "NGN" -> "NGN"
            currRaw == "$" || currRaw == "USD" -> "USD"
            currRaw == "£" || currRaw == "GBP" -> "GBP"
            currRaw == "€" || currRaw == "EUR" -> "EUR"
            else -> "NGN"
        }

        val numVal = amtMatch.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        if (numVal <= 0.0) return null
        val amountMinor = (numVal * 100.0).roundToLong()

        val counterparty = COUNTERPARTY_RE.find("$title $redactedBody")
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.take(48)
            ?: if (direction == "credit") "Incoming Transfer" else "Outgoing Payment"

        val summary = "$bank ${if (direction == "credit") "CREDIT" else "DEBIT"} $currency ${amtMatch.groupValues[2]} ($counterparty)"

        return BankAlertPayload(
            source = "android",
            bank = bank,
            direction = direction,
            amountMinor = amountMinor,
            currency = currency,
            counterparty = counterparty,
            rawSummary = summary.take(200),
            createdAt = nowMs
        )
    }
}
