//! Lightweight, dependency-free safety layer for FalkeAI.
//!
//! - Inbound: strip spoofed role tags, detect prompt-injection / extraction attempts.
//! - Approval: deterministic backstop for requests that must be held for Falcon.
//! - Outbound: redact secrets, system-prompt leaks and third-party phone numbers.
//! - Anti-spam: duplicate payloads, inbound loops, auto-reply bursts, outbound echoes.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::config::Config;

/// Falcon's own public numbers (allowed to appear in replies).
const OWNER_PUBLIC_NUMBERS: [&str; 2] = ["2349113683395", "09113683395"];

/// Marker substrings that only appear inside FalkeAI's internal system prompt.
const PROMPT_LEAK_MARKERS: &[&str] = &[
    "## 1. core personality",
    "## 2. thinking pattern",
    "## 5b. emotional intelligence",
    "## 6b. urgent matters",
    "## 8. safety & privacy",
    "## 9. output format",
    "core personality (falcon's profile)",
    "session state:",
    "flag_human",
    "memory_update",
    "save_booking",
    "urgent_intake",
    "emotion_intensity",
    "emotion_subtext",
    "private_internal",
    "__owner_self_chat__",
    "live workspace context",
    "owner memory & verified facts",
    "learned preferences & self-improvement",
    "contact memory (persistent)",
];

const INJECTION_PATTERNS: &[&str] = &[
    "ignore previous instructions",
    "ignore all previous",
    "ignore your instructions",
    "ignore all rules",
    "ignore your rules",
    "ignore the above",
    "disregard previous",
    "disregard your instructions",
    "forget your instructions",
    "forget all previous",
    "override instructions",
    "new instructions",
    "system prompt",
    "your prompt",
    "your instructions",
    "your rules",
    "what are your rules",
    "what are your instructions",
    "repeat the text above",
    "repeat everything above",
    "print the above",
    "reveal your",
    "print your",
    "show me your config",
    "your config",
    "dump database",
    "dump memory",
    "show all memories",
    "list all bookings",
    "other contacts",
    "who else messaged",
    "who else texted",
    "api key",
    "api_key",
    "groq_api",
    "cf_api_token",
    "ntfy_topic",
    "turso_auth",
    "gmail_refresh",
    "gmail_client",
    "phone_bridge",
    "device_token",
    "github_token",
    "auth token",
    "access token",
    "environment variable",
    "env var",
    "developer mode",
    "jailbreak",
    "you are now",
    "act as falcon",
    "pretend to be falcon",
    "falcon approved",
];

fn strip_hidden_control_chars(text: &str) -> String {
    text.chars()
        .filter(|&c| {
            !matches!(
                c,
                '\u{0}'
                    | '\u{200B}'
                    | '\u{200C}'
                    | '\u{200D}'
                    | '\u{FEFF}'
                    | '\u{2060}'
                    | '\u{202A}'..='\u{202E}'
                    | '\u{2066}'..='\u{2069}'
            )
        })
        .collect()
}

/// Removes spoofed role / control tags a sender might inject (e.g. `[Falcon]:`, `[SYSTEM]`, `<|im_start|>`).
pub fn sanitize_inbound(text: &str) -> String {
    let mut out = strip_hidden_control_chars(text);
    for tag in [
        "<|im_start|>",
        "<|im_end|>",
        "<|system|>",
        "<|assistant|>",
        "<|user|>",
        "[INST]",
        "[/INST]",
        "<<SYS>>",
        "<</SYS>>",
    ] {
        out = out.replace(tag, "");
    }
    let mut lines = Vec::new();
    for line in out.lines() {
        let trimmed = line.trim_start();
        let lower = trimmed.to_lowercase();
        let mut cleaned = trimmed.to_string();
        for prefix in [
            "[falcon]:", "[falcon]", "[system]:", "[system]", "[owner]:", "[owner]", "[admin]:", "[admin]",
            "system:", "assistant:", "falcon:", "owner:",
        ] {
            if lower.starts_with(prefix) {
                cleaned = trimmed[prefix.len()..].trim_start().to_string();
                break;
            }
        }
        lines.push(cleaned);
    }
    let joined = lines.join("\n");
    joined.chars().take(2000).collect()
}

/// Returns true if the text looks like a prompt-injection or secret-extraction attempt,
/// including obfuscated spacing/punctuation tricks (e.g. `s.y.s.t.e.m p-r-o-m-p-t`).
pub fn detect_injection(text: &str) -> bool {
    let stripped = strip_hidden_control_chars(text).to_lowercase();
    if INJECTION_PATTERNS.iter().any(|p| stripped.contains(p))
        || stripped.contains("[system]")
        || stripped.contains("[falcon]")
    {
        return true;
    }
    // Collapse punctuation/separators and extra whitespace to catch obfuscated keywords
    let normalized_words: String = stripped
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '_' { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if INJECTION_PATTERNS.iter().any(|p| normalized_words.contains(p)) {
        return true;
    }
    // Catch letter-spaced obfuscation like "s y s t e m p r o m p t" or "s.y.s.t.e.m"
    let squashed: String = stripped
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect();
    for critical in [
        "ignorepreviousinstructions",
        "ignoreyourinstructions",
        "systemprompt",
        "revealconfig",
        "ntfytopic",
        "tursoauthtoken",
        "groqapikey",
        "enterdevelopermode",
    ] {
        if squashed.contains(critical) {
            return true;
        }
    }
    false
}

/// Cleans an untrusted WhatsApp display name before it is stored or put into a prompt.
pub fn sanitize_display_name(name: &str) -> Option<String> {
    let cleaned: String = name
        .chars()
        .filter(|c| !c.is_control() && !matches!(c, '[' | ']' | '{' | '}' | '<' | '>' | '#' | '`'))
        .take(40)
        .collect();
    let cleaned = cleaned.trim().to_string();
    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned)
    }
}

/// Deterministic backstop: requests that must be held for Falcon even if the model misses them.
pub fn approval_trigger(text: &str) -> Option<&'static str> {
    let lower = format!(" {} ", text.to_lowercase());
    let financial = [
        "send me money", "send money", "account number", "acct number", "bank details",
        "account details", "loan", "borrow", "lend me", "invest in", "investment", "payment", "pay you", "pay him",
        "pay me", "refund", "debt", "bank transfer", "transfer money", "make a transfer", "do the transfer", "crypto", "usdt", "bitcoin", "wire ",
    ];
    if financial.iter().any(|k| lower.contains(k)) {
        return Some("Financial request");
    }
    let business = [
        "partnership", "partner with", "contract", "agreement", "business deal", "sign the",
        "mou", "sponsorship", "equity", "collaboration proposal", "job offer", "offer letter",
    ];
    if business.iter().any(|k| lower.contains(k)) {
        return Some("Business agreement / opportunity");
    }
    let private = [
        "password", " otp", " pin ", "bvn", " nin ", "his address", "where does he live", "where he lives",
        "his location", "home address", "his number", "send me his", "his bank",
    ];
    if private.iter().any(|k| lower.contains(k)) {
        return Some("Request for private information");
    }
    let commitment = [
        "can he promise", "promise me", "guarantee", "will he pay", "commit to", "confirm he will",
        "tell him to agree", "on his behalf",
    ];
    if commitment.iter().any(|k| lower.contains(k)) {
        return Some("Commitment on Falcon's behalf");
    }
    None
}

/// Deterministic urgency detector (keywords + VIP escalation backstop) for jobs, deals,
/// payments, strict deadlines, emergencies, or someone waiting right now.
pub fn urgent_trigger(text: &str, is_vip: bool) -> Option<&'static str> {
    let lower = format!(" {} ", text.to_lowercase());
    let hard_urgent = [
        "urgent", "urgently", "asap", "emergency", "immediately",
        "call me now", "pick up", "waiting outside", "i'm outside", "im outside",
        "at the door", "waiting for falcon", "waiting for your reply", "at the gate",
        "site is down", "server is down", "deadline", "due today", "due tonight",
        "before midnight", "closing today", "job offer", "job interview",
        "job opportunity", "role opening", "interview invite", "interview today",
        "recruiter", "shortlisted", "offer letter", "client deal", "business deal",
        "partnership deal", "sponsorship deal", "close the deal", "sign the contract",
        "payment issue", "payment failed", "payment confirmation", "payment receipt",
        "sent payment", "made payment", "sent the money", "paid you", "debit alert",
        "transfer failed", "hospital", "accident",
    ];
    if hard_urgent.iter().any(|k| lower.contains(k)) {
        return Some("Urgent keyword match");
    }
    if is_vip {
        let vip_urgent = [
            "call me", "need you", "where are you", "please reply", "respond",
            "payment", "deal", "job", "waiting", "problem", "trouble", "sick", "help me", "right now",
        ];
        if vip_urgent.iter().any(|k| lower.contains(k)) {
            return Some("VIP urgent message");
        }
    }
    None
}

/// Returns true only for genuine physical / medical / safety emergencies where even an unverified
/// contact should bypass 2-turn intake.
pub fn is_life_safety_emergency(text: &str) -> bool {
    let lower = format!(" {} ", text.to_lowercase());
    [
        "hospital",
        "accident",
        "medical emergency",
        "police",
        "bleeding",
        "ambulance",
        "dying",
        "collapsed",
        "arrested",
        "fire",
    ]
    .iter()
    .any(|k| lower.contains(k))
}

fn looks_like_secret_token(word: &str) -> bool {
    let w = word.trim_matches(|c: char| !c.is_ascii_alphanumeric() && c != '_' && c != '-' && c != '.');
    if w.starts_with("eyJ") && w.len() >= 24 {
        return true; // JWT (Turso tokens)
    }
    if w.starts_with("hf_")
        || w.starts_with("sk-")
        || w.starts_with("gsk_")
        || w.starts_with("nvapi-")
        || w.starts_with("ghp_")
        || w.starts_with("github_pat_")
        || w.starts_with("ya29.")
        || w.starts_with("1//")
        || w.starts_with("GOCSPX-")
    {
        return w.len() >= 14;
    }
    if w.len() < 32 {
        return false;
    }
    let has_digit = w.chars().any(|c| c.is_ascii_digit());
    let has_alpha = w.chars().any(|c| c.is_ascii_alphabetic());
    let token_chars = w.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
    has_digit && has_alpha && token_chars
}

/// Redacts any configured secret values (and their obvious derivatives) from arbitrary text.
pub fn redact_secrets(text: &str, cfg: &Config) -> String {
    let mut out = text.to_string();
    let mut secrets: Vec<String> = Vec::new();
    if !cfg.ai_key.trim().is_empty() {
        secrets.push(cfg.ai_key.trim().to_string());
    }
    if !cfg.cf_api_token.trim().is_empty() {
        secrets.push(cfg.cf_api_token.trim().to_string());
    }
    for k in &cfg.groq_keys {
        if !k.trim().is_empty() {
            secrets.push(k.trim().to_string());
        }
    }
    for attempt in &cfg.ai_attempts {
        if !attempt.api_key.trim().is_empty() {
            secrets.push(attempt.api_key.trim().to_string());
        }
    }
    if let Some(t) = cfg.turso_auth_token.as_deref() {
        if !t.trim().is_empty() {
            secrets.push(t.trim().to_string());
        }
    }
    if cfg.turso_url.trim().len() > 8 {
        secrets.push(cfg.turso_url.trim().to_string());
    }
    if cfg.cf_account_id.trim().len() >= 8 {
        secrets.push(cfg.cf_account_id.trim().to_string());
    }
    if cfg.ntfy_topic.trim().len() >= 4 {
        secrets.push(cfg.ntfy_topic.trim().to_string());
    }
    if let Some(t) = cfg.ntfy_token.as_deref() {
        if !t.trim().is_empty() {
            secrets.push(t.trim().to_string());
        }
    }
    if !cfg.gmail_client_secret.trim().is_empty() {
        secrets.push(cfg.gmail_client_secret.trim().to_string());
    }
    for acc in &cfg.gmail_accounts {
        if !acc.refresh_token.trim().is_empty() {
            secrets.push(acc.refresh_token.trim().to_string());
        }
    }
    if let Some(t) = cfg.github_token.as_deref() {
        if !t.trim().is_empty() {
            secrets.push(t.trim().to_string());
        }
    }
    if !cfg.phone_bridge_secret.trim().is_empty() {
        secrets.push(cfg.phone_bridge_secret.trim().to_string());
    }
    if cfg.ai_base_url.contains("/accounts/") {
        if let Some(acct) = cfg.ai_base_url.split("/accounts/").nth(1).and_then(|s| s.split('/').next()) {
            if acct.len() >= 8 {
                secrets.push(acct.to_string());
            }
        }
    }
    for s in secrets {
        if s.len() >= 6 {
            out = out.replace(&s, "[redacted]");
        }
    }
    out.split(' ')
        .map(|w| {
            if looks_like_secret_token(w) || w.to_lowercase().contains("libsql://") {
                "[redacted]".to_string()
            } else {
                w.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Replaces long digit runs (phone numbers) that are not the current sender's or Falcon's public number.
fn redact_foreign_numbers(text: &str, sender: &str) -> String {
    let sender_digits: String = sender.chars().filter(|c| c.is_ascii_digit()).collect();
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();
    let flush = |run: &mut String, out: &mut String| {
        let digits: String = run.chars().filter(|c| c.is_ascii_digit()).collect();
        let allowed = digits.len() < 10
            || (!sender_digits.is_empty() && (digits.ends_with(&sender_digits) || sender_digits.ends_with(&digits)))
            || OWNER_PUBLIC_NUMBERS.iter().any(|n| digits.ends_with(n) || n.ends_with(&digits));
        if allowed {
            out.push_str(run);
        } else {
            out.push_str("[number hidden]");
        }
        run.clear();
    };
    for ch in text.chars() {
        if ch.is_ascii_digit() || (!run.is_empty() && (ch == ' ' || ch == '-') && run.len() < 20) || (run.is_empty() && ch == '+') {
            run.push(ch);
        } else {
            if !run.is_empty() {
                let trailing: String = run.chars().rev().take_while(|c| !c.is_ascii_digit()).collect();
                let core_len = run.len() - trailing.len();
                let mut core = run[..core_len].to_string();
                flush(&mut core, &mut out);
                out.extend(trailing.chars().rev());
                run.clear();
            }
            out.push(ch);
        }
    }
    if !run.is_empty() {
        flush(&mut run, &mut out);
    }
    out
}

/// Final outbound filter applied to every automated reply before it reaches WhatsApp.
/// Returns `(clean_reply, leak_detected)`.
pub fn sanitize_outbound_reply(reply: &str, cfg: &Config, sender: &str) -> (String, bool) {
    let lower = reply.to_lowercase();
    if PROMPT_LEAK_MARKERS.iter().any(|m| lower.contains(m)) {
        return (
            "Nice try 😄 My internal setup stays private. What can I actually help you with?".to_string(),
            true,
        );
    }
    let redacted = redact_secrets(reply, cfg);
    let redacted = redact_foreign_numbers(&redacted, sender);
    let leaked = redacted != reply;
    (redacted, leaked)
}

fn normalize_for_compare(text: &str) -> String {
    text.to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric())
        .take(300)
        .collect()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InboundVerdict {
    Ok,
    Duplicate,
    Loop,
}

/// Per-contact anti-spam / anti-loop tracker (in-memory, O(window) per contact).
#[derive(Default)]
pub struct LoopGuard {
    inbound: Mutex<HashMap<String, Vec<(i64, String)>>>,
    replies: Mutex<HashMap<String, Vec<i64>>>,
    alerted: Mutex<HashMap<String, i64>>,
}

pub const LOOP_WINDOW_MS: i64 = 3 * 60_000;
pub const LOOP_IDENTICAL_THRESHOLD: usize = 4;
pub const MAX_AUTO_REPLIES_PER_WINDOW: usize = 6;
pub const DUPLICATE_WINDOW_MS: i64 = 3_000;

impl LoopGuard {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records an inbound message and classifies it.
    pub fn record_inbound(&self, sender: &str, text: &str, now_ms: i64) -> InboundVerdict {
        let norm = normalize_for_compare(text);
        let mut map = self.inbound.lock().unwrap();
        let entries = map.entry(sender.to_string()).or_default();
        entries.retain(|(t, _)| now_ms - *t < LOOP_WINDOW_MS);
        if !norm.is_empty()
            && entries
                .iter()
                .any(|(t, n)| *n == norm && now_ms - *t < DUPLICATE_WINDOW_MS)
        {
            return InboundVerdict::Duplicate;
        }
        entries.push((now_ms, norm.clone()));
        let identical = entries.iter().filter(|(_, n)| !norm.is_empty() && *n == norm).count();
        if identical >= LOOP_IDENTICAL_THRESHOLD {
            InboundVerdict::Loop
        } else {
            InboundVerdict::Ok
        }
    }

    /// Returns true (and records) if another automated reply is allowed for this contact right now.
    pub fn allow_auto_reply(&self, sender: &str, now_ms: i64) -> bool {
        let mut map = self.replies.lock().unwrap();
        let stamps = map.entry(sender.to_string()).or_default();
        stamps.retain(|t| now_ms - *t < LOOP_WINDOW_MS);
        if stamps.len() >= MAX_AUTO_REPLIES_PER_WINDOW {
            return false;
        }
        stamps.push(now_ms);
        true
    }

    /// Returns true only the first time per window, so Falcon is alerted once per loop.
    pub fn should_alert(&self, sender: &str, now_ms: i64) -> bool {
        let mut map = self.alerted.lock().unwrap();
        match map.get(sender) {
            Some(t) if now_ms - *t < LOOP_WINDOW_MS * 10 => false,
            _ => {
                map.insert(sender.to_string(), now_ms);
                true
            }
        }
    }

    pub fn reset(&self, sender: &str) {
        self.inbound.lock().unwrap().remove(sender);
        self.replies.lock().unwrap().remove(sender);
        self.alerted.lock().unwrap().remove(sender);
    }
}

/// Remembers recently sent outbound texts so WhatsApp echoes of FalkeAI's own messages
/// are never mistaken for Falcon's manual replies or owner commands.
#[derive(Default)]
pub struct OutboundEchoGuard {
    recent: Mutex<Vec<(i64, String)>>,
}

pub const ECHO_WINDOW_MS: i64 = 2 * 60_000;

impl OutboundEchoGuard {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record(&self, text: &str, now_ms: i64) {
        let norm = normalize_for_compare(text);
        if norm.is_empty() {
            return;
        }
        let mut v = self.recent.lock().unwrap();
        v.retain(|(t, _)| now_ms - *t < ECHO_WINDOW_MS);
        v.push((now_ms, norm));
        if v.len() > 500 {
            let excess = v.len() - 500;
            v.drain(0..excess);
        }
    }

    /// Returns true (and consumes the entry) if `text` matches something FalkeAI just sent.
    pub fn is_echo(&self, text: &str, now_ms: i64) -> bool {
        let norm = normalize_for_compare(text);
        if norm.is_empty() {
            return false;
        }
        let mut v = self.recent.lock().unwrap();
        v.retain(|(t, _)| now_ms - *t < ECHO_WINDOW_MS);
        if let Some(pos) = v.iter().position(|(_, n)| *n == norm) {
            v.remove(pos);
            return true;
        }
        false
    }
}
