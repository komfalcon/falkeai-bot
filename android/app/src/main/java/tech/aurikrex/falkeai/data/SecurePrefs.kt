package tech.aurikrex.falkeai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecurePrefs(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "falkeai_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Throwable) {
        // Safe fallback on OEM ROMs with transient Keystore hiccups during direct boot
        context.getSharedPreferences("falkeai_fallback_prefs", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "")?.trim()?.trimEnd('/') ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "Infinix Hot 10T") ?: "Infinix Hot 10T"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    var lastEventId: Long
        get() = prefs.getLong(KEY_LAST_EVENT_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EVENT_ID, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var screenVisionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_VISION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_VISION_ENABLED, value).apply()

    var btAutoReadoutEnabled: Boolean
        get() = prefs.getBoolean(KEY_BT_READOUT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BT_READOUT_ENABLED, value).apply()

    var bilingualMode: Boolean
        get() = prefs.getBoolean(KEY_BILINGUAL_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_BILINGUAL_MODE, value).apply()

    var confirmUnknownNumbers: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_UNKNOWN, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_UNKNOWN, value).apply()

    var speakTypedReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK_TYPED_REPLIES, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK_TYPED_REPLIES, value).apply()

    var wakeWords: List<String>
        get() {
            val raw = prefs.getString(KEY_WAKE_WORDS, null) ?: return DEFAULT_WAKE_WORDS
            return runCatching { json.decodeFromString<List<String>>(raw) }
                .getOrNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.ifEmpty { DEFAULT_WAKE_WORDS }
                ?: DEFAULT_WAKE_WORDS
        }
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            prefs.edit().putString(KEY_WAKE_WORDS, json.encodeToString(clean)).apply()
        }

    fun isPaired(): Boolean = serverUrl.isNotBlank() && deviceToken.isNotBlank()

    @Synchronized
    fun hasProcessedSmsEvent(eventId: Long): Boolean {
        if (eventId <= 0L) return false
        val raw = prefs.getString(KEY_PROCESSED_SMS_IDS, "") ?: ""
        return raw.split(',').any { it.toLongOrNull() == eventId }
    }

    @Synchronized
    fun markSmsEventProcessed(eventId: Long) {
        if (eventId <= 0L) return
        val existing = (prefs.getString(KEY_PROCESSED_SMS_IDS, "") ?: "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .toMutableList()
        if (!existing.contains(eventId)) {
            existing.add(eventId)
            val trimmed = existing.takeLast(100).joinToString(",")
            prefs.edit().putString(KEY_PROCESSED_SMS_IDS, trimmed).apply()
        }
    }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .putLong(KEY_LAST_EVENT_ID, 0L)
            .apply()
    }

    companion object {
        val DEFAULT_WAKE_WORDS = listOf(
            "Hey Falke",
            "Jarvis",
            "Hey Jarvis",
            "FalkeAI",
            "Buddy you there"
        )

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_SPEAK_TYPED_REPLIES = "speak_typed_replies"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_LAST_EVENT_ID = "last_event_id"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_SCREEN_VISION_ENABLED = "screen_vision_enabled"
        private const val KEY_BT_READOUT_ENABLED = "bt_readout_enabled"
        private const val KEY_BILINGUAL_MODE = "bilingual_mode"
        private const val KEY_CONFIRM_UNKNOWN = "confirm_unknown"
        private const val KEY_WAKE_WORDS = "wake_words_json"
        private const val KEY_PROCESSED_SMS_IDS = "processed_sms_ids"
    }
}
