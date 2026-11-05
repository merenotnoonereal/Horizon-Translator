//! Fixes for packs made before the 1.13 "flattening" (pack_format 1 to 3) that renaming files can't do:
//! blockstate files written for the old block properties, the 1.8 inventory layout, and textures that
//! newer versions read from somewhere else (empty armor slots, status effect icons).

use crate::files::Files;
use crate::sheets::{RgbaImage, decode_png, encode_png};

/// Runs every legacy fix; returns notes for the report.
pub fn fix_legacy(files: &mut Files, original_format: i64) -> Vec<String> {
    let mut notes = Vec::new();
    if original_format < 4 {
        drop_old_blockstates(files, &mut notes);
    }
    upgrade_inventory_layout(files, &mut notes);
    copy_slot_sprites(files, &mut notes);
    split_effect_icons(files, &mut notes);
    notes
}

/// Blockstate files from before 1.13 name the old properties (`half=bottom` on slabs, which are `type=` now;
/// `variants` for fences and panes, which are `multipart` now). A modern block state that no variant matches
/// has no model, and Horizon then refuses the whole pack ("unbound gallery block state"). Java shows a
/// missing-model block instead. Only a file whose single variant is `""` matches every state, so only those
/// are kept; the others are dropped and the game's own blockstates use the pack's models and textures.
fn drop_old_blockstates(files: &mut Files, notes: &mut Vec<String>) {
    let mut dropped = Vec::new();
    for (path, bytes) in &files.entries {
        let Some(name) = path
            .strip_prefix("assets/minecraft/blockstates/")
            .and_then(|n| n.strip_suffix(".json"))
        else {
            continue;
        };
        let text = String::from_utf8_lossy(bytes);
        let keep = serde_json::from_str::<serde_json::Value>(text.trim_start_matches('\u{feff}'))
            .ok()
            .and_then(|v| v.get("variants").and_then(|v| v.as_object()).cloned())
            .is_some_and(|variants| !variants.is_empty() && variants.keys().all(|k| k.is_empty() || k == "normal"));
        if !keep {
            dropped.push((path.clone(), name.to_owned()));
        }
    }
    if dropped.is_empty() {
        return;
    }
    for (path, _) in &dropped {
        files.entries.remove(path);
    }
    let names: Vec<&str> = dropped.iter().map(|(_, n)| n.as_str()).collect();
    let shown = names.iter().take(12).copied().collect::<Vec<_>>().join(", ");
    let more = if names.len() > 12 { format!(" and {} more", names.len() - 12) } else { String::new() };
    notes.push(format!(
        "Dropped {} pre-1.13 blockstate file(s) written for the old block properties ({shown}{more}). \
         Horizon refuses a pack whose blockstate leaves a block state without a model; the game's own \
         blockstates now use this pack's models and textures for those blocks.",
        names.len()
    ));
}

fn px(img: &RgbaImage, x: u32, y: u32) -> [u8; 4] {
    let i = ((y * img.width + x) * 4) as usize;
    [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3]]
}

fn put(img: &mut RgbaImage, x: u32, y: u32, c: [u8; 4]) {
    let i = ((y * img.width + x) * 4) as usize;
    img.data[i..i + 4].copy_from_slice(&c);
}

/// Copies a w x h block from (sx, sy) to (dx, dy) (all in the image's own pixels).
fn copy_rect(img: &mut RgbaImage, sx: u32, sy: u32, w: u32, h: u32, dx: u32, dy: u32) {
    let block: Vec<[u8; 4]> = (0..h).flat_map(|y| (0..w).map(move |x| (x, y))).map(|(x, y)| px(img, sx + x, sy + y)).collect();
    for y in 0..h {
        for x in 0..w {
            put(img, dx + x, dy + y, block[(y * w + x) as usize]);
        }
    }
}

/// The 1.8 inventory (`gui/container/inventory.png`) has its 2x2 crafting grid, arrow and result slot 10
/// pixels left and 8 down from where 1.9+ draws the slots, and no off-hand slot. Moves them and adds the
/// off-hand slot (a copy of the pack's own bottom armor slot), when the image still has the old layout.
fn upgrade_inventory_layout(files: &mut Files, notes: &mut Vec<String>) {
    let key = "assets/minecraft/textures/gui/container/inventory.png";
    let Some(bytes) = files.entries.get(key) else { return };
    let Ok(mut img) = decode_png(bytes) else { return };
    if img.width < 256 || img.width % 256 != 0 || img.height < img.width * 166 / 256 {
        return;
    }
    let s = img.width / 256;
    let bg = px(&img, 170 * s, 8 * s);
    // Old layout: a slot frame at the 1.8 grid corner (87,25), plain background where 1.9+ has its grid
    // corner (97,17) and result slot (153,27).
    let old = px(&img, 87 * s, 25 * s) != bg && px(&img, 97 * s, 17 * s) == bg && px(&img, 153 * s, 27 * s) == bg;
    if !old {
        return;
    }
    // Everything of the crafting area is inside x 86..163, y 24..62 (1.8 coordinates).
    let (x0, y0, w, h) = (86 * s, 24 * s, 77 * s, 38 * s);
    let block: Vec<[u8; 4]> = (0..h).flat_map(|y| (0..w).map(move |x| (x, y))).map(|(x, y)| px(&img, x0 + x, y0 + y)).collect();
    for y in 0..h {
        for x in 0..w {
            put(&mut img, x0 + x, y0 + y, bg);
        }
    }
    for y in 0..h {
        for x in 0..w {
            put(&mut img, x0 + 10 * s + x, y0 - 8 * s + y, block[(y * w + x) as usize]);
        }
    }
    let mut added_offhand = false;
    // The off-hand slot (1.9+): frame at (76,61); the 1.8 image has background in its middle.
    if px(&img, 85 * s, 70 * s) == bg || px(&img, 85 * s, 70 * s)[..3] == [0, 0, 0] {
        copy_rect(&mut img, 7 * s, 61 * s, 18 * s, 18 * s, 76 * s, 61 * s);
        added_offhand = true;
    }
    if let Ok(out) = encode_png(&img) {
        files.entries.insert(key.to_owned(), out);
        notes.push(format!(
            "Moved the 1.8 inventory's crafting grid to where 1.9+ draws it{}.",
            if added_offhand { " and added an off-hand slot (a copy of the pack's armor slot)" } else { "" }
        ));
    }
}

/// Empty armor slot icons: `textures/item/empty_armor_slot_*.png` (up to 1.20.1) are
/// `textures/gui/sprites/container/slot/*.png` since 1.20.2. Copies them there (the old files stay).
fn copy_slot_sprites(files: &mut Files, notes: &mut Vec<String>) {
    let mut n = 0;
    for part in ["helmet", "chestplate", "leggings", "boots", "shield"] {
        let dest = format!("assets/minecraft/textures/gui/sprites/container/slot/{part}.png");
        if files.entries.contains_key(&dest) {
            continue;
        }
        let src = [
            format!("assets/minecraft/textures/item/empty_armor_slot_{part}.png"),
            format!("assets/minecraft/textures/items/empty_armor_slot_{part}.png"),
        ];
        if let Some(bytes) = src.iter().find_map(|p| files.entries.get(p)).cloned() {
            files.entries.insert(dest, bytes);
            n += 1;
        }
    }
    if n > 0 {
        notes.push(format!("Copied {n} empty armor slot icon(s) to `gui/sprites/container/slot/` (where 1.20.2+ reads them)."));
    }
}

/// Status effect icons: in 1.8 an 18x18 grid at the bottom of `gui/container/inventory.png` (from y 198),
/// since 1.14 one file each in `textures/mob_effect/`.
const EFFECT_ICONS: &[(&str, u32, u32)] = &[
    ("speed", 0, 0),
    ("slowness", 1, 0),
    ("haste", 2, 0),
    ("mining_fatigue", 3, 0),
    ("strength", 4, 0),
    ("weakness", 5, 0),
    ("poison", 6, 0),
    ("regeneration", 7, 0),
    ("invisibility", 0, 1),
    ("hunger", 1, 1),
    ("jump_boost", 2, 1),
    ("nausea", 3, 1),
    ("night_vision", 4, 1),
    ("blindness", 5, 1),
    ("resistance", 6, 1),
    ("fire_resistance", 7, 1),
    ("water_breathing", 0, 2),
    ("wither", 1, 2),
    ("absorption", 2, 2),
    ("health_boost", 7, 2),
];

fn split_effect_icons(files: &mut Files, notes: &mut Vec<String>) {
    let key = "assets/minecraft/textures/gui/container/inventory.png";
    let Some(bytes) = files.entries.get(key) else { return };
    let Ok(img) = decode_png(bytes) else { return };
    if img.width < 256 || img.width % 256 != 0 || img.height < img.width {
        return;
    }
    let s = img.width / 256;
    let mut n = 0;
    for &(name, col, row) in EFFECT_ICONS {
        let dest = format!("assets/minecraft/textures/mob_effect/{name}.png");
        if files.entries.contains_key(&dest) {
            continue;
        }
        let (x0, y0, w) = (col * 18 * s, (198 + row * 18) * s, 18 * s);
        let mut icon = RgbaImage { width: w, height: w, data: Vec::with_capacity((w * w * 4) as usize) };
        for y in 0..w {
            for x in 0..w {
                icon.data.extend_from_slice(&px(&img, x0 + x, y0 + y));
            }
        }
        if !icon.data.chunks_exact(4).any(|c| c[3] > 0) {
            continue;
        }
        if let Ok(out) = encode_png(&icon) {
            files.entries.insert(dest, out);
            n += 1;
        }
    }
    if n > 0 {
        notes.push(format!("Cut {n} status effect icon(s) out of the 1.8 inventory sheet into `textures/mob_effect/`."));
    }
}
