use std::collections::BTreeMap;
use std::io::Cursor;
use horizon_resourcepackport::files::Files;
use horizon_resourcepackport::legacy;
use horizon_resourcepackport::mcmeta;
use horizon_resourcepackport::optifine;
use horizon_resourcepackport::remap;
use horizon_resourcepackport::sheets;
use horizon_resourcepackport::validate;

fn create_test_png(w: u32, h: u32, filled_regions: &[(u32, u32, u32, u32)]) -> Vec<u8> {
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    for &(rx, ry, rw, rh) in filled_regions {
        for y in ry..(ry + rh).min(h) {
            for x in rx..(rx + rw).min(w) {
                let idx = ((y * w + x) * 4) as usize;
                rgba[idx] = 255;     // R
                rgba[idx + 1] = 128; // G
                rgba[idx + 2] = 64;  // B
                rgba[idx + 3] = 255; // A
            }
        }
    }
    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(Cursor::new(&mut out), w, h);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().unwrap();
        writer.write_image_data(&rgba).unwrap();
    }
    out
}

#[test]
fn test_1_8_9_legacy_pack() {
    let mut files = Files {
        name: "OldPack.zip".to_string(),
        entries: BTreeMap::new(),
        layout_notes: Vec::new(),
    };

    // 1. pack.mcmeta with Latin-1 § byte (0xA7)
    let raw_meta = b"{\n  \"pack\": {\n    \"pack_format\": 1,\n    \"description\": \"Legacy \xA73Pack\"\n  }\n}".to_vec();
    files.set_bytes("pack.mcmeta", raw_meta);

    // 2. Legacy textures
    let sample_png = create_test_png(16, 16, &[(0, 0, 16, 16)]);
    files.set_bytes("assets/minecraft/textures/blocks/grass_side.png", sample_png.clone());
    files.set_bytes("assets/minecraft/textures/items/apple_golden.png", sample_png.clone());
    files.set_bytes("assets/minecraft/textures/models/armor/diamond_layer_1.png", sample_png.clone());
    files.set_bytes("assets/minecraft/textures/models/armor/diamond_layer_2.png", sample_png.clone());

    // 3. Legacy .lang (raw 0xA7 byte)
    files.set_bytes(
        "assets/minecraft/lang/en_US.lang",
        b"item.appleGold.name=Golden \xA7eApple\ntile.dirt.name=Dirt\n".to_vec(),
    );

    // 4. Legacy sheets
    let icons_png = create_test_png(256, 256, &[
        (0, 0, 15, 15),  // crosshair
        (16, 0, 9, 9),   // container
        (52, 0, 9, 9),   // full heart
    ]);
    files.set_bytes("assets/minecraft/textures/gui/icons.png", icons_png);

    // 5. Legacy 1.8 grass/tall_grass/double_grass blockstates and models
    files.set_text(
        "assets/minecraft/blockstates/grass.json",
        r#"{"variants":{"snowy=false":{"model":"grass_normal"}}}"#.to_string(),
    );
    files.set_text(
        "assets/minecraft/blockstates/tall_grass.json",
        r#"{"variants":{"normal":{"model":"tall_grass"}}}"#.to_string(),
    );
    files.set_text(
        "assets/minecraft/blockstates/double_grass.json",
        r#"{"variants":{"half=lower":{"model":"double_grass_bottom"}}}"#.to_string(),
    );
    files.set_text(
        "assets/minecraft/models/block/grass.json",
        r#"{"elements":[]}"#.to_string(),
    );
    files.set_text(
        "assets/minecraft/models/block/grass_normal.json",
        r#"{"parent":"block/grass","textures":{"top":"blocks/grass_top","side":"blocks/grass_side"}}"#.to_string(),
    );
    files.set_text(
        "assets/minecraft/models/block/tall_grass.json",
        r#"{"parent":"block/tallgrass","textures":{"cross":"blocks/tallgrass"}}"#.to_string(),
    );

    let widgets_png = create_test_png(256, 256, &[
        (0, 0, 182, 22), // hotbar
        (0, 66, 200, 20), // button
    ]);
    files.set_bytes("assets/minecraft/textures/gui/widgets.png", widgets_png);

    let particles_png = create_test_png(128, 128, &[
        (0, 0, 8, 8),    // generic_0
        (0, 8, 8, 8),    // crit
    ]);
    files.set_bytes("assets/minecraft/textures/particle/particles.png", particles_png);

    let kz_png = create_test_png(256, 256, &[
        (0, 0, 16, 16),  // kebab
    ]);
    files.set_bytes("assets/minecraft/textures/painting/kz.png", kz_png);

    // Process pack
    let raw_meta = files.entries.get("pack.mcmeta").unwrap();
    let meta_res = mcmeta::process_mcmeta(Some(raw_meta), &files.name);
    files.set_text("pack.mcmeta", meta_res.updated_json);

    let _remap_stats = remap::remap_pack(&mut files);
    let _sheet_stats = sheets::split_legacy_sheets(&mut files);
    let _lang_stats = horizon_resourcepackport::lang::convert_legacy_lang(&mut files);

    // Assertions
    assert_eq!(meta_res.original_format, 1);
    let parsed: serde_json::Value = serde_json::from_str(files.text("pack.mcmeta").unwrap().as_str()).unwrap();
    assert_eq!(parsed["pack"]["pack_format"], 15);
    assert_eq!(parsed["pack"]["supported_formats"]["min_inclusive"], 15);
    assert_eq!(parsed["pack"]["supported_formats"]["max_inclusive"], 999);
    assert_eq!(parsed["pack"]["min_format"], serde_json::json!([15, 0]));
    assert_eq!(parsed["pack"]["max_format"], serde_json::json!([999, 0]));

    // Check remapping
    assert!(files.entries.contains_key("assets/minecraft/textures/block/grass_block_side.png"));
    assert!(!files.entries.contains_key("assets/minecraft/textures/blocks/grass_side.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/item/golden_apple.png"));
    assert!(files.entries.contains_key("assets/minecraft/blockstates/grass_block.json"));
    assert!(files.entries.contains_key("assets/minecraft/blockstates/short_grass.json"));
    assert!(files.entries.contains_key("assets/minecraft/blockstates/tall_grass.json"));
    let short_grass_bs = files.text("assets/minecraft/blockstates/short_grass.json").unwrap();
    assert!(short_grass_bs.contains("block/tall_grass"));
    let bs_json: serde_json::Value = serde_json::from_str(&short_grass_bs).unwrap();
    assert!(bs_json["variants"].get("").is_some());
    assert!(bs_json["variants"].get("normal").is_none());
    let grass_normal_model = files.text("assets/minecraft/models/block/grass_normal.json").unwrap();
    assert!(grass_normal_model.contains("block/grass_block"));

    // Check armor copy
    assert!(files.entries.contains_key("assets/minecraft/textures/entity/equipment/humanoid/diamond.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/entity/equipment/humanoid_leggings/diamond.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/models/armor/diamond_layer_1.png"));

    // Check lang
    assert!(files.entries.contains_key("assets/minecraft/lang/en_us.json"));
    let lang_json: serde_json::Value = serde_json::from_slice(files.entries.get("assets/minecraft/lang/en_us.json").unwrap()).unwrap();
    assert_eq!(lang_json["item.appleGold.name"], "Golden §eApple");

    // Check sliced sprites
    assert!(files.entries.contains_key("assets/minecraft/textures/gui/sprites/hud/crosshair.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/gui/sprites/hud/hotbar.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/gui/sprites/widget/button.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/gui/sprites/widget/button.png.mcmeta"));
    assert!(files.entries.contains_key("assets/minecraft/textures/particle/generic_0.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/particle/critical_hit.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/painting/kebab.png"));
}

#[test]
fn test_multi_version_overlays() {
    let raw = r#"{
      "pack": {
        "pack_format": 15,
        "description": "Multi-version overlay pack"
      },
      "overlays": {
        "entries": [
          {
            "directory": "overlay_18",
            "formats": [15, 18]
          },
          {
            "directory": "overlay_modern",
            "formats": [19, 34]
          }
        ]
      }
    }"#;

    let res = mcmeta::process_mcmeta(Some(raw.as_bytes()), "TestPack");
    let parsed: serde_json::Value = serde_json::from_str(&res.updated_json).unwrap();
    let entries = parsed["overlays"]["entries"].as_array().unwrap();

    // Overlay 1 should be untouched
    assert_eq!(entries[0]["formats"], serde_json::json!([15, 18]));

    // ONLY newest overlay 2 should be extended to 999
    assert_eq!(entries[1]["formats"], serde_json::json!([19, 999]));
}

#[test]
fn test_optifine_ctm_sky_emissive_and_colormap() {
    let mut files = Files {
        name: "OptiPack.zip".to_string(),
        entries: BTreeMap::new(),
        layout_notes: Vec::new(),
    };

    let sample_png = create_test_png(16, 16, &[(0, 0, 16, 16)]);

    // CTM properties
    files.set_text(
        "assets/minecraft/optifine/ctm/glass/glass.properties",
        "matchBlocks=tallgrass\nmatchTiles=blocks/glass\nmethod=ctm\n".to_string(),
    );

    // Colormap in mcpatcher
    files.set_bytes("assets/minecraft/mcpatcher/colormap/grass.png", sample_png.clone());

    // Custom sky
    files.set_bytes("assets/minecraft/optifine/sky/world0/cloud.png", sample_png.clone());

    // Emissive texture and properties
    files.set_bytes("assets/minecraft/textures/block/furnace_e.png", sample_png.clone());
    files.set_text("assets/minecraft/optifine/emissive.properties", "suffix.emissive=_e\n".to_string());

    // Non-text core shader
    files.set_text("assets/minecraft/shaders/core/rendertype_solid.vsh", "// shader".to_string());
    files.set_text("assets/minecraft/shaders/core/text.vsh", "// text shader".to_string());

    let stats = optifine::process_optifine(&mut files);

    // Colormap should be copied to vanilla colormap path
    assert!(files.entries.contains_key("assets/minecraft/textures/colormap/grass.png"));
    assert_eq!(stats.colormaps_copied, 1);

    // CTM properties updated
    let ctm = files.text("assets/minecraft/optifine/ctm/glass/glass.properties").unwrap();
    assert!(ctm.contains("short_grass"));
    assert!(ctm.contains("block/glass"));

    // Sky, emissive, and shaders must NOT be dropped
    assert!(files.entries.contains_key("assets/minecraft/optifine/sky/world0/cloud.png"));
    assert!(files.entries.contains_key("assets/minecraft/textures/block/furnace_e.png"));
    assert!(files.entries.contains_key("assets/minecraft/shaders/core/rendertype_solid.vsh"));

    // Warnings recorded
    assert!(stats.warnings.iter().any(|w| w.contains("custom skyboxes")));
    assert!(stats.warnings.iter().any(|w| w.contains("emissive suffix")));
    assert!(stats.warnings.iter().any(|w| w.contains("non-text core shader")));
}

#[test]
fn test_malformed_hostile_pack() {
    let mut files = Files {
        name: "HostilePack.zip".to_string(),
        entries: BTreeMap::new(),
        layout_notes: Vec::new(),
    };

    // Trailing comma in JSON
    let bad_json = b"{\n  \"parent\": \"block/cube_all\",\n}";
    files.set_bytes("assets/minecraft/models/block/dirt.json", bad_json.to_vec());

    // Corrupt PNG
    files.set_bytes("assets/minecraft/textures/block/corrupt.png", b"not a valid png file".to_vec());

    let v_stats = validate::validate_and_repair(&mut files);
    assert_eq!(v_stats.json_repaired, 1);
    let repaired_text = files.text("assets/minecraft/models/block/dirt.json").unwrap();
    assert!(serde_json::from_str::<serde_json::Value>(&repaired_text).is_ok());

    assert!(v_stats.warnings.iter().any(|w| w.contains("Corrupt PNG")));
    // Corrupt PNG must still be preserved in files
    assert!(files.entries.contains_key("assets/minecraft/textures/block/corrupt.png"));
}

#[test]
fn test_determinism() {
    let mut files = Files {
        name: "DetPack.zip".to_string(),
        entries: BTreeMap::new(),
        layout_notes: Vec::new(),
    };

    files.set_text("pack.mcmeta", "{\"pack\":{\"pack_format\":1,\"description\":\"test\"}}".to_string());
    files.set_bytes("assets/minecraft/textures/block/stone.png", vec![1, 2, 3, 4]);
    files.set_bytes("assets/minecraft/textures/block/dirt.png", vec![5, 6, 7, 8]);

    let bytes1 = files.zip_bytes().unwrap();
    let bytes2 = files.zip_bytes().unwrap();

    assert_eq!(bytes1, bytes2);
}

fn files_with(entries: &[(&str, Vec<u8>)]) -> Files {
    Files {
        name: "Test.zip".to_string(),
        entries: entries.iter().map(|(k, v)| (k.to_string(), v.clone())).collect(),
        layout_notes: Vec::new(),
    }
}

#[test]
fn overlays_follow_the_packs_newest_version() {
    // Fresh Animations' layout: every overlay covering format 75 is on in 1.21.11, so all must stay on.
    let meta = br#"{"pack":{"pack_format":15,"supported_formats":{"min_inclusive":15,"max_inclusive":75},"description":"x"},
        "overlays":{"entries":[
            {"directory":"old","formats":{"min_inclusive":15,"max_inclusive":21}},
            {"directory":"20-3","formats":{"min_inclusive":22,"max_inclusive":75}},
            {"directory":"21-2","formats":[42,75]},
            {"directory":"21-11","formats":{"min_inclusive":75,"max_inclusive":75},"min_format":75,"max_format":75}]}}"#;
    let res = mcmeta::process_mcmeta(Some(meta), "Test");
    let v: serde_json::Value = serde_json::from_str(&res.updated_json).unwrap();
    let e = v["overlays"]["entries"].as_array().unwrap();
    assert_eq!(e[0]["formats"]["max_inclusive"], 21, "an overlay only for older versions stays off");
    assert_eq!(e[1]["formats"]["max_inclusive"], 999);
    assert_eq!(e[2]["formats"][1], 999);
    assert_eq!(e[3]["formats"]["max_inclusive"], 999);
    assert_eq!(e[3]["max_format"], serde_json::json!([999, 0]));
}

#[test]
fn old_blockstates_that_cannot_match_are_dropped() {
    let mut files = files_with(&[
        ("assets/minecraft/blockstates/stone_slab.json", br#"{"variants":{"half=bottom":{"model":"block/a"},"half=top":{"model":"block/b"}}}"#.to_vec()),
        ("assets/minecraft/blockstates/oak_fence.json", br#"{"variants":{"east=false,north=false,south=false,west=false":{"model":"block/c"}}}"#.to_vec()),
        ("assets/minecraft/blockstates/cobweb.json", br#"{"variants":{"":{"model":"block/cobweb"}}}"#.to_vec()),
        ("assets/minecraft/blockstates/glass.json", br#"{"variants":{"normal":{"model":"block/glass"}}}"#.to_vec()),
        ("assets/minecraft/models/block/a.json", b"{}".to_vec()),
    ]);
    let notes = legacy::fix_legacy(&mut files, 1);
    assert!(!files.entries.contains_key("assets/minecraft/blockstates/stone_slab.json"));
    assert!(!files.entries.contains_key("assets/minecraft/blockstates/oak_fence.json"));
    assert!(files.entries.contains_key("assets/minecraft/blockstates/cobweb.json"));
    assert!(files.entries.contains_key("assets/minecraft/blockstates/glass.json"));
    assert!(files.entries.contains_key("assets/minecraft/models/block/a.json"), "models stay");
    assert!(notes.iter().any(|n| n.contains("Dropped 2")));

    // Packs from 1.13 on keep theirs.
    let mut modern = files_with(&[("assets/minecraft/blockstates/stone_slab.json", br#"{"variants":{"type=bottom":{"model":"block/a"}}}"#.to_vec())]);
    legacy::fix_legacy(&mut modern, 15);
    assert!(modern.entries.contains_key("assets/minecraft/blockstates/stone_slab.json"));
}

#[test]
fn the_1_8_inventory_gets_the_modern_layout() {
    // Background everywhere (grey), slot frames at the 1.8 crafting grid corner and the 4th armor slot.
    let w = 256u32;
    let mut rgba = vec![0u8; (w * w * 4) as usize];
    let mut set = |x: u32, y: u32, c: [u8; 4]| { let i = ((y * w + x) * 4) as usize; rgba[i..i + 4].copy_from_slice(&c); };
    for y in 0..166 { for x in 0..176 { set(x, y, [79, 79, 79, 255]); } }
    for y in 25..43 { for x in 87..105 { set(x, y, [22, 22, 22, 255]); } }   // 1.8 grid's first slot
    for y in 61..79 { for x in 7..25 { set(x, y, [33, 33, 33, 255]); } }     // armor slot
    for y in 198..216 { for x in 0..18 { set(x, y, [200, 10, 10, 255]); } } // speed icon
    let mut png_bytes = Vec::new();
    {
        let mut enc = png::Encoder::new(Cursor::new(&mut png_bytes), w, w);
        enc.set_color(png::ColorType::Rgba);
        enc.set_depth(png::BitDepth::Eight);
        enc.write_header().unwrap().write_image_data(&rgba).unwrap();
    }
    let key = "assets/minecraft/textures/gui/container/inventory.png";
    let mut files = files_with(&[
        (key, png_bytes),
        ("assets/minecraft/textures/item/empty_armor_slot_helmet.png", b"helmet".to_vec()),
    ]);
    legacy::fix_legacy(&mut files, 1);
    let img = sheets::decode_png(&files.entries[key]).unwrap();
    let px = |x: u32, y: u32| { let i = ((y * img.width + x) * 4) as usize; img.data[i..i + 4].to_vec() };
    assert_eq!(px(97, 17), vec![22, 22, 22, 255], "grid moved 10 right, 8 up");
    assert_eq!(px(87, 25 + 18 + 2), vec![79, 79, 79, 255], "old place cleared");
    assert_eq!(px(76, 61), vec![33, 33, 33, 255], "off-hand slot added");
    assert!(files.entries.contains_key("assets/minecraft/textures/mob_effect/speed.png"));
    assert_eq!(files.entries["assets/minecraft/textures/gui/sprites/container/slot/helmet.png"], b"helmet".to_vec());
}
