//! Legacy 1.8-1.12 `.lang` to `.json` conversion with Latin-1/UTF-8 decoding and key merging.

use std::collections::BTreeMap;
use crate::files::Files;
use crate::mcmeta::decode_bytes_to_utf8;

pub struct LangStats {
    pub converted: usize,
    pub notes: Vec<String>,
}

pub fn convert_legacy_lang(files: &mut Files) -> LangStats {
    let mut notes = Vec::new();
    let mut converted = 0;

    let mut json_additions = Vec::new();

    for (path, bytes) in &files.entries {
        if let Some(rest) = path.strip_prefix("assets/") {
            if let Some((ns, sub)) = rest.split_once('/') {
                if let Some(filename) = sub.strip_prefix("lang/") {
                    if let Some(code) = filename.strip_suffix(".lang") {
                        let lower_code = code.to_ascii_lowercase();
                        let target_json_path = format!("assets/{ns}/lang/{lower_code}.json");

                        let mut entries = parse_lang_file(bytes);

                        // If the target json already exists, merge with it (existing json keys win)
                        if let Some(existing_bytes) = files.entries.get(&target_json_path) {
                            if let Ok(Value::Object(map)) = serde_json::from_slice::<serde_json::Value>(existing_bytes) {
                                for (k, v) in map {
                                    if let Some(s) = v.as_str() {
                                        entries.insert(k, s.to_string());
                                    }
                                }
                            }
                        }

                        if !entries.is_empty() {
                            if let Ok(json_text) = serde_json::to_string_pretty(&entries) {
                                json_additions.push((target_json_path.clone(), json_text.into_bytes()));
                                converted += 1;
                                notes.push(format!(
                                    "Converted `{path}` -> `{lower_code}.json` ({} entries).",
                                    entries.len()
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    for (p, d) in json_additions {
        files.entries.insert(p, d);
    }

    LangStats { converted, notes }
}

use serde_json::Value;

fn parse_lang_file(bytes: &[u8]) -> BTreeMap<String, String> {
    let (text, _) = decode_bytes_to_utf8(bytes);
    let mut map = BTreeMap::new();

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        if let Some((key, val)) = trimmed.split_once('=') {
            map.insert(key.trim().to_string(), val.to_string());
        }
    }

    map
}
