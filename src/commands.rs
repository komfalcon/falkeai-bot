use anyhow::Result;
use chrono::{DateTime, TimeZone, Utc};
use chrono_tz::Africa::Lagos;

use crate::alerts::{
    format_digest_message_with_followups, format_followup_card, format_recurrence_badge,
    parse_reminder_schedule, send_ntfy_http,
};
use crate::brain::{merge_semantic_facts, HISTORY_MAX};
use crate::config::{Config, BOT_NAME};
use crate::db::{ChatTurn, ContactMemory, ConversationState, Database, OwnerStatus};
use crate::ipc::GroupSummary;
use crate::util::{normalize_number, number_of_jid};

pub(crate) async fn resolve_contact_target(db: &Database, query: &str) -> Result<Option<String>> {
    let q = query.trim();
    if q.is_empty() {
        return Ok(None);
    }
    let norm = normalize_number(q);
    if norm.len() >= 7 && q.chars().all(|c| c.is_ascii_digit() || "+ -()".contains(c)) {
        return Ok(Some(norm));
    }
    if let Ok(hits) = db.find_contact_memories(q).await {
        if let Some(first) = hits.first() {
            return Ok(Some(first.sender.clone()));
        }
    }
    if let Ok(saved_hits) = db.find_saved_contacts(q).await {
        if let Some((id, _name)) = saved_hits.first() {
            return Ok(Some(id.clone()));
        }
    }
    let lower_q = q.to_lowercase();
    let q_alpha: String = lower_q.chars().filter(|c| c.is_alphanumeric()).collect();
    if let Ok(convos) = db.list_recent_conversations(30).await {
        if let Some(c) = convos.into_iter().find(|c| {
            (!norm.is_empty() && c.sender.contains(&norm))
                || c.name
                    .as_deref()
                    .map(|n| {
                        let nl = n.to_lowercase();
                        let na: String = nl.chars().filter(|ch| ch.is_alphanumeric()).collect();
                        nl.contains(&lower_q) || (!q_alpha.is_empty() && na.contains(&q_alpha))
                    })
                    .unwrap_or(false)
        }) {
            return Ok(Some(c.sender));
        }
    }
    if let Ok(flags) = db.list_flags(25).await {
        if let Some(f) = flags.into_iter().find(|f| {
            (!norm.is_empty() && f.chat.contains(&norm))
                || f.name
                    .as_deref()
                    .map(|n| n.to_lowercase().contains(&lower_q))
                    .unwrap_or(false)
        }) {
            return Ok(Some(f.chat));
        }
    }
    if let Ok(alerts) = db.list_unacked_urgent_alerts().await {
        if let Some(a) = alerts.into_iter().find(|a| {
            (!norm.is_empty() && (a.sender.contains(&norm) || a.phone.contains(&norm)))
                || a.contact_name
                    .as_deref()
                    .map(|n| n.to_lowercase().contains(&lower_q))
                    .unwrap_or(false)
        }) {
            return Ok(Some(a.sender));
        }
    }
    Ok(None)
}

pub async fn take_approved_outbounds(db: &Database) -> Result<Vec<(String, String)>> {
    let Some(raw) = db.get_setting("pending_approved_outbounds").await? else {
        return Ok(Vec::new());
    };
    if raw.trim().is_empty() || raw.trim() == "[]" {
        return Ok(Vec::new());
    }
    let items: Vec<(String, String)> = serde_json::from_str(&raw).unwrap_or_default();
    db.set_setting("pending_approved_outbounds", "[]").await?;
    Ok(items)
}

async fn queue_approved_outbound(db: &Database, number: &str, text: &str) -> Result<()> {
    let mut current: Vec<(String, String)> = db
        .get_setting("pending_approved_outbounds")
        .await?
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    current.push((format!("{}@s.whatsapp.net", number), text.to_string()));
    let serialized = serde_json::to_string(&current).unwrap_or_else(|_| "[]".to_string());
    db.set_setting("pending_approved_outbounds", &serialized)
        .await
}

pub fn is_self_chat(
    from_me: bool,
    self_jid: &str,
    self_lid: Option<&str>,
    chat_jid: &str,
    chat_alt_jid: Option<&str>,
) -> bool {
    if !from_me {
        return false;
    }
    let me: Vec<String> = [Some(self_jid), self_lid]
        .into_iter()
        .flatten()
        .map(number_of_jid)
        .filter(|s| !s.is_empty())
        .collect();
    let chat: Vec<String> = [Some(chat_jid), chat_alt_jid]
        .into_iter()
        .flatten()
        .map(number_of_jid)
        .filter(|s| !s.is_empty())
        .collect();
    chat.iter().any(|c| me.contains(c))
}

pub async fn get_reply_mode(db: &Database, fallback: &str) -> Result<String> {
    Ok(db
        .get_setting("reply_mode")
        .await?
        .unwrap_or_else(|| fallback.to_string()))
}

pub async fn get_self_voice_mode(db: &Database) -> Result<String> {
    Ok(db
        .get_setting("self_voice_mode")
        .await?
        .filter(|m| matches!(m.as_str(), "auto" | "on" | "off"))
        .unwrap_or_else(|| "auto".to_string()))
}

pub fn user_explicitly_requested_voice(raw_text: &str) -> bool {
    let lower = raw_text.trim().to_lowercase();
    [
        "read me my briefing",
        "read my briefing",
        "voice briefing",
        "reply with voice",
        "reply in voice",
        "reply with a voice note",
        "send a voice note",
        "send voice note",
        "speak to me",
        "say it out loud",
    ]
    .iter()
    .any(|p| lower.contains(p))
}

pub fn should_reply_with_voice_in_self_chat(
    voice_mode: &str,
    incoming_was_voice: bool,
    raw_user_text: &str,
) -> bool {
    let explicit = user_explicitly_requested_voice(raw_user_text);
    match voice_mode {
        "on" => true,
        "off" => explicit,
        _ => incoming_was_voice || explicit,
    }
}

fn parse_duration_minutes(token: &str) -> Option<i64> {
    let t = token.trim().to_lowercase();
    if let Some(h) = t.strip_suffix("hr").or_else(|| t.strip_suffix("hrs")).or_else(|| t.strip_suffix('h')) {
        return h.trim().parse::<i64>().ok().filter(|&v| v > 0).map(|v| v * 60);
    }
    if let Some(m) = t.strip_suffix("mins").or_else(|| t.strip_suffix("min")).or_else(|| t.strip_suffix('m')) {
        return m.trim().parse::<i64>().ok().filter(|&v| v > 0);
    }
    t.parse::<i64>().ok().filter(|&v| v > 0)
}

pub async fn run_owner_command<F, Fut>(
    db: &Database,
    cfg: &Config,
    text: &str,
    mut fetch_groups: F,
) -> Result<Option<String>>
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = Result<Vec<GroupSummary>>>,
{
    let raw = text.trim();
    if raw.is_empty() {
        return Ok(None);
    }
    let lower = raw.to_lowercase();

    // ack [phone/name] — stop repeating urgent ntfy alerts immediately
    if lower == "ack" || lower.starts_with("ack ") {
        let target = if lower.len() > 3 {
            let q = raw[3..].trim();
            if q.is_empty() {
                None
            } else {
                Some(resolve_contact_target(db, q).await?.unwrap_or_else(|| q.to_string()))
            }
        } else {
            None
        };
        let acked = db.ack_urgent_alerts(target.as_deref()).await?;
        if acked == 0 {
            return Ok(Some("✅ No active unacknowledged urgent alerts right now.".to_string()));
        }
        return Ok(Some(format!(
            "✅ Acknowledged {} urgent alert(s). Stopped ntfy repeat pings.",
            acked
        )));
    }

    // test ntfy / ntfy test — send an instant diagnostic max-priority ping to verify phone delivery
    if matches!(lower.as_str(), "test ntfy" | "ntfy test" | "ping ntfy" | "ntfy") {
        let topic = crate::config::normalize_ntfy_topic(&cfg.ntfy_topic, &cfg.ntfy_url);
        if topic.is_empty() {
            return Ok(Some(
                "❌ *ntfy is NOT configured* (`NTFY_TOPIC` env var is missing on Heroku).\nAdd `NTFY_TOPIC` (just the topic name, not the full URL) in Heroku Settings -> Config Vars and restart the dyno."
                    .to_string(),
            ));
        }
        return match send_ntfy_http(
            cfg,
            &format!("{} Test Alert", BOT_NAME),
            "Test max-priority alert from WhatsApp self-chat. If your phone rang / broke through DND, ntfy is ready!",
        )
        .await
        {
            Ok(()) => Ok(Some(
                "✅ Test max-priority `ntfy` alert sent! Check if your phone buzzed/rang (make sure battery optimization is off & Override DND is enabled on the max-priority channel in the ntfy app)."
                    .to_string(),
            )),
            Err(e) => Ok(Some(format!(
                "❌ `ntfy` push failed: {}\nCheck `NTFY_TOPIC` (topic name only) / `NTFY_URL` / `NTFY_TOKEN` in Heroku Config Vars.",
                crate::safety::redact_secrets(&e.to_string(), cfg)
            ))),
        };
    }

    // briefing / brief / summary / read me my briefing — on-demand digest
    if matches!(
        lower.as_str(),
        "briefing"
            | "brief"
            | "summary"
            | "morning summary"
            | "read me my briefing"
            | "read my briefing"
            | "voice briefing"
    ) {
        let now_ms = Utc::now().timestamp_millis();
        let _ = db.promote_due_followups(now_ms).await;
        let items = db.list_pending_digest_items(40).await?;
        let unacked = db.list_unacked_urgent_alerts().await?;
        let rems = db.list_pending_reminders(10).await?;
        let ready_followups = db.list_followups(Some("ready"), 10).await?;
        let msg = format_digest_message_with_followups(
            &items,
            &unacked,
            &rems,
            &ready_followups,
            &format!("📋 *{} On-Demand Briefing*", BOT_NAME),
        );
        return Ok(Some(msg));
    }

    // voice [auto|on|off] — self-chat TTS voice note mode
    if lower == "voice" || lower.starts_with("voice ") {
        let arg = raw.get(5..).unwrap_or("").trim().to_lowercase();
        if arg.is_empty() {
            let cur = get_self_voice_mode(db).await?;
            return Ok(Some(format!(
                "🎙 *Self-Chat Voice Reply Mode*: `{}`\n• `voice auto` — reply with a voice note when you send a voice note or say \"read me my briefing\"\n• `voice on` — always reply with a voice note in Message yourself\n• `voice off` — text replies only",
                cur
            )));
        }
        let mode = match arg.as_str() {
            "auto" => "auto",
            "on" | "always" => "on",
            "off" | "mute" | "text" => "off",
            _ => {
                return Ok(Some(
                    "⚠️ Usage: `voice auto`, `voice on`, or `voice off`".to_string(),
                ));
            }
        };
        db.set_setting("self_voice_mode", mode).await?;
        return Ok(Some(format!(
            "🎙 Self-chat voice reply mode set to *{}*.",
            mode
        )));
    }

    // vip / silent / normal <phone/name> — per-contact autonomy tiers
    if matches!(lower.as_str(), "vip" | "silent" | "normal") {
        return Ok(Some(format!(
            "⚠️ Usage: `{} <phone or name>` (tiers: `vip`, `normal`, `silent`)",
            lower
        )));
    }
    if lower.starts_with("vip ") || lower.starts_with("silent ") || lower.starts_with("normal ") {
        let idx = raw.find(' ').unwrap_or(0);
        let tier = lower[..idx].trim();
        let target_raw = raw[idx + 1..].trim();
        let Some(num) = resolve_contact_target(db, target_raw).await? else {
            return Ok(Some(format!(
                "⚠️ Could not find a contact matching \"{}\". Usage: `{} 2348012345678`",
                target_raw, tier
            )));
        };
        let cm = db.set_contact_tier(&num, tier).await?;
        let desc = match cm.tier.as_str() {
            "vip" => "VIP (urgent-alert priority + close memory scope)",
            "silent" => "Silent / Observe-Only (logs messages for your digest, never auto-replies)",
            _ => "Normal (standard 5-minute takeover behavior)",
        };
        return Ok(Some(format!("✅ Updated +{} -> *{}*", num, desc)));
    }

    // Availability modes: busy / deep / sleep / available
    if matches!(lower.as_str(), "available" | "back" | "im back" | "i'm back") {
        db.set_owner_status(&OwnerStatus::available()).await?;
        return Ok(Some("✅ Availability set to *available* (normal 5-minute wait restored).".to_string()));
    }
    if lower == "busy"
        || lower == "sleep"
        || lower == "sleeping"
        || lower == "deep"
        || lower.starts_with("busy ")
        || lower.starts_with("sleep ")
        || lower.starts_with("sleeping ")
        || lower.starts_with("deep ")
    {
        let mut parts_iter = raw.split_whitespace();
        let cmd_word = parts_iter.next().unwrap_or("busy").to_lowercase();
        let mode = match cmd_word.as_str() {
            "sleep" | "sleeping" => "sleeping",
            "deep" => "deep_work",
            _ => "busy",
        };
        let default_mins = if mode == "sleeping" { 420 } else { 120 };
        let rest_tokens: Vec<&str> = parts_iter.collect();
        let (mins, note_tokens) = if let Some(first) = rest_tokens.first() {
            if let Some(m) = parse_duration_minutes(first) {
                (m, &rest_tokens[1..])
            } else {
                (default_mins, &rest_tokens[..])
            }
        } else {
            (default_mins, &rest_tokens[..])
        };
        let note_str = note_tokens.join(" ");
        let note = if note_str.trim().is_empty() {
            None
        } else {
            Some(note_str.trim().to_string())
        };
        let now = Utc::now().timestamp_millis();
        let until_ms = now + mins * 60_000;
        db.set_owner_status(&OwnerStatus {
            mode: mode.to_string(),
            until_ms,
            note: note.clone(),
        })
        .await?;
        let note_fmt = note.map(|n| format!(" ({})", n)).unwrap_or_default();
        return Ok(Some(format!(
            "✅ Availability set to *{}* for {} min{}. {} will step in immediately while you're away.",
            mode, mins, note_fmt, BOT_NAME
        )));
    }

    // remind <schedule>: <message> (supports relative 30m/2h, clock times 7pm/tomorrow 8am, and recurring daily 8am / weekdays 7am / every monday 9am)
    if lower == "remind" || lower == "reminders" {
        let rems = db.list_pending_reminders(15).await?;
        if rems.is_empty() {
            return Ok(Some(
                "✅ No upcoming reminders.\nExamples:\n• `remind 30m: Call Tunde`\n• `remind 7pm: Review syncgrade PR`\n• `remind daily 8am: Morning workout`\n• `remind weekdays 7am: Check briefing`\n• `remind every monday 9am: Weekly planning`".to_string(),
            ));
        }
        let lines: Vec<String> = rems
            .iter()
            .map(|r| {
                let dt: DateTime<Utc> = Utc
                    .timestamp_millis_opt(r.due_at)
                    .single()
                    .unwrap_or_else(Utc::now);
                let lagos_str = dt.with_timezone(&Lagos).format("%b %d %I:%M %p").to_string();
                let badge = format_recurrence_badge(r.recurrence.as_deref());
                format!("• #{} [{}]{} {}", r.id, lagos_str, badge, r.text)
            })
            .collect();
        return Ok(Some(format!("⏰ *Upcoming Reminders*:\n{}", lines.join("\n"))));
    }
    if lower.starts_with("remind ") {
        let body = raw["remind ".len()..].trim();
        let (sched_part, msg_part) = match body.find(':') {
            Some(c) => (body[..c].trim(), body[c + 1..].trim()),
            None => {
                let mut sp = body.splitn(2, ' ');
                (sp.next().unwrap_or(""), sp.next().unwrap_or("").trim())
            }
        };
        let sched_clean = sched_part
            .to_lowercase()
            .trim_start_matches("me ")
            .trim_start_matches("in ")
            .trim()
            .to_string();
        let now = Utc::now().timestamp_millis();
        if !msg_part.is_empty() {
            if let Some((due_at, recurrence)) = parse_reminder_schedule(&sched_clean, now) {
                let id = db
                    .add_reminder_with_recurrence(due_at, msg_part, recurrence.as_deref(), now)
                    .await?;
                let dt: DateTime<Utc> = Utc
                    .timestamp_millis_opt(due_at)
                    .single()
                    .unwrap_or_else(Utc::now);
                let lagos_str = dt
                    .with_timezone(&Lagos)
                    .format("%a, %b %d at %I:%M %p")
                    .to_string();
                let badge = format_recurrence_badge(recurrence.as_deref());
                return Ok(Some(format!(
                    "⏰ Reminder #{} set for *{}*{}:\n\"{}\"",
                    id, lagos_str, badge, msg_part
                )));
            }
        }
    }
    if lower.starts_with("unremind ")
        || lower.starts_with("cancel reminder ")
        || lower.starts_with("delete reminder ")
    {
        let id_str = raw
            .split_whitespace()
            .last()
            .unwrap_or("")
            .trim_start_matches('#');
        let Ok(rid) = id_str.parse::<i64>() else {
            return Ok(Some("⚠️ Usage: `unremind <id>` (e.g. `unremind 3`)".to_string()));
        };
        return Ok(Some(if db.delete_reminder(rid).await? {
            format!("✅ Cancelled reminder #{}.", rid)
        } else {
            format!("⚠️ No reminder found with ID #{}.", rid)
        }));
    }

    // followups | send followup <id> [: custom msg] | skip followup <id> | snooze followup <id> [24h]
    if matches!(lower.as_str(), "followups" | "followup" | "follow-ups" | "follow ups") {
        let now = Utc::now().timestamp_millis();
        let _ = db.promote_due_followups(now).await;
        let open = db.list_followups(Some("open"), 15).await?;
        if open.is_empty() {
            return Ok(Some(
                "✅ No pending follow-ups right now. When you promise to get back to someone, they promise to get back to you, or an inquiry goes quiet, it'll appear here for your approval."
                    .to_string(),
            ));
        }
        let cards: Vec<String> = open.iter().map(format_followup_card).collect();
        return Ok(Some(format!(
            "📬 *Smart Follow-Up Queue ({})*:\n\n{}",
            open.len(),
            cards.join("\n\n")
        )));
    }
    if lower.starts_with("send followup ")
        || lower.starts_with("approve followup ")
        || lower.starts_with("send follow-up ")
    {
        let after_cmd = raw
            .splitn(3, ' ')
            .nth(2)
            .unwrap_or("")
            .trim();
        let (id_part, custom_msg) = match after_cmd.find(':') {
            Some(c) => (
                after_cmd[..c].trim().trim_start_matches('#'),
                Some(after_cmd[c + 1..].trim()),
            ),
            None => (after_cmd.trim().trim_start_matches('#'), None),
        };
        let Ok(fid) = id_part.parse::<i64>() else {
            return Ok(Some(
                "⚠️ Usage: `send followup <id>` or `send followup <id>: <custom message>`"
                    .to_string(),
            ));
        };
        let Some(f) = db.get_followup_by_id(fid).await? else {
            return Ok(Some(format!("⚠️ No follow-up found with ID #{}.", fid)));
        };
        let msg_to_send = custom_msg
            .filter(|s| !s.is_empty())
            .unwrap_or(&f.suggested_reply)
            .to_string();
        let now = Utc::now().timestamp_millis();
        let mut state = db
            .get_conversation(&f.sender)
            .await?
            .unwrap_or_else(|| ConversationState::new(&f.sender));
        state.history.push(ChatTurn {
            role: "assistant".to_string(),
            content: format!("[Falcon]: {}", msg_to_send),
        });
        if state.history.len() > HISTORY_MAX {
            let excess = state.history.len() - HISTORY_MAX;
            state.history.drain(0..excess);
        }
        state.bot_active = true;
        state.owner_read_unanswered = false;
        state.paused_until = 0;
        state.updated_at = now;
        db.save_conversation(&state).await?;
        let _ = db.update_followup_status(fid, "sent", None, now).await;
        queue_approved_outbound(db, &f.sender, &msg_to_send).await?;
        let who = f.contact_name.as_deref().unwrap_or(&f.sender);
        return Ok(Some(format!(
            "✅ Sent follow-up #{} to *{}* (+{}):\n\"{}\"",
            fid, who, f.sender, msg_to_send
        )));
    }
    if lower.starts_with("skip followup ")
        || lower.starts_with("dismiss followup ")
        || lower.starts_with("skip follow-up ")
    {
        let id_str = raw
            .split_whitespace()
            .last()
            .unwrap_or("")
            .trim_start_matches('#');
        let Ok(fid) = id_str.parse::<i64>() else {
            return Ok(Some("⚠️ Usage: `skip followup <id>`".to_string()));
        };
        let now = Utc::now().timestamp_millis();
        return Ok(Some(
            if db.update_followup_status(fid, "dismissed", None, now).await? {
                format!("✅ Dismissed follow-up #{}.", fid)
            } else {
                format!("⚠️ No follow-up found with ID #{}.", fid)
            },
        ));
    }
    if lower.starts_with("snooze followup ") || lower.starts_with("snooze follow-up ") {
        let tokens: Vec<&str> = raw.split_whitespace().collect();
        let id_str = tokens.get(2).copied().unwrap_or("").trim_start_matches('#');
        let Ok(fid) = id_str.parse::<i64>() else {
            return Ok(Some(
                "⚠️ Usage: `snooze followup <id> [24h|48h|4h]`".to_string(),
            ));
        };
        let mins = tokens
            .get(3)
            .and_then(|t| parse_duration_minutes(t))
            .unwrap_or(24 * 60);
        let now = Utc::now().timestamp_millis();
        let new_due = now + mins * 60_000;
        return Ok(Some(
            if db
                .update_followup_status(fid, "watching", Some(new_due), now)
                .await?
            {
                format!("⏳ Snoozed follow-up #{} for {} hours.", fid, (mins + 59) / 60)
            } else {
                format!("⚠️ No follow-up found with ID #{}.", fid)
            },
        ));
    }

    // block / unblock <numbers>
    if lower.starts_with("block ") || lower.starts_with("unblock ") {
        let (action, rest) = if let Some(r) = raw.strip_prefix("block ").or_else(|| raw.strip_prefix("BLOCK ")) {
            ("block", r)
        } else {
            let idx = raw.find(' ').unwrap_or(0);
            (
                if lower.starts_with("block ") { "block" } else { "unblock" },
                &raw[idx + 1..],
            )
        };
        let numbers: Vec<String> = rest
            .split(&[',', ';', '\n'][..])
            .map(normalize_number)
            .filter(|n| n.len() >= 7)
            .collect();
        if numbers.is_empty() {
            return Ok(None);
        }
        for n in &numbers {
            db.set_blocked(n, action == "block").await?;
            if action == "block" {
                db.delete_pending_reply(n).await?;
            }
        }
        let msg = if action == "block" {
            format!("✅ {} will ignore: {}", BOT_NAME, numbers.join(", "))
        } else {
            format!("✅ {} can reply again to: {}", BOT_NAME, numbers.join(", "))
        };
        return Ok(Some(msg));
    }

    if lower == "blocked" || lower == "blocklist" {
        let list = db.list_blocked().await?;
        let joined = if list.is_empty() {
            "none".to_string()
        } else {
            list.join(", ")
        };
        return Ok(Some(format!("✅ Ignored numbers ({}): {}", list.len(), joined)));
    }

    // approve <phone/name>: <reply> | reply <phone/name>: <reply> | say <phone/name>: <reply> | reject <phone/name>
    if lower == "approve" || lower == "reply" || lower == "say" || lower == "reject" {
        return Ok(Some(
            "⚠️ Usage: `approve <phone/name>: <reply to send>` or `reject <phone/name>`".to_string(),
        ));
    }
    if lower.starts_with("reject ") {
        let target_raw = raw["reject ".len()..].trim();
        let Some(num) = resolve_contact_target(db, target_raw).await? else {
            return Ok(Some(format!(
                "⚠️ Could not find a contact matching \"{}\". Usage: `reject 2348012345678`",
                target_raw
            )));
        };
        let now = Utc::now().timestamp_millis();
        let mut state = db
            .get_conversation(&num)
            .await?
            .unwrap_or_else(|| ConversationState::new(&num));
        state.bot_active = false;
        state.owner_read_unanswered = true;
        state.paused_until = now + cfg.takeover_pause_ms;
        state.updated_at = now;
        db.save_conversation(&state).await?;
        db.delete_pending_reply(&num).await?;
        let _ = db.delete_flags_for_chat(&num).await;
        let _ = db.ack_urgent_alerts(Some(&num)).await;
        return Ok(Some(format!(
            "✅ Rejected held request for +{}. {} stays paused in that chat so you can take over whenever ready.",
            num, BOT_NAME
        )));
    }
    if lower.starts_with("approve ") || lower.starts_with("reply ") || lower.starts_with("say ") {
        let idx = raw.find(' ').unwrap_or(0);
        let body = raw[idx + 1..].trim();
        let (target_part, custom_reply) = match body.find(':') {
            Some(c_idx) => (body[..c_idx].trim(), Some(body[c_idx + 1..].trim())),
            None => (body, None),
        };
        let Some(num) = resolve_contact_target(db, target_part).await? else {
            return Ok(Some(format!(
                "⚠️ Could not find a contact matching \"{}\". Usage: `approve 2348012345678: <message>`",
                target_part
            )));
        };
        let reply_to_send = match custom_reply.filter(|s| !s.is_empty()) {
            Some(r) => r.to_string(),
            None => {
                let latest_flag = db.get_latest_flag_for_chat(&num).await?;
                let suggested = latest_flag.and_then(|f| {
                    f.reason
                        .split("Suggested reply:")
                        .nth(1)
                        .map(|s| s.trim().to_string())
                        .filter(|s| !s.is_empty())
                });
                match suggested {
                    Some(s) => s,
                    None => {
                        return Ok(Some(format!(
                            "⚠️ Specify the message to send: `approve {}: <your reply>`",
                            num
                        )));
                    }
                }
            }
        };
        let now = Utc::now().timestamp_millis();
        let mut state = db
            .get_conversation(&num)
            .await?
            .unwrap_or_else(|| ConversationState::new(&num));
        state.history.push(ChatTurn {
            role: "assistant".to_string(),
            content: format!("[Falcon]: {}", reply_to_send),
        });
        if state.history.len() > HISTORY_MAX {
            let excess = state.history.len() - HISTORY_MAX;
            state.history.drain(0..excess);
        }
        state.owner_read_unanswered = false;
        state.paused_until = 0;
        state.bot_active = true;
        state.updated_at = now;
        db.save_conversation(&state).await?;
        db.delete_pending_reply(&num).await?;
        let _ = db.delete_flags_for_chat(&num).await;
        let _ = db.ack_urgent_alerts(Some(&num)).await;
        let _ = db.resolve_followups_for_sender(&num).await;
        queue_approved_outbound(db, &num, &reply_to_send).await?;
        return Ok(Some(format!(
            "✅ Approved & sent to +{}:\n\"{}\"",
            num, reply_to_send
        )));
    }

    // pause / resume <numbers or name>
    if lower == "pause" || lower == "resume" {
        return Ok(Some(format!(
            "⚠️ Usage: `{} <phone number>` (e.g. `{} 2348012345678`)",
            lower, lower
        )));
    }
    if lower.starts_with("pause ") || lower.starts_with("resume ") {
        let is_pause = lower.starts_with("pause ");
        let idx = raw.find(' ').unwrap_or(0);
        let rest = raw[idx + 1..].trim();
        let mut numbers: Vec<String> = rest
            .split(&[',', ';', '\n'][..])
            .map(normalize_number)
            .filter(|n| n.len() >= 7)
            .collect();
        if numbers.is_empty() && !rest.is_empty() {
            if let Ok(hits) = db.find_contact_memories(rest).await {
                numbers = hits.into_iter().map(|c| c.sender).collect();
            }
        }
        if numbers.is_empty() {
            return Ok(Some(format!(
                "⚠️ Could not find a phone number matching \"{}\". Usage: `{} 2348012345678`",
                rest,
                if is_pause { "pause" } else { "resume" }
            )));
        }
        let now = Utc::now().timestamp_millis();
        for n in &numbers {
            let mut state = db
                .get_conversation(n)
                .await?
                .unwrap_or_else(|| ConversationState::new(n));
            if is_pause {
                state.bot_active = false;
                state.owner_read_unanswered = true;
                state.paused_until = now + cfg.takeover_pause_ms;
                db.delete_pending_reply(n).await?;
            } else {
                state.paused_until = 0;
                state.owner_read_unanswered = false;
                state.bot_active = true;
            }
            state.updated_at = now;
            db.save_conversation(&state).await?;
        }
        let msg = if is_pause {
            format!("✅ Paused {} in chat(s): {}", BOT_NAME, numbers.join(", "))
        } else {
            format!("✅ Resumed {} in chat(s): {}", BOT_NAME, numbers.join(", "))
        };
        return Ok(Some(msg));
    }

    // remember [public|close|private] <fact> OR remember <number>: <fact>
    if lower == "remember" {
        return Ok(Some(
            "⚠️ Usage: `remember [public|close|private] <fact>` or `remember <phone>: <fact>`"
                .to_string(),
        ));
    }
    if lower.starts_with("remember ") {
        let body = raw["remember ".len()..].trim();
        if let Some(colon_idx) = body.find(':') {
            let prefix = body[..colon_idx].trim();
            let fact = body[colon_idx + 1..].trim();
            let num = normalize_number(prefix);
            if num.len() >= 7 && prefix.chars().all(|c| c.is_ascii_digit() || "+ -".contains(c)) && !fact.is_empty() {
                let mut cm = db
                    .get_contact_memory(&num)
                    .await?
                    .unwrap_or_else(|| ContactMemory::new(&num));
                cm.facts = merge_semantic_facts(&cm.facts, &[fact.to_string()], 25);
                cm.updated_at = Utc::now().timestamp_millis();
                db.save_contact_memory(&cm).await?;
                return Ok(Some(format!(
                    "✅ Saved to contact memory (+{}): \"{}\"",
                    num, fact
                )));
            }
        }
        let body_lower = body.to_lowercase();
        let (visibility, content) = if body_lower.starts_with("private ") {
            ("private_internal", body["private ".len()..].trim())
        } else if body_lower.starts_with("close ") {
            ("close", body["close ".len()..].trim())
        } else if body_lower.starts_with("public ") {
            ("public", body["public ".len()..].trim())
        } else {
            ("public", body)
        };
        let id = db
            .add_owner_memory_scoped("owner", visibility, content, "self_chat")
            .await?;
        return Ok(Some(format!(
            "✅ Stored in {} memory (#{}, {}): \"{}\"",
            BOT_NAME, id, visibility, content
        )));
    }

    // tidy memories / clean memories — deduplicate & supersede conflicting older owner facts
    if matches!(
        lower.as_str(),
        "tidy memories" | "clean memories" | "dedupe memories" | "tidy memory"
    ) {
        let superseded = db.tidy_owner_memory_deterministic().await?;
        let remaining = db.list_owner_memory(100).await?.len();
        return Ok(Some(format!(
            "🧹 *Memory Hygiene Complete*\n• Superseded conflicting/near-duplicate older facts: *{}*\n• Active owner facts remaining: *{}*",
            superseded, remaining
        )));
    }

    // learn <rule> / teach <rule>
    if lower == "learn" || lower == "teach" {
        return Ok(Some(
            "⚠️ Usage: `teach <rule>` or `learn <rule>` (e.g. `teach Always greet Dr. Ade formally`)"
                .to_string(),
        ));
    }
    if lower.starts_with("learn ") || lower.starts_with("teach ") {
        let idx = raw.find(' ').unwrap_or(0);
        let lesson = raw[idx + 1..].trim();
        if !lesson.is_empty() {
            let id = db.add_learning_episode(None, "owner_rule", lesson).await?;
            return Ok(Some(format!(
                "✅ {} learned (#{}): \"{}\"",
                BOT_NAME, id, lesson
            )));
        }
    }

    // memories [query] / profile [query]
    if lower == "memories"
        || lower == "profile"
        || lower.starts_with("memories ")
        || lower.starts_with("profile ")
    {
        let query = if let Some(idx) = raw.find(' ') {
            raw[idx + 1..].trim()
        } else {
            ""
        };
        if !query.is_empty() {
            let norm = normalize_number(query);
            let search = if norm.len() >= 7 { norm.as_str() } else { query };
            let hits = db.find_contact_memories(search).await?;
            if hits.is_empty() {
                return Ok(Some(format!("⚠️ No contact memory found for: {}", query)));
            }
            let lines: Vec<String> = hits
                .iter()
                .take(5)
                .map(|c| {
                    let name_part = c
                        .display_name
                        .as_deref()
                        .map(|n| format!(" ({})", n))
                        .unwrap_or_default();
                    let facts_str = if c.facts.is_empty() {
                        "none".to_string()
                    } else {
                        c.facts.join("; ")
                    };
                    let style_str = c
                        .owner_style_pattern
                        .as_deref()
                        .unwrap_or("observing");
                    format!(
                        "• +{}{}[{} | {}]\n  Pattern: {}\n  Facts: {}",
                        c.sender,
                        name_part,
                        c.relationship.as_deref().unwrap_or("unknown"),
                        c.tone.as_deref().unwrap_or("auto"),
                        style_str,
                        facts_str
                    )
                })
                .collect();
            return Ok(Some(format!(
                "✅ Contact profile ({}):\n{}",
                hits.len(),
                lines.join("\n")
            )));
        }

        let owner_list = db.list_owner_memory(15).await?;
        let lesson_list = db.list_learning_episodes(10).await?;
        let owner_fmt = if owner_list.is_empty() {
            "none yet".to_string()
        } else {
            owner_list
                .iter()
                .map(|o| format!("#{} [{}] {}", o.id, o.category, o.content))
                .collect::<Vec<_>>()
                .join("\n")
        };
        let lesson_fmt = if lesson_list.is_empty() {
            "none yet".to_string()
        } else {
            lesson_list
                .iter()
                .map(|l| format!("#{} {}", l.id, l.lesson))
                .collect::<Vec<_>>()
                .join("\n")
        };
        return Ok(Some(format!(
            "✅ *{} Memory*\nOwner facts ({}):\n{}\n\nLearned lessons ({}):\n{}",
            BOT_NAME,
            owner_list.len(),
            owner_fmt,
            lesson_list.len(),
            lesson_fmt
        )));
    }

    // forget <id or keyword>
    if lower.starts_with("forget ") {
        let q = raw["forget ".len()..].trim();
        let del_owner = db.delete_owner_memory(q).await?;
        let del_lesson = db.delete_learning_episodes(q).await?;
        return Ok(Some(format!(
            "✅ Removed {} memory item(s) matching \"{}\".",
            del_owner + del_lesson,
            q
        )));
    }

    // guard / unguard <group>
    if lower == "guard" || lower == "unguard" {
        return Ok(Some(format!(
            "⚠️ Usage: `{} <group name>` (or type `guarded` to list guarded groups)",
            lower
        )));
    }
    if lower.starts_with("guard ") || lower.starts_with("unguard ") {
        let is_guard = lower.starts_with("guard ");
        let idx = raw.find(' ').unwrap_or(0);
        let query = raw[idx + 1..].trim().to_lowercase();
        let groups = match fetch_groups().await {
            Ok(g) => g,
            Err(e) => {
                return Ok(Some(format!(
                    "⚠️ Could not fetch WhatsApp groups right now ({}). Try again in a few seconds.",
                    e
                )));
            }
        };
        let hits: Vec<&GroupSummary> = groups
            .iter()
            .filter(|g| g.subject.to_lowercase().contains(&query))
            .collect();
        if hits.is_empty() {
            return Ok(Some(format!("⚠️ No group found matching: {}", query)));
        }
        if hits.len() > 1 {
            let names: Vec<&str> = hits.iter().take(6).map(|g| g.subject.as_str()).collect();
            return Ok(Some(format!(
                "⚠️ Several groups match, be more specific: {}",
                names.join(", ")
            )));
        }
        let g = hits[0];
        if is_guard {
            db.set_guarded_group(&g.jid, &g.subject, "guarded").await?;
            return Ok(Some(format!(
                "✅ Now guarding: {}. Single non-Aurikrex links notify you; multiple links in <1m delete all & remove.",
                g.subject
            )));
        } else {
            db.set_guarded_group(&g.jid, &g.subject, "unguarded").await?;
            return Ok(Some(format!("✅ Stopped guarding: {}", g.subject)));
        }
    }

    if lower == "guarded" {
        let rows = db.list_guarded_groups().await?;
        let names = if rows.is_empty() {
            "Aurikrex Community auto-guarded".to_string()
        } else {
            rows.iter()
                .filter_map(|r| r.name.clone())
                .collect::<Vec<_>>()
                .join(", ")
        };
        return Ok(Some(format!(
            "✅ Explicitly guarded groups ({}, plus Aurikrex Community groups): {}",
            rows.len(),
            names
        )));
    }

    // allow / disallow <domain>
    if lower.starts_with("allow ") || lower.starts_with("disallow ") {
        let is_allow = lower.starts_with("allow ");
        let idx = raw.find(' ').unwrap_or(0);
        let site = raw[idx + 1..]
            .trim()
            .to_lowercase()
            .trim_start_matches("https://")
            .trim_start_matches("http://")
            .trim_start_matches("www.")
            .split('/')
            .next()
            .unwrap_or("")
            .to_string();
        if !site.contains('.') || site.contains(' ') {
            return Ok(None);
        }
        db.set_safe_domain(&site, is_allow).await?;
        return Ok(Some(if is_allow {
            format!("✅ Links to {} are now allowed in guarded groups", site)
        } else {
            format!("✅ Links to {} are no longer allowed", site)
        }));
    }

    if lower == "safelist" {
        let mut domains: Vec<String> = db.get_safe_domains().await?.into_iter().collect();
        domains.sort();
        return Ok(Some(format!("✅ Allowed link domains: {}", domains.join(", "))));
    }

    if lower == "mode everyone" || lower == "mode unsaved" {
        let mode = if lower.ends_with("unsaved") {
            "unsaved"
        } else {
            "everyone"
        };
        db.set_setting("reply_mode", mode).await?;
        return Ok(Some(if mode == "unsaved" {
            "✅ Now replying only to numbers NOT saved in your contacts.".to_string()
        } else {
            "✅ Now replying to everyone except blocked numbers (after 5-min wait if you have not replied).".to_string()
        }));
    }

    if lower == "bookings" {
        let rows = db.list_bookings(10).await?;
        let body = if rows.is_empty() {
            "none yet".to_string()
        } else {
            rows.iter()
                .map(|b| format!("{} | {} {} | {} | {}", b.name, b.subject, b.exam, b.slot, b.number))
                .collect::<Vec<_>>()
                .join("\n")
        };
        return Ok(Some(format!("✅ Latest bookings:\n{}", body)));
    }

    if lower == "flagged" {
        let rows = db.list_flags(10).await?;
        let body = if rows.is_empty() {
            "none yet".to_string()
        } else {
            rows.iter()
                .map(|f| {
                    let name_str = f
                        .name
                        .as_deref()
                        .map(|n| format!(" ({})", n))
                        .unwrap_or_default();
                    format!("{}{}: {}", f.chat, name_str, f.reason)
                })
                .collect::<Vec<_>>()
                .join("\n")
        };
        return Ok(Some(format!("✅ Chats needing you:\n{}", body)));
    }

    if lower == "status" {
        let now_ms = Utc::now().timestamp_millis();
        let _ = db.promote_due_followups(now_ms).await;
        let saved = db.count_saved_contacts().await?;
        let mode = get_reply_mode(db, &cfg.default_reply_mode).await?;
        let voice_mode = get_self_voice_mode(db).await?;
        let bookings = db.count_bookings().await?;
        let flags = db.count_flags().await?;
        let owner_mem = db.list_owner_memory(100).await?.len();
        let lessons = db.list_learning_episodes(100).await?.len();
        let avail = db.get_owner_status(now_ms).await?;
        let unacked = db.list_unacked_urgent_alerts().await?.len();
        let digest = db.list_pending_digest_items(50).await?.len();
        let rems = db.list_pending_reminders(50).await?.len();
        let followups = db.list_followups(Some("open"), 50).await?.len();
        let ntfy_state = if crate::config::normalize_ntfy_topic(&cfg.ntfy_topic, &cfg.ntfy_url).is_empty() {
            "OFF (set NTFY_TOPIC)"
        } else {
            "ready"
        };
        return Ok(Some(format!(
            "✅ {} online (Rust Core + Turso). Availability: {}. Reply mode: {}. Self-chat voice: {}. ntfy urgent push: {}. Active urgent alerts: {}. Digest queue (7am): {}. Follow-ups: {}. Reminders: {}. Saved contacts: {}. Bookings: {}. Flagged: {}. Memories: {} facts, {} lessons.",
            BOT_NAME, avail.mode, mode, voice_mode, ntfy_state, unacked, digest, followups, rems, saved, bookings, flags, owner_mem, lessons
        )));
    }

    if lower == "help" || lower == "commands" {
        return Ok(Some(format!(
            "✅ *{} Commands* (or just chat naturally like J.A.R.V.I.S.!):\n• ack [no/name] | test ntfy — stop or test urgent ring alerts\n• briefing | read me my briefing — view or hear pending 7:00 AM digest\n• voice [auto|on|off] — self-chat voice note reply mode\n• followups | send followup <id> [: msg] | snooze followup <id> | skip followup <id>\n• remind <30m|7pm|daily 8am|every mon 9am>: <text> | reminders | unremind <id>\n• busy/deep/sleep [30m|2h] [note] | available\n• vip/silent/normal <no/name> — set contact tier\n• approve/say <no/name>: <reply> | reject <no/name>\n• remember [public|close|private] <fact> | remember <no>: <fact> | tidy memories\n• teach <rule> | profile <no/name> | memories | forget <id/word>\n• pause/resume <no/name> | block/unblock <no> | blocked\n• guard/unguard <group> | guarded | allow/disallow <site> | safelist\n• mode everyone | mode unsaved | bookings | flagged | status",
            BOT_NAME
        )));
    }

    Ok(None)
}
