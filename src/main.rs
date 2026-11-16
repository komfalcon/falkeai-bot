pub mod brain;
pub mod commands;
pub mod config;
pub mod db;
pub mod ipc;
pub mod moderation;
pub mod util;

use anyhow::{anyhow, Context, Result};
use chrono::Utc;
use std::collections::{HashMap, HashSet};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot};
use tokio::task::AbortHandle;
use tracing::{error, info, warn};

use crate::brain::{
    call_llm_http, handle_customer_message, record_owner_read, record_owner_reply,
    HandleMessageResult, HISTORY_MAX,
};
use crate::commands::{get_reply_mode, is_self_chat, run_owner_command};
use crate::config::{Config, BOT_NAME};
use crate::db::{ChatTurn, Database, PendingReply};
use crate::ipc::{BridgeCommand, BridgeEvent, GroupSummary};
use crate::moderation::{
    moderate_group_message, CachedRoster, GroupMessageInput, ModerationOutcome, ModerationTracker,
};
use crate::util::{number_of_jid, split_bubbles};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DmActionStatus {
    PausedByOwner,
    RepliedImmediately(String),
    Scheduled { due_at: i64, messages_count: usize },
}

#[derive(Default)]
pub struct DmTakeoverScheduler {
    timers: Mutex<HashMap<String, AbortHandle>>,
}

impl DmTakeoverScheduler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn has_timer(&self, sender: &str) -> bool {
        self.timers
            .lock()
            .map(|m| m.contains_key(sender))
            .unwrap_or(false)
    }

    pub fn put_timer(&self, sender: &str, handle: AbortHandle) {
        if let Ok(mut map) = self.timers.lock() {
            if let Some(old) = map.insert(sender.to_string(), handle) {
                old.abort();
            }
        }
    }

    pub async fn cancel(&self, db: &Database, sender: &str) -> Result<()> {
        if let Ok(mut map) = self.timers.lock() {
            if let Some(handle) = map.remove(sender) {
                handle.abort();
            }
        }
        db.delete_pending_reply(sender).await?;
        Ok(())
    }
}

pub async fn execute_customer_reply<L, LFut, S, SFut, N, NFut>(
    db: &Database,
    cfg: &Config,
    sender: &str,
    remote_jid: &str,
    msg_id: &str,
    text: &str,
    aliases: &[String],
    now_ms: i64,
    llm_fn: L,
    mut send_fn: S,
    mut notify_owner_fn: N,
) -> Result<Option<HandleMessageResult>>
where
    L: FnOnce(Vec<ChatTurn>) -> LFut,
    LFut: std::future::Future<Output = Result<String>>,
    S: FnMut(String, String, String) -> SFut,
    SFut: std::future::Future<Output = Result<()>>,
    N: FnMut(String) -> NFut,
    NFut: std::future::Future<Output = Result<()>>,
{
    let existing_state = db.get_conversation(sender).await?;
    if let Some(ref state) = existing_state {
        if state.owner_read_unanswered || state.paused_until > now_ms {
            return Ok(None);
        }
    }

    let res = handle_customer_message(db, cfg, sender, text, aliases, now_ms, llm_fn).await?;

    send_fn(
        remote_jid.to_string(),
        msg_id.to_string(),
        res.reply.clone(),
    )
    .await?;

    if res.flagged {
        let updated = db.get_conversation(sender).await?;
        let display = match updated.and_then(|u| u.name) {
            Some(n) => format!("{} (+{})", n, sender),
            None => format!("+{}", sender),
        };
        let snippet: String = text.chars().take(220).collect();
        let alert = format!(
            "🔔 *{} Handoff*\nChat: {}\nReason: {}\nMessage: \"{}\"",
            BOT_NAME,
            display,
            res.flag_reason.as_deref().unwrap_or("Needs your attention"),
            snippet
        );
        notify_owner_fn(alert).await?;
    }

    Ok(Some(res))
}

pub async fn schedule_or_handle_dm<L, LFut, S, SFut, N, NFut>(
    scheduler: &DmTakeoverScheduler,
    db: &Database,
    cfg: &Config,
    sender: &str,
    remote_jid: &str,
    msg_id: &str,
    text: &str,
    aliases: &[String],
    wait_ms: i64,
    now_ms: i64,
    llm_fn: L,
    send_fn: S,
    notify_owner_fn: N,
) -> Result<DmActionStatus>
where
    L: FnOnce(Vec<ChatTurn>) -> LFut,
    LFut: std::future::Future<Output = Result<String>>,
    S: FnMut(String, String, String) -> SFut,
    SFut: std::future::Future<Output = Result<()>>,
    N: FnMut(String) -> NFut,
    NFut: std::future::Future<Output = Result<()>>,
{
    if let Some(mut state) = db.get_conversation(sender).await? {
        // 1) If Falcon opened the message without replying (chose not to reply on purpose),
        //    OR replied manually within the takeover pause window: record user message and stay silent.
        if state.owner_read_unanswered || state.paused_until > now_ms {
            state.history.push(ChatTurn {
                role: "user".to_string(),
                content: text.to_string(),
            });
            if state.history.len() > HISTORY_MAX {
                let excess = state.history.len() - HISTORY_MAX;
                state.history.drain(0..excess);
            }
            state.updated_at = now_ms;
            db.save_conversation(&state).await?;
            return Ok(DmActionStatus::PausedByOwner);
        }

        // 2) If FalkeAI is already actively handling this thread within the idle timeout
        let session_active =
            state.bot_active && (now_ms - state.updated_at < cfg.bot_session_idle_ms);
        if (session_active && !scheduler.has_timer(sender)) || wait_ms <= 0 {
            let res = execute_customer_reply(
                db,
                cfg,
                sender,
                remote_jid,
                msg_id,
                text,
                aliases,
                now_ms,
                llm_fn,
                send_fn,
                notify_owner_fn,
            )
            .await?;
            return Ok(DmActionStatus::RepliedImmediately(
                res.map(|r| r.reply).unwrap_or_default(),
            ));
        }
    } else if wait_ms <= 0 {
        let res = execute_customer_reply(
            db,
            cfg,
            sender,
            remote_jid,
            msg_id,
            text,
            aliases,
            now_ms,
            llm_fn,
            send_fn,
            notify_owner_fn,
        )
        .await?;
        return Ok(DmActionStatus::RepliedImmediately(
            res.map(|r| r.reply).unwrap_or_default(),
        ));
    }

    // 3) Otherwise, queue in pending_replies and wait 5 minutes for Falcon to reply first
    let existing = db.get_pending_reply(sender).await?;
    let first_msg_at = existing.as_ref().map(|e| e.first_msg_at).unwrap_or(now_ms);
    let due_at = existing
        .as_ref()
        .map(|e| e.due_at)
        .unwrap_or(now_ms + wait_ms);
    let mut messages = existing.map(|e| e.messages).unwrap_or_default();
    messages.push(text.to_string());
    let messages_count = messages.len();

    db.upsert_pending_reply(&PendingReply {
        sender: sender.to_string(),
        remote_jid: remote_jid.to_string(),
        first_msg_at,
        due_at,
        messages,
        last_msg_id: msg_id.to_string(),
    })
    .await?;

    Ok(DmActionStatus::Scheduled {
        due_at,
        messages_count,
    })
}

pub async fn flush_pending_reply<L, LFut, S, SFut, N, NFut>(
    scheduler: &DmTakeoverScheduler,
    db: &Database,
    cfg: &Config,
    sender: &str,
    aliases: &[String],
    now_ms: i64,
    llm_fn: L,
    send_fn: S,
    notify_owner_fn: N,
) -> Result<Option<HandleMessageResult>>
where
    L: FnOnce(Vec<ChatTurn>) -> LFut,
    LFut: std::future::Future<Output = Result<String>>,
    S: FnMut(String, String, String) -> SFut,
    SFut: std::future::Future<Output = Result<()>>,
    N: FnMut(String) -> NFut,
    NFut: std::future::Future<Output = Result<()>>,
{
    if let Ok(mut map) = scheduler.timers.lock() {
        map.remove(sender);
    }

    let Some(pending) = db.get_pending_reply(sender).await? else {
        return Ok(None);
    };
    db.delete_pending_reply(sender).await?;

    let combined = pending.messages.join("\n");
    execute_customer_reply(
        db,
        cfg,
        sender,
        &pending.remote_jid,
        &pending.last_msg_id,
        &combined,
        aliases,
        now_ms,
        llm_fn,
        send_fn,
        notify_owner_fn,
    )
    .await
}

pub async fn handle_owner_read(
    scheduler: &DmTakeoverScheduler,
    db: &Database,
    cfg: &Config,
    sender: &str,
    now_ms: i64,
) -> Result<crate::db::ConversationState> {
    scheduler.cancel(db, sender).await?;
    record_owner_read(db, cfg, sender, now_ms).await
}

// ---- Runtime Bridge Supervisor ----

struct AppContext {
    cfg: Config,
    db: Database,
    scheduler: Arc<DmTakeoverScheduler>,
    mod_tracker: Arc<ModerationTracker>,
    bridge_tx: mpsc::UnboundedSender<BridgeCommand>,
    self_identity: Mutex<(String, Option<String>)>,
    seen_ids: Mutex<HashSet<String>>,
    hourly_stamps: Mutex<HashMap<String, Vec<i64>>>,
    roster_waiters: Mutex<HashMap<String, oneshot::Sender<CachedRoster>>>,
    groups_waiters: Mutex<HashMap<String, oneshot::Sender<Vec<GroupSummary>>>>,
}

impl AppContext {
    fn send_cmd(&self, cmd: BridgeCommand) {
        let _ = self.bridge_tx.send(cmd);
    }

    async fn send_human_like(&self, chat_jid: &str, msg_id: &str, text: &str) {
        if !msg_id.is_empty() {
            self.send_cmd(BridgeCommand::MarkRead {
                chat_jid: chat_jid.to_string(),
                message_id: msg_id.to_string(),
                sender_jid: None,
            });
        }
        for part in split_bubbles(text, 4) {
            self.send_cmd(BridgeCommand::SendPresence {
                chat_jid: chat_jid.to_string(),
                state: "composing".to_string(),
            });
            let delay_ms = (600 + (part.len() as u64) * 25).min(3500);
            tokio::time::sleep(Duration::from_millis(delay_ms)).await;
            self.send_cmd(BridgeCommand::SendMessage {
                chat_jid: chat_jid.to_string(),
                text: part,
                mentions: vec![],
            });
        }
        self.send_cmd(BridgeCommand::SendPresence {
            chat_jid: chat_jid.to_string(),
            state: "paused".to_string(),
        });
    }

    fn notify_owner(&self, text: String) {
        let self_jid = self
            .self_identity
            .lock()
            .ok()
            .map(|g| g.0.clone())
            .unwrap_or_default();
        if !self_jid.is_empty() {
            self.send_cmd(BridgeCommand::SendMessage {
                chat_jid: self_jid,
                text,
                mentions: vec![],
            });
        }
    }

    fn over_hourly_limit(&self, number: &str, now_ms: i64) -> bool {
        let mut map = self.hourly_stamps.lock().unwrap();
        let stamps = map.entry(number.to_string()).or_default();
        stamps.retain(|&t| now_ms - t < 3_600_000);
        stamps.push(now_ms);
        stamps.len() > self.cfg.max_customer_msgs_per_hour
    }

    fn spawn_pending_timer(self: &Arc<Self>, sender: String, aliases: Vec<String>, due_at: i64) {
        if self.scheduler.has_timer(&sender) {
            return;
        }
        let now_ms = Utc::now().timestamp_millis();
        let delay_ms = (due_at - now_ms).max(0) as u64;
        let ctx = Arc::clone(self);
        let sender_clone = sender.clone();

        let join_handle = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(delay_ms)).await;
            let cfg_clone = ctx.cfg.clone();
            let ctx_send = Arc::clone(&ctx);
            let ctx_notify = Arc::clone(&ctx);
            let _ = flush_pending_reply(
                &ctx.scheduler,
                &ctx.db,
                &ctx.cfg,
                &sender_clone,
                &aliases,
                Utc::now().timestamp_millis(),
                |msgs| async move { call_llm_http(&cfg_clone, &msgs).await },
                move |jid, mid, reply| {
                    let c = Arc::clone(&ctx_send);
                    async move {
                        c.send_human_like(&jid, &mid, &reply).await;
                        Ok(())
                    }
                },
                move |alert| {
                    ctx_notify.notify_owner(alert);
                    async { Ok(()) }
                },
            )
            .await;
        });

        self.scheduler
            .put_timer(&sender, join_handle.abort_handle());
    }
}

async fn handle_bridge_event(ctx: Arc<AppContext>, event: BridgeEvent) -> Result<()> {
    match event {
        BridgeEvent::Connected { self_jid, self_lid } => {
            info!("WhatsApp connected ({} ready). JID: {}", BOT_NAME, self_jid);
            if let Ok(mut guard) = ctx.self_identity.lock() {
                *guard = (self_jid, self_lid);
            }
            // Snapshot WhatsApp session SQLite file into Turso so container restarts never lose login
            if let Ok(bytes) = std::fs::read("wa_session.db") {
                if !bytes.is_empty() {
                    let _ = ctx.db.save_session_blob("wa_session_db", &bytes).await;
                }
            }
            let ctx_snap = Arc::clone(&ctx);
            tokio::spawn(async move {
                for delay_secs in [5u64, 30, 120] {
                    tokio::time::sleep(Duration::from_secs(delay_secs)).await;
                    if let Ok(bytes) = std::fs::read("wa_session.db") {
                        if !bytes.is_empty() {
                            let _ = ctx_snap.db.save_session_blob("wa_session_db", &bytes).await;
                        }
                    }
                }
            });
            // Resume any pending 5-minute replies across restarts
            let pending_list = ctx.db.list_pending_replies().await?;
            let now_ms = Utc::now().timestamp_millis();
            for p in pending_list {
                if now_ms - p.first_msg_at > 30 * 60_000 {
                    ctx.db.delete_pending_reply(&p.sender).await?;
                } else {
                    ctx.spawn_pending_timer(p.sender.clone(), vec![p.sender], p.due_at);
                }
            }
        }
        BridgeEvent::LoggedOut => {
            error!("Logged out from WhatsApp. Clearing saved session in Turso for re-pairing.");
            let _ = ctx.db.clear_session_blob("wa_session_db").await;
            let _ = std::fs::remove_file("wa_session.db");
        }
        BridgeEvent::PairingCode { code } => {
            warn!(
                "\n==============================\nPAIRING CODE: {}\nWhatsApp > Settings > Linked devices > Link a device > Link with phone number instead\n==============================\n",
                code
            );
        }
        BridgeEvent::ContactsSync { contacts } => {
            for c in contacts {
                if c.name.trim().is_empty() {
                    continue;
                }
                for id_opt in [Some(c.jid.as_str()), c.lid.as_deref(), c.phone_number.as_deref()] {
                    if let Some(raw_id) = id_opt {
                        let num = number_of_jid(raw_id);
                        if !num.is_empty() {
                            let _ = ctx.db.upsert_saved_contact(&num, &c.name).await;
                        }
                    }
                }
            }
        }
        BridgeEvent::GroupRoster {
            request_id,
            group_jid,
            subject,
            description,
            linked_parent,
            participants,
        } => {
            let roster = CachedRoster::from_participants(
                Utc::now().timestamp_millis(),
                subject,
                description,
                linked_parent,
                &participants,
            );
            ctx.mod_tracker.put_cached_roster(&group_jid, roster.clone());
            if let Ok(mut waiters) = ctx.roster_waiters.lock() {
                if let Some(tx) = waiters.remove(&request_id) {
                    let _ = tx.send(roster);
                }
            }
        }
        BridgeEvent::GroupsList { request_id, groups } => {
            if let Ok(mut waiters) = ctx.groups_waiters.lock() {
                if let Some(tx) = waiters.remove(&request_id) {
                    let _ = tx.send(groups);
                }
            }
        }
        BridgeEvent::GroupInvalidate { group_jid } => {
            ctx.mod_tracker.invalidate_roster(Some(&group_jid));
        }
        BridgeEvent::ChatReadByOwner {
            chat_jid,
            chat_alt_jid,
        } => {
            if chat_jid.ends_with("@g.us")
                || chat_jid.ends_with("@broadcast")
                || chat_jid.ends_with("@newsletter")
            {
                return Ok(());
            }
            let (self_jid, self_lid) = ctx
                .self_identity
                .lock()
                .map(|g| g.clone())
                .unwrap_or_default();
            if is_self_chat(
                true,
                &self_jid,
                self_lid.as_deref(),
                &chat_jid,
                chat_alt_jid.as_deref(),
            ) {
                return Ok(());
            }
            let ids: Vec<String> = [Some(chat_jid.as_str()), chat_alt_jid.as_deref()]
                .into_iter()
                .flatten()
                .map(number_of_jid)
                .filter(|s| !s.is_empty())
                .collect();
            if let Some(number) = ids.first() {
                let _ = handle_owner_read(
                    &ctx.scheduler,
                    &ctx.db,
                    &ctx.cfg,
                    number,
                    Utc::now().timestamp_millis(),
                )
                .await;
            }
        }
        BridgeEvent::Error { message } => {
            error!("Bridge error: {}", message);
        }
        BridgeEvent::Message {
            id,
            chat_jid,
            chat_alt_jid,
            sender_jid,
            sender_alt_jid,
            push_name,
            from_me,
            is_group,
            timestamp,
            text,
            is_voice,
            matched_urls,
        } => {
            if chat_jid == "status@broadcast"
                || chat_jid.ends_with("@broadcast")
                || chat_jid.ends_with("@newsletter")
            {
                return Ok(());
            }

            let now_sec = Utc::now().timestamp();
            if timestamp > 0 && now_sec - timestamp > ctx.cfg.ignore_older_than_seconds {
                return Ok(());
            }

            {
                let mut seen = ctx.seen_ids.lock().unwrap();
                if !seen.insert(id.clone()) {
                    return Ok(());
                }
                if seen.len() > 5000 {
                    seen.clear();
                }
            }

            if from_me && sender_jid.ends_with("@lid") {
                if let Ok(mut guard) = ctx.self_identity.lock() {
                    if guard.1.as_deref().unwrap_or("").is_empty() {
                        guard.1 = Some(sender_jid.clone());
                    }
                }
            }

            let (self_jid, self_lid) = ctx
                .self_identity
                .lock()
                .map(|g| g.clone())
                .unwrap_or_default();

            // 1) Owner messages (self-chat commands OR manual DM replies)
            if from_me {
                let is_self_lid_dm = !is_group && !chat_jid.is_empty() && chat_jid == sender_jid;
                if is_self_lid_dm
                    || is_self_chat(
                        true,
                        &self_jid,
                        self_lid.as_deref(),
                        &chat_jid,
                        chat_alt_jid.as_deref(),
                    )
                {
                    if text.trim().is_empty() {
                        return Ok(());
                    }
                    let ctx_groups = Arc::clone(&ctx);
                    let reply = run_owner_command(&ctx.db, &ctx.cfg, &text, || {
                        let c = Arc::clone(&ctx_groups);
                        async move {
                            let req_id = format!("grp-{}", Utc::now().timestamp_millis());
                            let (tx, rx) = oneshot::channel();
                            c.groups_waiters.lock().unwrap().insert(req_id.clone(), tx);
                            c.send_cmd(BridgeCommand::FetchJoinedGroups { request_id: req_id });
                            tokio::time::timeout(Duration::from_secs(10), rx)
                                .await
                                .context("Timed out fetching joined groups")?
                                .context("Groups channel closed")
                        }
                    })
                    .await?;
                    if let Some(reply_text) = reply {
                        ctx.send_cmd(BridgeCommand::SendMessage {
                            chat_jid,
                            text: reply_text,
                            mentions: vec![],
                        });
                    }
                    return Ok(());
                }

                if !is_group {
                    let ids: Vec<String> = [Some(chat_jid.as_str()), chat_alt_jid.as_deref()]
                        .into_iter()
                        .flatten()
                        .map(number_of_jid)
                        .filter(|s| !s.is_empty())
                        .collect();
                    let Some(number) = ids.first().cloned() else {
                        return Ok(());
                    };
                    ctx.scheduler.cancel(&ctx.db, &number).await?;
                    let body = if text.trim().is_empty() {
                        "[Media/Voice message sent by Falcon]"
                    } else {
                        text.as_str()
                    };
                    let cfg_clone = ctx.cfg.clone();
                    let has_text = !text.trim().is_empty() && !cfg_clone.ai_key.is_empty();
                    let learn_cb = if has_text {
                        Some(|msgs: Vec<ChatTurn>| async move { call_llm_http(&cfg_clone, &msgs).await })
                    } else {
                        None
                    };
                    record_owner_reply(
                        &ctx.db,
                        &ctx.cfg,
                        &number,
                        body,
                        &ids,
                        Utc::now().timestamp_millis(),
                        learn_cb,
                    )
                    .await?;
                }
                return Ok(());
            }

            // 2) Group Moderation
            if is_group {
                let ctx_roster = Arc::clone(&ctx);
                let outcome = moderate_group_message(
                    &ctx.mod_tracker,
                    &ctx.db,
                    GroupMessageInput {
                        group_jid: &chat_jid,
                        msg_id: &id,
                        sender_jid: &sender_jid,
                        sender_alt_jid: sender_alt_jid.as_deref(),
                        text: &text,
                        matched_urls: &matched_urls,
                        now_ms: Utc::now().timestamp_millis(),
                    },
                    |grp| {
                        let c = Arc::clone(&ctx_roster);
                        async move {
                            let req_id = format!("rst-{}-{}", grp, Utc::now().timestamp_millis());
                            let (tx, rx) = oneshot::channel();
                            c.roster_waiters.lock().unwrap().insert(req_id.clone(), tx);
                            c.send_cmd(BridgeCommand::FetchGroupRoster {
                                request_id: req_id,
                                group_jid: grp,
                            });
                            tokio::time::timeout(Duration::from_secs(10), rx)
                                .await
                                .context("Timed out fetching group roster")?
                                .context("Roster channel closed")
                        }
                    },
                )
                .await?;

                match outcome {
                    ModerationOutcome::Notified { alert } => {
                        ctx.notify_owner(alert);
                    }
                    ModerationOutcome::Removed {
                        deleted_message_ids,
                        participant_jid,
                        alert,
                    } => {
                        for mid in deleted_message_ids {
                            ctx.send_cmd(BridgeCommand::DeleteMessage {
                                group_jid: chat_jid.clone(),
                                message_id: mid,
                                sender_jid: participant_jid.clone(),
                            });
                        }
                        ctx.send_cmd(BridgeCommand::RemoveParticipant {
                            group_jid: chat_jid,
                            participant_jid,
                        });
                        ctx.notify_owner(alert);
                    }
                    _ => {}
                }
                return Ok(());
            }

            // 3) Private Chats (5-minute takeover window + FalkeAI representation)
            let ids: Vec<String> = [
                Some(chat_jid.as_str()),
                chat_alt_jid.as_deref(),
                Some(sender_jid.as_str()),
                sender_alt_jid.as_deref(),
            ]
            .into_iter()
            .flatten()
            .map(number_of_jid)
            .filter(|s| !s.is_empty())
            .collect();
            let Some(number) = ids.first().cloned() else {
                return Ok(());
            };

            if let Some(pname) = push_name.as_deref().map(|s| s.trim()).filter(|s| !s.is_empty()) {
                if ctx.db.get_saved_contact_name(&ids).await.ok().flatten().is_none() {
                    let mut cm = ctx
                        .db
                        .get_contact_memory(&number)
                        .await?
                        .unwrap_or_else(|| crate::db::ContactMemory::new(&number));
                    if cm.display_name.is_none() {
                        cm.display_name = Some(pname.to_string());
                        let _ = ctx.db.save_contact_memory(&cm).await;
                    }
                }
            }

            if ctx.db.is_blocked(&ids).await? {
                return Ok(());
            }

            let mode = get_reply_mode(&ctx.db, &ctx.cfg.default_reply_mode).await?;
            if mode == "unsaved" {
                if ctx.db.count_saved_contacts().await? < 5 {
                    warn!("Reply mode is 'unsaved' but contacts have not synced yet. Staying silent.");
                    return Ok(());
                }
                if ctx.db.is_saved_contact(&ids).await? {
                    return Ok(());
                }
            }

            let now_ms = Utc::now().timestamp_millis();
            if ctx.over_hourly_limit(&number, now_ms) {
                return Ok(());
            }

            if text.trim().is_empty() && !is_voice {
                return Ok(());
            }

            let incoming_text = if is_voice {
                "[Voice note received - ask sender to type it out]".to_string()
            } else {
                text
            };

            let cfg_clone = ctx.cfg.clone();
            let ctx_send = Arc::clone(&ctx);
            let ctx_notify = Arc::clone(&ctx);
            let status = schedule_or_handle_dm(
                &ctx.scheduler,
                &ctx.db,
                &ctx.cfg,
                &number,
                &chat_jid,
                &id,
                &incoming_text,
                &ids,
                ctx.cfg.reply_wait_ms,
                now_ms,
                |msgs| async move { call_llm_http(&cfg_clone, &msgs).await },
                move |jid, mid, reply| {
                    let c = Arc::clone(&ctx_send);
                    async move {
                        c.send_human_like(&jid, &mid, &reply).await;
                        Ok(())
                    }
                },
                move |alert| {
                    ctx_notify.notify_owner(alert);
                    async { Ok(()) }
                },
            )
            .await?;

            if let DmActionStatus::Scheduled { due_at, .. } = status {
                ctx.spawn_pending_timer(number, ids, due_at);
            }
        }
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let cfg = Config::from_env();
    let db = Database::connect(&cfg.turso_url, cfg.turso_auth_token.as_deref()).await?;
    info!("Connected to Turso database ({} ready).", BOT_NAME);

    let scheduler = Arc::new(DmTakeoverScheduler::new());
    let mod_tracker = Arc::new(ModerationTracker::new());

    // Lightweight HTTP health listener for Azure App Service / Container Apps probes
    if cfg.health_port > 0 {
        let port = cfg.health_port;
        tokio::spawn(async move {
            if let Ok(listener) = tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
                info!("Azure health probe listening on 0.0.0.0:{}", port);
                while let Ok((mut stream, _)) = listener.accept().await {
                    tokio::spawn(async move {
                        let mut buf = [0u8; 512];
                        let _ = tokio::io::AsyncReadExt::read(&mut stream, &mut buf).await;
                        let body = r#"{"status":"ok","agent":"FalkeAI","up":1}"#;
                        let resp = format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                            body.len(),
                            body
                        );
                        let _ = stream.write_all(resp.as_bytes()).await;
                    });
                }
            }
        });
    }

    loop {
        // Restore WhatsApp login session from Turso if disk was wiped by a dyno restart
        if !std::path::Path::new("wa_session.db").exists() {
            if let Ok(Some(bytes)) = db.load_session_blob("wa_session_db").await {
                if std::fs::write("wa_session.db", &bytes).is_ok() {
                    info!("Restored WhatsApp session from Turso ({} bytes).", bytes.len());
                }
            }
        }

        info!("Starting WhatsApp bridge: {}", cfg.bridge_bin);
        let mut cmd = Command::new(&cfg.bridge_bin);
        cmd.env("PAIR_PHONE", &cfg.pair_phone)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());

        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => {
                error!(
                    "Failed to spawn bridge binary '{}': {}. Retrying in 10s...",
                    cfg.bridge_bin, e
                );
                tokio::time::sleep(Duration::from_secs(10)).await;
                continue;
            }
        };

        let stdin = child.stdin.take().ok_or_else(|| anyhow!("No child stdin"))?;
        let stdout = child.stdout.take().ok_or_else(|| anyhow!("No child stdout"))?;

        let (bridge_tx, mut bridge_rx) = mpsc::unbounded_channel::<BridgeCommand>();

        let writer_task = tokio::spawn(async move {
            let mut writer = stdin;
            while let Some(command) = bridge_rx.recv().await {
                if let Ok(line) = serde_json::to_string(&command) {
                    if writer.write_all(format!("{}\n", line).as_bytes()).await.is_err() {
                        break;
                    }
                    let _ = writer.flush().await;
                }
            }
        });

        let ctx = Arc::new(AppContext {
            cfg: cfg.clone(),
            db: db.clone(),
            scheduler: Arc::clone(&scheduler),
            mod_tracker: Arc::clone(&mod_tracker),
            bridge_tx,
            self_identity: Mutex::new((String::new(), None)),
            seen_ids: Mutex::new(HashSet::new()),
            hourly_stamps: Mutex::new(HashMap::new()),
            roster_waiters: Mutex::new(HashMap::new()),
            groups_waiters: Mutex::new(HashMap::new()),
        });

        let mut reader = BufReader::new(stdout).lines();
        while let Ok(Some(line)) = reader.next_line().await {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }
            match serde_json::from_str::<BridgeEvent>(trimmed) {
                Ok(event) => {
                    let c = Arc::clone(&ctx);
                    tokio::spawn(async move {
                        if let Err(err) = handle_bridge_event(c, event).await {
                            error!("Event handler error: {}", err);
                        }
                    });
                }
                Err(e) => {
                    warn!("Ignored non-IPC bridge output: {} ({})", trimmed, e);
                }
            }
        }

        writer_task.abort();
        let status = child.wait().await;
        warn!("WhatsApp bridge exited ({:?}). Restarting in 3s...", status);
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::brain::{build_system_prompt, normalize_exam, normalize_subject, validate_booking, BookingValidation};
    use crate::db::BookingDraft;
    use crate::ipc::GroupParticipant;
    use crate::moderation::{extract_hosts, is_safe_host};
    use crate::util::{lagos_parts, normalize_number, number_of_jid, split_bubbles};
    use chrono::TimeZone;
    use serde_json::json;

    fn test_cfg() -> Config {
        Config {
            turso_url: ":memory:".to_string(),
            turso_auth_token: None,
            pair_phone: "2349113683395".to_string(),
            ai_key: "test-key".to_string(),
            ai_base_url: "https://api.openai.com/v1".to_string(),
            ai_model: "gpt-4o".to_string(),
            default_reply_mode: "everyone".to_string(),
            reply_wait_ms: 300_000,
            takeover_pause_ms: 7_200_000,
            bot_session_idle_ms: 1_800_000,
            open_hour: 11,
            close_hour: 18,
            max_customer_msgs_per_hour: 40,
            ignore_older_than_seconds: 120,
            bridge_bin: "./wa-bridge".to_string(),
            health_port: 8080,
        }
    }

    fn good_booking() -> BookingDraft {
        BookingDraft {
            student_name: Some("Tunde".to_string()),
            subject: Some("physics".to_string()),
            exam: Some("jamb".to_string()),
            day: Some("Saturday".to_string()),
            time: Some("2pm".to_string()),
            start_hour_24: Some(14),
            contact_number: Some("08012345678".to_string()),
        }
    }

    #[test]
    fn test_normalisers_and_bubbles() {
        assert_eq!(normalize_subject("Further Maths"), Some("Maths"));
        assert_eq!(normalize_subject("Biology"), None);
        assert_eq!(normalize_exam("A Level"), Some("A-level"));
        assert_eq!(normalize_exam("university"), None);
        assert_eq!(normalize_number("0903 180 0503"), "2349031800503");
        assert_eq!(
            number_of_jid("2349031800503:7@s.whatsapp.net"),
            "2349031800503"
        );

        assert_eq!(
            split_bubbles("Maths? Good\n\nWhich exam?", 4),
            vec!["Maths? Good", "Which exam?"]
        );
        assert_eq!(
            split_bubbles("one line\nstill one bubble", 4),
            vec!["one line\nstill one bubble"]
        );
        assert_eq!(split_bubbles("a\n\nb\n\nc\n\nd\n\ne\n\nf", 4).len(), 4);
        assert!(split_bubbles("  \n\n ", 4).is_empty());
    }

    #[test]
    fn test_validate_booking_and_lagos_hours() {
        let b = good_booking();
        match validate_booking(&b, "2340000", 11, 18) {
            BookingValidation::Valid { subject, .. } => assert_eq!(subject, "Physics"),
            BookingValidation::Problem(p) => panic!("Unexpected problem: {}", p),
        }

        let mut bad_exam = b.clone();
        bad_exam.exam = Some("IELTS".to_string());
        match validate_booking(&bad_exam, "2340000", 11, 18) {
            BookingValidation::Problem(p) => assert!(p.contains("exam")),
            _ => panic!("Expected exam validation error"),
        }

        let mut late = b.clone();
        late.start_hour_24 = Some(19);
        match validate_booking(&late, "2340000", 11, 18) {
            BookingValidation::Problem(p) => assert!(p.contains("11am to 6pm")),
            _ => panic!("Expected out-of-hours error"),
        }

        let mut this_num = b.clone();
        this_num.contact_number = Some("this number".to_string());
        match validate_booking(&this_num, "2348011122233", 11, 18) {
            BookingValidation::Valid { number, .. } => assert_eq!(number, "2348011122233"),
            _ => panic!("Expected valid booking"),
        }

        let open_dt = Utc.with_ymd_and_hms(2026, 11, 12, 10, 30, 0).unwrap(); // 11:30 Lagos
        let closed_dt = Utc.with_ymd_and_hms(2026, 11, 12, 17, 30, 0).unwrap(); // 18:30 Lagos
        assert!(lagos_parts(open_dt, 11, 18).open);
        assert!(!lagos_parts(closed_dt, 11, 18).open);
    }

    #[tokio::test]
    async fn test_bookings_and_handoff_in_turso() {
        let db = Database::connect(":memory:", None).await.unwrap();
        let cfg = test_cfg();
        let b = good_booking();

        // Save booking once after confirmation, never twice
        let llm_resp = json!({
            "reply": "Booked.",
            "customer_name": "Tunde",
            "save_booking": true,
            "booking": b
        })
        .to_string();

        let r1 = handle_customer_message(&db, &cfg, "234801", "yes", &[], 1_000_000, |_| {
            let resp = llm_resp.clone();
            async move { Ok(resp) }
        })
        .await
        .unwrap();
        assert!(r1.booking_saved.is_some());
        assert_eq!(db.count_bookings().await.unwrap(), 1);

        let r2 = handle_customer_message(&db, &cfg, "234801", "yes", &[], 1_005_000, |_| {
            let resp = llm_resp.clone();
            async move { Ok(resp) }
        })
        .await
        .unwrap();
        assert!(r2.booking_saved.is_none());
        assert_eq!(db.count_bookings().await.unwrap(), 1);

        // Handoff uses "I'll leave this one for Falcon." and avoids repeating on "ok"
        let flag_resp = json!({
            "reply": "I'll leave this one for Falcon.",
            "flag_human": true,
            "flag_reason": "asked for custom pricing"
        })
        .to_string();

        let f1 = handle_customer_message(&db, &cfg, "234803", "how much?", &[], 2_000_000, |_| {
            let resp = flag_resp.clone();
            async move { Ok(resp) }
        })
        .await
        .unwrap();
        assert!(f1.flagged);
        assert_eq!(f1.reply, "I'll leave this one for Falcon.");
        assert_eq!(db.count_flags().await.unwrap(), 1);

        let f_ack = handle_customer_message(&db, &cfg, "234803", "ok", &[], 2_030_000, |_| {
            let resp = flag_resp.clone();
            async move { Ok(resp) }
        })
        .await
        .unwrap();
        assert!(!f_ack.flagged);
        assert_eq!(f_ack.reply, "Noted.");
        assert_eq!(db.count_flags().await.unwrap(), 1);
    }

    #[tokio::test]
    async fn test_3_layer_memory_compaction_and_learning() {
        let db = Database::connect(":memory:", None).await.unwrap();
        let cfg = test_cfg();

        let _ = handle_customer_message(
            &db,
            &cfg,
            "2348100001111",
            "Hey, it's Chidi from UNILAG Engineering",
            &[],
            1_000_000,
            |_| async move {
                Ok(json!({
                    "reply": "Good to hear from you, Chidi.",
                    "customer_name": "Chidi",
                    "memory_update": {
                        "relationship": "friend",
                        "tone": "casual",
                        "add_facts": ["Studies Engineering at UNILAG"],
                        "open_threads": ["Semester project"]
                    }
                })
                .to_string())
            },
        )
        .await
        .unwrap();

        let cm = db.get_contact_memory("2348100001111").await.unwrap().unwrap();
        assert_eq!(cm.display_name.as_deref(), Some("Chidi"));
        assert_eq!(cm.relationship.as_deref(), Some("friend"));
        assert_eq!(cm.facts, vec!["Studies Engineering at UNILAG"]);

        // Fill history to HISTORY_MAX and verify compaction on next turn
        let mut state = db.get_conversation("2348100001111").await.unwrap().unwrap();
        state.history = (0..HISTORY_MAX)
            .map(|i| ChatTurn {
                role: if i % 2 == 0 { "user" } else { "assistant" }.to_string(),
                content: format!("Turn {} key context", i + 1),
            })
            .collect();
        db.save_conversation(&state).await.unwrap();

        let _ = handle_customer_message(
            &db,
            &cfg,
            "2348100001111",
            "Pushing past 24 turns",
            &[],
            1_050_000,
            |_| async move { Ok(json!({ "reply": "Got it." }).to_string()) },
        )
        .await
        .unwrap();

        let cm_after = db.get_contact_memory("2348100001111").await.unwrap().unwrap();
        assert!(cm_after.summary.contains("Turn 1 key context"));

        // Falcon replies manually -> logs to history, pauses bot, and learns per-person chat pattern
        let learn_cb = Some(|_msgs: Vec<ChatTurn>| async move {
            Ok(json!({
                "lesson": "Keep replies brief and direct with engineering friends.",
                "owner_fact": "Bootcamp starts first Saturday of next month.",
                "contact_fact": "Joining the next bootcamp cohort",
                "relationship": "friend",
                "tone": "casual",
                "person_chat_pattern": "Playful UNILAG banter, uses 'ohh' and 'omo' at the end of short lines"
            })
            .to_string())
        });
        let updated_state = record_owner_reply(
            &db,
            &cfg,
            "2348100001111",
            "omo bootcamp starts first Saturday of next month ohh",
            &[],
            1_100_000,
            learn_cb,
        )
        .await
        .unwrap();

        assert!(!updated_state.bot_active);
        assert!(!updated_state.owner_read_unanswered);
        assert!(updated_state.paused_until > 1_100_000);
        assert!(updated_state.history.last().unwrap().content.contains("[Falcon]:"));

        let cm_learned = db.get_contact_memory("2348100001111").await.unwrap().unwrap();
        assert!(cm_learned
            .owner_style_pattern
            .as_deref()
            .unwrap_or("")
            .contains("ohh"));
        assert_eq!(
            cm_learned.sample_owner_replies,
            vec!["omo bootcamp starts first Saturday of next month ohh"]
        );

        let owner_mems = db.list_owner_memory(10).await.unwrap();
        let lessons = db.list_learning_episodes(10).await.unwrap();
        assert!(owner_mems.iter().any(|m| m.content.contains("Bootcamp")));
        assert!(lessons.iter().any(|l| l.lesson.contains("engineering friends")));

        let sys = build_system_prompt(
            &lagos_parts(Utc::now(), 11, 18),
            &updated_state,
            "2348100001111",
            Some(&cm_learned),
            &owner_mems,
            &lessons,
        );
        assert!(sys.contains("FalkeAI"));
        assert!(sys.contains("Bootcamp"));
        assert!(sys.contains("Falcon's chat pattern with this person (FOLLOW THIS PATTERN)"));
        assert!(sys.contains("Real examples of how Falcon texts this person"));
    }

    #[tokio::test]
    async fn test_5_minute_dm_takeover_intro_subsequent_and_owner_read_suppression() {
        let db = Database::connect(":memory:", None).await.unwrap();
        let cfg = test_cfg();
        let scheduler = DmTakeoverScheduler::new();
        let sent = Arc::new(Mutex::new(Vec::<String>::new()));
        let alerts = Arc::new(Mutex::new(Vec::<String>::new()));

        // 1) Incoming DM waits 5 minutes instead of replying immediately
        let st1 = schedule_or_handle_dm(
            &scheduler,
            &db,
            &cfg,
            "23480999",
            "23480999@s.whatsapp.net",
            "m1",
            "Hello Falcon",
            &[],
            300_000,
            1_000_000,
            |_| async { Ok("{}".to_string()) },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert_eq!(
            st1,
            DmActionStatus::Scheduled {
                due_at: 1_300_000,
                messages_count: 1
            }
        );

        // 2) Second message within 5 minutes batches into the same pending reply
        let st2 = schedule_or_handle_dm(
            &scheduler,
            &db,
            &cfg,
            "23480999",
            "23480999@s.whatsapp.net",
            "m2",
            "Can we talk about a partnership?",
            &[],
            300_000,
            1_020_000,
            |_| async { Ok("{}".to_string()) },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert_eq!(
            st2,
            DmActionStatus::Scheduled {
                due_at: 1_300_000,
                messages_count: 2
            }
        );

        // 3) 5 minutes pass without Falcon opening/replying -> flush_pending_reply fires.
        //    First of all it introduces itself with INTRO_MESSAGE, and since partnership requires Falcon personally,
        //    follows with "I'll leave this one for Falcon."
        let sent_c = Arc::clone(&sent);
        let alerts_c = Arc::clone(&alerts);
        let res = flush_pending_reply(
            &scheduler,
            &db,
            &cfg,
            "23480999",
            &[],
            1_300_000,
            |msgs| async move {
                assert!(msgs[0].content.contains("FIRST MESSAGE STEPPING IN FOR FALCON"));
                assert!(msgs.last().unwrap().content.contains("Hello Falcon\nCan we talk"));
                Ok(json!({
                    "reply": "Hey! FalkeAI here stepping in while Falcon's tied up. I've flagged your partnership note for him so he can look it over personally as soon as he's free.",
                    "flag_human": true,
                    "flag_reason": "Partnership request"
                })
                .to_string())
            },
            move |_, _, reply| {
                sent_c.lock().unwrap().push(reply);
                async { Ok(()) }
            },
            move |alert| {
                alerts_c.lock().unwrap().push(alert);
                async { Ok(()) }
            },
        )
        .await
        .unwrap()
        .unwrap();

        assert_eq!(
            res.reply,
            "Hey! FalkeAI here stepping in while Falcon's tied up. I've flagged your partnership note for him so he can look it over personally as soon as he's free."
        );
        assert_eq!(sent.lock().unwrap().len(), 1);
        assert_eq!(alerts.lock().unwrap().len(), 1);

        // 4) Subsequent message in the same active session: responds naturally WITHOUT introducing itself again
        let st_subsequent = schedule_or_handle_dm(
            &scheduler,
            &db,
            &cfg,
            "23480999",
            "23480999@s.whatsapp.net",
            "m3",
            "Also do you offer Physics for JAMB?",
            &[],
            300_000,
            1_330_000,
            |msgs| async move {
                assert!(msgs[0].content.contains("ACTIVE ONGOING CONVERSATION"));
                Ok(json!({
                    "reply": "Yes, we run Physics classes for JAMB between 11am and 6pm."
                })
                .to_string())
            },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert_eq!(
            st_subsequent,
            DmActionStatus::RepliedImmediately(
                "Yes, we run Physics classes for JAMB between 11am and 6pm.".to_string()
            )
        );

        // 5) When Falcon returns (sends a manual reply): stop responding automatically and let him take over
        let _ = record_owner_reply(
            &db,
            &cfg,
            "23480999",
            "Hey, I'm back now — let's discuss the partnership.",
            &[],
            1_360_000,
            None::<fn(Vec<ChatTurn>) -> std::future::Ready<Result<String>>>,
        )
        .await
        .unwrap();

        let st_after_falcon_returns = schedule_or_handle_dm(
            &scheduler,
            &db,
            &cfg,
            "23480999",
            "23480999@s.whatsapp.net",
            "m4",
            "Awesome, here is our deck",
            &[],
            300_000,
            1_370_000,
            |_| async { Ok("{}".to_string()) },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert_eq!(st_after_falcon_returns, DmActionStatus::PausedByOwner);

        // 6) Read-Without-Reply Suppression:
        //    Another contact ("23480777") sends a message, and Falcon OPENS/READS the message without replying.
        //    FalkeAI must cancel the 5-minute timer and NOT continue the conversation (because Falcon chose not to reply on purpose).
        let st_unread = schedule_or_handle_dm(
            &scheduler,
            &db,
            &cfg,
            "23480777",
            "23480777@s.whatsapp.net",
            "u1",
            "Hey Falcon, are you coming out tonight?",
            &[],
            300_000,
            2_000_000,
            |_| async { Ok("{}".to_string()) },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert_eq!(
            st_unread,
            DmActionStatus::Scheduled {
                due_at: 2_300_000,
                messages_count: 1
            }
        );

        // Falcon opens the chat at 2_060_000 (1 min later) and does NOT reply
        let read_state = handle_owner_read(&scheduler, &db, &cfg, "23480777", 2_060_000)
            .await
            .unwrap();
        assert!(!read_state.bot_active);
        assert!(read_state.owner_read_unanswered);
        assert!(db.get_pending_reply("23480777").await.unwrap().is_none());

        // Even if the 5-minute mark passes, nothing is sent
        let flushed = flush_pending_reply(
            &scheduler,
            &db,
            &cfg,
            "23480777",
            &[],
            2_300_000,
            |_| async { Ok(json!({ "reply": "Should never send" }).to_string()) },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert!(flushed.is_none());

        // Even if the person sends a follow-up message after Falcon left them on read, FalkeAI stays silent
        let st_followup = schedule_or_handle_dm(
            &scheduler,
            &db,
            &cfg,
            "23480777",
            "23480777@s.whatsapp.net",
            "u2",
            "Bro??",
            &[],
            300_000,
            2_400_000,
            |_| async { Ok("{}".to_string()) },
            |_, _, _| async { Ok(()) },
            |_| async { Ok(()) },
        )
        .await
        .unwrap();
        assert_eq!(st_followup, DmActionStatus::PausedByOwner);
    }

    #[tokio::test]
    async fn test_aurikrex_community_group_moderation() {
        let db = Database::connect(":memory:", None).await.unwrap();
        let tracker = ModerationTracker::new();

        let safe = db.get_safe_domains().await.unwrap();
        assert!(is_safe_host("aurikrex.tech", &safe));
        assert!(is_safe_host("aurikrex.com", &safe));
        assert!(is_safe_host("library.aurikrex.com", &safe));
        assert!(!is_safe_host("evilaurikrex.com", &safe));
        assert_eq!(
            extract_hosts("see aurikrex.tech and https://app.aurikrex.com/login!"),
            vec!["aurikrex.tech", "app.aurikrex.com"]
        );

        let participants = vec![
            GroupParticipant {
                jid: "111@lid".to_string(),
                lid: Some("111@lid".to_string()),
                phone_number: Some("2340001@s.whatsapp.net".to_string()),
                is_admin: true,
            },
            GroupParticipant {
                jid: "222@lid".to_string(),
                lid: Some("222@lid".to_string()),
                phone_number: Some("2340002@s.whatsapp.net".to_string()),
                is_admin: false,
            },
        ];

        let fetch_roster = |_: String| {
            let p = participants.clone();
            async move {
                Ok(CachedRoster::from_participants(
                    1_000_000,
                    "Aurikrex Community - Physics".to_string(),
                    String::new(),
                    None,
                    &p,
                ))
            }
        };

        // 1) Normal message & aurikrex.com / aurikrex.tech links from non-admin are allowed
        let o1 = moderate_group_message(
            &tracker,
            &db,
            GroupMessageInput {
                group_jid: "g@g.us",
                msg_id: "msg-1",
                sender_jid: "222@lid",
                sender_alt_jid: None,
                text: "Hello everyone",
                matched_urls: &[],
                now_ms: 1_000_000,
            },
            fetch_roster,
        )
        .await
        .unwrap();
        assert_eq!(o1, ModerationOutcome::NoLinks);

        let o2 = moderate_group_message(
            &tracker,
            &db,
            GroupMessageInput {
                group_jid: "g@g.us",
                msg_id: "msg-2",
                sender_jid: "222@lid",
                sender_alt_jid: None,
                text: "Check https://aurikrex.com/courses and aurikrex.tech",
                matched_urls: &[],
                now_ms: 1_005_000,
            },
            fetch_roster,
        )
        .await
        .unwrap();
        assert_eq!(o2, ModerationOutcome::SafeLinks);

        // 2) Admin posting any link is allowed
        let o3 = moderate_group_message(
            &tracker,
            &db,
            GroupMessageInput {
                group_jid: "g@g.us",
                msg_id: "msg-admin",
                sender_jid: "111@lid",
                sender_alt_jid: None,
                text: "https://external.xyz",
                matched_urls: &[],
                now_ms: 1_010_000,
            },
            fetch_roster,
        )
        .await
        .unwrap();
        assert_eq!(o3, ModerationOutcome::AdminSender);

        // 3) Single non-safelisted link from non-admin: notify Falcon ONLY (do NOT delete, do NOT remove)
        let o4 = moderate_group_message(
            &tracker,
            &db,
            GroupMessageInput {
                group_jid: "g@g.us",
                msg_id: "msg-link-1",
                sender_jid: "222@lid",
                sender_alt_jid: None,
                text: "Visit http://spam.xyz/one",
                matched_urls: &[],
                now_ms: 1_015_000,
            },
            fetch_roster,
        )
        .await
        .unwrap();
        match o4 {
            ModerationOutcome::Notified { alert } => assert!(alert.contains("Single Link Notice")),
            other => panic!("Expected Notified, got {:?}", other),
        }

        // 4) Second link from the same non-admin in < 1 minute:
        // Deletes ALL recent messages from that sender (msg-1, msg-2, msg-link-1, msg-link-2) and removes immediately
        let o5 = moderate_group_message(
            &tracker,
            &db,
            GroupMessageInput {
                group_jid: "g@g.us",
                msg_id: "msg-link-2",
                sender_jid: "222@lid",
                sender_alt_jid: None,
                text: "Also http://spam.xyz/two",
                matched_urls: &[],
                now_ms: 1_030_000,
            },
            fetch_roster,
        )
        .await
        .unwrap();
        match o5 {
            ModerationOutcome::Removed {
                deleted_message_ids,
                participant_jid,
                alert,
            } => {
                assert_eq!(participant_jid, "222@lid");
                assert!(deleted_message_ids.contains(&"msg-1".to_string()));
                assert!(deleted_message_ids.contains(&"msg-2".to_string()));
                assert!(deleted_message_ids.contains(&"msg-link-1".to_string()));
                assert!(deleted_message_ids.contains(&"msg-link-2".to_string()));
                assert!(alert.contains("Spammer Removed"));
            }
            other => panic!("Expected Removed, got {:?}", other),
        }
    }
}
