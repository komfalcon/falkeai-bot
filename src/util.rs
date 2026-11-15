use chrono::{DateTime, Datelike, Timelike, Utc};
use chrono_tz::Africa::Lagos;

#[derive(Debug, Clone)]
pub struct LagosTimeParts {
    pub text: String,
    pub hour: u32,
    pub iso: String,
    pub open: bool,
}

pub fn digits(s: &str) -> String {
    s.chars().filter(|c| c.is_ascii_digit()).collect()
}

/// '234...@s.whatsapp.net', '234...:7@s.whatsapp.net', '1234@lid' -> digits only
pub fn number_of_jid(jid: &str) -> String {
    let before_at = jid.split('@').next().unwrap_or("");
    let before_colon = before_at.split(':').next().unwrap_or("");
    digits(before_colon)
}

/// Nigerian local format 0903... becomes 234903...
pub fn normalize_number(raw: &str) -> String {
    let d = digits(raw);
    if d.starts_with('0') && d.len() == 11 {
        format!("234{}", &d[1..])
    } else {
        d
    }
}

pub fn lagos_parts(now_utc: DateTime<Utc>, open_hour: u32, close_hour: u32) -> LagosTimeParts {
    let lagos = now_utc.with_timezone(&Lagos);
    let hour = lagos.hour();
    let open = hour >= open_hour && hour < close_hour;

    let weekday = lagos.weekday();
    let weekday_name = match weekday {
        chrono::Weekday::Mon => "Monday",
        chrono::Weekday::Tue => "Tuesday",
        chrono::Weekday::Wed => "Wednesday",
        chrono::Weekday::Thu => "Thursday",
        chrono::Weekday::Fri => "Friday",
        chrono::Weekday::Sat => "Saturday",
        chrono::Weekday::Sun => "Sunday",
    };
    let month_name = match lagos.month() {
        1 => "January",
        2 => "February",
        3 => "March",
        4 => "April",
        5 => "May",
        6 => "June",
        7 => "July",
        8 => "August",
        9 => "September",
        10 => "October",
        11 => "November",
        12 => "December",
        _ => "",
    };
    let (is_pm, hour12) = lagos.hour12();
    let am_pm = if is_pm { "pm" } else { "am" };

    let text = format!(
        "{}, {:02} {} {} at {:02}:{:02} {}",
        weekday_name,
        lagos.day(),
        month_name,
        lagos.year(),
        hour12,
        lagos.minute(),
        am_pm
    );
    let iso = lagos.format("%Y-%m-%dT%H:%M:%S+01:00").to_string();

    LagosTimeParts {
        text,
        hour,
        iso,
        open,
    }
}

/// Blank line = new WhatsApp message. At most `max` bubbles; the rest is merged into the last one.
pub fn split_bubbles(text: &str, max: usize) -> Vec<String> {
    let mut parts: Vec<String> = Vec::new();
    let mut current: Vec<&str> = Vec::new();

    for line in text.lines() {
        if line.trim().is_empty() {
            if !current.is_empty() {
                let joined = current.join("\n").trim().to_string();
                if !joined.is_empty() {
                    parts.push(joined);
                }
                current.clear();
            }
        } else {
            current.push(line);
        }
    }
    if !current.is_empty() {
        let joined = current.join("\n").trim().to_string();
        if !joined.is_empty() {
            parts.push(joined);
        }
    }

    if parts.len() <= max || max == 0 {
        return parts;
    }
    let mut result = parts[..max - 1].to_vec();
    result.push(parts[max - 1..].join("\n"));
    result
}
