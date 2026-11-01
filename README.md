# Horizon Translator

Convert **Fabric mods** and **Iris / OptiFine shader packs** so they run in
[Horizon](https://github.com/horizon-rust/horizon), the rewrite of Minecraft Java Edition in Rust.

**[Open the website](https://merenotnoonereal.github.io/Horizon-Translator/)** ·
**[Download the Windows app](https://github.com/merenotnoonereal/Horizon-Translator/releases/latest/download/HorizonTranslator-Setup.exe)** ·
[Report an issue](https://github.com/merenotnoonereal/Horizon-Translator/issues/new/choose)

It comes as a **website** and a **Windows app**. Everything runs on the user's own computer: nothing is
ever uploaded, and there is no server to run or pay for.

| | What it does | Where it runs |
|---|---|---|
| **Website** (`web/`) | converts shader packs; download page for the app | in the visitor's browser (WebAssembly), hosted free on GitHub Pages |
| **Windows app** (`desktop/`) | converts mods and shader packs, same menu as the website | on the user's PC (Java included in the installer) |
| **Mod converter** (`mod-converter/`, Java) | Fabric mod `.jar` → Horizon native-mod bundle (`horizon-mixin.toml` + Rust) | inside the app, or from the command line |
| **Shader converter** (`shader-converter/`, Rust) | Iris/OptiFine pack `.zip` → pack Horizon loads (`<name>-horizon.zip`) | in the browser, inside the app, or from the command line |

> Community project. Not made by or affiliated with Mojang, Microsoft, the Horizon team, Fabric, Iris or OptiFine.

> [!WARNING]
> **The mod converter is experimental and doesn't convert mods fully.** Think of it as a helper: it does a
> lot of the porting work for you (licence check, mixin matching, Java → Rust), but most mods come out only
> partly working. Anything that relies on Minecraft features Horizon doesn't have yet is switched off and
> listed in `PORTING.md` for you to finish by hand. Try it on a popular mod to see what it can do, but don't
> expect a finished, 100% working port.
>
> The shader converter works better (11 of the 12 packs tested load fully), but converted packs can still
> look different from Java Edition + Iris.
>
> Everything here is provided as is, with no warranty. Keep backups, and only build mods you trust into your game.

---

## Contents

1. [Using the website and the app](#using-the-website-and-the-app)
2. [How it works](#how-it-works)
   - [The mod converter](#the-mod-converter)
   - [The shader converter](#the-shader-converter)
   - [The website and the app](#the-website-and-the-app)
3. [Running it yourself](#running-it-yourself)
4. [Support the project](#support-the-project)
5. [Project layout](#project-layout)
6. [Results so far](#results-so-far)
7. [Limits](#limits)
8. [Reporting problems](#reporting-problems)
9. [Licences and sharing](#licences-and-sharing)

---

## Using the website and the app

**Shader packs, on the website:**

1. Open the website and pick **Shader packs**.
2. Drop the pack `.zip` in (or click to choose it) and press **Convert shader pack**. It's converted inside
   your browser; small packs take seconds, the biggest a few minutes.
3. Download the result and read the report on the page.

**Mods (and shader packs), in the Windows app:**

1. On the website's **Mods** tab, press **Download for Windows** and run `HorizonTranslator-Setup.exe`.
   The installer isn't code-signed, so Windows SmartScreen may say *"Windows protected your PC"*: click
   **More info → Run anyway** (only for the file from this project's website or GitHub Releases).
   It installs for your user only, with no administrator rights, and Java is included.
2. Open **Horizon Translator**. It has the same Mods / Shader packs tabs as the website.
3. Drop in a Fabric mod `.jar` and press **Convert mod**. If the mod is *All Rights Reserved* and you made
   it, tick **I made this mod** and type your name exactly as the mod's `fabric.mod.json` lists it.
4. Results are saved in `Documents\Horizon Translator` (changeable). The report and the converter log
   show in the window; **Open result** opens the folder. For shader packs, *Also install it into Horizon's
   shaderpacks folder* copies the result straight into Horizon.

**Installing a converted shader pack:** put the `…-horizon.zip` in Horizon's `shaderpacks` folder
(`%APPDATA%\horizon\shaderpacks` on Windows) and choose it in *Options → Horizon Settings → Shader Packs*.

**Installing a converted mod:** Horizon native mods are compiled into the game. Unzip the bundle, read
`PORTING.md`, then build it with the Horizon launcher:

```bash
horizon-launcher native-build path/to/<modid>-horizon/mixins/<modid> --trust-native-code
```

A native mod runs with full access to your computer, so only build code you trust. The converter never
builds or runs anything; that step is always yours.

---

## How it works

### The mod converter

`mod-converter/` is **horizon-port**, a Java 21 program with no dependencies besides
[Vineflower](https://github.com/Vineflower/vineflower) (the decompiler, in `mod-converter/tools/`).
The app runs its `port` command:

```
port mod.jar --horizon-src horizon-src --out bundle [--i-am-the-author NAME]
```

which does five steps:

1. **Licence check** (`Licenses.java`). Reads the licence in `fabric.mod.json` and any `LICENSE` file in
   the jar. Open licences (MIT, Apache, BSD, GPL, LGPL, MPL, OSL, CC0, Unlicense, …) pass, with their
   conditions written into the bundle. *All Rights Reserved*, "no derivatives" and unknown licences are
   refused, unless `--i-am-the-author` matches one of the authors the jar lists. Nothing is decompiled
   before this check passes.
2. **Analyse the jar** (`ClassFile.java`, `Analyzer.java`). Parses the class files directly (no
   libraries): the mod's metadata, its entry points, every Mixin class and each `@Inject`, `@Redirect`,
   `@ModifyArg`, `@ModifyVariable`, `@WrapOperation`, `@Overwrite` and accessor, plus every Minecraft
   method the mod calls.
3. **Decompile** (`Decompiler.java`). Vineflower turns the classes back into Java source. That source is
   only an in-between step: it's kept outside the bundle, and the app deletes it as soon as the
   conversion ends.
4. **Match mixins to Horizon** (`Mappings.java`, `SourceMatch.java`, `mappings/horizon-mappings.json`).
   Every Minecraft method a mixin targets is looked up in a hand-checked mapping table, then in Horizon's
   own source: Horizon's functions carry doc comments like `doc is LocalPlayer.aiStep`, and a match is
   only taken automatically when the doc comment and the name or body agree ("high confidence").
   Each Sponge Mixin kind becomes the matching horizon-mixins kind (`HEAD`, `RETURN`, `BEFORE_CALL`,
   `MODIFY_ARG`, `WRAP_OPERATION`, …). Anything not certain is written commented out with a TODO note.
5. **Translate Java to Rust** (`JavaParser.java`, `RustTranslator.java`). A Java parser plus a translator
   that turns the mod's own logic into Rust: classes, records, enums, switch expressions, lambdas and
   streams, `Map`/`Set`/`Optional` (null becomes `Option`), strings, maths, JOML and `PoseStack`, regex,
   Gson JSON, `Properties`, config files, update checkers over HTTP, atomics and more. Game calls are mapped
   to Horizon helpers (chat, keys, HUD, tick, hand rendering, the Options → Mods settings page). Each method
   converts whole or not at all, so nothing half-works; what can't convert is listed with the reason.

The output **bundle** (`Generator.java`, `Report.java`):

```
<modid>-horizon/
  PORTING.md                     start here: what's on, what's left, the licence check
  mixins/<modid>/
    horizon-mixin.toml           the injections into Horizon's functions
    runtime.rs                   the translated Java (pub mod java) + glue
    game_side.rs                 code that reads/changes the game
  report/
    report.md, report.json       everything the analysis found
    translation.md               which methods became Rust and why others didn't
```

Everything switched on is safe by default: a bundle builds and plays like vanilla until the TODOs are
filled in, then each converted piece adds its behaviour. The converter also has a `check` command that
compiles a bundle against stubs of Horizon's real hook signatures without building the game.

Full detail: [`mod-converter/README.md`](mod-converter/README.md) (every command) and
[`mod-converter/CONVERSION-MAP.md`](mod-converter/CONVERSION-MAP.md) (what converts and what doesn't).

### The shader converter

`shader-converter/` is **horizon-shaderport**, a Rust program. Horizon already reads the Iris/OptiFine
`shaders/` format, but it **skips** any program its GLSL translator (naga) can't take, and a pack missing
programs draws wrong or not at all. The website and the app run:

```
horizon-shaderport port pack.zip --out result
```

1. **Load it exactly like the game** (`load.rs`, `gpu.rs`). The tool links Horizon's real
   `horizon-shaders` crate and runs its pack loader offline, with no GPU, for every dimension
   (overworld, nether, end). It goes one step further than the loader and also writes SPIR-V and checks
   output formats, so it catches the errors that only show up when the game compiles for the GPU.
   The result is a list of programs that load and programs that would be skipped, with the reason.
2. **Rewrite what fails** (`fix.rs`, `gpu_fix.rs`, `tex3d.rs`, `compute.rs`, …). About 20 rules, each a
   small, meaning-keeping GLSL rewrite: multi-line macro calls joined, `const in` parameters, reserved
   names, vector components passed to `out` parameters, outputs padded to RGBA, 3D textures replaced by
   2D stand-ins, compute passes moved into fragment passes, and more. Rules are tried one at a time and a
   rule is **kept only if** more programs load and nothing that loaded before now fails. When a rule
   breaks something, the tool bisects by file to keep the parts that help.
3. **Fix how it draws** (`normals.rs`, `uniforms.rs`, `pbr.rs`, `postkeep.rs`, `fragdepth.rs`,
   `noise2d.rs`, `bufsize.rs`, `skyblend.rs`, `cloudsoff.rs`). Passes found by comparing every pack in
   Horizon side by side with Java Edition + Iris. They work around differences in how Horizon renders:
   face normals rebuilt in the fragment shader, uniforms Horizon sends as zero, matrix order, buffers
   Horizon clears between passes, `gl_FragDepth` reads, noise textures Horizon doesn't load, buffer sizes,
   sky blending and clouds.
4. **Write the pack.** `<name>-horizon.zip` plus `<name>-horizon.md`, a report of every rule used, what
   it changed and what Horizon loads before and after.

Full detail, including every rule: [`shader-converter/README.md`](shader-converter/README.md).
Horizon bugs found along the way (written up for the Horizon team): [`docs/HORIZON-BUGS.md`](docs/HORIZON-BUGS.md).

### The website and the app

**The website** (`web/public/`) is plain HTML, CSS and JavaScript with no frameworks, hosted as static files.

- Shader packs are converted **in the browser**: the shader converter is compiled to WebAssembly
  (`horizon-shaderport.wasm`, built by `scripts/build-wasm.sh`) and run in a background worker
  (`shader-worker.js`) through a small WASI layer
  ([browser_wasi_shim](https://github.com/bjorn3/browser_wasi_shim)). The pack is read from the visitor's
  disk, converted in memory, and offered back as a download. It never leaves their computer.
  The WebAssembly build gives byte-for-byte the same result as the desktop program on all 12 test packs
  (about half the speed). WebAssembly can't run threads or catch crashes, so that build loads the three
  dimensions one after another and checks for a known naga crash before it happens (see `load.rs`, `gpu.rs`).
- The **Mods** tab is the download page for the Windows app (the newest `HorizonTranslator-Setup.exe` on
  the repository's GitHub Releases).
- Reports are Markdown, rendered by `markdown.js`, which escapes all text before adding formatting.
  The Support me section (`donate.json`), the Terms of Use (`terms.html`) and the "Report a problem" links that
  start a GitHub issue with the details filled in are all in the same folder.

**The Windows app** (`desktop/`) is a Java Swing program, `TranslatorApp.java`, laid out like the website:
a warning banner, Mods, Shader packs and Support me tabs, an output folder, and a result panel with
the report and the log. It runs the mod converter as a separate process with the Java it ships (Vineflower
runs inside that process), and `horizon-shaderport.exe` for shader packs. It ships Horizon's source as one
zip (`horizon-src.zip`, only the `.rs` files the converter reads), which the converter reads in place.
`scripts/build-desktop.sh` builds it with `jpackage` (bundles a trimmed Java runtime) and
[Inno Setup](https://jrsoftware.org/isinfo.php) (`desktop/installer.iss`) into one unsigned
`HorizonTranslator-Setup.exe` (about 43 MB). `--selftest <mod.jar> <pack.zip> <folder>` runs both converters
without the window.

---

## Running it yourself

You need **Java 21+** (JDK), **Rust 1.85+** ([rustup](https://rustup.rs)), **Python 3**, **Git Bash** on
Windows (or any bash), and an internet connection for the first build.

```bash
bash scripts/get-horizon.sh   # downloads Horizon's source (pinned commit) into ./horizon-src
bash scripts/build.sh         # builds both converters
bash scripts/build-wasm.sh    # rebuilds the website's WebAssembly shader converter
bash scripts/run-local.sh     # serves the website on http://localhost:8080
```

`run-local.sh` is only a file server (`web/server/TranslatorServer.java`, JDK only): browsers won't run the
WebAssembly converter from a page opened straight from disk.

**The Windows app** additionally needs [Inno Setup 6](https://jrsoftware.org/isdl.php):

```bash
bash scripts/build-desktop.sh   # -> dist/HorizonTranslator-Setup.exe
```

Keep the build out of OneDrive/Dropbox folders, which lock files while syncing: set `BUILD_DIR` and
`DIST_DIR` to folders elsewhere if the repo lives in one.

On Windows, Rust may need the Visual Studio Build Tools, or switch to the GNU toolchain
(`rustup default stable-x86_64-pc-windows-gnu`), which builds both converters fine.

**Command line, without the website:**

```bash
# Mods
java -jar mod-converter/build/horizon-port.jar port some-mod.jar --horizon-src horizon-src
java -jar mod-converter/build/horizon-port.jar license some-mod.jar      # just the licence check

# Shaders
shader-converter/target/release/horizon-shaderport check MyPack.zip     # what Horizon loads and skips
shader-converter/target/release/horizon-shaderport port MyPack.zip --install
```

Publishing the website and the installer is covered in [`docs/MAINTAINING.md`](docs/MAINTAINING.md).

**Tests:** `bash mod-converter/test/run-test.sh` (builds a made-up test mod and checks every step;
prints `FAIL:` lines on failure) and `cd shader-converter && cargo test`.

---

## Support the project

Horizon Translator is free. If it helped you, you can support it with crypto. The addresses, with QR codes,
are in the **Support me** section of the [website](https://merenotnoonereal.github.io/Horizon-Translator/#support)
and in the app's **Support me** tab.

- **Bitcoin (BTC):** `bc1qj2ky2j5rzxwtpk3zv7x5xtp3jvh33d9nr7ulkt`
- **Ethereum & EVM (ETH, USDC, USDT on Ethereum, Base, Arbitrum, Optimism or Polygon):** `0xB223E06c64D715a4b8e27394e75cE0eeb72B6778`

Only send on the networks listed. Crypto sent on another network can't be got back.

---

## Project layout

```
Horizon-Translator/
├── README.md                   this file
├── .github/
│   ├── workflows/pages.yml     publishes the website to GitHub Pages
│   └── ISSUE_TEMPLATE/         forms for mod, shader, website problems and ideas
├── scripts/
│   ├── get-horizon.sh          downloads Horizon's source (pinned commit) into horizon-src/
│   ├── build.sh                builds both converters
│   ├── build-wasm.sh           builds the website's WebAssembly shader converter
│   ├── build-desktop.sh        builds the Windows app + HorizonTranslator-Setup.exe
│   ├── check-donate.mjs        checks the donation addresses in web/public/donate.json
│   └── run-local.sh            serves the website on your PC
├── web/
│   ├── server/TranslatorServer.java   a local file server for trying the site (JDK only)
│   └── public/                 the website: index.html, terms.html, style.css, app.js, markdown.js,
│                               shader-worker.js + horizon-shaderport.wasm, donate.json, logo.png
├── desktop/                    the Windows app
│   ├── src/horizonport/        TranslatorApp.java (the window), Support.java + Qr.java (Support me tab)
│   ├── installer.iss, installer-notice.txt  the Inno Setup installer
│   └── make-horizon-zip.py, make-ico.py     build helpers
├── mod-converter/              horizon-port (Java)
│   ├── src/horizonport/        analyser, licence check, decompiler driver, Java parser,
│   │                           Java→Rust translator, bundle generator; *.rs = Rust runtime pieces
│   ├── mappings/horizon-mappings.json   Minecraft method → Horizon function table
│   ├── tools/vineflower-1.12.0.jar      decompiler (Apache-2.0)
│   ├── test/                   test mod generator + checks
│   ├── README.md, CONVERSION-MAP.md
│   └── build.sh, jdk.sh
├── shader-converter/           horizon-shaderport (Rust)
│   ├── src/                    loader, rules and draw fixes (one file per fix)
│   ├── tests/                  naga probes
│   └── README.md, Cargo.toml, Cargo.lock
└── docs/
    ├── HOW-IT-WORKS.md         the pipeline step by step, with diagrams
    ├── MAINTAINING.md          publishing the site and app, editing donation addresses
    └── HORIZON-BUGS.md         Horizon bugs found while testing packs
```

`horizon-src/` (Horizon's source) and all build output are left out of git (`.gitignore`); the build
scripts fetch or build them. The website's `horizon-shaderport.wasm` is committed, so GitHub Pages can serve it as is.

---

## Results so far

Tested against Horizon nightly `a46ce68`.

**Shader packs** (12 most-downloaded on Modrinth):

| Pack | Programs before | After |
|---|---|---|
| BSL, Complementary Reimagined, Complementary Unbound | all load | all load + draw fixes |
| Bliss | 13 load, 144 skipped | 80 load, 0 skipped |
| Photon | 9 load, 123 skipped | 120 load, 0 skipped |
| Noble | 44 load, 182 skipped | 138 load, 0 skipped |
| MakeUp UltraFast | 18 load, 84 skipped | 50 load, 0 skipped |
| Super Duper Vanilla | 33 load, 36 skipped | 69 load, 0 skipped |
| Solas | 35 load, 24 skipped | 55 load, 0 skipped |
| Mellow | 54 load, 9 skipped | 60 load, 0 skipped |
| Miniature | 14 load, 4 skipped | 18 load, 0 skipped |
| Rethinking Voxels | 24 load, 54 skipped | 36 load, 30 skipped (needs features Horizon lacks) |

11 of 12 load fully and were checked in game next to Java Edition + Iris.

**Mods:** 40 open-licence mods from the Fabulously Optimized modpack convert and compile against Horizon.
For three hand-tested mods, 79 of 92, 158 of 301 and 35 of 184 methods translated to Rust; all three load in
Horizon.

---

## Limits

- **Mods:** code that only uses Java and the game's basics converts well. Code built on Minecraft systems
  Horizon doesn't have yet (custom screens and widgets, entity renderers and render states, the resource
  pack repository, item components) stays switched off and is listed in `PORTING.md`. The mod still loads.
- **Mods** have to be built into the game by you with the Horizon launcher; the app can't do that.
- The mod converter needs the Windows app; it doesn't run in the browser.
- **Shaders:** geometry shaders, image load/store and storage buffers need Horizon support, so packs built
  around them (Rethinking Voxels) only partly convert. A pack that loads can still look a bit different
  from Java + Iris; the report says what was changed.
- Both converters read Horizon's internals, so they're pinned to one Horizon commit
  (`a46ce6898bd72016e061aaec1afc1375b384deac`). Newer nightlies may need the mapping table updated.

---

## Reporting problems

Open an [issue](../../issues/new/choose) and pick the form that fits: a mod that didn't convert, a shader
pack that looks wrong, a website problem, or an idea. On the website, **Report a problem** under a result
starts an issue with the file name, result and log filled in. Please link to where you got a mod or pack
instead of attaching it, unless its licence allows sharing.

---

## Licences and sharing

- **Converted files are for your own use.** Most shader pack licences (BSL, Complementary, Bliss, Solas…)
  forbid sharing modified copies, and a mod's licence decides whether a port of it may be shared.
  Nothing is uploaded anywhere: the website converts in the visitor's browser and the app on their PC.
  Decompiled code is deleted as soon as a conversion ends.
- The mod converter refuses to decompile mods whose licence doesn't allow it.
- Third-party parts: Horizon (MIT; downloaded at build time, and the app ships a zip of its `.rs` files),
  Vineflower (Apache-2.0, `mod-converter/tools/`), the Rust crates in `shader-converter/Cargo.lock` (naga,
  png, zip — MIT/Apache-2.0), the Java runtime bundled in the app (OpenJDK, GPLv2 with Classpath Exception),
  and, loaded by the website from CDNs, browser_wasi_shim (MIT/Apache-2.0) and qrcodejs (MIT).
- This repository doesn't have a licence file yet. Until one is added, the default is "all rights
  reserved". Add a `LICENSE` (for example MIT) if you want others to reuse the code.
