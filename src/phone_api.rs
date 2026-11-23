//! Unified HTTP + SSE Phone Bridge API for the Native Kotlin FalkeAI Android Companion App.
//!
//! Runs directly inside `falkeai-core` on Heroku's `0.0.0.0:$PORT` with **zero extra Cargo crates**
//! (pure `tokio::net::TcpListener`, `tokio::io`, `sha2`, `hex`, `serde_json`).
//!
//! Security & Reliability:
//! - Single-use 5-minute pairing code (`FALKE-XXXXXX`) generated via WhatsApp self-chat `pair android`
//!   exchanges for a 256-bit Bearer `device_token` stored SHA-256 hashed in Turso (`phone_devices`).
//! - Constant-time byte comparison on pairing codes and Bearer token hashes.
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
use tokio::sync::{broadcast, mpsc};
use tracing::{info, warn};

use crate::alerts::{build_catch_me_up_summary, format_bank_amount};
use crate::brain::call_llm_http;
use crate::commands::resolve_contact_target;
use crate::config::{Config, BOT_NAME};
use crate::db::{
    memory_token_jaccard, ContactMemory, Database, PhoneEventRecord, PhoneTelemetrySnapshot,
};
use crate::gmail::GmailTokenCache;
use crate::media::{
    base64_decode, base64_encode, describe_image_bytes, synthesize_voice_bytes,
    transcribe_audio_bytes, MediaBudget, MAX_AUDIO_BYTES, MAX_IMAGE_BYTES,
};
use crate::owner_chat::{handle_unified_owner_chat, OwnerPhoneContext};
use crate::safety::redact_secrets;
use crate::util::normalize_number;
use crate::web_reader::enrich_text_with_web_links;
use crate::web_search::percent_decode;

pub const MAX_HTTP_HEADER_BYTES: usize = 32 * 1024;
pub const MAX_HTTP_BODY_BYTES: usize = 6 * 1024 * 1024;
pub const SSE_PING_INTERVAL_SECS: u64 = 20;

pub struct PhoneApiState {
    pub db: Database,
    pub cfg: Config,
    pub gmail_cache: Arc<GmailTokenCache>,
    pub phone_bus: broadcast::Sender<PhoneEventRecord>,
    pub web_budget: Arc<MediaBudget>,
    pub tts_budget: Arc<MediaBudget>,
    pub wa_send_tx: mpsc::UnboundedSender<(String, String)>,
}

#[derive(Debug, Clone)]
pub struct HttpRequest {
    pub method: String,
    pub path: String,
    pub raw_target: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
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
    let nanos = Utc::now()
        .timestamp_nanos_opt()
        .unwrap_or(now_ms.saturating_mul(1_000_000));
    let mut h1 = Sha256::new();
    h1.update(b"falkeai-device-token-v1:");
    h1.update(nanos.to_le_bytes());
    h1.update(now_ms.to_le_bytes());
    h1.update(device_name.as_bytes());
    h1.update(cfg.phone_bridge_secret.as_bytes());
    h1.update(cfg.pair_phone.as_bytes());
    let d1 = h1.finalize();

    let mut h2 = Sha256::new();
    h2.update(d1);
    h2.update(b":second-round:");
    h2.update((std::process::id() as u64).to_le_bytes());
    h2.update(cfg.turso_url.as_bytes());
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
        if !tok.is_empty() {
            return Some(tok.to_string());
        }
    }
    None
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

pub async fn read_http_request<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Option<HttpRequest>> {
    let mut buf: Vec<u8> = Vec::with_capacity(4096);
    let mut chunk = [0u8; 4096];
    let header_end = loop {
        if let Some(pos) = find_header_end(&buf) {
            break pos;
        }
        if buf.len() > MAX_HTTP_HEADER_BYTES {
            return Err(anyhow!("HTTP headers exceeded maximum limit"));
        }
        let n = reader.read(&mut chunk).await?;
        if n == 0 {
            if buf.is_empty() {
                return Ok(None);
            }
            return Err(anyhow!("Unexpected EOF while reading HTTP headers"));
        }
        buf.extend_from_slice(&chunk[..n]);
    };

    let header_str = String::from_utf8_lossy(&buf[..header_end]).into_owned();
    let mut lines = header_str.lines();
    let request_line = lines
        .next()
        .ok_or_else(|| anyhow!("Empty HTTP request line"))?;
    let mut parts = request_line.split_whitespace();
    let method = parts
        .next()
        .ok_or_else(|| anyhow!("Missing HTTP method"))?
        .to_uppercase();
    let raw_target = parts
        .next()
        .ok_or_else(|| anyhow!("Missing HTTP path"))?
        .to_string();
    let path = raw_target
        .split_once('?')
        .map(|(p, _)| p.to_string())
        .unwrap_or_else(|| raw_target.clone());

    let mut headers = HashMap::new();
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_lowercase(), v.trim().to_string());
        }
    }

    let content_len = headers
        .get("content-length")
        .and_then(|s| s.parse::<usize>().ok())
        .unwrap_or(0);
    if content_len > MAX_HTTP_BODY_BYTES {
        return Err(anyhow!("HTTP request body exceeds 6 MB limit"));
    }

    let mut body = Vec::with_capacity(content_len);
    let body_start = header_end + 4;
    if buf.len() > body_start {
        let take = (buf.len() - body_start).min(content_len);
        body.extend_from_slice(&buf[body_start..body_start + take]);
    }
    while body.len() < content_len {
        let remaining = content_len - body.len();
        let to_read = remaining.min(chunk.len());
        let n = reader.read(&mut chunk[..to_read]).await?;
        if n == 0 {
            return Err(anyhow!("Unexpected EOF while reading HTTP body"));
        }
        body.extend_from_slice(&chunk[..n]);
    }

    Ok(Some(HttpRequest {
        method,
        path,
        raw_target,
        headers,
        body,
    }))
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
        413 => "Payload Too Large",
        500 => "Internal Server Error",
        _ => "OK",
    }
}

async fn write_json_response(stream: &mut TcpStream, status: u16, payload: &Value) {
    let body = payload.to_string();
    let resp = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n{}",
        status,
        status_reason(status),
        body.len(),
        body
    );
    let _ = stream.write_all(resp.as_bytes()).await;
    let _ = stream.flush().await;
}

/// `POST /api/v1/phone/pair` — exchanges a single-use 5-minute `FALKE-XXXXXX` pairing code
/// for a persistent Bearer `device_token` stored SHA-256 hashed in Turso.
pub async fn handle_pair_endpoint(
    db: &Database,
    cfg: &Config,
    body: &Value,
    now_ms: i64,
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
        .take(80)
        .collect::<String>();

    if raw_code.is_empty() {
        return (400, json!({ "error": "Missing pairing code" }));
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
        return (
            401,
            json!({ "error": "Pairing code expired (5-min window). Run 'pair android' again." }),
        );
    }

    if !constant_time_eq(raw_code.as_bytes(), expected_code.as_bytes()) {
        return (401, json!({ "error": "Incorrect pairing code." }));
    }

    // Single-use: invalidate immediately so replay is impossible
    let _ = db.set_setting("android_pair_code_json", "{}").await;

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

    (
        200,
        json!({
            "server_time_ms": now_ms,
            "availability": status,
            "low_battery_cover": low_battery_cover,
            "telemetry": telemetry,
            "catch_up_summary": catch_up,
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
    let battery_pct = body["battery_pct"].as_i64().unwrap_or(prev.battery_pct);
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
            if amount_minor > 0 && !raw_summary.is_empty() {
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
            .unwrap_or("VIP Caller");
        let phone = missed.get("phone").and_then(|v| v.as_str()).unwrap_or("");
        let count = missed.get("count").and_then(|v| v.as_i64()).unwrap_or(2);
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

/// `POST /api/v1/phone/ack` — acknowledges urgent alerts from the Android full-screen alarm,
/// and optionally sends an immediate WhatsApp reply straight from the lockscreen!
pub async fn handle_ack_endpoint(
    db: &Database,
    wa_send_tx: Option<&mpsc::UnboundedSender<(String, String)>>,
    body: &Value,
    now_ms: i64,
) -> (u16, Value) {
    let target = body["target"]
        .as_str()
        .or_else(|| body["sender"].as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let acked = db.ack_urgent_alerts(target).await.unwrap_or(0);

    let mut reply_sent = false;
    if let (Some(t), Some(reply_msg)) = (
        target,
        body["quick_reply"]
            .as_str()
            .map(str::trim)
            .filter(|s| !s.is_empty()),
    ) {
        let num = normalize_number(t);
        if !num.is_empty() {
            if let Some(tx) = wa_send_tx {
                let jid = format!("{}@s.whatsapp.net", num);
                if tx.send((jid, reply_msg.to_string())).is_ok() {
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
            "quick_reply_sent": reply_sent,
            "at": now_ms
        }),
    )
}

/// `POST /api/v1/phone/chat` — unified Android voice/text/vision brain endpoint.
/// 1) Transcribes `audio_base64` via Groq Whisper (supports multilingual Yoruba/Pidgin/French)
/// 2) Describes optional silent Android 11 `screenshot_base64` via Cloudflare Mistral Vision
/// 3) Enriches shared links safely via `web_reader`
/// 4) Runs `handle_unified_owner_chat` (cloud actions + typed Android `device_actions`)
/// 5) Dispatches any outbound WhatsApp DMs via `wa_send_tx`
/// 6) Synthesizes crisp direct WAV audio via Groq Orpheus (or Cloudflare Aura-1 fallback) if `want_tts`
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

    let dev = &body["device_context"];
    let mut screen_context = dev["screen_text"]
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);

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
                        screen_context = Some(match screen_context {
                            Some(existing) => format!("{} | Visual Screen: {}", existing, desc),
                            None => desc,
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
            "whatsapp_outbounds_sent": outcome.outbounds.len(),
            "tts_audio_base64": tts_audio_base64,
            "tts_mime": tts_mime,
            "tts_seconds": tts_seconds
        }),
    )
}

async fn verify_bearer(state: &PhoneApiState, req: &HttpRequest, now_ms: i64) -> bool {
    let Some(token) = extract_bearer_token(&req.headers) else {
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
    let headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache, no-store\r\nConnection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n: connected\n\n";
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

async fn handle_client_connection(mut stream: TcpStream, state: Arc<PhoneApiState>) {
    let req = match tokio::time::timeout(Duration::from_secs(25), read_http_request(&mut stream))
        .await
    {
        Ok(Ok(Some(r))) => r,
        _ => return,
    };

    let now_ms = Utc::now().timestamp_millis();

    // Public routes: health check & single-use pairing code exchange
    if req.method == "GET" && (req.path == "/" || req.path == "/health") {
        write_json_response(
            &mut stream,
            200,
            &json!({ "status": "ok", "agent": BOT_NAME, "up": 1, "phone_bridge": "ready" }),
        )
        .await;
        return;
    }

    if req.method == "POST" && req.path == "/api/v1/phone/pair" {
        let body: Value = serde_json::from_slice(&req.body).unwrap_or(json!({}));
        let (status, resp) = handle_pair_endpoint(&state.db, &state.cfg, &body, now_ms).await;
        write_json_response(&mut stream, status, &resp).await;
        return;
    }

    // All remaining `/api/v1/phone/*` endpoints require a verified paired Android Bearer token
    if !req.path.starts_with("/api/v1/phone/") {
        write_json_response(&mut stream, 404, &json!({ "error": "Not found" })).await;
        return;
    }

    if !verify_bearer(&state, &req, now_ms).await {
        write_json_response(
            &mut stream,
            401,
            &json!({ "error": "Unauthorized device token. Pair your Infinix phone via 'pair android'." }),
        )
        .await;
        return;
    }

    match (req.method.as_str(), req.path.as_str()) {
        ("GET", "/api/v1/phone/stream") => {
            let since_id = parse_query_param(&req.raw_target, "since_id")
                .or_else(|| req.headers.get("last-event-id").cloned())
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0);
            handle_sse_stream(stream, state, since_id).await;
        }
        ("POST", "/api/v1/phone/chat") => {
            let body: Value = serde_json::from_slice(&req.body).unwrap_or(json!({}));
            let (status, resp) = handle_phone_chat_endpoint(&state, &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("GET", "/api/v1/phone/dashboard") => {
            let (status, resp) = handle_dashboard_endpoint(&state.db, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("GET", "/api/v1/phone/caller-brief") => {
            let phone = parse_query_param(&req.raw_target, "phone").unwrap_or_default();
            if phone.trim().is_empty() {
                write_json_response(&mut stream, 400, &json!({ "error": "Missing phone query param" }))
                    .await;
                return;
            }
            match state.db.get_caller_brief_context(&phone).await {
                Ok(ctx) => {
                    write_json_response(&mut stream, 200, &serde_json::to_value(&ctx).unwrap_or(json!({})))
                        .await;
                }
                Err(e) => {
                    write_json_response(&mut stream, 500, &json!({ "error": e.to_string() })).await;
                }
            }
        }
        ("POST", "/api/v1/phone/call-note") => {
            let body: Value = serde_json::from_slice(&req.body).unwrap_or(json!({}));
            let (status, resp) =
                handle_call_note_endpoint(&state.db, &state.cfg, &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("POST", "/api/v1/phone/telemetry") => {
            let body: Value = serde_json::from_slice(&req.body).unwrap_or(json!({}));
            let (status, resp) =
                handle_telemetry_endpoint(&state.db, &state.phone_bus, &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        ("POST", "/api/v1/phone/ack") => {
            let body: Value = serde_json::from_slice(&req.body).unwrap_or(json!({}));
            let (status, resp) =
                handle_ack_endpoint(&state.db, Some(&state.wa_send_tx), &body, now_ms).await;
            write_json_response(&mut stream, status, &resp).await;
        }
        _ => {
            write_json_response(&mut stream, 404, &json!({ "error": "Unknown phone endpoint" }))
                .await;
        }
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
            while let Ok((stream, _)) = listener.accept().await {
                let st = Arc::clone(&state);
                tokio::spawn(async move {
                    handle_client_connection(stream, st).await;
                });
            }
        }
        Err(e) => {
            warn!("Failed to bind Phone Bridge HTTP/SSE listener on port {}: {}", port, e);
        }
    }
}
