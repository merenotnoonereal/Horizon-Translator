# horizon-port

Works out how a Fabric mod carries over to [Horizon](https://github.com/horizon-rust/horizon), the Rust
rewrite of Minecraft: Java Edition 26.3. Sharing this tool is fine: it runs on the player's machine, on a
jar they downloaded themselves, and holds no Mojang or mod code. Converted output shouldn't be shared
unless the mod's licence allows it.

It reads a jar's `fabric.mod.json`, its Mixin configs and its class files (constant pools and
annotations only, never decompiled), then maps each thing it finds onto Horizon:

| In the Fabric mod | In Horizon |
|---|---|
| Fabric API events (`ClientTickEvents`, `HudRenderCallback`, `KeyBindingHelper`...) | native hook points (`tick`, `hud`, `key`, `command`, `wheel`, `frame`...) |
| Mixin `@Inject(HEAD / RETURN / INVOKE)`, `@ModifyArg`, `@Overwrite` | horizon-mixins `HEAD`, `RETURN`, `BEFORE_CALL`, `MODIFY_ARG`, `OVERWRITE` |
| A Java method such as `LocalPlayer.aiStep` | the matching Rust function, found by `match` |
| Block/item registration, `assets/`, `data/` | a content mod (`.hmod`), which `horizon-port-pack` builds |

`analyze` reports, `match` finds the Horizon functions, `generate` writes the Horizon native-mod bundle, `translate` turns the mod's own logic into Rust, `check` compiles a bundle against Horizon's helpers, and `port` checks the licence, decompiles and generates in one go.

[CONVERSION-MAP.md](CONVERSION-MAP.md) lists every kind of thing in a Fabric mod and how far it gets into Horizon on its own.

Shader packs (Iris/OptiFine) have their own tool, [shaders/](shaders/README.md) (`horizon-shaderport`, Rust): it runs Horizon's
own pack loader offline, rewrites the GLSL it skips, and writes a pack Horizon loads.

## Build and run

Needs a JDK 21 or newer (no other dependencies).

```bash
bash build.sh
```

```bash
java -jar build/horizon-port.jar analyze path/to/some-mod.jar
```

This writes `out/<mod id>/report.md` and `report.json`. Each thing the mod does gets a status:

| Status | Meaning |
|---|---|
| auto | The Horizon hook or injection is known: the wiring can be generated. The Java body still has to become Rust. |
| content | Data and assets: horizon-port-pack carries these. |
| needs-mapping | The kind of change carries over, but the Horizon function isn't in the table yet. |
| needs-translation | No direct equivalent: someone has to rewrite this part. |
| unsupported | Horizon has nothing this can attach to yet (entities, block entities, menus, custom packets...). |

## Finding the matching classes: `match`

Horizon isn't laid out like the Java game, but its doc comments name the vanilla method each
function reimplements (`` /// `LocalPlayer.aiStep`'s riding jump ``), about 10,000 of them. `match`
indexes Horizon's Rust source (leaving out tests) and scores candidates for each game method the mod
uses that the table doesn't map:

| Signal | Score |
|---|---|
| The doc comment names `Class.method` | 10 |
| ...names it under a vanilla superclass (`LivingEntity.setSprinting` for `LocalPlayer`) | 5 |
| The function's name is the method's, in snake case | 4 (2 for get/set forms, 1 for a constructor's `new`) |
| It's in an `impl` of the class | 4 |
| It's in a module named after the class | 2 |
| Words of the class name in its path | 1 each, up to 2 |
| Nothing else in Horizon has that name (with some tie to the class) | 2 |

Horizon often splits one vanilla method across several functions (`LocalPlayer.aiStep` is in four).
Then the suggestion lists every documented piece under `pieces`, and the report asks which one a
mixin hooks.

```bash
java -jar build/horizon-port.jar match path/to/some-mod.jar path/to/horizon-source
```

It writes `out/<mod id>/suggested-mappings.json`. Check the suggestions (each lists its score, why it
matched, the file and line, and other candidates), then lay them over the base table:

```bash
java -jar build/horizon-port.jar analyze path/to/some-mod.jar --mappings out/<mod id>/suggested-mappings.json
```

Horizon's source is `horizon-source.tar.gz` on its
[releases page](https://github.com/horizon-rust/horizon/releases), or a clone of the repository.

## Writing the Horizon mod: `generate`

```bash
java -jar build/horizon-port.jar generate path/to/some-mod.jar --horizon-src path/to/horizon-source --horizon-revision <40-character commit>
```

This does the whole pipeline: analyse the jar, match it against Horizon's source, take the
high-confidence matches (the doc comment and the name or `impl` agree), and write a native-mod
bundle to `out/<mod id>-horizon/`, laid out as Horizon's own Coords HUD mod is:

```text
Cargo.toml, compatibility.json      the pinned Horizon and horizon-mixins commits
PORTING.md                          what each Java part became, and what's left
mixins/<id>/horizon-mixin.toml      the module and every [[injection]]
mixins/<id>/runtime.rs              ID, name, version, keys; the mod's own logic goes here
mixins/<id>/<hook or mixin>.rs      one helper per injection
report/                             the analysis report
```

- **Fabric events** become HEAD hooks on Horizon's hook points (`tick`, `hud`, `key`, `command`,
  `frame`...), bound exactly as Coords HUD binds them. The mod's `key.<mod>.<name>` strings become
  its keys in Options > Mods, read with `native_mod_key`. A config screen or Mod Menu entry gets a
  settings page.
- **Mixins** become horizon-mixins injections (HEAD, RETURN, BEFORE_CALL, AFTER_CALL, MODIFY_ARG,
  WRAP_OPERATION, bridges...) per horizon-mixins' own Sponge feature map. The helper's bindings come
  from the target function's real signature; `selector` and `require` come from counting the call
  sites in its body, and a Java `@At` with no ordinal becomes one injection per call site.
- **Every helper** lists the game calls its Java class makes and what each is in Horizon, e.g.
  `GuiGraphics.drawString -> self.native_text(batch, text, [x, y], rgb, shadow)`.

Everything it switches on leaves the game unchanged (an empty HEAD, a RETURN that passes its value
on, a WRAP_OPERATION that calls the original), so the bundle builds into a game that plays like
vanilla until the `TODO`s are filled in. It writes these commented out, with the reason:

- `OVERWRITE`s, which would replace a function before its Rust exists;
- kinds that need a person's choice (which local, which literal);
- targets it couldn't map with confidence;
- targets horizon-mixins can't rewrite: bodies with `?`, closures, async or macros, generic functions,
  and calls inside closures.

Without the mod's source it never sees the Java method bodies. With it (below), it translates the
ones that don't need the game.

### With the mod's own source: `--java-src`

If you wrote the mod (or its licence lets you use its source), give `generate` its source folder:

```bash
java -jar build/horizon-port.jar generate some-mod.jar --horizon-src path/to/horizon-source --java-src path/to/mod/src/main/java
```

Each helper then also gets the original Java it stands in for, as `// |` comments under the hints:

- **Mixin helpers:** the mixin's handler method, with its `@Inject` / `@WrapOperation` annotation.
- **Hook helpers:** the event registration, e.g. the `END_CLIENT_TICK.register(...)` lambda, plus up to
  four methods of the same file that it calls.

Commented-out mixins get a notes file in `todo/` with the same: why it's off, the Horizon hints,
and its Java. That folder is outside `mixins/`, so the build never reads it.

The source is also translated to Rust (see `translate` below) into `runtime.rs`, under `pub mod java`,
with `report/translation.md` saying what was and wasn't. A config class becomes working buttons on the
mod's settings page.

### No source? `port` (licence check, decompile, generate)

```bash
java -jar build/horizon-port.jar port some-mod.jar --horizon-src path/to/horizon-source --horizon-revision <commit>
```

1. **Licence check** (`license some-mod.jar` runs it alone). This reads `fabric.mod.json`'s `license`
   and any LICENSE / COPYING file in the jar; the strictest one wins.
   - Open licences pass, with their conditions in `PORTING.md`. These include GPL, LGPL, AGPL, MPL,
     EUPL, MIT, Apache, BSD, ISC, CC0, CC-BY, CC-BY-SA and CC-BY-NC. For example, a GPL-3.0 mod's port
     must stay GPL-3.0, with its source, if you share it.
   - All Rights Reserved, no-derivatives (CC-BY-ND) and missing or unknown licences are refused.
     The exception is `--i-am-the-author NAME`, where NAME is one of the authors the jar lists; that
     confirmation is written into `LICENSE-CHECK.md`. A wrong name is refused too.
2. **Decompile** with [Vineflower](https://github.com/Vineflower/vineflower) (Apache-2.0, in
   `tools/`, from Maven Central) into `out/<mod id>-src/`. That folder is kept apart from the bundle,
   so sharing a bundle never shares decompiled code.
3. **Generate** with that source, labelled "Decompiled Java" in the helpers.

`decompile some-mod.jar` stops after step 2. The check reads what the jar says about itself; it
isn't legal advice.

Key bindings also get their real default key: `new KeyMapping("key.mod.zoom", GLFW.GLFW_KEY_Z, ...)` (or
the raw code `90`) becomes `key.keyboard.z`.

Build the bundle with Horizon's launcher, which brings its own Rust:

```bash
horizon-launcher native-build out/<mod id>-horizon --trust-native-code
```

Native mods aren't sandboxed: read the code before trusting it.

## The mod's own logic in Rust: `translate`

```bash
java -jar build/horizon-port.jar translate path/to/mod/src/main/java
```

This writes `out/translate-<folder>/java.rs` and `translation.md`. `generate --java-src` and `port`
do the same thing into the bundle. It translates:

- constants, enums (with `values`, `valueOf`, `name`, `ordinal`), classes and records into consts, enums and structs;
- statics that change into statics behind a lock (`JCell`: `get`, `set`, `with`);
- maths, with Java's promotions and casts made explicit; `Math` and Minecraft's `Mth` helpers;
- `if`, loops (counting loops become ranges), `switch`, `return`, `break`, `continue`, `throw` (as a panic);
- strings: concatenation, `StringBuilder`, simple `String.format`, and the common String methods, with Java's char-based `length` and `substring`;
- arrays and `List`, including varargs, `System.arraycopy` and `Arrays.fill`;
- `try { Integer.parseInt(..) } catch (NumberFormatException e)` as a `match` on `parse`;
- switch expressions and `yield`; `++x` at the start of an `if` condition;
- `Map`, `Set` (hash, linked and tree orders kept), `Optional` and `null` as `Option`;
- files under the mod's config folder (`FabricLoader.getConfigDir`, `Files.readString`/`writeString`) with their try/catch;
- JOML vectors, matrices, quaternions and Minecraft's `PoseStack` (`joml.rs`), and `Random`;
- the static initializer and `onInitializeClient`, run once before the first hook;
- streams (`filter`, `map`, `collect`, `anyMatch`, `sorted`, `distinct`...), lambdas, method references and
  comparators, and functions passed as parameters (`Supplier`, `Predicate`, `Consumer`, `BiConsumer`...);
- regular expressions, through a small engine shipped in the bundle (`jregex.rs`);
- Gson: `JsonParser`, `JsonObject`/`JsonArray`, and `Gson.toJson`/`fromJson` of the mod's own classes, which
  get `to_jjson`/`from_jjson` (`jjson.rs`); `Properties` (`jprops.rs`); try-with-resources over file readers and writers;
- atomics, `Identifier.tryParse`, `this(...)` constructors, and parameters Java passes null to (an `Option`);
- `catch (RuntimeException ...)` and friends, as a caught panic;
- log calls, which become comments.

Branches that can't run in Horizon are folded away (`isModLoaded` is false, item components Horizon
doesn't keep are absent), and each such shortcut is listed in the report under "Close, not the same".

Each method is translated whole or not at all. A method that uses something above it doesn't cover
(Minecraft classes Horizon has nothing for, long lambdas, Gson, regexes, one object shared between two
names...) is left out with its reason, and so is every method that calls it. Mixin classes become the
injections; their hand-drawing handlers are translated (below).

Methods that use Minecraft are then tried again with Horizon's helpers: `Minecraft.getInstance()`
is `game`, `mc.player` is `game.native_player()` (position, yaw, pitch), `isShiftKeyDown` and vanilla
keys are `native_mapping`, `displayClientMessage` is `native_chat`, `GuiGraphics.drawString` / `fill`
and `font.width` are `native_text` / `native_fill` / `native_text_width`, and the mod's own
`KeyMapping`s become key state the `key` hook keeps (`consumeClick`, `isDown`). The player's own state
(held items, using an item, swinging, arm heights, effects, item tags and names, the open screen,
partial ticks) is read straight from Horizon's `Game`, since the bundle's modules live inside Horizon's
`game` module; such bundles pin the Horizon revision. Those methods go into
`game_side.rs`. Fabric tick and HUD handlers (lambdas or `Class::method`) become `on_tick_N` /
`on_hud_N`, which the hook helpers call. A handler with a statement that has no Horizon helper is
translated without that statement, which becomes a TODO.

**Settings page.** A config class's on/off options become ON/OFF buttons, and its number options become
`-`/`+` buttons when one of the mod's screens builds a slider for them (a call with three number literals and
a getter lambda such as `slider("arm_x", -0.3, 0.3, 0.01, () -> c.armX, ...)`): same range, same step. Labels
come from the mod's `en_us.json` when the screen names the option there, and so do key names.

The report counts methods whose job Horizon does another way (the mod initializer is the init hook, a
ModMenu entry point is the mod's page in Options > Mods) separately from methods left out.

**First-person hands.** A mixin at `pushPose` in `ItemInHandRenderer.submitArmWithItem` (the way hand
animation mods move the arm and item) is translated like a handler, with the vanilla method's
parameters, and wired with four injections into Horizon's `Game::hand_geometry`: HEAD runs it for each
hand Horizon shows on a Rust `PoseStack`; wrappers around `first_person::item_pose` and
`player_arm_pose` put its pose before vanilla's, or hide a hand it draws itself; RETURN draws the arms
(`renderRightHand`/`renderLeftHand`) and items (`renderItem`) it drew, with Horizon's own arm and item
drawing. These are injections into Horizon's own function, so only the launcher's `native-build`
compiles them.

A **config class** (public option fields and `public static Config INSTANCE = new Config()`) also gets
`load_settings` and `save_settings` through Horizon's per-mod settings, and `TOGGLES`, `flag` and
`toggle` for its on/off options. `generate` then writes a settings page with one button per option
and a test that saving and loading round-trips.

## Compiling a bundle without Horizon: `check`

```bash
java -jar build/horizon-port.jar check out/<mod id>-horizon --horizon-src path/to/horizon-source
```

It first looks up every `Game` field and function the translated code reads in that Horizon source
and stops with "Horizon changed:" if one is missing. Then it writes a small crate in `<bundle>/target/horizon-check` whose `Game` has exactly the `native_*`
signatures of that Horizon source. It puts the bundle's modules and hook-point helpers where
horizon-mixins puts them, then runs `cargo check`. Injections into Horizon's own functions need
Horizon's real types and aren't checked. It builds nothing that runs, so it doesn't need
`--trust-native-code`.

When the launcher has installed horizon-mixins (its first native build does; or set `HORIZON_MIXINS` to the tool,
`none` to skip), `check` also runs its `plan` on the bundle: it validates every hook against the source and compiles
nothing. It catches hooks horizon-mixins refuses, such as a RETURN or call hook in a function with closures. A bundle
that pins a commit needs the source folder to name it, in a `.horizon-revision` file (as the launcher's copy has) or a
git checkout.

## The mapping table

`mappings/horizon-mappings.json` holds the hooks (from Horizon's `native-hooks.json`, hooks version
1), Fabric events, Mixin injector kinds, game superclasses, vanilla class parents and game methods.
A game method maps to one of:

- `hook`: a Horizon hook point (`Minecraft.tick` is the `tick` hook);
- `api`: what a native mod writes instead, with the helpers Horizon gives it
  (`GuiGraphics.drawString` is `self.native_text(batch, text, [x, y], rgb, shadow)`);
- `rust`: a Horizon function, to inject into;
- `status: content`: registration and properties, which horizon-port-pack carries.

Entries marked `checked` were read in Horizon's source (nightly a46ce68); `approximate` ones are
educated guesses. Reviewed suggestions from `match` can be copied into the table, so it grows with
each mod.

## Tests

```bash
bash test/run-test.sh
```

Besides the checks below, `test/TranslatorTest.java` translates small Java classes and checks the Rust.

This builds a made-up Fabric mod ("Sneak HUD": a HUD line, a key, a tick handler, three mixins, a
block and some data) against stand-in game, Fabric and Mixin classes, runs `analyze` and `match`
(against a small stand-in for Horizon's source) and checks the results.

## Limits

- Jars for Minecraft before 26.1 use intermediary names (`class_1234`); the report flags them, and
  the table, which uses Mojang names, won't match them.
- Code that uses Minecraft where Horizon has no helper (items, the world, entities, screens) isn't
  translated, and neither are mixin bodies. `generate` leaves a `TODO` in each helper, listing every
  game call the Java code makes and what it is in Horizon.
- With Rust installed, the tests compile the translated code with `rustc`, run `cargo test` in a
  bundle, and run `check` against `../horizon-source` when it's there. Injections into Horizon's own
  functions are only compiled by the launcher's build.
  Integer overflow, which Java wraps, panics in a Rust debug build.
- Generated bundles are checked against horizon-mixins' documented rules, not compiled here: build them with the launcher.
- `match` reads Rust with simple patterns, not a parser. Its suggestions are candidates to check.
- The Fabric event table covers the common events. Anything else shows up as an unmapped call.
