# What carries over from a Fabric mod to Horizon

This is a map of every kind of thing in a Fabric mod and how far horizon-port gets it into Horizon
automatically. It's based on what Horizon (nightly a46ce68) and horizon-mixins actually offer, and
measured on three real mods: Hand Animations, NoForcedPacks and Entity Keepsake.

Legend: **Done** means the converter writes it and it works without anyone touching it. **Wired**
means the right place in Horizon is hooked, but the body still has to be written. **Draft** means
the converter writes a starting point and a person finishes it. **Blocked** means Horizon has
nothing to attach to yet, so no converter can do it.

## The map

| In the Fabric mod | In Horizon | State |
|---|---|---|
| `fabric.mod.json`: id, name, version, description | The mod's row in Options > Mods (`native_mods`) | Done |
| Key bindings (`KeyMapping`, `KeyBindingHelper`) with their default keys | The mod's keys in Options > Mods, read with `native_mod_key` | Done |
| A config class (public option fields plus a static `INSTANCE`) | A Rust struct, loaded and saved with Horizon's per-mod settings | Done |
| That config's on/off options | Buttons on the mod's settings page that flip and save them, labelled with the mod's own texts (its `en_us.json`) | Done |
| That config's number options that the mod's own screen has a slider for | A `-` and a `+` button each on the settings page, with the slider's range and step read from the screen code | Done |
| A config the mod reads and writes with Gson, `Properties` or its own text file | The same file in the mod's config folder, read and written by the translated code (`JJson`/`JGson`, `JProps` in `runtime.rs`) | Done |
| Plain logic: maths, curves, clamps, string handling, parsing, loops, switch | Rust in `runtime.rs` (`pub mod java`) | Done when it doesn't touch the game (see below) |
| Streams, lambdas, method references, comparators, functions passed as parameters (`Supplier`, `Predicate`, `Consumer`...) | Rust iterators and closures | Done (a function kept in a field or variable isn't yet) |
| Regular expressions (`Pattern`, `Matcher`, `String.matches`/`replaceAll`/`split`) | A small regex engine shipped in the bundle (`jregex.rs`; bundles can't add the regex crate) | Done |
| Constants, enums, data classes, records | Rust consts, enums and structs | Done |
| Changing statics (`static int ticks`, `static boolean open`) | Statics behind a lock (`JCell`) | Done |
| Assets and data (`assets/`, `data/`) | A content mod (`.hmod`) built by `horizon-port-pack` | Done, by Horizon's own tool |
| Fabric tick and HUD handlers (a lambda or `Class::method`) | Translated into `game_side.rs`; the Fabric API layer runs the tick ones from the `tick` hook, the HUD hook calls the HUD ones | Done when every statement has a Horizon helper; otherwise the rest is translated and those statements are TODOs |
| Fabric lifecycle and connection events: `START_CLIENT_TICK`, `START/END_LEVEL_TICK`, `JOIN`, `DISCONNECT`, `CLIENT_STARTED` | The Fabric API layer (`jfabricapi.rs`), in Fabric's order, from the `tick` and `frame` hooks | Done (same rule for statements as above) |
| Client commands (`ClientCommandRegistrationCallback` with a Brigadier tree: literals, number/bool/string arguments, `executes`) | The Fabric API layer's command engine, from the `command` hook; each `executes` translated on its own | Done per `executes`; one that can't be translated says so in chat |
| Server events and commands, `CLIENT_STOPPING`, screen events, use/attack callbacks | Nothing in Horizon a46ce68 (c9d474d has hooks for most) | Blocked by the pinned Horizon |
| Key events | Horizon's `key` hook | Wired |
| Mixins on methods Horizon has (`@Inject` HEAD/RETURN/INVOKE, `@ModifyArg`, `@WrapOperation`...) | horizon-mixins injections, bound from the real Rust signature | Wired, when `match` finds the Rust function with high confidence |
| Mixins `match` isn't sure about, `@Overwrite`, `@ModifyConstant` / `@ModifyVariable` | Commented-out injections plus a `todo/` note with the Java | Draft |
| Code that calls Minecraft where Horizon has a helper: player position and look, sneaking, vanilla keys, the mod's keys (`consumeClick`, `isDown`), chat messages, HUD text, boxes and text width | `native_player`, `native_mapping`, `native_mod_key` (through the key hook), `native_chat`, `native_text`, `native_fill`, `native_text_width`, in `game_side.rs` | Done |
| Code that reads the player's own state: held and off-hand items, using an item, swinging, arm heights, look history, sprinting/crouching, effects, item tags, use animations, item names, the open screen, partial ticks | `Game`'s own fields and functions (`game.inventory`, `game.using`, `game.swing`, `game.clock`...), read from `game_side.rs` because the bundle sits inside Horizon's `game` module | Done. These bundles pin the Horizon revision; `check` verifies every field and function they read is still there |
| Code that calls Minecraft where Horizon has nothing yet: the wider world, other entities, opening screens | Nothing yet | Draft: the call is a TODO in the translated method, with the reason |
| First-person hand rendering: a mixin at `pushPose` in `ItemInHandRenderer.submitArmWithItem` / `renderArmWithItem` that moves the `PoseStack`, draws its own arm (`renderRightHand` / `renderLeftHand`) or item (`renderItem`), and its wrappers hiding vanilla's draw | Four injections into Horizon's `Game::hand_geometry`: HEAD runs the translated handler for each shown hand on a Rust `PoseStack` (JOML as Rust structs); wrappers around `first_person::item_pose` / `player_arm_pose` multiply in its pose or hide a hand it draws itself; RETURN draws its arms and items with Horizon's `first_person_arm` / `submit_item_world` | Done (not run in the game yet, see below) |
| Other item and block rendering, models, render types, entity rendering | Horizon's own renderer, which is laid out differently | Blocked for automatic conversion |
| Custom entities, block entities, menus, custom packets | Nothing a native mod can attach to yet | Blocked by Horizon |
| Resource-pack screens and the pack list | Horizon's own UI | Blocked for automatic conversion: needs a rewrite |
| A mod's own `Screen` classes | Horizon has no screen API for native mods; the settings page stands in for config screens | Blocked by Horizon (sliders and toggles carry over, above) |

## Why a method is left out

The translator converts each Java method whole or not at all; a handler (a Fabric event or a hand-drawing
mixin) is translated without the statements it can't do, which become TODOs. What's left, across the
three mods (from each bundle's `report/translation.md`):

| Cause | Methods | Can it be automated? |
|---|---|---|
| Calls code that's left out | 178 | Follows the rows below: almost all of it leads back to Minecraft classes. |
| Uses Minecraft classes Horizon has nothing for (the pack repository, entities, render states, vertex formats, widgets) | 66 | Not until Horizon has them. |
| Other Java: `Object`-typed code (16), interface methods with no body (8), generic code (10), anonymous and local classes, reflection | 43 | Some; most of it is in classes that need Minecraft anyway. |
| Calls the game where Horizon has no helper (`ItemStack.get`, `Minecraft.execute`, `Gui.overlay`) | 12 | When Horizon keeps that data. |
| Fabric API (`Version`, client command sources, mod containers) | 12 | Case by case. |
| One object shared between two names, try/catch of other exceptions, lambdas kept in fields, null where no Option fits | 14 | Case by case. |
| Other libraries: `ThreadLocal`, `MethodHandle`, `java.time`, zip files, translated text | 6 | Some. |
| Done another way in Horizon (the mod initializer is the init hook, ModMenu is Options > Mods) | 3 | Already done. |

Added in this round: streams, lambdas and comparators, functions as parameters, a regex engine, Gson
(`JsonParser`, `JsonObject`/`JsonArray`, `Gson.toJson`/`fromJson` for the mod's own classes), `Properties`,
try-with-resources over files, `AtomicInteger`/`AtomicLong`/`AtomicBoolean`, `Identifier.tryParse`, nullable
parameters (an `Option` where Java passes or checks null), reading value-like objects out of shared maps as
copies, record equality, `this(...)` constructors, `catch (RuntimeException ...)` as a caught panic, more String, List, Set
and Collections methods, sliders and labels for the settings page. Before that: switch expressions,
Map/Set/Optional, files in the config folder, JOML and `PoseStack`, `Random`, the static initializer.

## The three mods

| Mod | Methods translated | Statics translated | Fabric handlers | Hooks and injections wired | Still to write |
|---|---|---|---|---|---|
| Hand Animations | 79 of 92 (2 more done another way) | 191 of 199 | tick, init, join, hand drawing, `/handanimations` (3 of 4 subcommands; `item` needs `ItemProfiles.describe`) | 12 | 3 injections (rod tip, which hands render), item profiles' reload listener |
| NoForcedPacks | 158 of 301 | 109 of 117 | join (mixin) | 4 | 26 injections, plus the pack screens and pack repository |
| Entity Keepsake | 37 of 184 (1 more done another way) | 77 of 96 | init, join, `/entitykeepsake` (all 7 subcommands; `framewatch` without its frame sampling) | 3 | 35 injections, plus its render-state caching |

What works in each bundle right now:

- **Hand Animations:** its config loads from and saves to its own `handanimations.json` (Gson), and
  the settings page has its 11 on/off options and its 8 sliders (sway, inspect speed, arm X/Y/Z,
  pitch, yaw, roll) as `-`/`+` buttons with the same ranges and steps, under the mod's own labels.
  The inspect key defaults to Y. All 26 animation clips and the whole per-tick animator run from
  Horizon's hooks against Horizon's own player state, and the hand drawing handler runs inside
  Horizon's `hand_geometry`. The item profile JSON parser is in Rust too, but nothing feeds it the
  profile files yet: they come through Minecraft's resource manager. Approximations, listed in the report: an item's lore
  and custom data are absent (Horizon doesn't keep them); an item's name leaves out book titles and
  potion names; the mod's `owned` flag is replaced by "this hand drew itself".
- **NoForcedPacks:** its texture catalog (`Catalog`) and choices (`Choices`) logic, their data files,
  the pack-UI rules (`PackUi`, with its regex) and the pack-name helpers are in Rust. Scanning packs
  and its screens need Minecraft's pack repository and UI, which Horizon doesn't expose.
- **Entity Keepsake:** its settings file (`Properties`) loads and saves, its init handler runs, and
  its text helpers are in Rust. The mod itself caches Minecraft's entity render states and replays
  vertex buffers: a speed-up for Minecraft's renderer that has nothing to attach to in Horizon's.

## Added: the Fabric API layer

Every bundle that needs it gets one shared library, `jfabricapi.rs`, in `game_side.rs`: Fabric's event
lifecycle and a Brigadier-style command engine, run from Horizon's hook points. The translator only fills in
which handlers each event runs (`J_FABRIC`) and the command trees (`j_fabric_commands`), so a new Fabric event
is one table row (`RustTranslator.FABRIC_EVENTS`) plus a field in the layer.

- Events, in Fabric's order on the window's tick: `START_CLIENT_TICK`, `JOIN` / `DISCONNECT` (a player appears
  or goes away), `START_LEVEL_TICK` / `END_LEVEL_TICK` (in a world), `END_CLIENT_TICK`; `CLIENT_STARTED` on the
  first frame. None run in the integrated server's `Game`.
- Commands: literals, `integer`/`longArg`/`floatArg`/`doubleArg` with bounds, `bool`, `word`/`string`/
  `greedyString`; `executes` as a lambda or `Class::method`; `getSource().sendFeedback` / `sendError`,
  `getClient`, `getPlayer`, `getInput` and the `getInteger(c, "n")`-style getters. Wrong input answers in
  chat with Brigadier's message (`Integer must not be more than 16, found 40`). `requires` and `suggests`
  are left out.
- Also added: Java's text of a Map or List (`"..." + map` gives `{a=1, b=2}`), which Entity Keepsake's
  status message needed.
- Tests: `test/fabric_layer_test.rs` (18 checks of event order and command parsing, compiled with warnings
  as errors) and 17 new translator checks.

## Added in the "any means" round

- **Update checkers** (all three mods): `HttpClient`/`HttpRequest` through Horizon's own `ureq` (HTTPS; the
  game side sets `J_HTTP`, so `runtime.rs` stays plain Rust), Fabric's `Version`/`SemanticVersion` and
  `getModContainer` metadata from the mod's `fabric.mod.json` (`jfabric.rs`; `minecraft` is the version Horizon
  plays, read from `horizon-core`), `CompletableFuture` as threads (`jfuture.rs`) whose callbacks run on the game
  thread through a queue the tick hook empties, and `URLEncoder`, `Duration`, `TimeUnit`, `Thread.sleep`.
- **Joining a world**: `ClientPlayConnectionEvents.JOIN` and a mixin on `ClientPacketListener.handleLogin` run on
  the window's first tick with a player (`join_check`).
- **Components** on the game side are Horizon's `Text`: `literal`, `translatable` (filled from the mod's
  `en_us.json`), `withStyle` with `ChatFormatting` or a style lambda (colours, bold..., click links, hover text),
  `append`. A styled message goes into chat with its link clickable; a plain one still uses `native_chat`.
- **Opening the mod's own screen** (`setScreen(new SettingsScreen(..))`) opens its page in Options > Mods.
- Optional `map`/`flatMap`/`filter`/`ifPresent`, a `?:` that gives null, a function parameter Java passes null
  for, `try { v = parse(..); } catch` setting a variable declared without a value, `float.class`.
- Explicit `Iterator` loops as decompilers write them (`Iterator it = list.iterator(); while (it.hasNext())
  { T x = (T)it.next(); ... }`, raw `Iterator` included), over lists, arrays, sets and JSON arrays (`jiter.rs`;
  the loop walks a copy). A `try { v = parse(..); break; } catch` keeps its `break` in the loop. With this,
  NoForcedPacks' update check (`fetch`, `newest`, `onJoin`) converts whole.

Not done yet: the name tag recolouring (`DevName`), which needs a hook in Horizon's render crate
(`HudBatch::styled_text`), where generated code can't reach the bundle's modules. Everything else left in the
three mods is Minecraft-bound: screens, the pack repository, entity render states and the render cache (including
Entity Keepsake's `canCache`, a pattern `switch` over entity types), `FrameWatch` (it samples the Java game
thread's stack) and the self-test's screen clicking.

## What would add the most next

1. **Hooks into more of Horizon's own functions**, as for hand drawing: Horizon's name tags
   (`game/names.rs`, for Entity Keepsake's `DevName`) and resource pack loading (for NoForcedPacks).
   Each is a small injection set the generator writes from the mod's mixin, but each needs reading
   Horizon's code for the matching function first.
2. **Functions kept in fields** (`BiConsumer` setters, `ThreadLocal.withInitial`) as `Arc<dyn Fn>`.
3. **Screens:** opening a mod's settings screen (`setScreen`) could open its Options > Mods page.

What blocks most of the rest is in Horizon: entity rendering, the pack repository and screens.

## How sure is it

- **Compiled and compared.** All three bundles' `runtime.rs` compile on Rust 1.99.1 (Horizon asks
  for 1.85 or newer) with no warnings, and their `cargo test` passes, including the settings save/load test.
  The Rust was also run side by side with the original Java on the same inputs. For Hand Animations'
  `Clip.sample`, 90 samples across two clips matched bit for bit. For NoForcedPacks, 7 string helpers
  on 15 tricky inputs gave identical results.
- **Compiled against Horizon's helpers.** `check` compiles each bundle's `runtime.rs`, `game_side.rs`
  and hook-point helpers against the exact `native_*` signatures of Horizon a46ce68. All three pass.
- **The state they read is checked too.** `check` looks up every `Game` field and function the
  translated code reads (75 declarations) in that exact Horizon source, then compiles against stand-ins with
  the same types. If Horizon changes one, `check` says which.
- **Not checked here:** injections into Horizon's own functions (the hand-drawing ones, the other
  mixins) need Horizon's real types, so only the launcher's `native-build` compiles them. Nothing has
  run inside the game yet.
- **The runtime pieces are tested.** The regex engine and the JSON reader/writer carry Rust tests
  (Java's lazy groups, `split` and `replaceAll` rules, Gson's pretty printing and number text), which
  run with each bundle's `cargo test`.
- **Some Java behaviour differs in Rust.** A `catch (RuntimeException e)` catches the matching Rust
  panic through `catch_unwind` (Horizon builds with unwinding panics), but the panic message is still
  printed. Integer overflow, which Java wraps, panics in a Rust
  debug build. `Math.round` and `String.length` follow Java through helper functions. Strings are
  compared by value, where Java's `==` compares references.
