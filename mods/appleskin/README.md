# AppleSkin for Horizon

A remake of AppleSkin: food values in tooltips, saturation and exhaustion on the hunger bar, and a preview of what the food in your hand restores.

A Horizon native mod made with Horizon Translator's mod kit.

## What it does

A remake of the AppleSkin mod's features, written for Horizon (not the original mod's code):

- **Food values in tooltips:** a food's tooltip shows the hunger it restores (shanks) and the
  saturation it gives (gold-outlined shanks).
- **Saturation on the hunger bar:** gold outlines round the shanks show your saturation, the hidden buffer that drains
  before hunger does.
- **Exhaustion under the hunger bar:** a pale bar fills from the right as you run, jump and fight; when it is full a
  point of saturation (or hunger) goes.
- **What the held food restores:** holding food, the shanks and saturation it would add flash on the bar.
- `/food` says your exact hunger, saturation and exhaustion in chat.
- `/appleskin` lists the commands. Each part can be turned off in Options > Mods > AppleSkin for Horizon.

It needs Horizon's hook points API 68 or newer (the nightly from 2026-11-01, c9d474d, has 80): the player-health HUD
(API 27) and tooltip images (API 68). On a server, exhaustion isn't sent to players, so the bar shows only in your
own worlds; everything else works everywhere.

## Install it

- **The easiest way:** Horizon Translator's **Get mods** tab installs it.
- **By hand:** `appleskin.hnmod` in this folder is the ready mod. Horizon Translator adds it to Horizon for you when
  "Add it to Horizon" is ticked; otherwise copy it into `%APPDATA%\horizon\horizon-native-mods\`. Then in the
  Horizon launcher's **Mods** page: **Build** under "Native build of Horizon" (if it isn't there, turn on
  Settings > Game > Native mods first), then play that build.
- **From this folder:** `horizon-launcher native-build "<this folder>" --trust-native-code`.

The launcher downloads its own Rust the first time (20 to 30 minutes), then builds the game with your native mods.

## Change it

| File | What it is |
|---|---|
| `mixins/appleskin/runtime.rs` | The mod: its settings at the top, its logic, and tests at the bottom |
| `mixins/appleskin/*.rs` (the others) | The hooks: each runs when Horizon calls it (a key, a tick, the HUD...) |
| `mixins/appleskin/health_hud.rs` | The hunger bar: Horizon's own rows, plus the outlines, the exhaustion bar and the preview |
| `mixins/appleskin/tooltip_size.rs`, `tooltip_draw.rs` | The tooltip rows |
| `mixins/appleskin/horizon-mixin.toml` | Which hook runs which file |
| `horizon-kit.json` | What you picked in the builder (open it in Horizon Translator to make a new version) |

Text and settings are at the top of `runtime.rs`. Edit them, then in Horizon Translator use **Make a mod > Check and
pack a folder** to pack a new `.hnmod`. This mod reaches into the game's own types (the health HUD, the held item),
which the quick check doesn't know, so pack it from the command line with `--no-check` and Horizon c9d474d's source:
`horizon-port pack . --horizon-src <horizon-c9d474d> --no-check`. With Rust installed, `cargo test` here runs the tests.

The guide that comes with Horizon Translator (Make a mod > Guide) lists every hook and game helper you can use.

Native mods run with the player's rights and aren't sandboxed: only share mods whose code you know.
