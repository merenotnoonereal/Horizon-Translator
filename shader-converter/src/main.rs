//! horizon-shaderport: makes Iris/OptiFine shader packs run in Horizon.
//!
//! Horizon reads the `shaders/` pack format itself (crate `horizon-shaders`), but a program it
//! cannot translate is skipped, and a pack missing its programs draws wrong or not at all. This
//! tool runs that same loader offline (no GPU), lists what it would skip and why, rewrites the
//! GLSL it trips on (rules in `fix.rs`, each kept only when the program then loads), and writes
//! the result as a pack for Horizon's `shaderpacks` folder.
//!
//! Commands:
//!   check <pack>                      what Horizon loads and skips, per dimension
//!   port <pack> [--out DIR] [--install]  check, fix, write `<name>-horizon.zip` (+ report)
//!   port-all <folder> [--out DIR]     port every pack in a folder, one summary table

mod bufsize;
mod cloudsoff;
mod skyblend;
mod tex2d;
mod compute;
mod files;
mod fragdepth;
mod fix;
mod glsl;
mod gpu;
mod gpu_fix;
mod load;
mod normals;
mod noise2d;
mod pbr;
mod postkeep;
mod tex3d;
mod uniforms;

use std::path::{Path, PathBuf};

fn main() {
    // A pack that panics Horizon's loader is reported (the game turns such a pack off); the
    // panic message itself is noise unless tracing.
    if std::env::var_os("HZ_TRACE").is_none() {
        std::panic::set_hook(Box::new(|_| {}));
    }
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
        // Debugging: one rule, unchecked, written as a zip.
        [cmd, pack, rule] if cmd.as_str() == "apply" => (|| {
            let mut files = files::Files::read(Path::new(pack.as_str()))?;
            let eval = load::evaluate(&files)?;
            let n = fix::apply_one(&mut files, &eval, rule)?;
            std::fs::create_dir_all(&out).map_err(|e| e.to_string())?;
            let to = out.join(format!("{}-{rule}.zip", files.name.trim_end_matches(".zip")));
            files.write_zip(&to)?;
            println!("{rule}: {n} file(s) changed, wrote {}", to.display());
            Ok(())
        })(),
        _ => {
            eprintln!(
                "usage:\n  horizon-shaderport check <pack.zip|folder>\n  horizon-shaderport port <pack.zip|folder> [--out DIR] [--install]\n  horizon-shaderport port-all <folder> [--out DIR]"
            );
            std::process::exit(2);
        }
    };
    if let Err(e) = code {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

fn check(path: &Path) -> Result<(), String> {
    let files = files::Files::read(path)?;
    let eval = load::evaluate(&files)?;
    print!("{}", eval.describe(&files.name));
    Ok(())
}

/// Ports one pack; returns (before, after) for the summary.
fn port(path: &Path, out: &Path, install: bool) -> Result<(load::Eval, load::Eval, usize), String> {
    let mut files = files::Files::read(path)?;
    let before = load::evaluate(&files)?;
    println!("{}: before: {}", files.name, before.summary());
    let mut log = fix::fix(&mut files, &before);
    compute::compute_off(&mut files, &mut log);
    // Fixes for how Horizon draws (not what it loads): run on every pack.
    normals::fix(&mut files, &mut log);
    uniforms::fix(&mut files, &mut log);
    uniforms::matrix_order(&mut files, &mut log);
    pbr::fix(&mut files, &mut log);
    postkeep::fix(&mut files, &mut log);
    fragdepth::fix(&mut files, &mut log);
    tex3d::fix(&mut files, &mut log);
    noise2d::fix(&mut files, &mut log);
    tex2d::fix(&mut files, &mut log);
    bufsize::fix(&mut files, &mut log);
    skyblend::fix(&mut files, &mut log);
    cloudsoff::fix(&mut files, &mut log);
    let after = load::evaluate(&files)?;
    println!("{}: after:  {}", files.name, after.summary());
    std::fs::create_dir_all(out).map_err(|e| format!("{}: {e}", out.display()))?;
    let stem = files.name.trim_end_matches(".zip").to_owned();
    let zip = out.join(format!("{stem}-horizon.zip"));
    files.write_zip(&zip)?;
    let report = out.join(format!("{stem}-horizon.md"));
    std::fs::write(&report, fix::report(&files.name, &before, &after, &log))
        .map_err(|e| format!("{}: {e}", report.display()))?;
    println!("  wrote {}\n  wrote {}", zip.display(), report.display());
    if install {
        let dir = horizon_shaderpacks_dir()?;
        std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        let to = dir.join(zip.file_name().unwrap());
        std::fs::copy(&zip, &to).map_err(|e| format!("{}: {e}", to.display()))?;
        println!("  installed {} (pick it on Options > Horizon Settings > Shader Packs)", to.display());
    }
    let kept = log.iter().filter(|c| !c.files.is_empty()).count();
    Ok((before, after, kept))
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
        "| Pack | Before | After | Programs still skipped | Rules used |\n|---|---|---|---|---|\n",
    );
    for p in entries {
        // A pack that panics the loader is reported, not fatal (the game contains it the same way).
        let name = p.file_name().unwrap().to_string_lossy().into_owned();
        let result = std::panic::catch_unwind(|| port(&p, out, false));
        match result {
            Ok(Ok((before, after, kept))) => table.push_str(&format!(
                "| {name} | {} load, {} skipped | {} load, {} skipped | {} | {} |\n",
                before.loaded_count(),
                before.skipped_count(),
                after.loaded_count(),
                after.skipped_count(),
                after.skipped_names().len(),
                kept
            )),
            Ok(Err(e)) => table.push_str(&format!("| {name} | error: {e} | | | |\n")),
            Err(_) => table.push_str(&format!("| {name} | loader panicked | | | |\n")),
        }
    }
    let summary = out.join("SUMMARY.md");
    std::fs::write(&summary, &table).map_err(|e| format!("{}: {e}", summary.display()))?;
    print!("\n{table}");
    Ok(())
}

/// `<Horizon's home>/shaderpacks` (beside Horizon's `options.txt`). The home is `HORIZON_HOME`, else
/// `dirs::data_dir()/horizon` as the launcher has it: `%APPDATA%` on Windows, `~/Library/Application Support` on
/// macOS, `$XDG_DATA_HOME` or `~/.local/share` on Linux.
fn horizon_shaderpacks_dir() -> Result<PathBuf, String> {
    let nonempty = |k: &str| std::env::var_os(k).filter(|v| !v.is_empty()).map(PathBuf::from);
    if let Some(home) = nonempty("HORIZON_HOME") {
        return Ok(home.join("shaderpacks"));
    }
    let base = if cfg!(windows) {
        nonempty("APPDATA")
    } else if cfg!(target_os = "macos") {
        nonempty("HOME").map(|h| h.join("Library/Application Support"))
    } else {
        nonempty("XDG_DATA_HOME").filter(|p| p.is_absolute()).or_else(|| nonempty("HOME").map(|h| h.join(".local/share")))
    }
    .ok_or("no APPDATA or HOME")?;
    Ok(base.join("horizon").join("shaderpacks"))
}
