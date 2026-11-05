//! Data-driven 1.8-1.21+ path and texture remapping for blocks, items, armor, models and blockstates.

use std::collections::{BTreeMap, HashMap};
use std::sync::LazyLock;
use crate::files::Files;

const MAPPINGS_RAW: &str = include_str!("mappings.txt");

struct Mappings {
    block: HashMap<&'static str, &'static str>,
    item: HashMap<&'static str, &'static str>,
}

static MAPPINGS: LazyLock<Mappings> = LazyLock::new(|| {
    let mut block = HashMap::new();
    let mut item = HashMap::new();

    for line in MAPPINGS_RAW.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 3 {
            match parts[0] {
                "block" => {
                    block.insert(parts[1], parts[2]);
                }
                "item" => {
                    item.insert(parts[1], parts[2]);
                }
                _ => {}
            }
        }
    }

    Mappings { block, item }
});

pub struct RemapStats {
    pub dir_moves: usize,
    pub textures_renamed: usize,
    pub armor_copied: usize,
    pub models_updated: usize,
    pub notes: Vec<String>,
}

pub fn remap_pack(files: &mut Files) -> RemapStats {
    let mut notes = Vec::new();
    let mut dir_moves = 0;
    let mut textures_renamed = 0;
    let mut armor_copied = 0;
    let mut models_updated = 0;

    let mut new_entries = BTreeMap::new();
    let old_entries = std::mem::take(&mut files.entries);

    // 1. Move textures/blocks -> textures/block and textures/items -> textures/item
    let existing_keys: std::collections::HashSet<String> = old_entries.keys().cloned().collect();
    for (path, data) in old_entries {
        let mut new_path = path.clone();
        let mut moved = false;

        if let Some(rest) = path.strip_prefix("assets/") {
            if let Some((ns, sub)) = rest.split_once('/') {
                if let Some(suffix) = sub.strip_prefix("textures/blocks/") {
                    let candidate = format!("assets/{ns}/textures/block/{suffix}");
                    if !existing_keys.contains(&candidate) && !new_entries.contains_key(&candidate) {
                        new_path = candidate;
                        moved = true;
                    }
                } else if let Some(suffix) = sub.strip_prefix("textures/items/") {
                    let candidate = format!("assets/{ns}/textures/item/{suffix}");
                    if !existing_keys.contains(&candidate) && !new_entries.contains_key(&candidate) {
                        new_path = candidate;
                        moved = true;
                    }
                }
            }
        }

        if moved {
            dir_moves += 1;
        }
        new_entries.insert(new_path, data);
    }

    // 2. Rename textures using mappings table (only when the target path doesn't already exist)
    let after_move_keys: std::collections::HashSet<String> = new_entries.keys().cloned().collect();
    let mut intermediate_entries = BTreeMap::new();
    for (path, data) in new_entries {
        let mut renamed_path = path.clone();
        let mut did_rename = false;

        if let Some(rest) = path.strip_prefix("assets/") {
            if let Some((ns, sub)) = rest.split_once('/') {
                if let Some(rest_block) = sub.strip_prefix("textures/block/") {
                    let is_mcmeta = rest_block.ends_with(".png.mcmeta");
                    let stem = if is_mcmeta {
                        rest_block.trim_end_matches(".png.mcmeta")
                    } else if rest_block.ends_with(".png") {
                        rest_block.trim_end_matches(".png")
                    } else {
                        ""
                    };

                    if !stem.is_empty() {
                        if let Some(target_stem) = lookup_mapped_stem(&MAPPINGS.block, stem) {
                            let ext = if is_mcmeta { ".png.mcmeta" } else { ".png" };
                            let candidate = format!("assets/{ns}/textures/block/{target_stem}{ext}");
                            if candidate != path
                                && !after_move_keys.contains(&candidate)
                                && !intermediate_entries.contains_key(&candidate)
                            {
                                renamed_path = candidate;
                                did_rename = true;
                            }
                        }
                    }
                } else if let Some(rest_item) = sub.strip_prefix("textures/item/") {
                    let is_mcmeta = rest_item.ends_with(".png.mcmeta");
                    let stem = if is_mcmeta {
                        rest_item.trim_end_matches(".png.mcmeta")
                    } else if rest_item.ends_with(".png") {
                        rest_item.trim_end_matches(".png")
                    } else {
                        ""
                    };

                    if !stem.is_empty() {
                        if let Some(target_stem) = lookup_mapped_stem(&MAPPINGS.item, stem) {
                            let ext = if is_mcmeta { ".png.mcmeta" } else { ".png" };
                            let candidate = format!("assets/{ns}/textures/item/{target_stem}{ext}");
                            if candidate != path
                                && !after_move_keys.contains(&candidate)
                                && !intermediate_entries.contains_key(&candidate)
                            {
                                renamed_path = candidate;
                                did_rename = true;
                            }
                        }
                    }
                }
            }
        }

        if did_rename {
            textures_renamed += 1;
        }
        intermediate_entries.insert(renamed_path, data);
    }

    // 3. Copy legacy armor textures to 1.21.2+ equipment paths
    let mut armor_additions = Vec::new();
    for (path, data) in &intermediate_entries {
        if let Some(stem) = path
            .strip_prefix("assets/minecraft/textures/models/armor/")
            .and_then(|p| p.strip_suffix(".png"))
        {
            if let Some(mat) = stem.strip_suffix("_layer_1") {
                let modern_path = format!("assets/minecraft/textures/entity/equipment/humanoid/{mat}.png");
                if !intermediate_entries.contains_key(&modern_path) {
                    armor_additions.push((modern_path, data.clone()));
                    armor_copied += 1;
                }
            } else if let Some(mat) = stem.strip_suffix("_layer_2") {
                let modern_path = format!("assets/minecraft/textures/entity/equipment/humanoid_leggings/{mat}.png");
                if !intermediate_entries.contains_key(&modern_path) {
                    armor_additions.push((modern_path, data.clone()));
                    armor_copied += 1;
                }
            }
        }
    }
    for (path, data) in armor_additions {
        intermediate_entries.insert(path, data);
    }

    // 3b. Normalize texture and model file paths under `assets/<ns>/textures/` and `assets/<ns>/models/`
    // to valid 1.13+ resource identifiers (lowercase ASCII, spaces -> underscores).
    let pre_norm_keys: std::collections::HashSet<String> = intermediate_entries.keys().cloned().collect();
    let mut normalized_entries = BTreeMap::new();
    for (path, data) in intermediate_entries {
        let mut target_path = path.clone();
        if let Some(rest) = path.strip_prefix("assets/") {
            if let Some((ns, sub)) = rest.split_once('/') {
                if sub.starts_with("textures/") || sub.starts_with("models/") || sub.starts_with("blockstates/") {
                    let norm_sub = sub.to_ascii_lowercase().replace(' ', "_");
                    if norm_sub != sub {
                        let candidate = format!("assets/{}/{}", ns.to_ascii_lowercase(), norm_sub);
                        if !pre_norm_keys.contains(&candidate) && !normalized_entries.contains_key(&candidate) {
                            target_path = candidate;
                            textures_renamed += 1;
                        }
                    }
                }
            }
        }
        normalized_entries.insert(target_path, data);
    }
    let mut intermediate_entries = normalized_entries;

    // 3c. If a legacy pack provides `fire_1.png` (from `fire_layer_2.png`) without `fire_0.png`,
    // copy `fire_1.png` (and its `.mcmeta`) to `fire_0.png` so both fire layers use the pack's fire texture.
    let fire1 = "assets/minecraft/textures/block/fire_1.png";
    let fire0 = "assets/minecraft/textures/block/fire_0.png";
    if !intermediate_entries.contains_key(fire0) {
        if let Some(bytes) = intermediate_entries.get(fire1).cloned() {
            intermediate_entries.insert(fire0.to_string(), bytes);
            if let Some(mcmeta) = intermediate_entries.get("assets/minecraft/textures/block/fire_1.png.mcmeta").cloned() {
                intermediate_entries
                    .entry("assets/minecraft/textures/block/fire_0.png.mcmeta".to_string())
                    .or_insert(mcmeta);
            }
        }
    }

    // 4. First pass: rename model and blockstate files using mappings table
    let existing_tex_keys: std::collections::HashSet<String> = intermediate_entries.keys().cloned().collect();
    let mut renamed_model_entries = BTreeMap::new();
    let mut renamed_paths_count = 0usize;
    for (path, data) in intermediate_entries {
        let is_model_or_blockstate = (path.contains("/models/") || path.contains("/blockstates/"))
            && path.ends_with(".json");

        if is_model_or_blockstate {
            let mut renamed_path = path.clone();
            if let Some(stem) = path.strip_prefix("assets/minecraft/models/item/").and_then(|p| p.strip_suffix(".json")) {
                if let Some(&target) = MAPPINGS.item.get(stem) {
                    let candidate = format!("assets/minecraft/models/item/{target}.json");
                    if !existing_tex_keys.contains(&candidate) && !renamed_model_entries.contains_key(&candidate) {
                        renamed_path = candidate;
                    }
                }
            } else if let Some(stem) = path.strip_prefix("assets/minecraft/models/block/").and_then(|p| p.strip_suffix(".json")) {
                if let Some(&target) = MAPPINGS.block.get(stem) {
                    let candidate = format!("assets/minecraft/models/block/{target}.json");
                    if !existing_tex_keys.contains(&candidate) && !renamed_model_entries.contains_key(&candidate) {
                        renamed_path = candidate;
                    }
                }
            } else if let Some(stem) = path.strip_prefix("assets/minecraft/blockstates/").and_then(|p| p.strip_suffix(".json")) {
                if stem == "tall_grass" && existing_tex_keys.contains("assets/minecraft/blockstates/double_grass.json") {
                    let candidate = "assets/minecraft/blockstates/short_grass.json".to_string();
                    if !existing_tex_keys.contains(&candidate) && !renamed_model_entries.contains_key(&candidate) {
                        renamed_path = candidate;
                    }
                } else if stem == "double_grass" {
                    let candidate = "assets/minecraft/blockstates/tall_grass.json".to_string();
                    if !renamed_model_entries.contains_key(&candidate) {
                        renamed_path = candidate;
                    }
                } else if let Some(&target) = MAPPINGS.block.get(stem) {
                    let candidate = format!("assets/minecraft/blockstates/{target}.json");
                    if !existing_tex_keys.contains(&candidate) && !renamed_model_entries.contains_key(&candidate) {
                        renamed_path = candidate;
                    }
                }
            }
            if renamed_path != path {
                renamed_paths_count += 1;
            }
            renamed_model_entries.insert(renamed_path, data);
        } else {
            renamed_model_entries.insert(path, data);
        }
    }

    // 5. Second pass: rewrite JSON references inside models and blockstates
    let post_rename_keys: std::collections::HashSet<String> = renamed_model_entries.keys().cloned().collect();
    let mut final_entries = BTreeMap::new();
    for (path, data) in renamed_model_entries {
        let is_model_or_blockstate = (path.contains("/models/") || path.contains("/blockstates/"))
            && path.ends_with(".json");

        if is_model_or_blockstate {
            let text = String::from_utf8_lossy(&data).into_owned();
            let rewritten = rewrite_model_json(&path, &text, &post_rename_keys);
            if rewritten != text {
                models_updated += 1;
                final_entries.insert(path, rewritten.into_bytes());
            } else {
                final_entries.insert(path, data);
            }
        } else {
            final_entries.insert(path, data);
        }
    }
    models_updated += renamed_paths_count;

    files.entries = final_entries;

    if dir_moves > 0 {
        notes.push(format!("Moved {dir_moves} texture file(s) from `blocks/` and `items/` to modern `block/` and `item/`."));
    }
    if textures_renamed > 0 {
        notes.push(format!("Renamed {textures_renamed} block/item texture(s) to modern 1.13-1.21 names."));
    }
    if armor_copied > 0 {
        notes.push(format!("Copied {armor_copied} legacy armor layer texture(s) to 1.21.2+ humanoid equipment paths."));
    }
    if models_updated > 0 {
        notes.push(format!("Updated {models_updated} model/blockstate JSON file(s) with modern texture paths."));
    }

    RemapStats {
        dir_moves,
        textures_renamed,
        armor_copied,
        models_updated,
        notes,
    }
}

fn lookup_mapped_stem(map: &HashMap<&'static str, &'static str>, stem: &str) -> Option<String> {
    if let Some(&target) = map.get(stem) {
        return Some(target.to_string());
    }
    for suffix in ["_n", "_s", "_e"] {
        if let Some(base) = stem.strip_suffix(suffix) {
            if let Some(&target) = map.get(base) {
                return Some(format!("{target}{suffix}"));
            }
        }
    }
    None
}

/// Rewrite texture, parent, and model references in model and blockstate JSONs:
/// - Replaces `blocks/` with `block/` and `items/` with `item/`
/// - Remaps legacy texture names inside `block/...` and `item/...` unless the legacy texture file is still preserved alongside the modern one
/// - Remaps 1.8 fire model parents (`block/fire_floor` -> `block/template_fire_floor`, etc.)
/// - Normalizes invalid resource identifiers (uppercase, spaces, bare `"parent"` names, bare blockstate `"model"` names)
fn rewrite_model_json(path: &str, json_str: &str, existing_files: &std::collections::HashSet<String>) -> String {
    let mut out = json_str.replace("blocks/", "block/").replace("items/", "item/");

    // Legacy 1.8 fire parent templates renamed in 1.13+
    for (old_parent, new_parent) in [
        ("block/fire_floor", "block/template_fire_floor"),
        ("minecraft:block/fire_floor", "minecraft:block/template_fire_floor"),
        ("block/fire_side", "block/template_fire_side"),
        ("minecraft:block/fire_side", "minecraft:block/template_fire_side"),
        ("block/fire_side_alt", "block/template_fire_side_alt"),
        ("minecraft:block/fire_side_alt", "minecraft:block/template_fire_side_alt"),
        ("block/fire_up", "block/template_fire_up"),
        ("minecraft:block/fire_up", "minecraft:block/template_fire_up"),
        ("block/fire_up_alt", "block/template_fire_up_alt"),
        ("minecraft:block/fire_up_alt", "minecraft:block/template_fire_up_alt"),
    ] {
        let old_file_check = format!(
            "assets/minecraft/models/{}.json",
            old_parent.strip_prefix("minecraft:").unwrap_or(old_parent)
        );
        if !existing_files.contains(&old_file_check) {
            let pat = format!("\"{old_parent}\"");
            let rep = format!("\"{new_parent}\"");
            if out.contains(&pat) {
                out = out.replace(&pat, &rep);
            }
        }
    }

    // Check block mappings
    for (&old, &new) in &MAPPINGS.block {
        if old == new {
            continue;
        }
        let legacy_tex = format!("assets/minecraft/textures/block/{old}.png");
        let legacy_model = format!("assets/minecraft/models/block/{old}.json");
        if existing_files.contains(&legacy_tex) || existing_files.contains(&legacy_model) {
            continue;
        }
        let pattern1 = format!("block/{old}\"");
        let repl1 = format!("block/{new}\"");
        if out.contains(&pattern1) {
            out = out.replace(&pattern1, &repl1);
        }

        let pattern2 = format!("blocks/{old}\"");
        let repl2 = format!("block/{new}\"");
        if out.contains(&pattern2) {
            out = out.replace(&pattern2, &repl2);
        }
    }

    // Check item mappings
    for (&old, &new) in &MAPPINGS.item {
        if old == new {
            continue;
        }
        let legacy_tex = format!("assets/minecraft/textures/item/{old}.png");
        let legacy_model = format!("assets/minecraft/models/item/{old}.json");
        if existing_files.contains(&legacy_tex) || existing_files.contains(&legacy_model) {
            continue;
        }
        let pattern1 = format!("item/{old}\"");
        let repl1 = format!("item/{new}\"");
        if out.contains(&pattern1) {
            out = out.replace(&pattern1, &repl1);
        }

        let pattern2 = format!("items/{old}\"");
        let repl2 = format!("item/{new}\"");
        if out.contains(&pattern2) {
            out = out.replace(&pattern2, &repl2);
        }
    }

    // Structural normalization via serde_json if the JSON parses cleanly
    if let Ok(mut val) = serde_json::from_str::<serde_json::Value>(&out) {
        let mut struct_changed = false;
        if path.contains("/models/") {
            if let Some(obj) = val.as_object_mut() {
                // Check "parent": if bare name (no `/`) or contains spaces, drop it if model has its own elements,
                // or normalize to lowercase/underscore.
                let has_elements = obj.contains_key("elements");
                let mut drop_parent = false;
                if let Some(parent_val) = obj.get_mut("parent") {
                    if let Some(p_str) = parent_val.as_str() {
                        let clean = p_str.strip_prefix("minecraft:").unwrap_or(p_str);
                        if !clean.contains('/') || clean.contains(' ') {
                            if has_elements {
                                drop_parent = true;
                            } else {
                                let norm = format!("block/{}", clean.to_ascii_lowercase().replace(' ', "_"));
                                *parent_val = serde_json::Value::String(norm);
                                struct_changed = true;
                            }
                        } else {
                            let norm = p_str.to_ascii_lowercase().replace('\\', "/").replace(' ', "_");
                            if norm != p_str {
                                *parent_val = serde_json::Value::String(norm);
                                struct_changed = true;
                            }
                        }
                    }
                }
                if drop_parent {
                    obj.remove("parent");
                    struct_changed = true;
                }

                // Normalize texture paths in "textures" object (lowercase, spaces -> `_`, `\` -> `/`)
                if let Some(tex_obj) = obj.get_mut("textures").and_then(|t| t.as_object_mut()) {
                    for (_k, v) in tex_obj.iter_mut() {
                        if let Some(s) = v.as_str() {
                            if !s.starts_with('#') {
                                let norm = s.to_ascii_lowercase().replace('\\', "/").replace(' ', "_");
                                if norm != s {
                                    *v = serde_json::Value::String(norm);
                                    struct_changed = true;
                                }
                            }
                        }
                    }
                }
            }
        } else if path.contains("/blockstates/") {
            if rewrite_blockstate_models(&mut val, existing_files) {
                struct_changed = true;
            }
        }

        if struct_changed {
            if let Ok(serialized) = serde_json::to_string_pretty(&val) {
                out = serialized;
            }
        }
    }

    out
}

fn rewrite_blockstate_models(val: &mut serde_json::Value, existing_files: &std::collections::HashSet<String>) -> bool {
    let mut changed = false;
    match val {
        serde_json::Value::Object(map) => {
            if let Some(variants_val) = map.get_mut("variants") {
                if let Some(variants_obj) = variants_val.as_object_mut() {
                    if let Some(normal_val) = variants_obj.remove("normal") {
                        if !variants_obj.contains_key("") {
                            variants_obj.insert("".to_string(), normal_val);
                            changed = true;
                        } else {
                            variants_obj.insert("normal".to_string(), normal_val);
                        }
                    }
                }
            }
            if let Some(model_val) = map.get_mut("model") {
                if let Some(m_str) = model_val.as_str() {
                    let norm_raw = m_str.to_ascii_lowercase().replace('\\', "/").replace(' ', "_");
                    let clean = norm_raw.strip_prefix("minecraft:").unwrap_or(&norm_raw);
                    let mut final_model = if !clean.contains('/') {
                        let stem = MAPPINGS.block.get(clean).copied().unwrap_or(clean);
                        let direct = format!("assets/minecraft/models/block/{stem}.json");
                        if !existing_files.contains(&direct) {
                            let bottom = format!("assets/minecraft/models/block/{stem}_bottom.json");
                            if existing_files.contains(&bottom) {
                                format!("block/{stem}_bottom")
                            } else {
                                format!("block/{stem}")
                            }
                        } else {
                            format!("block/{stem}")
                        }
                    } else {
                        if let Some(stem) = clean.strip_prefix("block/") {
                            let mapped = MAPPINGS.block.get(stem).copied().unwrap_or(stem);
                            let direct = format!("assets/minecraft/models/block/{mapped}.json");
                            if !existing_files.contains(&direct) {
                                let bottom = format!("assets/minecraft/models/block/{mapped}_bottom.json");
                                if existing_files.contains(&bottom) {
                                    format!("block/{mapped}_bottom")
                                } else {
                                    format!("block/{mapped}")
                                }
                            } else {
                                format!("block/{mapped}")
                            }
                        } else {
                            norm_raw.clone()
                        }
                    };
                    if m_str.starts_with("minecraft:") && !final_model.starts_with("minecraft:") {
                        final_model = format!("minecraft:{final_model}");
                    }
                    if final_model != m_str {
                        *model_val = serde_json::Value::String(final_model);
                        changed = true;
                    }
                }
            }
            for (_k, v) in map.iter_mut() {
                if rewrite_blockstate_models(v, existing_files) {
                    changed = true;
                }
            }
        }
        serde_json::Value::Array(arr) => {
            for item in arr.iter_mut() {
                if rewrite_blockstate_models(item, existing_files) {
                    changed = true;
                }
            }
        }
        _ => {}
    }
    changed
}
