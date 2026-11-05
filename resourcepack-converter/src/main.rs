//! horizon-resourcepackport: ports Minecraft Java resource packs (1.8 through 1.21+) to Horizon (format 97.1).
//!
//! Commands:
//!   check <pack.zip|folder>                      inspect format and compatibility
//!   port <pack.zip|folder> [--out DIR] [--install]  port pack, slice sprites, write `<name>-horizon.zip` + report
//!   port-all <folder> [--out DIR]                port every pack in a folder, summary table

use horizon_resourcepackport::{files, lang, mcmeta, optifine, remap, report, sheets, validate};

use std::path::{Path, PathBuf};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let flag = |name: &str| args.iter().any(|a| a == name);
    let value = |name: &str| {
        args.iter()
            .position(|a| a == name)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    let positional: Vec<&String> = {
        let mut skip_next = false;
        args.iter()
            .filter(|a| {
                if skip_next {
                    skip_next = false;
                    return false;
                }
                if *a == "--out" {
                    skip_next = true;
                    return false;
                }
                !a.starts_with("--")
            })
            .collect()
    };
    let out = value("--out").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("out"));
    let code = match positional.as_slice() {
        [cmd, pack] if cmd.as_str() == "check" => check(Path::new(pack.as_str())),
        [cmd, pack] if cmd.as_str() == "port" => {
            port(Path::new(pack.as_str()), &out, flag("--install")).map(|_| ())
        }
        [cmd, dir] if cmd.as_str() == "port-all" => port_all(Path::new(dir.as_str()), &out),
        _ => {
            eprintln!(
                "usage:\n  horizon-resourcepackport check <pack.zip|folder>\n  horizon-resourcepackport port <pack.zip|folder> [--out DIR] [--install]\n  horizon-resourcepackport port-all <folder> [--out DIR]"
            );
            std::process::exit(2);
        }
    };
    if let Err(e) = code {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

pub struct PortResult {
    pub name: String,
    pub original_format: i64,
    pub before_count: usize,
    pub after_count: usize,
    pub actions_count: usize,
}

pub fn check(path: &Path) -> Result<(), String> {
    let files = files::Files::read(path)?;
    let raw_meta = files.entries.get("pack.mcmeta").map(|v| v.as_slice());
    let mcmeta = mcmeta::process_mcmeta(raw_meta, &files.name);

    println!("Pack: {}", files.name);
    println!("  Original format: {}", mcmeta.original_format);
    println!("  Total files: {}", files.entries.len());
    if !files.layout_notes.is_empty() {
        for n in &files.layout_notes {
            println!("  Note: {n}");
        }
    }
    let has_legacy_blocks = files.entries.keys().any(|k| k.contains("/textures/blocks/"));
    let has_legacy_items = files.entries.keys().any(|k| k.contains("/textures/items/"));
    let has_icons = files.entries.contains_key("assets/minecraft/textures/gui/icons.png");
    let has_widgets = files.entries.contains_key("assets/minecraft/textures/gui/widgets.png");
    let has_particles = files.entries.contains_key("assets/minecraft/textures/particle/particles.png");
    let has_paintings = files.entries.contains_key("assets/minecraft/textures/painting/paintings_kristoffer_zetterstrand.png")
        || files.entries.contains_key("assets/minecraft/textures/painting/kz.png");
    let has_lang = files.entries.keys().any(|k| k.ends_with(".lang"));

    println!("  Legacy elements detected:");
    println!("    textures/blocks/ folder: {has_legacy_blocks}");
    println!("    textures/items/ folder:  {has_legacy_items}");
    println!("    textures/gui/icons.png:  {has_icons}");
    println!("    textures/gui/widgets.png:{has_widgets}");
    println!("    particle/particles.png:  {has_particles}");
    println!("    legacy painting sheet:   {has_paintings}");
    println!("    legacy .lang files:      {has_lang}");

    Ok(())
}

pub fn port(path: &Path, out: &Path, install: bool) -> Result<PortResult, String> {
    let mut files = files::Files::read(path)?;
    let before_count = files.entries.len();

    // 1. Process and upgrade pack.mcmeta
    let raw_meta = files.entries.get("pack.mcmeta").map(|v| v.as_slice());
    let mcmeta_res = mcmeta::process_mcmeta(raw_meta, &files.name);
    files.set_text("pack.mcmeta", mcmeta_res.updated_json.clone());

    println!(
        "{}: before: format {}, {before_count} file(s)",
        files.name, mcmeta_res.original_format
    );

    // 2. Data-driven remapping of paths, texture names, and models
    let remap_stats = remap::remap_pack(&mut files);

    // 3. Split legacy texture sheets into modern standalone sprites
    let mut sheet_stats = sheets::split_legacy_sheets(&mut files);

    // 3b. Pre-1.13 fixes renaming can't do (old blockstates, the 1.8 inventory, moved GUI textures)
    let legacy_notes = horizon_resourcepackport::legacy::fix_legacy(&mut files, mcmeta_res.original_format);
    sheet_stats.notes.extend(legacy_notes);

    // 4. Convert legacy .lang files to .json
    let lang_stats = lang::convert_legacy_lang(&mut files);

    // 5. Handle OptiFine features, colormaps, and properties
    let optifine_stats = optifine::process_optifine(&mut files);

    // 6. Validate and repair JSON and check PNGs
    let validate_stats = validate::validate_and_repair(&mut files);

    let after_count = files.entries.len();
    let clamped_format = mcmeta_res.original_format.clamp(15, 64);
    println!(
        "{}: after:  format {clamped_format} (compat 97.1), {after_count} file(s)",
        files.name
    );

    // Write output zip and report
    std::fs::create_dir_all(out).map_err(|e| format!("{}: {e}", out.display()))?;
    let stem = files.name.trim_end_matches(".zip").to_owned();
    let zip_path = out.join(format!("{stem}-horizon.zip"));
    files.write_zip(&zip_path)?;

    let report_content = report::generate_report(
        &files.name,
        before_count,
        &files,
        &mcmeta_res,
        &remap_stats,
        &sheet_stats,
        &lang_stats,
        &optifine_stats,
        &validate_stats,
    );
    let report_path = out.join(format!("{stem}-horizon.md"));
    std::fs::write(&report_path, report_content)
        .map_err(|e| format!("{}: {e}", report_path.display()))?;

    println!("  wrote {}", zip_path.display());
    println!("  wrote {}", report_path.display());

    if install {
        let dir = horizon_resourcepacks_dir()?;
        std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        let to = dir.join(zip_path.file_name().unwrap());
        std::fs::copy(&zip_path, &to).map_err(|e| format!("{}: {e}", to.display()))?;
        println!(
            "  installed {} (pick it on Options > Resource Packs)",
            to.display()
        );
    }

    let actions_count = remap_stats.textures_renamed
        + remap_stats.dir_moves
        + remap_stats.armor_copied
        + sheet_stats.icons_sliced
        + sheet_stats.widgets_sliced
        + sheet_stats.particles_sliced
        + sheet_stats.paintings_sliced
        + lang_stats.converted
        + validate_stats.json_repaired;

    Ok(PortResult {
        name: files.name,
        original_format: mcmeta_res.original_format,
        before_count,
        after_count,
        actions_count,
    })
}

fn port_all(dir: &Path, out: &Path) -> Result<(), String> {
    let mut entries: Vec<PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir() || p.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip")))
        .collect();
    entries.sort();

    let mut table = String::from(
        "| Pack | Original Format | Files Before | Files After | Conversions Performed |\n|---|---|---|---|---|\n",
    );

    for p in entries {
        let name = p.file_name().unwrap().to_string_lossy().into_owned();
        match port(&p, out, false) {
            Ok(res) => {
                table.push_str(&format!(
                    "| {name} | {} | {} | {} | {} |\n",
                    res.original_format, res.before_count, res.after_count, res.actions_count
                ));
            }
            Err(e) => {
                table.push_str(&format!("| {name} | error: {e} | | | |\n"));
            }
        }
    }

    let summary = out.join("SUMMARY.md");
    std::fs::write(&summary, &table).map_err(|e| format!("{}: {e}", summary.display()))?;
    print!("\n{table}");
    Ok(())
}

/// `<Horizon's home>/resourcepacks` (beside Horizon's `options.txt`).
fn horizon_resourcepacks_dir() -> Result<PathBuf, String> {
    let nonempty = |k: &str| std::env::var_os(k).filter(|v| !v.is_empty()).map(PathBuf::from);
    if let Some(home) = nonempty("HORIZON_HOME") {
        return Ok(home.join("resourcepacks"));
    }
    let base = if cfg!(windows) {
        nonempty("APPDATA")
    } else if cfg!(target_os = "macos") {
        nonempty("HOME").map(|h| h.join("Library/Application Support"))
    } else {
        nonempty("XDG_DATA_HOME")
            .filter(|p| p.is_absolute())
            .or_else(|| nonempty("HOME").map(|h| h.join(".local/share")))
    }
    .ok_or("no APPDATA or HOME")?;
    Ok(base.join("horizon").join("resourcepacks"))
}
