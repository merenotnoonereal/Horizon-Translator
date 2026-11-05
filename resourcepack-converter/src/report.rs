//! Markdown conversion report generation (<stem>-horizon.md).

use crate::files::Files;
use crate::lang::LangStats;
use crate::mcmeta::McmetaResult;
use crate::optifine::OptiFineStats;
use crate::remap::RemapStats;
use crate::sheets::SheetStats;
use crate::validate::ValidationStats;

pub fn generate_report(
    pack_name: &str,
    initial_file_count: usize,
    files: &Files,
    mcmeta: &McmetaResult,
    remap: &RemapStats,
    sheets: &SheetStats,
    lang: &LangStats,
    optifine: &OptiFineStats,
    validate: &ValidationStats,
) -> String {
    let mut out = String::new();

    out.push_str(&format!("# Resource Pack Port Report: {pack_name}\n\n"));
    out.push_str("Converted by **horizon-resourcepackport** for **Horizon (Minecraft Java 26.3 / Format 97.1)**.\n\n");

    out.push_str("## 1. Summary\n\n");
    out.push_str(&format!("- **Original format:** {}\n", mcmeta.original_format));
    out.push_str("- **Target compatibility:** Format 15..64 + supported_formats [15..64, 999] + min/max_format [15..64, 0]..[999, 0] (`Compat::Compatible` in Horizon without warning prompt)\n");
    out.push_str(&format!("- **Files before:** {initial_file_count}\n"));
    out.push_str(&format!("- **Files after:** {}\n", files.entries.len()));

    let total_sliced = sheets.icons_sliced + sheets.widgets_sliced + sheets.particles_sliced + sheets.paintings_sliced;
    out.push_str(&format!("- **Legacy sheets sliced:** {total_sliced} modern sprites generated\n"));
    out.push_str(&format!("- **Texture paths remapped:** {} moved, {} renamed\n", remap.dir_moves, remap.textures_renamed));
    out.push_str(&format!("- **Legacy armor textures copied:** {} equipment textures\n", remap.armor_copied));
    out.push_str(&format!("- **Language files converted:** {} .lang -> .json\n", lang.converted));
    out.push_str(&format!("- **JSON files repaired:** {}\n", validate.json_repaired));
    out.push_str(&format!("- **OptiFine colormaps copied:** {}\n", optifine.colormaps_copied));

    out.push_str("\n---\n\n## 2. Conversions & Adjustments\n\n");

    if !files.layout_notes.is_empty() {
        out.push_str("### Archive Normalization\n");
        for n in &files.layout_notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    if !mcmeta.notes.is_empty() {
        out.push_str("### Metadata (`pack.mcmeta`)\n");
        for n in &mcmeta.notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    if !remap.notes.is_empty() {
        out.push_str("### Path & Texture Remapping\n");
        for n in &remap.notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    if !sheets.notes.is_empty() {
        out.push_str("### Sprite Sheet Slicing\n");
        for n in &sheets.notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    if !lang.notes.is_empty() {
        out.push_str("### Language Conversions\n");
        for n in &lang.notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    if !optifine.notes.is_empty() {
        out.push_str("### OptiFine / MCPatcher Updates\n");
        for n in &optifine.notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    if !validate.notes.is_empty() {
        out.push_str("### JSON & Asset Validation\n");
        for n in &validate.notes {
            out.push_str(&format!("- {n}\n"));
        }
        out.push('\n');
    }

    let mut all_warnings = Vec::new();
    all_warnings.extend_from_slice(&optifine.warnings);
    all_warnings.extend_from_slice(&validate.warnings);

    if !all_warnings.is_empty() {
        out.push_str("## 3. Features Kept with Notes & Warnings\n\n");
        out.push_str("> Horizon follows a strict policy of keeping all pack files intact while warning when a feature is not yet rendered by the engine.\n\n");
        for w in &all_warnings {
            out.push_str(&format!("- ⚠️ {w}\n"));
        }
        out.push('\n');
    }

    out
}
