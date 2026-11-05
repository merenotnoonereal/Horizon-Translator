# Resource Packs in Horizon

This document answers what Horizon (`c9d474d`, targeting Minecraft Java 26.3 / resource pack format `97.1`) already loads from Java Edition resource packs, what it ignores or rejects, and what `horizon-resourcepackport` (`resourcepack-converter/`) does to make packs from Minecraft 1.8 through 1.21+ work cleanly in Horizon.

---

## 1. How Horizon Loads Resource Packs

Horizon discovers resource packs (both `.zip` archives and directories) in `%APPDATA%\horizon\resourcepacks` (Windows), `~/Library/Application Support/horizon/resourcepacks` (macOS), or `$XDG_DATA_HOME/horizon/resourcepacks` (Linux) via `crates/horizon-client-render/src/packs.rs` and `crates/horizon-client/src/game/packs.rs`.

When resource packs are enabled, Horizon builds a unified overlay directory over the vanilla 26.3 assets:
1. It reads `pack.mcmeta` from the root of each pack to check the pack format, apply matching `overlays`, and apply `filter` rules.
2. Files from higher-priority packs overwrite files from lower-priority packs.
3. Resource-stack files (`assets/<namespace>/lang/*.json`, `sounds.json`, `font/*.json`, `atlases/*.json`) are merged layer-by-layer from bottom to top, matching vanilla behavior.

---

## 2. Questions Answered from Horizon's Source Code

### 2.1 Which `pack_format` values does Horizon accept, and what happens with older or newer ones?
- **Target format:** Horizon's target resource pack format is `(97, 1)` (`RESOURCE_PACK_FORMAT = (97, 1)` in `crates/horizon-core/src/version.rs:13`, `RESOURCE_FORMAT` in `crates/horizon-client-render/src/packs.rs:18`).
- **Pre-minor format cutoff:** Format `64` is the boundary between single-integer formats (`pack_format <= 64`) and major.minor tuple formats (`min_format` / `max_format > 64`).
- **Older or newer formats:** Horizon does **not** hard-block older or newer packs. If a pack's format range is below `(97, 1)`, `compatibility_for()` (`packs.rs:365-376`) returns `Compat::TooOld` (or `Compat::TooNew` if above). In the Resource Packs screen (`pack_screen.rs:620-656`), clicking an incompatible pack opens a confirmation modal (*"Are you sure you want to load this pack?"*).
- **Validation strictness:**
  - If `pack_format > 64` is used **without** `min_format` and `max_format`, `validate_old_format()` (`packs.rs:321-325`) fails metadata validation and marks compatibility as `Compat::Unknown`.
  - If `min_format` has major `<= 64` and `max_format` has major `> 64`, Horizon requires **both** `pack_format` + `supported_formats` (for older clients) **and** `min_format` + `max_format` (`packs.rs:289-315`), as seen in `%APPDATA%\horizon\resourcepacks\Xray-Ultimate-horizon.zip`:
    ```json
    {
      "pack": {
        "pack_format": 16,
        "description": "X-ray pack v5.4.4, Minecraft 26.0+ Only",
        "supported_formats": { "min_inclusive": 16, "max_inclusive": 999 },
        "min_format": [16, 0],
        "max_format": [999, 0]
      }
    }
    ```

### 2.2 Does it read `supported_formats`, `min_format`/`max_format`, and `overlays`?
- **Yes to all three** (`packs.rs:172-425`):
  - `min_format` and `max_format` accept either an integer `N` (interpreted as `(N, 0)`) or `[major, minor]`.
  - `supported_formats` accepts an integer, `[min, max]`, or `{"min_inclusive": min, "max_inclusive": max}`.
  - `overlays.entries` are evaluated in order (`packs.rs:381-425`). Any overlay entry whose `formats` or `min_format`/`max_format` covers `(97, 1)` (and whose directory is a valid single folder name) is layered on top of the pack's base `assets/` (`packs.rs:527-566`).
  - Note: If a 1.20–1.21 pack has overlays for multiple versions (e.g., an overlay for 1.13–1.19 and a newest overlay for 1.20.2+ or 1.21+ whose `max_format` is `< 97`), Horizon won't activate the newest overlay unless **only that newest overlay** has its upper bound extended to cover `97.1`.
  - `filter.block` (`packs.rs:426-436`) is also supported: regex patterns on `namespace` and `path` hide matching files from lower packs.

### 2.3 Which folders under `assets/` does Horizon read?
Verified directly against `crates/horizon-client-render/src/` and `crates/horizon-client/src/`, Horizon reads all namespaces (`assets/minecraft/` as well as mod namespaces like `assets/<modid>/`):

| Folder / File | Status in Horizon | Where in Horizon Source |
|---|---|---|
| `assets/<ns>/blockstates/*.json` | **Supported** (variants, multipart, weights, x/y rotation, uvlock) | `horizon-client-render/src/models/catalog.rs` |
| `assets/<ns>/models/block/*.json`, `models/item/*.json` | **Supported** (parents, elements, faces, cullface, tintindex, rotations) | `horizon-client-render/src/model_library.rs` |
| `assets/<ns>/items/*.json` | **Supported** (1.21.4+ / 26.3 client item definitions) | `horizon-client-render/src/models/item_def.rs` |
| `assets/<ns>/atlases/*.json` | **Supported** (`directory`, `single`, `filter`, `unstitch`, `paletted_permutations`) | `horizon-client-render/src/atlas_sources.rs` |
| `assets/<ns>/textures/block/*.png` | **Supported** (1.13+ directory & flattened names; 16x–512x+) | `horizon-client-render/src/sprites.rs` |
| `assets/<ns>/textures/item/*.png` | **Supported** (1.13+ directory & flattened names) | `horizon-client-render/src/sprites.rs` |
| `assets/<ns>/textures/gui/sprites/**/*.png` | **Supported** (1.20.2+ split HUD and widget sprites, including nine-slice `width`, `height`, `border`, `stretch_inner`) | `horizon-client-render/src/ui.rs:24-180`, `hud_layout.rs` |
| `assets/<ns>/textures/gui/container/*.png`, `title/background/*` | **Supported** | `horizon-client-render/src/hud.rs`, `gfx/panorama.rs` |
| `assets/<ns>/textures/entity/**/*.png` | **Supported** (including villager hats `*.png.mcmeta` via `VillagerHats::load`) | `horizon-client-render/src/hud.rs`, `mobs/villager_hats.rs` |
| `assets/<ns>/textures/environment/*.png` | **Supported** (`sun.png`, `moon_phases.png`, `clouds.png`, weather) | `horizon-client-render/src/hud.rs` |
| `assets/<ns>/textures/painting/*.png` | **Supported** (individual painting sprites) | `horizon-client-render/src/hud.rs` |
| `assets/<ns>/textures/particle/*.png` + `particles/*.json` | **Supported** (individual particle textures) | `horizon-client-render/src/particles.rs` |
| `assets/<ns>/textures/mob_effect/*.png`, `map/*.png`, `misc/*.png` | **Supported** (including `blur`/`clamp` in `TextureFlags::from_mcmeta`) | `horizon-client-render/src/hud.rs:42-58`, `screen_effects.rs` |
| `assets/<ns>/textures/colormap/*.png` | **Supported** (`grass.png`, `foliage.png`, `dry_foliage.png`) | `horizon-client-render/src/biome.rs` |
| `assets/<ns>/textures/trims/**/*.png`, `equipment/**/*.json` | **Supported** (both item trims and worn armor trims via `armor_stand_assets.reload_trims`) | `horizon-client/src/game/armor_stands.rs:139`, `atlas_sources.rs` |
| `assets/<ns>/font/*.json`, `textures/font/*.png` | **Supported** (`bitmap`, `space`, `unihex`, `reference`, **and `ttf` TrueType** via `Provider::TrueType`, plus custom text component fonts via `glyph_styled`) | `horizon-client-render/src/font.rs:640-710` |
| `assets/<ns>/lang/*.json` | **Supported** (JSON format only; legacy `.lang` is ignored) | `horizon-client-core/src/lang.rs:155` |
| `assets/<ns>/sounds.json`, `sounds/**/*.ogg` | **Supported** | `horizon-client-render/src/sound_events.rs` |
| `assets/<ns>/texts/splashes.txt`, `credits.json`, `end.txt`, `postcredits.txt` | **Supported** (`title.rs` and `win_screen.rs:59-96`) | `horizon-client-render/src/title.rs`, `horizon-client/src/win_screen.rs` |
| `assets/<ns>/waypoint_style/*.json` | **Supported** (`WaypointStyles::load`) | `horizon-client/src/game/packs.rs:338` |
| `assets/<ns>/post_effect/*.json`, `shaders/post/*` | **Supported** (`PostChain` translates `post_effect` JSON and GLSL post passes via `post_glsl`) | `horizon-client-render/src/post_chain.rs:1-35` |

### 2.4 Does it support `.mcmeta` animations, `emissive`/`_e` textures, and OptiFine folders?
- **`.png.mcmeta` animations:** **Yes, including `interpolate: true`** (`crates/horizon-assets/src/animation.rs:42-83`, `crates/horizon-client-render/src/gfx/animate.rs:1-20`, `sprites.rs:31-40`). Frame indices, `frametime`, custom `{index, time}` entries, `width`/`height`, and frame interpolation (`animate_sprite_interpolate` shader) all work.
- **`.png.mcmeta` GUI nine-slice:** **Yes** (`crates/horizon-client-render/src/ui.rs:24-180`). Reads `scaling.type == "nine_slice"`, `width`, `height`, `border`, and `stretch_inner`. (`tile` scaling type is not yet implemented and falls back to stretch).
- **Emissive / `_e` textures:** **No** for OptiFine `*_e.png` suffixes and `optifine/emissive.properties` (verified via grep across `crates/horizon-optifine` and `crates/horizon-client-render`). Horizon only has an internal native-mod hook (`crates/horizon-client-render/src/native_emissive.rs`) for up to 32 texture pairs where the overlay is in `textures/emissive/` and a native mod registers the `(base, overlay)` pair via `Game::native_emissive`.
- **OptiFine folders (`assets/minecraft/optifine/` and `mcpatcher/`):**
  - **CTM (`optifine/ctm/`, `mcpatcher/ctm/`):** **Supported** (`crates/horizon-optifine/src/ctm.rs`, `crates/horizon-client-render/src/connected.rs`). Supports 18 CTM methods (`ctm`, `ctm_compact`, `horizontal`, `vertical`, `repeat`, `random`, `fixed`, `overlay`, etc.).
  - **CIT (`optifine/cit/`, `mcpatcher/cit/`, `citresewn/cit/`):** **Supported** (`crates/horizon-optifine/src/cit.rs`, `crates/horizon-client-render/src/cit.rs`).
  - **CEM (`optifine/cem/*.jem`, `emf/cem/*.jem`):** **Supported** (`crates/horizon-optifine/src/cem/`, `crates/horizon-client/src/mobs/cem.rs`).
  - **Random Entities (`optifine/random/`, `etf/random/`, `optifine/mob/`, `mcpatcher/mob/`):** **Supported** (`crates/horizon-optifine/src/random_textures.rs`).
  - **Custom Sky (`optifine/sky/`, `mcpatcher/sky/`):** **Not supported** (no `sky` module in `horizon-optifine/src`).
  - **OptiFine Colormap / Lightmap / Anim / GUI (`optifine/colormap/`, `lightmap/`, `anim/`, `gui/`):** **Not supported** by `horizon-optifine` (though vanilla `textures/colormap/grass.png` and `foliage.png` work).

### 2.5 What about core shaders and post shaders (`assets/minecraft/shaders/`)?
- **Core shaders (`assets/minecraft/shaders/core/`):** Horizon loads and translates `shaders/core/text.vsh` and `shaders/core/text.fsh` (plus `#moj_import` / `#include` files from `shaders/include/`) via Naga (`crates/horizon-shaders/src/core_shader.rs` and `crates/horizon-client-render/src/hud/pack_text.rs:32-33`). Other world/terrain core shaders (`rendertype_solid`, `rendertype_cutout`, `position_tex`, etc.) are not executed because Horizon renders world geometry with its own WGSL pipelines (`terrain.wgsl`, `entity.wgsl`).
- **Post shaders (`assets/minecraft/post_effect/*.json` and `shaders/post/`):** Horizon's `post_chain.rs` reads `post_effect/*.json` and translates referenced GLSL post passes via `post_glsl` (falling back to built-in WGSL stand-ins if a pass fails to translate). Legacy pre-1.21.2 `shaders/post/*.json` chains can be copied/adapted or kept with a note if they use the old format.

### 2.6 Does a pack with one broken file fail completely or partly?
- **Partly (graceful per-file fallback)** for almost everything:
  - Unreadable zip entries are skipped with a log warning (`packs.rs:757-760`).
  - Malformed `sounds.json`, `lang/*.json`, `font/*.json`, or `atlases/*.json` in one pack layer are skipped while keeping other layers (`packs.rs:849-852`).
  - Broken blockstate JSON or model JSON logs a warning and falls back to the missing model (`models/catalog.rs:164-220`).
  - Corrupt PNGs in block/item textures fall back to the 16x16 magenta/black missing texture (`sprites.rs:309-326`), or are skipped in the HUD (`hud.rs:617`).
  - Corrupt `.jem` or `.properties` rules in CTM/CIT/CEM are skipped individually.
- **What fails a pack completely:**
  1. Missing or invalid JSON in `pack.mcmeta` at the zip root (`packs.rs:446-461`).
  2. A pack zipped inside a nested root subfolder (e.g. `MyPack/pack.mcmeta` instead of `pack.mcmeta`).
  3. Zip entries with a leading `/` (`stays_inside` in `packs.rs:570-575` rejects empty path segments, silently dropping every file with a leading slash).
  4. Non-UTF-8 `pack.mcmeta` (e.g. Windows-1252 / Latin-1 `§` section signs in old 1.8.9 packs, where byte `0xA7` is invalid UTF-8).

### 2.7 What was changed in `%APPDATA%\horizon\resourcepacks\Xray-Ultimate-horizon.zip`?
1. **`pack.mcmeta` updated** to include `"supported_formats": {"min_inclusive": 16, "max_inclusive": 999}`, `"min_format": [16, 0]`, `"max_format": [999, 0]` alongside `"pack_format": 16`, so Horizon's format validator treats it as natively compatible with `(97, 1)` without a confirmation prompt.
2. **Block model texture references updated** for 1.21.2+ / 26.3 texture splits (`models/block/xray/purpur_pillar.json` and `quartz_pillar.json` updated from `block/purpur_pillar` to `block/purpur_pillar_side`).

---

## 3. What `resourcepack-converter` Does

Based on the verified source above, `horizon-resourcepackport` (`resourcepack-converter/`) follows a strict **"keep everything, never drop unknown files, fix what breaks or moved across versions"** policy:

1. **Archive & Path Normalization (Never Crash)**:
   - Unwrap single nested root folders (where `Subfolder/pack.mcmeta` or `Subfolder/assets/` exists instead of root `pack.mcmeta`).
   - Normalize path separators (`\` → `/`), strip leading slashes (`/`), reject `..` or drive letters, and report warnings instead of failing.
   - Produce deterministic, byte-identical `.zip` output across CLI, Desktop App, and Browser WASM (sorted `BTreeMap` entries, fixed DOS timestamp).

2. **`pack.mcmeta` Repair & Format Upgrade**:
   - Decode non-UTF-8 `pack.mcmeta` files (converting Latin-1 / Windows-1252 `0xA7` `§` codes to valid UTF-8 `§`) and repair common JSON syntax issues (BOM, trailing commas, comments, unescaped control chars in description strings).
   - Preserve the original `description` (and `language` / `filter` sections).
   - Set multi-version compatibility fields (`pack_format` clamped to `15..=64` since `validate_pack_format_for_range` in `packs.rs:215` requires `main_format >= 15` for multi-version packs, `supported_formats: {"min_inclusive": min, "max_inclusive": 999}`, `min_format: [min, 0]`, `max_format: [999, 0]`) so Horizon `(97, 1)` accepts the pack as natively compatible (`Compat::Compatible`) without warnings or red highlight.
   - **Overlays (`overlays.entries`):** Find only the **newest** overlay entry (the one with the highest upper bound covering the pack's latest Minecraft version) and extend only that entry's upper bound (`formats` / `max_format`) to cover `97.1` (`999`), without turning on older overlays.

3. **Legacy 1.8–1.12 Flattening & Modern Texture Renames (Data-Driven)**:
   - Move `assets/<ns>/textures/blocks/` → `assets/<ns>/textures/block/` and `assets/<ns>/textures/items/` → `assets/<ns>/textures/item/` (including `.png.mcmeta`).
   - Rename pre-1.13 block and item texture filenames and 1.14–1.21.4+ renames (such as `grass_side` → `grass_block_side`, `tallgrass` / `grass` → `short_grass`, `redstone_torch_on` → `redstone_torch`, `purpur_pillar` → `purpur_pillar_side`, `quartz_pillar` → `quartz_pillar_side`, potion/dye/bed/wool/glass/terracotta/log/plank/door/boat renames) using a mapping table in `src/mappings.txt`.
   - Update texture references inside `models/**/*.json`, `blockstates/*.json`, and OptiFine `.properties` files (`blocks/` → `block/`, `items/` → `item/`, and renamed texture stems).
   - Copy legacy pre-1.21.2 armor textures (`textures/models/armor/<mat>_layer_1.png` and `_layer_2.png`) to 1.21.2+ equipment paths (`textures/entity/equipment/humanoid/<mat>.png` and `textures/entity/equipment/humanoid_leggings/<mat>.png`) while keeping the originals, so 1.8.9 PvP armor textures render on players in Horizon.

4. **Splitting Legacy Texture Sheets into Modern Sprites**:
   - **`textures/gui/icons.png`** (256×256 scaled): Slice into 1.20.2+ HUD sprites under `assets/minecraft/textures/gui/sprites/hud/` (crosshair, hearts, armor, food, air bubbles, experience bar, hotbar selection) when the pack doesn't already provide those sprites.
   - **`textures/gui/widgets.png`** (256×256 scaled): Slice into 1.20.2+ HUD hotbar & button sprites under `assets/minecraft/textures/gui/sprites/` (`hud/hotbar.png`, `hud/hotbar_selection.png`, `hud/hotbar_offhand_left.png`, `hud/hotbar_offhand_right.png`, `widget/button.png`, `widget/button_highlighted.png`, `widget/button_disabled.png`) with `.png.mcmeta` nine-slice metadata for buttons.
   - **`textures/particle/particles.png`** (128×128 scaled): Slice into individual particle PNGs under `assets/minecraft/textures/particle/` (`flame.png`, `lava.png`, `smoke_*.png`, `crit.png`, `damage.png`, `enchanted_hit.png`, `heart.png`, `angry.png`, `glint.png`, `note.png`, `bubble.png`, `fishing_hook.png`, `drip_*.png`, `spell_*.png`, `splash_*.png`, etc.).
   - **`textures/painting/paintings_kristoffer_zetterstrand.png` / `kz.png`** (256×256 scaled): Slice into the 26 individual painting textures under `assets/minecraft/textures/painting/` (`kebab.png`, `aztec.png`, `alban.png`, `wanderer.png`, `skeleton.png`, `skull_and_roses.png`, `fighters.png`, `pointer.png`, `pigscene.png`, `burning_skull.png`, `back.png`, etc.).

5. **Legacy Language File Conversion (`.lang` → `.json`)**:
   - Convert 1.8–1.12 `assets/<ns>/lang/*.lang` (`key=value` lines, supporting UTF-8 and Latin-1 `§` codes) into lowercase `assets/<ns>/lang/<code>.json` (merging with any existing `.json` lang file).

6. **JSON Repair & Validation**:
   - Clean up non-standard JSON in `blockstates/*.json`, `models/**/*.json`, `sounds.json`, `font/*.json`, `lang/*.json`, and `*.png.mcmeta` (UTF-8 BOM, Latin-1 `§` bytes, trailing commas, `//` or `/* */` comments) so `serde_json` in Horizon parses them cleanly.
   - Validate PNG headers on textures; report a warning (without crashing) if a PNG is corrupt.

7. **OptiFine & Shader Handling (Keep + Warn on Unsupported Features)**:
   - **Keep & fix supported OptiFine features:** Preserve `optifine/ctm/`, `optifine/cit/`, `optifine/cem/`, and `optifine/random/` (plus `mcpatcher/`, `citresewn/`, `emf/`, `etf/`), updating any legacy 1.12 block/item texture references inside `.properties` files.
   - **Copy OptiFine `colormap/custom/grass.png` or `mcpatcher/colormap/grass.png` / `foliage.png`** to vanilla `textures/colormap/` if vanilla colormap files are missing.
   - **Keep all files and emit clear warnings for features Horizon does not render yet:**
     - `optifine/sky/` & `mcpatcher/sky/` (custom skyboxes — kept in zip, warned in report that Horizon ignores OptiFine custom sky).
     - `*_e.png` / `emissive.properties` (OptiFine emissive suffix textures — kept in zip, warned in report that Horizon ignores OptiFine `_e` emissive suffixes).
     - `optifine/lightmap/`, `optifine/anim/`, `optifine/gui/` (kept in zip, warned in report that Horizon ignores these OptiFine folders).
     - `shaders/core/` other than `text.vsh`/`text.fsh`/`text.json` (kept in zip, warned in report that Horizon only runs `shaders/core/text` and uses its own WGSL pipelines for world/terrain).
