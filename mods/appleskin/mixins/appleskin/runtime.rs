// AppleSkin for Horizon: a Horizon native mod made with Horizon Translator's mod kit.
// This file is both the mod's Cargo library (`cargo test` runs the tests at the bottom without the game)
// and the module the bundle adds to the game (crate::game::kit_appleskin). The small files beside it (key.rs, hud.rs...)
// are the hooks: Horizon calls them, and they call the functions here. Change anything you like; the guide
// (Make a mod > Guide in Horizon Translator) says what each hook and game helper does.
// Module files can't hold `//!` comments or inner attributes: keep to plain `//` comments.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;

/// The mod's id (its settings folder and its keys' lines), name, version and description.
pub const ID: &str = "appleskin";
pub const NAME: &str = "AppleSkin for Horizon";
pub const VERSION: &str = "1.1.0";
pub const DESCRIPTION: &str = "A remake of AppleSkin: food values in tooltips, saturation and exhaustion on the hunger bar, and a preview of what the food in your hand restores.";
pub const AUTHOR: &str = "Horizon Translator mod kit";

/// The mod's keys, as Options > Mods lists them: name, label, default key. Players can change them there.
pub const KEYS: &[[&str; 3]] = &[
];

/// What a key or a command does.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Action {
    /// Puts these lines in chat (placeholders filled in).
    Say(&'static [&'static str]),
    /// Shows or hides the HUD box.
    ToggleHud,
    /// Zooms in, or back out.
    ToggleZoom,
    /// Zooms while the key is held.
    HoldZoom,
    /// Lists the mod's commands and keys (the /appleskin command).
    Help,
    /// Says the player's hunger, saturation and exhaustion (the /food command).
    Food,
}

/// What each key (by its name in KEYS) does.
pub const KEY_ACTIONS: &[(&str, Action)] = &[
];

/// The mod's chat commands, without the slash, and what they do. /appleskin (the help) is added by `command`.
pub const COMMANDS: &[(&str, Action)] = &[
    ("food", Action::Food),
];

/// The HUD box: its corner (0 top left, 1 top right, 2 bottom left, 3 bottom right), its lines, the text colour
/// (0xRRGGBB) and the box behind it (0xAARRGGBB, 0 for none).
pub const HUD_CORNER: usize = 0;
pub const HUD_LINES: &[&str] = &[];
pub const HUD_INK: u32 = 0xFFFFFF;
pub const HUD_BACKGROUND: u32 = 0x7800_0000;

/// Zoom: how many times closer, eased like the spyglass (smooth), and whether the mouse wheel changes it while zooming.
pub const ZOOM_FACTOR: f32 = 4.0;
pub const ZOOM_SMOOTH: bool = true;
pub const ZOOM_SCROLL: bool = false;

/// Said in chat on joining a world.
pub const WELCOME: &[&str] = &[];

/// Said in chat every so many seconds while in a world.
pub const TIMERS: &[(u32, &[&str])] = &[
];

/// The ON/OFF options of the mod's settings page (Options > Mods > AppleSkin for Horizon): key, label. All start ON.
pub const TOGGLES: &[(&str, &str)] = &[
    ("saturation", "Saturation on the hunger bar"),
    ("exhaustion", "Exhaustion under the hunger bar"),
    ("preview", "What the held food restores"),
    ("tooltip", "Food values in tooltips"),
];

// ------------------------------------------------------------------ text

/// What the text placeholders show: the player, in a world.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Info {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub yaw: f32,
    pub pitch: f32,
    pub dimension: String,
    pub fps: u32,
}

impl Info {
    /// From the game's `native_player()` (x, y, z, yaw, pitch, dimension) and frame rate.
    pub fn new(player: (f64, f64, f64, f32, f32, &str), fps: u32) -> Self {
        let (x, y, z, yaw, pitch, dimension) = player;
        Info { x, y, z, yaw, pitch, dimension: dimension.to_owned(), fps }
    }
}

/// The way the player faces (Minecraft's yaw: 0 faces south, 90 west).
pub fn facing(yaw: f32) -> &'static str {
    let i = (yaw / 90.0 + 0.5).floor() as i32 & 3;
    ["South (+Z)", "West (-X)", "North (-Z)", "East (+X)"][i as usize]
}

/// `minecraft:the_nether` as players say it.
pub fn dimension_name(id: &str) -> String {
    match id {
        "minecraft:overworld" => "Overworld".into(),
        "minecraft:the_nether" => "Nether".into(),
        "minecraft:the_end" => "End".into(),
        other => other.rsplit(':').next().unwrap_or(other).replace('_', " "),
    }
}

/// `text` with its placeholders ({x}, {facing}, {fps}...) filled in. Unknown ones stay as they are; outside a
/// world (`info` None) the ones about the player show `?`.
pub fn fill(text: &str, info: Option<&Info>) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(open) = rest.find('{') {
        out.push_str(&rest[..open]);
        let after = &rest[open + 1..];
        let Some(close) = after.find('}') else {
            rest = &rest[open..];
            break;
        };
        let name = &after[..close];
        match value(name, info) {
            Some(v) => out.push_str(&v),
            None => {
                out.push('{');
                out.push_str(name);
                out.push('}');
            }
        }
        rest = &after[close + 1..];
    }
    out.push_str(rest);
    out
}

fn value(name: &str, info: Option<&Info>) -> Option<String> {
    match name {
        "name" => return Some(NAME.into()),
        "version" => return Some(VERSION.into()),
        _ => {}
    }
    let known = ["x", "y", "z", "bx", "by", "bz", "facing", "yaw", "pitch", "dimension", "fps"];
    if !known.contains(&name) {
        return None;
    }
    let Some(i) = info else {
        return Some("?".into());
    };
    Some(match name {
        "x" => format!("{:.1}", i.x),
        "y" => format!("{:.1}", i.y),
        "z" => format!("{:.1}", i.z),
        "bx" => format!("{}", i.x.floor() as i64),
        "by" => format!("{}", i.y.floor() as i64),
        "bz" => format!("{}", i.z.floor() as i64),
        "facing" => facing(i.yaw).into(),
        "yaw" => format!("{:.1}", i.yaw),
        "pitch" => format!("{:.1}", i.pitch),
        "dimension" => dimension_name(&i.dimension),
        _ => i.fps.to_string(),
    })
}

// ------------------------------------------------------------------ settings

thread_local! {
    /// The ON/OFF options, read once from their files, then kept.
    static FLAGS: RefCell<HashMap<&'static str, bool>> = RefCell::new(HashMap::new());
}

/// Whether option `key` (of TOGGLES) is ON, read once through `read` (the game's `native_setting`).
pub fn flag(key: &'static str, read: impl FnOnce(&str) -> Option<String>) -> bool {
    if let Some(v) = FLAGS.with(|f| f.borrow().get(key).copied()) {
        return v;
    }
    let v = read(key).is_none_or(|v| v.trim() != "false");
    FLAGS.with(|f| f.borrow_mut().insert(key, v));
    v
}

/// Sets option `key`; the caller keeps it with `native_set_setting`.
pub fn set_flag(key: &'static str, on: bool) {
    FLAGS.with(|f| f.borrow_mut().insert(key, on));
}

/// The settings page's button for an option: `Show the HUD box: ON`.
pub fn toggle_label(label: &str, on: bool) -> String {
    format!("{label}: {}", if on { "ON" } else { "OFF" })
}

/// The text kept for an option's file.
pub fn stored(on: bool) -> &'static str {
    if on { "true" } else { "false" }
}

// ------------------------------------------------------------------ keys and commands

/// What key `name` of KEYS does.
pub fn key_action(name: &str) -> Option<Action> {
    KEY_ACTIONS.iter().find(|(n, _)| *n == name).map(|(_, a)| *a)
}

/// What a chat command (without its slash, with its arguments) does, when it is one of this mod's.
pub fn command(command: &str) -> Option<Action> {
    let name = command.split_whitespace().next().unwrap_or("").to_lowercase();
    if name == ID {
        return Some(Action::Help);
    }
    COMMANDS.iter().find(|(n, _)| *n == name).map(|(_, a)| *a)
}

/// The /appleskin lines: the mod, its commands and its keys. `key_label` gives the key a player chose.
pub fn help(key_label: impl Fn(&str, &str) -> String) -> Vec<String> {
    let mut out = vec![format!("{NAME} {VERSION}")];
    if !DESCRIPTION.is_empty() {
        out.push(DESCRIPTION.into());
    }
    if !COMMANDS.is_empty() {
        let names: Vec<String> = COMMANDS.iter().map(|(n, _)| format!("/{n}")).collect();
        out.push(format!("Commands: {}", names.join(", ")));
    }
    for [name, label, default] in KEYS {
        out.push(format!("{label}: {}", key_label(name, default)));
    }
    if !KEYS.is_empty() || !TOGGLES.is_empty() {
        out.push(format!("Change keys and options in Options > Mods > {NAME}."));
    }
    out
}

/// `key.keyboard.left.shift` as a player reads it: `Left Shift`.
pub fn key_label(key: &str) -> String {
    let short = key.trim_start_matches("key.keyboard.").trim_start_matches("key.");
    short
        .split('.')
        .map(|w| {
            let mut c = w.chars();
            match c.next() {
                Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

// ------------------------------------------------------------------ zoom

thread_local! {
    static ZOOMING: Cell<bool> = const { Cell::new(false) };
    static ZOOM_NOW: Cell<f32> = const { Cell::new(ZOOM_FACTOR) };
}

/// Zooms in or out (`Some(on)`), or the other way round (`None`). Zooming out puts the wheel's change back.
pub fn set_zoom(on: Option<bool>) {
    let on = on.unwrap_or(!ZOOMING.get());
    ZOOMING.set(on);
    if !on {
        ZOOM_NOW.set(ZOOM_FACTOR);
    }
}

/// How many times closer the view is now, or None while not zooming.
pub fn zoom_now() -> Option<f32> {
    ZOOMING.get().then(|| ZOOM_NOW.get())
}

/// The mouse's turning while zoomed: slower the closer the view, as with the spyglass.
pub fn zoom_turn(factor: f32) -> f32 {
    (1.0 / factor).clamp(0.05, 1.0)
}

/// Wheel steps while zooming change the zoom: whether they were taken (so the hotbar keeps still).
pub fn zoom_wheel(steps: f32) -> bool {
    if !ZOOM_SCROLL || !ZOOMING.get() {
        return false;
    }
    ZOOM_NOW.set((ZOOM_NOW.get() * 1.25f32.powf(steps)).clamp(1.5, 10.0));
    true
}

// ------------------------------------------------------------------ messages

thread_local! {
    static TICKS: Cell<u64> = const { Cell::new(0) };
    static IN_WORLD: Cell<bool> = const { Cell::new(false) };
}

/// One tick (20 a second) of the window's game: the lines to put in chat now. The welcome on joining a world,
/// then the timed messages every so many seconds, counted from joining.
pub fn tick(in_world: bool, welcome_on: bool, timers_on: bool) -> Vec<&'static str> {
    let mut say = Vec::new();
    let joined = in_world && !IN_WORLD.get();
    IN_WORLD.set(in_world);
    if !in_world {
        TICKS.set(0);
        return say;
    }
    if joined && welcome_on {
        say.extend_from_slice(WELCOME);
    }
    let t = TICKS.get() + 1;
    TICKS.set(t);
    if timers_on {
        for (every, lines) in TIMERS {
            if t.is_multiple_of(u64::from(*every) * 20) {
                say.extend_from_slice(lines);
            }
        }
    }
    say
}

// ------------------------------------------------------------------ AppleSkin

/// The saturation outline's two golds (lit edges, shaded edges) and the exhaustion bar (0xAARRGGBB).
pub const GOLD: u32 = 0xFFFF_DD4A;
pub const GOLD_SHADE: u32 = 0xFFC7_8A12;
pub const EXHAUSTION: u32 = 0x4DFF_FFFF;
/// Exhaustion takes a point of saturation (or hunger) each time it reaches this.
pub const EXHAUSTION_MAX: f32 = 4.0;

/// The shank's shape, 9 by 9 like the game's hunger icons: the outline is drawn round it.
const SHANK: [&str; 9] = [
    "..##.....",
    ".####....",
    "######...",
    "#######..",
    ".######..",
    "..#####..",
    "...######",
    "......###",
    "......##.",
];

fn in_shank(x: i32, y: i32) -> bool {
    (0..9).contains(&x) && (0..9).contains(&y) && SHANK[y as usize].as_bytes()[x as usize] == b'#'
}

/// Something to draw, in GUI pixels; the hooks turn these into Horizon's draws. Sprites are 9 by 9.
#[derive(Clone, Debug, PartialEq)]
pub enum Draw {
    Sprite { name: &'static str, x: i32, y: i32, color: u32 },
    Fill { x0: i32, y0: i32, x1: i32, y1: i32, color: u32 },
    Text { text: String, x: i32, y: i32, color: u32 },
}

/// Which of the options are ON.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Shown {
    pub saturation: bool,
    pub exhaustion: bool,
    pub preview: bool,
}

impl Shown {
    /// From the settings page (`read` is the game's `native_setting`).
    pub fn read(read: impl Fn(&str) -> Option<String>) -> Self {
        Shown {
            saturation: flag("saturation", &read),
            exhaustion: flag("exhaustion", &read),
            preview: flag("preview", &read),
        }
    }
    pub fn any(self) -> bool {
        self.saturation || self.exhaustion || self.preview
    }
}

/// The player's hunger bar: hunger (0-20), saturation, exhaustion, the held food (hunger, saturation it gives),
/// the Hunger effect (green shanks), and the time in milliseconds (the preview flashes).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Bar {
    pub food: i32,
    pub saturation: f32,
    pub exhaustion: f32,
    pub held: Option<(u32, f32)>,
    pub hunger_effect: bool,
    pub millis: i64,
}

/// The preview's alpha, 0x50 to 0xE6 and back every 1.5 seconds.
pub fn flash(millis: i64) -> u32 {
    let phase = (millis.rem_euclid(1500)) as f32 / 1500.0 * std::f32::consts::TAU;
    (80.0 + 150.0 * (0.5 - 0.5 * phase.cos())) as u32
}

/// `argb` with alpha `alpha`.
fn with_alpha(argb: u32, alpha: u32) -> u32 {
    (alpha.min(255) << 24) | (argb & 0x00FF_FFFF)
}

/// The gold outline round the shank at (x, y), its edge pixels in rows: lit gold where the edge faces up or left,
/// shaded gold where it faces down or right. `part` (0 to 2) of it shows, from the bone end, as a shank fills;
/// `alpha` fades it (the preview).
pub fn outline(x: i32, y: i32, part: f32, alpha: u32) -> Vec<Draw> {
    let shown_from = 9 - ((part / 2.0) * 9.0).ceil().clamp(1.0, 9.0) as i32;
    let (lit, shade) = (with_alpha(GOLD, alpha), with_alpha(GOLD_SHADE, alpha));
    let mut out = Vec::new();
    for py in 0..9 {
        let mut run: Option<(i32, i32, u32)> = None;
        for px in 0..=9 {
            let color = (px >= shown_from && in_shank(px, py)).then(|| {
                let edge = |dx: i32, dy: i32| !in_shank(px + dx, py + dy);
                let (up, left, down, right) = (edge(0, -1), edge(-1, 0), edge(0, 1), edge(1, 0));
                if up || left {
                    Some(lit)
                } else if down || right {
                    Some(shade)
                } else {
                    None
                }
            });
            let color = color.flatten();
            match (run, color) {
                (Some((start, end, c)), Some(n)) if c == n && end == px => run = Some((start, px + 1, c)),
                _ => {
                    if let Some((start, end, c)) = run.take() {
                        out.push(Draw::Fill { x0: x + start, y0: y + py, x1: x + end, y1: y + py + 1, color: c });
                    }
                    if let Some(n) = color {
                        run = Some((px, px + 1, n));
                    }
                }
            }
        }
    }
    out
}

/// How much of shank `i` (0 is the right one, holding points 1 and 2) `amount` fills: 0 to 2.
fn part(amount: f32, i: usize) -> f32 {
    (amount - 2.0 * i as f32).clamp(0.0, 2.0)
}

/// The shank sprites, for the Hunger effect or not: full, half.
fn shanks(hunger_effect: bool) -> (&'static str, &'static str) {
    if hunger_effect {
        ("hud/food_full_hunger", "hud/food_half_hunger")
    } else {
        ("hud/food_full", "hud/food_half")
    }
}

/// What AppleSkin adds to the hunger bar whose shanks are at `icons` (right one first, as the game drew them):
/// what goes under the shanks (the exhaustion bar) and over them (saturation outlines, the held food's flashing
/// shanks and outlines).
pub fn bar(icons: &[(i32, i32)], b: &Bar, shown: Shown) -> (Vec<Draw>, Vec<Draw>) {
    let (mut under, mut over) = (Vec::new(), Vec::new());
    let Some(&(x0, y0)) = icons.first() else {
        return (under, over);
    };
    if shown.exhaustion && b.exhaustion > 0.0 {
        let width = icons.len() as f32 * 8.0 + 1.0;
        let w = ((b.exhaustion / EXHAUSTION_MAX).clamp(0.0, 1.0) * width).round() as i32;
        if w > 0 {
            under.push(Draw::Fill { x0: x0 + 9 - w, y0: y0 + 1, x1: x0 + 9, y1: y0 + 8, color: EXHAUSTION });
        }
    }
    if shown.saturation {
        for (i, &(x, y)) in icons.iter().enumerate() {
            let p = part(b.saturation, i);
            if p > 0.0 {
                over.extend(outline(x, y, p, 0xFF));
            }
        }
    }
    if let (true, Some((nutrition, saturation))) = (shown.preview, b.held) {
        let alpha = flash(b.millis);
        let food = b.food.clamp(0, 20);
        let new_food = (food + nutrition as i32).min(20);
        let (full, half) = shanks(b.hunger_effect);
        for (i, &(x, y)) in icons.iter().enumerate() {
            let level = 2 * i as i32;
            if new_food >= level + 2 && food < level + 2 {
                over.push(Draw::Sprite { name: full, x, y, color: with_alpha(0xFFFFFF, alpha) });
            } else if new_food == level + 1 && food <= level {
                over.push(Draw::Sprite { name: half, x, y, color: with_alpha(0xFFFFFF, alpha) });
            }
        }
        if shown.saturation {
            let new_saturation = (b.saturation + saturation).min(new_food as f32);
            for (i, &(x, y)) in icons.iter().enumerate() {
                let (old, new) = (part(b.saturation, i), part(new_saturation, i));
                if new > old {
                    over.extend(outline(x, y, new, alpha));
                }
            }
        }
    }
    (under, over)
}

/// The most icons a tooltip row shows; a bigger value ends its row in `x12` instead.
pub const TOOLTIP_ICONS: usize = 10;

fn icons_of(nutrition: u32, saturation: f32) -> (usize, usize) {
    (nutrition.div_ceil(2) as usize, (saturation.max(0.0) / 2.0).ceil() as usize)
}

/// The text after a row that has too many icons to show: `x12` (shanks), `x15` (outlines); None for short rows.
pub fn tooltip_texts(nutrition: u32, saturation: f32) -> [Option<String>; 2] {
    let (h, s) = icons_of(nutrition, saturation);
    [(h > TOOLTIP_ICONS).then(|| format!("x{h}")), (s > TOOLTIP_ICONS).then(|| format!("x{s}"))]
}

/// A food's tooltip rows, from (0, 0): the hunger it restores as shanks, and under them the saturation it gives as
/// gold-outlined shanks. A row with more than TOOLTIP_ICONS icons ends in its count. Saturation 0 has no row.
pub fn tooltip(nutrition: u32, saturation: f32, hunger_effect: bool) -> Vec<Draw> {
    let mut out = Vec::new();
    let (full, half) = shanks(hunger_effect);
    let (h, s) = icons_of(nutrition, saturation);
    let [hunger_text, saturation_text] = tooltip_texts(nutrition, saturation);
    for k in 0..h.min(TOOLTIP_ICONS) {
        let x = (k * 9) as i32;
        out.push(Draw::Sprite { name: "hud/food_empty", x, y: 0, color: 0xFFFF_FFFF });
        let name = if hunger_text.is_some() || 2 * k as u32 + 2 <= nutrition { full } else { half };
        out.push(Draw::Sprite { name, x, y: 0, color: 0xFFFF_FFFF });
    }
    if let Some(text) = hunger_text {
        out.push(Draw::Text { text, x: (TOOLTIP_ICONS * 9) as i32 + 2, y: 1, color: 0xFFFF_FFFF });
    }
    for k in 0..s.min(TOOLTIP_ICONS) {
        let x = (k * 9) as i32;
        out.push(Draw::Sprite { name: "hud/food_empty", x, y: 10, color: 0xFFFF_FFFF });
        let p = if saturation_text.is_some() { 2.0 } else { (saturation - 2.0 * k as f32).clamp(0.0, 2.0) };
        out.extend(outline(x, 10, p, 0xFF));
    }
    if let Some(text) = saturation_text {
        out.push(Draw::Text { text, x: (TOOLTIP_ICONS * 9) as i32 + 2, y: 11, color: GOLD });
    }
    out
}

/// The tooltip's size for a food, given the width of its longest count text (0 when it has none): none for a food
/// that gives nothing.
pub fn tooltip_size(nutrition: u32, saturation: f32, text_width: f32) -> Option<[f32; 2]> {
    let (h, s) = icons_of(nutrition, saturation);
    if h == 0 && s == 0 {
        return None;
    }
    let icons = h.max(s).min(TOOLTIP_ICONS);
    let text = if text_width > 0.0 { 2.0 + text_width } else { 0.0 };
    let height = if s > 0 { 19.0 } else { 9.0 };
    Some([icons as f32 * 9.0 + text, height])
}

/// The /food lines.
pub fn food_report(food: i32, saturation: f32, exhaustion: f32) -> Vec<String> {
    vec![
        format!("Hunger: {food} / 20"),
        format!("Saturation: {saturation:.1} / {food}"),
        format!("Exhaustion: {exhaustion:.2} / {EXHAUSTION_MAX:.0} (each 4 takes a point of saturation, then hunger)"),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn icons() -> Vec<(i32, i32)> {
        (0..10).map(|n| (100 - n * 8, 50)).collect()
    }
    const ALL: Shown = Shown { saturation: true, exhaustion: true, preview: true };

    fn pixels(draws: &[Draw]) -> Vec<(i32, i32, u32)> {
        let mut out = Vec::new();
        for d in draws {
            if let Draw::Fill { x0, y0, x1, y1, color } = d {
                for y in *y0..*y1 {
                    for x in *x0..*x1 {
                        out.push((x, y, *color));
                    }
                }
            }
        }
        out
    }

    #[test]
    fn the_outline_follows_the_shank() {
        let px = pixels(&outline(0, 0, 2.0, 0xFF));
        // Every pixel is on the shank's edge, none twice, lit on top and shaded on the bone's underside.
        for &(x, y, _) in &px {
            assert!(in_shank(x, y));
            assert!([(0, -1), (-1, 0), (0, 1), (1, 0)].iter().any(|(dx, dy)| !in_shank(x + dx, y + dy)), "{x},{y}");
        }
        let mut seen = px.iter().map(|&(x, y, _)| (x, y)).collect::<Vec<_>>();
        seen.dedup();
        assert_eq!(seen.len(), px.len());
        assert!(px.contains(&(2, 0, GOLD)));
        assert!(px.contains(&(7, 8, GOLD_SHADE)));
        // The middle of the meat stays clear: the shank shows through.
        assert!(!px.iter().any(|&(x, y, _)| (x, y) == (3, 3)));
        // Part of a shank: only its bone end.
        let half = pixels(&outline(0, 0, 1.0, 0xFF));
        assert!(!half.is_empty() && half.iter().all(|&(x, _, _)| x >= 4));
        // Faded for the preview.
        assert!(pixels(&outline(0, 0, 2.0, 0x80)).iter().all(|&(_, _, c)| c >> 24 == 0x80));
    }

    #[test]
    fn saturation_outlines_follow_the_shanks() {
        let b = Bar { food: 20, saturation: 5.0, ..Bar::default() };
        let (under, over) = bar(&icons(), &b, Shown { exhaustion: false, preview: false, ..ALL });
        assert!(under.is_empty());
        // 5 saturation: shanks 1 and 2 outlined whole, shank 3 its bone end.
        let px = pixels(&over);
        let whole = pixels(&outline(0, 0, 2.0, 0xFF)).len();
        let bone_end = pixels(&outline(0, 0, 1.0, 0xFF)).len();
        assert_eq!(px.len(), 2 * whole + bone_end);
        assert!(px.iter().all(|p| p.0 >= 84 + 4));
        let none = bar(&icons(), &Bar { food: 20, ..Bar::default() }, ALL);
        assert!(none.0.is_empty() && none.1.is_empty());
    }

    #[test]
    fn exhaustion_fills_from_the_right() {
        let b = Bar { food: 20, exhaustion: 2.0, ..Bar::default() };
        let (under, _) = bar(&icons(), &b, ALL);
        assert_eq!(under, vec![Draw::Fill { x0: 109 - 41, y0: 51, x1: 109, y1: 58, color: EXHAUSTION }]);
        let full = bar(&icons(), &Bar { exhaustion: 9.0, ..b }, ALL).0;
        assert_eq!(full, vec![Draw::Fill { x0: 109 - 81, y0: 51, x1: 109, y1: 58, color: EXHAUSTION }]);
    }

    #[test]
    fn the_held_food_flashes_what_it_restores() {
        // Hunger 13 (6 and a half shanks), a steak: +8 hunger (to 20 max), +12.8 saturation.
        let b = Bar { food: 13, saturation: 0.0, held: Some((8, 12.8)), millis: 750, ..Bar::default() };
        let (_, over) = bar(&icons(), &b, Shown { saturation: false, exhaustion: false, preview: true });
        let sprites: Vec<_> = over.iter().filter_map(|d| match d { Draw::Sprite { name, x, .. } => Some((*name, *x)), _ => None }).collect();
        // Shanks 7 (half now) to 10 become full.
        assert_eq!(sprites, vec![("hud/food_full", 100 - 6 * 8), ("hud/food_full", 100 - 7 * 8), ("hud/food_full", 100 - 8 * 8), ("hud/food_full", 100 - 9 * 8)]);
        assert!(over.iter().all(|d| matches!(d, Draw::Sprite { color, .. } if *color >> 24 == flash(750))));
        // An odd gain ends on a half shank, green under the Hunger effect.
        let b = Bar { food: 4, held: Some((1, 0.6)), hunger_effect: true, ..b };
        let (_, over) = bar(&icons(), &b, Shown { saturation: false, exhaustion: false, preview: true });
        assert!(matches!(over[..], [Draw::Sprite { name: "hud/food_half_hunger", x: 84, .. }]));
    }

    #[test]
    fn the_held_food_saturation_stops_at_the_new_hunger() {
        let b = Bar { food: 2, saturation: 0.0, held: Some((2, 20.0)), ..Bar::default() };
        let (_, over) = bar(&icons(), &b, ALL);
        // New hunger 4: saturation can't pass 4, so two shanks' outlines flash, no more.
        let px = pixels(&over);
        assert!(!px.is_empty() && px.iter().all(|p| p.0 >= 92));
        assert!(px.iter().all(|p| p.2 >> 24 == flash(0)));
    }

    #[test]
    fn flashing_stays_visible() {
        for t in (0..3000).step_by(37) {
            let a = flash(t);
            assert!((80..=230).contains(&a), "{a}");
        }
        assert!(flash(0) < flash(750));
    }

    #[test]
    fn tooltips_show_the_food_values() {
        assert_eq!(tooltip_size(0, 0.0, 0.0), None);
        // Apple: 4 hunger, 2.4 saturation: 2 shanks, 2 outlined shanks, no text.
        assert_eq!(tooltip_texts(4, 2.4), [None, None]);
        assert_eq!(tooltip_size(4, 2.4, 0.0), Some([18.0, 19.0]));
        let d = tooltip(4, 2.4, false);
        assert_eq!(d.iter().filter(|d| matches!(d, Draw::Sprite { name: "hud/food_full", .. })).count(), 2);
        assert!(!d.iter().any(|d| matches!(d, Draw::Text { .. })));
        assert!(pixels(&d).iter().all(|p| p.1 >= 10));
        // An odd value ends on a half shank; no saturation, one row.
        assert!(tooltip(5, 0.0, false).iter().any(|d| matches!(d, Draw::Sprite { name: "hud/food_half", x: 18, .. })));
        assert_eq!(tooltip_size(5, 0.0, 0.0), Some([27.0, 9.0]));
        // Many: 10 icons, then the count.
        assert_eq!(tooltip_texts(40, 30.0), [Some("x20".into()), Some("x15".into())]);
        let big = tooltip(40, 30.0, false);
        assert_eq!(big.iter().filter(|d| matches!(d, Draw::Sprite { name: "hud/food_empty", y: 0, .. })).count(), TOOLTIP_ICONS);
        assert!(big.contains(&Draw::Text { text: "x20".into(), x: 92, y: 1, color: 0xFFFF_FFFF }));
        assert_eq!(tooltip_size(40, 30.0, 12.0), Some([104.0, 19.0]));
    }

    #[test]
    fn food_report_reads_well() {
        let r = food_report(17, 3.25, 1.5);
        assert_eq!(r[0], "Hunger: 17 / 20");
        assert_eq!(r[1], "Saturation: 3.2 / 17");
        assert!(r[2].starts_with("Exhaustion: 1.50 / 4"));
    }

    fn here() -> Info {
        Info::new((12.34, 64.0, -7.5, 180.0, -10.0, "minecraft:the_nether"), 144)
    }

    #[test]
    fn placeholders_fill_in() {
        let i = here();
        assert_eq!(fill("XYZ {x} {y} {z}", Some(&i)), "XYZ 12.3 64.0 -7.5");
        assert_eq!(fill("{bx} {by} {bz}", Some(&i)), "12 64 -8");
        assert_eq!(fill("{facing} in the {dimension}", Some(&i)), "North (-Z) in the Nether");
        assert_eq!(fill("{fps} fps", Some(&i)), "144 fps");
        assert_eq!(fill("{nope} {x", Some(&i)), "{nope} {x");
        assert_eq!(fill("{x} {name}", None), format!("? {NAME}"));
    }

    #[test]
    fn facing_follows_minecraft() {
        assert_eq!(facing(0.0), "South (+Z)");
        assert_eq!(facing(90.0), "West (-X)");
        assert_eq!(facing(-90.0), "East (+X)");
        assert_eq!(facing(179.0), "North (-Z)");
    }

    #[test]
    fn the_help_command_and_commands_are_found() {
        assert_eq!(command("appleskin"), Some(Action::Help));
        assert_eq!(command("appleskin extra words"), Some(Action::Help));
        for (name, action) in COMMANDS {
            assert_eq!(command(&name.to_uppercase()), Some(*action));
        }
        assert_eq!(command("not_a_command_of_this_mod"), None);
        assert!(help(|_, d| key_label(d)).len() >= 1);
    }

    #[test]
    fn every_key_has_an_action() {
        for [name, _, default] in KEYS {
            assert!(key_action(name).is_some(), "{name}");
            assert!(default.starts_with("key."), "{default}");
        }
        assert_eq!(key_label("key.keyboard.left.shift"), "Left Shift");
    }

    #[test]
    fn options_start_on_and_keep_their_value() {
        for &(key, _) in TOGGLES {
            assert!(flag(key, |_| None));
            set_flag(key, false);
            assert!(!flag(key, |_| Some("true".into())));
            set_flag(key, true);
        }
        assert!(!flag("stored_off", |_| Some("false".into())));
    }

    #[test]
    fn zoom_goes_in_and_out() {
        assert_eq!(zoom_now(), None);
        set_zoom(Some(true));
        assert_eq!(zoom_now(), Some(ZOOM_FACTOR));
        assert_eq!(zoom_wheel(1.0), ZOOM_SCROLL);
        set_zoom(None);
        assert_eq!(zoom_now(), None);
        assert!(zoom_turn(4.0) < 1.0);
    }

    #[test]
    fn welcome_once_per_join_and_timers_on_time() {
        assert!(tick(false, true, true).is_empty());
        assert_eq!(tick(true, true, false), WELCOME.to_vec());
        assert!(tick(true, true, false).is_empty());
        tick(false, true, true);
        let mut said = 0;
        for _ in 0..20 * 60 * 60 {
            said += tick(true, false, true).len();
        }
        let want: usize = TIMERS.iter().map(|(e, l)| (3600 / *e as usize) * l.len()).sum();
        assert_eq!(said, want);
    }
}
