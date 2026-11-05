# AGENTS.md

Notes for AI coding agents (Claude Code, Codex, Cursor...) working in this repo. People can read it too: it is the
short version of [web/public/making-mods.md](web/public/making-mods.md) and [docs/MAINTAINING.md](docs/MAINTAINING.md).

## What this repo is

| Folder | What it is |
|---|---|
| `mod-converter/` | `horizon-port.jar`: the mod kit (makes Horizon native mods), the Fabric mod converter, the checks |
| `mods/` | Mods made with the kit and shared from the website and the app's **Get mods** tab |
| `desktop/` | The Windows/Linux app (Swing) that wraps the jar |
| `web/public/` | The website (GitHub Pages): shader converter in the browser, guide, mod list |
| `shader-converter/` | Rust shader pack converter (also built to WebAssembly for the site) |
| `scripts/` | Build scripts and `mods-index.py` |

Horizon is a Rust rewrite of Minecraft Java. Its native mods are
[horizon-mixins](https://github.com/horizon-rust/horizon-mixins) bundles: Rust files pasted into Horizon's source,
which the Horizon launcher compiles (its Mods page, **Build**). A packed mod is an `<id>.hnmod` file.

## Running the tool

```bash
cd mod-converter && bash build.sh          # builds build/horizon-port.jar (JDK 21+)
java -jar mod-converter/build/horizon-port.jar --help
```

With the app installed on Windows, its own Java runs the same tool, nothing else needed:

```bash
"$LOCALAPPDATA/Programs/Horizon Translator/runtime/bin/java.exe" -jar "$LOCALAPPDATA/Programs/Horizon Translator/app/horizon-port.jar" --help
```

Below, `horizon-port` means either command. `SRC` is Horizon's source folder, unpacked (the app's `horizon-src.zip`
works for `new`, `pack` and `hooks`, not for `compile`). Get one with `scripts/get-horizon.sh` or from a Horizon
GitHub archive; it should have a `.horizon-revision` file holding its commit, as the launcher's copies do.

## Making a mod

1. **Start from a spec.** Copy one of `mod-converter/kit-examples/*.json` (HUD lines, commands, keys, zoom, timed
   messages) and run:
   ```bash
   horizon-port new spec.json --out my-mod --horizon-src SRC
   ```
   The folder gets `mixins/<id>/` (the code), `Cargo.toml`, `README.md`, `HOOKS.md` (every hook point and helper of
   that Horizon) and `AGENTS.md`. Never run `new` again on a folder you edited: it starts over from the spec.
2. **Find the hooks you need** in `HOOKS.md`, or list them for any Horizon:
   ```bash
   horizon-port hooks --horizon-src SRC --out HOOKS.md     # or --json for a machine-readable list
   ```
   Each hook point has the `[[injection]]` to paste into `horizon-mixin.toml` and the helper function's signature.
3. **Write the code.** Logic goes in `mixins/<id>/runtime.rs` (it is both the game module `crate::game::kit_<id>` and
   the project's library, so `cargo test` tests it without the game). Each hook file holds one function.
4. **Check it for real and pack it:**
   ```bash
   horizon-port compile my-mod --horizon-src SRC                       # last line: RESULT: ok / failed / not checked
   horizon-port pack my-mod --horizon-src SRC --compile --install      # compile + cargo test + pack + add to the launcher
   ```
5. The player presses **Build** on the launcher's Mods page. **Don't run the launcher's build or
   `--trust-native-code` yourself**: native mods run unsandboxed, so building one is the player's decision.

### The checks, and which to trust

| Command | What it does | Misses |
|---|---|---|
| `cargo test` (in the mod folder) | The mod's own tests on `runtime.rs` | Everything about the game |
| `check` / `pack` | Compiles the hook files against stand-ins for Horizon's helpers; runs horizon-mixins `plan` | Code that uses the game's own types: it **fails** on them even when they're right. Use `pack --no-check` or `--compile` for such mods |
| `compile` / `pack --compile` | horizon-mixins applies the mod to a copy of Horizon and `cargo check` builds it, as the launcher does | Nothing at compile time; it doesn't run the game |

`compile` needs cargo, the source unpacked and horizon-mixins (the launcher installs it on its first native build;
or set `HORIZON_MIXINS`). Its work folder (`--work`, default `HORIZON_PORT_WORK` or
`%LOCALAPPDATA%/horizon-port/compile`) keeps cargo's build: the first run takes **a few GB and several minutes**,
later runs a minute or two. Errors name the mod file they came from (`mixins/<id>/health_hud.rs, fn ...`) and
quote the staged line, since horizon-mixins reformats the code it pastes in. Exit codes: 0 ok, 1 failed, 2 not
checked.

### Rules horizon-mixins and the kit set

- In `runtime.rs`: no `//!` comments, no `#![...]` attributes, no `mod x;` files, no `include!`. Inline
  `mod x { ... }` is fine.
- One function per hook file, taking the `bindings` in order. Hooks that answer something take an out parameter
  (`claimed`, `draws`, `size`...): check it first, another mod may have answered already.
- Check `game.native_on(m::ID)` first in every hook, so players can turn the mod off in Options > Mods.
- Keep the mod id the same for every version of a mod; bump `version` in `horizon-mixin.toml` (and `Cargo.toml`).
- **Hook points only** (`Game<'_>::native_*` targets): the launcher builds the mod on any Horizon with the hooks
  API it needs (`pack` works it out from `HOOKS.md`'s API numbers). Reaching past them (`game.player`,
  `game.items()`, a target elsewhere in Horizon) works, since the runtime module sits inside Horizon's `game`
  module, but can break on another commit: check it with `compile` on the commit you name with
  `--horizon-revision`.

### A worked example

`mods/appleskin/` (AppleSkin for Horizon) uses the hunger bar (`native_health_hud`) and tooltip
(`native_tooltip_component`, `native_tooltip_component_draw`) hook points and reads the game's own food data. Packed
with:

```bash
horizon-port pack mods/appleskin --horizon-src SRC --compile --horizon-revision c9d474dd9116e84c243f8992d75c11314df56f06
```

## Sharing a mod in this repo

Put the project in `mods/<id>/` with its `<id>.hnmod` and a `mod.json` (name, author, description, needs), then run
`python scripts/mods-index.py` (it copies the file to `web/public/mods/` and writes `index.json` with its SHA-256).
Only add mods whose author agreed to share them here. See docs/MAINTAINING.md, "Sharing a mod".

## Working on this repo

- Tests: `bash mod-converter/test/kit-test.sh` (the kit; set `HORIZON_SRC`), `bash mod-converter/test/run-test.sh`
  (the converter).
- Build the app with `scripts/build-desktop.sh` outside OneDrive or any synced folder (set `BUILD_DIR`/`DIST_DIR`):
  sync tools lock build files.
- Don't push, tag or publish releases for the owner; they upload.
