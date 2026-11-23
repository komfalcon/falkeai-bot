use std::env;

pub const BOT_NAME: &str = "FalkeAI";
pub const INTRO_MESSAGE: &str = "Hey! I'm Falcon's Personal Symbiotic Agent. I work alongside him, keeping conversations moving and handling things in his absence. He's unavailable at the moment, but I'm here. What's up?";
pub const HANDOFF_REPLY: &str = "I'll leave this one for Falcon.";
pub const SUBJECTS: [&str; 3] = ["Physics", "Chemistry", "Maths"];
pub const EXAMS: [&str; 5] = ["JAMB", "WAEC", "NECO", "JUPEB", "A-level"];
pub const DEFAULT_SAFE_DOMAINS: [&str; 2] = ["aurikrex.tech", "aurikrex.com"];

pub const GROQ_BASE_URL: &str = "https://api.groq.com/openai/v1";
pub const DEFAULT_GROQ_MODELS: [&str; 3] = [
    "qwen/qwen3.8-27b",
    "openai/gpt-oss-120b",
    "openai/gpt-oss-20b",
];
pub const DEFAULT_CF_MODELS: [&str; 3] = [
    "@cf/mistralai/mistral-small-3.1-24b-instruct",
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "@cf/meta/llama-3.1-8b-instruct-fast",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiAttempt {
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

#[derive(Debug, Clone)]
pub struct Config {
    pub turso_url: String,
    pub turso_auth_token: Option<String>,
    pub pair_phone: String,
    pub ai_key: String,
    pub groq_keys: Vec<String>,
    pub cf_account_id: String,
    pub cf_api_token: String,
    pub ai_base_url: String,
    pub ai_model: String,
    pub ai_attempts: Vec<AiAttempt>,
    pub whisper_model: String,
    pub vision_model: String,
    pub ntfy_url: String,
    pub ntfy_topic: String,
    pub ntfy_token: Option<String>,
    pub digest_hour_lagos: u32,
    pub max_media_per_day: usize,
    pub tts_voice: String,
    pub max_tts_per_day: usize,
    pub max_web_reads_per_day: usize,
    pub default_reply_mode: String,
    pub reply_wait_ms: i64,
    pub takeover_pause_ms: i64,
    pub bot_session_idle_ms: i64,
    pub open_hour: u32,
    pub close_hour: u32,
    pub max_customer_msgs_per_hour: usize,
    pub ignore_older_than_seconds: i64,
    pub bridge_bin: String,
    pub health_port: u16,
}

fn push_unique_keys(out: &mut Vec<String>, raw: &str) {
    for part in raw.split(',') {
        let k = part.trim();
        if !k.is_empty() && !out.iter().any(|existing| existing == k) {
            out.push(k.to_string());
        }
    }
}

pub fn normalize_ntfy_url(raw: &str) -> String {
    let trimmed = raw
        .trim()
        .trim_matches(|c| c == '"' || c == '\'')
        .trim_end_matches('/');
    if trimmed.is_empty() {
        return "https://ntfy.sh".to_string();
    }
    if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        trimmed.to_string()
    } else {
        format!("https://{}", trimmed)
    }
}

/// Extracts a clean ntfy topic name even if the user accidentally pasted a full subscription URL
/// like `https://ntfy.sh/my-secret-topic` or wrapped it in quotes.
pub fn normalize_ntfy_topic(raw: &str, base_url: &str) -> String {
    let mut s = raw
        .trim()
        .trim_matches(|c| c == '"' || c == '\'')
        .trim()
        .trim_matches('/')
        .to_string();
    let lower = s.to_lowercase();
    let base_no_scheme = base_url
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .to_lowercase();

    if lower.starts_with("https://") || lower.starts_with("http://") {
        if let Some(after_scheme) = s.split("://").nth(1) {
            if let Some((_, topic_part)) = after_scheme.split_once('/') {
                s = topic_part.trim_matches('/').to_string();
            }
        }
    } else if !base_no_scheme.is_empty() && lower.starts_with(&(base_no_scheme.clone() + "/")) {
        s = s[base_no_scheme.len() + 1..].trim_matches('/').to_string();
    } else if lower.starts_with("ntfy.sh/") {
        s = s["ntfy.sh/".len()..].trim_matches('/').to_string();
    }
    s.split('/').next().unwrap_or("").trim().to_string()
}

/// Builds the ordered multi-provider LLM failover chain:
/// 1) Groq keys (model-first across all configured keys: Qwen 3.8 27B -> GPT-OSS 120B -> GPT-OSS 20B)
/// 2) Custom primary / fallback OpenAI-compatible providers (if configured)
/// 3) Cloudflare Workers AI backup (`CF_ACCOUNT_ID` + `CF_API_TOKEN`)
pub fn build_ai_attempts(
    groq_keys: &[String],
    groq_preferred_model: Option<&str>,
    primary_base_url: &str,
    primary_key: &str,
    primary_model: &str,
    fallback_base_url: Option<&str>,
    fallback_key: Option<&str>,
    fallback_model: Option<&str>,
    cf_account_id: &str,
    cf_api_token: &str,
) -> Vec<AiAttempt> {
    let mut attempts: Vec<AiAttempt> = Vec::new();
    let mut push_attempt = |provider: String, base_url: String, api_key: String, model: String| {
        if base_url.trim().is_empty() || api_key.trim().is_empty() || model.trim().is_empty() {
            return;
        }
        let dup = attempts.iter().any(|a| {
            a.base_url == base_url && a.api_key == api_key && a.model == model
        });
        if !dup {
            attempts.push(AiAttempt {
                provider,
                base_url,
                api_key,
                model,
            });
        }
    };

    // 1) Groq pool: model-first across all keys so key #1 rate limits fail over to key #2
    // on the same top model before stepping down to the next model pool.
    if !groq_keys.is_empty() {
        let mut groq_models: Vec<String> = Vec::new();
        if let Some(pref) = groq_preferred_model
            .map(str::trim)
            .filter(|m| !m.is_empty() && !m.starts_with("@cf/"))
        {
            groq_models.push(pref.to_string());
        }
        for m in DEFAULT_GROQ_MODELS {
            if !groq_models.iter().any(|existing| existing == m) {
                groq_models.push(m.to_string());
            }
        }
        for model in &groq_models {
            for (idx, key) in groq_keys.iter().enumerate() {
                push_attempt(
                    format!("groq#{}", idx + 1),
                    GROQ_BASE_URL.to_string(),
                    key.clone(),
                    model.clone(),
                );
            }
        }
    }

    // 2) Custom primary provider (non-Groq, non-Cloudflare; or Cloudflare if explicitly primary with no Groq)
    let primary_is_groq = primary_base_url.contains("groq.com");
    let primary_is_cf = primary_base_url.contains("cloudflare.com");
    if !primary_key.trim().is_empty() && !primary_is_groq && (!primary_is_cf || groq_keys.is_empty()) {
        let mut keys = Vec::new();
        push_unique_keys(&mut keys, primary_key);
        let mut models = Vec::new();
        if !primary_model.trim().is_empty() {
            models.push(primary_model.trim().to_string());
        }
        if primary_is_cf {
            for m in DEFAULT_CF_MODELS {
                if !models.iter().any(|existing| existing == m) {
                    models.push(m.to_string());
                }
            }
        }
        for model in &models {
            for (idx, key) in keys.iter().enumerate() {
                let label = if primary_is_cf {
                    "cloudflare".to_string()
                } else if keys.len() > 1 {
                    format!("primary#{}", idx + 1)
                } else {
                    "primary".to_string()
                };
                push_attempt(
                    label,
                    primary_base_url.trim().to_string(),
                    key.clone(),
                    model.clone(),
                );
            }
        }
    }

    // 3) Explicit generic fallback provider (AI_FALLBACK_*)
    if let Some(fk) = fallback_key.map(str::trim).filter(|s| !s.is_empty()) {
        let f_url = fallback_base_url
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(primary_base_url);
        let mut f_keys = Vec::new();
        push_unique_keys(&mut f_keys, fk);
        let mut f_models = Vec::new();
        if let Some(fm) = fallback_model.map(str::trim).filter(|s| !s.is_empty()) {
            f_models.push(fm.to_string());
        } else if f_url.contains("groq.com") {
            f_models.extend(DEFAULT_GROQ_MODELS.iter().map(|s| s.to_string()));
        } else if f_url.contains("cloudflare.com") {
            f_models.extend(DEFAULT_CF_MODELS.iter().map(|s| s.to_string()));
        } else if !primary_model.trim().is_empty() {
            f_models.push(primary_model.trim().to_string());
        }
        for model in &f_models {
            for (idx, key) in f_keys.iter().enumerate() {
                push_attempt(
                    if f_keys.len() > 1 {
                        format!("fallback#{}", idx + 1)
                    } else {
                        "fallback".to_string()
                    },
                    f_url.to_string(),
                    key.clone(),
                    model.clone(),
                );
            }
        }
    }

    // 4) Cloudflare Workers AI automatic backup whenever CF_ACCOUNT_ID + CF_API_TOKEN exist
    if !cf_account_id.trim().is_empty() && !cf_api_token.trim().is_empty() {
        let cf_url = format!(
            "https://api.cloudflare.com/client/v4/accounts/{}/ai/v1",
            cf_account_id.trim()
        );
        let mut cf_models: Vec<String> = Vec::new();
        if primary_model.trim().starts_with("@cf/") {
            cf_models.push(primary_model.trim().to_string());
        }
        for m in DEFAULT_CF_MODELS {
            if !cf_models.iter().any(|existing| existing == m) {
                cf_models.push(m.to_string());
            }
        }
        for model in cf_models {
            push_attempt(
                "cloudflare".to_string(),
                cf_url.clone(),
                cf_api_token.trim().to_string(),
                model,
            );
        }
    }

    attempts
}

impl Config {
    pub fn has_ai(&self) -> bool {
        !self.ai_attempts.is_empty() || !self.ai_key.trim().is_empty()
    }

    pub fn from_env() -> Self {
        let turso_url = env::var("TURSO_DATABASE_URL")
            .or_else(|_| env::var("LIBSQL_URL"))
            .unwrap_or_default();
        let turso_auth_token = env::var("TURSO_AUTH_TOKEN")
            .or_else(|_| env::var("LIBSQL_AUTH_TOKEN"))
            .ok()
            .filter(|s| !s.trim().is_empty());

        let pair_phone: String = env::var("PAIR_PHONE")
            .unwrap_or_default()
            .chars()
            .filter(|c| c.is_ascii_digit())
            .collect();

        let raw_ai_key = env::var("AI_API_KEY").unwrap_or_default();
        let raw_ai_base_url = env::var("AI_BASE_URL").unwrap_or_default();
        let cf_account_id = {
            let explicit = env::var("CF_ACCOUNT_ID").unwrap_or_default().trim().to_string();
            if !explicit.is_empty() {
                explicit
            } else if raw_ai_base_url.contains("/accounts/") {
                raw_ai_base_url
                    .split("/accounts/")
                    .nth(1)
                    .and_then(|s| s.split('/').next())
                    .map(|s| s.trim().to_string())
                    .unwrap_or_default()
            } else {
                String::new()
            }
        };
        let explicit_cf_token = env::var("CF_API_TOKEN")
            .or_else(|_| env::var("CLOUDFLARE_API_TOKEN"))
            .unwrap_or_default()
            .trim()
            .to_string();

        // Collect all Groq API keys from GROQ_API_KEY(_2/_3) and AI_API_KEY (if Groq URL or gsk_ prefix)
        let mut groq_keys: Vec<String> = Vec::new();
        for var in ["GROQ_API_KEY", "GROQ_API_KEY_2", "GROQ_API_KEY_3"] {
            if let Ok(val) = env::var(var) {
                push_unique_keys(&mut groq_keys, &val);
            }
        }
        if raw_ai_base_url.contains("groq.com") || raw_ai_key.trim().starts_with("gsk_") {
            push_unique_keys(&mut groq_keys, &raw_ai_key);
        }

        let cf_api_token = if !explicit_cf_token.is_empty() {
            explicit_cf_token
        } else if (raw_ai_base_url.contains("cloudflare.com")
            || (raw_ai_base_url.trim().is_empty() && !cf_account_id.is_empty()))
            && !raw_ai_key.trim().starts_with("gsk_")
        {
            raw_ai_key.trim().to_string()
        } else {
            String::new()
        };

        let ai_base_url = if !raw_ai_base_url.trim().is_empty() {
            raw_ai_base_url.trim().to_string()
        } else if !groq_keys.is_empty() {
            GROQ_BASE_URL.to_string()
        } else if !cf_account_id.is_empty() {
            format!(
                "https://api.cloudflare.com/client/v4/accounts/{}/ai/v1",
                cf_account_id
            )
        } else {
            "https://api.openai.com/v1".to_string()
        };

        let ai_key = if !groq_keys.is_empty() {
            groq_keys[0].clone()
        } else if !raw_ai_key.trim().is_empty() {
            raw_ai_key.trim().to_string()
        } else {
            cf_api_token.clone()
        };

        let raw_ai_model = env::var("AI_MODEL").ok().filter(|s| !s.trim().is_empty());
        let groq_model_env = env::var("GROQ_MODEL").ok().filter(|s| !s.trim().is_empty());
        let ai_model = raw_ai_model.clone().unwrap_or_else(|| {
            if !groq_keys.is_empty() || ai_base_url.contains("groq.com") {
                DEFAULT_GROQ_MODELS[0].to_string()
            } else {
                DEFAULT_CF_MODELS[0].to_string()
            }
        });

        let fallback_base_url = env::var("AI_FALLBACK_BASE_URL")
            .ok()
            .filter(|s| !s.trim().is_empty());
        let fallback_key = env::var("AI_FALLBACK_API_KEY")
            .ok()
            .filter(|s| !s.trim().is_empty());
        let fallback_model = env::var("AI_FALLBACK_MODEL")
            .ok()
            .filter(|s| !s.trim().is_empty());

        let ai_attempts = build_ai_attempts(
            &groq_keys,
            groq_model_env.as_deref().or(raw_ai_model.as_deref()),
            &ai_base_url,
            if raw_ai_key.trim().is_empty() { &ai_key } else { &raw_ai_key },
            &ai_model,
            fallback_base_url.as_deref(),
            fallback_key.as_deref(),
            fallback_model.as_deref(),
            &cf_account_id,
            &cf_api_token,
        );

        let whisper_model = env::var("WHISPER_MODEL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "@cf/openai/whisper-large-v3-turbo".to_string());
        let vision_model = env::var("VISION_MODEL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "@cf/mistralai/mistral-small-3.1-24b-instruct".to_string());

        let ntfy_url = normalize_ntfy_url(
            &env::var("NTFY_URL")
                .ok()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or_else(|| "https://ntfy.sh".to_string()),
        );
        let ntfy_topic = normalize_ntfy_topic(
            &env::var("NTFY_TOPIC").unwrap_or_default(),
            &ntfy_url,
        );
        let ntfy_token = env::var("NTFY_TOKEN")
            .ok()
            .filter(|s| !s.trim().is_empty());

        let digest_hour_lagos: u32 = env::var("DIGEST_HOUR_LAGOS")
            .ok()
            .and_then(|v| v.parse().ok())
            .filter(|&h| h < 24)
            .unwrap_or(7);
        let max_media_per_day: usize = env::var("MAX_MEDIA_PER_DAY")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(40);
        let tts_voice = env::var("TTS_VOICE")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "troy".to_string());
        let max_tts_per_day: usize = env::var("MAX_TTS_PER_DAY")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(25);
        let max_web_reads_per_day: usize = env::var("MAX_WEB_READS_PER_DAY")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(40);

        let default_reply_mode = match env::var("REPLY_MODE").unwrap_or_default().as_str() {
            "unsaved" => "unsaved".to_string(),
            _ => "everyone".to_string(),
        };

        let wait_mins: i64 = env::var("REPLY_WAIT_MINUTES")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(5);
        let pause_mins: i64 = env::var("TAKEOVER_PAUSE_MINUTES")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(120);

        let bridge_bin = env::var("WA_BRIDGE_BIN").unwrap_or_else(|_| "./wa-bridge".to_string());
        let health_port: u16 = env::var("PORT")
            .or_else(|_| env::var("WEBSITES_PORT"))
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(8080);

        Self {
            turso_url,
            turso_auth_token,
            pair_phone,
            ai_key,
            groq_keys,
            cf_account_id,
            cf_api_token,
            ai_base_url,
            ai_model,
            ai_attempts,
            whisper_model,
            vision_model,
            ntfy_url,
            ntfy_topic,
            ntfy_token,
            digest_hour_lagos,
            max_media_per_day,
            tts_voice,
            max_tts_per_day,
            max_web_reads_per_day,
            default_reply_mode,
            reply_wait_ms: wait_mins.max(0) * 60_000,
            takeover_pause_ms: pause_mins.max(1) * 60_000,
            bot_session_idle_ms: 12 * 60 * 60_000,
            open_hour: 11,
            close_hour: 18,
            max_customer_msgs_per_hour: 40,
            ignore_older_than_seconds: 120,
            bridge_bin,
            health_port,
        }
    }
}
