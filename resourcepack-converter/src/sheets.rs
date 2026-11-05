//! Splitting legacy texture sheets (icons.png, widgets.png, particles.png, paintings)
//! into modern 1.20.2+ standalone sprite and texture files.

use std::io::Cursor;
use crate::files::Files;

pub struct SheetStats {
    pub icons_sliced: usize,
    pub widgets_sliced: usize,
    pub particles_sliced: usize,
    pub paintings_sliced: usize,
    pub notes: Vec<String>,
}

pub fn split_legacy_sheets(files: &mut Files) -> SheetStats {
    let mut notes = Vec::new();
    let icons_sliced = split_icons(files, &mut notes);
    let widgets_sliced = split_widgets(files, &mut notes);
    let particles_sliced = split_particles(files, &mut notes);
    let paintings_sliced = split_paintings(files, &mut notes);

    SheetStats {
        icons_sliced,
        widgets_sliced,
        particles_sliced,
        paintings_sliced,
        notes,
    }
}

// -----------------------------------------------------------------------------------------
// 1. GUI Icons (textures/gui/icons.png -> textures/gui/sprites/hud/...)
// -----------------------------------------------------------------------------------------

struct SpriteDef {
    rel_path: &'static str,
    x: u32,
    y: u32,
    w: u32,
    h: u32,
}

const ICON_SPRITES: &[SpriteDef] = &[
    SpriteDef { rel_path: "hud/crosshair.png", x: 0, y: 0, w: 15, h: 15 },
    SpriteDef { rel_path: "hud/heart/container.png", x: 16, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/container_blinking.png", x: 25, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/container_hardcore.png", x: 16, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/container_hardcore_blinking.png", x: 25, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/full.png", x: 52, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/half.png", x: 61, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/full_blinking.png", x: 70, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/half_blinking.png", x: 79, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/hardcore_full.png", x: 52, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/hardcore_half.png", x: 61, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/hardcore_full_blinking.png", x: 70, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/hardcore_half_blinking.png", x: 79, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_full.png", x: 88, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_half.png", x: 97, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_full_blinking.png", x: 106, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_half_blinking.png", x: 115, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_hardcore_full.png", x: 88, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_hardcore_half.png", x: 97, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_hardcore_full_blinking.png", x: 106, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/poisoned_hardcore_half_blinking.png", x: 115, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_full.png", x: 124, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_half.png", x: 133, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_full_blinking.png", x: 142, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_half_blinking.png", x: 151, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_hardcore_full.png", x: 124, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_hardcore_half.png", x: 133, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_hardcore_full_blinking.png", x: 142, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/withered_hardcore_half_blinking.png", x: 151, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_full.png", x: 160, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_half.png", x: 169, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_full_blinking.png", x: 160, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_half_blinking.png", x: 169, y: 0, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_hardcore_full.png", x: 160, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_hardcore_half.png", x: 169, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_hardcore_full_blinking.png", x: 160, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/absorbing_hardcore_half_blinking.png", x: 169, y: 45, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/armor_empty.png", x: 16, y: 9, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/armor_half.png", x: 25, y: 9, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/armor_full.png", x: 34, y: 9, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/food_empty.png", x: 16, y: 27, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/food_half.png", x: 61, y: 27, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/food_full.png", x: 52, y: 27, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/food_empty_hunger.png", x: 43, y: 27, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/food_half_hunger.png", x: 79, y: 27, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/food_full_hunger.png", x: 70, y: 27, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/air.png", x: 16, y: 18, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/air_bursting.png", x: 25, y: 18, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/air_empty.png", x: 16, y: 18, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/experience_bar_background.png", x: 0, y: 64, w: 182, h: 5 },
    SpriteDef { rel_path: "hud/experience_bar_progress.png", x: 0, y: 69, w: 182, h: 5 },
    SpriteDef { rel_path: "hud/jump_bar_background.png", x: 0, y: 84, w: 182, h: 5 },
    SpriteDef { rel_path: "hud/jump_bar_progress.png", x: 0, y: 89, w: 182, h: 5 },
    SpriteDef { rel_path: "hud/heart/vehicle_container.png", x: 54, y: 9, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/vehicle_full.png", x: 63, y: 9, w: 9, h: 9 },
    SpriteDef { rel_path: "hud/heart/vehicle_half.png", x: 72, y: 9, w: 9, h: 9 },
];

fn split_icons(files: &mut Files, notes: &mut Vec<String>) -> usize {
    let key = "assets/minecraft/textures/gui/icons.png";
    let Some(bytes) = files.entries.get(key).cloned() else {
        return 0;
    };
    let Ok(img) = decode_png(&bytes) else {
        notes.push("Could not decode textures/gui/icons.png".to_string());
        return 0;
    };

    let scale = (img.width / 256).max(1);
    let mut count = 0;
    let mut added = Vec::new();

    for def in ICON_SPRITES {
        let dest_path = format!("assets/minecraft/textures/gui/sprites/{}", def.rel_path);
        if files.entries.contains_key(&dest_path) {
            continue;
        }

        if let Some(cropped) = crop_image(&img, def.x * scale, def.y * scale, def.w * scale, def.h * scale) {
            if has_visible_pixels(&cropped) {
                if let Ok(encoded) = encode_png(&cropped) {
                    added.push((dest_path, encoded));
                    count += 1;
                }
            }
        }
    }

    for (p, d) in added {
        files.entries.insert(p, d);
    }

    if count > 0 {
        notes.push(format!("Sliced {count} modern HUD sprite(s) from `textures/gui/icons.png`."));
    }
    count
}

// -----------------------------------------------------------------------------------------
// 2. GUI Widgets (textures/gui/widgets.png -> textures/gui/sprites/...)
// -----------------------------------------------------------------------------------------

const WIDGET_SPRITES: &[SpriteDef] = &[
    SpriteDef { rel_path: "hud/hotbar.png", x: 0, y: 0, w: 182, h: 22 },
    SpriteDef { rel_path: "hud/hotbar_selection.png", x: 0, y: 22, w: 24, h: 23 },
    SpriteDef { rel_path: "hud/hotbar_offhand_left.png", x: 24, y: 22, w: 29, h: 24 },
    SpriteDef { rel_path: "hud/hotbar_offhand_right.png", x: 53, y: 22, w: 29, h: 24 },
    SpriteDef { rel_path: "widget/button_disabled.png", x: 0, y: 46, w: 200, h: 20 },
    SpriteDef { rel_path: "widget/button.png", x: 0, y: 66, w: 200, h: 20 },
    SpriteDef { rel_path: "widget/button_highlighted.png", x: 0, y: 86, w: 200, h: 20 },
];

const BUTTON_NINE_SLICE_MCMETA: &str = r#"{"gui":{"scaling":{"type":"nine_slice","width":200,"height":20,"border":{"left":20,"top":4,"right":20,"bottom":4}}}}"#;

fn split_widgets(files: &mut Files, notes: &mut Vec<String>) -> usize {
    let key = "assets/minecraft/textures/gui/widgets.png";
    let Some(bytes) = files.entries.get(key).cloned() else {
        return 0;
    };
    let Ok(img) = decode_png(&bytes) else {
        notes.push("Could not decode textures/gui/widgets.png".to_string());
        return 0;
    };

    let scale = (img.width / 256).max(1);
    let mut count = 0;
    let mut added = Vec::new();

    for def in WIDGET_SPRITES {
        let dest_path = format!("assets/minecraft/textures/gui/sprites/{}", def.rel_path);
        if files.entries.contains_key(&dest_path) {
            continue;
        }

        if let Some(cropped) = crop_image(&img, def.x * scale, def.y * scale, def.w * scale, def.h * scale) {
            if has_visible_pixels(&cropped) {
                if let Ok(encoded) = encode_png(&cropped) {
                    added.push((dest_path.clone(), encoded));
                    count += 1;

                    // Add nine_slice mcmeta for button widgets
                    if def.rel_path.starts_with("widget/button") {
                        let mcmeta_path = format!("{dest_path}.mcmeta");
                        if !files.entries.contains_key(&mcmeta_path) {
                            added.push((mcmeta_path, BUTTON_NINE_SLICE_MCMETA.as_bytes().to_vec()));
                        }
                    }
                }
            }
        }
    }

    for (p, d) in added {
        files.entries.insert(p, d);
    }

    if count > 0 {
        notes.push(format!("Sliced {count} modern widget/HUD sprite(s) from `textures/gui/widgets.png`."));
    }
    count
}

// -----------------------------------------------------------------------------------------
// 3. Particles (textures/particle/particles.png -> textures/particle/*.png)
// -----------------------------------------------------------------------------------------

struct ParticleDef {
    name: &'static str,
    col: u32,
    row: u32,
}

const PARTICLES: &[ParticleDef] = &[
    ParticleDef { name: "generic_0", col: 0, row: 0 },
    ParticleDef { name: "generic_1", col: 1, row: 0 },
    ParticleDef { name: "generic_2", col: 2, row: 0 },
    ParticleDef { name: "generic_3", col: 3, row: 0 },
    ParticleDef { name: "generic_4", col: 4, row: 0 },
    ParticleDef { name: "generic_5", col: 5, row: 0 },
    ParticleDef { name: "generic_6", col: 6, row: 0 },
    ParticleDef { name: "generic_7", col: 7, row: 0 },
    ParticleDef { name: "crit", col: 0, row: 1 },
    ParticleDef { name: "critical_hit", col: 0, row: 1 },
    ParticleDef { name: "enchanted_hit", col: 1, row: 1 },
    ParticleDef { name: "damage", col: 2, row: 1 },
    ParticleDef { name: "flame", col: 3, row: 1 },
    ParticleDef { name: "lava", col: 4, row: 1 },
    ParticleDef { name: "note", col: 5, row: 1 },
    ParticleDef { name: "heart", col: 6, row: 1 },
    ParticleDef { name: "angry", col: 7, row: 1 },
    ParticleDef { name: "bubble", col: 0, row: 2 },
    ParticleDef { name: "splash_0", col: 1, row: 2 },
    ParticleDef { name: "splash_1", col: 2, row: 2 },
    ParticleDef { name: "splash_2", col: 3, row: 2 },
    ParticleDef { name: "splash_3", col: 4, row: 2 },
    ParticleDef { name: "fishing_hook", col: 5, row: 2 },
    ParticleDef { name: "glint", col: 6, row: 2 },
    ParticleDef { name: "drip_hang", col: 0, row: 7 },
    ParticleDef { name: "drip_fall", col: 1, row: 7 },
    ParticleDef { name: "drip_land", col: 2, row: 7 },
    ParticleDef { name: "spell_0", col: 0, row: 9 },
    ParticleDef { name: "spell_1", col: 1, row: 9 },
    ParticleDef { name: "spell_2", col: 2, row: 9 },
    ParticleDef { name: "spell_3", col: 3, row: 9 },
    ParticleDef { name: "spell_4", col: 4, row: 9 },
    ParticleDef { name: "spell_5", col: 5, row: 9 },
    ParticleDef { name: "spell_6", col: 6, row: 9 },
    ParticleDef { name: "spell_7", col: 7, row: 9 },
    ParticleDef { name: "effect_0", col: 0, row: 9 },
    ParticleDef { name: "effect_1", col: 1, row: 9 },
    ParticleDef { name: "effect_2", col: 2, row: 9 },
    ParticleDef { name: "effect_3", col: 3, row: 9 },
    ParticleDef { name: "effect_4", col: 4, row: 9 },
    ParticleDef { name: "effect_5", col: 5, row: 9 },
    ParticleDef { name: "effect_6", col: 6, row: 9 },
    ParticleDef { name: "effect_7", col: 7, row: 9 },
];

fn split_particles(files: &mut Files, notes: &mut Vec<String>) -> usize {
    let key = "assets/minecraft/textures/particle/particles.png";
    let Some(bytes) = files.entries.get(key).cloned() else {
        return 0;
    };
    let Ok(img) = decode_png(&bytes) else {
        notes.push("Could not decode textures/particle/particles.png".to_string());
        return 0;
    };

    let scale = (img.width / 128).max(1);
    let tile_size = 8 * scale;
    let mut count = 0;
    let mut added = Vec::new();

    for def in PARTICLES {
        let dest_path = format!("assets/minecraft/textures/particle/{}.png", def.name);
        if files.entries.contains_key(&dest_path) {
            continue;
        }

        let sx = def.col * tile_size;
        let sy = def.row * tile_size;
        if let Some(cropped) = crop_image(&img, sx, sy, tile_size, tile_size) {
            if has_visible_pixels(&cropped) {
                if let Ok(encoded) = encode_png(&cropped) {
                    added.push((dest_path, encoded));
                    count += 1;
                }
            }
        }
    }

    for (p, d) in added {
        files.entries.insert(p, d);
    }

    if count > 0 {
        notes.push(format!("Sliced {count} modern particle texture(s) from `textures/particle/particles.png`."));
    }
    count
}

// -----------------------------------------------------------------------------------------
// 4. Paintings (paintings_kristoffer_zetterstrand.png / kz.png -> 26 painting textures)
// -----------------------------------------------------------------------------------------

struct PaintingDef {
    name: &'static str,
    u: u32,
    v: u32,
    w: u32,
    h: u32,
}

const PAINTINGS: &[PaintingDef] = &[
    PaintingDef { name: "kebab", u: 0, v: 0, w: 16, h: 16 },
    PaintingDef { name: "aztec", u: 16, v: 0, w: 16, h: 16 },
    PaintingDef { name: "alban", u: 32, v: 0, w: 16, h: 16 },
    PaintingDef { name: "aztec2", u: 48, v: 0, w: 16, h: 16 },
    PaintingDef { name: "bomb", u: 64, v: 0, w: 16, h: 16 },
    PaintingDef { name: "plant", u: 80, v: 0, w: 16, h: 16 },
    PaintingDef { name: "wasteland", u: 96, v: 0, w: 16, h: 16 },
    PaintingDef { name: "back", u: 192, v: 0, w: 16, h: 16 },
    PaintingDef { name: "pool", u: 0, v: 32, w: 32, h: 16 },
    PaintingDef { name: "courbet", u: 32, v: 32, w: 32, h: 16 },
    PaintingDef { name: "sea", u: 64, v: 32, w: 32, h: 16 },
    PaintingDef { name: "sunset", u: 96, v: 32, w: 32, h: 16 },
    PaintingDef { name: "creebet", u: 128, v: 32, w: 32, h: 16 },
    PaintingDef { name: "wanderer", u: 0, v: 64, w: 16, h: 32 },
    PaintingDef { name: "graham", u: 16, v: 64, w: 16, h: 32 },
    PaintingDef { name: "match", u: 0, v: 128, w: 32, h: 32 },
    PaintingDef { name: "bust", u: 32, v: 128, w: 32, h: 32 },
    PaintingDef { name: "stage", u: 64, v: 128, w: 32, h: 32 },
    PaintingDef { name: "void", u: 96, v: 128, w: 32, h: 32 },
    PaintingDef { name: "skull_and_roses", u: 128, v: 128, w: 32, h: 32 },
    PaintingDef { name: "wither", u: 160, v: 128, w: 32, h: 32 },
    PaintingDef { name: "fighters", u: 0, v: 96, w: 64, h: 32 },
    PaintingDef { name: "pointer", u: 0, v: 192, w: 64, h: 64 },
    PaintingDef { name: "pigscene", u: 64, v: 192, w: 64, h: 64 },
    PaintingDef { name: "burning_skull", u: 128, v: 192, w: 64, h: 64 },
    PaintingDef { name: "skeleton", u: 192, v: 64, w: 64, h: 48 },
    PaintingDef { name: "donkey_kong", u: 192, v: 112, w: 64, h: 48 },
];

fn split_paintings(files: &mut Files, notes: &mut Vec<String>) -> usize {
    let key1 = "assets/minecraft/textures/painting/paintings_kristoffer_zetterstrand.png";
    let key2 = "assets/minecraft/textures/painting/kz.png";

    let bytes = files
        .entries
        .get(key1)
        .or_else(|| files.entries.get(key2))
        .cloned();

    let Some(bytes) = bytes else {
        return 0;
    };

    let Ok(img) = decode_png(&bytes) else {
        notes.push("Could not decode legacy paintings sprite sheet".to_string());
        return 0;
    };

    let scale = (img.width / 256).max(1);
    let mut count = 0;
    let mut added = Vec::new();

    for def in PAINTINGS {
        let dest_path = format!("assets/minecraft/textures/painting/{}.png", def.name);
        if files.entries.contains_key(&dest_path) {
            continue;
        }

        let sx = def.u * scale;
        let sy = def.v * scale;
        let sw = def.w * scale;
        let sh = def.h * scale;

        if let Some(cropped) = crop_image(&img, sx, sy, sw, sh) {
            if has_visible_pixels(&cropped) {
                if let Ok(encoded) = encode_png(&cropped) {
                    added.push((dest_path, encoded));
                    count += 1;
                }
            }
        }
    }

    for (p, d) in added {
        files.entries.insert(p, d);
    }

    if count > 0 {
        notes.push(format!("Sliced {count} individual painting texture(s) from legacy painting sheet."));
    }
    count
}

// -----------------------------------------------------------------------------------------
// PNG Helpers (Decode RGBA8, Crop, Encode RGBA8)
// -----------------------------------------------------------------------------------------

pub struct RgbaImage {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>, // RGBA 4 bytes per pixel
}

pub fn decode_png(bytes: &[u8]) -> Result<RgbaImage, String> {
    let mut decoder = png::Decoder::new(Cursor::new(bytes));
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder.read_info().map_err(|e| e.to_string())?;
    let (color_type, _) = reader.output_color_type();
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).map_err(|e| e.to_string())?;
    let bytes = &buf[..info.buffer_size()];

    let mut rgba = Vec::with_capacity((info.width * info.height * 4) as usize);

    match color_type {
        png::ColorType::Rgba => {
            rgba.extend_from_slice(bytes);
        }
        png::ColorType::Rgb => {
            for chunk in bytes.chunks_exact(3) {
                rgba.push(chunk[0]);
                rgba.push(chunk[1]);
                rgba.push(chunk[2]);
                rgba.push(255);
            }
        }
        png::ColorType::Grayscale => {
            for &g in bytes {
                rgba.push(g);
                rgba.push(g);
                rgba.push(g);
                rgba.push(255);
            }
        }
        png::ColorType::GrayscaleAlpha => {
            for chunk in bytes.chunks_exact(2) {
                rgba.push(chunk[0]);
                rgba.push(chunk[0]);
                rgba.push(chunk[0]);
                rgba.push(chunk[1]);
            }
        }
        png::ColorType::Indexed => {
            return Err("Indexed PNG could not be expanded to RGB/RGBA".to_string());
        }
    }

    Ok(RgbaImage {
        width: info.width,
        height: info.height,
        data: rgba,
    })
}

pub fn encode_png(img: &RgbaImage) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(Cursor::new(&mut out), img.width, img.height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().map_err(|e| e.to_string())?;
        writer.write_image_data(&img.data).map_err(|e| e.to_string())?;
    }
    Ok(out)
}

fn crop_image(src: &RgbaImage, x: u32, y: u32, w: u32, h: u32) -> Option<RgbaImage> {
    if x + w > src.width || y + h > src.height || w == 0 || h == 0 {
        return None;
    }
    let mut data = Vec::with_capacity((w * h * 4) as usize);
    for row in 0..h {
        let src_y = y + row;
        let start = ((src_y * src.width + x) * 4) as usize;
        let end = start + (w * 4) as usize;
        data.extend_from_slice(&src.data[start..end]);
    }
    Some(RgbaImage {
        width: w,
        height: h,
        data,
    })
}

fn has_visible_pixels(img: &RgbaImage) -> bool {
    // Check if alpha channel has any non-zero value
    for chunk in img.data.chunks_exact(4) {
        if chunk[3] > 0 {
            return true;
        }
    }
    false
}
