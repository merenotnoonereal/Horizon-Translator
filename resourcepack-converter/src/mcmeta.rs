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

    // Handle overlays if present: extend ONLY the newest overlay whose upper bound is < 97
    if let Some(overlay_notes) = update_overlays(&mut meta) {
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

/// Update only the newest overlay entry if its upper bound is < 97
fn update_overlays(meta: &mut Value) -> Option<Vec<String>> {
    let entries = meta.get_mut("overlays")?.get_mut("entries")?.as_array_mut()?;
    if entries.is_empty() {
        return None;
    }

    // Find the entry with the highest upper bound
    let mut best_index = None;
    let mut highest_bound = -1;

    for (idx, entry) in entries.iter().enumerate() {
        if let Some(bound) = get_overlay_upper_bound(entry) {
            if bound >= highest_bound {
                highest_bound = bound;
                best_index = Some(idx);
            }
        }
    }

    let mut notes = Vec::new();
    if let Some(idx) = best_index {
        if highest_bound < 97 {
            let entry = &mut entries[idx];
            let dir = entry["directory"].as_str().unwrap_or("unknown").to_string();

            // Extend max_format or formats
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
                // If neither formats nor max_format was set (rare), set max_format
                entry["max_format"] = json!([999, 0]);
            }

            notes.push(format!(
                "Extended newest overlay `{dir}` upper format to 999 (was {highest_bound}) so Horizon activates it without enabling older overlays."
            ));
        }
    }

    Some(notes)
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
