use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactEntry {
    pub jid: String,
    pub lid: Option<String>,
    pub phone_number: Option<String>,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupParticipant {
    pub jid: String,
    pub lid: Option<String>,
    pub phone_number: Option<String>,
    pub is_admin: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupSummary {
    pub jid: String,
    pub subject: String,
}

/// Events emitted by the Go `whatsmeow` bridge over stdout (NDJSON)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum BridgeEvent {
    Connected {
        self_jid: String,
        self_lid: Option<String>,
    },
    PairingCode {
        code: String,
    },
    Message {
        id: String,
        chat_jid: String,
        chat_alt_jid: Option<String>,
        sender_jid: String,
        sender_alt_jid: Option<String>,
        from_me: bool,
        is_group: bool,
        timestamp: i64,
        text: String,
        is_voice: bool,
        matched_urls: Vec<String>,
    },
    ContactsSync {
        contacts: Vec<ContactEntry>,
    },
    GroupRoster {
        request_id: String,
        group_jid: String,
        subject: String,
        description: String,
        linked_parent: Option<String>,
        participants: Vec<GroupParticipant>,
    },
    GroupsList {
        request_id: String,
        groups: Vec<GroupSummary>,
    },
    GroupInvalidate {
        group_jid: String,
    },
    ChatReadByOwner {
        chat_jid: String,
        chat_alt_jid: Option<String>,
    },
    LoggedOut,
    Error {
        message: String,
    },
}

/// Commands sent from the Rust Core to the Go `whatsmeow` bridge over stdin (NDJSON)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
pub enum BridgeCommand {
    SendMessage {
        chat_jid: String,
        text: String,
        #[serde(default)]
        mentions: Vec<String>,
    },
    SendPresence {
        chat_jid: String,
        state: String,
    },
    MarkRead {
        chat_jid: String,
        message_id: String,
        sender_jid: Option<String>,
    },
    DeleteMessage {
        group_jid: String,
        message_id: String,
        sender_jid: String,
    },
    RemoveParticipant {
        group_jid: String,
        participant_jid: String,
    },
    FetchGroupRoster {
        request_id: String,
        group_jid: String,
    },
    FetchJoinedGroups {
        request_id: String,
    },
}
