package tech.aurikrex.falkeai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FalkeApiClient(private val prefs: SecurePrefs) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Dedicated SSE client with no read timeout (server sends `: ping` every 20s for Heroku!)
    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _sseConnected = MutableStateFlow(false)
    val sseConnected: StateFlow<Boolean> = _sseConnected

    private fun baseUrl(): String {
        val raw = prefs.serverUrl.trim().trimEnd('/')
        if (raw.isEmpty()) throw IllegalStateException("Server URL not configured")
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun authedBuilder(path: String): Request.Builder {
        val token = prefs.deviceToken
        if (token.isBlank()) throw IllegalStateException("Phone not paired yet")
        return Request.Builder()
            .url("${baseUrl()}$path")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "FalkeAI-Android/1.0")
    }

    suspend fun pair(serverBaseUrl: String, pairCode: String, deviceName: String): Result<PairResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = serverBaseUrl.trim().trimEnd('/').let {
                    if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                }
                val body = json.encodeToString(PairRequest(pairCode.trim(), deviceName.trim()))
                    .toRequestBody(mediaJson)
                val req = Request.Builder()
                    .url("$normalized/api/v1/phone/pair")
                    .post(body)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val parsed = runCatching { json.decodeFromString<PairResponse>(raw) }
                        .getOrElse { PairResponse(ok = false, error = "HTTP ${resp.code}: $raw") }
                    if (!resp.isSuccessful || !parsed.ok || parsed.deviceToken.isBlank()) {
                        error(parsed.error ?: "Pairing failed (HTTP ${resp.code})")
                    }
                    prefs.serverUrl = normalized
                    prefs.deviceToken = parsed.deviceToken
                    prefs.deviceName = parsed.deviceName.ifBlank { deviceName }
                    parsed
                }
            }
        }

    suspend fun chat(reqPayload: PhoneChatRequest): Result<PhoneChatResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(reqPayload).toRequestBody(mediaJson)
                val req = authedBuilder("/api/v1/phone/chat").post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val parsed = runCatching { json.decodeFromString<PhoneChatResponse>(raw) }
                        .getOrElse { PhoneChatResponse(ok = false, error = "HTTP ${resp.code}") }
                    if (!resp.isSuccessful || !parsed.ok) {
                        error(parsed.error ?: "Chat failed (HTTP ${resp.code})")
                    }
                    parsed
                }
            }
        }

    suspend fun fetchDashboard(): Result<DashboardResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val req = authedBuilder("/api/v1/phone/dashboard").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Dashboard HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                json.decodeFromString<DashboardResponse>(raw)
            }
        }
    }

    suspend fun fetchCallerBrief(phone: String): Result<CallerBriefResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(phone, "UTF-8")
                val req = authedBuilder("/api/v1/phone/caller-brief?phone=$encoded").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Caller brief HTTP ${resp.code}")
                    json.decodeFromString<CallerBriefResponse>(resp.body?.string().orEmpty())
                }
            }
        }

    suspend fun sendCallNote(note: CallNoteRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(note).toRequestBody(mediaJson)
            val req = authedBuilder("/api/v1/phone/call-note").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Call note HTTP ${resp.code}")
            }
        }
    }

    suspend fun sendTelemetry(payload: TelemetryRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(payload).toRequestBody(mediaJson)
            val req = authedBuilder("/api/v1/phone/telemetry").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Telemetry HTTP ${resp.code}")
            }
        }
    }

    suspend fun acknowledgeAlert(target: String?, quickReply: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(AckRequest(target, quickReply)).toRequestBody(mediaJson)
                val req = authedBuilder("/api/v1/phone/ack").post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Ack HTTP ${resp.code}")
                }
            }
        }

    fun openSseStream(
        onEvent: (id: Long, type: String, data: String) -> Unit,
        onDisconnected: (Throwable?) -> Unit
    ): EventSource? {
        if (!prefs.isPaired()) return null
        val sinceId = prefs.lastEventId
        val req = authedBuilder("/api/v1/phone/stream?since_id=$sinceId")
            .header("Accept", "text/event-stream")
            .header("Last-Event-ID", sinceId.toString())
            .get()
            .build()

        return EventSources.createFactory(sseClient).newEventSource(
            req,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    _sseConnected.value = true
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    val parsedId = id?.toLongOrNull() ?: 0L
                    if (parsedId > prefs.lastEventId) {
                        prefs.lastEventId = parsedId
                    }
                    onEvent(parsedId, type.orEmpty(), data)
                }

                override fun onClosed(eventSource: EventSource) {
                    _sseConnected.value = false
                    onDisconnected(null)
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    _sseConnected.value = false
                    onDisconnected(t)
                }
            }
        )
    }

    fun markSseDisconnected() {
        _sseConnected.value = false
    }
}
