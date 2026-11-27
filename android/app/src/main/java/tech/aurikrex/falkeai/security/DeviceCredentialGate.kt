package tech.aurikrex.falkeai.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import tech.aurikrex.falkeai.data.SecurePrefs

/**
 * Native Android 11+ Lockscreen PIN / Pattern / Password / Biometric Gate (`$0`, zero extra SDKs).
 * Uses `KeyguardManager.createConfirmDeviceCredentialIntent` so Infinix XOS presents the owner's
 * real system Pattern or PIN screen before sensitive actions or unlocking the app.
 */
object DeviceCredentialGate {
    private const val UNLOCK_GRACE_MS = 90_000L

    @Volatile
    private var lastVerifiedAtMs: Long = 0L

    @Volatile
    private var appSessionUnlocked: Boolean = false

    fun isDeviceLockConfigured(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        return km?.isDeviceSecure == true
    }

    @Suppress("DEPRECATION")
    fun buildSystemCredentialIntent(
        context: Context,
        title: String = "Verify It's Falcon",
        description: String = "Enter your phone PIN or Pattern to authorize FalkeAI"
    ): Intent? {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return null
        if (!km.isDeviceSecure) return null
        return km.createConfirmDeviceCredentialIntent(title, description)
    }

    fun markVerifiedNow() {
        lastVerifiedAtMs = System.currentTimeMillis()
        appSessionUnlocked = true
    }

    fun lockAppSession() {
        appSessionUnlocked = false
        lastVerifiedAtMs = 0L
    }

    fun isAppUnlocked(prefs: SecurePrefs): Boolean {
        if (!prefs.appLockEnabled) return true
        return appSessionUnlocked
    }

    fun isSensitiveActionRecentlyVerified(): Boolean {
        val elapsed = System.currentTimeMillis() - lastVerifiedAtMs
        return lastVerifiedAtMs > 0L && elapsed in 0..UNLOCK_GRACE_MS
    }

    /**
     * Checks whether a natural-language text command triggers a sensitive outbound or destructive action
     * (sending/replying/trashing email, placing a phone call, or sending an SMS) that should be gated
     * by PIN/Pattern when `prefs.requireLockForSensitive` is enabled.
     */
    fun isSensitiveCommand(rawText: String): Boolean {
        val lower = " ${rawText.trim().lowercase()} "
        val triggers = listOf(
            " send email",
            " send an email",
            " send gmail",
            " reply to email",
            " reply email",
            " send draft",
            " trash ",
            " delete email",
            " delete the email",
            " delete spam",
            " call ",
            " dial ",
            " send sms",
            " text ",
            " send message to",
            " dm ",
            " send follow-up"
        )
        return triggers.any { lower.contains(it) }
    }
}
