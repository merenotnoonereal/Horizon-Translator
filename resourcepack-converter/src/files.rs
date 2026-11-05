//! A pack's files in memory, archive normalization, and deterministic zip output.

use std::collections::BTreeMap;
use std::io::{Cursor, Read, Write};
use std::path::Path;

pub struct Files {
    /// The pack's file or folder name.
    pub name: String,
    /// Every file by its path from the pack's root (`assets/...`, `pack.mcmeta`, ...).
    pub entries: BTreeMap<String, Vec<u8>>,
    /// Changes and warnings noted while reading (nested folder unwrapped, bad path skipped, etc.).
    pub layout_notes: Vec<String>,
}

impl Files {
    pub fn read(path: &Path) -> Result<Self, String> {
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let mut entries = BTreeMap::new();
        let mut layout_notes = Vec::new();

        if path.is_dir() {
            walk(path, path, &mut entries, &mut layout_notes)
                .map_err(|e| format!("{}: {e}", path.display()))?;
        } else {
            let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
            let mut zip = zip::ZipArchive::new(Cursor::new(bytes))
                .map_err(|e| format!("{}: not a readable zip: {e}", path.display()))?;
            for i in 0..zip.len() {
                let mut f = match zip.by_index(i) {
                    Ok(entry) => entry,
                    Err(e) => {
                        layout_notes.push(format!("Zip entry #{i} could not be opened: {e}"));
                        continue;
                    }
                };
                let raw_name = f.name().to_string();
                if f.is_dir() || raw_name.ends_with('/') || raw_name.ends_with('\\') {
                    continue;
                }
                let sanitized = match sanitize_path(&raw_name) {
                    Ok(Some(p)) => p,
                    Ok(None) => continue, // Ignored OS metadata files (__MACOSX, .DS_Store, Thumbs.db)
                    Err(warning) => {
                        layout_notes.push(format!("Skipped invalid zip entry `{raw_name}`: {warning}"));
                        continue;
                    }
                };
                let mut data = Vec::new();
                if let Err(e) = f.read_to_end(&mut data) {
                    layout_notes.push(format!("Could not read zip entry `{raw_name}`: {e}"));
                    continue;
                }
                entries.insert(sanitized, data);
            }
        }

        let mut files = Files {
            name,
            entries,
            layout_notes,
        };
        files.unwrap_folder();

        if !files.entries.keys().any(|k| k == "pack.mcmeta" || k.starts_with("assets/")) {
            return Err(format!("{}: neither pack.mcmeta nor assets/ found: not a resource pack", files.name));
        }

        Ok(files)
    }

    /// A zip that wraps the pack in a subfolder (`MyPack/pack.mcmeta` or `MyPack/assets/...`)
    /// is unwrapped so `pack.mcmeta` and `assets/` sit at the root.
    fn unwrap_folder(&mut self) {
        if self.entries.keys().any(|k| k == "pack.mcmeta" || k.starts_with("assets/")) {
            return;
        }
        // Look for pack.mcmeta in a subfolder first
        let prefix = self
            .entries
            .keys()
            .find_map(|k| k.find("/pack.mcmeta").map(|i| k[..=i].to_owned()))
            .or_else(|| {
                self.entries
                    .keys()
                    .find_map(|k| k.find("/assets/").map(|i| k[..=i].to_owned()))
            });

        let Some(prefix) = prefix else {
            return;
        };

        self.entries = std::mem::take(&mut self.entries)
            .into_iter()
            .filter_map(|(k, v)| k.strip_prefix(&prefix).map(|s| (s.to_owned(), v)))
            .collect();
        self.layout_notes.push(format!(
            "The pack was wrapped inside `{prefix}`; unwrapped so `pack.mcmeta` and `assets/` are at the root."
        ));
    }

    /// Read file content as string if present (lossy UTF-8).
    pub fn text(&self, path: &str) -> Option<String> {
        self.entries.get(path).map(|b| String::from_utf8_lossy(b).into_owned())
    }

    /// Set file bytes.
    pub fn set_bytes(&mut self, path: &str, data: Vec<u8>) {
        self.entries.insert(path.to_string(), data);
    }

    /// Set file text (UTF-8).
    pub fn set_text(&mut self, path: &str, text: String) {
        self.entries.insert(path.to_string(), text.into_bytes());
    }

    /// Remove a file.
    pub fn remove(&mut self, path: &str) -> Option<Vec<u8>> {
        self.entries.remove(path)
    }

    /// Deterministic zip bytes with fixed 1980-01-01 00:00:00 timestamp and sorted entries.
    pub fn zip_bytes(&self) -> Result<Vec<u8>, String> {
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated)
            .last_modified_time(zip::DateTime::default());

        for (k, v) in &self.entries {
            w.start_file(k.as_str(), opts).map_err(|e| e.to_string())?;
            w.write_all(v).map_err(|e| e.to_string())?;
        }
        Ok(w.finish().map_err(|e| e.to_string())?.into_inner())
    }

    /// Write deterministic zip archive to disk.
    pub fn write_zip(&self, to: &Path) -> Result<(), String> {
        let bytes = self.zip_bytes()?;
        std::fs::write(to, bytes).map_err(|e| format!("{}: {e}", to.display()))
    }
}

/// Sanitize zip entry path:
/// - Replaces `\` with `/`
/// - Strips leading `/`
/// - Drops OS metadata (`__MACOSX/`, `.DS_Store`, `Thumbs.db`) -> returns `Ok(None)`
/// - Blocks directory traversal (`..`), empty segments, colons (`:`), and null bytes (`\0`) -> returns `Err`
fn sanitize_path(raw: &str) -> Result<Option<String>, &'static str> {
    if raw.contains('\0') {
        return Err("path contains null byte");
    }
    let normalized = raw.replace('\\', "/");
    let trimmed = normalized.trim_start_matches('/');
    if trimmed.is_empty() {
        return Err("empty entry path");
    }

    // Check for junk OS metadata
    if trimmed.starts_with("__MACOSX/")
        || trimmed.ends_with(".DS_Store")
        || trimmed.ends_with("Thumbs.db")
    {
        return Ok(None);
    }

    // Check individual segments
    let mut clean_segments = Vec::new();
    for seg in trimmed.split('/') {
        if seg.is_empty() || seg == "." {
            continue;
        }
        if seg == ".." {
            return Err("path traversal (..) detected");
        }
        if seg.contains(':') {
            return Err("path contains drive colon (:)");
        }
        clean_segments.push(seg);
    }

    if clean_segments.is_empty() {
        return Err("path has no valid segments");
    }

    Ok(Some(clean_segments.join("/")))
}

fn walk(
    root: &Path,
    dir: &Path,
    out: &mut BTreeMap<String, Vec<u8>>,
    notes: &mut Vec<String>,
) -> std::io::Result<()> {
    for e in std::fs::read_dir(dir)? {
        let p = e?.path();
        if p.is_dir() {
            walk(root, &p, out, notes)?;
        } else {
            let rel = p
                .strip_prefix(root)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
            match sanitize_path(&rel) {
                Ok(Some(clean)) => {
                    out.insert(clean, std::fs::read(&p)?);
                }
                Ok(None) => {}
                Err(e) => {
                    notes.push(format!("Skipped `{rel}` in directory: {e}"));
                }
            }
        }
    }
    Ok(())
}
