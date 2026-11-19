use std::env;

pub const BOT_NAME: &str = "FalkeAI";
pub const INTRO_MESSAGE: &str = "Hey! I'm Falcon's Personal Symbiotic Agent. I work alongside him, keeping conversations moving and handling things in his absence. He's unavailable at the moment, but I'm here. What's up?";
pub const HANDOFF_REPLY: &str = "I'll leave this one for Falcon.";
pub const SUBJECTS: [&str; 3] = ["Physics", "Chemistry", "Maths"];
pub const EXAMS: [&str; 5] = ["JAMB", "WAEC", "NECO", "JUPEB", "A-level"];
pub const DEFAULT_SAFE_DOMAINS: [&str; 2] = ["aurikrex.tech", "aurikrex.com"];

#[derive(Debug, Clone)]
pub struct Config {
    pub turso_url: String,
    pub turso_auth_token: Option<String>,
    pub pair_phone: String,
    pub ai_key: String,
    pub cf_account_id: String,
    pub ai_base_url: String,
    pub ai_model: String,
    pub whisper_model: String,
    pub vision_model: String,
    pub ntfy_url: String,
    pub ntfy_topic: String,
    pub ntfy_token: Option<String>,
    pub digest_hour_lagos: u32,
    pub max_media_per_day: usize,
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

impl Config {
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

        let ai_key = env::var("AI_API_KEY")
            .or_else(|_| env::var("CF_API_TOKEN"))
            .or_else(|_| env::var("CLOUDFLARE_API_TOKEN"))
            .unwrap_or_default();
        let cf_account_id = env::var("CF_ACCOUNT_ID").unwrap_or_default();
        let ai_base_url = env::var("AI_BASE_URL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| {
                if !cf_account_id.trim().is_empty() {
                    format!(
                        "https://api.cloudflare.com/client/v4/accounts/{}/ai/v1",
                        cf_account_id.trim()
                    )
                } else {
                    "https://api.openai.com/v1".to_string()
                }
            });
        let ai_model = env::var("AI_MODEL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "@cf/mistralai/mistral-small-3.1-24b-instruct".to_string());
        let whisper_model = env::var("WHISPER_MODEL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "@cf/openai/whisper-large-v3-turbo".to_string());
        let vision_model = env::var("VISION_MODEL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| ai_model.clone());

        let ntfy_url = env::var("NTFY_URL")
            .ok()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "https://ntfy.sh".to_string());
        let ntfy_topic = env::var("NTFY_TOPIC")
            .unwrap_or_default()
            .trim()
            .trim_matches('/')
            .to_string();
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
            cf_account_id: cf_account_id.trim().to_string(),
            ai_base_url,
            ai_model,
            whisper_model,
            vision_model,
            ntfy_url,
            ntfy_topic,
            ntfy_token,
            digest_hour_lagos,
            max_media_per_day,
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
