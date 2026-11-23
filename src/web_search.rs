//! Zero-cost live online Web Search & GitHub Account Search for FalkeAI.
//!
//! - Uses zero extra Cargo dependencies (`reqwest`, `regex`, `serde_json`).
//! - Multi-provider free web search chain:
//!   1) DuckDuckGo HTML Lite + DuckDuckGo Instant Answer API
//!   2) Wikipedia REST Summary API (authoritative encyclopedic / factual answers)
//!   3) Top result enrichment via SSRF-safe `crate::web_reader::fetch_and_summarize_link`
//! - GitHub account search (repos, issues, notifications) via official `api.github.com`
//!   using optional free `GITHUB_TOKEN`.
//! - Strict prompt-injection firewall on all untrusted search results.

use anyhow::{anyhow, Result};
use regex::Regex;
use serde_json::Value;
use std::sync::OnceLock;
use std::time::Duration;

use crate::config::Config;
use crate::safety::{detect_injection, redact_secrets, sanitize_inbound};
use crate::web_reader::{decode_html_entities, fetch_and_summarize_link, parse_and_validate_url_syntax};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResultItem {
    pub title: String,
    pub url: String,
    pub snippet: String,
    pub source: String,
}

fn search_http() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(6))
        .timeout(Duration::from_secs(12))
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn url_encode(s: &str) -> String {
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

pub fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(hex_str) = std::str::from_utf8(&bytes[i + 1..i + 3]) {
                if let Ok(val) = u8::from_str_radix(hex_str, 16) {
                    out.push(val);
                    i += 3;
                    continue;
                }
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn strip_tags(raw: &str) -> String {
    static TAG_RE: OnceLock<Regex> = OnceLock::new();
    let re = TAG_RE.get_or_init(|| Regex::new(r"(?is)<[^>]+>").expect("Valid tag regex"));
    decode_html_entities(&re.replace_all(raw, " "))
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn unwrap_ddg_url(raw_href: &str) -> String {
    let decoded_ent = decode_html_entities(raw_href.trim());
    if let Some(idx) = decoded_ent.find("uddg=") {
        let rest = &decoded_ent[idx + 5..];
        let end = rest.find('&').unwrap_or(rest.len());
        return percent_decode(&rest[..end]);
    }
    if decoded_ent.starts_with("//") {
        return format!("https:{}", decoded_ent);
    }
    decoded_ent
}

/// Pure parser for DuckDuckGo HTML search responses (`https://html.duckduckgo.com/html/`).
pub fn parse_ddg_html_results(html: &str, max_results: usize) -> Vec<SearchResultItem> {
    static LINK_RE: OnceLock<Regex> = OnceLock::new();
    static SNIP_RE: OnceLock<Regex> = OnceLock::new();
    let link_re = LINK_RE.get_or_init(|| {
        Regex::new(r#"(?is)<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>"#)
            .expect("Valid DDG link regex")
    });
    let snip_re = SNIP_RE.get_or_init(|| {
        Regex::new(r#"(?is)<(?:a|div|span)[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|span)>"#)
            .expect("Valid DDG snippet regex")
    });

    let snippets: Vec<String> = snip_re
        .captures_iter(html)
        .map(|c| strip_tags(c.get(1).map(|m| m.as_str()).unwrap_or("")))
        .collect();

    let mut out = Vec::new();
    for (idx, caps) in link_re.captures_iter(html).enumerate() {
        let raw_href = caps.get(1).map(|m| m.as_str()).unwrap_or("");
        let title = strip_tags(caps.get(2).map(|m| m.as_str()).unwrap_or(""));
        let url = unwrap_ddg_url(raw_href);
        if title.is_empty() || parse_and_validate_url_syntax(&url).is_err() {
            continue;
        }
        let snippet = snippets.get(idx).cloned().unwrap_or_default();
        out.push(SearchResultItem {
            title,
            url,
            snippet,
            source: "DuckDuckGo".to_string(),
        });
        if out.len() >= max_results {
            break;
        }
    }
    out
}

/// Pure parser for Wikipedia REST page summary JSON (`https://en.wikipedia.org/api/rest_v1/page/summary/...`).
pub fn parse_wikipedia_summary_json(v: &Value) -> Option<SearchResultItem> {
    let title = v["title"].as_str()?.trim();
    let extract = v["extract"].as_str()?.trim();
    if title.is_empty() || extract.is_empty() {
        return None;
    }
    let url = v["content_urls"]["desktop"]["page"]
        .as_str()
        .unwrap_or("https://en.wikipedia.org")
        .to_string();
    Some(SearchResultItem {
        title: title.to_string(),
        url,
        snippet: extract.chars().take(420).collect(),
        source: "Wikipedia".to_string(),
    })
}

fn sanitize_search_field(raw: &str, max_chars: usize) -> String {
    let clean = sanitize_inbound(raw);
    if detect_injection(&clean) {
        return "[Filtered external snippet]".to_string();
    }
    clean
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(max_chars)
        .collect()
}

/// Runs a fast, zero-cost live web search and returns a concise, sanitized, source-cited summary.
pub async fn search_web_and_summarize(
    cfg: &Config,
    query: &str,
    max_results: usize,
) -> Result<String> {
    let q = query.trim();
    if q.is_empty() {
        return Err(anyhow!("Empty web search query"));
    }
    let client = search_http();
    let cap = max_results.clamp(1, 5);
    let mut items: Vec<SearchResultItem> = Vec::new();

    // 1) DuckDuckGo Instant Answer JSON (ultra-fast structured facts & definitions)
    let ia_url = format!(
        "https://api.duckduckgo.com/?q={}&format=json&no_html=1&skip_disambig=1",
        url_encode(q)
    );
    if let Ok(resp) = client.get(&ia_url).send().await {
        if resp.status().is_success() {
            if let Ok(v) = resp.json::<Value>().await {
                let abstract_txt = v["AbstractText"].as_str().unwrap_or("").trim();
                let heading = v["Heading"].as_str().unwrap_or(q).trim();
                let abstract_url = v["AbstractURL"].as_str().unwrap_or("").trim();
                if !abstract_txt.is_empty() {
                    items.push(SearchResultItem {
                        title: heading.to_string(),
                        url: if abstract_url.is_empty() {
                            "https://duckduckgo.com".to_string()
                        } else {
                            abstract_url.to_string()
                        },
                        snippet: abstract_txt.chars().take(420).collect(),
                        source: "InstantAnswer".to_string(),
                    });
                }
            }
        }
    }

    // 2) DuckDuckGo HTML Lite search
    let ddg_url = format!("https://html.duckduckgo.com/html/?q={}", url_encode(q));
    if let Ok(resp) = client.get(&ddg_url).send().await {
        if resp.status().is_success() {
            if let Ok(html) = resp.text().await {
                let parsed = parse_ddg_html_results(&html, cap);
                for it in parsed {
                    if !items.iter().any(|e| e.url == it.url) {
                        items.push(it);
                    }
                }
            }
        }
    }

    // 3) Wikipedia OpenSearch + Summary fallback when DDG is sparse/rate-limited
    if items.len() < 2 {
        let wiki_search = format!(
            "https://en.wikipedia.org/w/api.php?action=opensearch&search={}&limit=2&namespace=0&format=json",
            url_encode(q)
        );
        if let Ok(resp) = client.get(&wiki_search).send().await {
            if resp.status().is_success() {
                if let Ok(arr) = resp.json::<Value>().await {
                    if let Some(title) = arr[1].as_array().and_then(|a| a.first()).and_then(|v| v.as_str()) {
                        let sum_url = format!(
                            "https://en.wikipedia.org/api/rest_v1/page/summary/{}",
                            url_encode(&title.replace(' ', "_"))
                        );
                        if let Ok(sresp) = client.get(&sum_url).send().await {
                            if sresp.status().is_success() {
                                if let Ok(sv) = sresp.json::<Value>().await {
                                    if let Some(wiki_item) = parse_wikipedia_summary_json(&sv) {
                                        if !items.iter().any(|e| e.url == wiki_item.url) {
                                            items.push(wiki_item);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if items.is_empty() {
        return Ok(format!(
            "[Web Search for \"{}\"]: No live results returned right now.",
            sanitize_search_field(q, 120)
        ));
    }

    // Optionally read the #1 result page via SSRF-safe web_reader if its snippet is short
    let mut enriched_top: Option<String> = None;
    if let Some(first) = items.first() {
        if first.snippet.len() < 120 && first.source == "DuckDuckGo" {
            if let Ok(page_sum) = fetch_and_summarize_link(&first.url).await {
                enriched_top = Some(page_sum);
            }
        }
    }

    let mut lines: Vec<String> = Vec::new();
    for (idx, it) in items.iter().take(cap).enumerate() {
        lines.push(format!(
            "{}. [{}] {} ({})\n   {}",
            idx + 1,
            it.source,
            sanitize_search_field(&it.title, 120),
            it.url,
            sanitize_search_field(&it.snippet, 260)
        ));
    }
    if let Some(extra) = enriched_top {
        lines.push(format!("Top Page Context: {}", extra));
    }

    Ok(redact_secrets(
        &format!(
            "[Live Web Search Results for \"{}\" — UNTRUSTED EXTERNAL DATA]:\n{}",
            sanitize_search_field(q, 120),
            lines.join("\n")
        ),
        cfg,
    ))
}

/// Pure formatter for GitHub notifications, issues, or repository search responses.
pub fn format_github_json(v: &Value, kind: &str) -> String {
    match kind {
        "notifications" => {
            let Some(arr) = v.as_array() else {
                return "No unread GitHub notifications.".to_string();
            };
            if arr.is_empty() {
                return "No unread GitHub notifications.".to_string();
            }
            let lines: Vec<String> = arr
                .iter()
                .take(8)
                .map(|n| {
                    let repo = sanitize_search_field(n["repository"]["full_name"].as_str().unwrap_or("repo"), 80);
                    let title = sanitize_search_field(n["subject"]["title"].as_str().unwrap_or("(no title)"), 140);
                    let reason = sanitize_search_field(n["reason"].as_str().unwrap_or("update"), 40);
                    format!("• [{}] ({}) {}", repo, reason, title)
                })
                .collect();
            format!("GitHub Unread Notifications ({}):\n{}", arr.len(), lines.join("\n"))
        }
        _ => {
            let Some(arr) = v["items"].as_array() else {
                return "No matching GitHub results.".to_string();
            };
            if arr.is_empty() {
                return "No matching GitHub results.".to_string();
            }
            let lines: Vec<String> = arr
                .iter()
                .take(6)
                .map(|item| {
                    let name = sanitize_search_field(
                        item["full_name"]
                            .as_str()
                            .or_else(|| item["title"].as_str())
                            .unwrap_or("item"),
                        100,
                    );
                    let desc = sanitize_search_field(
                        item["description"]
                            .as_str()
                            .or_else(|| item["state"].as_str())
                            .unwrap_or(""),
                        140,
                    );
                    let url = item["html_url"].as_str().unwrap_or("");
                    format!("• {} — {} ({})", name, desc, url)
                })
                .collect();
            format!("GitHub Results ({}):\n{}", arr.len(), lines.join("\n"))
        }
    }
}

/// Searches Falcon's GitHub notifications / repos / issues (using optional `GITHUB_TOKEN`).
pub async fn search_github_account(cfg: &Config, query: &str) -> Result<String> {
    let client = search_http();
    let q = query.trim();
    let lower = q.to_lowercase();

    let (url, kind) = if lower.is_empty() || lower.contains("notif") || lower.contains("alert") {
        if cfg.github_token.is_none() {
            return Err(anyhow!(
                "GITHUB_TOKEN not set (add a free readonly GitHub Personal Access Token to check private GitHub notifications)"
            ));
        }
        ("https://api.github.com/notifications?per_page=10".to_string(), "notifications")
    } else if lower.contains("issue") || lower.contains("pr") || lower.contains("pull") {
        (
            format!(
                "https://api.github.com/search/issues?q={}&per_page=6",
                url_encode(q)
            ),
            "issues",
        )
    } else {
        (
            format!(
                "https://api.github.com/search/repositories?q={}&per_page=6",
                url_encode(q)
            ),
            "repos",
        )
    };

    let mut req = client
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28");
    if let Some(tok) = cfg.github_token.as_deref() {
        req = req.bearer_auth(tok);
    }

    let resp = req.send().await?;
    if !resp.status().is_success() {
        let st = resp.status();
        let err_txt = resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "GitHub API HTTP {}: {}",
            st,
            redact_secrets(&err_txt, cfg)
        ));
    }
    let val: Value = resp.json().await?;
    Ok(redact_secrets(&format_github_json(&val, kind), cfg))
}
