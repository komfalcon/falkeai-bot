//! Multi-account official Gmail REST API integration (`gmail.googleapis.com`).
//!
//! - Zero extra Cargo crates (pure `reqwest` + `serde_json` + zero-dep `base64url_decode`/`encode`).
//! - Supports unlimited Gmail accounts (`GMAIL_REFRESH_TOKEN`, `GMAIL_REFRESH_TOKEN_2..5`,
//!   `GMAIL_ACCOUNTS_JSON`, plus dynamic accounts stored in Turso).
//! - Separates real-time urgent alerts (interviews, Paystack/Stripe failures, Heroku/GitHub crashes)
//!   from 7:00 AM briefing priority emails while dropping newsletters & promos.
//! - Draft-only `create_gmail_draft` (never auto-sends emails behind Falcon's back).
//! - Strict prompt-injection firewall on all untrusted external email headers & bodies.

use anyhow::{anyhow, Result};
use chrono::Utc;
use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use crate::config::{Config, GmailAccountConfig};
use crate::db::{Database, EmailItemRecord};
use crate::media::{base64url_decode, base64url_encode};
use crate::safety::{detect_injection, redact_secrets, sanitize_inbound};
use crate::web_reader::decode_html_entities;

/// In-memory cache of short-lived OAuth2 access tokens keyed by refresh token.
#[derive(Default)]
pub struct GmailTokenCache {
    tokens: Mutex<HashMap<String, (String, i64)>>,
}

impl GmailTokenCache {
    pub fn new() -> Self {
        Self::default()
    }

    fn get_valid(&self, refresh_token: &str, now_ms: i64) -> Option<String> {
        let guard = self.tokens.lock().ok()?;
        let (tok, exp) = guard.get(refresh_token)?;
        if *exp > now_ms + 60_000 {
            Some(tok.clone())
        } else {
            None
        }
    }

    fn put(&self, refresh_token: &str, access_token: String, expires_at_ms: i64) {
        if let Ok(mut guard) = self.tokens.lock() {
            guard.insert(refresh_token.to_string(), (access_token, expires_at_ms));
        }
    }
}

fn gmail_http() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(15))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn url_encode_query(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// Collects all configured Gmail accounts from environment config + optional Turso JSON setting.
pub async fn resolve_gmail_accounts(db: &Database, cfg: &Config) -> Vec<GmailAccountConfig> {
    let mut out = cfg.gmail_accounts.clone();
    if let Ok(Some(raw)) = db.get_setting("extra_gmail_accounts_json").await {
        if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&raw) {
            for (i, item) in arr.iter().enumerate() {
                if let Some(rt) = item["refresh_token"]
                    .as_str()
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                {
                    if !out.iter().any(|a| a.refresh_token == rt) {
                        let label = item["label"]
                            .as_str()
                            .or_else(|| item["email"].as_str())
                            .map(str::trim)
                            .filter(|s| !s.is_empty())
                            .map(|s| s.to_string())
                            .unwrap_or_else(|| format!("Gmail #{}", out.len() + i + 1));
                        out.push(GmailAccountConfig {
                            label,
                            refresh_token: rt.to_string(),
                        });
                    }
                }
            }
        }
    }
    out
}

/// Exchanges a Gmail OAuth2 refresh token for a fresh access token (cached in memory).
pub async fn get_access_token(
    cfg: &Config,
    cache: &GmailTokenCache,
    refresh_token: &str,
) -> Result<String> {
    let now_ms = Utc::now().timestamp_millis();
    if let Some(cached) = cache.get_valid(refresh_token, now_ms) {
        return Ok(cached);
    }
    if cfg.gmail_client_id.trim().is_empty() || cfg.gmail_client_secret.trim().is_empty() {
        return Err(anyhow!("GMAIL_CLIENT_ID or GMAIL_CLIENT_SECRET is not set"));
    }

    let form_body = format!(
        "client_id={}&client_secret={}&refresh_token={}&grant_type=refresh_token",
        url_encode_query(&cfg.gmail_client_id),
        url_encode_query(&cfg.gmail_client_secret),
        url_encode_query(refresh_token),
    );
    let resp = gmail_http()
        .post("https://oauth2.googleapis.com/token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(form_body)
        .send()
        .await?;
    if !resp.status().is_success() {
        let st = resp.status();
        let err_txt = resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "Gmail OAuth token refresh failed ({}): {}",
            st,
            redact_secrets(&err_txt, cfg)
        ));
    }
    let payload: Value = resp.json().await?;
    let access_token = payload["access_token"]
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("Missing access_token in Google OAuth response"))?
        .to_string();
    let expires_in = payload["expires_in"].as_i64().unwrap_or(3500).max(120);
    cache.put(refresh_token, access_token.clone(), now_ms + expires_in * 1000);
    Ok(access_token)
}

/// Extracts a header value case-insensitively from a Gmail API message payload.
pub fn extract_gmail_header(payload: &Value, name: &str) -> String {
    payload["headers"]
        .as_array()
        .and_then(|arr| {
            arr.iter().find_map(|h| {
                let hn = h["name"].as_str()?;
                if hn.eq_ignore_ascii_case(name) {
                    h["value"].as_str().map(|s| s.trim().to_string())
                } else {
                    None
                }
            })
        })
        .unwrap_or_default()
}

fn strip_html_tags(html: &str) -> String {
    static STYLE_SCRIPT_RE: OnceLock<Regex> = OnceLock::new();
    static TAG_RE: OnceLock<Regex> = OnceLock::new();
    let ss_re = STYLE_SCRIPT_RE.get_or_init(|| {
        Regex::new(r"(?is)<(script|style|head)\b[^>]*>.*?</(script|style|head)>")
            .expect("Valid style/script regex")
    });
    let tag_re = TAG_RE.get_or_init(|| Regex::new(r"(?is)<[^>]+>").expect("Valid tag regex"));
    let without_ss = ss_re.replace_all(html, " ");
    let without_tags = tag_re.replace_all(&without_ss, " ");
    decode_html_entities(&without_tags)
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn find_mime_part_text(part: &Value, want_mime: &str) -> Option<String> {
    let mime = part["mimeType"].as_str().unwrap_or("").to_lowercase();
    if mime == want_mime {
        if let Some(data) = part["body"]["data"].as_str().filter(|s| !s.trim().is_empty()) {
            if let Ok(bytes) = base64url_decode(data) {
                let text = String::from_utf8_lossy(&bytes).into_owned();
                if !text.trim().is_empty() {
                    return Some(text);
                }
            }
        }
    }
    if let Some(parts) = part["parts"].as_array() {
        for sub in parts {
            if let Some(found) = find_mime_part_text(sub, want_mime) {
                return Some(found);
            }
        }
    }
    None
}

/// Extracts readable plain text from a Gmail API message `payload` (preferring `text/plain`,
/// falling back to HTML-stripped `text/html`), capped at `1,500` chars.
pub fn extract_gmail_plain_body(payload: &Value) -> String {
    if let Some(plain) = find_mime_part_text(payload, "text/plain") {
        return plain
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")
            .chars()
            .take(1500)
            .collect();
    }
    if let Some(html) = find_mime_part_text(payload, "text/html") {
        return strip_html_tags(&html).chars().take(1500).collect();
    }
    if let Some(data) = payload["body"]["data"].as_str().filter(|s| !s.trim().is_empty()) {
        if let Ok(bytes) = base64url_decode(data) {
            return strip_html_tags(&String::from_utf8_lossy(&bytes))
                .chars()
                .take(1500)
                .collect();
        }
    }
    String::new()
}

/// Deterministic zero-noise priority email classifier.
/// Returns `(category, priority)` where:
/// - `category`: `interview_career` | `payments_finance` | `infra_alerts` | `direct_human` | `low_noise`
/// - `priority`: `urgent` | `important` | `low`
pub fn classify_email_priority(
    from: &str,
    subject: &str,
    snippet: &str,
    has_list_unsubscribe: bool,
) -> (&'static str, &'static str) {
    let from_l = from.to_lowercase();
    let subj_l = subject.to_lowercase();
    let combined = format!("{} {} {}", from_l, subj_l, snippet.to_lowercase());

    // 1) Infra / DevOps / Security Alerts (GitHub, Heroku, Cloudflare, Turso, Sentry, Vercel)
    let infra_senders = [
        "heroku",
        "github",
        "cloudflare",
        "turso",
        "vercel",
        "sentry",
        "groq",
        "render.com",
        "google cloud",
    ];
    let is_infra_sender = infra_senders.iter().any(|s| from_l.contains(s) || subj_l.contains(s));
    if is_infra_sender {
        let urgent_infra = [
            "crashed",
            "app crashed",
            "down",
            "outage",
            "security alert",
            "vulnerability",
            "suspended",
            "payment failed",
            "billing failed",
            "quota exceeded",
            "action required",
            "workflow run failed",
            "build failed",
            "deployment failed",
            "exposed secret",
        ];
        if urgent_infra.iter().any(|k| combined.contains(k)) {
            return ("infra_alerts", "urgent");
        }
        let important_infra = [
            "pull request",
            "mentioned you",
            "review requested",
            "assigned you",
            "invoice",
            "receipt",
            "billing",
            "alert",
            "release",
        ];
        if important_infra.iter().any(|k| combined.contains(k)) {
            return ("infra_alerts", "important");
        }
    }

    // 2) Job Interviews / Offers / Career Opportunities (checked before finance so interviews from Stripe/Paystack stay career!)
    let career_urgent = [
        "interview invite",
        "interview invitation",
        "schedule an interview",
        "interview scheduled",
        "interview rescheduled",
        "offer letter",
        "job offer",
        "technical interview",
        "final interview",
        "coding assessment",
        "take-home assessment",
    ];
    if career_urgent.iter().any(|k| combined.contains(k)) {
        return ("interview_career", "urgent");
    }
    let career_important = [
        "interview",
        "shortlisted",
        "recruiter",
        "hiring manager",
        "greenhouse.io",
        "lever.co",
        "ashbyhq",
        "workday",
        "calendly",
        "application update",
        "next steps for",
    ];
    if career_important.iter().any(|k| combined.contains(k)) {
        return ("interview_career", "important");
    }

    // 3) Payments / Payouts / Fintech (Stripe, Paystack, Wise, Flutterwave, Nigerian Banks)
    let finance_senders = [
        "paystack",
        "stripe",
        "flutterwave",
        "wise.com",
        "payoneer",
        "moniepoint",
        "opay",
        "palmpay",
        "kuda",
        "gtbank",
        "guaranty trust",
        "accessbank",
        "zenithbank",
        "uba",
        "firstbank",
    ];
    let is_finance = finance_senders.iter().any(|s| from_l.contains(s) || subj_l.contains(s))
        || [
            "payout",
            "settlement",
            "credit alert",
            "debit alert",
            "chargeback",
            "invoice paid",
            "payment received",
        ]
        .iter()
        .any(|k| subj_l.contains(k));
    if is_finance {
        let urgent_fin = [
            "payout failed",
            "payment failed",
            "dispute",
            "chargeback",
            "fraud",
            "unauthorized",
            "account restricted",
            "suspended",
            "declined",
            "failed transfer",
        ];
        if urgent_fin.iter().any(|k| combined.contains(k)) {
            return ("payments_finance", "urgent");
        }
        return ("payments_finance", "important");
    }

    // 4) Drop automated newsletters, digests & bulk marketing noise
    let automated_sender = [
        "noreply",
        "no-reply",
        "newsletter",
        "marketing@",
        "promo@",
        "promotions@",
        "digest@",
        "notifications@",
        "updates@",
        "mailer-daemon",
        "donotreply",
        "substack.com",
        "medium.com",
    ]
    .iter()
    .any(|s| from_l.contains(s));
    if has_list_unsubscribe || automated_sender {
        return ("low_noise", "low");
    }

    // 5) Direct human email without bulk unsubscribe header
    ("direct_human", "important")
}

/// Scrubs untrusted external email text so malicious senders cannot smuggle prompt injections.
pub fn sanitize_email_text(raw: &str, max_chars: usize) -> String {
    let cleaned = sanitize_inbound(raw);
    if detect_injection(&cleaned) {
        return "[Filtered suspicious prompt-injection text in external email]".to_string();
    }
    cleaned
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(max_chars)
        .collect()
}

/// Parses a Gmail API `messages.get?format=full` JSON object into an `EmailItemRecord`.
pub fn parse_gmail_message_to_record(
    account_label: &str,
    msg_json: &Value,
    now_ms: i64,
) -> Option<EmailItemRecord> {
    let msg_id = msg_json["id"].as_str()?.trim().to_string();
    if msg_id.is_empty() {
        return None;
    }
    let thread_id = msg_json["threadId"].as_str().unwrap_or("").trim().to_string();
    let payload = &msg_json["payload"];
    let raw_from = extract_gmail_header(payload, "From");
    let raw_subject = extract_gmail_header(payload, "Subject");
    let has_unsub = !extract_gmail_header(payload, "List-Unsubscribe").is_empty();
    let raw_snippet = decode_html_entities(msg_json["snippet"].as_str().unwrap_or(""));
    let body_plain = extract_gmail_plain_body(payload);
    let received_at = msg_json["internalDate"]
        .as_str()
        .and_then(|s| s.parse::<i64>().ok())
        .or_else(|| msg_json["internalDate"].as_i64())
        .unwrap_or(now_ms);

    let (category, priority) =
        classify_email_priority(&raw_from, &raw_subject, &raw_snippet, has_unsub);

    let safe_from = sanitize_email_text(&raw_from, 100);
    let safe_subject = sanitize_email_text(
        if raw_subject.is_empty() {
            "(No subject)"
        } else {
            &raw_subject
        },
        160,
    );
    let safe_snippet = sanitize_email_text(
        if raw_snippet.is_empty() {
            &body_plain
        } else {
            &raw_snippet
        },
        240,
    );
    let summary = if safe_snippet.is_empty() {
        format!("[{}] {} — {}", account_label, safe_from, safe_subject)
    } else {
        format!(
            "[{}] {} — {}: {}",
            account_label,
            safe_from,
            safe_subject,
            safe_snippet.chars().take(140).collect::<String>()
        )
    };

    Some(EmailItemRecord {
        msg_id,
        account_label: account_label.to_string(),
        thread_id,
        sender: safe_from,
        subject: safe_subject,
        snippet: safe_snippet,
        category: category.to_string(),
        priority: priority.to_string(),
        summary,
        received_at,
        alerted: false,
        digest_delivered: false,
        created_at: now_ms,
    })
}

/// Extracts a structured bank/fintech credit or debit record from a `payments_finance` email
/// when a clear amount (`NGN`, `₦`, `$`, `USD`) is present, so Paystack/Stripe payouts populate
/// the Turso `bank_alerts` ledger automatically.
pub fn extract_bank_alert_from_email(
    item: &EmailItemRecord,
) -> Option<(String, String, i64, String, String)> {
    if item.category != "payments_finance" {
        return None;
    }
    let text = format!("{} {}", item.subject, item.snippet);
    let lower = text.to_lowercase();
    let direction = if ["debit", "charged", "spent", "withdrawal", "paid to", "sent to"]
        .iter()
        .any(|k| lower.contains(k))
    {
        "debit"
    } else if [
        "payout",
        "credit",
        "received",
        "settlement",
        "deposited",
        "payment from",
    ]
    .iter()
    .any(|k| lower.contains(k))
    {
        "credit"
    } else {
        return None;
    };

    static AMT_RE: OnceLock<Regex> = OnceLock::new();
    let re = AMT_RE.get_or_init(|| {
        Regex::new(r"(?i)(NGN|₦|\$|USD|GBP|£|EUR|€)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)")
            .expect("Valid amount regex")
    });
    let caps = re.captures(&text)?;
    let cur_raw = caps.get(1)?.as_str().to_uppercase();
    let currency = match cur_raw.as_str() {
        "₦" | "NGN" => "NGN",
        "$" | "USD" => "USD",
        "£" | "GBP" => "GBP",
        "€" | "EUR" => "EUR",
        other => other,
    };
    let num_clean = caps.get(2)?.as_str().replace(',', "");
    let amount_f64: f64 = num_clean.parse().ok()?;
    let amount_minor = (amount_f64 * 100.0).round() as i64;
    if amount_minor <= 0 {
        return None;
    }

    let bank = if lower.contains("paystack") {
        "Paystack"
    } else if lower.contains("stripe") {
        "Stripe"
    } else if lower.contains("flutterwave") {
        "Flutterwave"
    } else if lower.contains("wise") {
        "Wise"
    } else if lower.contains("gtbank") || lower.contains("guaranty trust") {
        "GTBank"
    } else if lower.contains("opay") {
        "OPay"
    } else if lower.contains("moniepoint") {
        "Moniepoint"
    } else if lower.contains("kuda") {
        "Kuda"
    } else {
        "Gmail Finance"
    };

    Some((
        bank.to_string(),
        direction.to_string(),
        amount_minor,
        currency.to_string(),
        item.sender.clone(),
    ))
}

async fn fetch_account_messages(
    cfg: &Config,
    cache: &GmailTokenCache,
    account: &GmailAccountConfig,
    query: &str,
    max_results: usize,
) -> Result<Vec<Value>> {
    let token = get_access_token(cfg, cache, &account.refresh_token).await?;
    let client = gmail_http();
    let list_url = format!(
        "https://gmail.googleapis.com/gmail/v1/users/me/messages?q={}&maxResults={}",
        url_encode_query(query),
        max_results.clamp(1, 25)
    );
    let list_resp = client.get(&list_url).bearer_auth(&token).send().await?;
    if !list_resp.status().is_success() {
        let st = list_resp.status();
        let err_txt = list_resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "Gmail list failed for {} ({}): {}",
            account.label,
            st,
            redact_secrets(&err_txt, cfg)
        ));
    }
    let list_json: Value = list_resp.json().await?;
    let Some(msgs) = list_json["messages"].as_array() else {
        return Ok(Vec::new());
    };

    let mut out = Vec::new();
    for m in msgs.iter().take(max_results) {
        let Some(id) = m["id"].as_str() else {
            continue;
        };
        let get_url = format!(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/{}?format=full",
            url_encode_query(id)
        );
        if let Ok(resp) = client.get(&get_url).bearer_auth(&token).send().await {
            if resp.status().is_success() {
                if let Ok(full_msg) = resp.json::<Value>().await {
                    out.push(full_msg);
                }
            }
        }
    }
    Ok(out)
}

/// Polls ALL connected Gmail accounts for unread Primary/Updates emails from the last 48h,
/// skipping already-triaged IDs in Turso, persisting new items + any extracted payout/bank alerts,
/// and returning newly discovered priority (`urgent` | `important`) records.
pub async fn sync_all_gmail_accounts(
    db: &Database,
    cfg: &Config,
    cache: &GmailTokenCache,
    now_ms: i64,
) -> Result<Vec<EmailItemRecord>> {
    let accounts = resolve_gmail_accounts(db, cfg).await;
    if accounts.is_empty() || cfg.gmail_client_id.trim().is_empty() {
        return Ok(Vec::new());
    }

    let query = "is:unread -category:promotions -category:social -in:chats newer_than:2d";
    let mut newly_found = Vec::new();

    for acc in &accounts {
        let token = match get_access_token(cfg, cache, &acc.refresh_token).await {
            Ok(t) => t,
            Err(e) => {
                tracing::warn!("Gmail auth failed for {}: {}", acc.label, e);
                continue;
            }
        };
        let client = gmail_http();
        let list_url = format!(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages?q={}&maxResults=20",
            url_encode_query(query)
        );
        let list_resp = match client.get(&list_url).bearer_auth(&token).send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::warn!("Gmail poll HTTP {} for {}", r.status(), acc.label);
                continue;
            }
            Err(e) => {
                tracing::warn!("Gmail poll error for {}: {}", acc.label, e);
                continue;
            }
        };
        let list_json: Value = list_resp.json().await.unwrap_or_default();
        let Some(msgs) = list_json["messages"].as_array() else {
            continue;
        };

        for m in msgs.iter().take(20) {
            let Some(id) = m["id"].as_str() else {
                continue;
            };
            let composite_id = format!("{}:{}", acc.label, id);
            if db.has_email_item(&composite_id).await.unwrap_or(false) {
                continue;
            }
            let get_url = format!(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/{}?format=full",
                url_encode_query(id)
            );
            let Ok(resp) = client.get(&get_url).bearer_auth(&token).send().await else {
                continue;
            };
            if !resp.status().is_success() {
                continue;
            }
            let Ok(full_msg) = resp.json::<Value>().await else {
                continue;
            };
            if let Some(mut rec) = parse_gmail_message_to_record(&acc.label, &full_msg, now_ms) {
                rec.msg_id = composite_id;
                let _ = db.upsert_email_item(&rec).await;
                if let Some((bank, dir, minor, cur, cp)) = extract_bank_alert_from_email(&rec) {
                    let _ = db
                        .insert_bank_alert("gmail", &bank, &dir, minor, &cur, &cp, &rec.summary, now_ms)
                        .await;
                }
                if matches!(rec.priority.as_str(), "urgent" | "important") {
                    newly_found.push(rec);
                }
            }
        }
    }

    Ok(newly_found)
}

/// Searches across ALL connected Gmail accounts for an arbitrary query (e.g. "Paystack", "interview",
/// "from:sarah", or "is:unread") for live J.A.R.V.I.S. answers.
pub async fn search_all_gmail_accounts(
    db: &Database,
    cfg: &Config,
    cache: &GmailTokenCache,
    query: &str,
    max_per_account: usize,
) -> Result<Vec<EmailItemRecord>> {
    let accounts = resolve_gmail_accounts(db, cfg).await;
    if accounts.is_empty() || cfg.gmail_client_id.trim().is_empty() {
        return Err(anyhow!(
            "Gmail is not configured yet (set GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET, and GMAIL_REFRESH_TOKEN)"
        ));
    }
    let clean_q = if query.trim().is_empty() {
        "is:unread newer_than:7d"
    } else {
        query.trim()
    };
    let now_ms = Utc::now().timestamp_millis();
    let mut results = Vec::new();
    for acc in &accounts {
        match fetch_account_messages(cfg, cache, acc, clean_q, max_per_account).await {
            Ok(raw_msgs) => {
                for m in raw_msgs {
                    if let Some(rec) = parse_gmail_message_to_record(&acc.label, &m, now_ms) {
                        results.push(rec);
                    }
                }
            }
            Err(e) => {
                tracing::warn!("Gmail search skipped {}: {}", acc.label, e);
            }
        }
    }
    results.sort_by(|a, b| b.received_at.cmp(&a.received_at));
    Ok(results)
}

/// Safely creates a Gmail **draft** (NEVER auto-sends an email) using `gmail.compose` scope.
pub async fn create_gmail_draft(
    db: &Database,
    cfg: &Config,
    cache: &GmailTokenCache,
    account_hint: Option<&str>,
    to: &str,
    subject: &str,
    body: &str,
) -> Result<String> {
    let accounts = resolve_gmail_accounts(db, cfg).await;
    let acc = account_hint
        .and_then(|h| {
            let hl = h.to_lowercase();
            accounts
                .iter()
                .find(|a| a.label.to_lowercase().contains(&hl))
        })
        .or_else(|| accounts.first())
        .ok_or_else(|| anyhow!("No Gmail account configured"))?;

    let token = get_access_token(cfg, cache, &acc.refresh_token).await?;
    let clean_to = to.replace(['\r', '\n'], " ");
    let clean_subj = subject.replace(['\r', '\n'], " ");
    let raw_rfc2822 = format!(
        "To: {}\r\nSubject: {}\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\n\r\n{}",
        clean_to.trim(),
        clean_subj.trim(),
        body.trim()
    );
    let encoded = base64url_encode(raw_rfc2822.as_bytes());
    let resp = gmail_http()
        .post("https://gmail.googleapis.com/gmail/v1/users/me/drafts")
        .bearer_auth(&token)
        .json(&json!({
            "message": {
                "raw": encoded
            }
        }))
        .send()
        .await?;
    if !resp.status().is_success() {
        let st = resp.status();
        let err_txt = resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "Gmail draft creation failed ({} — ensure gmail.compose scope is enabled): {}",
            st,
            redact_secrets(&err_txt, cfg)
        ));
    }
    let val: Value = resp.json().await?;
    let draft_id = val["id"].as_str().unwrap_or("saved").to_string();
    Ok(format!("Draft #{} saved in {}", draft_id, acc.label))
}
