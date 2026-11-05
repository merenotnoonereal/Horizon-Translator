//! Asset validation and repair: cleans and fixes JSON/.mcmeta syntax issues,
//! validates PNG headers, and reports non-fatal warnings for corrupt files.

use std::io::Cursor;
use crate::files::Files;
use crate::mcmeta::{decode_bytes_to_utf8, sanitize_json};

pub struct ValidationStats {
    pub json_repaired: usize,
    pub notes: Vec<String>,
    pub warnings: Vec<String>,
}

pub fn validate_and_repair(files: &mut Files) -> ValidationStats {
    let mut notes = Vec::new();
    let mut warnings = Vec::new();
    let mut json_repaired = 0;

    let mut repaired_entries = Vec::new();

    for (path, bytes) in &files.entries {
        // Validate / repair JSON and MCMETA files
        if path.ends_with(".json") || path.ends_with(".mcmeta") {
            let (text, decode_notes) = decode_bytes_to_utf8(bytes);
            if !decode_notes.is_empty() {
                notes.push(format!("`{path}`: {}", decode_notes.join(", ")));
            }

            match serde_json::from_str::<serde_json::Value>(&text) {
                Ok(_) => {
                    // Valid without sanitation, but if BOM or non-UTF8 was stripped, update
                    if text.as_bytes() != bytes.as_slice() {
                        repaired_entries.push((path.clone(), text.into_bytes()));
                        json_repaired += 1;
                    }
                }
                Err(_) => {
                    // Try sanitation (comments, trailing commas, unescaped controls)
                    let sanitized = sanitize_json(&text);
                    match serde_json::from_str::<serde_json::Value>(&sanitized) {
                        Ok(_) => {
                            repaired_entries.push((path.clone(), sanitized.into_bytes()));
                            json_repaired += 1;
                            notes.push(format!("Repaired JSON comments/trailing commas in `{path}`."));
                        }
                        Err(e) => {
                            // Check if the file starts with a valid root JSON object/array followed by trailing garbage
                            let mut stream = serde_json::Deserializer::from_str(&sanitized).into_iter::<serde_json::Value>();
                            if let Some(Ok(val)) = stream.next() {
                                if (val.is_object() || val.is_array()) && e.to_string().contains("trailing characters") {
                                    if let Ok(rebuilt) = serde_json::to_string_pretty(&val) {
                                        repaired_entries.push((path.clone(), rebuilt.into_bytes()));
                                        json_repaired += 1;
                                        notes.push(format!("Repaired trailing characters after root JSON in `{path}`."));
                                        continue;
                                    }
                                }
                            }
                            warnings.push(format!("Invalid JSON syntax in `{path}` ({e}); kept in pack as-is."));
                        }
                    }
                }
            }
        }

        // Validate PNG textures
        if path.ends_with(".png") {
            const PNG_SIG: &[u8; 8] = &[137, 80, 78, 71, 13, 10, 26, 10];
            if !bytes.starts_with(PNG_SIG) {
                warnings.push(format!("Corrupt PNG header in `{path}` (invalid magic signature); kept as-is."));
                continue;
            }

            let decoder = png::Decoder::new(Cursor::new(bytes));
            if let Err(e) = decoder.read_info() {
                warnings.push(format!("Unreadable PNG image in `{path}` ({e}); kept as-is."));
            }
        }
    }

    for (p, d) in repaired_entries {
        files.entries.insert(p, d);
    }

    ValidationStats {
        json_repaired,
        notes,
        warnings,
    }
}
