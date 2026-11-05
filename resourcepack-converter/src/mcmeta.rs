//! `pack.mcmeta` reading, Latin-1/BOM repair, lenient JSON recovery, and format upgrading.

use serde_json::{Value, json};

pub struct McmetaResult {
    pub original_format: i64,
    pub updated_json: String,
    pub notes: Vec<String>,
}

/// Upgrade or synthesize `pack.mcmeta` in `Files`.
pub fn process_mcmeta(raw_bytes: Option<&[u8]>, pack_name: &str) -> McmetaResult {
    let mut notes = Vec::new();
    let (mut meta, orig_format) = match raw_bytes {
        Some(bytes) => {
            let (decoded_str, decoded_notes) = decode_bytes_to_utf8(bytes);
            notes.extend(decoded_notes);
            match parse_lenient_json(&decoded_str) {
                Ok(val) => {
                    let fmt = extract_pack_format(&val);
                    (val, fmt)
                }
                Err(err) => {
                    notes.push(format!("pack.mcmeta JSON was unrecoverable ({err}); synthesizing a valid one."));
                    let val = synthesize_default_meta(pack_name);
                    (val, 1)
                }
            }
        }
        None => {
            notes.push("pack.mcmeta was missing; synthesized a valid one.".to_string());
            let val = synthesize_default_meta(pack_name);
            (val, 1)
        }
    };

    // Ensure "pack" object exists
    if !meta.get("pack").is_some_and(|p| p.is_object()) {
        meta["pack"] = json!({ "description": pack_name });
    }

    // The newest format the pack says it supports, read before it is rewritten below.
    let pack_upper = upper_bound(&meta["pack"], "supported_formats").unwrap_or(orig_format);

    let clamped = orig_format.clamp(15, 64);
    let pack = meta.get_mut("pack").unwrap();

    // 1. pack_format
    pack["pack_format"] = json!(clamped);

    // 2. supported_formats (min_inclusive, max_inclusive)
    pack["supported_formats"] = json!({
        "min_inclusive": clamped,
        "max_inclusive": 999
    });

    // 3. min_format and max_format tuples
    pack["min_format"] = json!([clamped, 0]);
    pack["max_format"] = json!([999, 0]);

    notes.push(format!(
        "Set pack format compatibility: format {clamped} (orig: {orig_format}), supported_formats: [{clamped}, 999], min/max_format: [{clamped}, 0]..[999, 0]"
    ));

    // Overlays: keep on the ones the pack's newest supported version uses
    if let Some(overlay_notes) = update_overlays(&mut meta, pack_upper) {
        notes.extend(overlay_notes);
    }

    let updated_json = serde_json::to_string_pretty(&meta).unwrap_or_else(|_| "{}".to_string());

    McmetaResult {
        original_format: orig_format,
        updated_json,
        notes,
    }
}

fn extract_pack_format(meta: &Value) -> i64 {
    let pack = &meta["pack"];
    if let Some(f) = pack["pack_format"].as_i64() {
        return f;
    }
    // Fallbacks
    if let Some(min) = pack["min_format"].as_array().and_then(|a| a.first()).and_then(|v| v.as_i64()) {
        return min;
    }
    if let Some(min) = pack["min_format"].as_i64() {
        return min;
    }
    if let Some(min) = pack["supported_formats"].as_array().and_then(|a| a.first()).and_then(|v| v.as_i64()) {
        return min;
    }
    if let Some(min) = pack["supported_formats"]["min_inclusive"].as_i64() {
        return min;
    }
    1
}

fn synthesize_default_meta(pack_name: &str) -> Value {
    json!({
        "pack": {
            "pack_format": 1,
            "description": pack_name
        }
    })
}

/// Overlays are active when the game's format is inside their range. Horizon's format (97) is past the
/// range of packs made for older versions, so their overlays would all be off. The pack's newest supported
/// version has every overlay whose range holds that version's format on (for example 20-3, 21-2, 21-5 and
/// 21-11 at format 75); those get their upper bound raised to 999 so Horizon uses the same set. Overlays for
/// older versions only stay off.
fn update_overlays(meta: &mut Value, pack_upper: i64) -> Option<Vec<String>> {
    let entries = meta.get_mut("overlays")?.get_mut("entries")?.as_array_mut()?;
    if entries.is_empty() {
        return None;
    }
    let newest = entries.iter().filter_map(get_overlay_upper_bound).max().unwrap_or(-1);
    let target = pack_upper.max(newest);
    if target >= 97 {
        return Some(Vec::new());
    }

    let mut extended = Vec::new();
    for entry in entries.iter_mut() {
        let Some(upper) = get_overlay_upper_bound(entry) else { continue };
        let lower = get_overlay_lower_bound(entry).unwrap_or(i64::MIN);
        if upper >= 97 || lower > target || upper < target {
            continue;
        }
        let dir = entry["directory"].as_str().unwrap_or("unknown").to_string();
        if entry.get("max_format").is_some() {
            entry["max_format"] = json!([999, 0]);
        }
        if let Some(formats) = entry.get_mut("formats") {
            if let Some(arr) = formats.as_array_mut() {
                if let Some(last) = arr.get_mut(1) {
                    *last = json!(999);
                } else if arr.len() == 1 {
                    arr.push(json!(999));
                }
            } else if formats.is_object() {
                formats["max_inclusive"] = json!(999);
            } else if let Some(n) = formats.as_i64() {
                *formats = json!({
                    "min_inclusive": n,
                    "max_inclusive": 999
                });
            }
        } else if entry.get("max_format").is_none() {
            entry["max_format"] = json!([999, 0]);
        }
        extended.push(format!("`{dir}`"));
    }

    let mut notes = Vec::new();
    if !extended.is_empty() {
        notes.push(format!(
            "Kept on the overlay(s) {} that the pack's newest version (format {target}) uses, by raising their upper format to 999.",
            extended.join(", ")
        ));
    }
    Some(notes)
}

/// The upper end of a `supported_formats`-style value: an int, `[min, max]`, or `{min_inclusive, max_inclusive}`;
/// falls back to `max_format` (int or `[major, minor]`) and then `pack_format`.
fn upper_bound(obj: &Value, key: &str) -> Option<i64> {
    let f = &obj[key];
    let from_formats = f
        .as_i64()
        .or_else(|| f.as_array().and_then(|a| a.last()).and_then(|v| v.as_i64()))
        .or_else(|| f.get("max_inclusive").and_then(|v| v.as_i64()));
    let from_max = obj["max_format"]
        .as_i64()
        .or_else(|| obj["max_format"].as_array().and_then(|a| a.first()).and_then(|v| v.as_i64()));
    match (from_formats, from_max) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (a, b) => a.or(b).or_else(|| obj["pack_format"].as_i64()),
    }
}

fn get_overlay_lower_bound(entry: &Value) -> Option<i64> {
    if let Some(min_f) = entry.get("min_format") {
        if let Some(n) = min_f.as_i64().or_else(|| min_f.as_array().and_then(|a| a.first()).and_then(|v| v.as_i64())) {
            return Some(n);
        }
    }
    let f = entry.get("formats")?;
    f.as_i64()
        .or_else(|| f.as_array().and_then(|a| a.first()).and_then(|v| v.as_i64()))
        .or_else(|| f.get("min_inclusive").and_then(|v| v.as_i64()))
}

fn get_overlay_upper_bound(entry: &Value) -> Option<i64> {
    if let Some(max_f) = entry.get("max_format") {
        if let Some(arr) = max_f.as_array() {
            if let Some(v) = arr.first().and_then(|x| x.as_i64()) {
                return Some(v);
            }
        }
        if let Some(n) = max_f.as_i64() {
            return Some(n);
        }
    }
    if let Some(formats) = entry.get("formats") {
        if let Some(arr) = formats.as_array() {
            if let Some(v) = arr.get(1).or_else(|| arr.first()).and_then(|x| x.as_i64()) {
                return Some(v);
            }
        }
        if let Some(max_inc) = formats.get("max_inclusive").and_then(|v| v.as_i64()) {
            return Some(max_inc);
        }
        if let Some(n) = formats.as_i64() {
            return Some(n);
        }
    }
    None
}

/// Decode bytes to UTF-8, stripping BOM and decoding Windows-1252 / Latin-1 if invalid UTF-8.
pub fn decode_bytes_to_utf8(bytes: &[u8]) -> (String, Vec<String>) {
    let mut notes = Vec::new();
    let data = if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        notes.push("Stripped UTF-8 BOM.".to_string());
        &bytes[3..]
    } else {
        bytes
    };

    match std::str::from_utf8(data) {
        Ok(s) => (s.to_string(), notes),
        Err(_) => {
            notes.push("Decoded non-UTF-8 bytes (Latin-1 / Windows-1252 § codes) to UTF-8.".to_string());
            let s: String = data.iter().map(|&b| decode_cp1252_byte(b)).collect();
            (s, notes)
        }
    }
}

/// Map byte in 0x80..=0xFF according to Windows-1252 (with fallback to Latin-1).
fn decode_cp1252_byte(b: u8) -> char {
    match b {
        0x80 => '€',
        0x82 => '‚',
        0x83 => 'ƒ',
        0x84 => '„',
        0x85 => '…',
        0x86 => '†',
        0x87 => '‡',
        0x88 => 'ˆ',
        0x89 => '‰',
        0x8A => 'Š',
        0x8B => '‹',
        0x8C => 'Œ',
        0x8E => 'Ž',
        0x91 => '‘',
        0x92 => '’',
        0x93 => '“',
        0x94 => '”',
        0x95 => '•',
        0x96 => '–',
        0x97 => '—',
        0x98 => '˜',
        0x99 => '™',
        0x9A => 'š',
        0x9B => '›',
        0x9C => 'œ',
        0x9E => 'ž',
        0x9F => 'Ÿ',
        _ => b as char, // ASCII and ISO-8859-1 (0xA7 is '§')
    }
}

/// Lenient JSON parsing: strips comments, trailing commas, and unescaped newlines in strings.
pub fn parse_lenient_json(input: &str) -> Result<Value, String> {
    if let Ok(v) = serde_json::from_str::<Value>(input) {
        return Ok(v);
    }
    let sanitized = sanitize_json(input);
    serde_json::from_str::<Value>(&sanitized).map_err(|e| e.to_string())
}

/// Strip comments (`//`, `/* ... */`, and `#` outside strings), fix trailing commas, escape literal control chars in strings.
pub fn sanitize_json(input: &str) -> String {
    let mut pass1 = String::with_capacity(input.len());
    let chars: Vec<char> = input.chars().collect();
    let len = chars.len();
    let mut i = 0;
    let mut in_string = false;
    let mut escaped = false;

    while i < len {
        let c = chars[i];
        if in_string {
            if escaped {
                escaped = false;
                pass1.push(c);
            } else if c == '\\' {
                escaped = true;
                pass1.push(c);
            } else if c == '"' {
                in_string = false;
                pass1.push(c);
            } else if c == '\n' {
                pass1.push_str("\\n");
            } else if c == '\r' {
                pass1.push_str("\\r");
            } else if c == '\t' {
                pass1.push_str("\\t");
            } else {
                pass1.push(c);
            }
            i += 1;
            continue;
        }

        // Outside string
        if c == '"' {
            in_string = true;
            pass1.push(c);
            i += 1;
            continue;
        }

        // Single-line comment `//` or `#`
        if (c == '/' && i + 1 < len && chars[i + 1] == '/') || c == '#' {
            i += 1;
            while i < len && chars[i] != '\n' {
                i += 1;
            }
            continue;
        }

        // Multi-line comment `/* ... */`
        if c == '/' && i + 1 < len && chars[i + 1] == '*' {
            i += 2;
            while i + 1 < len && !(chars[i] == '*' && chars[i + 1] == '/') {
                i += 1;
            }
            i += 2; // skip */
            continue;
        }

        pass1.push(c);
        i += 1;
    }

    // Pass 2: remove trailing commas before `}` or `]` now that comments are gone
    let chars2: Vec<char> = pass1.chars().collect();
    let len2 = chars2.len();
    let mut out = String::with_capacity(pass1.len());
    let mut k = 0;
    in_string = false;
    escaped = false;

    while k < len2 {
        let c = chars2[k];
        if in_string {
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == '"' {
                in_string = false;
            }
            out.push(c);
            k += 1;
            continue;
        }
        if c == '"' {
            in_string = true;
            out.push(c);
            k += 1;
            continue;
        }
        if c == ',' {
            let mut j = k + 1;
            while j < len2 && (chars2[j].is_whitespace() || chars2[j] == '\r' || chars2[j] == '\n') {
                j += 1;
            }
            if j < len2 && (chars2[j] == '}' || chars2[j] == ']') {
                k += 1;
                continue;
            }
        }
        out.push(c);
        k += 1;
    }

    out
}
