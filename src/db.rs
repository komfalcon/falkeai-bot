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
        content TEXT NOT NULL,
        source TEXT NOT NULL DEFAULT 'owner',
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationState {
    pub sender: String,
    pub name: Option<String>,
    pub draft: BookingDraft,
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactMemory {
    pub sender: String,
    pub display_name: Option<String>,
    pub aliases: Vec<String>,
    pub relationship: Option<String>,
    pub tone: Option<String>,
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
    pub content: String,
    pub source: String,
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
                let history: Vec<ChatTurn> = row_str(row, "history_json")
                    .and_then(|s| serde_json::from_str(&s).ok())
                    .unwrap_or_default();

                Ok(Some(ConversationState {
                    sender: row_str(row, "sender").unwrap_or_else(|| sender.to_string()),
                    name: row_str(row, "name"),
                    draft,
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
                let history_json =
                    serde_json::to_string(&state.history).unwrap_or_else(|_| "[]".to_string());
                self.execute_remote(
                    "INSERT INTO conversations (sender, name, draft_json, history_json, last_booking_key, bot_active, owner_read_unanswered, paused_until, last_flagged_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(sender) DO UPDATE SET
                       name = excluded.name,
                       draft_json = excluded.draft_json,
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
        ContactMemory {
            sender: row_str(row, "sender").unwrap_or_default(),
            display_name: row_str(row, "display_name"),
            aliases: row_str(row, "aliases_json")
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default(),
            relationship: row_str(row, "relationship"),
            tone: row_str(row, "tone"),
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
                    "INSERT INTO contact_memory (sender, display_name, aliases_json, relationship, tone, owner_style_pattern, sample_owner_replies_json, summary, facts_json, open_threads_json, interaction_count, last_seen_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(sender) DO UPDATE SET
                       display_name = excluded.display_name,
                       aliases_json = excluded.aliases_json,
                       relationship = excluded.relationship,
                       tone = excluded.tone,
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

    // ---- Owner Memory (Layer 3 - Permanent) ----
    pub async fn add_owner_memory(&self, category: &str, content: &str, source: &str) -> Result<i64> {
        let clean = content.trim();
        if clean.is_empty() {
            return Ok(0);
        }
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
                    existing.category = category.to_string();
                    let id = existing.id;
                    guard.flush()?;
                    return Ok(id);
                }
                let id = guard.alloc_id();
                guard.data.owner_memory.push(OwnerMemoryItem {
                    id,
                    category: category.to_string(),
                    content: clean.to_string(),
                    source: source.to_string(),
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
                if let Some(row) = existing.rows.first() {
                    let id = row_i64(row, "id");
                    self.execute_remote(
                        "UPDATE owner_memory SET updated_at = ?, category = ? WHERE id = ?",
                        vec![SqlParam::Int(now), SqlParam::from(category), SqlParam::Int(id)],
                    )
                    .await?;
                    return Ok(id);
                }
                let res = self
                    .execute_remote(
                        "INSERT INTO owner_memory (category, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                        vec![
                            SqlParam::from(category),
                            SqlParam::from(clean),
                            SqlParam::from(source),
                            SqlParam::Int(now),
                            SqlParam::Int(now),
                        ],
                    )
                    .await?;
                Ok(res.last_insert_rowid)
            }
        }
    }

    pub async fn list_owner_memory(&self, limit: i64) -> Result<Vec<OwnerMemoryItem>> {
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                let mut list = guard.data.owner_memory.clone();
                list.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
                list.truncate(limit as usize);
                Ok(list)
            }
            TursoBackend::Remote { .. } => {
                let res = self
                    .execute_remote(
                        "SELECT * FROM owner_memory ORDER BY updated_at DESC LIMIT ?",
                        vec![SqlParam::Int(limit)],
                    )
                    .await?;
                Ok(res
                    .rows
                    .iter()
                    .map(|r| OwnerMemoryItem {
                        id: row_i64(r, "id"),
                        category: row_str(r, "category").unwrap_or_default(),
                        content: row_str(r, "content").unwrap_or_default(),
                        source: row_str(r, "source").unwrap_or_default(),
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
        match &self.backend {
            TursoBackend::Local(s) => {
                let guard = s.lock().unwrap();
                Ok(ids.iter().any(|id| guard.data.saved_contacts.contains_key(id)))
            }
            TursoBackend::Remote { .. } => {
                for id in ids {
                    let res = self
                        .execute_remote(
                            "SELECT id FROM saved_contacts WHERE id = ? LIMIT 1",
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
}
