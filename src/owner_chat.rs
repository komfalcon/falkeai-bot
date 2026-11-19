//! Conversational J.A.R.V.I.S. Self-Chat Brain for Falcon ("Message yourself").
//! Lets Falcon chat naturally to query contacts/chats, send DMs, set availability modes,
//! configure VIP/silent tiers, create reminders, acknowledge urgent alerts, and teach
//! FalkeAI deeper details about himself via structured or interview-style chats.

use anyhow::Result;
use chrono::{DateTime, TimeZone, Utc};
use chrono_tz::Africa::Lagos;
use serde_json::Value;
use std::future::Future;

use crate::alerts::format_digest_message;
use crate::brain::{extract_json_object, HISTORY_MAX};
use crate::commands::resolve_contact_target;
use crate::config::{Config, BOT_NAME};
use crate::db::{ChatTurn, ConversationState, Database, OwnerStatus};
use crate::safety::redact_secrets;
use crate::util::lagos_parts;

pub const OWNER_SELF_CHAT_KEY: &str = "__owner_self_chat__";

#[derive(Debug, Clone)]
pub struct OwnerChatOutcome {
    pub reply: String,
    pub outbounds: Vec<(String, String)>,
}

fn looks_like_raw_json(s: &str) -> bool {
    let t = s.trim();
    if t.starts_with('{') || t.starts_with("```json") {
        return true;
    }
    let lower = t.to_lowercase();
    (lower.contains("\"actions\"") && lower.contains("\"reply\""))
        || lower.contains("\"save_owner_memory\"")
        || lower.contains("\"private_internal\"")
        || (lower.contains("\"type\":") && lower.contains("\"visibility\":"))
}

/// Extracts and decodes a single JSON string property (e.g. `"reply": "..."`) even from truncated JSON.
fn extract_json_string_field(raw: &str, field: &str) -> Option<String> {
    let key = format!("\"{}\"", field);
    let key_idx = raw.find(&key)?;
    let rest = &raw[key_idx + key.len()..];
    let colon_idx = rest.find(':')?;
    let after_colon = rest[colon_idx + 1..].trim_start();
    if !after_colon.starts_with('"') {
        return None;
    }
    let bytes = after_colon.as_bytes();
    let mut i = 1;
    let mut escaped = false;
    while i < bytes.len() {
        let b = bytes[i];
        if escaped {
            escaped = false;
        } else if b == b'\\' {
            escaped = true;
        } else if b == b'"' {
            let literal = &after_colon[..=i];
            return serde_json::from_str::<String>(literal).ok();
        }
        i += 1;
    }
    None
}

/// Salvages every complete `{...}` JSON object inside `"actions": [ ...` even if the array was truncated.
fn salvage_actions_array(raw: &str) -> Vec<Value> {
    let mut out = Vec::new();
    let Some(act_idx) = raw.find("\"actions\"") else {
        return out;
    };
    let rest = &raw[act_idx + "\"actions\"".len()..];
    let Some(bracket_idx) = rest.find('[') else {
        return out;
    };
    let slice = &rest[bracket_idx + 1..];
    let bytes = slice.as_bytes();
    let mut i = 0;
    let mut in_str = false;
    let mut escaped = false;
    let mut depth: i32 = 0;
    let mut obj_start: Option<usize> = None;

    while i < bytes.len() {
        let b = bytes[i];
        if in_str {
            if escaped {
                escaped = false;
            } else if b == b'\\' {
                escaped = true;
            } else if b == b'"' {
                in_str = false;
            }
        } else {
            match b {
                b'"' => in_str = true,
                b'{' => {
                    if depth == 0 {
                        obj_start = Some(i);
                    }
                    depth += 1;
                }
                b'}' => {
                    if depth > 0 {
                        depth -= 1;
                        if depth == 0 {
                            if let Some(start) = obj_start.take() {
                                if let Ok(v) = serde_json::from_str::<Value>(&slice[start..=i]) {
                                    if v.is_object() {
                                        out.push(v);
                                    }
                                }
                            }
                        }
                    }
                }
                b']' if depth == 0 => break,
                _ => {}
            }
        }
        i += 1;
    }
    out
}

/// Parses J.A.R.V.I.S. self-chat JSON strictly, salvaging partial replies & complete actions
/// on token truncation and guaranteeing raw JSON never leaks into WhatsApp.
pub fn parse_owner_chat_response(raw: &str) -> (String, Vec<Value>) {
    let candidate = extract_json_object(raw);
    if let Ok(parsed) = serde_json::from_str::<Value>(candidate) {
        if parsed.is_object() {
            let actions = parsed["actions"]
                .as_array()
                .cloned()
                .unwrap_or_default();
            let reply = parsed["reply"]
                .as_str()
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && !looks_like_raw_json(s));
            let clean_reply = reply.unwrap_or_else(|| {
                if !actions.is_empty() {
                    "Done — saved that for you.".to_string()
                } else {
                    "Got it.".to_string()
                }
            });
            return (clean_reply, actions);
        }
    }

    // Truncated or slightly malformed JSON: salvage `"reply"` and every completed action object
    let salvaged_reply = extract_json_string_field(raw, "reply")
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && !looks_like_raw_json(s));
    let salvaged_actions = salvage_actions_array(raw);

    if let Some(r) = salvaged_reply {
        return (r, salvaged_actions);
    }
    if !salvaged_actions.is_empty() {
        return (
            "Got it — saved what you shared. What else would you like to add?".to_string(),
            salvaged_actions,
        );
    }
    let trimmed = raw.trim();
    if !trimmed.is_empty() && !looks_like_raw_json(trimmed) {
        return (trimmed.to_string(), Vec::new());
    }
    (
        "Hit a brief formatting snag on my reply — send that again and I've got you.".to_string(),
        Vec::new(),
    )
}

pub async fn handle_owner_self_chat<F, Fut>(
    db: &Database,
    cfg: &Config,
    text: &str,
    now_ms: i64,
    llm_fn: F,
) -> Result<OwnerChatOutcome>
where
    F: FnOnce(Vec<ChatTurn>) -> Fut,
    Fut: Future<Output = Result<String>>,
{
    let now_utc: DateTime<Utc> = Utc
        .timestamp_millis_opt(now_ms)
        .single()
        .unwrap_or_else(Utc::now);
    let parts = lagos_parts(now_utc, cfg.open_hour, cfg.close_hour);

    let owner_status = db.get_owner_status(now_ms).await?;
    let unacked = db.list_unacked_urgent_alerts().await?;
    let digest_items = db.list_pending_digest_items(20).await?;
    let flags = db.list_flags(12).await?;
    let reminders = db.list_pending_reminders(10).await?;
    let contacts = db.find_contact_memories("").await?;
    let recent_convos = db.list_recent_conversations(8).await?;
    let owner_mem = db.list_owner_memory(30).await?;

    let mut ctx_blocks: Vec<String> = Vec::new();
    ctx_blocks.push(format!(
        "- Current Lagos time: {}\n- Falcon availability mode: {} (note: {})",
        parts.text,
        owner_status.mode,
        owner_status.note.as_deref().unwrap_or("none")
    ));

    if !unacked.is_empty() {
        let lines: Vec<String> = unacked
            .iter()
            .map(|a| format!("  * [ID {}] {}", a.id, a.summary))
            .collect();
        ctx_blocks.push(format!("Active unacknowledged urgent alerts:\n{}", lines.join("\n")));
    }

    if !digest_items.is_empty() {
        let lines: Vec<String> = digest_items
            .iter()
            .take(10)
            .map(|d| {
                format!(
                    "  * [{}] {} (+{}): {} — \"{}\"",
                    d.kind,
                    d.contact_name.as_deref().unwrap_or("Unknown"),
                    d.sender,
                    d.summary,
                    d.last_message.chars().take(80).collect::<String>()
                )
            })
            .collect();
        ctx_blocks.push(format!("Queued for 7:00 AM digest:\n{}", lines.join("\n")));
    }

    if !flags.is_empty() {
        let lines: Vec<String> = flags
            .iter()
            .take(8)
            .map(|f| {
                format!(
                    "  * {} (+{}): {} — \"{}\"",
                    f.name.as_deref().unwrap_or("Unknown"),
                    f.chat,
                    f.reason,
                    f.last_message.chars().take(80).collect::<String>()
                )
            })
            .collect();
        ctx_blocks.push(format!("Pending approval holds (flags):\n{}", lines.join("\n")));
    }

    if !reminders.is_empty() {
        let lines: Vec<String> = reminders
            .iter()
            .map(|r| {
                let dt = Utc
                    .timestamp_millis_opt(r.due_at)
                    .single()
                    .unwrap_or_else(Utc::now)
                    .with_timezone(&Lagos)
                    .format("%b %d %I:%M %p");
                format!("  * [{}] {}", dt, r.text)
            })
            .collect();
        ctx_blocks.push(format!("Upcoming reminders:\n{}", lines.join("\n")));
    }

    if !recent_convos.is_empty() {
        let lines: Vec<String> = recent_convos
            .iter()
            .map(|c| {
                let cm = contacts.iter().find(|m| m.sender == c.sender);
                let tier = cm.map(|m| m.tier.as_str()).unwrap_or("normal");
                let mood = cm
                    .and_then(|m| m.recent_emotions.last())
                    .map(|e| format!("{} ({})", e.emotion, e.intensity))
                    .unwrap_or_else(|| "neutral".to_string());
                let last_turns: Vec<String> = c
                    .history
                    .iter()
                    .rev()
                    .take(2)
                    .rev()
                    .map(|t| {
                        let role = if t.role == "user" { "Them" } else { "Bot/You" };
                        format!("{}: {}", role, t.content.chars().take(90).collect::<String>())
                    })
                    .collect();
                format!(
                    "  * {} (+{}) [tier: {} | mood: {}] -> {}",
                    c.name.as_deref().unwrap_or("Unknown"),
                    c.sender,
                    tier,
                    mood,
                    last_turns.join(" | ")
                )
            })
            .collect();
        ctx_blocks.push(format!("Recent WhatsApp DM activity:\n{}", lines.join("\n")));
    }

    if !owner_mem.is_empty() {
        let lines: Vec<String> = owner_mem
            .iter()
            .take(20)
            .map(|m| format!("  * [{} | {}] {}", m.category, m.visibility, m.content))
            .collect();
        ctx_blocks.push(format!("What you already know about Falcon:\n{}", lines.join("\n")));
    }

    let sys_prompt = format!(
        r#"You are {BOT_NAME}, speaking directly and privately with your owner, Falcon, inside his WhatsApp "Message yourself" chat.
Think J.A.R.V.I.S.: sharp, concise, witty when natural, completely loyal, zero fluff, zero robotic filler.

## What You Can Do Here
1. Answer questions about his WhatsApp activity, contacts, emotions, pending handoffs, bookings, or schedule using the live data below.
2. Execute real actions on his behalf by including items in `"actions"`:
   - Send a DM to a contact: `{{"type": "send_dm", "target": "phone or name", "message": "exact text to send"}}`
   - Update Falcon's availability: `{{"type": "set_availability", "mode": "available|busy|deep_work|sleeping", "duration_minutes": 120, "note": "optional note"}}`
   - Set contact tier: `{{"type": "set_contact_tier", "target": "phone or name", "tier": "vip|normal|silent"}}`
   - Save a durable fact about Falcon: `{{"type": "save_owner_memory", "category": "bio|projects|aurikrex|schedule|relationships|style|preferences", "visibility": "public|close|private_internal", "content": "concise fact"}}`
     * Use `public` for Aurikrex/public bio, `close` for friends/VIPs, `private_internal` for private guidance only for you and Falcon.
   - Add a reminder: `{{"type": "add_reminder", "in_minutes": 60, "text": "reminder text"}}`
   - Acknowledge active urgent alerts: `{{"type": "ack_alerts", "target": null}}`
   - Show full briefing: `{{"type": "briefing"}}`
3. If Falcon says he wants you to "interview" him or learn more about him, ask 1–2 thoughtful, crisp questions at a time (e.g. his current projects, communication boundaries, close people, daily rhythm, Aurikrex offerings), and save his answers concisely using `save_owner_memory` with the right visibility (bundle related points into at most 4 short `save_owner_memory` entries per turn so your JSON stays compact).
4. Never fabricate messages or facts that aren't in the live data below. Never expose API keys, tokens, or `NTFY_TOPIC`.

## Live Workspace Context
{}

## Output Format
Reply with ONE valid, compact JSON object only (no markdown fences):
{{
  "reply": "your direct J.A.R.V.I.S.-style message to Falcon",
  "actions": []
}}"#,
        ctx_blocks.join("\n\n")
    );

    let mut self_state = db
        .get_conversation(OWNER_SELF_CHAT_KEY)
        .await?
        .unwrap_or_else(|| ConversationState::new(OWNER_SELF_CHAT_KEY));
    // Strip any legacy malformed JSON assistant turns so they can never poison future context.
    self_state
        .history
        .retain(|t| !(t.role == "assistant" && looks_like_raw_json(&t.content)));

    let mut messages = Vec::with_capacity(self_state.history.len() + 2);
    messages.push(ChatTurn {
        role: "system".to_string(),
        content: sys_prompt,
    });
    messages.extend(self_state.history.iter().rev().take(12).rev().cloned());
    messages.push(ChatTurn {
        role: "user".to_string(),
        content: text.to_string(),
    });

    let raw = match llm_fn(messages).await {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!(
                "Owner self-chat AI call failed: {}",
                redact_secrets(&e.to_string(), cfg)
            );
            return Ok(OwnerChatOutcome {
                reply: "Hit a quick snag reaching the AI right now — fast commands like `status`, `briefing`, `ack`, `flagged`, and `help` still work immediately.".to_string(),
                outbounds: Vec::new(),
            });
        }
    };

    let (mut reply_text, actions) = parse_owner_chat_response(&raw);

    let mut outbounds: Vec<(String, String)> = Vec::new();
    let mut action_notes: Vec<String> = Vec::new();

    for act in actions.iter().take(6) {
        let kind = act["type"].as_str().unwrap_or("").trim().to_lowercase();
            match kind.as_str() {
                "send_dm" => {
                    let target = act["target"].as_str().unwrap_or("").trim();
                    let msg = act["message"].as_str().unwrap_or("").trim();
                    if !target.is_empty() && !msg.is_empty() {
                        if let Some(num) = resolve_contact_target(db, target).await? {
                            let mut st = db
                                .get_conversation(&num)
                                .await?
                                .unwrap_or_else(|| ConversationState::new(&num));
                            st.history.push(ChatTurn {
                                role: "assistant".to_string(),
                                content: format!("[Falcon]: {}", msg),
                            });
                            if st.history.len() > HISTORY_MAX {
                                let excess = st.history.len() - HISTORY_MAX;
                                st.history.drain(0..excess);
                            }
                            st.bot_active = false;
                            st.owner_read_unanswered = false;
                            st.paused_until = now_ms + cfg.takeover_pause_ms;
                            st.updated_at = now_ms;
                            db.save_conversation(&st).await?;
                            let _ = db.delete_flags_for_chat(&num).await;
                            outbounds.push((format!("{}@s.whatsapp.net", num), msg.to_string()));
                            action_notes.push(format!("Sent to +{}", num));
                        } else {
                            action_notes.push(format!("Couldn't resolve contact \"{}\"", target));
                        }
                    }
                }
                "set_availability" => {
                    let mode = match act["mode"].as_str().unwrap_or("available").trim().to_lowercase().as_str() {
                        "busy" => "busy",
                        "deep_work" | "deep" | "focus" => "deep_work",
                        "sleeping" | "sleep" => "sleeping",
                        _ => "available",
                    };
                    let mins = act["duration_minutes"].as_i64().unwrap_or(0).max(0);
                    let until_ms = if mode == "available" || mins == 0 {
                        0
                    } else {
                        now_ms + mins * 60_000
                    };
                    let note = act["note"]
                        .as_str()
                        .map(|s| s.trim().to_string())
                        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));
                    db.set_owner_status(&OwnerStatus {
                        mode: mode.to_string(),
                        until_ms,
                        note,
                    })
                    .await?;
                }
                "set_contact_tier" => {
                    let target = act["target"].as_str().unwrap_or("").trim();
                    let tier = act["tier"].as_str().unwrap_or("normal").trim();
                    if let Some(num) = resolve_contact_target(db, target).await? {
                        let cm = db.set_contact_tier(&num, tier).await?;
                        action_notes.push(format!("+{} -> tier: {}", num, cm.tier));
                    }
                }
                "save_owner_memory" => {
                    let cat = act["category"].as_str().unwrap_or("profile").trim();
                    let vis = act["visibility"].as_str().unwrap_or("public").trim();
                    let content = act["content"].as_str().unwrap_or("").trim();
                    if !content.is_empty() {
                        let _ = db
                            .add_owner_memory_scoped(cat, vis, content, "self_chat_ai")
                            .await?;
                    }
                }
                "add_reminder" => {
                    let in_mins = act["in_minutes"].as_i64().unwrap_or(0);
                    let r_text = act["text"].as_str().unwrap_or("").trim();
                    if in_mins > 0 && !r_text.is_empty() {
                        let due_at = now_ms + in_mins * 60_000;
                        let _ = db.add_reminder(due_at, r_text, now_ms).await?;
                    }
                }
                "ack_alerts" => {
                    let target = act["target"]
                        .as_str()
                        .map(|s| s.trim())
                        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));
                    let _ = db.ack_urgent_alerts(target).await?;
                }
                "briefing" => {
                    let items = db.list_pending_digest_items(30).await?;
                    let alerts = db.list_unacked_urgent_alerts().await?;
                    let rems = db.list_pending_reminders(10).await?;
                    let digest = format_digest_message(
                        &items,
                        &alerts,
                        &rems,
                        &format!("📋 *{} On-Demand Briefing*", BOT_NAME),
                    );
                    reply_text = digest;
                }
                _ => {}
            }
    }

    if !action_notes.is_empty() {
        reply_text = format!("{}\n\n_({})_", reply_text, action_notes.join(" · "));
    }
    reply_text = redact_secrets(&reply_text, cfg);

    self_state.history.push(ChatTurn {
        role: "user".to_string(),
        content: text.to_string(),
    });
    self_state.history.push(ChatTurn {
        role: "assistant".to_string(),
        content: reply_text.clone(),
    });
    if self_state.history.len() > 16 {
        let excess = self_state.history.len() - 16;
        self_state.history.drain(0..excess);
    }
    self_state.updated_at = now_ms;
    db.save_conversation(&self_state).await?;

    Ok(OwnerChatOutcome {
        reply: reply_text,
        outbounds,
    })
}
