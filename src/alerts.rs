//! Urgent-alert `ntfy` engine (ring-through-DND repeating every 2 minutes until `ack`),
//! urgent intake timeout evaluator, 7:00 AM Africa/Lagos morning digest, and reminders ticker.

use anyhow::{anyhow, Result};
use chrono::{DateTime, Timelike, TimeZone, Utc};
use chrono_tz::Africa::Lagos;
use std::future::Future;
use std::time::Duration;

use crate::config::{Config, BOT_NAME};
use crate::db::{
    Database, DigestItem, ReminderRecord, UrgentAlertRecord, UrgentDraft,
};
use crate::safety::redact_secrets;

pub const URGENT_REPEAT_INTERVAL_MS: i64 = 2 * 60_000;
pub const URGENT_MAX_ATTEMPTS: i64 = 15;
pub const URGENT_CHAT_COOLDOWN_MS: i64 = 60 * 60_000;
pub const URGENT_INTAKE_TIMEOUT_MS: i64 = 150_000; // 2.5 minutes
pub const URGENT_INTAKE_MAX_TURNS: i64 = 2;

pub fn is_urgent_draft_complete(d: &UrgentDraft) -> bool {
    let has_name = d.name.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    let has_number = d.number.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    let has_req = d.request.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    let has_deadline = d.deadline.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    has_name && has_number && has_req && has_deadline
}

pub fn format_ntfy_payload(alert: &UrgentAlertRecord, attempt: i64) -> (String, String) {
    let who = match alert.contact_name.as_deref() {
        Some(n) if !n.trim().is_empty() => format!("{} (+{})", n.trim(), alert.phone),
        _ => format!("+{}", alert.phone),
    };
    let title = if attempt <= 1 {
        format!("URGENT — {} ({})", who, BOT_NAME)
    } else {
        format!("URGENT [Ping #{}] — {}", attempt, who)
    };
    let deadline_line = if alert.deadline.trim().is_empty() {
        "Not specified".to_string()
    } else {
        alert.deadline.trim().to_string()
    };
    let body = format!(
        "From: {}\nRequest: {}\nDeadline: {}\n\nReply 'ack' in WhatsApp Message yourself to stop alerts.",
        who,
        alert.request.trim(),
        deadline_line
    );
    (title, body)
}

/// Sends a top-priority (`Priority: max`) push notification to the configured secret `NTFY_TOPIC`.
pub async fn send_ntfy_http(cfg: &Config, title: &str, body: &str) -> Result<()> {
    let topic = cfg.ntfy_topic.trim().trim_matches('/');
    if topic.is_empty() {
        tracing::warn!("NTFY_TOPIC is not configured; skipping ntfy push (WhatsApp self-chat alert still sent).");
        return Ok(());
    }
    let url = format!("{}/{}", cfg.ntfy_url.trim_end_matches('/'), topic);
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(6))
        .timeout(Duration::from_secs(12))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());

    let safe_title = redact_secrets(title, cfg);
    let safe_body = redact_secrets(body, cfg);
    let mut req = client
        .post(&url)
        .header("Title", safe_title)
        .header("Priority", "max")
        .header("Tags", "rotating_light,phone")
        .body(safe_body);
    if let Some(tok) = cfg.ntfy_token.as_deref().filter(|s| !s.trim().is_empty()) {
        req = req.bearer_auth(tok.trim());
    }

    let resp = req.send().await?;
    if !resp.status().is_success() {
        let st = resp.status();
        let err_txt = resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "ntfy HTTP {}: {}",
            st,
            redact_secrets(&err_txt, cfg)
        ));
    }
    Ok(())
}

/// Fires an urgent alert sequence if this chat has NOT already triggered one in the last hour.
/// Persists the alert in Turso so 2-minute repeats survive dyno restarts until `ack`.
pub async fn fire_urgent_alert_if_allowed<NF, NFut, WF, WFut>(
    db: &Database,
    cfg: &Config,
    sender: &str,
    contact_name: Option<String>,
    draft: &UrgentDraft,
    fallback_request: &str,
    now_ms: i64,
    mut send_ntfy: NF,
    mut notify_wa: WF,
) -> Result<bool>
where
    NF: FnMut(String, String) -> NFut,
    NFut: Future<Output = Result<()>>,
    WF: FnMut(String) -> WFut,
    WFut: Future<Output = Result<()>>,
{
    // Rate limit: max 1 urgent alert sequence per chat per hour
    if db
        .has_recent_urgent_alert(sender, now_ms - URGENT_CHAT_COOLDOWN_MS)
        .await?
    {
        return Ok(false);
    }

    let name = draft
        .name
        .clone()
        .or(contact_name)
        .filter(|s| !s.trim().is_empty());
    let phone = draft
        .number
        .clone()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| sender.to_string());
    let request = draft
        .request
        .clone()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| fallback_request.chars().take(240).collect());
    let deadline = draft
        .deadline
        .clone()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "ASAP / not specified".to_string());

    let summary = format!(
        "{} (+{}) — {} (Deadline: {})",
        name.as_deref().unwrap_or("Contact"),
        phone,
        request,
        deadline
    );

    let mut rec = UrgentAlertRecord {
        id: 0,
        sender: sender.to_string(),
        contact_name: name,
        phone,
        request,
        deadline,
        summary,
        created_at: now_ms,
        last_sent_at: now_ms,
        attempts: 1,
        acknowledged: false,
    };

    let id = db.create_urgent_alert(&rec).await?;
    rec.id = id;

    let (title, body) = format_ntfy_payload(&rec, 1);
    if let Err(e) = send_ntfy(title, body).await {
        tracing::warn!(
            "Initial ntfy urgent push failed: {}",
            redact_secrets(&e.to_string(), cfg)
        );
    }

    let wa_alert = format!(
        "🚨 *{} URGENT ALERT*\nFrom: {} (+{})\nRequest: {}\nDeadline: {}\n\nReply `ack` to stop 2-min ntfy pings, or `approve {}: <msg>` to reply.",
        BOT_NAME,
        rec.contact_name.as_deref().unwrap_or("Unknown"),
        rec.phone,
        rec.request,
        rec.deadline,
        rec.sender
    );
    let _ = notify_wa(wa_alert).await;

    // Clear active intake and pause automation on this urgent thread while awaiting Falcon
    if let Some(mut st) = db.get_conversation(sender).await? {
        st.urgent_draft = UrgentDraft::default();
        st.bot_active = false;
        st.owner_read_unanswered = true;
        st.paused_until = now_ms + cfg.takeover_pause_ms;
        st.updated_at = now_ms;
        db.save_conversation(&st).await?;
    }

    Ok(true)
}

/// Checks unacknowledged urgent alerts and re-sends every 2 minutes up to `URGENT_MAX_ATTEMPTS`.
pub async fn tick_urgent_alerts<NF, NFut>(
    db: &Database,
    cfg: &Config,
    now_ms: i64,
    mut send_ntfy: NF,
) -> Result<usize>
where
    NF: FnMut(String, String) -> NFut,
    NFut: Future<Output = Result<()>>,
{
    let pending = db.list_unacked_urgent_alerts().await?;
    let mut sent_count = 0usize;
    for alert in pending {
        if alert.attempts >= URGENT_MAX_ATTEMPTS {
            db.update_urgent_alert_sent(alert.id, alert.last_sent_at, alert.attempts, true)
                .await?;
            continue;
        }
        if now_ms - alert.last_sent_at >= URGENT_REPEAT_INTERVAL_MS {
            let next_attempt = alert.attempts + 1;
            let (title, body) = format_ntfy_payload(&alert, next_attempt);
            if let Err(e) = send_ntfy(title, body).await {
                tracing::warn!(
                    "Repeat ntfy urgent push #{} failed: {}",
                    next_attempt,
                    redact_secrets(&e.to_string(), cfg)
                );
            }
            let capped = next_attempt >= URGENT_MAX_ATTEMPTS;
            db.update_urgent_alert_sent(alert.id, now_ms, next_attempt, capped)
                .await?;
            sent_count += 1;
        }
    }
    Ok(sent_count)
}

/// If a sender triggered urgent intake and stopped replying for >= 2.5 minutes, escalate immediately
/// with whatever details we already collected so real emergencies never stall.
pub async fn tick_stale_urgent_intakes<NF, NFut, WF, WFut>(
    db: &Database,
    cfg: &Config,
    now_ms: i64,
    mut send_ntfy: NF,
    mut notify_wa: WF,
) -> Result<usize>
where
    NF: FnMut(String, String) -> NFut,
    NFut: Future<Output = Result<()>>,
    WF: FnMut(String) -> WFut,
    WFut: Future<Output = Result<()>>,
{
    let active = db.list_active_urgent_intakes().await?;
    let mut fired = 0usize;
    for st in active {
        if st.urgent_draft.started_at > 0
            && now_ms - st.urgent_draft.started_at >= URGENT_INTAKE_TIMEOUT_MS
        {
            let fallback_req = st
                .history
                .iter()
                .rev()
                .find(|h| h.role == "user")
                .map(|h| h.content.clone())
                .unwrap_or_else(|| "Urgent message (details incomplete)".to_string());
            let did_fire = fire_urgent_alert_if_allowed(
                db,
                cfg,
                &st.sender,
                st.name.clone(),
                &st.urgent_draft,
                &fallback_req,
                now_ms,
                &mut send_ntfy,
                &mut notify_wa,
            )
            .await?;
            if did_fire {
                fired += 1;
            } else {
                let mut updated = st.clone();
                updated.urgent_draft = UrgentDraft::default();
                updated.updated_at = now_ms;
                db.save_conversation(&updated).await?;
            }
        }
    }
    Ok(fired)
}

pub fn format_digest_message(
    items: &[DigestItem],
    unacked_alerts: &[UrgentAlertRecord],
    reminders: &[ReminderRecord],
    header: &str,
) -> String {
    let mut sections: Vec<String> = Vec::new();
    sections.push(header.to_string());

    if !unacked_alerts.is_empty() {
        let lines: Vec<String> = unacked_alerts
            .iter()
            .map(|a| format!("• {}", a.summary))
            .collect();
        sections.push(format!(
            "🚨 *Active Urgent Alerts ({})*:\n{}",
            unacked_alerts.len(),
            lines.join("\n")
        ));
    }

    let handoffs: Vec<&DigestItem> = items.iter().filter(|i| i.kind == "handoff").collect();
    if !handoffs.is_empty() {
        let lines: Vec<String> = handoffs
            .iter()
            .map(|i| {
                let who = match i.contact_name.as_deref() {
                    Some(n) if !n.is_empty() => format!("{} (+{})", n, i.sender),
                    _ => format!("+{}", i.sender),
                };
                format!("• {}: {} (\"{}\")", who, i.summary, i.last_message.chars().take(90).collect::<String>())
            })
            .collect();
        sections.push(format!(
            "🔔 *Pending Approvals / Handoffs ({})*:\n{}",
            handoffs.len(),
            lines.join("\n")
        ));
    }

    let notes: Vec<&DigestItem> = items.iter().filter(|i| i.kind == "note").collect();
    if !notes.is_empty() {
        let lines: Vec<String> = notes
            .iter()
            .map(|i| {
                let who = match i.contact_name.as_deref() {
                    Some(n) if !n.is_empty() => format!("{} (+{})", n, i.sender),
                    _ => format!("+{}", i.sender),
                };
                format!("• {}: {}", who, i.summary)
            })
            .collect();
        sections.push(format!(
            "📝 *Notes & Reminders Left for You ({})*:\n{}",
            notes.len(),
            lines.join("\n")
        ));
    }

    let silent: Vec<&DigestItem> = items.iter().filter(|i| i.kind == "silent_dm").collect();
    if !silent.is_empty() {
        let lines: Vec<String> = silent
            .iter()
            .map(|i| {
                let who = match i.contact_name.as_deref() {
                    Some(n) if !n.is_empty() => format!("{} (+{})", n, i.sender),
                    _ => format!("+{}", i.sender),
                };
                format!("• {}: \"{}\"", who, i.last_message.chars().take(100).collect::<String>())
            })
            .collect();
        sections.push(format!(
            "👁️ *Observe-Only Contacts ({})*:\n{}",
            silent.len(),
            lines.join("\n")
        ));
    }

    let bookings: Vec<&DigestItem> = items.iter().filter(|i| i.kind == "booking").collect();
    if !bookings.is_empty() {
        let lines: Vec<String> = bookings
            .iter()
            .map(|i| format!("• {}", i.summary))
            .collect();
        sections.push(format!(
            "📚 *New Aurikrex Bookings ({})*:\n{}",
            bookings.len(),
            lines.join("\n")
        ));
    }

    if !reminders.is_empty() {
        let lines: Vec<String> = reminders
            .iter()
            .take(5)
            .map(|r| {
                let dt: DateTime<Utc> = Utc
                    .timestamp_millis_opt(r.due_at)
                    .single()
                    .unwrap_or_else(Utc::now);
                let lagos_str = dt.with_timezone(&Lagos).format("%b %d %I:%M %p").to_string();
                format!("• [{}] {}", lagos_str, r.text)
            })
            .collect();
        sections.push(format!(
            "⏰ *Upcoming Reminders ({})*:\n{}",
            reminders.len(),
            lines.join("\n")
        ));
    }

    if sections.len() == 1 {
        sections.push("All clear — no pending handoffs, notes, or urgent alerts.".to_string());
    }

    sections.join("\n\n")
}

/// Delivers the daily morning summary once per day at `cfg.digest_hour_lagos` (default 7:00 AM Africa/Lagos).
pub async fn tick_morning_digest<WF, WFut>(
    db: &Database,
    cfg: &Config,
    now_ms: i64,
    mut notify_wa: WF,
) -> Result<bool>
where
    WF: FnMut(String) -> WFut,
    WFut: Future<Output = Result<()>>,
{
    let now_utc: DateTime<Utc> = Utc
        .timestamp_millis_opt(now_ms)
        .single()
        .unwrap_or_else(Utc::now);
    let lagos = now_utc.with_timezone(&Lagos);
    if lagos.hour() < cfg.digest_hour_lagos {
        return Ok(false);
    }

    let today_key = lagos.format("%Y-%m-%d").to_string();
    let last_date = db.get_setting("last_digest_date").await?.unwrap_or_default();
    if last_date == today_key {
        return Ok(false);
    }

    let items = db.list_pending_digest_items(40).await?;
    let unacked = db.list_unacked_urgent_alerts().await?;
    let reminders = db.list_pending_reminders(10).await?;

    db.set_setting("last_digest_date", &today_key).await?;

    if items.is_empty() && unacked.is_empty() && reminders.is_empty() {
        return Ok(false);
    }

    let header = format!(
        "☀️ *{} 7:00 AM Morning Briefing ({})*",
        BOT_NAME,
        lagos.format("%a, %d %b")
    );
    let msg = format_digest_message(&items, &unacked, &reminders, &header);
    notify_wa(msg).await?;

    let ids: Vec<i64> = items.iter().map(|i| i.id).collect();
    db.mark_digest_items_delivered(&ids).await?;
    Ok(true)
}

/// Fires any due owner reminders to WhatsApp `"Message yourself"`.
pub async fn tick_due_reminders<WF, WFut>(
    db: &Database,
    now_ms: i64,
    mut notify_wa: WF,
) -> Result<usize>
where
    WF: FnMut(String) -> WFut,
    WFut: Future<Output = Result<()>>,
{
    let due = db.list_due_reminders(now_ms).await?;
    let mut delivered = 0usize;
    for r in due {
        let msg = format!("⏰ *{} Reminder*\n{}", BOT_NAME, r.text);
        notify_wa(msg).await?;
        db.mark_reminder_delivered(r.id).await?;
        delivered += 1;
    }
    Ok(delivered)
}
