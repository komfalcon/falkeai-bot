//! Urgent-alert `ntfy` engine (ring-through-DND repeating every 2 minutes until `ack`),
//! urgent intake timeout evaluator, 7:00 AM Africa/Lagos morning digest, and reminders ticker.

use anyhow::{anyhow, Result};
use chrono::{DateTime, Datelike, Duration as ChronoDuration, Timelike, TimeZone, Utc, Weekday};
use chrono_tz::Africa::Lagos;
use serde_json::json;
use std::future::Future;
use std::time::Duration;

use crate::config::{Config, BOT_NAME};
use crate::db::{
    ChatTurn, ConversationState, Database, DigestItem, EmailItemRecord, FollowupRecord,
    ReminderRecord, UrgentAlertRecord, UrgentDraft,
};
use crate::gmail::{sync_all_gmail_accounts, GmailTokenCache};
use crate::safety::redact_secrets;

pub const URGENT_REPEAT_INTERVAL_MS: i64 = 2 * 60_000;
pub const URGENT_MAX_ATTEMPTS: i64 = 15;
pub const URGENT_CHAT_COOLDOWN_MS: i64 = 60 * 60_000;
pub const URGENT_INTAKE_TIMEOUT_MS: i64 = 150_000; // 2.5 minutes
pub const URGENT_INTAKE_MAX_TURNS: i64 = 2;
pub const MAX_GLOBAL_NON_VIP_URGENT_PER_HOUR: usize = 4;

fn clean_single_line(s: &str, max_chars: usize) -> String {
    s.chars()
        .map(|c| if c == '\r' || c == '\n' || c.is_control() { ' ' } else { c })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(max_chars)
        .collect()
}

/// Sanitizes an HTTP header value (such as ntfy `Title`) to printable ASCII (`0x20..=0x7E`)
/// with zero CR/LF or non-ASCII bytes so `reqwest::header::HeaderValue` can never reject or inject headers.
pub fn sanitize_http_header_ascii(s: &str) -> String {
    let replaced = s.replace('—', "-").replace('–', "-");
    let ascii: String = replaced
        .chars()
        .filter_map(|c| {
            if (' '..='~').contains(&c) {
                Some(c)
            } else if c.is_whitespace() {
                Some(' ')
            } else {
                None
            }
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if ascii.is_empty() {
        format!("URGENT - {}", BOT_NAME)
    } else {
        ascii.chars().take(120).collect()
    }
}

pub fn is_urgent_draft_complete(d: &UrgentDraft) -> bool {
    let has_name = d.name.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    let has_number = d.number.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    let has_req = d.request.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    let has_deadline = d.deadline.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false);
    has_name && has_number && has_req && has_deadline
}

pub fn format_ntfy_payload(alert: &UrgentAlertRecord, attempt: i64) -> (String, String) {
    let clean_phone = clean_single_line(&alert.phone, 32);
    let who = match alert.contact_name.as_deref() {
        Some(n) if !n.trim().is_empty() => {
            format!("{} (+{})", clean_single_line(n, 40), clean_phone)
        }
        _ => format!("+{}", clean_phone),
    };
    let raw_title = if attempt <= 1 {
        format!("URGENT - {} ({})", who, BOT_NAME)
    } else {
        format!("URGENT [Ping #{}] - {}", attempt, who)
    };
    let title = sanitize_http_header_ascii(&raw_title);
    let deadline_line = if alert.deadline.trim().is_empty() {
        "Not specified".to_string()
    } else {
        clean_single_line(&alert.deadline, 120)
    };
    let body = format!(
        "From: {}\nRequest: {}\nDeadline: {}\n\nReply 'ack' in WhatsApp Message yourself to stop alerts.",
        who,
        clean_single_line(&alert.request, 240),
        deadline_line
    );
    (title, body)
}

/// Sends a top-priority (`priority: 5` = `max`) push notification via ntfy's JSON publish API
/// (`POST <ntfy_url>/` with JSON body) so UTF-8 titles/emojis never fail HTTP header validation.
pub async fn send_ntfy_http(cfg: &Config, title: &str, body: &str) -> Result<()> {
    let topic = crate::config::normalize_ntfy_topic(&cfg.ntfy_topic, &cfg.ntfy_url);
    if topic.is_empty() {
        return Err(anyhow!(
            "NTFY_TOPIC is not set in environment variables (set NTFY_TOPIC in Heroku Config Vars)"
        ));
    }
    let base_url = crate::config::normalize_ntfy_url(&cfg.ntfy_url);
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(6))
        .timeout(Duration::from_secs(12))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());

    let safe_title = sanitize_http_header_ascii(&redact_secrets(title, cfg));
    let safe_body = redact_secrets(body, cfg);
    let payload = json!({
        "topic": topic,
        "title": safe_title,
        "message": safe_body,
        "priority": 5,
        "tags": ["rotating_light", "phone"]
    });
    let mut req = client
        .post(format!("{}/", base_url.trim_end_matches('/')))
        .json(&payload);
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
    // Rate limit 1: max 1 urgent alert sequence per chat per hour
    if db
        .has_recent_urgent_alert(sender, now_ms - URGENT_CHAT_COOLDOWN_MS)
        .await?
    {
        return Ok(false);
    }

    // Rate limit 2: global non-VIP anti-flood guard so multi-number spam waves cannot DoS Falcon's phone
    let is_vip = db
        .get_contact_memory(sender)
        .await?
        .map(|cm| cm.tier == "vip")
        .unwrap_or(false);
    if !is_vip
        && db
            .count_recent_urgent_alerts(now_ms - URGENT_CHAT_COOLDOWN_MS)
            .await?
            >= MAX_GLOBAL_NON_VIP_URGENT_PER_HOUR
    {
        tracing::warn!(
            "Suppressed non-VIP urgent alert flood for +{} (global hourly limit {} reached)",
            sender,
            MAX_GLOBAL_NON_VIP_URGENT_PER_HOUR
        );
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

    let _ = db
        .append_phone_event(
            "urgent_alarm",
            &json!({
                "source": "whatsapp",
                "alert_id": rec.id,
                "sender": rec.sender,
                "phone": rec.phone,
                "contact_name": rec.contact_name,
                "request": rec.request,
                "deadline": rec.deadline,
                "summary": rec.summary,
                "at": now_ms
            })
            .to_string(),
            now_ms,
        )
        .await;

    let (title, body) = format_ntfy_payload(&rec, 1);
    let ntfy_warning = match send_ntfy(title, body).await {
        Ok(()) => None,
        Err(e) => {
            let err_msg = redact_secrets(&e.to_string(), cfg);
            tracing::warn!("Initial ntfy urgent push failed: {}", err_msg);
            Some(err_msg)
        }
    };

    let mut wa_alert = format!(
        "🚨 *{} URGENT ALERT*\nFrom: {} (+{})\nRequest: {}\nDeadline: {}\n\nReply `ack` to stop 2-min ntfy pings, or `approve {}: <msg>` to reply.",
        BOT_NAME,
        rec.contact_name.as_deref().unwrap_or("Unknown"),
        rec.phone,
        rec.request,
        rec.deadline,
        rec.sender
    );
    if let Some(warn_msg) = ntfy_warning {
        wa_alert.push_str(&format!("\n\n⚠️ *ntfy push failed:* {}", warn_msg));
    }
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

pub fn format_recurrence_badge(recurrence: Option<&str>) -> String {
    let Some(rec) = recurrence.map(str::trim).filter(|s| !s.is_empty()) else {
        return String::new();
    };
    if let Some(time_part) = rec.strip_prefix("daily@") {
        format!(" 🔁 (daily {})", time_part)
    } else if let Some(time_part) = rec.strip_prefix("weekdays@") {
        format!(" 🔁 (weekdays {})", time_part)
    } else if let Some(time_part) = rec.strip_prefix("weekends@") {
        format!(" 🔁 (weekends {})", time_part)
    } else if let Some(rest) = rec.strip_prefix("weekly:") {
        if let Some((days, time_part)) = rest.split_once('@') {
            format!(" 🔁 ({} {})", days, time_part)
        } else {
            format!(" 🔁 ({})", rest)
        }
    } else {
        format!(" 🔁 ({})", rec)
    }
}

pub fn parse_clock_time_token(raw: &str) -> Option<(u32, u32)> {
    let s = raw.trim().to_lowercase();
    if s.is_empty() {
        return None;
    }
    let (is_am, is_pm, core) = if let Some(v) = s.strip_suffix("am") {
        (true, false, v.trim())
    } else if let Some(v) = s.strip_suffix("pm") {
        (false, true, v.trim())
    } else {
        (false, false, s.as_str())
    };

    let (h_raw, m_raw) = if let Some((h, m)) = core.split_once(':') {
        (h.trim().parse::<u32>().ok()?, m.trim().parse::<u32>().ok()?)
    } else if is_am || is_pm {
        (core.parse::<u32>().ok()?, 0)
    } else {
        return None;
    };

    if m_raw >= 60 {
        return None;
    }
    let hour_24 = if is_am {
        if h_raw == 0 || h_raw > 12 {
            return None;
        }
        if h_raw == 12 { 0 } else { h_raw }
    } else if is_pm {
        if h_raw == 0 || h_raw > 12 {
            return None;
        }
        if h_raw == 12 { 12 } else { h_raw + 12 }
    } else {
        if h_raw >= 24 {
            return None;
        }
        h_raw
    };
    Some((hour_24, m_raw))
}

pub fn parse_weekday_token(raw: &str) -> Option<(Weekday, &'static str)> {
    match raw.trim().to_lowercase().as_str() {
        "mon" | "monday" | "mondays" => Some((Weekday::Mon, "mon")),
        "tue" | "tues" | "tuesday" | "tuesdays" => Some((Weekday::Tue, "tue")),
        "wed" | "weds" | "wednesday" | "wednesdays" => Some((Weekday::Wed, "wed")),
        "thu" | "thur" | "thurs" | "thursday" | "thursdays" => Some((Weekday::Thu, "thu")),
        "fri" | "friday" | "fridays" => Some((Weekday::Fri, "fri")),
        "sat" | "saturday" | "saturdays" => Some((Weekday::Sat, "sat")),
        "sun" | "sunday" | "sundays" => Some((Weekday::Sun, "sun")),
        _ => None,
    }
}

fn next_lagos_occurrence<F>(after_ms: i64, hour: u32, minute: u32, day_filter: F) -> Option<i64>
where
    F: Fn(Weekday) -> bool,
{
    let base_utc = Utc.timestamp_millis_opt(after_ms).single()?;
    let base_lagos = base_utc.with_timezone(&Lagos);
    for day_offset in 0..=8 {
        let candidate_date = (base_lagos + ChronoDuration::days(day_offset)).date_naive();
        if !day_filter(candidate_date.weekday()) {
            continue;
        }
        let naive_dt = candidate_date.and_hms_opt(hour, minute, 0)?;
        if let Some(lagos_dt) = Lagos.from_local_datetime(&naive_dt).single().or_else(|| {
            Lagos.from_local_datetime(&naive_dt).earliest()
        }) {
            let candidate_ms = lagos_dt.with_timezone(&Utc).timestamp_millis();
            if candidate_ms > after_ms {
                return Some(candidate_ms);
            }
        }
    }
    None
}

/// Computes the next occurrence timestamp (in UTC ms) for a recurrence descriptor in `Africa/Lagos`.
pub fn compute_next_recurrence_ms(recurrence: &str, after_ms: i64) -> Option<i64> {
    let rec = recurrence.trim().to_lowercase();
    if let Some(t) = rec.strip_prefix("daily@") {
        let (h, m) = parse_clock_time_token(t)?;
        return next_lagos_occurrence(after_ms, h, m, |_| true);
    }
    if let Some(t) = rec.strip_prefix("weekdays@") {
        let (h, m) = parse_clock_time_token(t)?;
        return next_lagos_occurrence(after_ms, h, m, |w| {
            !matches!(w, Weekday::Sat | Weekday::Sun)
        });
    }
    if let Some(t) = rec.strip_prefix("weekends@") {
        let (h, m) = parse_clock_time_token(t)?;
        return next_lagos_occurrence(after_ms, h, m, |w| {
            matches!(w, Weekday::Sat | Weekday::Sun)
        });
    }
    if let Some(rest) = rec.strip_prefix("weekly:") {
        let (days_str, t) = rest.split_once('@')?;
        let (h, m) = parse_clock_time_token(t)?;
        let mut allowed = Vec::new();
        for tok in days_str.split(',') {
            if let Some((wd, _)) = parse_weekday_token(tok) {
                if !allowed.contains(&wd) {
                    allowed.push(wd);
                }
            }
        }
        if allowed.is_empty() {
            return None;
        }
        return next_lagos_occurrence(after_ms, h, m, |w| allowed.contains(&w));
    }
    None
}

fn parse_simple_relative_duration_ms(raw: &str) -> Option<i64> {
    let s = raw.trim().to_lowercase();
    let s = s.strip_prefix("in ").unwrap_or(&s).trim();
    if s.is_empty() {
        return None;
    }
    let split_idx = s
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(s.len());
    if split_idx == 0 {
        return None;
    }
    let val: i64 = s[..split_idx].parse().ok()?;
    if val <= 0 {
        return None;
    }
    let unit = s[split_idx..].trim();
    match unit {
        "m" | "min" | "mins" | "minute" | "minutes" => Some(val * 60_000),
        "h" | "hr" | "hrs" | "hour" | "hours" => Some(val * 3_600_000),
        "d" | "day" | "days" => Some(val * 86_400_000),
        _ => None,
    }
}

/// Parses relative durations (`30m`, `2h`), clock times (`7pm`, `19:30`, `tomorrow 8am`),
/// and recurring schedules (`daily 8am`, `weekdays 7am`, `every monday 9am`, `every mon,wed,fri 6pm`)
/// in `Africa/Lagos` timezone. Returns `(due_at_ms, Option<recurrence_spec>)`.
pub fn parse_reminder_schedule(spec: &str, now_ms: i64) -> Option<(i64, Option<String>)> {
    let cleaned = spec
        .trim()
        .to_lowercase()
        .replace(" at ", " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if cleaned.is_empty() {
        return None;
    }

    // 1) Relative duration: "30m", "2h", "1d", "in 45m"
    if let Some(dur_ms) = parse_simple_relative_duration_ms(&cleaned) {
        return Some((now_ms + dur_ms, None));
    }

    // 2) Single clock token: "7pm", "8:30am", "19:30"
    let base_clean = cleaned.strip_prefix("at ").unwrap_or(&cleaned).trim();
    if let Some((h, m)) = parse_clock_time_token(base_clean) {
        let due = next_lagos_occurrence(now_ms, h, m, |_| true)?;
        return Some((due, None));
    }

    let tokens: Vec<&str> = base_clean.split_whitespace().collect();
    if tokens.len() >= 2 {
        let time_tok = tokens[tokens.len() - 1];
        if let Some((h, m)) = parse_clock_time_token(time_tok) {
            let prefix = tokens[..tokens.len() - 1].join(" ");
            let hhmm = format!("{:02}:{:02}", h, m);
            match prefix.as_str() {
                "today" => {
                    let due = next_lagos_occurrence(now_ms, h, m, |_| true)?;
                    return Some((due, None));
                }
                "tomorrow" | "tmrw" => {
                    let base_utc = Utc.timestamp_millis_opt(now_ms).single()?;
                    let tmrw_date = (base_utc.with_timezone(&Lagos) + ChronoDuration::days(1)).date_naive();
                    let naive_dt = tmrw_date.and_hms_opt(h, m, 0)?;
                    let lagos_dt = Lagos
                        .from_local_datetime(&naive_dt)
                        .single()
                        .or_else(|| Lagos.from_local_datetime(&naive_dt).earliest())?;
                    return Some((lagos_dt.with_timezone(&Utc).timestamp_millis(), None));
                }
                "daily" | "everyday" | "every day" => {
                    let rec = format!("daily@{}", hhmm);
                    let due = compute_next_recurrence_ms(&rec, now_ms)?;
                    return Some((due, Some(rec)));
                }
                "weekdays" | "weekday" | "every weekday" | "every weekdays" => {
                    let rec = format!("weekdays@{}", hhmm);
                    let due = compute_next_recurrence_ms(&rec, now_ms)?;
                    return Some((due, Some(rec)));
                }
                "weekends" | "weekend" | "every weekend" | "every weekends" => {
                    let rec = format!("weekends@{}", hhmm);
                    let due = compute_next_recurrence_ms(&rec, now_ms)?;
                    return Some((due, Some(rec)));
                }
                _ => {
                    let day_part = prefix.strip_prefix("every ").unwrap_or(&prefix).trim();
                    let is_recurring = prefix.starts_with("every ");
                    let mut slugs: Vec<&'static str> = Vec::new();
                    let mut weekdays: Vec<Weekday> = Vec::new();
                    for raw_day in day_part.split(|c: char| c == ',' || c.is_whitespace()) {
                        if raw_day.is_empty() || raw_day == "and" {
                            continue;
                        }
                        let (wd, slug) = parse_weekday_token(raw_day)?;
                        if !weekdays.contains(&wd) {
                            weekdays.push(wd);
                            slugs.push(slug);
                        }
                    }
                    if !weekdays.is_empty() {
                        if is_recurring || slugs.len() > 1 {
                            let rec = format!("weekly:{}@{}", slugs.join(","), hhmm);
                            let due = compute_next_recurrence_ms(&rec, now_ms)?;
                            return Some((due, Some(rec)));
                        } else {
                            let target_wd = weekdays[0];
                            let due = next_lagos_occurrence(now_ms, h, m, |w| w == target_wd)?;
                            return Some((due, None));
                        }
                    }
                }
            }
        }
    }

    None
}

pub fn format_followup_card(f: &FollowupRecord) -> String {
    let kind_badge = match f.kind.as_str() {
        "falcon_promised" => "🤝 *You promised to get back*",
        "waiting_on_them" => "⏳ *Waiting on their reply*",
        _ => "🧊 *Cold inquiry / thread*",
    };
    let who = match f.contact_name.as_deref() {
        Some(n) if !n.trim().is_empty() => format!("{} (+{})", n.trim(), f.sender),
        _ => format!("+{}", f.sender),
    };
    let status_tag = if f.status == "ready" {
        "READY"
    } else {
        "WATCHING"
    };
    format!(
        "• *[#{id} | {status_tag}]* {kind_badge} — *{who}*\n  Reason: {reason}\n  Draft: _\"{draft}\"_\n  Actions: `send followup {id}` | `send followup {id}: <custom text>` | `snooze followup {id}` | `skip followup {id}`",
        id = f.id,
        status_tag = status_tag,
        kind_badge = kind_badge,
        who = who,
        reason = f.reason,
        draft = f.suggested_reply
    )
}

pub fn format_bank_amount(amount_minor: i64, currency: &str) -> String {
    let major = (amount_minor as f64) / 100.0;
    let upper = currency.to_uppercase();
    let sym = match upper.as_str() {
        "NGN" => "₦",
        "USD" => "$",
        "GBP" => "£",
        "EUR" => "€",
        other => other,
    };
    if amount_minor % 100 == 0 {
        format!("{}{}", sym, amount_minor / 100)
    } else {
        format!("{}{:.2}", sym, major)
    }
}

pub fn format_email_line(e: &EmailItemRecord) -> String {
    let icon = match e.category.as_str() {
        "interview_career" => "💼",
        "payments_finance" => "💰",
        "infra_alerts" => "🛠️",
        _ => "✉️",
    };
    let urg_tag = if e.priority == "urgent" { " [URGENT]" } else { "" };
    format!(
        "• {} *[{}]{}* {} — *{}*\n  {}",
        icon,
        e.account_label,
        urg_tag,
        e.sender.chars().take(48).collect::<String>(),
        e.subject.chars().take(90).collect::<String>(),
        e.snippet.chars().take(120).collect::<String>()
    )
}

pub fn format_digest_message(
    items: &[DigestItem],
    unacked_alerts: &[UrgentAlertRecord],
    reminders: &[ReminderRecord],
    header: &str,
) -> String {
    format_digest_message_full(items, unacked_alerts, reminders, &[], &[], header)
}

pub fn format_digest_message_with_followups(
    items: &[DigestItem],
    unacked_alerts: &[UrgentAlertRecord],
    reminders: &[ReminderRecord],
    ready_followups: &[FollowupRecord],
    header: &str,
) -> String {
    format_digest_message_full(
        items,
        unacked_alerts,
        reminders,
        ready_followups,
        &[],
        header,
    )
}

pub fn format_digest_message_full(
    items: &[DigestItem],
    unacked_alerts: &[UrgentAlertRecord],
    reminders: &[ReminderRecord],
    ready_followups: &[FollowupRecord],
    priority_emails: &[EmailItemRecord],
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

    if !priority_emails.is_empty() {
        let lines: Vec<String> = priority_emails
            .iter()
            .take(8)
            .map(format_email_line)
            .collect();
        sections.push(format!(
            "📧 *Priority Unread Emails ({})*:\n{}",
            priority_emails.len(),
            lines.join("\n")
        ));
    }

    if !ready_followups.is_empty() {
        let lines: Vec<String> = ready_followups
            .iter()
            .take(6)
            .map(format_followup_card)
            .collect();
        sections.push(format!(
            "📬 *Smart Follow-Ups Ready for Approval ({})*:\n{}",
            ready_followups.len(),
            lines.join("\n\n")
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
            .take(6)
            .map(|r| {
                let dt: DateTime<Utc> = Utc
                    .timestamp_millis_opt(r.due_at)
                    .single()
                    .unwrap_or_else(Utc::now);
                let lagos_str = dt.with_timezone(&Lagos).format("%b %d %I:%M %p").to_string();
                let badge = format_recurrence_badge(r.recurrence.as_deref());
                format!("• [#{}] [{}]{} {}", r.id, lagos_str, badge, r.text)
            })
            .collect();
        sections.push(format!(
            "⏰ *Upcoming Reminders ({})*:\n{}",
            reminders.len(),
            lines.join("\n")
        ));
    }

    if sections.len() == 1 {
        sections.push("All clear — no pending handoffs, follow-ups, notes, or urgent alerts.".to_string());
    }

    sections.join("\n\n")
}

/// Promotes any due `watching` follow-ups to `ready` so they appear in `followups` and the 7:00 AM briefing.
pub async fn tick_promote_followups(db: &Database, now_ms: i64) -> Result<usize> {
    let promoted = db.promote_due_followups(now_ms).await?;
    Ok(promoted.len())
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

    let _ = db.promote_due_followups(now_ms).await;
    let items = db.list_pending_digest_items(40).await?;
    let unacked = db.list_unacked_urgent_alerts().await?;
    let reminders = db.list_pending_reminders(10).await?;
    let ready_followups = db.list_followups(Some("ready"), 10).await?;
    let priority_emails = db.list_pending_digest_emails(12).await?;

    if items.is_empty()
        && unacked.is_empty()
        && reminders.is_empty()
        && ready_followups.is_empty()
        && priority_emails.is_empty()
    {
        db.set_setting("last_digest_date", &today_key).await?;
        return Ok(false);
    }

    let header = format!(
        "☀️ *{} 7:00 AM Morning Briefing ({})*",
        BOT_NAME,
        lagos.format("%a, %d %b")
    );
    let msg = format_digest_message_full(
        &items,
        &unacked,
        &reminders,
        &ready_followups,
        &priority_emails,
        &header,
    );
    notify_wa(msg).await?;

    db.set_setting("last_digest_date", &today_key).await?;
    let ids: Vec<i64> = items.iter().map(|i| i.id).collect();
    db.mark_digest_items_delivered(&ids).await?;
    let email_ids: Vec<String> = priority_emails.iter().map(|e| e.msg_id.clone()).collect();
    db.mark_emails_digest_delivered(&email_ids).await?;
    Ok(true)
}

/// Polls all connected Gmail accounts and triggers immediate urgent alerts for critical emails
/// (interview invites/reschedules, Paystack/Stripe payout failures/disputes, Heroku/GitHub crashes).
pub async fn tick_gmail_watcher<NF, NFut, WF, WFut, PF, PFut>(
    db: &Database,
    cfg: &Config,
    cache: &GmailTokenCache,
    now_ms: i64,
    mut send_ntfy: NF,
    mut notify_wa: WF,
    mut push_phone_alarm: PF,
) -> Result<usize>
where
    NF: FnMut(String, String) -> NFut,
    NFut: Future<Output = Result<()>>,
    WF: FnMut(String) -> WFut,
    WFut: Future<Output = Result<()>>,
    PF: FnMut(String, serde_json::Value) -> PFut,
    PFut: Future<Output = Result<()>>,
{
    let newly_found = sync_all_gmail_accounts(db, cfg, cache, now_ms).await?;
    let mut urgent_count = 0usize;
    for email in newly_found {
        if email.priority != "urgent" || email.alerted {
            continue;
        }
        let title = format!("URGENT EMAIL [{}] - {}", email.account_label, email.category);
        let body = format!(
            "From: {}\nSubject: {}\n{}",
            email.sender, email.subject, email.snippet
        );
        let _ = send_ntfy(title, body.clone()).await;
        let wa_msg = format!(
            "🚨 *{} URGENT EMAIL [{}]*\nFrom: {}\nSubject: {}\nSummary: {}",
            BOT_NAME, email.account_label, email.sender, email.subject, email.snippet
        );
        let _ = notify_wa(wa_msg).await;
        let _ = push_phone_alarm(
            "urgent_alarm".to_string(),
            json!({
                "source": "gmail",
                "account": email.account_label,
                "sender": email.sender,
                "subject": email.subject,
                "summary": email.snippet,
                "msg_id": email.msg_id,
                "at": now_ms
            }),
        )
        .await;
        db.mark_email_alerted(&email.msg_id).await?;
        urgent_count += 1;
    }
    Ok(urgent_count)
}

/// Dispatches any due scheduled WhatsApp or SMS messages (Feature J).
pub async fn tick_scheduled_messages<SW, SWFut, SS, SSFut, NW, NWFut>(
    db: &Database,
    now_ms: i64,
    mut send_whatsapp: SW,
    mut send_sms_to_phone: SS,
    mut notify_wa: NW,
) -> Result<usize>
where
    SW: FnMut(String, String) -> SWFut,
    SWFut: Future<Output = Result<()>>,
    SS: FnMut(String, serde_json::Value) -> SSFut,
    SSFut: Future<Output = Result<()>>,
    NW: FnMut(String) -> NWFut,
    NWFut: Future<Output = Result<()>>,
{
    let due = db.list_due_scheduled_messages(now_ms).await?;
    if due.is_empty() {
        return Ok(0);
    }
    let has_paired_phone = !db.list_active_phone_devices().await?.is_empty();
    let mut sent = 0usize;
    for msg in due {
        if msg.channel == "sms" {
            if !has_paired_phone {
                db.update_scheduled_message_status(msg.id, "failed", now_ms)
                    .await?;
                let _ = notify_wa(format!(
                    "⚠️ Scheduled SMS #{} to {} (+{}) could not be sent because no Android phone is paired yet (`pair android`).",
                    msg.id,
                    msg.contact_name.as_deref().unwrap_or("Contact"),
                    msg.target
                ))
                .await;
                continue;
            }
            send_sms_to_phone(
                "send_sms".to_string(),
                json!({
                    "scheduled_id": msg.id,
                    "phone": msg.target,
                    "contact_name": msg.contact_name,
                    "message": msg.message,
                }),
            )
            .await?;
            db.update_scheduled_message_status(msg.id, "sent", now_ms)
                .await?;
            let _ = notify_wa(format!(
                "📤 Dispatched scheduled SMS #{} to {} (+{}) via Android phone:\n\"{}\"",
                msg.id,
                msg.contact_name.as_deref().unwrap_or("Contact"),
                msg.target,
                msg.message
            ))
            .await;
            sent += 1;
        } else {
            let jid = if msg.target.contains('@') {
                msg.target.clone()
            } else {
                format!("{}@s.whatsapp.net", msg.target)
            };
            send_whatsapp(jid, msg.message.clone()).await?;
            let mut st = db
                .get_conversation(&msg.target)
                .await?
                .unwrap_or_else(|| ConversationState::new(&msg.target));
            st.history.push(ChatTurn {
                role: "assistant".to_string(),
                content: format!("[Falcon]: {}", msg.message),
            });
            st.bot_active = true;
            st.owner_read_unanswered = false;
            st.paused_until = 0;
            st.updated_at = now_ms;
            db.save_conversation(&st).await?;
            db.update_scheduled_message_status(msg.id, "sent", now_ms)
                .await?;
            let _ = notify_wa(format!(
                "✅ Sent scheduled WhatsApp #{} to {} (+{}):\n\"{}\"",
                msg.id,
                msg.contact_name.as_deref().unwrap_or("Contact"),
                msg.target,
                msg.message
            ))
            .await;
            sent += 1;
        }
    }
    Ok(sent)
}

/// Builds a compact ~20-second "Catch Me Up" summary across WhatsApp, Gmail, Follow-ups & Bank Alerts (Feature I).
pub async fn build_catch_me_up_summary(db: &Database, now_ms: i64) -> Result<String> {
    let _ = db.promote_due_followups(now_ms).await;
    let unacked = db.list_unacked_urgent_alerts().await?;
    let flags = db.list_flags(6).await?;
    let emails = db.list_recent_priority_emails(5).await?;
    let followups = db.list_followups(Some("ready"), 5).await?;
    let bank = db
        .list_bank_alerts(now_ms - 24 * 3_600_000, 5)
        .await?;

    let mut parts: Vec<String> = Vec::new();
    if !unacked.is_empty() {
        parts.push(format!(
            "Urgent alerts ({}): {}",
            unacked.len(),
            unacked
                .iter()
                .take(2)
                .map(|a| a.summary.as_str())
                .collect::<Vec<_>>()
                .join("; ")
        ));
    }
    if !flags.is_empty() {
        parts.push(format!(
            "WhatsApp chats needing your approval ({}): {}",
            flags.len(),
            flags
                .iter()
                .take(3)
                .map(|f| format!("{}: {}", f.name.as_deref().unwrap_or(&f.chat), f.reason))
                .collect::<Vec<_>>()
                .join("; ")
        ));
    }
    if !emails.is_empty() {
        parts.push(format!(
            "Priority Gmail ({}): {}",
            emails.len(),
            emails
                .iter()
                .take(3)
                .map(|e| format!("{} — {}", e.sender, e.subject))
                .collect::<Vec<_>>()
                .join("; ")
        ));
    }
    if !bank.is_empty() {
        parts.push(format!(
            "Recent bank/payout alerts ({}): {}",
            bank.len(),
            bank.iter()
                .take(3)
                .map(|b| format!(
                    "{} {} {} ({})",
                    b.bank,
                    if b.direction == "credit" { "+" } else { "-" },
                    format_bank_amount(b.amount_minor, &b.currency),
                    b.counterparty
                ))
                .collect::<Vec<_>>()
                .join("; ")
        ));
    }
    if !followups.is_empty() {
        parts.push(format!(
            "{} follow-up(s) ready for approval",
            followups.len()
        ));
    }

    if parts.is_empty() {
        Ok("All clear, boss — no urgent alerts, pending WhatsApp holds, priority emails, or new bank alerts right now.".to_string())
    } else {
        Ok(format!("⚡ *Catch-Up Briefing*\n{}", parts.join("\n• ")))
    }
}

/// Fires any due owner reminders to WhatsApp `"Message yourself"`, automatically rescheduling
/// recurring reminders (`daily`, `weekdays`, `weekends`, `weekly:...`) to their next occurrence.
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
        let badge = format_recurrence_badge(r.recurrence.as_deref());
        let msg = format!("⏰ *{} Reminder*{}\n{}", BOT_NAME, badge, r.text);
        notify_wa(msg).await?;
        if let Some(rec) = r.recurrence.as_deref() {
            let base_after = now_ms.max(r.due_at) + 60_000;
            if let Some(next_due) = compute_next_recurrence_ms(rec, base_after) {
                db.reschedule_recurring_reminder(r.id, next_due).await?;
            } else {
                db.mark_reminder_delivered(r.id).await?;
            }
        } else {
            db.mark_reminder_delivered(r.id).await?;
        }
        delivered += 1;
    }
    Ok(delivered)
}
