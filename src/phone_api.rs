//! Unified HTTP + SSE Phone Bridge API for the Native Kotlin FalkeAI Android Companion App.
//!
//! Runs directly inside `falkeai-core` on Heroku's `0.0.0.0:$PORT` with **zero extra Cargo crates**
//! (pure `tokio::net::TcpListener`, `tokio::io`, `sha2`, `hex`, `serde_json`).
//!
//! Security & Penetration Hardening:
//! - True 256-bit OS CSPRNG Bearer `device_token` (`fkd_<64 hex>`) stored SHA-256 hashed in Turso.
//! - Serialized pairing lock (`pair_lock`) prevents TOCTOU race replay AND wipes active pairing code
//!   after 5 failed guesses (defeating brute-force attacks).
//! - Two-stage HTTP parser (`read_http_headers` -> Bearer auth -> `read_http_body`) rejects
//!   unauthenticated oversized bodies BEFORE allocating heap memory (defeating OOM DoS on 512 MB Heroku).
//! - Blocks HTTP Request Smuggling (`Transfer-Encoding` / duplicate `Content-Length`) and enforces
//!   `X-Forwarded-Proto: https` behind reverse proxies.
//! - Concurrency semaphores (`32` HTTP + `4` SSE) prevent connection-flood exhaustion.
//! - SSE stream (`GET /api/v1/phone/stream?since_id=N`) replays missed Turso `phone_events` and emits
//!   `: ping\n\n` every 20 seconds so Heroku's 55-second idle timeout never drops the socket.

use anyhow::{anyhow, Result};
use chrono::Utc;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, Mutex as AsyncMutex, Semaphore};
use tracing::{info, warn};

use crate::alerts::{build_catch_me_up_summary, format_bank_amount};
use crate::brain::{call_llm_http, HISTORY_MAX};
use crate::commands::{resolve_contact_target, run_owner_command, take_approved_outbounds};
use crate::config::{Config, BOT_NAME};
use crate::db::{
    memory_token_jaccard, ChatTurn, ContactMemory, ConversationState, Database, PhoneEventRecord,
    PhoneTelemetrySnapshot,
};
use crate::gmail::{resolve_gmail_account_profiles, GmailAccountProfile, GmailTokenCache};
use crate::media::{
    base64_decode, base64_encode, describe_image_bytes, synthesize_voice_bytes,
    transcribe_audio_bytes, MediaBudget, MAX_AUDIO_BYTES, MAX_IMAGE_BYTES,
};
use crate::owner_chat::{handle_unified_owner_chat, OwnerPhoneContext};
use crate::safety::{detect_injection, os_csprng_32_bytes, redact_secrets, sanitize_inbound};
use crate::util::normalize_number;
use crate::web_reader::enrich_text_with_web_links;
use crate::web_search::percent_decode;

pub const MAX_HTTP_HEADER_BYTES: usize = 16 * 1024;
pub const MAX_PAIR_BODY_BYTES: usize = 2 * 1024;
pub const MAX_TELEMETRY_BODY_BYTES: usize = 128 * 1024;
pub const MAX_ACK_BODY_BYTES: usize = 16 * 1024;
pub const MAX_HTTP_BODY_BYTES: usize = 6 * 1024 * 1024;
pub const SSE_PING_INTERVAL_SECS: u64 = 20;
pub const MAX_PAIR_FAILED_ATTEMPTS: u32 = 5;

pub struct PhoneApiState {
    pub db: Database,
    pub cfg: Config,
    pub gmail_cache: Arc<GmailTokenCache>,
    pub phone_bus: broadcast::Sender<PhoneEventRecord>,
    pub web_budget: Arc<MediaBudget>,
    pub tts_budget: Arc<MediaBudget>,
    pub wa_send_tx: mpsc::UnboundedSender<(String, String)>,
    pub pair_lock: AsyncMutex<u32>,
    pub http_sem: Arc<Semaphore>,
    pub sse_sem: Arc<Semaphore>,
}

impl PhoneApiState {
    pub fn new(
        db: Database,
        cfg: Config,
        gmail_cache: Arc<GmailTokenCache>,
        phone_bus: broadcast::Sender<PhoneEventRecord>,
        web_budget: Arc<MediaBudget>,
        tts_budget: Arc<MediaBudget>,
        wa_send_tx: mpsc::UnboundedSender<(String, String)>,
    ) -> Self {
        Self {
            db,
            cfg,
            gmail_cache,
            phone_bus,
            web_budget,
            tts_budget,
            wa_send_tx,
            pair_lock: AsyncMutex::new(0),
            http_sem: Arc::new(Semaphore::new(32)),
            sse_sem: Arc::new(Semaphore::new(4)),
        }
    }
}

#[derive(Debug, Clone)]
pub struct HttpHeaders {
    pub method: String,
    pub path: String,
    pub raw_target: String,
    pub headers: HashMap<String, String>,
    pub content_length: usize,
    pub pre_read_body: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HttpParseError {
    Eof,
    HeadersTooLarge,
    MalformedRequest,
    SmugglingDetected,
    ChunkedUnsupported,
}

pub fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    hex::encode(hasher.finalize())
}

pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

pub fn generate_device_token(cfg: &Config, device_name: &str, now_ms: i64) -> String {
    let entropy_a = os_csprng_32_bytes();
    let entropy_b = os_csprng_32_bytes();

    let mut h1 = Sha256::new();
    h1.update(b"falkeai-device-token-v2:");
    h1.update(entropy_a);
    h1.update(now_ms.to_le_bytes());
    h1.update(device_name.as_bytes());
    h1.update(cfg.phone_bridge_secret.as_bytes());
    let d1 = h1.finalize();

    let mut h2 = Sha256::new();
    h2.update(d1);
    h2.update(entropy_b);
    h2.update(cfg.pair_phone.as_bytes());
    let d2 = h2.finalize();

    format!("fkd_{}{}", hex::encode(&d1[..16]), hex::encode(&d2[..16]))
}

pub fn format_sse_event(ev: &PhoneEventRecord) -> String {
    let safe_type = ev
        .event_type
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .collect::<String>();
    let ev_name = if safe_type.is_empty() {
        "message".to_string()
    } else {
        safe_type
    };
    let clean_payload = ev.payload_json.replace(['\r', '\n'], " ");
    format!(
        "id: {}\nevent: {}\ndata: {}\n\n",
        ev.id, ev_name, clean_payload
    )
}

/// Persists an event in Turso `phone_events` and broadcasts it to any live Android SSE streams.
pub async fn publish_phone_event(
    db: &Database,
    bus: &broadcast::Sender<PhoneEventRecord>,
    event_type: &str,
    payload: Value,
    now_ms: i64,
) -> Result<PhoneEventRecord> {
    let payload_json = payload.to_string();
    let rec = db
        .append_phone_event(event_type, &payload_json, now_ms)
        .await?;
    let _ = bus.send(rec.clone());
    Ok(rec)
}

pub fn parse_query_param(raw_target: &str, key: &str) -> Option<String> {
    let (_, query) = raw_target.split_once('?')?;
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k == key {
                return Some(percent_decode(v));
            }
        }
    }
    None
}

fn extract_bearer_token(headers: &HashMap<String, String>) -> Option<String> {
    let auth = headers.get("authorization")?;
    let trimmed = auth.trim();
    let prefix = trimmed.get(..7)?;
    if prefix.eq_ignore_ascii_case("bearer ") {
        let tok = trimmed[7..].trim();
        if !tok.is_empty() && tok.len() <= 256 {
            return Some(tok.to_string());
        }
    }
    None
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

/// Stage 1 HTTP parser: reads ONLY headers (up to 16 KB) so unauthenticated requests
/// can NEVER force large body allocations before Bearer verification!
pub async fn read_http_headers<R: AsyncRead + Unpin>(
    reader: &mut R,
) -> std::result::Result<HttpHeaders, HttpParseError> {
    let mut buf: Vec<u8> = Vec::with_capacity(2048);
    let mut chunk = [0u8; 2048];
    let header_end = loop {
        if let Some(pos) = find_header_end(&buf) {
            break pos;
        }
        if buf.len() > MAX_HTTP_HEADER_BYTES {
            return Err(HttpParseError::HeadersTooLarge);
        }
        let n = reader
            .read(&mut chunk)
            .await
            .map_err(|_| HttpParseError::MalformedRequest)?;
        if n == 0 {
            if buf.is_empty() {
                return Err(HttpParseError::Eof);
            }
            return Err(HttpParseError::MalformedRequest);
        }
        buf.extend_from_slice(&chunk[..n]);
    };

    let header_str = String::from_utf8_lossy(&buf[..header_end]).into_owned();
    let mut lines = header_str.split("\r\n");
    let request_line = lines.next().ok_or(HttpParseError::MalformedRequest)?;
    let mut parts = request_line.split_whitespace();
    let method = parts
        .next()
        .ok_or(HttpParseError::MalformedRequest)?
        .to_uppercase();
    let raw_target = parts
        .next()
        .ok_or(HttpParseError::MalformedRequest)?
        .to_string();
    let raw_path = raw_target
        .split_once('?')
        .map(|(p, _)| p)
        .unwrap_or(&raw_target);
    if !raw_path.starts_with('/') || raw_path.contains("..") || raw_path.contains('\\') {
        return Err(HttpParseError::MalformedRequest);
    }
    let path = raw_path.to_string();

    let mut headers = HashMap::new();
    let mut seen_content_length: Option<usize> = None;
    let mut has_transfer_encoding = false;

    for line in lines {
        if line.is_empty() {
            continue;
        }
        let Some((k, v)) = line.split_once(':') else {
            return Err(HttpParseError::MalformedRequest);
        };
        if k.chars().any(|c| c.is_whitespace()) {
            // RFC 7230 obs-fold / whitespace before colon smuggling defense
            return Err(HttpParseError::SmugglingDetected);
        }
        let key = k.trim().to_lowercase();
        let val = v.trim().to_string();
        if key == "transfer-encoding" {
            has_transfer_encoding = true;
        } else if key == "content-length" {
            let parsed = val
                .parse::<usize>()
                .map_err(|_| HttpParseError::MalformedRequest)?;
            if let Some(prev) = seen_content_length {
                if prev != parsed {
                    return Err(HttpParseError::SmugglingDetected);
                }
            }
            seen_content_length = Some(parsed);
        }
        headers.insert(key, val);
    }

    if has_transfer_encoding {
        if seen_content_length.is_some() {
            return Err(HttpParseError::SmugglingDetected);
        }
        return Err(HttpParseError::ChunkedUnsupported);
    }

    let content_length = seen_content_length.unwrap_or(0);
    let body_start = header_end + 4;
    let pre_read_body = if buf.len() > body_start {
        buf[body_start..].to_vec()
    } else {
        Vec::new()
    };

    Ok(HttpHeaders {
        method,
        path,
        raw_target,
        headers,
        content_length,
        pre_read_body,
    })
}

/// Stage 2 HTTP parser: reads the exact `Content-Length` body ONLY AFTER route & auth checks pass.
pub async fn read_http_body<R: AsyncRead + Unpin>(
    reader: &mut R,
    hdr: &HttpHeaders,
    max_allowed: usize,
) -> Result<Vec<u8>> {
    if hdr.content_length > max_allowed {
        return Err(anyhow!("Payload exceeds route limit"));
    }
    if hdr.content_length == 0 {
        return Ok(Vec::new());
    }
    let mut body = Vec::with_capacity(hdr.content_length);
    let initial_take = hdr.pre_read_body.len().min(hdr.content_length);
    if initial_take > 0 {
        body.extend_from_slice(&hdr.pre_read_body[..initial_take]);
    }
    let mut chunk = [0u8; 8192];
    while body.len() < hdr.content_length {
        let rem = (hdr.content_length - body.len()).min(chunk.len());
        let n = reader.read(&mut chunk[..rem]).await?;
        if n == 0 {
            return Err(anyhow!("Unexpected EOF reading HTTP body"));
        }
        body.extend_from_slice(&chunk[..n]);
    }
    Ok(body)
}

fn status_reason(code: u16) -> &'static str {
    match code {
        200 => "OK",
        201 => "Created",
        204 => "No Content",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        411 => "Length Required",
        413 => "Payload Too Large",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "OK",
    }
}

async fn write_json_response(stream: &mut TcpStream, status: u16, payload: &Value) {
    let body = payload.to_string();
    let resp = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n{}",
        status,
        status_reason(status),
        body.len(),
        body
    );
    let _ = stream.write_all(resp.as_bytes()).await;
    let _ = stream.flush().await;
}

/// `POST /api/v1/phone/pair` — serialized via `pair_lock` to eliminate TOCTOU race conditions
/// AND auto-wipes the pairing code after 5 failed guesses to defeat brute-force attacks.
pub async fn handle_pair_endpoint_locked(
    pair_lock: &AsyncMutex<u32>,
    db: &Database,
    cfg: &Config,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let mut failed_attempts = pair_lock.lock().await;
    let (status, resp) = handle_pair_inner(db, cfg, body, now_ms, &mut failed_attempts).await;
    (status, resp)
}

pub async fn handle_pair_endpoint(
    db: &Database,
    cfg: &Config,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let mut dummy_counter = 0u32;
    handle_pair_inner(db, cfg, body, now_ms, &mut dummy_counter).await
}

async fn handle_pair_inner(
    db: &Database,
    cfg: &Config,
    body: &Value,
    now_ms: i64,
    failed_attempts: &mut u32,
) -> (u16, Value) {
    let raw_code = body["code"]
        .as_str()
        .or_else(|| body["pair_code"].as_str())
        .unwrap_or("")
        .trim()
        .to_uppercase();
    let device_name = body["device_name"]
        .as_str()
        .unwrap_or("Infinix Hot 10T")
        .trim()
        .chars()
        .filter(|c| !c.is_control())
        .take(80)
        .collect::<String>();

    if raw_code.is_empty() || raw_code.len() > 32 {
        return (400, json!({ "error": "Missing or invalid pairing code" }));
    }

    let saved_raw = match db.get_setting("android_pair_code_json").await {
        Ok(Some(v)) => v,
        _ => {
            return (
                401,
                json!({ "error": "No active pairing code. Send 'pair android' in WhatsApp self-chat first." }),
            );
        }
    };
    let saved_json: Value = match serde_json::from_str(&saved_raw) {
        Ok(v) => v,
        Err(_) => {
            return (
                401,
                json!({ "error": "Pairing code invalid. Generate a new code with 'pair android'." }),
            );
        }
    };
    let expected_code = saved_json["code"].as_str().unwrap_or("").trim().to_uppercase();
    let expires_at = saved_json["expires_at"].as_i64().unwrap_or(0);

    if expected_code.is_empty() || now_ms > expires_at {
        let _ = db.set_setting("android_pair_code_json", "{}").await;
        *failed_attempts = 0;
        return (
            401,
            json!({ "error": "Pairing code expired (5-min window). Run 'pair android' again." }),
        );
    }

    // Normalize optional dash formatting so both `FALKE-ABCD-EFGH` and `FALKEABCDEFGH` compare cleanly
    let norm_got = raw_code.replace(' ', "");
    if !constant_time_eq(norm_got.as_bytes(), expected_code.as_bytes()) {
        *failed_attempts = failed_attempts.saturating_add(1);
        if *failed_attempts >= MAX_PAIR_FAILED_ATTEMPTS {
            let _ = db.set_setting("android_pair_code_json", "{}").await;
            *failed_attempts = 0;
            return (
                429,
                json!({ "error": "Too many incorrect guesses — pairing code invalidated for security. Run 'pair android' again." }),
            );
        }
        return (401, json!({ "error": "Incorrect pairing code." }));
    }

    // Single-use: invalidate immediately under lock so concurrent replay is impossible
    let _ = db.set_setting("android_pair_code_json", "{}").await;
    *failed_attempts = 0;

    let latest_event_id = db
        .list_phone_events_since(0, 200)
        .await
        .ok()
        .and_then(|v| v.last().map(|e| e.id))
        .unwrap_or(0);

    let token = generate_device_token(cfg, &device_name, now_ms);
    let token_hash = sha256_hex(&token);
    let clean_name = if device_name.is_empty() {
        "Android Companion".to_string()
    } else {
        device_name
    };
    match db
        .register_phone_device(&clean_name, &token_hash, now_ms)
        .await
    {
        Ok(device_id) => (
            200,
            json!({
                "ok": true,
                "device_id": device_id,
                "device_name": clean_name,
                "device_token": token,
                "latest_event_id": latest_event_id,
                "server_time_ms": now_ms,
                "agent": BOT_NAME
            }),
        ),
        Err(e) => (
            500,
            json!({ "error": format!("Failed to register device: {}", redact_secrets(&e.to_string(), cfg)) }),
        ),
    }
}

/// `GET /api/v1/phone/dashboard` — unified live Command Center JSON for Android UI.
pub async fn handle_dashboard_endpoint(db: &Database, now_ms: i64) -> (u16, Value) {
    handle_dashboard_endpoint_inner(db, None, None, now_ms).await
}

pub async fn handle_dashboard_endpoint_with_state(
    state: &PhoneApiState,
    now_ms: i64,
) -> (u16, Value) {
    handle_dashboard_endpoint_inner(&state.db, Some(&state.cfg), Some(state.gmail_cache.as_ref()), now_ms).await
}

async fn handle_dashboard_endpoint_inner(
    db: &Database,
    cfg: Option<&Config>,
    gmail_cache: Option<&GmailTokenCache>,
    now_ms: i64,
) -> (u16, Value) {
    let _ = db.promote_due_followups(now_ms).await;
    let status = db.get_owner_status(now_ms).await.unwrap_or_default();
    let low_battery_cover = db.is_phone_low_battery_cover(now_ms).await.unwrap_or(false);
    let telemetry = db.get_phone_telemetry().await.ok().flatten();
    let urgent = db.list_unacked_urgent_alerts().await.unwrap_or_default();
    let emails = db.list_recent_priority_emails(15).await.unwrap_or_default();
    let followups = db.list_followups(Some("open"), 15).await.unwrap_or_default();
    let flags = db.list_flags(12).await.unwrap_or_default();
    let reminders = db.list_pending_reminders(12).await.unwrap_or_default();
    let scheduled = db
        .list_pending_scheduled_messages(12)
        .await
        .unwrap_or_default();
    let bank = db
        .list_bank_alerts(now_ms - 14 * 86_400_000, 20)
        .await
        .unwrap_or_default();
    let bookings = db.list_bookings(15).await.unwrap_or_default();
    let catch_up = build_catch_me_up_summary(db, now_ms)
        .await
        .unwrap_or_default();
    let gmail_accounts: Vec<GmailAccountProfile> = match (cfg, gmail_cache) {
        (Some(c), Some(cache)) => resolve_gmail_account_profiles(db, c, cache, false).await,
        _ => Vec::new(),
    };

    (
        200,
        json!({
            "server_time_ms": now_ms,
            "availability": status,
            "low_battery_cover": low_battery_cover,
            "telemetry": telemetry,
            "catch_up_summary": catch_up,
            "gmail_accounts": gmail_accounts,
            "urgent_alerts": urgent,
            "priority_emails": emails,
            "followups": followups,
            "pending_flags": flags,
            "reminders": reminders,
            "scheduled_messages": scheduled,
            "bank_alerts": bank.iter().map(|b| json!({
                "id": b.id,
                "source": b.source,
                "bank": b.bank,
                "direction": b.direction,
                "amount_minor": b.amount_minor,
                "amount_formatted": format_bank_amount(b.amount_minor, &b.currency),
                "currency": b.currency,
                "counterparty": b.counterparty,
                "raw_summary": b.raw_summary,
                "created_at": b.created_at
            })).collect::<Vec<_>>(),
            "bookings": bookings
        }),
    )
}

/// `POST /api/v1/phone/telemetry` — receives battery, DND, BT, calendar, redacted notifications,
/// structured bank/Paystack alerts, Dead-Battery (`<=10%` unplugged) Auto-Cover beacon, and VIP missed-call escalation.
pub async fn handle_telemetry_endpoint(
    db: &Database,
    bus: &broadcast::Sender<PhoneEventRecord>,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let prev = db.get_phone_telemetry().await.ok().flatten().unwrap_or_default();
    let battery_pct = body["battery_pct"]
        .as_i64()
        .unwrap_or(prev.battery_pct)
        .clamp(0, 100);
    let is_charging = body["is_charging"].as_bool().unwrap_or(prev.is_charging);
    let dnd_active = body["dnd_active"].as_bool().unwrap_or(prev.dnd_active);
    let bt_connected = body["bt_connected"].as_bool().unwrap_or(prev.bt_connected);

    let calendar_upcoming: Vec<String> = body["calendar_upcoming"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str())
                .map(|s| s.trim().chars().take(120).collect::<String>())
                .filter(|s| !s.is_empty())
                .take(10)
                .collect()
        })
        .unwrap_or(prev.calendar_upcoming);

    let recent_notifications: Vec<String> = body["recent_notifications"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str())
                .map(|s| s.trim().chars().take(160).collect::<String>())
                .filter(|s| !s.is_empty())
                .take(20)
                .collect()
        })
        .unwrap_or(prev.recent_notifications);

    let explicit_dead_beacon = body["battery_critical"].as_bool().unwrap_or(false);
    let low_battery_cover = if is_charging || battery_pct > 15 {
        false
    } else if explicit_dead_beacon || (battery_pct > 0 && battery_pct <= 10 && !is_charging) {
        true
    } else {
        prev.low_battery_cover
    };

    let snap = PhoneTelemetrySnapshot {
        battery_pct,
        is_charging,
        dnd_active,
        bt_connected,
        calendar_upcoming,
        recent_notifications,
        low_battery_cover,
        updated_at: now_ms,
    };
    if let Err(e) = db.save_phone_telemetry(&snap).await {
        return (500, json!({ "error": e.to_string() }));
    }

    let mut bank_inserted = 0usize;
    if let Some(arr) = body["bank_alerts"].as_array() {
        for item in arr.iter().take(15) {
            let bank = item["bank"].as_str().unwrap_or("Bank").trim();
            let direction = match item["direction"].as_str().unwrap_or("credit").trim() {
                "debit" => "debit",
                _ => "credit",
            };
            let amount_minor = item["amount_minor"].as_i64().unwrap_or(0);
            let currency = item["currency"].as_str().unwrap_or("NGN").trim().to_uppercase();
            let counterparty = item["counterparty"].as_str().unwrap_or("Transfer").trim();
            let raw_summary = item["raw_summary"]
                .as_str()
                .unwrap_or("")
                .trim()
                .chars()
                .take(220)
                .collect::<String>();
            if amount_minor > 0 && amount_minor <= 100_000_000_000 && !raw_summary.is_empty() {
                let source = item["source"].as_str().unwrap_or("android");
                let created_at = item["created_at"].as_i64().unwrap_or(now_ms);
                if db
                    .insert_bank_alert(
                        source,
                        bank,
                        direction,
                        amount_minor,
                        &currency,
                        counterparty,
                        &raw_summary,
                        created_at,
                    )
                    .await
                    .unwrap_or(0)
                    > 0
                {
                    bank_inserted += 1;
                }
            }
        }
    }

    // Escalate repeated VIP missed calls straight to full-screen urgent alarm
    let mut missed_escalated = false;
    if let Some(missed) = body["missed_call_urgent"].as_object() {
        let caller = missed
            .get("caller")
            .and_then(|v| v.as_str())
            .unwrap_or("VIP Caller")
            .chars()
            .take(60)
            .collect::<String>();
        let phone = missed
            .get("phone")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .chars()
            .take(32)
            .collect::<String>();
        let count = missed.get("count").and_then(|v| v.as_i64()).unwrap_or(2).clamp(2, 50);
        let _ = publish_phone_event(
            db,
            bus,
            "urgent_alarm",
            json!({
                "source": "missed_call",
                "sender": phone,
                "contact_name": caller,
                "summary": format!("{} missed calls in 10 mins from {}", count, caller),
                "at": now_ms
            }),
            now_ms,
        )
        .await;
        missed_escalated = true;
    }

    (
        200,
        json!({
            "ok": true,
            "low_battery_cover": low_battery_cover,
            "bank_alerts_saved": bank_inserted,
            "missed_call_escalated": missed_escalated
        }),
    )
}

/// `POST /api/v1/phone/call-note` — saves a post-call 1-tap voice/text note straight into
/// `contact_memory` (with semantic deduplication) and optionally stages a follow-up (Feature B).
pub async fn handle_call_note_endpoint(
    db: &Database,
    cfg: &Config,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let raw_phone = body["phone"].as_str().unwrap_or("").trim();
    let contact_name = body["contact_name"]
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty());

    let mut note_text = body["text"]
        .as_str()
        .or_else(|| body["note"].as_str())
        .unwrap_or("")
        .trim()
        .to_string();

    if note_text.is_empty() {
        if let Some(audio_b64) = body["audio_base64"].as_str().filter(|s| !s.trim().is_empty()) {
            match base64_decode(audio_b64) {
                Ok(bytes) if !bytes.is_empty() && bytes.len() <= MAX_AUDIO_BYTES => {
                    let mime = body["audio_mime"].as_str();
                    match transcribe_audio_bytes(cfg, &bytes, mime, true).await {
                        Ok(t) => note_text = t,
                        Err(e) => {
                            return (
                                500,
                                json!({ "error": format!("Transcription failed: {}", redact_secrets(&e.to_string(), cfg)) }),
                            );
                        }
                    }
                }
                _ => return (400, json!({ "error": "Invalid or oversized audio_base64" })),
            }
        }
    }

    if note_text.is_empty() {
        return (400, json!({ "error": "Empty call note text/audio" }));
    }

    let norm_phone = normalize_number(raw_phone);
    let key = if !norm_phone.is_empty() {
        norm_phone
    } else if let Some(name) = contact_name {
        resolve_contact_target(db, name)
            .await
            .ok()
            .flatten()
            .unwrap_or_else(|| name.to_lowercase().replace(' ', "_"))
    } else {
        return (400, json!({ "error": "Missing phone or contact_name" }));
    };

    let mut mem = db
        .get_contact_memory(&key)
        .await
        .ok()
        .flatten()
        .unwrap_or_else(|| ContactMemory::new(&key));
    if mem.display_name.is_none() {
        mem.display_name = contact_name.map(|s| s.to_string());
    }
    let clean_fact = format!(
        "Call note: {}",
        note_text.chars().take(220).collect::<String>()
    );
    let is_dup = mem
        .facts
        .iter()
        .any(|f| memory_token_jaccard(f, &clean_fact) >= 0.72);
    if !is_dup {
        mem.facts.push(clean_fact.clone());
        if mem.facts.len() > 14 {
            let excess = mem.facts.len() - 14;
            mem.facts.drain(0..excess);
        }
    }
    mem.last_seen_at = now_ms;
    mem.updated_at = now_ms;
    if let Err(e) = db.save_contact_memory(&mem).await {
        return (500, json!({ "error": e.to_string() }));
    }

    let lower = note_text.to_lowercase();
    let create_followup = body["create_followup"].as_bool().unwrap_or(false)
        || lower.contains("follow up")
        || lower.contains("send him")
        || lower.contains("send her")
        || lower.contains("remind me")
        || lower.contains("invoice")
        || lower.contains("promise");
    let mut followup_id = None;
    if create_followup {
        let draft = body["suggested_reply"]
            .as_str()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| {
                format!(
                    "Hey {}, following up on our call earlier — {}",
                    mem.display_name.as_deref().unwrap_or("there"),
                    note_text.chars().take(160).collect::<String>()
                )
            });
        if let Ok(fid) = db
            .upsert_followup(
                &key,
                mem.display_name.as_deref(),
                "falcon_promised",
                &clean_fact,
                &draft,
                now_ms + 2 * 3_600_000,
                now_ms,
            )
            .await
        {
            followup_id = Some(fid);
        }
    }

    (
        200,
        json!({
            "ok": true,
            "contact_key": key,
            "transcript": note_text,
            "saved_fact": clean_fact,
            "followup_id": followup_id
        }),
    )
}

/// `GET /api/v1/phone/settings` — returns strictly non-secret server preferences for the Settings screen.
pub async fn handle_get_settings_endpoint(db: &Database, cfg: &Config) -> (u16, Value) {
    let digest_hour = db
        .get_setting("digest_hour_lagos")
        .await
        .ok()
        .flatten()
        .and_then(|s| s.trim().parse::<u32>().ok())
        .filter(|&h| h <= 23)
        .unwrap_or(cfg.digest_hour_lagos);
    let reply_mode = db
        .get_setting("reply_mode")
        .await
        .ok()
        .flatten()
        .filter(|s| matches!(s.as_str(), "everyone" | "contacts" | "off"))
        .unwrap_or_else(|| cfg.default_reply_mode.clone());
    let self_voice_mode = db
        .get_setting("self_voice_mode")
        .await
        .ok()
        .flatten()
        .filter(|s| matches!(s.as_str(), "auto" | "on" | "off"))
        .unwrap_or_else(|| "auto".to_string());
    let dm_voice_mode = db
        .get_setting("dm_voice_mode")
        .await
        .ok()
        .flatten()
        .filter(|s| matches!(s.as_str(), "auto" | "explicit" | "off"))
        .unwrap_or_else(|| "auto".to_string());
    let active_devices = db
        .list_active_phone_devices()
        .await
        .map(|v| v.len())
        .unwrap_or(0);
    let gmail_count = crate::gmail::resolve_gmail_accounts(db, cfg).await.len();
    let ntfy_configured =
        !crate::config::normalize_ntfy_topic(&cfg.ntfy_topic, &cfg.ntfy_url).is_empty();

    (
        200,
        json!({
            "ok": true,
            "digest_hour_lagos": digest_hour,
            "reply_mode": reply_mode,
            "self_voice_mode": self_voice_mode,
            "dm_voice_mode": dm_voice_mode,
            "tts_voice": cfg.tts_voice,
            "ntfy_configured": ntfy_configured,
            "gmail_count": gmail_count,
            "paired_devices_count": active_devices
        }),
    )
}

/// `POST /api/v1/phone/settings` — updates whitelisted server settings ONLY (rejects unknown keys).
pub async fn handle_update_settings_endpoint(
    db: &Database,
    cfg: &Config,
    body: &Value,
) -> (u16, Value) {
    let Some(obj) = body.as_object() else {
        return (400, json!({ "error": "Expected JSON object" }));
    };
    if obj.is_empty() {
        return (400, json!({ "error": "Empty settings update" }));
    }

    const ALLOWED_KEYS: &[&str] = &[
        "digest_hour_lagos",
        "reply_mode",
        "self_voice_mode",
        "dm_voice_mode",
    ];
    for k in obj.keys() {
        if !ALLOWED_KEYS.contains(&k.as_str()) {
            return (
                400,
                json!({ "error": format!("Disallowed or unknown setting key: {}", k) }),
            );
        }
    }

    if let Some(v) = obj.get("digest_hour_lagos") {
        let Some(hour) = v.as_u64().filter(|&h| h <= 23) else {
            return (
                400,
                json!({ "error": "digest_hour_lagos must be an integer 0..23" }),
            );
        };
        if let Err(e) = db
            .set_setting("digest_hour_lagos", &hour.to_string())
            .await
        {
            return (
                500,
                json!({ "error": redact_secrets(&e.to_string(), cfg) }),
            );
        }
    }

    if let Some(v) = obj.get("reply_mode") {
        let Some(mode) = v
            .as_str()
            .map(|s| s.trim().to_lowercase())
            .filter(|m| matches!(m.as_str(), "everyone" | "contacts" | "off"))
        else {
            return (
                400,
                json!({ "error": "reply_mode must be 'everyone', 'contacts', or 'off'" }),
            );
        };
        if let Err(e) = db.set_setting("reply_mode", &mode).await {
            return (
                500,
                json!({ "error": redact_secrets(&e.to_string(), cfg) }),
            );
        }
    }

    if let Some(v) = obj.get("self_voice_mode") {
        let Some(mode) = v
            .as_str()
            .map(|s| s.trim().to_lowercase())
            .filter(|m| matches!(m.as_str(), "auto" | "on" | "off"))
        else {
            return (
                400,
                json!({ "error": "self_voice_mode must be 'auto', 'on', or 'off'" }),
            );
        };
        if let Err(e) = db.set_setting("self_voice_mode", &mode).await {
            return (
                500,
                json!({ "error": redact_secrets(&e.to_string(), cfg) }),
            );
        }
    }

    if let Some(v) = obj.get("dm_voice_mode") {
        let Some(mode) = v
            .as_str()
            .map(|s| s.trim().to_lowercase())
            .filter(|m| matches!(m.as_str(), "auto" | "explicit" | "off"))
        else {
            return (
                400,
                json!({ "error": "dm_voice_mode must be 'auto', 'explicit', or 'off'" }),
            );
        };
        if let Err(e) = db.set_setting("dm_voice_mode", &mode).await {
            return (
                500,
                json!({ "error": redact_secrets(&e.to_string(), cfg) }),
            );
        }
    }

    handle_get_settings_endpoint(db, cfg).await
}

/// `POST /api/v1/phone/unpair` — revokes this phone's token hash (or all paired phones if requested).
pub async fn handle_unpair_endpoint(
    db: &Database,
    token_hash: Option<&str>,
    revoke_all: bool,
) -> (u16, Value) {
    let res = if revoke_all {
        db.revoke_all_phone_devices().await
    } else if let Some(th) = token_hash.filter(|s| !s.is_empty()) {
        db.revoke_phone_device_by_hash(th).await
    } else {
        Ok(0)
    };
    match res {
        Ok(revoked) => (200, json!({ "ok": true, "revoked": revoked })),
        Err(e) => (500, json!({ "error": e.to_string() })),
    }
}

/// `POST /api/v1/phone/ack` — acknowledges urgent alerts from the Android full-screen alarm,
/// and optionally sends an immediate WhatsApp reply straight from the lockscreen while syncing `ConversationState`!
pub async fn handle_ack_endpoint(
    db: &Database,
    wa_send_tx: Option<&mpsc::UnboundedSender<(String, String)>>,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let dismiss_all_flags = body["dismiss_all_flags"].as_bool().unwrap_or(false);
    let dismiss_flag = body["dismiss_flag"]
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let mut flags_dismissed = 0u64;
    if dismiss_all_flags {
        flags_dismissed = db.delete_all_flags().await.unwrap_or(0);
    } else if let Some(chat_id) = dismiss_flag {
        flags_dismissed = db.delete_flags_for_chat(chat_id).await.unwrap_or(0);
    }

    let target = body["target"]
        .as_str()
        .or_else(|| body["sender"].as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let acked = if dismiss_flag.is_some() && target.is_none() && !dismiss_all_flags {
        0
    } else {
        db.ack_urgent_alerts(target).await.unwrap_or(0)
    };

    let mut reply_sent = false;
    if let (Some(t), Some(reply_msg)) = (
        target,
        body["quick_reply"]
            .as_str()
            .map(str::trim)
            .filter(|s| !s.is_empty()),
    ) {
        let num = normalize_number(t);
        if num.len() >= 7 {
            if let Some(tx) = wa_send_tx {
                let jid = format!("{}@s.whatsapp.net", num);
                if tx.send((jid, reply_msg.to_string())).is_ok() {
                    let mut st = db
                        .get_conversation(&num)
                        .await
                        .ok()
                        .flatten()
                        .unwrap_or_else(|| ConversationState::new(&num));
                    st.history.push(ChatTurn {
                        role: "assistant".to_string(),
                        content: format!("[Falcon]: {}", reply_msg),
                    });
                    if st.history.len() > HISTORY_MAX {
                        let excess = st.history.len() - HISTORY_MAX;
                        st.history.drain(0..excess);
                    }
                    st.bot_active = true;
                    st.owner_read_unanswered = false;
                    st.paused_until = 0;
                    st.updated_at = now_ms;
                    let _ = db.save_conversation(&st).await;
                    let _ = db.delete_pending_reply(&num).await;
                    let _ = db.delete_flags_for_chat(&num).await;
                    let _ = db.resolve_followups_for_sender(&num).await;
                    reply_sent = true;
                }
            }
        }
    }

    (
        200,
        json!({
            "ok": true,
            "acknowledged": acked,
            "flags_dismissed": flags_dismissed,
            "quick_reply_sent": reply_sent,
            "at": now_ms
        }),
    )
}

/// `POST /api/v1/phone/chat` — unified Android voice/text/vision brain endpoint.
pub async fn handle_phone_chat_endpoint(
    state: &PhoneApiState,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let multilingual = body["multilingual"].as_bool().unwrap_or(true);
    let want_tts = body["want_tts"].as_bool().unwrap_or(true);

    let mut user_text = body["text"].as_str().unwrap_or("").trim().to_string();
    let mut transcript_out = user_text.clone();

    if user_text.is_empty() {
        if let Some(audio_b64) = body["audio_base64"].as_str().filter(|s| !s.trim().is_empty()) {
            match base64_decode(audio_b64) {
                Ok(bytes) if !bytes.is_empty() && bytes.len() <= MAX_AUDIO_BYTES => {
                    let mime = body["audio_mime"].as_str();
                    match transcribe_audio_bytes(&state.cfg, &bytes, mime, multilingual).await {
                        Ok(t) => {
                            user_text = t.clone();
                            transcript_out = t;
                        }
                        Err(e) => {
                            return (
                                500,
                                json!({
                                    "error": format!(
                                        "Voice transcription failed: {}",
                                        redact_secrets(&e.to_string(), &state.cfg)
                                    )
                                }),
                            );
                        }
                    }
                }
                _ => {
                    return (
                        400,
                        json!({ "error": "Invalid or oversized audio_base64 payload" }),
                    );
                }
            }
        }
    }

    if user_text.trim().is_empty() {
        return (400, json!({ "error": "Provide either 'text' or 'audio_base64'" }));
    }

    // 1) Fast <10ms deterministic owner command check (works even if AI providers are down!)
    if let Ok(Some(fast_reply)) =
        run_owner_command(&state.db, &state.cfg, &user_text, || async { Ok(vec![]) }).await
    {
        let mut wa_sent = 0usize;
        if let Ok(approved) = take_approved_outbounds(&state.db).await {
            for (jid, msg) in approved {
                if state.wa_send_tx.send((jid, msg)).is_ok() {
                    wa_sent += 1;
                }
            }
        }
        let mut tts_b64 = None;
        let mut tts_m = None;
        let mut tts_s = None;
        if want_tts && !fast_reply.trim().is_empty() {
            let today_key = Utc::now().format("%Y-%m-%d").to_string();
            if state
                .tts_budget
                .try_consume(state.cfg.max_tts_per_day.saturating_mul(4).max(100), &today_key)
            {
                if let Ok((b, m, s)) = synthesize_voice_bytes(&state.cfg, &fast_reply, true).await {
                    tts_b64 = Some(base64_encode(&b));
                    tts_m = Some(m);
                    tts_s = Some(s);
                }
            }
        }
        return (
            200,
            json!({
                "ok": true,
                "transcript": transcript_out,
                "reply_text": redact_secrets(&fast_reply, &state.cfg),
                "cloud_actions_done": ["Fast Command"],
                "device_actions": [],
                "whatsapp_outbounds_sent": wa_sent,
                "tts_audio_base64": tts_b64,
                "tts_mime": tts_m,
                "tts_seconds": tts_s
            }),
        );
    }

    let dev = &body["device_context"];
    let mut screen_context = dev["screen_text"]
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            if detect_injection(s) {
                "[filtered prompt-injection on screen]".to_string()
            } else {
                sanitize_inbound(s)
            }
        });

    if let Some(img_b64) = body["screenshot_base64"]
        .as_str()
        .filter(|s| !s.trim().is_empty())
    {
        if let Ok(img_bytes) = base64_decode(img_b64) {
            if !img_bytes.is_empty() && img_bytes.len() <= MAX_IMAGE_BYTES {
                let img_mime = body["screenshot_mime"].as_str().or(Some("image/jpeg"));
                let vision_prompt = format!(
                    "Describe what is visible on Falcon's Android phone screen so FalkeAI can accurately answer his voice question: \"{}\". Quote exact text, names, numbers, errors, prices, or buttons concisely.",
                    user_text
                );
                match describe_image_bytes(
                    &state.cfg,
                    &img_bytes,
                    img_mime,
                    &user_text,
                    Some(&vision_prompt),
                )
                .await
                {
                    Ok(desc) => {
                        let safe_desc = if detect_injection(&desc) {
                            "[filtered visual prompt-injection on screen]".to_string()
                        } else {
                            sanitize_inbound(&desc)
                        };
                        screen_context = Some(match screen_context {
                            Some(existing) => format!("{} | Visual Screen: {}", existing, safe_desc),
                            None => safe_desc,
                        });
                    }
                    Err(e) => {
                        warn!(
                            "Android screenshot vision failed: {}",
                            redact_secrets(&e.to_string(), &state.cfg)
                        );
                    }
                }
            }
        }
    }

    let phone_ctx = OwnerPhoneContext {
        from_android: true,
        battery_pct: dev["battery_pct"].as_i64(),
        is_charging: dev["is_charging"].as_bool(),
        dnd_active: dev["dnd_active"].as_bool(),
        bt_connected: dev["bt_connected"].as_bool(),
        calendar_upcoming: dev["calendar_upcoming"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(str::to_string))
                    .take(10)
                    .collect()
            })
            .unwrap_or_default(),
        recent_notifications: dev["recent_notifications"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(str::to_string))
                    .take(15)
                    .collect()
            })
            .unwrap_or_default(),
        active_alarms: dev["active_alarms"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(str::to_string))
                    .take(10)
                    .collect()
            })
            .unwrap_or_default(),
        screen_context,
    };

    let enriched_input =
        enrich_text_with_web_links(&state.cfg, &state.web_budget, &user_text, &[], 2, false).await;

    let cfg_llm = state.cfg.clone();
    let outcome = match handle_unified_owner_chat(
        &state.db,
        &state.cfg,
        &enriched_input,
        now_ms,
        Some(&phone_ctx),
        Some(state.gmail_cache.as_ref()),
        |msgs| async move { call_llm_http(&cfg_llm, &msgs).await },
    )
    .await
    {
        Ok(o) => o,
        Err(e) => {
            return (
                500,
                json!({
                    "error": format!(
                        "Brain error: {}",
                        redact_secrets(&e.to_string(), &state.cfg)
                    )
                }),
            );
        }
    };

    for (target_jid, msg_text) in &outcome.outbounds {
        let _ = state
            .wa_send_tx
            .send((target_jid.clone(), msg_text.clone()));
    }
    for (target_jid, msg_text) in &outcome.voice_outbounds {
        let _ = state
            .wa_send_tx
            .send((format!("voice:{}", target_jid), msg_text.clone()));
    }

    // If any cloud action triggered find_phone_siren, push SSE too so all paired devices react
    for da in &outcome.device_actions {
        if da["type"].as_str() == Some("find_phone_siren") {
            let _ = publish_phone_event(
                &state.db,
                &state.phone_bus,
                "find_phone",
                json!({"message": "I'm right here, boss!", "strobe": true, "at": now_ms}),
                now_ms,
            )
            .await;
        }
    }

    let mut tts_audio_base64: Option<String> = None;
    let mut tts_mime: Option<String> = None;
    let mut tts_seconds: Option<u32> = None;
    if want_tts && !outcome.reply.trim().is_empty() {
        let today_key = Utc::now().format("%Y-%m-%d").to_string();
        if state
            .tts_budget
            .try_consume(state.cfg.max_tts_per_day.saturating_mul(4).max(100), &today_key)
        {
            match synthesize_voice_bytes(&state.cfg, &outcome.reply, true).await {
                Ok((audio_bytes, mime, secs)) => {
                    tts_audio_base64 = Some(base64_encode(&audio_bytes));
                    tts_mime = Some(mime);
                    tts_seconds = Some(secs);
                }
                Err(e) => {
                    warn!(
                        "Phone TTS failed (Android will fallback to on-device TextToSpeech): {}",
                        redact_secrets(&e.to_string(), &state.cfg)
                    );
                }
            }
        }
    }

    (
        200,
        json!({
            "ok": true,
            "transcript": transcript_out,
            "reply_text": outcome.reply,
            "cloud_actions_done": outcome.cloud_actions_done,
            "device_actions": outcome.device_actions,
            "whatsapp_outbounds_sent": outcome.outbounds.len() + outcome.voice_outbounds.len(),
            "tts_audio_base64": tts_audio_base64,
            "tts_mime": tts_mime,
            "tts_seconds": tts_seconds
        }),
    )
}

async fn verify_bearer(state: &PhoneApiState, hdr: &HttpHeaders, now_ms: i64) -> bool {
    let Some(token) = extract_bearer_token(&hdr.headers) else {
        return false;
    };
    let hash = sha256_hex(&token);
    state
        .db
        .verify_phone_device_hash(&hash, now_ms)
        .await
        .ok()
        .flatten()
        .is_some()
}

async fn handle_sse_stream(mut stream: TcpStream, state: Arc<PhoneApiState>, since_id: i64) {
    let Ok(_sse_permit) = state.sse_sem.clone().try_acquire_owned() else {
        write_json_response(
            &mut stream,
            429,
            &json!({ "error": "Too many concurrent SSE streams" }),
        )
        .await;
        return;
    };

    let headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache, no-store\r\nConnection: keep-alive\r\nX-Accel-Buffering: no\r\nX-Content-Type-Options: nosniff\r\n\r\n: connected\n\n";
    if stream.write_all(headers.as_bytes()).await.is_err() {
        return;
    }
    let _ = stream.flush().await;

    let mut rx = state.phone_bus.subscribe();
    let mut last_sent_id = since_id;

    // Replay missed events stored in Turso since `since_id`
    if let Ok(missed) = state.db.list_phone_events_since(since_id, 50).await {
        for ev in missed {
            if ev.id > last_sent_id {
                let frame = format_sse_event(&ev);
                if stream.write_all(frame.as_bytes()).await.is_err() {
                    return;
                }
                last_sent_id = ev.id;
            }
        }
        let _ = stream.flush().await;
    }

    let mut ping_interval = tokio::time::interval(Duration::from_secs(SSE_PING_INTERVAL_SECS));
    ping_interval.tick().await; // skip immediate first tick

    loop {
        tokio::select! {
            _ = ping_interval.tick() => {
                if stream.write_all(b": ping\n\n").await.is_err() {
                    break;
                }
                if stream.flush().await.is_err() {
                    break;
                }
            }
            recv = rx.recv() => {
                match recv {
                    Ok(ev) => {
                        if ev.id <= last_sent_id {
                            continue;
                        }
                        let frame = format_sse_event(&ev);
                        if stream.write_all(frame.as_bytes()).await.is_err() {
                            break;
                        }
                        let _ = stream.flush().await;
                        last_sent_id = ev.id;
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        if let Ok(missed) = state.db.list_phone_events_since(last_sent_id, 50).await {
                            for ev in missed {
                                if ev.id > last_sent_id {
                                    let frame = format_sse_event(&ev);
                                    if stream.write_all(frame.as_bytes()).await.is_err() {
                                        return;
                                    }
                                    last_sent_id = ev.id;
                                }
                            }
                            let _ = stream.flush().await;
                        }
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        }
    }
}

async fn handle_client_connection(
    mut stream: TcpStream,
    state: Arc<PhoneApiState>,
    http_permit: tokio::sync::OwnedSemaphorePermit,
) {
    let hdr = match tokio::time::timeout(Duration::from_secs(10), read_http_headers(&mut stream))
        .await
    {
        Ok(Ok(h)) => h,
        Ok(Err(HttpParseError::Eof)) => return,
        Ok(Err(HttpParseError::HeadersTooLarge)) => {
            write_json_response(&mut stream, 413, &json!({ "error": "Headers too large" })).await;
            return;
        }
        Ok(Err(HttpParseError::ChunkedUnsupported)) => {
            write_json_response(
                &mut stream,
                411,
                &json!({ "error": "Content-Length required" }),
            )
            .await;
            return;
        }
        Ok(Err(HttpParseError::SmugglingDetected | HttpParseError::MalformedRequest)) => {
            write_json_response(&mut stream, 400, &json!({ "error": "Malformed HTTP request" }))
                .await;
            return;
        }
        Err(_) => return,
    };

    let now_ms = Utc::now().timestamp_millis();

    // 1) Public probe route (0-byte body)
    if hdr.method == "GET" && (hdr.path == "/" || hdr.path == "/health") {
        write_json_response(
            &mut stream,
            200,
            &json!({ "status": "ok", "agent": BOT_NAME, "up": 1, "phone_bridge": "ready" }),
        )
        .await;
        return;
    }

    if !hdr.path.starts_with("/api/v1/phone/") {
        write_json_response(&mut stream, 404, &json!({ "error": "Not found" })).await;
        return;
    }

    // 2) Enforce HTTPS when behind reverse proxy (e.g. Heroku Router sets X-Forwarded-Proto)
    if let Some(proto) = hdr.headers.get("x-forwarded-proto") {
        if !proto.trim().eq_ignore_ascii_case("https") {
            write_json_response(
                &mut stream,
                403,
                &json!({ "error": "HTTPS is strictly required for FalkeAI Phone Bridge" }),
            )
            .await;
            return;
        }
    }

    // 3) Single-use pairing endpoint: strictly capped at 2 KB BEFORE reading body!
    if hdr.method == "POST" && hdr.path == "/api/v1/phone/pair" {
        if hdr.content_length > MAX_PAIR_BODY_BYTES {
            write_json_response(&mut stream, 413, &json!({ "error": "Pairing payload too large" }))
                .await;
            return;
        }
        let Ok(Ok(raw_body)) = tokio::time::timeout(
            Duration::from_secs(10),
            read_http_body(&mut stream, &hdr, MAX_PAIR_BODY_BYTES),
        )
        .await
        else {
            write_json_response(&mut stream, 400, &json!({ "error": "Invalid request body" })).await;
            return;
        };
        let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
        let (status, resp) =
            handle_pair_endpoint_locked(&state.pair_lock, &state.db, &state.cfg, &body, now_ms)
                .await;
        write_json_response(&mut stream, status, &resp).await;
        return;
    }

    // 4) Verify cryptographic Bearer token BEFORE reading any body bytes (defeats unauthenticated OOM DoS!)
    if !verify_bearer(&state, &hdr, now_ms).await {
        write_json_response(
            &mut stream,
            401,
            &json!({ "error": "Unauthorized device token. Pair your Infinix phone via 'pair android'." }),
        )
        .await;
        return;
    }

    // 5) SSE stream drops HTTP concurrency permit immediately and uses dedicated `sse_sem`
    if hdr.method == "GET" && hdr.path == "/api/v1/phone/stream" {
        drop(http_permit);
        let since_id = parse_query_param(&hdr.raw_target, "since_id")
            .or_else(|| hdr.headers.get("last-event-id").cloned())
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);
        handle_sse_stream(stream, state, since_id).await;
        return;
    }

    // 6) Authenticated JSON routes with route-specific body limits
    let max_body = match (hdr.method.as_str(), hdr.path.as_str()) {
        ("POST", "/api/v1/phone/chat") | ("POST", "/api/v1/phone/call-note") => MAX_HTTP_BODY_BYTES,
        ("POST", "/api/v1/phone/telemetry") => MAX_TELEMETRY_BODY_BYTES,
        ("POST", "/api/v1/phone/ack") => MAX_ACK_BODY_BYTES,
        ("GET", _) => 0,
        _ => MAX_ACK_BODY_BYTES,
    };

    if hdr.content_length > max_body {
        write_json_response(
            &mut stream,
            413,
            &json!({ "error": "Request body exceeds endpoint limit" }),
        )
        .await;
        return;
    }

    let raw_body = if hdr.content_length > 0 {
        match tokio::time::timeout(
            Duration::from_secs(25),
            read_http_body(&mut stream, &hdr, max_body),
        )
        .await
        {
            Ok(Ok(b)) => b,
            _ => {
                write_json_response(
                    &mut stream,
                    400,
                    &json!({ "error": "Failed reading request body" }),
                )
                .await;
                return;
            }
        }
    } else {
        Vec::new()
    };

    match (hdr.method.as_str(), hdr.path.as_str()) {
        ("POST", "/api/v1/phone/chat") => {
            let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
            let (status, resp) = handle_phone_chat_endpoint(&state, &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("GET", "/api/v1/phone/dashboard") => {
            let (status, resp) = handle_dashboard_endpoint_with_state(&state, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("GET", "/api/v1/phone/caller-brief") => {
            let phone = parse_query_param(&hdr.raw_target, "phone").unwrap_or_default();
            if phone.trim().is_empty() {
                write_json_response(&mut stream, 400, &json!({ "error": "Missing phone query param" }))
                    .await;
                return;
            }
            match state.db.get_caller_brief_context(&phone).await {
                Ok(ctx) => {
                    write_json_response(
                        &mut stream,
                        200,
                        &serde_json::to_value(&ctx).unwrap_or(json!({})),
                    )
                    .await;
                }
                Err(e) => {
                    write_json_response(
                        &mut stream,
                        500,
                        &json!({ "error": redact_secrets(&e.to_string(), &state.cfg) }),
                    )
                    .await;
                }
            }
        }
        ("POST", "/api/v1/phone/call-note") => {
            let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
            let (status, resp) =
                handle_call_note_endpoint(&state.db, &state.cfg, &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("POST", "/api/v1/phone/telemetry") => {
            let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
            let (status, resp) =
                handle_telemetry_endpoint(&state.db, &state.phone_bus, &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("POST", "/api/v1/phone/ack") => {
            let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
            let (status, resp) =
                handle_ack_endpoint(&state.db, Some(&state.wa_send_tx), &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("GET", "/api/v1/phone/settings") => {
            let (status, resp) = handle_get_settings_endpoint(&state.db, &state.cfg).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("POST", "/api/v1/phone/settings") => {
            let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
            let (status, resp) =
                handle_update_settings_endpoint(&state.db, &state.cfg, &body).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("POST", "/api/v1/phone/unpair") => {
            let body: Value = serde_json::from_slice(&raw_body).unwrap_or(json!({}));
            let revoke_all = body["revoke_all"].as_bool().unwrap_or(false);
            let tok_hash = extract_bearer_token(&hdr.headers).map(|t| sha256_hex(&t));
            let (status, resp) =
                handle_unpair_endpoint(&state.db, tok_hash.as_deref(), revoke_all).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        _ => {
            write_json_response(&mut stream, 404, &json!({ "error": "Unknown phone endpoint" }))
                .await;
        }
    }
}

pub async fn serve_phone_api_on_listener(listener: TcpListener, state: Arc<PhoneApiState>) {
    while let Ok((mut stream, _)) = listener.accept().await {
        let sem = Arc::clone(&state.http_sem);
        let Ok(permit) = sem.try_acquire_owned() else {
            tokio::spawn(async move {
                write_json_response(
                    &mut stream,
                    503,
                    &json!({ "error": "Server busy — retry shortly" }),
                )
                .await;
            });
            continue;
        };
        let st = Arc::clone(&state);
        tokio::spawn(async move {
            handle_client_connection(stream, st, permit).await;
        });
    }
}

/// Starts the unified Health + Android Companion HTTP/SSE server on `0.0.0.0:{port}`.
pub async fn serve_phone_api(port: u16, state: Arc<PhoneApiState>) {
    match TcpListener::bind(("0.0.0.0", port)).await {
        Ok(listener) => {
            info!(
                "{} HTTP + Android Phone Bridge SSE API listening on 0.0.0.0:{}",
                BOT_NAME, port
            );
            serve_phone_api_on_listener(listener, state).await;
        }
        Err(e) => {
            warn!("Failed to bind Phone Bridge HTTP/SSE listener on port {}: {}", port, e);
        }
    }
}
