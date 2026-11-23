use anyhow::{anyhow, Context, Result};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::config::DEFAULT_SAFE_DOMAINS;

const SCHEMA_STATEMENTS: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS auth_state (
        id TEXT PRIMARY KEY,
        value TEXT NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS conversations (
        sender TEXT PRIMARY KEY,
        name TEXT,
        draft_json TEXT NOT NULL DEFAULT '{}',
        urgent_draft_json TEXT NOT NULL DEFAULT '{}',
        history_json TEXT NOT NULL DEFAULT '[]',
        last_booking_key TEXT,
        bot_active INTEGER NOT NULL DEFAULT 0,
        owner_read_unanswered INTEGER NOT NULL DEFAULT 0,
        paused_until INTEGER NOT NULL DEFAULT 0,
        last_flagged_at INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS pending_replies (
        sender TEXT PRIMARY KEY,
        remote_jid TEXT NOT NULL,
        first_msg_at INTEGER NOT NULL,
        due_at INTEGER NOT NULL,
        messages_json TEXT NOT NULL DEFAULT '[]',
        last_msg_id TEXT NOT NULL DEFAULT ''
    )",
    "CREATE TABLE IF NOT EXISTS contact_memory (
        sender TEXT PRIMARY KEY,
        display_name TEXT,
        aliases_json TEXT NOT NULL DEFAULT '[]',
        relationship TEXT,
        tone TEXT,
        tier TEXT NOT NULL DEFAULT 'normal',
        recent_emotions_json TEXT NOT NULL DEFAULT '[]',
        owner_style_pattern TEXT,
        sample_owner_replies_json TEXT NOT NULL DEFAULT '[]',
        summary TEXT NOT NULL DEFAULT '',
        facts_json TEXT NOT NULL DEFAULT '[]',
        open_threads_json TEXT NOT NULL DEFAULT '[]',
        interaction_count INTEGER NOT NULL DEFAULT 0,
        last_seen_at INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL DEFAULT 0
    )",
    "CREATE TABLE IF NOT EXISTS owner_memory (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        category TEXT NOT NULL DEFAULT 'general',
        visibility TEXT NOT NULL DEFAULT 'public',
        content TEXT NOT NULL,
        source TEXT NOT NULL DEFAULT 'owner',
        active INTEGER NOT NULL DEFAULT 1,
        superseded_by INTEGER,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS learning_episodes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT,
        kind TEXT NOT NULL DEFAULT 'lesson',
        lesson TEXT NOT NULL,
        created_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS bookings (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT NOT NULL,
        chat TEXT NOT NULL,
        name TEXT NOT NULL,
        subject TEXT NOT NULL,
        exam TEXT NOT NULL,
        slot TEXT NOT NULL,
        number TEXT NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS flags (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT NOT NULL,
        chat TEXT NOT NULL,
        name TEXT,
        reason TEXT NOT NULL,
        last_message TEXT
    )",
    "CREATE TABLE IF NOT EXISTS blocked (
        id TEXT PRIMARY KEY,
        at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS guarded_groups (
        id TEXT PRIMARY KEY,
        name TEXT,
        mode TEXT NOT NULL DEFAULT 'guarded',
        updated_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS safe_domains (
        id TEXT PRIMARY KEY,
        at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS strikes (
        id TEXT PRIMARY KEY,
        count INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS mod_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        at INTEGER NOT NULL,
        group_jid TEXT NOT NULL,
        group_name TEXT,
        sender TEXT NOT NULL,
        action TEXT NOT NULL,
        reason TEXT,
        result TEXT,
        deleted TEXT,
        links_json TEXT
    )",
    "CREATE TABLE IF NOT EXISTS saved_contacts (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS settings (
        id TEXT PRIMARY KEY,
        value TEXT NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS urgent_alerts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT NOT NULL,
        contact_name TEXT,
        phone TEXT NOT NULL,
        request TEXT NOT NULL,
        deadline TEXT NOT NULL DEFAULT '',
        summary TEXT NOT NULL DEFAULT '',
        created_at INTEGER NOT NULL,
        last_sent_at INTEGER NOT NULL DEFAULT 0,
        attempts INTEGER NOT NULL DEFAULT 0,
        acknowledged INTEGER NOT NULL DEFAULT 0
    )",
    "CREATE TABLE IF NOT EXISTS digest_queue (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT NOT NULL,
        contact_name TEXT,
        kind TEXT NOT NULL DEFAULT 'handoff',
        summary TEXT NOT NULL,
        last_message TEXT NOT NULL DEFAULT '',
        created_at INTEGER NOT NULL,
        delivered INTEGER NOT NULL DEFAULT 0
    )",
    "CREATE TABLE IF NOT EXISTS reminders (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        due_at INTEGER NOT NULL,
        text TEXT NOT NULL,
        recurrence TEXT,
        delivered INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS followups (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT NOT NULL,
        contact_name TEXT,
        kind TEXT NOT NULL DEFAULT 'cold_lead',
        reason TEXT NOT NULL,
        suggested_reply TEXT NOT NULL,
        due_at INTEGER NOT NULL,
        status TEXT NOT NULL DEFAULT 'watching',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS email_items (
        msg_id TEXT PRIMARY KEY,
        account_label TEXT NOT NULL,
        thread_id TEXT NOT NULL DEFAULT '',
        sender TEXT NOT NULL,
        subject TEXT NOT NULL,
        snippet TEXT NOT NULL DEFAULT '',
        category TEXT NOT NULL DEFAULT 'direct_human',
        priority TEXT NOT NULL DEFAULT 'important',
        summary TEXT NOT NULL DEFAULT '',
        received_at INTEGER NOT NULL,
        alerted INTEGER NOT NULL DEFAULT 0,
        digest_delivered INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS scheduled_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        target TEXT NOT NULL,
        contact_name TEXT,
        channel TEXT NOT NULL DEFAULT 'whatsapp',
        message TEXT NOT NULL,
        due_at INTEGER NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS bank_alerts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        source TEXT NOT NULL DEFAULT 'android',
        bank TEXT NOT NULL,
        direction TEXT NOT NULL DEFAULT 'credit',
        amount_minor INTEGER NOT NULL DEFAULT 0,
        currency TEXT NOT NULL DEFAULT 'NGN',
        counterparty TEXT NOT NULL DEFAULT '',
        raw_summary TEXT NOT NULL,
        created_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS phone_devices (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_name TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE,
        active INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL,
        last_seen_at INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS phone_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        event_type TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at INTEGER NOT NULL
    )",
];

#[derive(Debug, Clone)]
pub enum SqlParam {
    Null,
    Int(i64),
    Text(String),
}

impl From<&str> for SqlParam {
    fn from(s: &str) -> Self {
        SqlParam::Text(s.to_string())
    }
}
impl From<String> for SqlParam {
    fn from(s: String) -> Self {
        SqlParam::Text(s)
    }
}
impl From<Option<String>> for SqlParam {
    fn from(opt: Option<String>) -> Self {
        match opt {
            Some(s) => SqlParam::Text(s),
            None => SqlParam::Null,
        }
    }
}
impl From<i64> for SqlParam {
    fn from(v: i64) -> Self {
        SqlParam::Int(v)
    }
}

#[derive(Debug, Clone, Default)]
pub struct QueryResult {
    pub rows: Vec<HashMap<String, Value>>,
    pub rows_affected: u64,
    pub last_insert_rowid: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatTurn {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct BookingDraft {
    pub student_name: Option<String>,
    pub subject: Option<String>,
    pub exam: Option<String>,
    pub day: Option<String>,
    pub time: Option<String>,
    pub start_hour_24: Option<i64>,
    pub contact_number: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct UrgentDraft {
    #[serde(default)]
    pub active: bool,
    pub name: Option<String>,
    pub number: Option<String>,
    pub request: Option<String>,
    pub deadline: Option<String>,
    #[serde(default)]
    pub started_at: i64,
    #[serde(default)]
    pub turns: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct EmotionEntry {
    pub emotion: String,
    pub intensity: String,
    #[serde(default)]
    pub subtext: String,
    #[serde(default)]
    pub at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UrgentAlertRecord {
    pub id: i64,
    pub sender: String,
    pub contact_name: Option<String>,
    pub phone: String,
    pub request: String,
    pub deadline: String,
    pub summary: String,
    pub created_at: i64,
    pub last_sent_at: i64,
    pub attempts: i64,
    pub acknowledged: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DigestItem {
    pub id: i64,
    pub sender: String,
    pub contact_name: Option<String>,
    pub kind: String,
    pub summary: String,
    pub last_message: String,
    pub created_at: i64,
    pub delivered: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReminderRecord {
    pub id: i64,
    pub due_at: i64,
    pub text: String,
    #[serde(default)]
    pub recurrence: Option<String>,
    pub delivered: bool,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FollowupRecord {
    pub id: i64,
    pub sender: String,
    pub contact_name: Option<String>,
    /// "falcon_promised" | "waiting_on_them" | "cold_lead"
    pub kind: String,
    pub reason: String,
    pub suggested_reply: String,
    pub due_at: i64,
    /// "watching" | "ready" | "sent" | "dismissed"
    pub status: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct OwnerStatus {
    /// "available" | "busy" | "deep_work" | "sleeping"
    pub mode: String,
    pub until_ms: i64,
    pub note: Option<String>,
}

impl OwnerStatus {
    pub fn available() -> Self {
        Self {
            mode: "available".to_string(),
            until_ms: 0,
            note: None,
        }
    }

    pub fn is_unavailable(&self, now_ms: i64) -> bool {
        self.mode != "available" && !self.mode.is_empty() && (self.until_ms == 0 || now_ms < self.until_ms)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationState {
    pub sender: String,
    pub name: Option<String>,
    pub draft: BookingDraft,
    #[serde(default)]
    pub urgent_draft: UrgentDraft,
    pub history: Vec<ChatTurn>,
    pub last_booking_key: Option<String>,
    pub bot_active: bool,
    #[serde(default)]
    pub owner_read_unanswered: bool,
    pub paused_until: i64,
    pub last_flagged_at: i64,
    pub updated_at: i64,
}

impl ConversationState {
    pub fn new(sender: &str) -> Self {
        Self {
            sender: sender.to_string(),
            name: None,
            draft: BookingDraft::default(),
            urgent_draft: UrgentDraft::default(),
            history: Vec::new(),
            last_booking_key: None,
            bot_active: false,
            owner_read_unanswered: false,
            paused_until: 0,
            last_flagged_at: 0,
            updated_at: Utc::now().timestamp_millis(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingReply {
    pub sender: String,
    pub remote_jid: String,
    pub first_msg_at: i64,
    pub due_at: i64,
    pub messages: Vec<String>,
    pub last_msg_id: String,
}

fn default_tier() -> String {
    "normal".to_string()
}

fn default_visibility() -> String {
    "public".to_string()
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactMemory {
    pub sender: String,
    pub display_name: Option<String>,
    pub aliases: Vec<String>,
    pub relationship: Option<String>,
    pub tone: Option<String>,
    #[serde(default = "default_tier")]
    pub tier: String,
    #[serde(default)]
    pub recent_emotions: Vec<EmotionEntry>,
    #[serde(default)]
    pub owner_style_pattern: Option<String>,
    #[serde(default)]
    pub sample_owner_replies: Vec<String>,
    pub summary: String,
    pub facts: Vec<String>,
    pub open_threads: Vec<String>,
    pub interaction_count: i64,
    pub last_seen_at: i64,
    pub updated_at: i64,
}

impl ContactMemory {
    pub fn new(sender: &str) -> Self {
        let now = Utc::now().timestamp_millis();
        Self {
            sender: sender.to_string(),
            display_name: None,
            aliases: Vec::new(),
            relationship: None,
            tone: None,
            tier: "normal".to_string(),
            recent_emotions: Vec::new(),
            owner_style_pattern: None,
            sample_owner_replies: Vec::new(),
            summary: String::new(),
            facts: Vec::new(),
            open_threads: Vec::new(),
            interaction_count: 0,
            last_seen_at: now,
            updated_at: now,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OwnerMemoryItem {
    pub id: i64,
    pub category: String,
    #[serde(default = "default_visibility")]
    pub visibility: String,
    pub content: String,
    pub source: String,
    #[serde(default = "default_true")]
    pub active: bool,
    #[serde(default)]
    pub superseded_by: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LearningEpisode {
    pub id: i64,
    pub sender: Option<String>,
    pub kind: String,
    pub lesson: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BookingRecord {
    pub name: String,
    pub subject: String,
    pub exam: String,
    pub slot: String,
    pub number: String,
    pub timestamp: String,
    pub chat: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlagRecord {
    pub timestamp: String,
    pub chat: String,
    pub name: Option<String>,
    pub reason: String,
    pub last_message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GuardedGroup {
    pub id: String,
    pub name: Option<String>,
    pub mode: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmailItemRecord {
    pub msg_id: String,
    pub account_label: String,
    pub thread_id: String,
    pub sender: String,
    pub subject: String,
    pub snippet: String,
    /// "interview_career" | "payments_finance" | "infra_alerts" | "direct_human" | "low_noise"
    pub category: String,
    /// "urgent" | "important" | "low"
    pub priority: String,
    pub summary: String,
    pub received_at: i64,
    pub alerted: bool,
    pub digest_delivered: bool,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduledMessageRecord {
    pub id: i64,
    pub target: String,
    pub contact_name: Option<String>,
    /// "whatsapp" | "sms"
    pub channel: String,
    pub message: String,
    pub due_at: i64,
    /// "pending" | "sent" | "cancelled" | "failed"
    pub status: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BankAlertRecord {
    pub id: i64,
    pub source: String,
    pub bank: String,
    /// "credit" | "debit"
    pub direction: String,
    /// Minor units (kobo for NGN, cents for USD)
    pub amount_minor: i64,
    pub currency: String,
    pub counterparty: String,
    pub raw_summary: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhoneDeviceRecord {
    pub id: i64,
    pub device_name: String,
    pub token_hash: String,
    pub active: bool,
    pub created_at: i64,
    pub last_seen_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhoneEventRecord {
    pub id: i64,
    pub event_type: String,
    pub payload_json: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PhoneTelemetrySnapshot {
    pub battery_pct: i64,
    pub is_charging: bool,
    pub dnd_active: bool,
    pub bt_connected: bool,
    #[serde(default)]
    pub calendar_upcoming: Vec<String>,
    #[serde(default)]
    pub recent_notifications: Vec<String>,
    #[serde(default)]
    pub low_battery_cover: bool,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallerBriefContext {
    pub phone: String,
    pub display_name: Option<String>,
    pub relationship: Option<String>,
    pub tier: String,
    pub summary_line: String,
    pub open_followups: Vec<String>,
    pub bookings: Vec<String>,
    pub last_chat_snippet: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct LocalStoreData {
    conversations: HashMap<String, ConversationState>,
    pending_replies: HashMap<String, PendingReply>,
    contact_memory: HashMap<String, ContactMemory>,
    owner_memory: Vec<OwnerMemoryItem>,
    learning_episodes: Vec<LearningEpisode>,
    bookings: Vec<BookingRecord>,
    flags: Vec<FlagRecord>,
    blocked: HashMap<String, i64>,
    guarded_groups: HashMap<String, GuardedGroup>,
    safe_domains: HashSet<String>,
    saved_contacts: HashMap<String, String>,
    settings: HashMap<String, String>,
    auth_blobs: HashMap<String, String>,
    #[serde(default)]
    urgent_alerts: Vec<UrgentAlertRecord>,
    #[serde(default)]
    digest_queue: Vec<DigestItem>,
    #[serde(default)]
    reminders: Vec<ReminderRecord>,
    #[serde(default)]
    followups: Vec<FollowupRecord>,
    #[serde(default)]
    email_items: HashMap<String, EmailItemRecord>,
    #[serde(default)]
    scheduled_messages: Vec<ScheduledMessageRecord>,
    #[serde(default)]
    bank_alerts: Vec<BankAlertRecord>,
    #[serde(default)]
    phone_devices: Vec<PhoneDeviceRecord>,
    #[serde(default)]
    phone_events: Vec<PhoneEventRecord>,
    next_id: i64,
}

struct LocalStore {
    path: Option<PathBuf>,
    data: LocalStoreData,
}

impl LocalStore {
    fn open(path: Option<PathBuf>) -> Result<Self> {
        let data = if let Some(ref p) = path {
            if p.exists() {
                let raw = std::fs::read_to_string(p).unwrap_or_default();
                serde_json::from_str(&raw).unwrap_or_default()
            } else {
                LocalStoreData::default()
            }
        } else {
            LocalStoreData::default()
        };
        Ok(Self { path, data })
    }

    fn flush(&self) -> Result<()> {
        if let Some(ref p) = self.path {
            let serialized = serde_json::to_string(&self.data)?;
            std::fs::write(p, serialized)?;
        }
        Ok(())
    }

    fn alloc_id(&mut self) -> i64 {
        self.data.next_id += 1;
        self.data.next_id
    }
}

#[derive(Clone)]
enum TursoBackend {
    Local(Arc<Mutex<LocalStore>>),
    Remote {
        http: reqwest::Client,
        pipeline_url: String,
        auth_token: String,
    },
}

#[derive(Clone)]
pub struct Database {
    backend: TursoBackend,
}

fn row_str(row: &HashMap<String, Value>, key: &str) -> Option<String> {
    match row.get(key)? {
        Value::Null => None,
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        other => Some(other.to_string()),
    }
}

fn row_i64(row: &HashMap<String, Value>, key: &str) -> i64 {
    match row.get(key) {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(0),
        Some(Value::String(s)) => s.parse::<i64>().unwrap_or(0),
        _ => 0,
    }
}

fn memory_tokens(text: &str) -> Vec<String> {
    const STOPWORDS: &[&str] = &[
        "the", "is", "are", "was", "were", "to", "for", "and", "or", "in", "on", "at", "of",
        "with", "that", "this", "from", "by", "as", "be", "has", "have", "had",
    ];
    let mut out = Vec::new();
    let mut cur = String::new();
    for ch in text.chars() {
        if ch.is_ascii_alphanumeric() {
            cur.push(ch.to_ascii_lowercase());
        } else if !cur.is_empty() {
            if cur.len() >= 2 && !STOPWORDS.contains(&cur.as_str()) {
                out.push(std::mem::take(&mut cur));
            } else {
                cur.clear();
            }
        }
    }
    if cur.len() >= 2 && !STOPWORDS.contains(&cur.as_str()) {
        out.push(cur);
    }
    out
}

/// Computes semantic token similarity (Jaccard + subject-prefix bonus) between two memory facts.
/// Used to detect when a newer fact updates or supersedes an older fact (threshold `>= 0.68`).
pub fn memory_token_jaccard(a: &str, b: &str) -> f64 {
    let ta = memory_tokens(a);
    let tb = memory_tokens(b);
    if ta.is_empty() || tb.is_empty() {
        return 0.0;
    }
    // Check shared leading subject prefix (e.g. "falcon tutoring rate ..." vs "falcon tutoring rate ...")
    let prefix_match = ta
        .iter()
        .zip(tb.iter())
        .take_while(|(x, y)| x == y)
        .count();

    let set_a: HashSet<&str> = ta.iter().map(|s| s.as_str()).collect();
    let set_b: HashSet<&str> = tb.iter().map(|s| s.as_str()).collect();
    let inter = set_a.intersection(&set_b).count() as f64;
    let union = set_a.union(&set_b).count() as f64;
    if union == 0.0 {
        return 0.0;
    }
    let jaccard = inter / union;
    if prefix_match >= 3 && jaccard >= 0.40 {
        jaccard.max(0.72)
    } else if prefix_match >= 2 && jaccard >= 0.50 {
        jaccard.max(0.70)
    } else {
        jaccard
    }
}

impl Database {
    pub async fn connect(url: &str, auth_token: Option<&str>) -> Result<Self> {
        let trimmed = url.trim();
        if trimmed.is_empty() {
            return Err(anyhow!("TURSO_DATABASE_URL is not set"));
        }

        if trimmed.starts_with("libsql://")
            || trimmed.starts_with("https://")
            || trimmed.starts_with("http://")
        {
            let base = if let Some(rest) = trimmed.strip_prefix("libsql://") {
                format!("https://{}", rest.trim_end_matches('/'))
            } else {
                trimmed.trim_end_matches('/').to_string()
            };
            let pipeline_url = format!("{}/v2/pipeline", base);
            let db = Self {
                backend: TursoBackend::Remote {
                    http: reqwest::Client::new(),
                    pipeline_url,
                    auth_token: auth_token.unwrap_or_default().to_string(),
                },
            };
            db.init_remote_schema().await?;
            db.prune_old_data().await?;
            Ok(db)
        } else {
            let path_str = trimmed.strip_prefix("file:").unwrap_or(trimmed);
            let path = if path_str == ":memory:" || path_str.is_empty() {
                None
            } else {
                Some(PathBuf::from(path_str))
            };
            let store = LocalStore::open(path)?;
            Ok(Self {
                backend: TursoBackend::Local(Arc::new(Mutex::new(store))),
            })
        }
    }

    async fn init_remote_schema(&self) -> Result<()> {
        for stmt in SCHEMA_STATEMENTS {
            self.execute_remote(stmt, vec![]).await?;
        }
        // Safe migrations for existing Turso databases
        let _ = self
            .execute_remote(
                "ALTER TABLE conversations ADD COLUMN owner_read_unanswered INTEGER NOT NULL DEFAULT 0",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE contact_memory ADD COLUMN owner_style_pattern TEXT",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE contact_memory ADD COLUMN sample_owner_replies_json TEXT NOT NULL DEFAULT '[]'",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE conversations ADD COLUMN urgent_draft_json TEXT NOT NULL DEFAULT '{}'",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE contact_memory ADD COLUMN tier TEXT NOT NULL DEFAULT 'normal'",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE contact_memory ADD COLUMN recent_emotions_json TEXT NOT NULL DEFAULT '[]'",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE owner_memory ADD COLUMN visibility TEXT NOT NULL DEFAULT 'public'",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote(
                "ALTER TABLE owner_memory ADD COLUMN active INTEGER NOT NULL DEFAULT 1",
                vec![],
            )
            .await;
        let _ = self
            .execute_remote("ALTER TABLE owner_memory ADD COLUMN superseded_by INTEGER", vec![])
            .await;
        let _ = self
            .execute_remote("ALTER TABLE reminders ADD COLUMN recurrence TEXT", vec![])
            .await;
        Ok(())
    }

    pub async fn prune_old_data(&self) -> Result<()> {
        let now = Utc::now().timestamp_millis();
        let day_ms = 86_400_000_i64;
        match &self.backend {
            TursoBackend::Local(store_arc) => {
                let mut s = store_arc.lock().map_err(|_| anyhow!("Mutex poisoned"))?;
                s.data
                    .conversations
                    .retain(|_, v| v.updated_at >= now - 30 * day_ms);
                s.flush()?;
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "DELETE FROM conversations WHERE updated_at < ?",
                    vec![SqlParam::Int(now - 30 * day_ms)],
                )
                .await?;
                self.execute_remote(
                    "DELETE FROM strikes WHERE updated_at < ?",
                    vec![SqlParam::Int(now - 30 * day_ms)],
                )
                .await?;
                self.execute_remote(
                    "DELETE FROM mod_log WHERE at < ?",
                    vec![SqlParam::Int(now - 90 * day_ms)],
                )
                .await?;
            }
        }
        Ok(())
    }

    async fn execute_remote(&self, sql: &str, args: Vec<SqlParam>) -> Result<QueryResult> {
        let TursoBackend::Remote {
            http,
            pipeline_url,
            auth_token,
        } = &self.backend
        else {
            return Err(anyhow!("Not a remote Turso backend"));
        };

        let hrana_args: Vec<Value> = args
            .into_iter()
            .map(|p| match p {
                SqlParam::Null => json!({ "type": "null" }),
                SqlParam::Int(i) => json!({ "type": "integer", "value": i.to_string() }),
                SqlParam::Text(t) => json!({ "type": "text", "value": t }),
            })
            .collect();

        let body = json!({
            "requests": [
                {
                    "type": "execute",
                    "stmt": {
                        "sql": sql,
                        "args": hrana_args
                    }
                },
                { "type": "close" }
            ]
        });

        let mut req = http.post(pipeline_url).json(&body);
        if !auth_token.is_empty() {
            req = req.bearer_auth(auth_token);
        }
        let resp = req.send().await.context("Turso HTTP request failed")?;
        if !resp.status().is_success() {
            let status = resp.status();
            let err_text = resp.text().await.unwrap_or_default();
            return Err(anyhow!("Turso HTTP {}: {}", status, err_text));
        }
        let payload: Value = resp.json().await.context("Invalid Turso JSON response")?;
        let first = &payload["results"][0];
        if first["type"] == "error" {
            return Err(anyhow!("Turso SQL error: {}", first["error"]["message"]));
        }
        let exec_res = &first["response"]["result"];
        let cols: Vec<String> = exec_res["cols"]
            .as_array()
            .map(|arr| {
                arr.iter()
                    .map(|c| c["name"].as_str().unwrap_or_default().to_string())
                    .collect()
            })
            .unwrap_or_default();

        let mut rows_out = Vec::new();
        if let Some(raw_rows) = exec_res["rows"].as_array() {
            for raw_row in raw_rows {
                let mut map = HashMap::new();
                if let Some(cells) = raw_row.as_array() {
                    for (idx, cell) in cells.iter().enumerate() {
                        if let Some(col_name) = cols.get(idx) {
                            let val = match cell["type"].as_str().unwrap_or("null") {
                                "null" => Value::Null,
                                "integer" => {
                                    let n = cell["value"]
                                        .as_str()
                                        .and_then(|s| s.parse::<i64>().ok())
                                        .unwrap_or(0);
                                    json!(n)
                                }
                                "float" => json!(cell["value"].as_f64().unwrap_or(0.0)),
                                _ => Value::String(
                                    cell["value"].as_str().unwrap_or_default().to_string(),
                                ),
                            };
                            map.insert(col_name.clone(), val);
                        }
                    }
                }
                rows_out.push(map);
            }
        }

        let rows_affected = exec_res["affected_row_count"].as_u64().unwrap_or(0);
        let last_insert_rowid = exec_res["last_insert_rowid"]
            .as_str()
            .and_then(|s| s.parse::<i64>().ok())
            .or_else(|| exec_res["last_insert_rowid"].as_i64())
            .unwrap_or(0);

        Ok(QueryResult {
            rows: rows_out,
            rows_affected,
            last_insert_rowid,
        })
    }

    // ---- Conversations ----
    pub async fn get_conversation(&self, sender: &str) -> Result<Option<ConversationState>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard.data.conversations.get(sender).cloned())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM conversations WHERE sender = ?",
                        vec![SqlParam::from(sender)],
                    )
                    .await?;
                let Some(row) = res.rows.first() else {
                    return Ok(None);
                };
                let draft: BookingDraft = row_str(row, "draft_json")
                    .and_then(|s| serde_json::from_str(&s).ok())
                    .unwrap_or_default();
                let urgent_draft: UrgentDraft = row_str(row, "urgent_draft_json")
                    .and_then(|s| serde_json::from_str(&s).ok())
                    .unwrap_or_default();
                let history: Vec<ChatTurn> = row_str(row, "history_json")
                    .and_then(|s| serde_json::from_str(&s).ok())
                    .unwrap_or_default();

                Ok(Some(ConversationState {
                    sender: row_str(row, "sender").unwrap_or_else(|| sender.to_string()),
                    name: row_str(row, "name"),
                    draft,
                    urgent_draft,
                    history,
                    last_booking_key: row_str(row, "last_booking_key"),
                    bot_active: row_i64(row, "bot_active") != 0,
                    owner_read_unanswered: row_i64(row, "owner_read_unanswered") != 0,
                    paused_until: row_i64(row, "paused_until"),
                    last_flagged_at: row_i64(row, "last_flagged_at"),
                    updated_at: row_i64(row, "updated_at"),
                }))
            }
        }
    }

    pub async fn save_conversation(&self, state: &ConversationState) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard
                    .data
                    .conversations
                    .insert(state.sender.clone(), state.clone());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                let draft_json =
                    serde_json::to_string(&state.draft).unwrap_or_else(|_| "{}".to_string());
                let urgent_draft_json =
                    serde_json::to_string(&state.urgent_draft).unwrap_or_else(|_| "{}".to_string());
                let history_json =
                    serde_json::to_string(&state.history).unwrap_or_else(|_| "[]".to_string());
                self.execute_remote(
                    "INSERT INTO conversations (sender, name, draft_json, urgent_draft_json, history_json, last_booking_key, bot_active, owner_read_unanswered, paused_until, last_flagged_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(sender) DO UPDATE SET
                       name = excluded.name,
                       draft_json = excluded.draft_json,
                       urgent_draft_json = excluded.urgent_draft_json,
                       history_json = excluded.history_json,
                       last_booking_key = excluded.last_booking_key,
                       bot_active = excluded.bot_active,
                       owner_read_unanswered = excluded.owner_read_unanswered,
                       paused_until = excluded.paused_until,
                       last_flagged_at = excluded.last_flagged_at,
                       updated_at = excluded.updated_at",
                    vec![
                        SqlParam::from(state.sender.clone()),
                        SqlParam::from(state.name.clone()),
                        SqlParam::from(draft_json),
                        SqlParam::from(urgent_draft_json),
                        SqlParam::from(history_json),
                        SqlParam::from(state.last_booking_key.clone()),
                        SqlParam::Int(if state.bot_active { 1 } else { 0 }),
                        SqlParam::Int(if state.owner_read_unanswered { 1 } else { 0 }),
                        SqlParam::Int(state.paused_until),
                        SqlParam::Int(state.last_flagged_at),
                        SqlParam::Int(state.updated_at),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    // ---- Pending 5-Minute Replies ----
    pub async fn get_pending_reply(&self, sender: &str) -> Result<Option<PendingReply>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard.data.pending_replies.get(sender).cloned())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM pending_replies WHERE sender = ?",
                        vec![SqlParam::from(sender)],
                    )
                    .await?;
                let Some(row) = res.rows.first() else {
                    return Ok(None);
                };
                let messages: Vec<String> = row_str(row, "messages_json")
                    .and_then(|s| serde_json::from_str(&s).ok())
                    .unwrap_or_default();
                Ok(Some(PendingReply {
                    sender: row_str(row, "sender").unwrap_or_else(|| sender.to_string()),
                    remote_jid: row_str(row, "remote_jid").unwrap_or_default(),
                    first_msg_at: row_i64(row, "first_msg_at"),
                    due_at: row_i64(row, "due_at"),
                    messages,
                    last_msg_id: row_str(row, "last_msg_id").unwrap_or_default(),
                }))
            }
        }
    }

    pub async fn upsert_pending_reply(&self, p: &PendingReply) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard
                    .data
                    .pending_replies
                    .insert(p.sender.clone(), p.clone());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                let msgs_json =
                    serde_json::to_string(&p.messages).unwrap_or_else(|_| "[]".to_string());
                self.execute_remote(
                    "INSERT INTO pending_replies (sender, remote_jid, first_msg_at, due_at, messages_json, last_msg_id)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(sender) DO UPDATE SET
                       remote_jid = excluded.remote_jid,
                       first_msg_at = excluded.first_msg_at,
                       due_at = excluded.due_at,
                       messages_json = excluded.messages_json,
                       last_msg_id = excluded.last_msg_id",
                    vec![
                        SqlParam::from(p.sender.clone()),
                        SqlParam::from(p.remote_jid.clone()),
                        SqlParam::Int(p.first_msg_at),
                        SqlParam::Int(p.due_at),
                        SqlParam::from(msgs_json),
                        SqlParam::from(p.last_msg_id.clone()),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn delete_pending_reply(&self, sender: &str) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard.data.pending_replies.remove(sender);
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "DELETE FROM pending_replies WHERE sender = ?",
                    vec![SqlParam::from(sender)],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn list_pending_replies(&self) -> Result<Vec<PendingReply>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<PendingReply> =
                    guard.data.pending_replies.values().cloned().collect();
                list.sort_by_key(|p| p.due_at);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT * FROM pending_replies ORDER BY due_at ASC", vec![])
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|row| PendingReply {
                        sender: row_str(row, "sender").unwrap_or_default(),
                        remote_jid: row_str(row, "remote_jid").unwrap_or_default(),
                        first_msg_at: row_i64(row, "first_msg_at"),
                        due_at: row_i64(row, "due_at"),
                        messages: row_str(row, "messages_json")
                            .and_then(|s| serde_json::from_str(&s).ok())
                            .unwrap_or_default(),
                        last_msg_id: row_str(row, "last_msg_id").unwrap_or_default(),
                    })
                    .collect())
            }
        }
    }

    // ---- Contact Memory (Layer 2 - Permanent) ----
    pub async fn get_contact_memory(&self, sender: &str) -> Result<Option<ContactMemory>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard.data.contact_memory.get(sender).cloned())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM contact_memory WHERE sender = ?",
                        vec![SqlParam::from(sender)],
                    )
                    .await?;
                let Some(row) = res.rows.first() else {
                    return Ok(None);
                };
                Ok(Some(Self::parse_contact_memory(row)))
            }
        }
    }

    fn parse_contact_memory(row: &HashMap<String, Value>) -> ContactMemory {
        let tier = row_str(row, "tier")
            .filter(|s| matches!(s.as_str(), "vip" | "normal" | "silent"))
            .unwrap_or_else(|| "normal".to_string());
        ContactMemory {
            sender: row_str(row, "sender").unwrap_or_default(),
            display_name: row_str(row, "display_name"),
            aliases: row_str(row, "aliases_json")
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default(),
            relationship: row_str(row, "relationship"),
            tone: row_str(row, "tone"),
            tier,
            recent_emotions: row_str(row, "recent_emotions_json")
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default(),
            owner_style_pattern: row_str(row, "owner_style_pattern"),
            sample_owner_replies: row_str(row, "sample_owner_replies_json")
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default(),
            summary: row_str(row, "summary").unwrap_or_default(),
            facts: row_str(row, "facts_json")
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default(),
            open_threads: row_str(row, "open_threads_json")
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default(),
            interaction_count: row_i64(row, "interaction_count"),
            last_seen_at: row_i64(row, "last_seen_at"),
            updated_at: row_i64(row, "updated_at"),
        }
    }

    pub async fn find_contact_memories(&self, query: &str) -> Result<Vec<ContactMemory>> {
        let q = query.trim().to_lowercase();
        let all = match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<ContactMemory> =
                    guard.data.contact_memory.values().cloned().collect();
                list.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
                list
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT * FROM contact_memory ORDER BY updated_at DESC", vec![])
                    .await?;
                res.rows.iter().map(Self::parse_contact_memory).collect()
            }
        };
        Ok(all
            .into_iter()
            .filter(|m| {
                q.is_empty()
                    || m.sender.contains(&q)
                    || m.display_name
                        .as_deref()
                        .unwrap_or("")
                        .to_lowercase()
                        .contains(&q)
                    || m.aliases.iter().any(|a| a.to_lowercase().contains(&q))
            })
            .collect())
    }

    pub async fn save_contact_memory(&self, mem: &ContactMemory) -> Result<()> {
        let mut copy = mem.clone();
        if copy.facts.len() > 25 {
            copy.facts = copy.facts.split_off(copy.facts.len() - 25);
        }
        if copy.open_threads.len() > 15 {
            copy.open_threads = copy.open_threads.split_off(copy.open_threads.len() - 15);
        }
        if copy.sample_owner_replies.len() > 6 {
            copy.sample_owner_replies = copy
                .sample_owner_replies
                .split_off(copy.sample_owner_replies.len() - 6);
        }
        if copy.recent_emotions.len() > 5 {
            copy.recent_emotions = copy
                .recent_emotions
                .split_off(copy.recent_emotions.len() - 5);
        }
        if !matches!(copy.tier.as_str(), "vip" | "normal" | "silent") {
            copy.tier = "normal".to_string();
        }

        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard
                    .data
                    .contact_memory
                    .insert(copy.sender.clone(), copy);
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO contact_memory (sender, display_name, aliases_json, relationship, tone, tier, recent_emotions_json, owner_style_pattern, sample_owner_replies_json, summary, facts_json, open_threads_json, interaction_count, last_seen_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(sender) DO UPDATE SET
                       display_name = excluded.display_name,
                       aliases_json = excluded.aliases_json,
                       relationship = excluded.relationship,
                       tone = excluded.tone,
                       tier = excluded.tier,
                       recent_emotions_json = excluded.recent_emotions_json,
                       owner_style_pattern = excluded.owner_style_pattern,
                       sample_owner_replies_json = excluded.sample_owner_replies_json,
                       summary = excluded.summary,
                       facts_json = excluded.facts_json,
                       open_threads_json = excluded.open_threads_json,
                       interaction_count = excluded.interaction_count,
                       last_seen_at = excluded.last_seen_at,
                       updated_at = excluded.updated_at",
                    vec![
                        SqlParam::from(copy.sender.clone()),
                        SqlParam::from(copy.display_name.clone()),
                        SqlParam::from(serde_json::to_string(&copy.aliases).unwrap_or_else(|_| "[]".to_string())),
                        SqlParam::from(copy.relationship.clone()),
                        SqlParam::from(copy.tone.clone()),
                        SqlParam::from(copy.tier.clone()),
                        SqlParam::from(serde_json::to_string(&copy.recent_emotions).unwrap_or_else(|_| "[]".to_string())),
                        SqlParam::from(copy.owner_style_pattern.clone()),
                        SqlParam::from(serde_json::to_string(&copy.sample_owner_replies).unwrap_or_else(|_| "[]".to_string())),
                        SqlParam::from(copy.summary.clone()),
                        SqlParam::from(serde_json::to_string(&copy.facts).unwrap_or_else(|_| "[]".to_string())),
                        SqlParam::from(serde_json::to_string(&copy.open_threads).unwrap_or_else(|_| "[]".to_string())),
                        SqlParam::Int(copy.interaction_count),
                        SqlParam::Int(copy.last_seen_at),
                        SqlParam::Int(copy.updated_at),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    // ---- Owner Memory (Layer 3 - Permanent, Audience-Scoped + Semantic Superseding) ----
    pub async fn add_owner_memory(&self, category: &str, content: &str, source: &str) -> Result<i64> {
        self.add_owner_memory_scoped(category, "public", content, source)
            .await
    }

    pub async fn add_owner_memory_scoped(
        &self,
        category: &str,
        visibility: &str,
        content: &str,
        source: &str,
    ) -> Result<i64> {
        self.add_owner_memory_with_replaces(category, visibility, content, source, &[])
            .await
    }

    pub async fn add_owner_memory_with_replaces(
        &self,
        category: &str,
        visibility: &str,
        content: &str,
        source: &str,
        replaces_ids: &[i64],
    ) -> Result<i64> {
        let clean = content.trim();
        if clean.is_empty() {
            return Ok(0);
        }
        let cat = if category.trim().is_empty() {
            "general"
        } else {
            category.trim()
        };
        let vis = match visibility.trim().to_lowercase().as_str() {
            "close" | "friends" | "vip" => "close",
            "private" | "private_internal" | "internal" => "private_internal",
            _ => "public",
        };
        let now = Utc::now().timestamp_millis();
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(existing) = guard
                    .data
                    .owner_memory
                    .iter_mut()
                    .find(|m| m.content.eq_ignore_ascii_case(clean))
                {
                    existing.updated_at = now;
                    existing.category = cat.to_string();
                    existing.visibility = vis.to_string();
                    existing.active = true;
                    existing.superseded_by = None;
                    let id = existing.id;
                    for m in &mut guard.data.owner_memory {
                        if m.id != id
                            && m.active
                            && (replaces_ids.contains(&m.id)
                                || (m.category.eq_ignore_ascii_case(cat)
                                    && memory_token_jaccard(&m.content, clean) >= 0.68))
                        {
                            m.active = false;
                            m.superseded_by = Some(id);
                            m.updated_at = now;
                        }
                    }
                    guard.flush()?;
                    return Ok(id);
                }
                let id = guard.alloc_id();
                for m in &mut guard.data.owner_memory {
                    if m.active
                        && (replaces_ids.contains(&m.id)
                            || (m.category.eq_ignore_ascii_case(cat)
                                && memory_token_jaccard(&m.content, clean) >= 0.68))
                    {
                        m.active = false;
                        m.superseded_by = Some(id);
                        m.updated_at = now;
                    }
                }
                guard.data.owner_memory.push(OwnerMemoryItem {
                    id,
                    category: cat.to_string(),
                    visibility: vis.to_string(),
                    content: clean.to_string(),
                    source: source.to_string(),
                    active: true,
                    superseded_by: None,
                    created_at: now,
                    updated_at: now,
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let existing = self
                    .execute_remote(
                        "SELECT id FROM owner_memory WHERE LOWER(content) = LOWER(?)",
                        vec![SqlParam::from(clean)],
                    )
                    .await?;
                let new_id = if let Some(row) = existing.rows.first() {
                    let id = row_i64(row, "id");
                    self.execute_remote(
                        "UPDATE owner_memory SET updated_at = ?, category = ?, visibility = ?, active = 1, superseded_by = NULL WHERE id = ?",
                        vec![
                            SqlParam::Int(now),
                            SqlParam::from(cat),
                            SqlParam::from(vis),
                            SqlParam::Int(id),
                        ],
                    )
                    .await?;
                    id
                } else {
                    let res = self
                        .execute_remote(
                            "INSERT INTO owner_memory (category, visibility, content, source, active, superseded_by, created_at, updated_at) VALUES (?, ?, ?, ?, 1, NULL, ?, ?)",
                            vec![
                                SqlParam::from(cat),
                                SqlParam::from(vis),
                                SqlParam::from(clean),
                                SqlParam::from(source),
                                SqlParam::Int(now),
                                SqlParam::Int(now),
                            ],
                        )
                        .await?;
                    res.last_insert_rowid
                };

                // Supersede explicitly listed IDs + any near-duplicate active facts in the same category
                let mut to_supersede: Vec<i64> = replaces_ids
                    .iter()
                    .copied()
                    .filter(|&old_id| old_id > 0 && old_id != new_id)
                    .collect();
                if let Ok(active_same_cat) = self
                    .execute_remote(
                        "SELECT id, content FROM owner_memory WHERE COALESCE(active, 1) = 1 AND LOWER(category) = LOWER(?) AND id != ?",
                        vec![SqlParam::from(cat), SqlParam::Int(new_id)],
                    )
                    .await
                {
                    for row in &active_same_cat.rows {
                        let old_id = row_i64(row, "id");
                        let old_content = row_str(row, "content").unwrap_or_default();
                        if old_id > 0
                            && !to_supersede.contains(&old_id)
                            && memory_token_jaccard(&old_content, clean) >= 0.68
                        {
                            to_supersede.push(old_id);
                        }
                    }
                }
                if !to_supersede.is_empty() {
                    let _ = self
                        .supersede_owner_memories(&to_supersede, Some(new_id))
                        .await;
                }
                Ok(new_id)
            }
        }
    }

    pub async fn supersede_owner_memories(
        &self,
        old_ids: &[i64],
        new_id: Option<i64>,
    ) -> Result<u64> {
        if old_ids.is_empty() {
            return Ok(0);
        }
        let now = Utc::now().timestamp_millis();
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let mut count = 0u64;
                for m in &mut guard.data.owner_memory {
                    if old_ids.contains(&m.id) && m.active {
                        m.active = false;
                        m.superseded_by = new_id;
                        m.updated_at = now;
                        count += 1;
                    }
                }
                if count > 0 {
                    guard.flush()?;
                }
                Ok(count)
            }
            TursoBackend::Remote { .. } => {
                let mut total = 0u64;
                for &old_id in old_ids {
                    let sup_param = match new_id {
                        Some(nid) => SqlParam::Int(nid),
                        None => SqlParam::Null,
                    };
                    let r = self
                        .execute_remote(
                            "UPDATE owner_memory SET active = 0, superseded_by = ?, updated_at = ? WHERE id = ?",
                            vec![sup_param, SqlParam::Int(now), SqlParam::Int(old_id)],
                        )
                        .await?;
                    total += r.rows_affected;
                }
                Ok(total)
            }
        }
    }

    /// Deterministically sweeps all active owner memories (newest first) and marks older
    /// conflicting/near-duplicate facts within the same category as superseded.
    pub async fn tidy_owner_memory_deterministic(&self) -> Result<usize> {
        let items = self.list_owner_memory(200).await?;
        let mut kept: Vec<OwnerMemoryItem> = Vec::new();
        let mut superseded_pairs: Vec<(i64, i64)> = Vec::new();
        for item in items {
            if !item.active {
                continue;
            }
            if let Some(newer) = kept.iter().find(|k| {
                k.category.eq_ignore_ascii_case(&item.category)
                    && (k.content.eq_ignore_ascii_case(&item.content)
                        || memory_token_jaccard(&k.content, &item.content) >= 0.68)
            }) {
                superseded_pairs.push((item.id, newer.id));
            } else {
                kept.push(item);
            }
        }
        for (old_id, new_id) in &superseded_pairs {
            let _ = self
                .supersede_owner_memories(&[*old_id], Some(*new_id))
                .await;
        }
        Ok(superseded_pairs.len())
    }

    pub async fn list_owner_memory(&self, limit: i64) -> Result<Vec<OwnerMemoryItem>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<OwnerMemoryItem> = guard
                    .data
                    .owner_memory
                    .iter()
                    .filter(|m| m.active)
                    .cloned()
                    .collect();
                list.sort_by(|a, b| b.updated_at.cmp(&a.updated_at).then_with(|| b.id.cmp(&a.id)));
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM owner_memory WHERE COALESCE(active, 1) = 1 ORDER BY updated_at DESC, id DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| OwnerMemoryItem {
                        id: row_i64(r, "id"),
                        category: row_str(r, "category").unwrap_or_default(),
                        visibility: row_str(r, "visibility")
                            .filter(|s| !s.is_empty())
                            .unwrap_or_else(|| "public".to_string()),
                        content: row_str(r, "content").unwrap_or_default(),
                        source: row_str(r, "source").unwrap_or_default(),
                        active: row_str(r, "active").map(|s| s != "0").unwrap_or(true),
                        superseded_by: row_str(r, "superseded_by").and_then(|s| s.parse().ok()),
                        created_at: row_i64(r, "created_at"),
                        updated_at: row_i64(r, "updated_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn delete_owner_memory(&self, query: &str) -> Result<u64> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(0);
        }
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let before = guard.data.owner_memory.len();
                if let Ok(id) = q.parse::<i64>() {
                    guard.data.owner_memory.retain(|m| m.id != id);
                } else {
                    let lower = q.to_lowercase();
                    guard
                        .data
                        .owner_memory
                        .retain(|m| !m.content.to_lowercase().contains(&lower));
                }
                let removed = (before - guard.data.owner_memory.len()) as u64;
                guard.flush()?;
                Ok(removed)
            }
            TursoBackend::Remote { .. } => {
                if let Ok(id) = q.parse::<i64>() {
                    let r = self
                        .execute_remote("DELETE FROM owner_memory WHERE id = ?", vec![SqlParam::Int(id)])
                        .await?;
                    return Ok(r.rows_affected);
                }
                let r = self
                    .execute_remote(
                        "DELETE FROM owner_memory WHERE LOWER(content) LIKE ?",
                        vec![SqlParam::from(format!("%{}%", q.to_lowercase()))],
                    )
                    .await?;
                Ok(r.rows_affected)
            }
        }
    }

    // ---- Learning Episodes (Continuous Self-Improvement) ----
    pub async fn add_learning_episode(&self, sender: Option<&str>, kind: &str, lesson: &str) -> Result<i64> {
        let clean = lesson.trim();
        if clean.is_empty() {
            return Ok(0);
        }
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(existing) = guard
                    .data
                    .learning_episodes
                    .iter()
                    .find(|l| l.lesson.eq_ignore_ascii_case(clean))
                {
                    return Ok(existing.id);
                }
                let id = guard.alloc_id();
                guard.data.learning_episodes.push(LearningEpisode {
                    id,
                    sender: sender.map(|v| v.to_string()),
                    kind: kind.to_string(),
                    lesson: clean.to_string(),
                    created_at: Utc::now().timestamp_millis(),
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let existing = self
                    .execute_remote(
                        "SELECT id FROM learning_episodes WHERE LOWER(lesson) = LOWER(?)",
                        vec![SqlParam::from(clean)],
                    )
                    .await?;
                if let Some(row) = existing.rows.first() {
                    return Ok(row_i64(row, "id"));
                }
                let res = self
                    .execute_remote(
                        "INSERT INTO learning_episodes (sender, kind, lesson, created_at) VALUES (?, ?, ?, ?)",
                        vec![
                            SqlParam::from(sender.map(|s| s.to_string())),
                            SqlParam::from(kind),
                            SqlParam::from(clean),
                            SqlParam::Int(Utc::now().timestamp_millis()),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn list_learning_episodes(&self, limit: i64) -> Result<Vec<LearningEpisode>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list = guard.data.learning_episodes.clone();
                list.sort_by(|a, b| b.id.cmp(&a.id));
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM learning_episodes ORDER BY id DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| LearningEpisode {
                        id: row_i64(r, "id"),
                        sender: row_str(r, "sender"),
                        kind: row_str(r, "kind").unwrap_or_default(),
                        lesson: row_str(r, "lesson").unwrap_or_default(),
                        created_at: row_i64(r, "created_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn delete_learning_episodes(&self, query: &str) -> Result<u64> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(0);
        }
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let before = guard.data.learning_episodes.len();
                if let Ok(id) = q.parse::<i64>() {
                    guard.data.learning_episodes.retain(|l| l.id != id);
                } else {
                    let lower = q.to_lowercase();
                    guard
                        .data
                        .learning_episodes
                        .retain(|l| !l.lesson.to_lowercase().contains(&lower));
                }
                let removed = (before - guard.data.learning_episodes.len()) as u64;
                guard.flush()?;
                Ok(removed)
            }
            TursoBackend::Remote { .. } => {
                if let Ok(id) = q.parse::<i64>() {
                    let r = self
                        .execute_remote(
                            "DELETE FROM learning_episodes WHERE id = ?",
                            vec![SqlParam::Int(id)],
                        )
                        .await?;
                    return Ok(r.rows_affected);
                }
                let r = self
                    .execute_remote(
                        "DELETE FROM learning_episodes WHERE LOWER(lesson) LIKE ?",
                        vec![SqlParam::from(format!("%{}%", q.to_lowercase()))],
                    )
                    .await?;
                Ok(r.rows_affected)
            }
        }
    }

    // ---- Bookings & Flags ----
    pub async fn insert_booking(&self, b: &BookingRecord) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard.data.bookings.push(b.clone());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO bookings (timestamp, chat, name, subject, exam, slot, number) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    vec![
                        SqlParam::from(b.timestamp.clone()),
                        SqlParam::from(b.chat.clone()),
                        SqlParam::from(b.name.clone()),
                        SqlParam::from(b.subject.clone()),
                        SqlParam::from(b.exam.clone()),
                        SqlParam::from(b.slot.clone()),
                        SqlParam::from(b.number.clone()),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn list_bookings(&self, limit: i64) -> Result<Vec<BookingRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .bookings
                    .iter()
                    .rev()
                    .take(limit as usize)
                    .cloned()
                    .collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM bookings ORDER BY id DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| BookingRecord {
                        timestamp: row_str(r, "timestamp").unwrap_or_default(),
                        chat: row_str(r, "chat").unwrap_or_default(),
                        name: row_str(r, "name").unwrap_or_default(),
                        subject: row_str(r, "subject").unwrap_or_default(),
                        exam: row_str(r, "exam").unwrap_or_default(),
                        slot: row_str(r, "slot").unwrap_or_default(),
                        number: row_str(r, "number").unwrap_or_default(),
                    })
                    .collect())
            }
        }
    }

    pub async fn count_bookings(&self) -> Result<i64> {
        match &self.backend {
            TursoBackend::Local(s) => Ok(s.lock().unwrap().data.bookings.len() as i64),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT COUNT(*) AS c FROM bookings", vec![])
                    .await?;
                Ok(res.rows.first().map(|r| row_i64(r, "c")).unwrap_or(0))
            }
        }
    }

    pub async fn insert_flag(&self, f: &FlagRecord) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard.data.flags.push(f.clone());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO flags (timestamp, chat, name, reason, last_message) VALUES (?, ?, ?, ?, ?)",
                    vec![
                        SqlParam::from(f.timestamp.clone()),
                        SqlParam::from(f.chat.clone()),
                        SqlParam::from(f.name.clone()),
                        SqlParam::from(f.reason.clone()),
                        SqlParam::from(f.last_message.clone()),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn list_flags(&self, limit: i64) -> Result<Vec<FlagRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .flags
                    .iter()
                    .rev()
                    .take(limit as usize)
                    .cloned()
                    .collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM flags ORDER BY id DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| FlagRecord {
                        timestamp: row_str(r, "timestamp").unwrap_or_default(),
                        chat: row_str(r, "chat").unwrap_or_default(),
                        name: row_str(r, "name"),
                        reason: row_str(r, "reason").unwrap_or_default(),
                        last_message: row_str(r, "last_message").unwrap_or_default(),
                    })
                    .collect())
            }
        }
    }

    pub async fn count_flags(&self) -> Result<i64> {
        match &self.backend {
            TursoBackend::Local(s) => Ok(s.lock().unwrap().data.flags.len() as i64),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT COUNT(*) AS c FROM flags", vec![])
                    .await?;
                Ok(res.rows.first().map(|r| row_i64(r, "c")).unwrap_or(0))
            }
        }
    }

    pub async fn get_latest_flag_for_chat(&self, chat: &str) -> Result<Option<FlagRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .flags
                    .iter()
                    .rev()
                    .find(|f| f.chat == chat)
                    .cloned())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM flags WHERE chat = ? ORDER BY id DESC LIMIT 1",
                        vec![SqlParam::from(chat)],
                    )
                    .await?;
                Ok(res.rows.first().map(|r| FlagRecord {
                    timestamp: row_str(r, "timestamp").unwrap_or_default(),
                    chat: row_str(r, "chat").unwrap_or_default(),
                    name: row_str(r, "name"),
                    reason: row_str(r, "reason").unwrap_or_default(),
                    last_message: row_str(r, "last_message").unwrap_or_default(),
                }))
            }
        }
    }

    pub async fn delete_flags_for_chat(&self, chat: &str) -> Result<u64> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let before = guard.data.flags.len();
                guard.data.flags.retain(|f| f.chat != chat);
                let removed = (before - guard.data.flags.len()) as u64;
                guard.flush()?;
                Ok(removed)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("DELETE FROM flags WHERE chat = ?", vec![SqlParam::from(chat)])
                    .await?;
                Ok(res.rows_affected)
            }
        }
    }

    // ---- Blocked, Contacts, Guarded Groups, Safe Domains, Settings ----
    pub async fn is_blocked(&self, ids: &[String]) -> Result<bool> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(ids.iter().any(|id| guard.data.blocked.contains_key(id)))
            }
            TursoBackend::Remote { .. } => {
                for id in ids {
                    let res = self
                        .execute_remote(
                            "SELECT id FROM blocked WHERE id = ? LIMIT 1",
                            vec![SqlParam::from(id.clone())],
                        )
                        .await?;
                    if !res.rows.is_empty() {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
        }
    }

    pub async fn set_blocked(&self, id: &str, blocked: bool) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if blocked {
                    guard
                        .data
                        .blocked
                        .insert(id.to_string(), Utc::now().timestamp_millis());
                } else {
                    guard.data.blocked.remove(id);
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                if blocked {
                    self.execute_remote(
                        "INSERT INTO blocked (id, at) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET at = excluded.at",
                        vec![SqlParam::from(id), SqlParam::Int(Utc::now().timestamp_millis())],
                    )
                    .await?;
                } else {
                    self.execute_remote("DELETE FROM blocked WHERE id = ?", vec![SqlParam::from(id)])
                        .await?;
                }
                Ok(())
            }
        }
    }

    pub async fn list_blocked(&self) -> Result<Vec<String>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard.data.blocked.keys().cloned().collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT id FROM blocked ORDER BY at DESC", vec![])
                    .await?;
                Ok(res.rows.iter().filter_map(|r| row_str(r, "id")).collect())
            }
        }
    }

    pub async fn get_guarded_group(&self, group_jid: &str) -> Result<Option<GuardedGroup>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard.data.guarded_groups.get(group_jid).cloned())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT id, name, mode FROM guarded_groups WHERE id = ?",
                        vec![SqlParam::from(group_jid)],
                    )
                    .await?;
                Ok(res.rows.first().map(|r| GuardedGroup {
                    id: row_str(r, "id").unwrap_or_default(),
                    name: row_str(r, "name"),
                    mode: row_str(r, "mode").unwrap_or_else(|| "guarded".to_string()),
                }))
            }
        }
    }

    pub async fn set_guarded_group(&self, group_jid: &str, name: &str, mode: &str) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard.data.guarded_groups.insert(
                    group_jid.to_string(),
                    GuardedGroup {
                        id: group_jid.to_string(),
                        name: Some(name.to_string()),
                        mode: mode.to_string(),
                    },
                );
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO guarded_groups (id, name, mode, updated_at) VALUES (?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET name = excluded.name, mode = excluded.mode, updated_at = excluded.updated_at",
                    vec![
                        SqlParam::from(group_jid),
                        SqlParam::from(name),
                        SqlParam::from(mode),
                        SqlParam::Int(Utc::now().timestamp_millis()),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn list_guarded_groups(&self) -> Result<Vec<GuardedGroup>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .guarded_groups
                    .values()
                    .filter(|g| g.mode != "unguarded")
                    .cloned()
                    .collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT id, name, mode FROM guarded_groups WHERE mode != 'unguarded'",
                        vec![],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| GuardedGroup {
                        id: row_str(r, "id").unwrap_or_default(),
                        name: row_str(r, "name"),
                        mode: row_str(r, "mode").unwrap_or_else(|| "guarded".to_string()),
                    })
                    .collect())
            }
        }
    }

    pub async fn get_safe_domains(&self) -> Result<HashSet<String>> {
        let mut set: HashSet<String> = DEFAULT_SAFE_DOMAINS.iter().map(|s| s.to_string()).collect();
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                for d in &guard.data.safe_domains {
                    set.insert(d.to_lowercase());
                }
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT id FROM safe_domains", vec![])
                    .await?;
                for r in &res.rows {
                    if let Some(d) = row_str(r, "id") {
                        set.insert(d.to_lowercase());
                    }
                }
            }
        }
        Ok(set)
    }

    pub async fn set_safe_domain(&self, domain: &str, allowed: bool) -> Result<()> {
        let d = domain.to_lowercase();
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if allowed {
                    guard.data.safe_domains.insert(d);
                } else {
                    guard.data.safe_domains.remove(&d);
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                if allowed {
                    self.execute_remote(
                        "INSERT INTO safe_domains (id, at) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET at = excluded.at",
                        vec![SqlParam::from(d), SqlParam::Int(Utc::now().timestamp_millis())],
                    )
                    .await?;
                } else {
                    self.execute_remote("DELETE FROM safe_domains WHERE id = ?", vec![SqlParam::from(d)])
                        .await?;
                }
                Ok(())
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn insert_mod_log(
        &self,
        group_jid: &str,
        group_name: &str,
        sender: &str,
        action: &str,
        reason: &str,
        result: &str,
        deleted: &str,
        links: &[String],
    ) -> Result<()> {
        if let TursoBackend::Remote { .. } = &self.backend {
            self.execute_remote(
                "INSERT INTO mod_log (at, group_jid, group_name, sender, action, reason, result, deleted, links_json)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                vec![
                    SqlParam::Int(Utc::now().timestamp_millis()),
                    SqlParam::from(group_jid),
                    SqlParam::from(group_name),
                    SqlParam::from(sender),
                    SqlParam::from(action),
                    SqlParam::from(reason),
                    SqlParam::from(result),
                    SqlParam::from(deleted),
                    SqlParam::from(serde_json::to_string(links).unwrap_or_else(|_| "[]".to_string())),
                ],
            )
            .await?;
        }
        Ok(())
    }

    pub async fn upsert_saved_contact(&self, id: &str, name: &str) -> Result<()> {
        if id.is_empty() || name.trim().is_empty() {
            return Ok(());
        }
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard
                    .data
                    .saved_contacts
                    .insert(id.to_string(), name.trim().to_string());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO saved_contacts (id, name) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET name = excluded.name",
                    vec![SqlParam::from(id), SqlParam::from(name.trim())],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn is_saved_contact(&self, ids: &[String]) -> Result<bool> {
        Ok(self.get_saved_contact_name(ids).await?.is_some())
    }

    pub async fn get_saved_contact_name(&self, ids: &[String]) -> Result<Option<String>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                for id in ids {
                    if let Some(name) = guard.data.saved_contacts.get(id) {
                        if !name.trim().is_empty() {
                            return Ok(Some(name.clone()));
                        }
                    }
                }
                Ok(None)
            }
            TursoBackend::Remote { .. } => {
                for id in ids {
                    let res = self
                        .execute_remote(
                            "SELECT name FROM saved_contacts WHERE id = ? LIMIT 1",
                            vec![SqlParam::from(id.clone())],
                        )
                        .await?;
                    if let Some(row) = res.rows.first() {
                        if let Some(name) = row_str(row, "name") {
                            if !name.trim().is_empty() {
                                return Ok(Some(name));
                            }
                        }
                    }
                }
                Ok(None)
            }
        }
    }

    pub async fn count_saved_contacts(&self) -> Result<i64> {
        match &self.backend {
            TursoBackend::Local(s) => Ok(s.lock().unwrap().data.saved_contacts.len() as i64),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("SELECT COUNT(*) AS c FROM saved_contacts", vec![])
                    .await?;
                Ok(res.rows.first().map(|r| row_i64(r, "c")).unwrap_or(0))
            }
        }
    }

    pub async fn find_saved_contacts(&self, query: &str) -> Result<Vec<(String, String)>> {
        let q = query.trim().to_lowercase();
        let q_alpha: String = q.chars().filter(|c| c.is_alphanumeric()).collect();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let all: Vec<(String, String)> = match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                guard
                    .data
                    .saved_contacts
                    .iter()
                    .map(|(k, v)| (k.clone(), v.clone()))
                    .collect()
            }
            TursoBackend::Remote { .. } => {
                let like = format!("%{}%", q);
                let res = self
                    .execute_remote(
                        "SELECT id, name FROM saved_contacts WHERE LOWER(name) LIKE ? OR id LIKE ? LIMIT 25",
                        vec![SqlParam::from(like.clone()), SqlParam::from(like)],
                    )
                    .await?;
                res.rows
                    .iter()
                    .filter_map(|r| Some((row_str(r, "id")?, row_str(r, "name")?)))
                    .collect()
            }
        };
        let mut hits: Vec<(String, String)> = all
            .into_iter()
            .filter(|(id, name)| {
                let nl = name.to_lowercase();
                let n_alpha: String = nl.chars().filter(|c| c.is_alphanumeric()).collect();
                nl.contains(&q)
                    || id.contains(&q)
                    || (!q_alpha.is_empty() && n_alpha.contains(&q_alpha))
            })
            .collect();
        // Prefer exact name matches and standard phone numbers (starting with country code, not @lid ids)
        hits.sort_by_key(|(id, name)| {
            let exact = name.trim().eq_ignore_ascii_case(query.trim());
            let looks_phone = id.starts_with("234") && id.len() == 13;
            (!exact, !looks_phone, name.len())
        });
        Ok(hits)
    }

    pub async fn get_setting(&self, key: &str) -> Result<Option<String>> {
        match &self.backend {
            TursoBackend::Local(s) => Ok(s.lock().unwrap().data.settings.get(key).cloned()),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT value FROM settings WHERE id = ?",
                        vec![SqlParam::from(key)],
                    )
                    .await?;
                Ok(res.rows.first().and_then(|r| row_str(r, "value")))
            }
        }
    }

    pub async fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard
                    .data
                    .settings
                    .insert(key.to_string(), value.to_string());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO settings (id, value) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET value = excluded.value",
                    vec![SqlParam::from(key), SqlParam::from(value)],
                )
                .await?;
                Ok(())
            }
        }
    }

    // ---- WhatsApp Session Persistence in Turso (survives Heroku dyno restarts) ----
    pub async fn save_session_blob(&self, key: &str, data: &[u8]) -> Result<()> {
        let encoded = hex::encode(data);
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard.data.auth_blobs.insert(key.to_string(), encoded);
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO auth_state (id, value) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET value = excluded.value",
                    vec![SqlParam::from(key), SqlParam::from(encoded)],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn load_session_blob(&self, key: &str) -> Result<Option<Vec<u8>>> {
        let hex_str = match &self.backend {
            TursoBackend::Local(s) => s.lock().unwrap().data.auth_blobs.get(key).cloned(),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT value FROM auth_state WHERE id = ?",
                        vec![SqlParam::from(key)],
                    )
                    .await?;
                res.rows.first().and_then(|r| row_str(r, "value"))
            }
        };
        match hex_str {
            Some(h) if !h.is_empty() => Ok(hex::decode(h).ok()),
            _ => Ok(None),
        }
    }

    pub async fn clear_session_blob(&self, key: &str) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard.data.auth_blobs.remove(key);
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote("DELETE FROM auth_state WHERE id = ?", vec![SqlParam::from(key)])
                    .await?;
                Ok(())
            }
        }
    }

    // ---- Recent Conversations & Active Urgent Intakes ----
    pub async fn list_recent_conversations(&self, limit: i64) -> Result<Vec<ConversationState>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<ConversationState> = guard
                    .data
                    .conversations
                    .values()
                    .filter(|c| c.sender != "__owner_self_chat__")
                    .cloned()
                    .collect();
                list.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM conversations WHERE sender != '__owner_self_chat__' ORDER BY updated_at DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|row| ConversationState {
                        sender: row_str(row, "sender").unwrap_or_default(),
                        name: row_str(row, "name"),
                        draft: row_str(row, "draft_json")
                            .and_then(|s| serde_json::from_str(&s).ok())
                            .unwrap_or_default(),
                        urgent_draft: row_str(row, "urgent_draft_json")
                            .and_then(|s| serde_json::from_str(&s).ok())
                            .unwrap_or_default(),
                        history: row_str(row, "history_json")
                            .and_then(|s| serde_json::from_str(&s).ok())
                            .unwrap_or_default(),
                        last_booking_key: row_str(row, "last_booking_key"),
                        bot_active: row_i64(row, "bot_active") != 0,
                        owner_read_unanswered: row_i64(row, "owner_read_unanswered") != 0,
                        paused_until: row_i64(row, "paused_until"),
                        last_flagged_at: row_i64(row, "last_flagged_at"),
                        updated_at: row_i64(row, "updated_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn list_active_urgent_intakes(&self) -> Result<Vec<ConversationState>> {
        let recent = self.list_recent_conversations(50).await?;
        Ok(recent
            .into_iter()
            .filter(|c| c.urgent_draft.active)
            .collect())
    }

    // ---- Contact Tiers & Owner Availability ----
    pub async fn set_contact_tier(&self, sender: &str, tier: &str) -> Result<ContactMemory> {
        let clean_tier = match tier.trim().to_lowercase().as_str() {
            "vip" => "vip",
            "silent" | "observe" | "observe_only" => "silent",
            _ => "normal",
        };
        let mut cm = self
            .get_contact_memory(sender)
            .await?
            .unwrap_or_else(|| ContactMemory::new(sender));
        cm.tier = clean_tier.to_string();
        cm.updated_at = Utc::now().timestamp_millis();
        self.save_contact_memory(&cm).await?;
        Ok(cm)
    }

    pub async fn get_owner_status(&self, now_ms: i64) -> Result<OwnerStatus> {
        let Some(raw) = self.get_setting("owner_status_json").await? else {
            return Ok(OwnerStatus::available());
        };
        let status: OwnerStatus =
            serde_json::from_str(&raw).unwrap_or_else(|_| OwnerStatus::available());
        if status.until_ms > 0 && now_ms >= status.until_ms {
            let avail = OwnerStatus::available();
            let _ = self.set_owner_status(&avail).await;
            return Ok(avail);
        }
        Ok(status)
    }

    pub async fn set_owner_status(&self, status: &OwnerStatus) -> Result<()> {
        let raw = serde_json::to_string(status).unwrap_or_else(|_| "{}".to_string());
        self.set_setting("owner_status_json", &raw).await
    }

    // ---- Urgent Alerts (ntfy repeating ring-through-DND loop) ----
    pub async fn has_recent_urgent_alert(&self, sender: &str, since_ms: i64) -> Result<bool> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .urgent_alerts
                    .iter()
                    .any(|a| a.sender == sender && a.created_at >= since_ms))
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT id FROM urgent_alerts WHERE sender = ? AND created_at >= ? LIMIT 1",
                        vec![SqlParam::from(sender), SqlParam::Int(since_ms)],
                    )
                    .await?;
                Ok(!res.rows.is_empty())
            }
        }
    }

    pub async fn count_recent_urgent_alerts(&self, since_ms: i64) -> Result<usize> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .urgent_alerts
                    .iter()
                    .filter(|a| a.created_at >= since_ms)
                    .count())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT COUNT(*) AS cnt FROM urgent_alerts WHERE created_at >= ?",
                        vec![SqlParam::Int(since_ms)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .first()
                    .map(|r| row_i64(r, "cnt") as usize)
                    .unwrap_or(0))
            }
        }
    }

    pub async fn create_urgent_alert(&self, alert: &UrgentAlertRecord) -> Result<i64> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let id = guard.alloc_id();
                let mut copy = alert.clone();
                copy.id = id;
                guard.data.urgent_alerts.push(copy);
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "INSERT INTO urgent_alerts (sender, contact_name, phone, request, deadline, summary, created_at, last_sent_at, attempts, acknowledged)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        vec![
                            SqlParam::from(alert.sender.clone()),
                            SqlParam::from(alert.contact_name.clone()),
                            SqlParam::from(alert.phone.clone()),
                            SqlParam::from(alert.request.clone()),
                            SqlParam::from(alert.deadline.clone()),
                            SqlParam::from(alert.summary.clone()),
                            SqlParam::Int(alert.created_at),
                            SqlParam::Int(alert.last_sent_at),
                            SqlParam::Int(alert.attempts),
                            SqlParam::Int(if alert.acknowledged { 1 } else { 0 }),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn list_unacked_urgent_alerts(&self) -> Result<Vec<UrgentAlertRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .urgent_alerts
                    .iter()
                    .filter(|a| !a.acknowledged)
                    .cloned()
                    .collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM urgent_alerts WHERE acknowledged = 0 ORDER BY created_at ASC",
                        vec![],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| UrgentAlertRecord {
                        id: row_i64(r, "id"),
                        sender: row_str(r, "sender").unwrap_or_default(),
                        contact_name: row_str(r, "contact_name"),
                        phone: row_str(r, "phone").unwrap_or_default(),
                        request: row_str(r, "request").unwrap_or_default(),
                        deadline: row_str(r, "deadline").unwrap_or_default(),
                        summary: row_str(r, "summary").unwrap_or_default(),
                        created_at: row_i64(r, "created_at"),
                        last_sent_at: row_i64(r, "last_sent_at"),
                        attempts: row_i64(r, "attempts"),
                        acknowledged: row_i64(r, "acknowledged") != 0,
                    })
                    .collect())
            }
        }
    }

    pub async fn update_urgent_alert_sent(
        &self,
        id: i64,
        last_sent_at: i64,
        attempts: i64,
        acknowledged: bool,
    ) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(a) = guard.data.urgent_alerts.iter_mut().find(|a| a.id == id) {
                    a.last_sent_at = last_sent_at;
                    a.attempts = attempts;
                    a.acknowledged = acknowledged;
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "UPDATE urgent_alerts SET last_sent_at = ?, attempts = ?, acknowledged = ? WHERE id = ?",
                    vec![
                        SqlParam::Int(last_sent_at),
                        SqlParam::Int(attempts),
                        SqlParam::Int(if acknowledged { 1 } else { 0 }),
                        SqlParam::Int(id),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn ack_urgent_alerts(&self, filter_sender: Option<&str>) -> Result<u64> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let mut count = 0u64;
                for a in &mut guard.data.urgent_alerts {
                    if !a.acknowledged {
                        let matches = match filter_sender {
                            None => true,
                            Some(q) => {
                                let ql = q.to_lowercase();
                                a.sender.contains(q)
                                    || a.phone.contains(q)
                                    || a.contact_name
                                        .as_deref()
                                        .map(|n| n.to_lowercase().contains(&ql))
                                        .unwrap_or(false)
                            }
                        };
                        if matches {
                            a.acknowledged = true;
                            count += 1;
                        }
                    }
                }
                guard.flush()?;
                Ok(count)
            }
            TursoBackend::Remote { .. } => {
                let res = match filter_sender {
                    None => {
                        self.execute_remote(
                            "UPDATE urgent_alerts SET acknowledged = 1 WHERE acknowledged = 0",
                            vec![],
                        )
                        .await?
                    }
                    Some(q) => {
                        let like = format!("%{}%", q.to_lowercase());
                        self.execute_remote(
                            "UPDATE urgent_alerts SET acknowledged = 1 WHERE acknowledged = 0 AND (sender LIKE ? OR phone LIKE ? OR LOWER(COALESCE(contact_name, '')) LIKE ?)",
                            vec![
                                SqlParam::from(like.clone()),
                                SqlParam::from(like.clone()),
                                SqlParam::from(like),
                            ],
                        )
                        .await?
                    }
                };
                Ok(res.rows_affected)
            }
        }
    }

    // ---- 7:00 AM Morning Digest Queue ----
    pub async fn insert_digest_item(&self, item: &DigestItem) -> Result<i64> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let id = guard.alloc_id();
                let mut copy = item.clone();
                copy.id = id;
                guard.data.digest_queue.push(copy);
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "INSERT INTO digest_queue (sender, contact_name, kind, summary, last_message, created_at, delivered)
                         VALUES (?, ?, ?, ?, ?, ?, 0)",
                        vec![
                            SqlParam::from(item.sender.clone()),
                            SqlParam::from(item.contact_name.clone()),
                            SqlParam::from(item.kind.clone()),
                            SqlParam::from(item.summary.clone()),
                            SqlParam::from(item.last_message.clone()),
                            SqlParam::Int(item.created_at),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn list_pending_digest_items(&self, limit: i64) -> Result<Vec<DigestItem>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .digest_queue
                    .iter()
                    .filter(|d| !d.delivered)
                    .take(limit as usize)
                    .cloned()
                    .collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM digest_queue WHERE delivered = 0 ORDER BY created_at ASC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| DigestItem {
                        id: row_i64(r, "id"),
                        sender: row_str(r, "sender").unwrap_or_default(),
                        contact_name: row_str(r, "contact_name"),
                        kind: row_str(r, "kind").unwrap_or_else(|| "handoff".to_string()),
                        summary: row_str(r, "summary").unwrap_or_default(),
                        last_message: row_str(r, "last_message").unwrap_or_default(),
                        created_at: row_i64(r, "created_at"),
                        delivered: row_i64(r, "delivered") != 0,
                    })
                    .collect())
            }
        }
    }

    pub async fn mark_digest_items_delivered(&self, ids: &[i64]) -> Result<()> {
        if ids.is_empty() {
            return Ok(());
        }
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                for d in &mut guard.data.digest_queue {
                    if ids.contains(&d.id) {
                        d.delivered = true;
                    }
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                for &id in ids {
                    let _ = self
                        .execute_remote(
                            "UPDATE digest_queue SET delivered = 1 WHERE id = ?",
                            vec![SqlParam::Int(id)],
                        )
                        .await;
                }
                Ok(())
            }
        }
    }

    // ---- Reminders (One-Off & Recurring) ----
    pub async fn add_reminder(&self, due_at: i64, text: &str, now_ms: i64) -> Result<i64> {
        self.add_reminder_with_recurrence(due_at, text, None, now_ms)
            .await
    }

    pub async fn add_reminder_with_recurrence(
        &self,
        due_at: i64,
        text: &str,
        recurrence: Option<&str>,
        now_ms: i64,
    ) -> Result<i64> {
        let clean = text.trim();
        if clean.is_empty() {
            return Ok(0);
        }
        let rec = recurrence
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string());
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let id = guard.alloc_id();
                guard.data.reminders.push(ReminderRecord {
                    id,
                    due_at,
                    text: clean.to_string(),
                    recurrence: rec,
                    delivered: false,
                    created_at: now_ms,
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "INSERT INTO reminders (due_at, text, recurrence, delivered, created_at) VALUES (?, ?, ?, 0, ?)",
                        vec![
                            SqlParam::Int(due_at),
                            SqlParam::from(clean),
                            SqlParam::from(rec),
                            SqlParam::Int(now_ms),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn reschedule_recurring_reminder(&self, id: i64, next_due_at: i64) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(r) = guard.data.reminders.iter_mut().find(|r| r.id == id) {
                    r.due_at = next_due_at;
                    r.delivered = false;
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "UPDATE reminders SET due_at = ?, delivered = 0 WHERE id = ?",
                    vec![SqlParam::Int(next_due_at), SqlParam::Int(id)],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn list_due_reminders(&self, now_ms: i64) -> Result<Vec<ReminderRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard
                    .data
                    .reminders
                    .iter()
                    .filter(|r| !r.delivered && r.due_at <= now_ms)
                    .cloned()
                    .collect())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM reminders WHERE delivered = 0 AND due_at <= ? ORDER BY due_at ASC",
                        vec![SqlParam::Int(now_ms)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| ReminderRecord {
                        id: row_i64(r, "id"),
                        due_at: row_i64(r, "due_at"),
                        text: row_str(r, "text").unwrap_or_default(),
                        recurrence: row_str(r, "recurrence").filter(|s| !s.is_empty()),
                        delivered: row_i64(r, "delivered") != 0,
                        created_at: row_i64(r, "created_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn list_pending_reminders(&self, limit: i64) -> Result<Vec<ReminderRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<ReminderRecord> = guard
                    .data
                    .reminders
                    .iter()
                    .filter(|r| !r.delivered)
                    .cloned()
                    .collect();
                list.sort_by_key(|r| r.due_at);
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM reminders WHERE delivered = 0 ORDER BY due_at ASC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| ReminderRecord {
                        id: row_i64(r, "id"),
                        due_at: row_i64(r, "due_at"),
                        text: row_str(r, "text").unwrap_or_default(),
                        recurrence: row_str(r, "recurrence").filter(|s| !s.is_empty()),
                        delivered: row_i64(r, "delivered") != 0,
                        created_at: row_i64(r, "created_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn mark_reminder_delivered(&self, id: i64) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(r) = guard.data.reminders.iter_mut().find(|r| r.id == id) {
                    r.delivered = true;
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "UPDATE reminders SET delivered = 1 WHERE id = ?",
                    vec![SqlParam::Int(id)],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn delete_reminder(&self, id: i64) -> Result<bool> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let before = guard.data.reminders.len();
                guard.data.reminders.retain(|r| r.id != id);
                let deleted = guard.data.reminders.len() < before;
                if deleted {
                    guard.flush()?;
                }
                Ok(deleted)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("DELETE FROM reminders WHERE id = ?", vec![SqlParam::Int(id)])
                    .await?;
                Ok(res.rows_affected > 0)
            }
        }
    }

    // ---- Smart Follow-Up Queue (Approval-First across Personal, Business & Aurikrex Chats) ----
    #[allow(clippy::too_many_arguments)]
    pub async fn upsert_followup(
        &self,
        sender: &str,
        contact_name: Option<&str>,
        kind: &str,
        reason: &str,
        suggested_reply: &str,
        due_at: i64,
        now_ms: i64,
    ) -> Result<i64> {
        let clean_sender = sender.trim();
        let clean_reason = reason.trim();
        let clean_reply = suggested_reply.trim();
        if clean_sender.is_empty() || clean_reason.is_empty() || clean_reply.is_empty() {
            return Ok(0);
        }
        let clean_kind = match kind.trim().to_lowercase().as_str() {
            "falcon_promised" | "owner_promised" => "falcon_promised",
            "waiting_on_them" | "they_promised" => "waiting_on_them",
            _ => "cold_lead",
        };
        let status = if due_at <= now_ms { "ready" } else { "watching" };
        let cname = contact_name
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string());

        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(existing) = guard
                    .data
                    .followups
                    .iter_mut()
                    .find(|f| f.sender == clean_sender && matches!(f.status.as_str(), "watching" | "ready"))
                {
                    if cname.is_some() {
                        existing.contact_name = cname;
                    }
                    existing.kind = clean_kind.to_string();
                    existing.reason = clean_reason.to_string();
                    existing.suggested_reply = clean_reply.to_string();
                    existing.due_at = due_at;
                    existing.status = status.to_string();
                    existing.updated_at = now_ms;
                    let id = existing.id;
                    guard.flush()?;
                    return Ok(id);
                }
                let id = guard.alloc_id();
                guard.data.followups.push(FollowupRecord {
                    id,
                    sender: clean_sender.to_string(),
                    contact_name: cname,
                    kind: clean_kind.to_string(),
                    reason: clean_reason.to_string(),
                    suggested_reply: clean_reply.to_string(),
                    due_at,
                    status: status.to_string(),
                    created_at: now_ms,
                    updated_at: now_ms,
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let existing = self
                    .execute_remote(
                        "SELECT id, contact_name FROM followups WHERE sender = ? AND status IN ('watching', 'ready') ORDER BY id DESC LIMIT 1",
                        vec![SqlParam::from(clean_sender)],
                    )
                    .await?;
                if let Some(row) = existing.rows.first() {
                    let id = row_i64(row, "id");
                    let merged_name = cname.or_else(|| row_str(row, "contact_name"));
                    self.execute_remote(
                        "UPDATE followups SET contact_name = ?, kind = ?, reason = ?, suggested_reply = ?, due_at = ?, status = ?, updated_at = ? WHERE id = ?",
                        vec![
                            SqlParam::from(merged_name),
                            SqlParam::from(clean_kind),
                            SqlParam::from(clean_reason),
                            SqlParam::from(clean_reply),
                            SqlParam::Int(due_at),
                            SqlParam::from(status),
                            SqlParam::Int(now_ms),
                            SqlParam::Int(id),
                        ],
                    )
                    .await?;
                    return Ok(id);
                }
                let res = self
                    .execute_remote(
                        "INSERT INTO followups (sender, contact_name, kind, reason, suggested_reply, due_at, status, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        vec![
                            SqlParam::from(clean_sender),
                            SqlParam::from(cname),
                            SqlParam::from(clean_kind),
                            SqlParam::from(clean_reason),
                            SqlParam::from(clean_reply),
                            SqlParam::Int(due_at),
                            SqlParam::from(status),
                            SqlParam::Int(now_ms),
                            SqlParam::Int(now_ms),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn resolve_followups_for_sender(&self, sender: &str) -> Result<u64> {
        let now_ms = Utc::now().timestamp_millis();
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let mut count = 0u64;
                for f in &mut guard.data.followups {
                    if f.sender == sender && matches!(f.status.as_str(), "watching" | "ready") {
                        f.status = "dismissed".to_string();
                        f.updated_at = now_ms;
                        count += 1;
                    }
                }
                if count > 0 {
                    guard.flush()?;
                }
                Ok(count)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "UPDATE followups SET status = 'dismissed', updated_at = ? WHERE sender = ? AND status IN ('watching', 'ready')",
                        vec![SqlParam::Int(now_ms), SqlParam::from(sender)],
                    )
                    .await?;
                Ok(res.rows_affected)
            }
        }
    }

    pub async fn promote_due_followups(&self, now_ms: i64) -> Result<Vec<FollowupRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let mut promoted = Vec::new();
                for f in &mut guard.data.followups {
                    if f.status == "watching" && f.due_at <= now_ms {
                        f.status = "ready".to_string();
                        f.updated_at = now_ms;
                        promoted.push(f.clone());
                    }
                }
                if !promoted.is_empty() {
                    guard.flush()?;
                }
                Ok(promoted)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM followups WHERE status = 'watching' AND due_at <= ? ORDER BY due_at ASC",
                        vec![SqlParam::Int(now_ms)],
                    )
                    .await?;
                let mut promoted = Vec::new();
                for r in &res.rows {
                    let id = row_i64(r, "id");
                    let _ = self
                        .execute_remote(
                            "UPDATE followups SET status = 'ready', updated_at = ? WHERE id = ?",
                            vec![SqlParam::Int(now_ms), SqlParam::Int(id)],
                        )
                        .await;
                    promoted.push(FollowupRecord {
                        id,
                        sender: row_str(r, "sender").unwrap_or_default(),
                        contact_name: row_str(r, "contact_name"),
                        kind: row_str(r, "kind").unwrap_or_else(|| "cold_lead".to_string()),
                        reason: row_str(r, "reason").unwrap_or_default(),
                        suggested_reply: row_str(r, "suggested_reply").unwrap_or_default(),
                        due_at: row_i64(r, "due_at"),
                        status: "ready".to_string(),
                        created_at: row_i64(r, "created_at"),
                        updated_at: now_ms,
                    });
                }
                Ok(promoted)
            }
        }
    }

    pub async fn list_followups(
        &self,
        status_filter: Option<&str>,
        limit: i64,
    ) -> Result<Vec<FollowupRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<FollowupRecord> = guard
                    .data
                    .followups
                    .iter()
                    .filter(|f| match status_filter {
                        None => true,
                        Some("open") => matches!(f.status.as_str(), "ready" | "watching"),
                        Some(st) => f.status == st,
                    })
                    .cloned()
                    .collect();
                list.sort_by(|a, b| {
                    let a_ready = a.status == "ready";
                    let b_ready = b.status == "ready";
                    b_ready.cmp(&a_ready).then_with(|| a.due_at.cmp(&b.due_at))
                });
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = match status_filter {
                    None => {
                        self.execute_remote(
                            "SELECT * FROM followups ORDER BY due_at ASC LIMIT ?",
                            vec![SqlParam::Int(limit)],
                        )
                        .await?
                    }
                    Some("open") => {
                        self.execute_remote(
                            "SELECT * FROM followups WHERE status IN ('ready', 'watching') ORDER BY CASE WHEN status = 'ready' THEN 0 ELSE 1 END, due_at ASC LIMIT ?",
                            vec![SqlParam::Int(limit)],
                        )
                        .await?
                    }
                    Some(st) => {
                        self.execute_remote(
                            "SELECT * FROM followups WHERE status = ? ORDER BY due_at ASC LIMIT ?",
                            vec![SqlParam::from(st), SqlParam::Int(limit)],
                        )
                        .await?
                    }
                };
                Ok(res
                    .rows
                    .iter()
                    .map(|r| FollowupRecord {
                        id: row_i64(r, "id"),
                        sender: row_str(r, "sender").unwrap_or_default(),
                        contact_name: row_str(r, "contact_name"),
                        kind: row_str(r, "kind").unwrap_or_else(|| "cold_lead".to_string()),
                        reason: row_str(r, "reason").unwrap_or_default(),
                        suggested_reply: row_str(r, "suggested_reply").unwrap_or_default(),
                        due_at: row_i64(r, "due_at"),
                        status: row_str(r, "status").unwrap_or_else(|| "watching".to_string()),
                        created_at: row_i64(r, "created_at"),
                        updated_at: row_i64(r, "updated_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn get_followup_by_id(&self, id: i64) -> Result<Option<FollowupRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(guard.data.followups.iter().find(|f| f.id == id).cloned())
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM followups WHERE id = ? LIMIT 1",
                        vec![SqlParam::Int(id)],
                    )
                    .await?;
                Ok(res.rows.first().map(|r| FollowupRecord {
                    id: row_i64(r, "id"),
                    sender: row_str(r, "sender").unwrap_or_default(),
                    contact_name: row_str(r, "contact_name"),
                    kind: row_str(r, "kind").unwrap_or_else(|| "cold_lead".to_string()),
                    reason: row_str(r, "reason").unwrap_or_default(),
                    suggested_reply: row_str(r, "suggested_reply").unwrap_or_default(),
                    due_at: row_i64(r, "due_at"),
                    status: row_str(r, "status").unwrap_or_else(|| "watching".to_string()),
                    created_at: row_i64(r, "created_at"),
                    updated_at: row_i64(r, "updated_at"),
                }))
            }
        }
    }

    pub async fn update_followup_status(
        &self,
        id: i64,
        status: &str,
        new_due_at: Option<i64>,
        now_ms: i64,
    ) -> Result<bool> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(f) = guard.data.followups.iter_mut().find(|f| f.id == id) {
                    f.status = status.to_string();
                    if let Some(due) = new_due_at {
                        f.due_at = due;
                    }
                    f.updated_at = now_ms;
                    guard.flush()?;
                    return Ok(true);
                }
                Ok(false)
            }
            TursoBackend::Remote { .. } => {
                let res = if let Some(due) = new_due_at {
                    self.execute_remote(
                        "UPDATE followups SET status = ?, due_at = ?, updated_at = ? WHERE id = ?",
                        vec![
                            SqlParam::from(status),
                            SqlParam::Int(due),
                            SqlParam::Int(now_ms),
                            SqlParam::Int(id),
                        ],
                    )
                    .await?
                } else {
                    self.execute_remote(
                        "UPDATE followups SET status = ?, updated_at = ? WHERE id = ?",
                        vec![
                            SqlParam::from(status),
                            SqlParam::Int(now_ms),
                            SqlParam::Int(id),
                        ],
                    )
                    .await?
                };
                Ok(res.rows_affected > 0)
            }
        }
    }

    // ---- Multi-Account Priority Gmail Items ----
    pub async fn has_email_item(&self, msg_id: &str) -> Result<bool> {
        match &self.backend {
            TursoBackend::Local(s) => Ok(s.lock().unwrap().data.email_items.contains_key(msg_id)),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT msg_id FROM email_items WHERE msg_id = ? LIMIT 1",
                        vec![SqlParam::from(msg_id)],
                    )
                    .await?;
                Ok(!res.rows.is_empty())
            }
        }
    }

    pub async fn upsert_email_item(&self, item: &EmailItemRecord) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                guard
                    .data
                    .email_items
                    .insert(item.msg_id.clone(), item.clone());
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "INSERT INTO email_items (msg_id, account_label, thread_id, sender, subject, snippet, category, priority, summary, received_at, alerted, digest_delivered, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(msg_id) DO UPDATE SET
                       category = excluded.category,
                       priority = excluded.priority,
                       summary = excluded.summary,
                       alerted = CASE WHEN email_items.alerted = 1 THEN 1 ELSE excluded.alerted END,
                       digest_delivered = CASE WHEN email_items.digest_delivered = 1 THEN 1 ELSE excluded.digest_delivered END",
                    vec![
                        SqlParam::from(item.msg_id.clone()),
                        SqlParam::from(item.account_label.clone()),
                        SqlParam::from(item.thread_id.clone()),
                        SqlParam::from(item.sender.clone()),
                        SqlParam::from(item.subject.clone()),
                        SqlParam::from(item.snippet.clone()),
                        SqlParam::from(item.category.clone()),
                        SqlParam::from(item.priority.clone()),
                        SqlParam::from(item.summary.clone()),
                        SqlParam::Int(item.received_at),
                        SqlParam::Int(if item.alerted { 1 } else { 0 }),
                        SqlParam::Int(if item.digest_delivered { 1 } else { 0 }),
                        SqlParam::Int(item.created_at),
                    ],
                )
                .await?;
                Ok(())
            }
        }
    }

    fn parse_email_item(r: &HashMap<String, Value>) -> EmailItemRecord {
        EmailItemRecord {
            msg_id: row_str(r, "msg_id").unwrap_or_default(),
            account_label: row_str(r, "account_label").unwrap_or_default(),
            thread_id: row_str(r, "thread_id").unwrap_or_default(),
            sender: row_str(r, "sender").unwrap_or_default(),
            subject: row_str(r, "subject").unwrap_or_default(),
            snippet: row_str(r, "snippet").unwrap_or_default(),
            category: row_str(r, "category").unwrap_or_else(|| "direct_human".to_string()),
            priority: row_str(r, "priority").unwrap_or_else(|| "important".to_string()),
            summary: row_str(r, "summary").unwrap_or_default(),
            received_at: row_i64(r, "received_at"),
            alerted: row_i64(r, "alerted") != 0,
            digest_delivered: row_i64(r, "digest_delivered") != 0,
            created_at: row_i64(r, "created_at"),
        }
    }

    pub async fn list_pending_digest_emails(&self, limit: i64) -> Result<Vec<EmailItemRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<EmailItemRecord> = guard
                    .data
                    .email_items
                    .values()
                    .filter(|e| !e.digest_delivered && matches!(e.priority.as_str(), "urgent" | "important"))
                    .cloned()
                    .collect();
                list.sort_by(|a, b| {
                    let a_urg = a.priority == "urgent";
                    let b_urg = b.priority == "urgent";
                    b_urg.cmp(&a_urg).then_with(|| b.received_at.cmp(&a.received_at))
                });
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM email_items WHERE digest_delivered = 0 AND priority IN ('urgent', 'important') ORDER BY CASE WHEN priority = 'urgent' THEN 0 ELSE 1 END, received_at DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res.rows.iter().map(Self::parse_email_item).collect())
            }
        }
    }

    pub async fn list_recent_priority_emails(&self, limit: i64) -> Result<Vec<EmailItemRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<EmailItemRecord> = guard
                    .data
                    .email_items
                    .values()
                    .filter(|e| matches!(e.priority.as_str(), "urgent" | "important"))
                    .cloned()
                    .collect();
                list.sort_by(|a, b| b.received_at.cmp(&a.received_at));
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM email_items WHERE priority IN ('urgent', 'important') ORDER BY received_at DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res.rows.iter().map(Self::parse_email_item).collect())
            }
        }
    }

    pub async fn mark_emails_digest_delivered(&self, msg_ids: &[String]) -> Result<()> {
        if msg_ids.is_empty() {
            return Ok(());
        }
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                for id in msg_ids {
                    if let Some(e) = guard.data.email_items.get_mut(id) {
                        e.digest_delivered = true;
                    }
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                for id in msg_ids {
                    let _ = self
                        .execute_remote(
                            "UPDATE email_items SET digest_delivered = 1 WHERE msg_id = ?",
                            vec![SqlParam::from(id.clone())],
                        )
                        .await;
                }
                Ok(())
            }
        }
    }

    pub async fn mark_email_alerted(&self, msg_id: &str) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(e) = guard.data.email_items.get_mut(msg_id) {
                    e.alerted = true;
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "UPDATE email_items SET alerted = 1 WHERE msg_id = ?",
                    vec![SqlParam::from(msg_id)],
                )
                .await?;
                Ok(())
            }
        }
    }

    // ---- Scheduled WhatsApp & SMS Messages (Feature J) ----
    pub async fn add_scheduled_message(
        &self,
        target: &str,
        contact_name: Option<&str>,
        channel: &str,
        message: &str,
        due_at: i64,
        now_ms: i64,
    ) -> Result<i64> {
        let clean_target = target.trim();
        let clean_msg = message.trim();
        if clean_target.is_empty() || clean_msg.is_empty() {
            return Ok(0);
        }
        let chan = if channel.trim().eq_ignore_ascii_case("sms") {
            "sms"
        } else {
            "whatsapp"
        };
        let cname = contact_name
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string());
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let id = guard.alloc_id();
                guard.data.scheduled_messages.push(ScheduledMessageRecord {
                    id,
                    target: clean_target.to_string(),
                    contact_name: cname,
                    channel: chan.to_string(),
                    message: clean_msg.to_string(),
                    due_at,
                    status: "pending".to_string(),
                    created_at: now_ms,
                    updated_at: now_ms,
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "INSERT INTO scheduled_messages (target, contact_name, channel, message, due_at, status, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)",
                        vec![
                            SqlParam::from(clean_target),
                            SqlParam::from(cname),
                            SqlParam::from(chan),
                            SqlParam::from(clean_msg),
                            SqlParam::Int(due_at),
                            SqlParam::Int(now_ms),
                            SqlParam::Int(now_ms),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    fn parse_scheduled_message(r: &HashMap<String, Value>) -> ScheduledMessageRecord {
        ScheduledMessageRecord {
            id: row_i64(r, "id"),
            target: row_str(r, "target").unwrap_or_default(),
            contact_name: row_str(r, "contact_name"),
            channel: row_str(r, "channel").unwrap_or_else(|| "whatsapp".to_string()),
            message: row_str(r, "message").unwrap_or_default(),
            due_at: row_i64(r, "due_at"),
            status: row_str(r, "status").unwrap_or_else(|| "pending".to_string()),
            created_at: row_i64(r, "created_at"),
            updated_at: row_i64(r, "updated_at"),
        }
    }

    pub async fn list_due_scheduled_messages(&self, now_ms: i64) -> Result<Vec<ScheduledMessageRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<ScheduledMessageRecord> = guard
                    .data
                    .scheduled_messages
                    .iter()
                    .filter(|m| m.status == "pending" && m.due_at <= now_ms)
                    .cloned()
                    .collect();
                list.sort_by_key(|m| m.due_at);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM scheduled_messages WHERE status = 'pending' AND due_at <= ? ORDER BY due_at ASC",
                        vec![SqlParam::Int(now_ms)],
                    )
                    .await?;
                Ok(res.rows.iter().map(Self::parse_scheduled_message).collect())
            }
        }
    }

    pub async fn list_pending_scheduled_messages(&self, limit: i64) -> Result<Vec<ScheduledMessageRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<ScheduledMessageRecord> = guard
                    .data
                    .scheduled_messages
                    .iter()
                    .filter(|m| m.status == "pending")
                    .cloned()
                    .collect();
                list.sort_by_key(|m| m.due_at);
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM scheduled_messages WHERE status = 'pending' ORDER BY due_at ASC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res.rows.iter().map(Self::parse_scheduled_message).collect())
            }
        }
    }

    pub async fn update_scheduled_message_status(
        &self,
        id: i64,
        status: &str,
        now_ms: i64,
    ) -> Result<bool> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(m) = guard.data.scheduled_messages.iter_mut().find(|m| m.id == id) {
                    m.status = status.to_string();
                    m.updated_at = now_ms;
                    guard.flush()?;
                    return Ok(true);
                }
                Ok(false)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "UPDATE scheduled_messages SET status = ?, updated_at = ? WHERE id = ?",
                        vec![SqlParam::from(status), SqlParam::Int(now_ms), SqlParam::Int(id)],
                    )
                    .await?;
                Ok(res.rows_affected > 0)
            }
        }
    }

    // ---- Bank / Paystack / Stripe Alert Ledger (Feature #4) ----
    #[allow(clippy::too_many_arguments)]
    pub async fn insert_bank_alert(
        &self,
        source: &str,
        bank: &str,
        direction: &str,
        amount_minor: i64,
        currency: &str,
        counterparty: &str,
        raw_summary: &str,
        now_ms: i64,
    ) -> Result<i64> {
        let dir = if direction.trim().eq_ignore_ascii_case("debit") {
            "debit"
        } else {
            "credit"
        };
        let cur = if currency.trim().is_empty() {
            "NGN"
        } else {
            currency.trim()
        };
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                // Simple 2-minute duplicate suppression for identical bank alert notifications
                if let Some(dup) = guard.data.bank_alerts.iter().rev().take(10).find(|b| {
                    b.bank.eq_ignore_ascii_case(bank)
                        && b.direction == dir
                        && b.amount_minor == amount_minor
                        && b.raw_summary == raw_summary
                        && (now_ms - b.created_at).abs() < 120_000
                }) {
                    return Ok(dup.id);
                }
                let id = guard.alloc_id();
                guard.data.bank_alerts.push(BankAlertRecord {
                    id,
                    source: source.to_string(),
                    bank: bank.to_string(),
                    direction: dir.to_string(),
                    amount_minor,
                    currency: cur.to_uppercase(),
                    counterparty: counterparty.trim().to_string(),
                    raw_summary: raw_summary.chars().take(240).collect(),
                    created_at: now_ms,
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let dup = self
                    .execute_remote(
                        "SELECT id FROM bank_alerts WHERE bank = ? AND direction = ? AND amount_minor = ? AND raw_summary = ? AND created_at >= ? LIMIT 1",
                        vec![
                            SqlParam::from(bank),
                            SqlParam::from(dir),
                            SqlParam::Int(amount_minor),
                            SqlParam::from(raw_summary.chars().take(240).collect::<String>()),
                            SqlParam::Int(now_ms - 120_000),
                        ],
                    )
                    .await?;
                if let Some(row) = dup.rows.first() {
                    return Ok(row_i64(row, "id"));
                }
                let res = self
                    .execute_remote(
                        "INSERT INTO bank_alerts (source, bank, direction, amount_minor, currency, counterparty, raw_summary, created_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        vec![
                            SqlParam::from(source),
                            SqlParam::from(bank),
                            SqlParam::from(dir),
                            SqlParam::Int(amount_minor),
                            SqlParam::from(cur.to_uppercase()),
                            SqlParam::from(counterparty.trim()),
                            SqlParam::from(raw_summary.chars().take(240).collect::<String>()),
                            SqlParam::Int(now_ms),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn list_bank_alerts(
        &self,
        since_ms: i64,
        limit: i64,
    ) -> Result<Vec<BankAlertRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<BankAlertRecord> = guard
                    .data
                    .bank_alerts
                    .iter()
                    .filter(|b| b.created_at >= since_ms)
                    .cloned()
                    .collect();
                list.sort_by(|a, b| b.created_at.cmp(&a.created_at));
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM bank_alerts WHERE created_at >= ? ORDER BY created_at DESC LIMIT ?",
                        vec![SqlParam::Int(since_ms), SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| BankAlertRecord {
                        id: row_i64(r, "id"),
                        source: row_str(r, "source").unwrap_or_default(),
                        bank: row_str(r, "bank").unwrap_or_default(),
                        direction: row_str(r, "direction").unwrap_or_else(|| "credit".to_string()),
                        amount_minor: row_i64(r, "amount_minor"),
                        currency: row_str(r, "currency").unwrap_or_else(|| "NGN".to_string()),
                        counterparty: row_str(r, "counterparty").unwrap_or_default(),
                        raw_summary: row_str(r, "raw_summary").unwrap_or_default(),
                        created_at: row_i64(r, "created_at"),
                    })
                    .collect())
            }
        }
    }

    // ---- Android Companion Devices, SSE Events & Telemetry ----
    pub async fn register_phone_device(
        &self,
        device_name: &str,
        token_hash: &str,
        now_ms: i64,
    ) -> Result<i64> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let id = guard.alloc_id();
                guard.data.phone_devices.push(PhoneDeviceRecord {
                    id,
                    device_name: device_name.to_string(),
                    token_hash: token_hash.to_string(),
                    active: true,
                    created_at: now_ms,
                    last_seen_at: now_ms,
                });
                guard.flush()?;
                Ok(id)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "INSERT INTO phone_devices (device_name, token_hash, active, created_at, last_seen_at) VALUES (?, ?, 1, ?, ?)",
                        vec![
                            SqlParam::from(device_name),
                            SqlParam::from(token_hash),
                            SqlParam::Int(now_ms),
                            SqlParam::Int(now_ms),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn verify_phone_device_hash(&self, token_hash: &str, now_ms: i64) -> Result<Option<PhoneDeviceRecord>> {
        let devices = self.list_active_phone_devices().await?;
        // Constant-time byte comparison across active hashed tokens
        let want = token_hash.as_bytes();
        let matched = devices.into_iter().find(|d| {
            let got = d.token_hash.as_bytes();
            if got.len() != want.len() {
                return false;
            }
            let mut diff = 0u8;
            for (a, b) in got.iter().zip(want.iter()) {
                diff |= a ^ b;
            }
            diff == 0
        });
        if let Some(ref dev) = matched {
            let _ = self.touch_phone_device(dev.id, now_ms).await;
        }
        Ok(matched)
    }

    async fn touch_phone_device(&self, id: i64, now_ms: i64) -> Result<()> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                if let Some(d) = guard.data.phone_devices.iter_mut().find(|d| d.id == id) {
                    d.last_seen_at = now_ms;
                }
                guard.flush()?;
                Ok(())
            }
            TursoBackend::Remote { .. } => {
                self.execute_remote(
                    "UPDATE phone_devices SET last_seen_at = ? WHERE id = ?",
                    vec![SqlParam::Int(now_ms), SqlParam::Int(id)],
                )
                .await?;
                Ok(())
            }
        }
    }

    pub async fn list_active_phone_devices(&self) -> Result<Vec<PhoneDeviceRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => Ok(s
                .lock()
                .unwrap()
                .data
                .phone_devices
                .iter()
                .filter(|d| d.active)
                .cloned()
                .collect()),
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM phone_devices WHERE active = 1 ORDER BY last_seen_at DESC",
                        vec![],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| PhoneDeviceRecord {
                        id: row_i64(r, "id"),
                        device_name: row_str(r, "device_name").unwrap_or_default(),
                        token_hash: row_str(r, "token_hash").unwrap_or_default(),
                        active: row_i64(r, "active") != 0,
                        created_at: row_i64(r, "created_at"),
                        last_seen_at: row_i64(r, "last_seen_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn revoke_all_phone_devices(&self) -> Result<u64> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let mut cnt = 0u64;
                for d in &mut guard.data.phone_devices {
                    if d.active {
                        d.active = false;
                        cnt += 1;
                    }
                }
                guard.flush()?;
                Ok(cnt)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote("UPDATE phone_devices SET active = 0 WHERE active = 1", vec![])
                    .await?;
                Ok(res.rows_affected)
            }
        }
    }

    pub async fn append_phone_event(
        &self,
        event_type: &str,
        payload_json: &str,
        now_ms: i64,
    ) -> Result<PhoneEventRecord> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let mut guard = s.lock().unwrap();
                let id = guard.alloc_id();
                let rec = PhoneEventRecord {
                    id,
                    event_type: event_type.to_string(),
                    payload_json: payload_json.to_string(),
                    created_at: now_ms,
                };
                guard.data.phone_events.push(rec.clone());
                if guard.data.phone_events.len() > 200 {
                    let excess = guard.data.phone_events.len() - 200;
                    guard.data.phone_events.drain(0..excess);
                }
                guard.flush()?;
                Ok(rec)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "INSERT INTO phone_events (event_type, payload_json, created_at) VALUES (?, ?, ?)",
                        vec![
                            SqlParam::from(event_type),
                            SqlParam::from(payload_json),
                            SqlParam::Int(now_ms),
                        ],
                    )
                    .await?;
                // Prune events older than 48h occasionally
                let _ = self
                    .execute_remote(
                        "DELETE FROM phone_events WHERE created_at < ?",
                        vec![SqlParam::Int(now_ms - 48 * 3_600_000)],
                    )
                    .await;
                Ok(PhoneEventRecord {
                    id: res.last_insert_rowid,
                    event_type: event_type.to_string(),
                    payload_json: payload_json.to_string(),
                    created_at: now_ms,
                })
            }
        }
    }

    pub async fn list_phone_events_since(&self, since_id: i64, limit: i64) -> Result<Vec<PhoneEventRecord>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list: Vec<PhoneEventRecord> = guard
                    .data
                    .phone_events
                    .iter()
                    .filter(|e| e.id > since_id)
                    .cloned()
                    .collect();
                list.sort_by_key(|e| e.id);
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM phone_events WHERE id > ? ORDER BY id ASC LIMIT ?",
                        vec![SqlParam::Int(since_id), SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| PhoneEventRecord {
                        id: row_i64(r, "id"),
                        event_type: row_str(r, "event_type").unwrap_or_default(),
                        payload_json: row_str(r, "payload_json").unwrap_or_default(),
                        created_at: row_i64(r, "created_at"),
                    })
                    .collect())
            }
        }
    }

    pub async fn get_phone_telemetry(&self) -> Result<Option<PhoneTelemetrySnapshot>> {
        let Some(raw) = self.get_setting("phone_telemetry_json").await? else {
            return Ok(None);
        };
        Ok(serde_json::from_str::<PhoneTelemetrySnapshot>(&raw).ok())
    }

    pub async fn save_phone_telemetry(&self, snap: &PhoneTelemetrySnapshot) -> Result<()> {
        let raw = serde_json::to_string(snap).unwrap_or_else(|_| "{}".to_string());
        self.set_setting("phone_telemetry_json", &raw).await
    }

    /// Returns true if the Infinix Hot 10T recently sent a Dead-Battery (`<=10%` unplugged) beacon,
    /// enabling automatic 0-minute WhatsApp cover until the phone charges or reconnects.
    pub async fn is_phone_low_battery_cover(&self, now_ms: i64) -> Result<bool> {
        let Some(snap) = self.get_phone_telemetry().await? else {
            return Ok(false);
        };
        if !snap.low_battery_cover {
            return Ok(false);
        }
        // Automatically expire stale cover after 12 hours just in case
        if now_ms - snap.updated_at > 12 * 3_600_000 {
            return Ok(false);
        }
        Ok(true)
    }

    /// Fast `<5ms` caller pre-brief lookup for incoming Android phone calls (Feature A).
    pub async fn get_caller_brief_context(&self, raw_phone: &str) -> Result<CallerBriefContext> {
        let norm = crate::util::normalize_number(raw_phone);
        let lookup_key = if norm.is_empty() {
            raw_phone.trim().to_string()
        } else {
            norm.clone()
        };
        let cm = self.get_contact_memory(&lookup_key).await?;
        let conv = self.get_conversation(&lookup_key).await?;
        let saved_name = self
            .get_saved_contact_name(&[lookup_key.clone()])
            .await
            .ok()
            .flatten();
        let display_name = cm
            .as_ref()
            .and_then(|c| c.display_name.clone())
            .or_else(|| conv.as_ref().and_then(|c| c.name.clone()))
            .or(saved_name);

        let all_bookings = self.list_bookings(30).await.unwrap_or_default();
        let matching_bookings: Vec<String> = all_bookings
            .into_iter()
            .filter(|b| {
                let b_chat = crate::util::normalize_number(&b.chat);
                let b_num = crate::util::normalize_number(&b.number);
                (!lookup_key.is_empty() && (b_chat == lookup_key || b_num == lookup_key))
                    || display_name
                        .as_deref()
                        .map(|n| b.name.to_lowercase().contains(&n.to_lowercase()))
                        .unwrap_or(false)
            })
            .take(3)
            .map(|b| format!("{} {} ({})", b.subject, b.exam, b.slot))
            .collect();

        let open_fu: Vec<String> = self
            .list_followups(Some("open"), 20)
            .await
            .unwrap_or_default()
            .into_iter()
            .filter(|f| f.sender == lookup_key)
            .map(|f| format!("[#{}] {}", f.id, f.reason))
            .collect();

        let last_chat_snippet = conv
            .as_ref()
            .and_then(|c| c.history.last())
            .map(|t| {
                let who = if t.role == "user" { "They said" } else { "Last reply" };
                format!("{}: {}", who, t.content.chars().take(110).collect::<String>())
            });

        let mut parts: Vec<String> = Vec::new();
        if let Some(ref rel) = cm.as_ref().and_then(|c| c.relationship.clone()) {
            parts.push(rel.clone());
        }
        if !matching_bookings.is_empty() {
            parts.push(format!("Booked: {}", matching_bookings.join(", ")));
        }
        if !open_fu.is_empty() {
            parts.push(format!("Follow-up: {}", open_fu[0]));
        }
        if let Some(ref snip) = last_chat_snippet {
            parts.push(snip.clone());
        }
        let summary_line = if parts.is_empty() {
            "No prior WhatsApp/booking history yet.".to_string()
        } else {
            parts.join(" | ")
        };

        Ok(CallerBriefContext {
            phone: lookup_key,
            display_name,
            relationship: cm.as_ref().and_then(|c| c.relationship.clone()),
            tier: cm
                .as_ref()
                .map(|c| c.tier.clone())
                .unwrap_or_else(|| "normal".to_string()),
            summary_line,
            open_followups: open_fu,
            bookings: matching_bookings,
            last_chat_snippet,
        })
    }
}
