//! OptiFine / MCPatcher feature handling: colormap promotion, CTM/CIT property updating,
//! and clear warnings for features Horizon ignores (skyboxes, emissive textures, lightmaps, etc.).

use crate::files::Files;

pub struct OptiFineStats {
    pub colormaps_copied: usize,
    pub properties_updated: usize,
    pub notes: Vec<String>,
    pub warnings: Vec<String>,
}

pub fn process_optifine(files: &mut Files) -> OptiFineStats {
    let mut notes = Vec::new();
    let mut warnings = Vec::new();
    let mut colormaps_copied = 0;
    let mut properties_updated = 0;

    // 1. Check for custom grass/foliage colormaps in optifine/ or mcpatcher/
    let grass_dest = "assets/minecraft/textures/colormap/grass.png";
    if !files.entries.contains_key(grass_dest) {
        let grass_srcs = [
            "assets/minecraft/optifine/colormap/custom/grass.png",
            "assets/minecraft/optifine/colormap/grass.png",
            "assets/minecraft/mcpatcher/colormap/grass.png",
            "assets/minecraft/mcpatcher/colormap/blocks/grass.png",
        ];
        for src in grass_srcs {
            if let Some(bytes) = files.entries.get(src).cloned() {
                files.entries.insert(grass_dest.to_string(), bytes);
                colormaps_copied += 1;
                notes.push(format!("Copied custom colormap `{src}` to standard `{grass_dest}`."));
                break;
            }
        }
    }

    let foliage_dest = "assets/minecraft/textures/colormap/foliage.png";
    if !files.entries.contains_key(foliage_dest) {
        let foliage_srcs = [
            "assets/minecraft/optifine/colormap/custom/foliage.png",
            "assets/minecraft/optifine/colormap/foliage.png",
            "assets/minecraft/mcpatcher/colormap/foliage.png",
            "assets/minecraft/mcpatcher/colormap/blocks/foliage.png",
        ];
        for src in foliage_srcs {
            if let Some(bytes) = files.entries.get(src).cloned() {
                files.entries.insert(foliage_dest.to_string(), bytes);
                colormaps_copied += 1;
                notes.push(format!("Copied custom colormap `{src}` to standard `{foliage_dest}`."));
                break;
            }
        }
    }

    // 2. Update legacy references in .properties files (CTM, CIT, etc.)
    let mut updated_props = Vec::new();
    for (path, bytes) in &files.entries {
        if path.ends_with(".properties") && (path.contains("optifine/") || path.contains("mcpatcher/")) {
            let text = String::from_utf8_lossy(bytes).into_owned();
            let mut rewritten = text.clone();

            // Update blocks/ and items/ paths
            rewritten = rewritten.replace("textures/blocks/", "textures/block/");
            rewritten = rewritten.replace("textures/items/", "textures/item/");
            rewritten = rewritten.replace("blocks/", "block/");
            rewritten = rewritten.replace("items/", "item/");

            // Common block name updates in matchBlocks
            rewritten = rewritten.replace("tallgrass", "short_grass");
            rewritten = rewritten.replace("reeds", "sugar_cane");
            rewritten = rewritten.replace("noteblock", "note_block");

            // Repair single-tile `tiles=<name>` references when `<name>_ore` was written instead of `<name>_overlay`
            let dir = path.rsplit_once('/').map(|(d, _)| d).unwrap_or("");
            let mut fixed_lines = Vec::new();
            for line in rewritten.lines() {
                if let Some(tile_val) = line.trim().strip_prefix("tiles=") {
                    let tile = tile_val.trim();
                    if !tile.is_empty() && !tile.contains(' ') && !tile.contains('-') && !tile.contains('/') {
                        let direct_png = format!("{dir}/{tile}.png");
                        if !files.entries.contains_key(&direct_png) {
                            if let Some(base) = tile.strip_suffix("_ore") {
                                let alt_tile = format!("{base}_overlay");
                                let alt_png = format!("{dir}/{alt_tile}.png");
                                if files.entries.contains_key(&alt_png) {
                                    fixed_lines.push(format!("tiles={alt_tile}"));
                                    continue;
                                }
                            }
                        }
                    }
                }
                fixed_lines.push(line.to_string());
            }
            let joined = if rewritten.contains("\r\n") {
                let mut s = fixed_lines.join("\r\n");
                if rewritten.ends_with("\r\n") {
                    s.push_str("\r\n");
                }
                s
            } else {
                let mut s = fixed_lines.join("\n");
                if rewritten.ends_with('\n') {
                    s.push('\n');
                }
                s
            };
            rewritten = joined;

            if rewritten != text {
                properties_updated += 1;
                updated_props.push((path.clone(), rewritten.into_bytes()));
            }
        }
    }
    for (p, d) in updated_props {
        files.entries.insert(p, d);
    }
    if properties_updated > 0 {
        notes.push(format!("Updated {properties_updated} OptiFine/MCPatcher `.properties` file(s) with modern names."));
    }

    // 3. Scan for features Horizon ignores and record informative warnings
    let has_sky = files.entries.keys().any(|k| k.contains("optifine/sky/") || k.contains("mcpatcher/sky/"));
    if has_sky {
        warnings.push(
            "Pack contains OptiFine/MCPatcher custom skyboxes (`optifine/sky/` or `mcpatcher/sky/`). Files were preserved, but Horizon does not currently render custom skyboxes."
                .to_string(),
        );
    }

    let has_emissive = files.entries.keys().any(|k| {
        (k.ends_with("_e.png") && !k.contains("textures/entity/equipment/"))
            || k.ends_with("emissive.properties")
    });
    if has_emissive {
        warnings.push(
            "Pack contains OptiFine `*_e.png` emissive suffix textures or `emissive.properties`. Files were preserved, but Horizon uses native mod hooks for emissive overlays rather than OptiFine suffix scanning."
                .to_string(),
        );
    }

    let has_lightmap = files.entries.keys().any(|k| k.contains("optifine/lightmap/") || k.contains("mcpatcher/lightmap/"));
    if has_lightmap {
        warnings.push(
            "Pack contains OptiFine custom lightmaps (`optifine/lightmap/`). Files were preserved, but Horizon uses its own HDR lighting model."
                .to_string(),
        );
    }

    let has_anim = files.entries.keys().any(|k| k.contains("optifine/anim/") || k.contains("mcpatcher/anim/"));
    if has_anim {
        warnings.push(
            "Pack contains OptiFine `anim/` texture animations. Files were preserved, but Horizon uses vanilla `.png.mcmeta` frame animation with interpolation."
                .to_string(),
        );
    }

    let has_optifine_gui = files.entries.keys().any(|k| k.contains("optifine/gui/container/"));
    if has_optifine_gui {
        warnings.push(
            "Pack contains OptiFine custom container GUI textures (`optifine/gui/`). Files were preserved, but Horizon renders vanilla container GUIs."
                .to_string(),
        );
    }

    let non_text_shaders: Vec<&String> = files
        .entries
        .keys()
        .filter(|k| k.starts_with("assets/minecraft/shaders/core/") && !k.contains("text."))
        .collect();
    if !non_text_shaders.is_empty() {
        warnings.push(format!(
            "Pack contains {} non-text core shader file(s) in `shaders/core/`. Files were preserved, but Horizon only executes `shaders/core/text` and renders terrain and entities via its own high-performance WGSL pipelines.",
            non_text_shaders.len()
        ));
    }

    OptiFineStats {
        colormaps_copied,
        properties_updated,
        notes,
        warnings,
    }
}
