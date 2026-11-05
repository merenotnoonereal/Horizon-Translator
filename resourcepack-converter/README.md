# horizon-resourcepackport

Ports Minecraft Java Edition resource packs (from Minecraft 1.8 through 1.21+) to [Horizon](https://github.com/horizon-rust/horizon) (targeting Minecraft 26.3 / resource pack format `97.1`).

Horizon discovers and loads resource packs from `%APPDATA%\horizon\resourcepacks` (Windows), `~/Library/Application Support/horizon/resourcepacks` (macOS), or `$XDG_DATA_HOME/horizon/resourcepacks` (Linux). This tool makes packs from any Minecraft version work cleanly without warning prompts, missing textures, or broken GUI elements.

---

## What It Does

Following Horizon's policy of **"keep everything, never drop unknown files, fix what broke or moved across versions"**, `horizon-resourcepackport` performs:

1. **Archive Normalization & Deterministic Zips (`src/files.rs`)**:
   - Safely reads zip entries by index, sanitizing paths (`\` to `/`, stripping leading slashes, dropping invalid segments, directory traversal `..`, and null bytes).
   - Unwraps packs nested inside a single root folder (e.g. `MyPack/pack.mcmeta` -> `pack.mcmeta`).
   - Produces byte-identical, deterministic `.zip` outputs across CLI, Desktop, and WASM (sorted `BTreeMap` entries, fixed DOS timestamp 1980-01-01 00:00:00).
   - Never re-encodes PNGs that are already valid.

2. **`pack.mcmeta` Repair & Compatibility Upgrade (`src/mcmeta.rs`)**:
   - Strips UTF-8 BOM, decodes raw Latin-1 / Windows-1252 section signs (`0xA7` `§`), and repairs lenient JSON (comments, trailing commas, unescaped control chars). Synthesizes valid metadata if missing.
   - Preserves original `description`, `language`, and `filter` sections.
   - Sets Horizon format compatibility:
     - `"pack_format": orig.clamp(1, 64)`
     - `"supported_formats": { "min_inclusive": orig.clamp(1, 64), "max_inclusive": 999 }`
     - `"min_format": [orig.clamp(1, 64), 0]`
     - `"max_format": [999, 0]`
     so Horizon `(97, 1)` marks the pack `Compat::Compatible` without a confirmation prompt.
   - **Overlays (`overlays.entries`):** Finds **only the newest overlay** whose declared format covers the pack's highest targeted version, and extends **only that overlay's** upper bound (`max_format` / `formats`) to `999`. Older overlays remain untouched.

3. **Data-Driven 1.8–1.21+ Path & Texture Remapping (`src/remap.rs` + `src/mappings.txt`)**:
   - Moves `assets/<ns>/textures/blocks/` -> `assets/<ns>/textures/block/` and `assets/<ns>/textures/items/` -> `assets/<ns>/textures/item/` (both `.png` and `.png.mcmeta`).
   - Renames flattened 1.8–1.12 block and item textures and 1.13–1.21.4+ renames (`grass_side` -> `grass_block_side`, `tallgrass`/`grass` -> `short_grass`, `redstone_torch_on` -> `redstone_torch`, `quartz_pillar` -> `quartz_pillar_side`, `purpur_pillar` -> `purpur_pillar_side`, potion, dye, wool, glass, terracotta, wood, and tool names).
   - Copies legacy armor textures (`textures/models/armor/<mat>_layer_1.png` and `_layer_2.png`) to 1.21.2+ equipment paths (`textures/entity/equipment/humanoid/<mat>.png` and `textures/entity/equipment/humanoid_leggings/<mat>.png`) while preserving the originals.
   - Updates model JSON and blockstate files referencing `blocks/` or `items/` and renamed textures.

4. **Splitting Legacy Texture Sheets into Modern Sprites (`src/sheets.rs`)**:
   - **`textures/gui/icons.png`** (base 256×256, scaled): Slices into individual HUD sprites under `assets/minecraft/textures/gui/sprites/hud/` (crosshair, full/half/blinking hearts for normal, hardcore, poison, wither, absorption, armor, food, hunger, air bubbles, experience and jump bars, vehicle hearts).
   - **`textures/gui/widgets.png`** (base 256×256, scaled): Slices into `hud/hotbar.png`, `hud/hotbar_selection.png`, `hud/hotbar_offhand_left/right.png`, and button widgets with 1.20.2+ nine-slice `.png.mcmeta` metadata.
   - **`textures/particle/particles.png`** (base 128×128, scaled): Slices into individual particle textures under `assets/minecraft/textures/particle/` (`generic_0`..`generic_7`, `splash_0`..`splash_3`, `crit`, `damage`, `flame`, `lava`, `note`, `heart`, `angry`, `glint`, `drip_*`, `spell_*`).
   - **`textures/painting/paintings_kristoffer_zetterstrand.png` / `kz.png`** (base 256×256, scaled): Slices into the 26 individual painting textures and `back.png` under `assets/minecraft/textures/painting/`.
   - Supports 8-bit RGBA, RGB, Grayscale, and paletted/indexed PNG formats. Only slices when target files don't already exist in the pack.

5. **Legacy Language Conversion (`src/lang.rs`)**:
   - Converts `assets/<ns>/lang/*.lang` key-value files into modern lowercase `assets/<ns>/lang/<code>.json` files with Latin-1/UTF-8 decoding. Merges with existing `.json` files if present. Keeps original `.lang` files.

6. **OptiFine Features & Unsupported Feature Warnings (`src/optifine.rs`)**:
   - Copies custom grass/foliage colormaps from `optifine/colormap/` or `mcpatcher/colormap/` to standard `textures/colormap/` if missing.
   - Updates legacy texture and block references inside OptiFine/MCPatcher `.properties` files (CTM, CIT).
   - Preserves all files and emits clear warnings for features Horizon does not yet render (`optifine/sky/`, `*_e.png` emissives, `optifine/lightmap/`, `optifine/anim/`, `optifine/gui/`, non-text `shaders/core/`).

7. **Asset Validation and Repair (`src/validate.rs`)**:
   - Cleans and repairs JSON syntax errors (BOM, comments, trailing commas) across all JSON and `.mcmeta` files.
   - Verifies PNG magic signatures and chunk headers, emitting warnings for corrupt textures without failing the conversion.

---

## Build and Run

### Native Build

```bash
cargo build --release
```

### WebAssembly Build (for Desktop & Web Apps)

```bash
cargo build --release --target wasm32-wasip1
```

### CLI Commands

```bash
# Check pack format and detect legacy elements
./target/release/horizon-resourcepackport check "path/to/Pack.zip"

# Port pack, produce <stem>-horizon.zip and markdown report <stem>-horizon.md
./target/release/horizon-resourcepackport port "path/to/Pack.zip" --out out

# Port and install directly into Horizon's resourcepacks folder
./target/release/horizon-resourcepackport port "path/to/Pack.zip" --out out --install

# Port all packs in a folder and write a summary table (SUMMARY.md)
./target/release/horizon-resourcepackport port-all "path/to/folder-of-packs" --out out
```
