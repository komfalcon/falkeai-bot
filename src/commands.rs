use anyhow::Result;
use chrono::Utc;

use crate::brain::merge_unique_strings;
use crate::config::{Config, BOT_NAME};
use crate::db::{ContactMemory, ConversationState, Database};
use crate::ipc::GroupSummary;
use crate::util::{normalize_number, number_of_jid};

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

    // pause / resume <numbers>
    if lower.starts_with("pause ") || lower.starts_with("resume ") {
        let is_pause = lower.starts_with("pause ");
        let idx = raw.find(' ').unwrap_or(0);
        let rest = &raw[idx + 1..];
        let numbers: Vec<String> = rest
            .split(&[',', ';', '\n'][..])
            .map(normalize_number)
            .filter(|n| n.len() >= 7)
            .collect();
        if numbers.is_empty() {
            return Ok(None);
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

    // remember <number>: <fact> OR remember <fact>
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
                cm.facts = merge_unique_strings(&cm.facts, &[fact.to_string()], 25);
                cm.updated_at = Utc::now().timestamp_millis();
                db.save_contact_memory(&cm).await?;
                return Ok(Some(format!(
                    "✅ Saved to contact memory (+{}): \"{}\"",
                    num, fact
                )));
            }
        }
        let id = db.add_owner_memory("owner", body, "self_chat").await?;
        return Ok(Some(format!(
            "✅ Stored in {} memory (#{}): \"{}\"",
            BOT_NAME, id, body
        )));
    }

    // learn <rule>
    if lower.starts_with("learn ") {
        let lesson = raw["learn ".len()..].trim();
        if !lesson.is_empty() {
            let id = db.add_learning_episode(None, "owner_rule", lesson).await?;
            return Ok(Some(format!(
                "✅ {} learned (#{}): \"{}\"",
                BOT_NAME, id, lesson
            )));
        }
    }

    // memories [query]
    if lower == "memories" || lower.starts_with("memories ") {
        let query = if lower.len() > "memories".len() {
            raw["memories".len()..].trim()
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
                "✅ Contact memories ({}):\n{}",
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
    if lower.starts_with("guard ") || lower.starts_with("unguard ") {
        let is_guard = lower.starts_with("guard ");
        let idx = raw.find(' ').unwrap_or(0);
        let query = raw[idx + 1..].trim().to_lowercase();
        let groups = fetch_groups().await?;
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
        let saved = db.count_saved_contacts().await?;
        let mode = get_reply_mode(db, &cfg.default_reply_mode).await?;
        let bookings = db.count_bookings().await?;
        let flags = db.count_flags().await?;
        let owner_mem = db.list_owner_memory(100).await?.len();
        let lessons = db.list_learning_episodes(100).await?.len();
        return Ok(Some(format!(
            "✅ {} online (Rust Core + Turso). Reply mode: {}. Saved contacts: {}. Bookings: {}. Flagged chats: {}. Memories: {} facts, {} lessons.",
            BOT_NAME, mode, saved, bookings, flags, owner_mem, lessons
        )));
    }

    if lower == "help" || lower == "commands" {
        return Ok(Some(format!(
            "✅ *{} Commands*:\n• remember <fact> | remember <no>: <fact>\n• learn <rule> | memories [no/name] | forget <id/word>\n• pause <no> | resume <no>\n• block <no> | unblock <no> | blocked\n• guard <group> | unguard <group> | guarded\n• allow <site> | disallow <site> | safelist\n• mode everyone | mode unsaved\n• bookings | flagged | status",
            BOT_NAME
        )));
    }

    Ok(None)
}
