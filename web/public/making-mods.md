# Making Horizon mods

Horizon Translator's **Make a mod** tab makes mods for [Horizon](https://github.com/horizon-rust/horizon), the Rust
rewrite of Minecraft Java. You can make a mod without writing any code, and you can open its code later to go further.

- **No code:** tick what your mod does, fill in the boxes, press **Make mod**. You get a ready `.hnmod` file.
- **With code:** every mod comes with its Rust source, a test setup and a check button. Use it as a starter kit.
- **It keeps working:** kit mods only use the parts of Horizon that its team keeps stable for mods (the "hook points"),
  so the launcher builds them on newer Horizon versions too. You don't need a new version for each Horizon update.
- **It gets updates:** the app checks GitHub for a new version when it starts and tells you. New versions bring new
  features to the builder and the guide.

## Quick start

1. Get Horizon Translator for Windows or Linux from the website (or the GitHub Releases page) and open it.
2. Open the **Make a mod** tab. Pick an example under **Start from** and press **Use**, or start with the blank mod.
3. Give your mod a **name**. The **mod id** fills itself in: keep it the same for every new version of the same mod.
4. Tick the parts you want (HUD box, chat commands, keys, zoom, chat messages) and fill them in.
5. Leave **Add it to Horizon** ticked and press **Make mod**.
6. In the Horizon launcher's **Mods** page: **Build** under "Native build of Horizon" (if it isn't there, turn on
   **Settings > Game > Native mods** first). The first build downloads Rust and
   takes 20 to 30 minutes; later builds take a few minutes.
7. Play. Type `/<your mod id>` in chat to see your mod's commands and keys.

Your mod is saved in `Documents/Horizon Translator/<mod id>/` (change the folder at the bottom of the app).

## What you can make without code

### HUD box

Text in a corner of the screen, such as your coordinates, the way you face or your FPS. Each line can mix plain text
with placeholders (below). The box hides under F1 and moves out of the way of the F3 screen. Pick a text colour, a
dark box behind it or not, and a key to show or hide it. Players can also turn it off in **Options > Mods**.

### Chat commands

Commands players type in chat, like `/hello`. A command can:

- **Say in chat:** show one or more lines (use `|` between lines in the box).
- **Show / hide the HUD box.**
- **Zoom in / out.**

Commands run on the player's own game, before the server sees them, so they work on any server. Don't reuse a
server's command name (`/help`, `/home`, `/spawn`...): your mod would take it over. `/<mod id>` is always added: it
lists your commands and keys.

### Keys

Keys that do the same things as commands. Players can change every key in **Options > Mods > your mod**. Keys only
act while playing, not while typing in chat or with a menu open.

### Zoom

A zoom key like OptiFine's: how many times closer (1.5 to 10), only while held or press on / press off, smooth like
the spyglass, and the mouse wheel zooming closer or further while zooming. Mouse turning slows down while zoomed.

### Chat messages

- **Welcome message:** lines shown when you join a world.
- **Timed messages:** lines shown every so many minutes while you play (counted from joining the world).

Messages only show on your screen; nothing is sent to the server.

### Placeholders

Any text can hold these. They're filled in when the text is shown.

| Placeholder | Shows |
|---|---|
| `{x}` `{y}` `{z}` | Your position, one decimal: `12.3` |
| `{bx}` `{by}` `{bz}` | The block you're in: `12` |
| `{facing}` | `North (-Z)`, `East (+X)`, `South (+Z)`, `West (-X)` |
| `{yaw}` `{pitch}` | Where you look, in degrees |
| `{dimension}` | `Overworld`, `Nether`, `End` |
| `{fps}` | Frames per second |
| `{name}` `{version}` | Your mod's name and version |

### Settings players get

Every kit mod has a row in **Options > Mods** with its keys and an ON/OFF button for each part (HUD box, zoom, welcome
message, timed messages). Players can also turn the whole mod off there.

## Making a new version

1. **Open a mod I made...** and pick your mod's folder. The form fills in with what you picked last time.
2. Change what you want and raise the **version** (1.0.0 → 1.1.0).
3. **Make mod**. The new version goes into a new folder (`<mod id>-2`...), never over your old one, and replaces the
   old `.hnmod` in Horizon. Build again in the launcher.

Keep the same **mod id**: Horizon knows a mod by its id, and players' settings and keys stay with it.

## Sharing your mod

Send the `.hnmod` file. Players install it from the launcher's Mods page (or copy it into
`%APPDATA%\horizon\horizon-native-mods\` on Windows, `~/.local/share/horizon/horizon-native-mods/` on Linux) and build.

Native mods are compiled into the game and run with the player's rights; they aren't sandboxed. That's why the launcher
asks before building. Only share mods whose code you know, and only install mods from people you trust.

## Going further: the code

Every mod folder is a small Rust project:

| File | What it is |
|---|---|
| `mixins/<id>/runtime.rs` | The mod. Its settings at the top (texts, keys, commands), its logic, and tests at the bottom |
| `mixins/<id>/key.rs`, `hud.rs`, `command.rs`... | The hooks: each runs when Horizon calls it (a key, the HUD, a command, a tick) |
| `mixins/<id>/horizon-mixin.toml` | Which hook point runs which file |
| `Cargo.toml` | Lets `cargo test` run the tests in `runtime.rs` without the game |
| `compatibility.json` | The hook points' version the mod needs and the Horizon commit it was made on |
| `horizon-kit.json` | What you picked in the builder |
| `<id>.hnmod` | The packed mod |

To change the code:

1. Edit the files in any editor (VS Code with the rust-analyzer extension is a good choice).
2. In the app: **Make a mod > Check and pack a folder...**, and pick your mod's folder. With Rust installed
   (rustup.rs), it compiles your code against Horizon's mod helpers and runs your tests first; without Rust it packs
   straight away and the launcher's build tells you about mistakes. Then it packs a new `.hnmod` and adds it to Horizon.
3. Build in the launcher and try it.

**Don't use Make mod on a mod whose code you changed by hand:** it starts again from the form. Use Check and pack.

### From the command line (and for AI agents)

The app's `horizon-port.jar` does the same from a terminal, and adds a full check. Each mod folder has an
`AGENTS.md` with these commands for AI coding agents, and a `HOOKS.md` listing every hook point and helper.

| Command | What it does |
|---|---|
| `java -jar horizon-port.jar new spec.json --out DIR --horizon-src SRC` | Makes a mod from a builder spec (`kit-examples/` has some) |
| `java -jar horizon-port.jar hooks --horizon-src SRC [--json]` | Lists that Horizon's hook points and helpers, with the `[[injection]]` for each hook |
| `java -jar horizon-port.jar compile DIR --horizon-src SRC` | The full check: puts the mod into a copy of Horizon and compiles it, as the launcher does |
| `java -jar horizon-port.jar pack DIR --horizon-src SRC --compile --install` | Full check, tests, pack, add to Horizon |

`SRC` is Horizon's source, unpacked for `compile`. `compile` needs Rust and the launcher's horizon-mixins tool (installed
by its first native build); its first run takes a few GB and several minutes. Unlike **Check and pack**, it also checks
code that reads the game's own data (`game.player`, `game.items()`...), which isn't part of the hook points.

### How a hook works

Horizon calls empty functions at fixed places (the **hook points**). Each line `[[injection]]` in `horizon-mixin.toml`
puts one of your files at the start of one of those functions. Your file holds one function, which gets the values
listed under `bindings`. For example `key.rs` gets the game, the key's name and whether it went down or up:

```rust
fn my_mod_key(game: &mut crate::game::Game<'_>, key: &str, pressed: bool) {
    use crate::game::kit_my_mod as m;
    if !game.native_on(m::ID) {
        return;
    }
    if pressed && game.native_playing() && key == game.native_mod_key(m::ID, "jump_note", "key.keyboard.j") {
        game.native_chat("You pressed J");
    }
}
```

`runtime.rs` is added to the game as a module (`crate::game::kit_<id>`) and is also the project's own library, so
anything that doesn't need the game (maths, text, your mod's state) goes there and can be tested with `cargo test`.

Rules horizon-mixins sets: no `//!` comments or `#![...]` attributes in `runtime.rs`, no `mod x;` files and no
`include!`. Put everything in that one file (inline `mod x { ... }` is fine).

### The hook points

These are kit mods' building blocks. Names and arguments stay the same across Horizon versions.

| Hook point | When Horizon calls it | What it gets |
|---|---|---|
| `native_frame` | Every frame, in a world or not | `elapsed: Duration` |
| `native_tick` | Every game tick (20 a second), also in single player's server: check `native_window()` | |
| `native_hud` | While drawing the HUD (not under F1) | `batch`, `corners`: place a box with `corners.place(corner, w, h)` |
| `native_key` | A key or mouse button goes down or up (`key.keyboard.z`, `key.mouse.left`) | `key`, `pressed` |
| `native_wheel` | Mouse wheel while playing | `steps`, `claimed`: set it to keep the wheel from the hotbar |
| `native_command` | A chat command, without its slash, before the server sees it | `command`, `ran`: set it when it was yours |
| `native_zoom` | Every frame while playing | `zoom: &mut Option<NativeZoom>`: set it to zoom |
| `native_minimap` | Every frame | `minimap: &mut Option<NativeMinimap>`: set it to show Horizon's minimap |
| `native_mods` | Options > Mods lists the mods | `mods`: push your mod's row and keys |
| `native_settings` | Your mod's settings page opens | `mod_id`, `buttons`: push button labels |
| `native_settings_click` | A button on your settings page | `mod_id`, `button` |

Every hook gets `game` when its `bindings` ask for it: `{ name = "game", ty = "&mut crate::game::Game<'_>", value = "&mut *self" }`
(`&crate::game::Game<'_>` and `&*self` for `native_zoom` and `native_minimap`). The files the kit writes show the
`bindings` of each one.

### The game helpers

Call these on `game` from any hook.

| Helper | What it does |
|---|---|
| `native_on(id)` | Whether the player left your mod on in Options > Mods. Check it first in every hook |
| `native_playing()` | Playing, with no menu, chat or sign open |
| `native_window()` | This is the window's game, not a server's |
| `native_player()` | The player in a world: `x`, `y`, `z`, `yaw`, `pitch`, `dimension`, or `None` |
| `native_frame_rate()` | FPS now, the slowest frame and the 1% low |
| `native_clicks()` | Attack and use clicks in the last second |
| `native_mapping("key.jump")` | Whether a vanilla control is held, and its key's name |
| `native_mod_key(id, name, default)` | The key the player chose for your key `name` |
| `native_chat(text)` | A line in the player's chat |
| `native_text(batch, text, [x, y], rgb, shadow)` | Text on the HUD |
| `native_fill(batch, [x, y, w, h], argb)` | A rectangle on the HUD |
| `native_text_width(text)` | How wide a text is, in GUI pixels |
| `native_corner_shown(corner)` | Whether a corner is free (F3 takes the top ones) |
| `native_setting(id, key)` / `native_set_setting(id, key, value)` | Read and keep your mod's settings |
| `native_data_dir(id)` | Your mod's own folder, for files of its own |

Newer Horizon versions add more (an item panel beside inventories, recipes, giving items in creative). A mod that uses
those needs that Horizon version; **Check and pack** notices and writes it into the mod.

### Ideas to try

- A **CPS counter**: a HUD line with `native_clicks()`.
- **Keystrokes**: boxes that light up while `native_mapping("key.forward")` and the others are held.
- A **death coordinates** note: remember `native_player()` each tick and say where you were when it turns `None`.
- A **calculator**: `/calc 2+2`, reading the numbers after the command's name.
- A **session timer**: count ticks in `native_tick` and show the time played in the HUD box.

## What mods can't do yet

Horizon gives native mods the hook points above, not the whole game. Mods can't yet:

- add blocks, items, mobs, recipes or textures (those are content mods, `.hmod`, which this kit doesn't make);
- change how the world or mobs work on a server;
- read the world around the player (blocks, mobs, items) or the player's inventory;
- send chat messages or commands to the server.

When Horizon adds hook points, the kit can use them: the app's updates bring them to the builder.

## Problems

- **The launcher's build fails:** its Mods page shows the last lines of the error. Run **Check and pack** with Rust
  installed to see the full error with the file and line. Copy the error into a GitHub issue if you're stuck.
- **"Not packed: TODO"**: the packer refuses code with `TODO` in it (that's how unfinished converted mods are marked).
  Finish or remove those lines.
- **My key does nothing:** check **Options > Mods > your mod**: another mod or Horizon may use the same key.
- **My command goes to the server instead:** mod ids and command names are lower case; check `/<mod id>` lists it.
