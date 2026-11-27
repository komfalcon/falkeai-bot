package tech.aurikrex.falkeai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Hardware-backed AES-256-GCM Encrypted Preferences.
 * Security Guarantee: NEVER falls back to cleartext disk SharedPreferences for tokens or voiceprints.
 * If Android Keystore invalidates the master key (e.g. OS re-flash or lockscreen wipe), cleanly resets
 * the corrupted encrypted file, regenerates a fresh hardware key, and sets `keystoreResetNoticePending`
 * so the user is prompted to re-pair instead of silently leaking secrets to plaintext XML.
 */
class SecurePrefs(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private var resetOccurred = false

    private val prefs: SharedPreferences? = initEncryptedPrefs(context.applicationContext)

    // Volatile RAM-only fallback used ONLY if Keystore hardware is completely unavailable (e.g. unit test JVM)
    private val memoryFallback = HashMap<String, Any>()

    private fun initEncryptedPrefs(appCtx: Context): SharedPreferences? {
        return try {
            createEncrypted(appCtx)
        } catch (_: Throwable) {
            // Keystore entry corrupted — purge encrypted file + broken alias, then recreate securely
            runCatching {
                appCtx.deleteSharedPreferences(PREFS_FILE)
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }
            // Also wipe any legacy plaintext fallback file from v1.0 if present
            runCatching { appCtx.deleteSharedPreferences("falkeai_fallback_prefs") }
            resetOccurred = true
            runCatching { createEncrypted(appCtx) }.getOrNull()?.also {
                it.edit().putBoolean(KEY_KEYSTORE_RESET_NOTICE, true).apply()
            }
        }
    }

    private fun createEncrypted(appCtx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appCtx,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getStr(key: String, def: String = ""): String =
        prefs?.getString(key, def) ?: (memoryFallback[key] as? String ?: def)

    private fun putStr(key: String, value: String) {
        if (prefs != null) prefs.edit().putString(key, value).apply()
        else memoryFallback[key] = value
    }

    private fun getBool(key: String, def: Boolean): Boolean =
        prefs?.getBoolean(key, def) ?: (memoryFallback[key] as? Boolean ?: def)

    private fun putBool(key: String, value: Boolean) {
        if (prefs != null) prefs.edit().putBoolean(key, value).apply()
        else memoryFallback[key] = value
    }

    private fun getLongVal(key: String, def: Long): Long =
        prefs?.getLong(key, def) ?: (memoryFallback[key] as? Long ?: def)

    private fun putLongVal(key: String, value: Long) {
        if (prefs != null) prefs.edit().putLong(key, value).apply()
        else memoryFallback[key] = value
    }

    private fun getIntVal(key: String, def: Int): Int =
        prefs?.getInt(key, def) ?: (memoryFallback[key] as? Int ?: def)

    private fun putIntVal(key: String, value: Int) {
        if (prefs != null) prefs.edit().putInt(key, value).apply()
        else memoryFallback[key] = value
    }

    private fun getFloatVal(key: String, def: Float): Float =
        prefs?.getFloat(key, def) ?: (memoryFallback[key] as? Float ?: def)

    private fun putFloatVal(key: String, value: Float) {
        if (prefs != null) prefs.edit().putFloat(key, value).apply()
        else memoryFallback[key] = value
    }

    private fun removeKey(key: String) {
        if (prefs != null) prefs.edit().remove(key).apply()
        else memoryFallback.remove(key)
    }

    var keystoreResetNoticePending: Boolean
        get() = resetOccurred || getBool(KEY_KEYSTORE_RESET_NOTICE, false)
        set(value) {
            resetOccurred = value
            putBool(KEY_KEYSTORE_RESET_NOTICE, value)
        }

    var serverUrl: String
        get() = getStr(KEY_SERVER_URL, "").trim().trimEnd('/')
        set(value) = putStr(KEY_SERVER_URL, value.trim().trimEnd('/'))

    var deviceToken: String
        get() = getStr(KEY_DEVICE_TOKEN, "")
        set(value) = putStr(KEY_DEVICE_TOKEN, value.trim())

    var deviceName: String
        get() = getStr(KEY_DEVICE_NAME, "Infinix Hot 10T")
        set(value) = putStr(KEY_DEVICE_NAME, value.trim())

    var lastEventId: Long
        get() = getLongVal(KEY_LAST_EVENT_ID, 0L)
        set(value) = putLongVal(KEY_LAST_EVENT_ID, value)

    var wakeWordEnabled: Boolean
        get() = getBool(KEY_WAKE_WORD_ENABLED, true)
        set(value) = putBool(KEY_WAKE_WORD_ENABLED, value)

    var screenVisionEnabled: Boolean
        get() = getBool(KEY_SCREEN_VISION_ENABLED, true)
        set(value) = putBool(KEY_SCREEN_VISION_ENABLED, value)

    var btAutoReadoutEnabled: Boolean
        get() = getBool(KEY_BT_READOUT_ENABLED, true)
        set(value) = putBool(KEY_BT_READOUT_ENABLED, value)

    var bilingualMode: Boolean
        get() = getBool(KEY_BILINGUAL_MODE, true)
        set(value) = putBool(KEY_BILINGUAL_MODE, value)

    var confirmUnknownNumbers: Boolean
        get() = getBool(KEY_CONFIRM_UNKNOWN, true)
        set(value) = putBool(KEY_CONFIRM_UNKNOWN, value)

    var speakTypedReplies: Boolean
        get() = getBool(KEY_SPEAK_TYPED_REPLIES, false)
        set(value) = putBool(KEY_SPEAK_TYPED_REPLIES, value)

    var persistChatHistory: Boolean
        get() = getBool(KEY_PERSIST_CHAT, true)
        set(value) = putBool(KEY_PERSIST_CHAT, value)

    var appLockEnabled: Boolean
        get() = getBool(KEY_APP_LOCK, false)
        set(value) = putBool(KEY_APP_LOCK, value)

    var requireLockForSensitive: Boolean
        get() = getBool(KEY_SENSITIVE_LOCK, true)
        set(value) = putBool(KEY_SENSITIVE_LOCK, value)

    var floatingBubbleEnabled: Boolean
        get() = getBool(KEY_FLOATING_BUBBLE, false)
        set(value) = putBool(KEY_FLOATING_BUBBLE, value)

    var bubblePosX: Int
        get() = getIntVal(KEY_BUBBLE_X, 0)
        set(value) = putIntVal(KEY_BUBBLE_X, value)

    var bubblePosY: Int
        get() = getIntVal(KEY_BUBBLE_Y, 420)
        set(value) = putIntVal(KEY_BUBBLE_Y, value)

    var infinixAutoStartConfirmed: Boolean
        get() = getBool(KEY_INFINIX_AUTOSTART_ACK, false)
        set(value) = putBool(KEY_INFINIX_AUTOSTART_ACK, value)

    // Speaker Verification ("Only My Voice Works")
    var voiceLockEnabled: Boolean
        get() = getBool(KEY_VOICE_LOCK_ENABLED, false)
        set(value) = putBool(KEY_VOICE_LOCK_ENABLED, value)

    var voiceMatchThreshold: Float
        get() = getFloatVal(KEY_VOICE_MATCH_THRESHOLD, 0.58f).coerceIn(0.40f, 0.80f)
        set(value) = putFloatVal(KEY_VOICE_MATCH_THRESHOLD, value.coerceIn(0.40f, 0.80f))

    var enrolledVoiceprint: FloatArray?
        get() {
            val raw = getStr(KEY_VOICEPRINT_VEC, "")
            if (raw.isBlank()) return null
            return runCatching {
                raw.split(',').map { it.toFloat() }.toFloatArray().takeIf { it.size >= 64 }
            }.getOrNull()
        }
        set(value) {
            if (value == null || value.isEmpty()) {
                removeKey(KEY_VOICEPRINT_VEC)
            } else {
                putStr(KEY_VOICEPRINT_VEC, value.joinToString(",") { "%.6f".format(java.util.Locale.US, it) })
            }
        }

    var enrolledSampleCount: Int
        get() = getIntVal(KEY_ENROLLED_SAMPLES, 0)
        set(value) = putIntVal(KEY_ENROLLED_SAMPLES, value)

    // Custom Trained Piper/VITS Own-Voice TTS
    var customVoiceEnabled: Boolean
        get() = getBool(KEY_CUSTOM_VOICE_ENABLED, true)
        set(value) = putBool(KEY_CUSTOM_VOICE_ENABLED, value)

    var speechSpeed: Float
        get() = getFloatVal(KEY_SPEECH_SPEED, 1.0f).coerceIn(0.75f, 1.35f)
        set(value) = putFloatVal(KEY_SPEECH_SPEED, value.coerceIn(0.75f, 1.35f))

    // Optional fallback in-app PIN hash (used only if Android device has no lockscreen PIN/Pattern)
    fun hasFallbackPin(): Boolean = getStr(KEY_FALLBACK_PIN_HASH, "").isNotBlank()

    fun setFallbackPin(pin: String) {
        val clean = pin.trim()
        if (clean.length < 4) return
        putStr(KEY_FALLBACK_PIN_HASH, hashPin(clean))
    }

    fun verifyFallbackPin(pin: String): Boolean {
        val expected = getStr(KEY_FALLBACK_PIN_HASH, "")
        if (expected.isBlank()) return false
        return expected == hashPin(pin.trim())
    }

    private fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("falkeai-pin-v1:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    var wakeWords: List<String>
        get() {
            val raw = getStr(KEY_WAKE_WORDS, "")
            if (raw.isBlank()) return DEFAULT_WAKE_WORDS
            return runCatching { json.decodeFromString<List<String>>(raw) }
                .getOrNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.ifEmpty { DEFAULT_WAKE_WORDS }
                ?: DEFAULT_WAKE_WORDS
        }
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            putStr(KEY_WAKE_WORDS, json.encodeToString(clean))
        }

    fun isPaired(): Boolean = serverUrl.isNotBlank() && deviceToken.isNotBlank()

    fun hasVoiceprint(): Boolean = enrolledVoiceprint != null

    @Synchronized
    fun hasProcessedSmsEvent(eventId: Long): Boolean {
        if (eventId <= 0L) return false
        val raw = getStr(KEY_PROCESSED_SMS_IDS, "")
        return raw.split(',').any { it.toLongOrNull() == eventId }
    }

    @Synchronized
    fun markSmsEventProcessed(eventId: Long) {
        if (eventId <= 0L) return
        val existing = getStr(KEY_PROCESSED_SMS_IDS, "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .toMutableList()
        if (!existing.contains(eventId)) {
            existing.add(eventId)
            val trimmed = existing.takeLast(100).joinToString(",")
            putStr(KEY_PROCESSED_SMS_IDS, trimmed)
        }
    }

    fun clearPairing() {
        removeKey(KEY_DEVICE_TOKEN)
        putLongVal(KEY_LAST_EVENT_ID, 0L)
        keystoreResetNoticePending = false
    }

    fun clearVoiceprint() {
        removeKey(KEY_VOICEPRINT_VEC)
        enrolledSampleCount = 0
        voiceLockEnabled = false
    }

    companion object {
        private const val PREFS_FILE = "falkeai_secure_prefs"

        val DEFAULT_WAKE_WORDS = listOf(
            "Hey Fall Kay",
            "Fall Kay",
            "Hey Falke",
            "Jarvis",
            "Hey Jarvis",
            "FalkeAI",
            "Buddy you there"
        )

        private const val KEY_KEYSTORE_RESET_NOTICE = "keystore_reset_notice"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_LAST_EVENT_ID = "last_event_id"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_SCREEN_VISION_ENABLED = "screen_vision_enabled"
        private const val KEY_BT_READOUT_ENABLED = "bt_readout_enabled"
        private const val KEY_BILINGUAL_MODE = "bilingual_mode"
        private const val KEY_CONFIRM_UNKNOWN = "confirm_unknown_numbers"
        private const val KEY_SPEAK_TYPED_REPLIES = "speak_typed_replies"
        private const val KEY_PERSIST_CHAT = "persist_chat_history"
        private const val KEY_APP_LOCK = "app_lock_enabled"
        private const val KEY_SENSITIVE_LOCK = "require_lock_sensitive"
        private const val KEY_FLOATING_BUBBLE = "floating_bubble_enabled"
        private const val KEY_BUBBLE_X = "bubble_pos_x"
        private const val KEY_BUBBLE_Y = "bubble_pos_y"
        private const val KEY_INFINIX_AUTOSTART_ACK = "infinix_autostart_ack"
        private const val KEY_VOICE_LOCK_ENABLED = "voice_lock_enabled"
        private const val KEY_VOICE_MATCH_THRESHOLD = "voice_match_threshold"
        private const val KEY_VOICEPRINT_VEC = "enrolled_voiceprint_vec"
        private const val KEY_ENROLLED_SAMPLES = "enrolled_sample_count"
        private const val KEY_CUSTOM_VOICE_ENABLED = "custom_voice_enabled"
        private const val KEY_SPEECH_SPEED = "speech_speed"
        private const val KEY_FALLBACK_PIN_HASH = "fallback_pin_hash"
        private const val KEY_WAKE_WORDS = "wake_words_json"
        private const val KEY_PROCESSED_SMS_IDS = "processed_sms_ids"
    }
}
