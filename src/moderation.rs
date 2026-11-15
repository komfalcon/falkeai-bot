use anyhow::Result;
use regex::Regex;
use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};

use crate::db::Database;
use crate::ipc::GroupParticipant;
use crate::util::number_of_jid;

pub const LINK_WINDOW_MS: i64 = 60_000; // "less than a minute"
const RECENT_MSG_WINDOW_MS: i64 = 120_000; // 2-minute buffer of all messages from each sender
const ROSTER_TTL_MS: i64 = 10 * 60_000;

fn url_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(
            r"(?i)(?:https?://|www\.)[^\s]+|\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|co|ng|xyz|top|site|link|me|ly|app|info|biz|ru|cc|tk|ml|gq|cf|ga|click|live|shop|online|store|vip|club|gl|page|dev|ai|tech|icu|pw|cloud|win|bet|fun|best|work|today|world|space|website|ink)\b(?:/[^\s]*)?",
        )
        .expect("Valid URL regex")
    })
}

pub fn extract_hosts(text: &str) -> Vec<String> {
    let mut hosts = Vec::new();
    for m in url_regex().find_iter(text) {
        let token = m
            .as_str()
            .to_lowercase()
            .trim_start_matches("https://")
            .trim_start_matches("http://")
            .trim_start_matches("www.")
            .to_string();
        let host_part = token.split(&['/', '?', '#', ':'][..]).next().unwrap_or("");
        let clean = host_part.trim_matches(&['.', ',', ')', '!'][..]);
        if !clean.is_empty() {
            hosts.push(clean.to_string());
        }
    }
    hosts
}

pub fn is_safe_host(host: &str, safe_domains: &HashSet<String>) -> bool {
    let h = host.to_lowercase();
    safe_domains.iter().any(|d| {
        let dom = d.to_lowercase();
        h == dom || h.ends_with(&format!(".{}", dom))
    })
}

#[derive(Debug, Clone)]
pub struct CachedRoster {
    pub at_ms: i64,
    pub subject: String,
    pub description: String,
    pub linked_parent: Option<String>,
    pub admins: HashSet<String>,
    pub members: HashSet<String>,
}

impl CachedRoster {
    pub fn from_participants(
        at_ms: i64,
        subject: String,
        description: String,
        linked_parent: Option<String>,
        participants: &[GroupParticipant],
    ) -> Self {
        let mut admins = HashSet::new();
        let mut members = HashSet::new();
        for p in participants {
            let ids: Vec<String> = [
                Some(p.jid.as_str()),
                p.lid.as_deref(),
                p.phone_number.as_deref(),
            ]
            .into_iter()
            .flatten()
            .map(number_of_jid)
            .filter(|s| !s.is_empty())
            .collect();

            for id in &ids {
                members.insert(id.clone());
                if p.is_admin {
                    admins.insert(id.clone());
                }
            }
        }
        Self {
            at_ms,
            subject,
            description,
            linked_parent,
            admins,
            members,
        }
    }

    pub fn is_aurikrex_community(&self) -> bool {
        self.subject.to_lowercase().contains("aurikrex")
            || self.description.to_lowercase().contains("aurikrex")
    }
}

#[derive(Debug, Clone)]
struct RecentMsgEntry {
    at_ms: i64,
    msg_id: String,
}

#[derive(Debug, Clone)]
struct RecentLinkEntry {
    at_ms: i64,
    count: usize,
    msg_id: String,
    links: Vec<String>,
}

#[derive(Default)]
pub struct ModerationTracker {
    rosters: Mutex<HashMap<String, CachedRoster>>,
    recent_messages: Mutex<HashMap<String, Vec<RecentMsgEntry>>>,
    recent_links: Mutex<HashMap<String, Vec<RecentLinkEntry>>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ModerationOutcome {
    GroupNotGuarded,
    RosterUnavailable,
    NoSender,
    SenderUnverified,
    AdminSender,
    NoLinks,
    SafeLinks,
    Notified {
        alert: String,
    },
    Removed {
        deleted_message_ids: Vec<String>,
        participant_jid: String,
        alert: String,
    },
}

impl ModerationTracker {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn invalidate_roster(&self, group_jid: Option<&str>) {
        if let Ok(mut map) = self.rosters.lock() {
            if let Some(g) = group_jid {
                map.remove(g);
            } else {
                map.clear();
            }
        }
    }

    pub fn get_cached_roster(&self, group_jid: &str, now_ms: i64) -> Option<CachedRoster> {
        let map = self.rosters.lock().ok()?;
        let r = map.get(group_jid)?;
        if now_ms - r.at_ms < ROSTER_TTL_MS {
            Some(r.clone())
        } else {
            None
        }
    }

    pub fn put_cached_roster(&self, group_jid: &str, roster: CachedRoster) {
        if let Ok(mut map) = self.rosters.lock() {
            map.insert(group_jid.to_string(), roster);
        }
    }
}

pub async fn is_group_moderated(
    db: &Database,
    group_jid: &str,
    roster: &CachedRoster,
) -> Result<bool> {
    if let Some(doc) = db.get_guarded_group(group_jid).await? {
        return Ok(doc.mode != "unguarded");
    }
    if roster.is_aurikrex_community() {
        return Ok(true);
    }
    if let Some(ref parent) = roster.linked_parent {
        if let Some(parent_doc) = db.get_guarded_group(parent).await? {
            if parent_doc.mode != "unguarded" {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

pub struct GroupMessageInput<'a> {
    pub group_jid: &'a str,
    pub msg_id: &'a str,
    pub sender_jid: &'a str,
    pub sender_alt_jid: Option<&'a str>,
    pub text: &'a str,
    pub matched_urls: &'a [String],
    pub now_ms: i64,
}

pub async fn moderate_group_message<F, Fut>(
    tracker: &ModerationTracker,
    db: &Database,
    input: GroupMessageInput<'_>,
    mut fetch_roster: F,
) -> Result<ModerationOutcome>
where
    F: FnMut(String) -> Fut,
    Fut: std::future::Future<Output = Result<CachedRoster>>,
{
    if let Some(explicit) = db.get_guarded_group(input.group_jid).await? {
        if explicit.mode == "unguarded" {
            return Ok(ModerationOutcome::GroupNotGuarded);
        }
    }

    let sender_ids: Vec<String> = [Some(input.sender_jid), input.sender_alt_jid]
        .into_iter()
        .flatten()
        .map(number_of_jid)
        .filter(|s| !s.is_empty())
        .collect();

    if input.sender_jid.is_empty() || sender_ids.is_empty() {
        return Ok(ModerationOutcome::NoSender);
    }

    let (mut roster, mut was_fresh) = match tracker.get_cached_roster(input.group_jid, input.now_ms) {
        Some(r) => (r, false),
        None => match fetch_roster(input.group_jid.to_string()).await {
            Ok(r) => {
                tracker.put_cached_roster(input.group_jid, r.clone());
                (r, true)
            }
            Err(_) => return Ok(ModerationOutcome::RosterUnavailable),
        },
    };

    if !is_group_moderated(db, input.group_jid, &roster).await? {
        return Ok(ModerationOutcome::GroupNotGuarded);
    }

    // If sender is not in cached roster, refresh once so newly joined spammers cannot slip through
    if !sender_ids.iter().any(|id| roster.members.contains(id)) && !was_fresh {
        if let Ok(fresh_roster) = fetch_roster(input.group_jid.to_string()).await {
            tracker.put_cached_roster(input.group_jid, fresh_roster.clone());
            roster = fresh_roster;
            was_fresh = true;
        }
    }
    let _ = was_fresh;

    if !sender_ids.iter().any(|id| roster.members.contains(id)) {
        return Ok(ModerationOutcome::SenderUnverified);
    }
    if sender_ids.iter().any(|id| roster.admins.contains(id)) {
        return Ok(ModerationOutcome::AdminSender);
    }

    let sender_key = sender_ids[0].clone();
    let rate_key = format!("{}:{}", input.group_jid, sender_key);

    // Record all recent messages from this non-admin so we can delete ALL their messages if they send multiple links
    let mut msg_history = {
        let mut map = tracker.recent_messages.lock().unwrap();
        let list = map.entry(rate_key.clone()).or_default();
        list.retain(|m| input.now_ms - m.at_ms < RECENT_MSG_WINDOW_MS);
        if !input.msg_id.is_empty() && !list.iter().any(|m| m.msg_id == input.msg_id) {
            list.push(RecentMsgEntry {
                at_ms: input.now_ms,
                msg_id: input.msg_id.to_string(),
            });
        }
        if list.len() > 25 {
            let excess = list.len() - 25;
            list.drain(0..excess);
        }
        list.clone()
    };

    let combined_text = if input.matched_urls.is_empty() {
        input.text.to_string()
    } else {
        format!("{} {}", input.text, input.matched_urls.join(" "))
    };
    let hosts = extract_hosts(&combined_text);
    if hosts.is_empty() {
        return Ok(ModerationOutcome::NoLinks);
    }

    let safe_domains = db.get_safe_domains().await?;
    let bad: Vec<String> = hosts
        .into_iter()
        .filter(|h| !is_safe_host(h, &safe_domains))
        .collect();
    if bad.is_empty() {
        return Ok(ModerationOutcome::SafeLinks);
    }

    // Track non-safelisted link activity inside the 60-second ("less than a minute") window
    let link_window = {
        let mut map = tracker.recent_links.lock().unwrap();
        let list = map.entry(rate_key.clone()).or_default();
        list.retain(|e| input.now_ms - e.at_ms < LINK_WINDOW_MS);
        list.push(RecentLinkEntry {
            at_ms: input.now_ms,
            count: bad.len(),
            msg_id: input.msg_id.to_string(),
            links: bad.clone(),
        });
        list.clone()
    };

    let total_link_msgs = link_window.len();
    let total_bad_links: usize = link_window.iter().map(|e| e.count).sum();
    let is_multiple_under_minute = total_link_msgs >= 2 || total_bad_links >= 2;

    if !is_multiple_under_minute {
        let snippet: String = input.text.chars().take(200).collect();
        let alert = format!(
            "⚠️ *Single Link Notice* ({})\nFrom: +{}\nLink: {}\nMessage: \"{}\"\n(Message kept; another link within 60s will delete all their messages and remove them.)",
            roster.subject, sender_key, bad[0], snippet
        );
        db.insert_mod_log(
            input.group_jid,
            &roster.subject,
            &sender_key,
            "notified",
            "single_link",
            "ok",
            "none",
            &bad,
        )
        .await?;
        return Ok(ModerationOutcome::Notified { alert });
    }

    // Multiple links in < 1 minute: collect ALL recent message IDs from this sender in this group to delete for everyone
    let mut ids_to_delete = Vec::new();
    let mut seen_ids = HashSet::new();
    for m in msg_history.drain(..) {
        if !m.msg_id.is_empty() && seen_ids.insert(m.msg_id.clone()) {
            ids_to_delete.push(m.msg_id);
        }
    }
    for l in &link_window {
        if !l.msg_id.is_empty() && seen_ids.insert(l.msg_id.clone()) {
            ids_to_delete.push(l.msg_id.clone());
        }
    }
    if !input.msg_id.is_empty() && seen_ids.insert(input.msg_id.to_string()) {
        ids_to_delete.push(input.msg_id.to_string());
    }

    if let Ok(mut map) = tracker.recent_links.lock() {
        map.remove(&rate_key);
    }
    if let Ok(mut map) = tracker.recent_messages.lock() {
        map.remove(&rate_key);
    }

    let mut all_bad_links = Vec::new();
    for entry in &link_window {
        for l in &entry.links {
            if !all_bad_links.contains(l) && all_bad_links.len() < 5 {
                all_bad_links.push(l.clone());
            }
        }
    }

    let alert = format!(
        "🚨 *Spammer Removed* ({})\nMember: +{}\nReason: Multiple links in under 1 minute ({})\nMessages deleted for everyone: {}",
        roster.subject,
        sender_key,
        all_bad_links.join(", "),
        ids_to_delete.len()
    );

    db.insert_mod_log(
        input.group_jid,
        &roster.subject,
        &sender_key,
        "removed",
        "multiple_links_under_1m",
        "ok",
        &format!("ok ({})", ids_to_delete.len()),
        &all_bad_links,
    )
    .await?;

    Ok(ModerationOutcome::Removed {
        deleted_message_ids: ids_to_delete,
        participant_jid: input.sender_jid.to_string(),
        alert,
    })
}
