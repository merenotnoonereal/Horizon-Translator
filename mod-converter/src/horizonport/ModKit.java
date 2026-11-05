package horizonport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod kit: makes a new Horizon native mod from a spec (the app's "Make a mod" builder writes one; `horizon-kit.json`
 * in each project keeps it). The mod uses only Horizon's native hook points (hooks API 1), so the launcher builds it on
 * any Horizon that has them, newer nightlies included.
 *
 * The spec (JSON): name, id, version, description, author, and any of
 *   hud      {corner: top_left|top_right|bottom_left|bottom_right, lines: [..], color: "ffffff", background: true, key: "key.keyboard.h"}
 *   commands [{name: "hello", action: say|toggle_hud|toggle_zoom, text: [..]}]
 *   keys     [{label: "Say hi", key: "key.keyboard.g", action: say|toggle_hud|toggle_zoom, text: [..]}]
 *   zoom     {key: "key.keyboard.c", factor: 4, hold: true, smooth: true, scroll: true}
 *   welcome  [..lines said in chat on joining a world]
 *   timers   [{every: 300, text: [..]}]   (seconds)
 * Text may hold {x} {y} {z} {bx} {by} {bz} {facing} {yaw} {pitch} {dimension} {fps} {name} {version}.
 */
final class ModKit {
    private ModKit() {}

    static final int HOOKS_API = 1;
    static final String GAME = "26.3";

    /** The placeholders text may use, and what each shows. */
    static final String[][] PLACEHOLDERS = {
            {"x", "X position, one decimal"}, {"y", "Y position"}, {"z", "Z position"},
            {"bx", "block X"}, {"by", "block Y"}, {"bz", "block Z"},
            {"facing", "North (-Z), East (+X)…"}, {"yaw", "yaw in degrees"}, {"pitch", "pitch in degrees"},
            {"dimension", "Overworld, Nether, End"}, {"fps", "frames per second"},
            {"name", "the mod's name"}, {"version", "the mod's version"},
    };

    record Made(Path project, Path hnmod, List<String> notes) {}

    // ------------------------------------------------------------------ the spec

    record Act(String kind, List<String> text) {} // kind: say, toggle_hud, toggle_zoom
    record Cmd(String name, Act act) {}
    record Key(String name, String label, String key, Act act) {}
    record Timer(int every, List<String> text) {}
    record Hud(int corner, List<String> lines, int ink, boolean background) {}
    record Zoom(float factor, boolean hold, boolean smooth, boolean scroll) {}

    static final class Spec {
        String name, id, version, description, author;
        Hud hud;
        Zoom zoom;
        final List<Cmd> commands = new ArrayList<>();
        final List<Key> keys = new ArrayList<>();
        final List<String> welcome = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
    }

    static final Pattern KEY = Pattern.compile("key\\.(keyboard\\.[a-z0-9.]+|mouse\\.(left|right|middle|[0-9]+))");
    static final Set<String> CORNERS = Set.of("top_left", "top_right", "bottom_left", "bottom_right");

    /** Reads and checks a spec. Problems are collected in `problems`; the spec is only usable when there are none. */
    static Spec parse(Map<String, Object> j) {
        Spec s = new Spec();
        s.name = str(j.get("name"), "").strip();
        if (s.name.isEmpty()) s.problems.add("The mod needs a name.");
        String id = str(j.get("id"), "").strip();
        s.id = id.isEmpty() ? idFrom(s.name) : id;
        if (!s.id.matches("[a-z][a-z0-9_]{1,63}"))
            s.problems.add("The mod id `" + s.id + "` must be 2 to 64 of a-z, 0-9 and _, starting with a letter.");
        s.version = str(j.get("version"), "1.0.0").strip();
        if (!s.version.matches("[0-9A-Za-z.+-]{1,32}")) s.problems.add("The version `" + s.version + "` may only use letters, digits and . + -");
        s.description = str(j.get("description"), "").strip();
        s.author = str(j.get("author"), "").strip();

        if (j.get("hud") instanceof Map<?, ?> h) {
            String corner = str(h.get("corner"), "top_left");
            if (!CORNERS.contains(corner)) s.problems.add("HUD corner `" + corner + "` isn't one of " + CORNERS + ".");
            List<String> lines = lines(h.get("lines"));
            if (lines.isEmpty()) s.problems.add("The HUD box needs at least one line of text.");
            int ink = color(str(h.get("color"), "ffffff"), s);
            s.hud = new Hud(List.of("top_left", "top_right", "bottom_left", "bottom_right").indexOf(corner), lines, ink, bool(h.get("background"), true));
            String key = str(h.get("key"), "");
            if (!key.isEmpty()) addKey(s, "hud", "Show or hide the HUD box", key, new Act("toggle_hud", List.of()));
        }
        if (j.get("zoom") instanceof Map<?, ?> z) {
            float f = (float) num(z.get("factor"), 4);
            if (!(f >= 1.5f && f <= 10f)) s.problems.add("Zoom must be between 1.5 and 10 times.");
            s.zoom = new Zoom(f, bool(z.get("hold"), true), bool(z.get("smooth"), true), bool(z.get("scroll"), true));
            addKey(s, "zoom", "Zoom", str(z.get("key"), "key.keyboard.c"), new Act(s.zoom.hold() ? "hold_zoom" : "toggle_zoom", List.of()));
        }
        int n = 0;
        for (Object o : list(j.get("keys"))) {
            Map<?, ?> k = (Map<?, ?>) o;
            n++;
            String label = str(k.get("label"), "Key " + n).strip();
            addKey(s, "key" + n, label.isEmpty() ? "Key " + n : label, str(k.get("key"), ""), act(k, s, "Key \"" + label + "\""));
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object o : list(j.get("commands"))) {
            Map<?, ?> c = (Map<?, ?>) o;
            String name = str(c.get("name"), "").strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]{1,32}")) { s.problems.add("The command `/" + name + "` may only use a-z, 0-9, _ and -."); continue; }
            if (name.equals(s.id)) { s.problems.add("`/" + name + "` is the mod's own help command: pick another name."); continue; }
            if (!names.add(name)) { s.problems.add("There are two `/" + name + "` commands."); continue; }
            s.commands.add(new Cmd(name, act(c, s, "/" + name)));
        }
        s.welcome.addAll(lines(j.get("welcome")));
        for (Object o : list(j.get("timers"))) {
            Map<?, ?> t = (Map<?, ?>) o;
            int every = (int) num(t.get("every"), 300);
            List<String> text = lines(t.get("text"));
            if (every < 5 || every > 86400) s.problems.add("A timed message must repeat every 5 seconds to 24 hours.");
            else if (text.isEmpty()) s.problems.add("A timed message has no text.");
            else s.timers.add(new Timer(every, text));
        }
        boolean zoomAct = s.keys.stream().anyMatch(k -> k.act().kind().contains("zoom")) || s.commands.stream().anyMatch(c -> c.act().kind().contains("zoom"));
        boolean hudAct = s.keys.stream().anyMatch(k -> k.act().kind().equals("toggle_hud")) || s.commands.stream().anyMatch(c -> c.act().kind().equals("toggle_hud"));
        if (zoomAct && s.zoom == null) s.problems.add("Something zooms, but the zoom feature isn't on.");
        if (hudAct && s.hud == null) s.problems.add("Something shows or hides the HUD box, but there is no HUD box.");
        return s;
    }

    private static void addKey(Spec s, String name, String label, String key, Act act) {
        key = key.strip().toLowerCase(Locale.ROOT);
        if (key.matches("[a-z0-9]")) key = "key.keyboard." + key;
        if (!KEY.matcher(key).matches()) { s.problems.add("\"" + label + "\" needs a key like key.keyboard.g (got `" + key + "`)."); return; }
        s.keys.add(new Key(name, label, key, act));
    }

    private static Act act(Map<?, ?> m, Spec s, String what) {
        String kind = str(m.get("action"), "say");
        List<String> text = lines(m.get("text"));
        if (!Set.of("say", "toggle_hud", "toggle_zoom").contains(kind)) s.problems.add(what + ": unknown action `" + kind + "`.");
        if (kind.equals("say") && text.isEmpty()) s.problems.add(what + " says nothing: give it some text.");
        return new Act(kind, text);
    }

    private static int color(String hex, Spec s) {
        hex = hex.strip().replaceFirst("^#", "");
        if (!hex.matches("[0-9a-fA-F]{6}")) { s.problems.add("The colour `" + hex + "` isn't like ffffff."); return 0xFFFFFF; }
        return Integer.parseInt(hex, 16);
    }

    static String idFrom(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty() || !Character.isLetter(id.charAt(0))) id = "mod_" + id;
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    private static String str(Object o, String dflt) { return o == null ? dflt : String.valueOf(o); }
    private static boolean bool(Object o, boolean dflt) { return o instanceof Boolean b ? b : dflt; }
    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? dflt : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return Double.NaN; }
    }
    private static List<?> list(Object o) { return o instanceof List<?> l ? l : List.of(); }
    /** Text as lines: a list, or one string split at line breaks. Blank lines at the ends are dropped. */
    private static List<String> lines(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.addAll(List.of(String.valueOf(x).split("\r?\n", -1)));
        else if (o != null) out.addAll(List.of(String.valueOf(o).split("\r?\n", -1)));
        while (!out.isEmpty() && out.get(0).isBlank()) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    // ------------------------------------------------------------------ writing the project

    /**
     * Writes the project into `dir` (which must not exist yet or be empty), packs `<id>.hnmod` in it, and installs that
     * into the game folder `install` when given. horizonSrc: Horizon's source (for the commit it was made on and the
     * horizon-mixins commit); without it no .hnmod is packed.
     */
    static Made make(Map<String, Object> json, Path dir, Path horizonSrc, Path install) throws IOException {
        Spec s = parse(json);
        if (!s.problems.isEmpty()) throw new IllegalArgumentException("The mod can't be made yet:\n- " + String.join("\n- ", s.problems));
        if (Files.isDirectory(dir)) try (var l = Files.list(dir)) {
            if (l.findAny().isPresent()) throw new IllegalArgumentException(dir + " isn't empty: pick a new folder (the kit never writes over your code)");
        }
        Path mod = dir.resolve("mixins").resolve(s.id);
        Files.createDirectories(mod);
        String revision = null, mixins = null;
        if (horizonSrc != null) {
            Path r = horizonSrc.resolve(".horizon-revision");
            if (Files.isRegularFile(r) && Text.read(r).strip().matches("[0-9a-f]{40}")) revision = Text.read(r).strip();
            Path nm = horizonSrc.resolve("launcher/src/native_mods.rs");
            if (Files.isRegularFile(nm)) {
                Matcher mm = Pattern.compile("MIXINS_REV: &str = \"([0-9a-f]{40})\"").matcher(Text.read(nm));
                if (mm.find()) mixins = mm.group(1);
            }
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put("runtime.rs", runtime(s));
        files.put("list.rs", listRs(s));
        if (s.hud != null) files.put("hud.rs", hudRs(s));
        if (!s.keys.isEmpty()) files.put("key.rs", keyRs(s));
        files.put("command.rs", commandRs(s)); // the /<id> help command is always there
        if (s.zoom != null) {
            files.put("zoom.rs", zoomRs(s));
            if (s.zoom.scroll()) files.put("wheel.rs", wheelRs(s));
        }
        if (!s.welcome.isEmpty() || !s.timers.isEmpty() || s.zoom != null) files.put("tick.rs", tickRs(s));
        if (!toggles(s).isEmpty()) {
            files.put("settings.rs", settingsRs(s));
            files.put("click.rs", clickRs(s));
        }
        files.put("horizon-mixin.toml", mixinToml(s, files.keySet(), revision));
        for (Map.Entry<String, String> e : files.entrySet()) write(mod.resolve(e.getKey()), e.getValue());

        write(dir.resolve("Cargo.toml"), "[package]\nname = \"horizon-mod-" + s.id.replace('_', '-') + "\"\nversion = \"" + cargoVersion(s.version) + "\"\nedition = \"2024\"\npublish = false\n"
                + "description = " + tomlStr(s.name + (s.description.isEmpty() ? "" : ": " + s.description)) + "\n\n"
                + "# The mod's logic and its tests (`cargo test`): the same file the bundle adds to the game.\n[lib]\npath = \"mixins/" + s.id + "/runtime.rs\"\n\n"
                + "# Not part of any other workspace.\n[workspace]\n");
        Map<String, Object> compat = new LinkedHashMap<>();
        compat.put("game", GAME);
        compat.put("hooks", HOOKS_API);
        compat.put("horizon_repository", "https://github.com/horizon-rust/horizon");
        if (revision != null) compat.put("horizon_revision", revision);
        compat.put("horizon_mixins_repository", "https://github.com/horizon-rust/horizon-mixins");
        if (mixins != null) compat.put("horizon_mixins_revision", mixins);
        write(dir.resolve("compatibility.json"), Json.write(compat) + "\n");
        write(dir.resolve(".gitignore"), "/target\n*.hnmod\n*.hnmod.part\nHOOKS.md\n");
        if (horizonSrc != null && Files.isRegularFile(horizonSrc.resolve(HookList.HOOKS_FILE))) {
            try {
                write(dir.resolve("HOOKS.md"), HookList.markdown(HookList.read(horizonSrc), HookList.revision(horizonSrc)));
            } catch (RuntimeException e) {
                // The list is a help, not part of the mod: a Horizon it can't read just goes without.
            }
        }
        write(dir.resolve("horizon-kit.json"), Json.write(json) + "\n");
        write(dir.resolve("README.md"), readme(s));
        write(dir.resolve("AGENTS.md"), agents(s));

        List<String> notes = new ArrayList<>();
        Path hnmod = null;
        if (horizonSrc != null) {
            NativePackage.Outcome o = NativePackage.write(dir, null, s.id, s.version, GAME, revision, mixins, HOOKS_API, List.of());
            if (o.full()) hnmod = o.file();
            else notes.addAll(o.gaps());
        } else notes.add("No Horizon source given, so no .hnmod was packed (the launcher can still build the folder).");
        if (hnmod != null && install != null) {
            Path to = NativePackage.install(hnmod, s.id, install);
            notes.add("installed as " + to);
        }
        return new Made(dir, hnmod, notes);
    }

    private static void write(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }

    private static String cargoVersion(String v) {
        return v.matches("\\d+\\.\\d+\\.\\d+([-+].*)?") ? v : v.matches("\\d+\\.\\d+") ? v + ".0" : v.matches("\\d+") ? v + ".0.0" : "0.1.0";
    }

    /** The ON/OFF options the settings page shows: key, label. */
    static List<String[]> toggles(Spec s) {
        List<String[]> t = new ArrayList<>();
        if (s.hud != null) t.add(new String[] {"hud", "Show the HUD box"});
        if (s.zoom != null) t.add(new String[] {"zoom", "Zoom"});
        if (!s.welcome.isEmpty()) t.add(new String[] {"welcome", "Welcome message"});
        if (!s.timers.isEmpty()) t.add(new String[] {"timers", "Timed messages"});
        return t;
    }

    // ------------------------------------------------------------------ Rust

    private static String module(Spec s) { return "kit_" + s.id; }

    /** A Rust string literal. "TODO" never appears in it as written (Horizon Translator treats a TODO as unfinished work). */
    static String rs(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u{%x}", (int) c));
                    else if (c == 'D' && i >= 2 && v.startsWith("TOD", i - 2) && v.startsWith("DO", i)) b.append("\\u{44}");
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String rsList(List<String> lines) {
        if (lines.isEmpty()) return "&[]";
        StringBuilder b = new StringBuilder("&[");
        for (int i = 0; i < lines.size(); i++) b.append(i == 0 ? "" : ", ").append(rs(lines.get(i)));
        return b.append(']').toString();
    }

    private static String rsAct(Act a) {
        return switch (a.kind()) {
            case "toggle_hud" -> "Action::ToggleHud";
            case "toggle_zoom" -> "Action::ToggleZoom";
            case "hold_zoom" -> "Action::HoldZoom";
            default -> "Action::Say(" + rsList(a.text()) + ")";
        };
    }

    private static String rsFloat(float f) {
        String v = Float.toString(f);
        return v.contains(".") || v.contains("E") ? v : v + ".0";
    }

    private static String comment(String s) { return s.replace("TODO", "To do").replace("\n", " "); }

    static String runtime(Spec s) {
        StringBuilder b = new StringBuilder();
        b.append("// ").append(comment(s.name)).append(": a Horizon native mod made with Horizon Translator's mod kit.\n");
        b.append("""
                // This file is both the mod's Cargo library (`cargo test` runs the tests at the bottom without the game)
                // and the module the bundle adds to the game (crate::game::%s). The small files beside it (key.rs, hud.rs...)
                // are the hooks: Horizon calls them, and they call the functions here. Change anything you like; the guide
                // (Make a mod > Guide in Horizon Translator) says what each hook and game helper does.
                // Module files can't hold `//!` comments or inner attributes: keep to plain `//` comments.

                use std::cell::{Cell, RefCell};
                use std::collections::HashMap;

                """.formatted(module(s)));
        b.append("/// The mod's id (its settings folder and its keys' lines), name, version and description.\n");
        b.append("pub const ID: &str = ").append(rs(s.id)).append(";\n");
        b.append("pub const NAME: &str = ").append(rs(s.name)).append(";\n");
        b.append("pub const VERSION: &str = ").append(rs(s.version)).append(";\n");
        b.append("pub const DESCRIPTION: &str = ").append(rs(s.description)).append(";\n");
        b.append("pub const AUTHOR: &str = ").append(rs(s.author)).append(";\n\n");

        b.append("/// The mod's keys, as Options > Mods lists them: name, label, default key. Players can change them there.\n");
        b.append("pub const KEYS: &[[&str; 3]] = &[\n");
        for (Key k : s.keys) b.append("    [").append(rs(k.name())).append(", ").append(rs(k.label())).append(", ").append(rs(k.key())).append("],\n");
        b.append("];\n\n");

        b.append("""
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
                    /// Lists the mod's commands and keys (the /%s command).
                    Help,
                }

                """.formatted(s.id));
        b.append("/// What each key (by its name in KEYS) does.\npub const KEY_ACTIONS: &[(&str, Action)] = &[\n");
        for (Key k : s.keys) b.append("    (").append(rs(k.name())).append(", ").append(rsAct(k.act())).append("),\n");
        b.append("];\n\n");
        b.append("/// The mod's chat commands, without the slash, and what they do. /").append(s.id).append(" (the help) is added by `command`.\n");
        b.append("pub const COMMANDS: &[(&str, Action)] = &[\n");
        for (Cmd c : s.commands) b.append("    (").append(rs(c.name())).append(", ").append(rsAct(c.act())).append("),\n");
        b.append("];\n\n");

        Hud h = s.hud;
        b.append("/// The HUD box: its corner (0 top left, 1 top right, 2 bottom left, 3 bottom right), its lines, the text colour\n");
        b.append("/// (0xRRGGBB) and the box behind it (0xAARRGGBB, 0 for none).\n");
        b.append("pub const HUD_CORNER: usize = ").append(h == null ? 0 : h.corner()).append(";\n");
        b.append("pub const HUD_LINES: &[&str] = ").append(h == null ? "&[]" : rsList(h.lines())).append(";\n");
        b.append("pub const HUD_INK: u32 = 0x").append(String.format("%06X", h == null ? 0xFFFFFF : h.ink())).append(";\n");
        b.append("pub const HUD_BACKGROUND: u32 = ").append(h == null || h.background() ? "0x7800_0000" : "0").append(";\n\n");

        Zoom z = s.zoom;
        b.append("/// Zoom: how many times closer, eased like the spyglass (smooth), and whether the mouse wheel changes it while zooming.\n");
        b.append("pub const ZOOM_FACTOR: f32 = ").append(rsFloat(z == null ? 4f : z.factor())).append(";\n");
        b.append("pub const ZOOM_SMOOTH: bool = ").append(z == null || z.smooth()).append(";\n");
        b.append("pub const ZOOM_SCROLL: bool = ").append(z != null && z.scroll()).append(";\n\n");

        b.append("/// Said in chat on joining a world.\npub const WELCOME: &[&str] = ").append(rsList(s.welcome)).append(";\n\n");
        b.append("/// Said in chat every so many seconds while in a world.\npub const TIMERS: &[(u32, &[&str])] = &[\n");
        for (Timer t : s.timers) b.append("    (").append(t.every()).append(", ").append(rsList(t.text())).append("),\n");
        b.append("];\n\n");

        b.append("/// The ON/OFF options of the mod's settings page (Options > Mods > ").append(comment(s.name)).append("): key, label. All start ON.\n");
        b.append("pub const TOGGLES: &[(&str, &str)] = &[\n");
        for (String[] t : toggles(s)) b.append("    (").append(rs(t[0])).append(", ").append(rs(t[1])).append("),\n");
        b.append("];\n\n");

        b.append(RUNTIME_BODY.replace("%ID%", s.id));
        return b.toString();
    }

    /** The mod's logic, the same for every kit mod (the tables above say what it does). */
    static final String RUNTIME_BODY = """
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

            /// The /%ID% lines: the mod, its commands and its keys. `key_label` gives the key a player chose.
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

            #[cfg(test)]
            mod tests {
                use super::*;

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
                    assert_eq!(command("%ID%"), Some(Action::Help));
                    assert_eq!(command("%ID% extra words"), Some(Action::Help));
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
            """;

    // ------------------------------------------------------------------ the hooks

    private static String head(Spec s, String what) {
        return "    use crate::game::" + module(s) + " as m;\n"
                + "    // Turned off in Options > Mods: nothing until the launcher's next build leaves the mod out.\n"
                + "    if !game.native_on(m::ID) {\n        return;\n    }\n";
    }

    /** The player's Info for the placeholders, or None outside a world. */
    private static final String INFO = "game.native_player().map(|p| m::Info::new((p.x, p.y, p.z, p.yaw, p.pitch, p.dimension), game.native_frame_rate().0))";

    static String listRs(Spec s) {
        return "// The mod's row in Options > Mods, and its keys (name, label, default key).\n"
                + "fn " + s.id + "_listed(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {\n"
                + "    use crate::game::" + module(s) + " as m;\n"
                + "    mods.push(crate::game::native_hooks::NativeMod {\n"
                + "        id: m::ID.into(),\n        title: format!(\"{} {}\", m::NAME, m::VERSION),\n        description: m::DESCRIPTION.into(),\n"
                + "        keys: m::KEYS.iter().map(|[n, l, d]| [n.to_string(), l.to_string(), d.to_string()]).collect(),\n    });\n}\n";
    }

    static String hudRs(Spec s) {
        return "// The HUD box: its lines (placeholders filled in) in its corner, under F1 hidden like the rest of the HUD.\n"
                + "fn " + s.id + "_hud(\n    game: &mut crate::game::Game<'_>,\n    batch: &mut horizon_client::hud::HudBatch,\n"
                + "    corners: &mut horizon_client::extras_hud::Corners,\n) {\n" + head(s, "hud")
                + "    use horizon_client::extras::Corner;\n"
                + "    let corner = [Corner::TopLeft, Corner::TopRight, Corner::BottomLeft, Corner::BottomRight][m::HUD_CORNER];\n"
                + "    // F3's debug screen takes the top corners.\n"
                + "    if !game.native_corner_shown(corner) || !m::flag(\"hud\", |k| game.native_setting(m::ID, k)) {\n        return;\n    }\n"
                + "    let Some(info) = " + INFO + " else {\n        return;\n    };\n"
                + "    let lines: Vec<String> = m::HUD_LINES.iter().map(|l| m::fill(l, Some(&info))).collect();\n"
                + "    let w = lines.iter().map(|l| game.native_text_width(l)).fold(0.0, f32::max) + 4.0;\n"
                + "    let h = 10.0 * lines.len() as f32 + 2.0;\n"
                + "    let (x, y) = corners.place(corner, w, h);\n"
                + "    if m::HUD_BACKGROUND != 0 {\n        game.native_fill(batch, [x, y, w, h], m::HUD_BACKGROUND);\n    }\n"
                + "    for (i, line) in lines.iter().enumerate() {\n"
                + "        game.native_text(batch, line, [x + 2.0, y + 2.0 + 10.0 * i as f32], m::HUD_INK, true);\n    }\n}\n";
    }

    static String keyRs(Spec s) {
        return "// The mod's keys (KEYS in runtime.rs): the player picks them in Options > Mods.\n"
                + "fn " + s.id + "_key(game: &mut crate::game::Game<'_>, key: &str, pressed: bool) {\n" + head(s, "key")
                + "    for [name, _, default] in m::KEYS {\n"
                + "        if key != game.native_mod_key(m::ID, name, default) {\n            continue;\n        }\n"
                + "        let Some(action) = m::key_action(name) else {\n            continue;\n        };\n"
                + "        if action == m::Action::HoldZoom {\n"
                + "            // Let go: always back out, even if a screen opened meanwhile.\n"
                + "            if !pressed || game.native_playing() && m::flag(\"zoom\", |k| game.native_setting(m::ID, k)) {\n"
                + "                m::set_zoom(Some(pressed));\n            }\n            continue;\n        }\n"
                + "        // The other keys act on the press, while playing (not typing in chat or a screen).\n"
                + "        if !pressed || !game.native_playing() {\n            continue;\n        }\n"
                + DO_ACTION.replace("$IND", "        ")
                + "    }\n}\n";
    }

    /** Runs `action` (in scope: game, m, action). $IND: the indent. */
    private static final String DO_ACTION = """
            $INDmatch action {
            $IND    m::Action::Say(lines) => {
            $IND        let info = %s;
            $IND        for line in lines {
            $IND            game.native_chat(&m::fill(line, info.as_ref()));
            $IND        }
            $IND    }
            $IND    m::Action::ToggleHud => {
            $IND        let on = !m::flag("hud", |k| game.native_setting(m::ID, k));
            $IND        m::set_flag("hud", on);
            $IND        game.native_set_setting(m::ID, "hud", m::stored(on));
            $IND    }
            $IND    m::Action::ToggleZoom | m::Action::HoldZoom => {
            $IND        if m::flag("zoom", |k| game.native_setting(m::ID, k)) {
            $IND            m::set_zoom(None);
            $IND        }
            $IND    }
            $IND    m::Action::Help => {
            $IND        let lines = m::help(|name, default| m::key_label(&game.native_mod_key(m::ID, name, default)));
            $IND        for line in lines {
            $IND            game.native_chat(&line);
            $IND        }
            $IND    }
            $IND}
            """.formatted(INFO);

    static String commandRs(Spec s) {
        return "// The mod's chat commands (COMMANDS in runtime.rs) and /" + s.id + ", which lists them. Horizon asks before the\n"
                + "// server sees a command: `ran` keeps it from going on.\n"
                + "fn " + s.id + "_command(game: &mut crate::game::Game<'_>, command: &str, ran: &mut bool) {\n" + head(s, "command")
                + "    let Some(action) = m::command(command) else {\n        return;\n    };\n    *ran = true;\n"
                + DO_ACTION.replace("$IND", "    ")
                + (s.hud != null ? "    if action == m::Action::ToggleHud {\n        let on = m::flag(\"hud\", |k| game.native_setting(m::ID, k));\n"
                        + "        game.native_chat(if on { \"HUD box shown\" } else { \"HUD box hidden\" });\n    }\n" : "")
                + "}\n";
    }

    static String zoomRs(Spec s) {
        return "// The zoom the mod asks for while zooming; Horizon applies it while playing (the spyglass wins).\n"
                + "fn " + s.id + "_zoom(game: &crate::game::Game<'_>, zoom: &mut Option<crate::game::native_hooks::NativeZoom>) {\n"
                + "    use crate::game::" + module(s) + " as m;\n"
                + "    if zoom.is_some() || !game.native_on(m::ID) {\n        return;\n    }\n"
                + "    if let Some(factor) = m::zoom_now() {\n"
                + "        *zoom = Some(crate::game::native_hooks::NativeZoom {\n            factor,\n            smooth: m::ZOOM_SMOOTH,\n            turn: m::zoom_turn(factor),\n        });\n    }\n}\n";
    }

    static String wheelRs(Spec s) {
        return "// The mouse wheel while zooming zooms closer or further instead of changing the hotbar slot.\n"
                + "fn " + s.id + "_wheel(game: &mut crate::game::Game<'_>, steps: f32, claimed: &mut bool) {\n" + head(s, "wheel")
                + "    if !*claimed && m::zoom_wheel(steps) {\n        *claimed = true;\n    }\n}\n";
    }

    static String tickRs(Spec s) {
        return "// Once a tick (20 a second): the welcome on joining a world and the timed messages. Servers' own games tick too,\n"
                + "// so this only runs in the window's.\n"
                + "fn " + s.id + "_tick(game: &mut crate::game::Game<'_>) {\n" + head(s, "tick")
                + "    if !game.native_window() {\n        return;\n    }\n"
                + "    let info = " + INFO + ";\n"
                + (s.zoom != null ? "    // Zooming stops when the world goes or the option is turned off.\n"
                        + "    if m::zoom_now().is_some() && (info.is_none() || !m::flag(\"zoom\", |k| game.native_setting(m::ID, k))) {\n        m::set_zoom(Some(false));\n    }\n" : "")
                + "    let welcome = " + (s.welcome.isEmpty() ? "false" : "m::flag(\"welcome\", |k| game.native_setting(m::ID, k))") + ";\n"
                + "    let timers = " + (s.timers.isEmpty() ? "false" : "m::flag(\"timers\", |k| game.native_setting(m::ID, k))") + ";\n"
                + "    for line in m::tick(info.is_some(), welcome, timers) {\n        game.native_chat(&m::fill(line, info.as_ref()));\n    }\n}\n";
    }

    static String settingsRs(Spec s) {
        return "// The mod's settings page (Options > Mods): one ON/OFF button per option of TOGGLES, under its keys.\n"
                + "fn " + s.id + "_settings(game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {\n"
                + "    use crate::game::" + module(s) + " as m;\n    if mod_id != m::ID {\n        return;\n    }\n"
                + "    for &(key, label) in m::TOGGLES {\n        let on = m::flag(key, |k| game.native_setting(m::ID, k));\n"
                + "        buttons.push(m::toggle_label(label, on));\n    }\n}\n";
    }

    static String clickRs(Spec s) {
        return "// A click on the settings page: flips that option and keeps it.\n"
                + "fn " + s.id + "_click(game: &mut crate::game::Game<'_>, mod_id: &str, button: usize) {\n"
                + "    use crate::game::" + module(s) + " as m;\n    if mod_id != m::ID {\n        return;\n    }\n"
                + "    let Some(&(key, _)) = m::TOGGLES.get(button) else {\n        return;\n    };\n"
                + "    let on = !m::flag(key, |k| game.native_setting(m::ID, k));\n    m::set_flag(key, on);\n"
                + "    game.native_set_setting(m::ID, key, m::stored(on));\n}\n";
    }

    private static final String GAME_MUT = "  { name = \"game\", ty = \"&mut crate::game::Game<'_>\", value = \"&mut *self\" },\n";

    static String mixinToml(Spec s, Set<String> files, String revision) {
        StringBuilder b = new StringBuilder();
        b.append("[mod]\nid = ").append(tomlStr(s.id)).append("\nversion = ").append(tomlStr(s.version)).append("\npriority = 100\n\n");
        b.append("# Made with Horizon Translator's mod kit. It hooks only Horizon's native hook points (hooks API ").append(HOOKS_API)
                .append(" in\n# compatibility.json), so the launcher builds it on any Horizon that has them. The .hnmod names the commit it was\n# made on.\n\n");
        b.append("[[module]]\nid = \"runtime\"\nfile = \"crates/horizon-client/src/game.rs\"\nname = \"").append(module(s)).append("\"\nsource = \"runtime.rs\"\n");
        hook(b, "list", "mods", "list.rs", "  { name = \"mods\", ty = \"&mut Vec<crate::game::native_hooks::NativeMod>\", value = \"&mut *mods\" },\n");
        if (files.contains("hud.rs")) hook(b, "hud", "hud", "hud.rs", GAME_MUT
                + "  { name = \"batch\", ty = \"&mut horizon_client::hud::HudBatch\", value = \"&mut *batch\" },\n"
                + "  { name = \"corners\", ty = \"&mut horizon_client::extras_hud::Corners\", value = \"&mut *corners\" },\n");
        if (files.contains("key.rs")) hook(b, "key", "key", "key.rs", GAME_MUT
                + "  { name = \"key\", ty = \"&str\", value = \"key\" },\n  { name = \"pressed\", ty = \"bool\", value = \"pressed\" },\n");
        if (files.contains("command.rs")) hook(b, "command", "command", "command.rs", GAME_MUT
                + "  { name = \"command\", ty = \"&str\", value = \"command\" },\n  { name = \"ran\", ty = \"&mut bool\", value = \"&mut *ran\" },\n");
        if (files.contains("zoom.rs")) hook(b, "zoom", "zoom", "zoom.rs",
                "  { name = \"game\", ty = \"&crate::game::Game<'_>\", value = \"&*self\" },\n"
                        + "  { name = \"zoom\", ty = \"&mut Option<crate::game::native_hooks::NativeZoom>\", value = \"&mut *zoom\" },\n");
        if (files.contains("wheel.rs")) hook(b, "wheel", "wheel", "wheel.rs", GAME_MUT
                + "  { name = \"steps\", ty = \"f32\", value = \"steps\" },\n  { name = \"claimed\", ty = \"&mut bool\", value = \"&mut *claimed\" },\n");
        if (files.contains("tick.rs")) hook(b, "tick", "tick", "tick.rs", GAME_MUT);
        if (files.contains("settings.rs")) {
            hook(b, "settings", "settings", "settings.rs", GAME_MUT
                    + "  { name = \"mod_id\", ty = \"&str\", value = \"mod_id\" },\n  { name = \"buttons\", ty = \"&mut Vec<String>\", value = \"&mut *buttons\" },\n");
            hook(b, "click", "settings_click", "click.rs", GAME_MUT
                    + "  { name = \"mod_id\", ty = \"&str\", value = \"mod_id\" },\n  { name = \"button\", ty = \"usize\", value = \"button\" },\n");
        }
        return b.toString();
    }

    private static void hook(StringBuilder b, String id, String point, String helper, String bindings) {
        b.append("\n[[injection]]\nid = \"").append(id).append("\"\nkind = \"HEAD\"\ntarget = \"horizon_client::game::native_hooks::Game<'_>::native_")
                .append(point).append("\"\nhelper = \"").append(helper).append("\"\nbindings = [\n").append(bindings).append("]\n");
    }

    private static String tomlStr(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** Notes for an AI coding agent that works on the mod: the commands, the checks and the rules. */
    static String agents(Spec s) {
        return """
                # AGENTS.md

                %s (`%s`) is a Horizon native mod made with Horizon Translator's mod kit: a horizon-mixins bundle in
                `mixins/%s/`, packed as `%s.hnmod`. README.md says what each file is.

                `horizon-port` below is `java -jar horizon-port.jar` (Horizon Translator's `app` folder has it; on Windows
                with the app installed: `"$LOCALAPPDATA/Programs/Horizon Translator/runtime/bin/java.exe" -jar
                "$LOCALAPPDATA/Programs/Horizon Translator/app/horizon-port.jar"`). `SRC` is Horizon's source folder.

                - **What Horizon offers:** HOOKS.md lists every hook point (with the `[[injection]]` to paste into
                  `horizon-mixin.toml`) and every helper, with the hooks API each came in. Make it again for another
                  Horizon: `horizon-port hooks --horizon-src SRC --out HOOKS.md` (`--json` for JSON).
                - **Logic** goes in `runtime.rs` (the game module `crate::game::kit_%s` and this project's library):
                  test it with `cargo test`. Each other file is one hook: one function taking its `bindings` in order.
                - **Check for real:** `horizon-port compile . --horizon-src SRC` applies the mod to a copy of Horizon and
                  compiles it, as the launcher does. Its last line is `RESULT: ok`, `RESULT: failed (...)` or
                  `RESULT: not checked`. The first run takes a few GB and several minutes.
                - **Pack:** `horizon-port pack . --horizon-src SRC --compile --install` (compile, `cargo test`, pack
                  `%s.hnmod`, add it to the launcher). Plain `pack` checks against stand-ins for Horizon's helpers
                  only, and fails on code that uses the game's own types (`game.player`, `game.items()`...).
                - **Don't** run `horizon-port new` on this folder (it starts over), and don't press the launcher's Build or
                  pass `--trust-native-code` yourself: building native code is the player's decision.
                - In `runtime.rs`: no `//!` comments, `#![...]` attributes, `mod x;` files or `include!`.
                - Check `game.native_on(m::ID)` first in every hook. Keep the id; bump `version` in
                  `horizon-mixin.toml` and `Cargo.toml` for a new version.
                """.formatted(s.name, s.id, s.id, s.id, s.id, s.id);
    }

    static String readme(Spec s) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(s.name).append("\n\n");
        if (!s.description.isEmpty()) b.append(s.description).append("\n\n");
        b.append("A Horizon native mod").append(s.author.isEmpty() ? "" : " by " + s.author)
                .append(", made with Horizon Translator's mod kit.\n\n## What it does\n\n");
        if (s.hud != null) b.append("- A HUD box in the ").append(List.of("top left", "top right", "bottom left", "bottom right").get(s.hud.corner())).append(" corner.\n");
        if (s.zoom != null) b.append("- Zoom (").append(s.zoom.factor()).append("x) on ").append(keyOf(s, "zoom")).append(s.zoom.hold() ? " while held" : "").append(s.zoom.scroll() ? "; the mouse wheel zooms closer or further" : "").append(".\n");
        for (Key k : s.keys) if (k.name().startsWith("key")) b.append("- ").append(k.label()).append(" on ").append(k.key()).append(".\n");
        for (Cmd c : s.commands) b.append("- `/").append(c.name()).append("`").append(c.act().kind().equals("say") ? "" : " (" + c.act().kind().replace('_', ' ') + ")").append(".\n");
        if (!s.welcome.isEmpty()) b.append("- A welcome message on joining a world.\n");
        for (Timer t : s.timers) b.append("- A message every ").append(t.every()).append(" seconds.\n");
        b.append("- `/").append(s.id).append("` lists the commands and keys. Keys and options are in Options > Mods > ").append(s.name).append(".\n\n");
        b.append("""
                ## Install it

                - **The easy way:** `%s.hnmod` in this folder is the ready mod. Horizon Translator adds it to Horizon for you when
                  "Add it to Horizon" is ticked; otherwise copy it into `%%APPDATA%%\\horizon\\horizon-native-mods\\`. Then in the
                  Horizon launcher's **Mods** page: **Build** under "Native build of Horizon" (if it isn't there, turn on
  Settings > Game > Native mods first), then play that build.
                - **From this folder:** `horizon-launcher native-build "<this folder>" --trust-native-code`.

                The launcher downloads its own Rust the first time (20 to 30 minutes), then builds the game with your native mods.

                ## Change it

                | File | What it is |
                |---|---|
                | `mixins/%s/runtime.rs` | The mod: its settings at the top, its logic, and tests at the bottom |
                | `mixins/%s/*.rs` (the others) | The hooks: each runs when Horizon calls it (a key, a tick, the HUD...) |
                | `mixins/%s/horizon-mixin.toml` | Which hook runs which file |
                | `horizon-kit.json` | What you picked in the builder (open it in Horizon Translator to make a new version) |
                | `HOOKS.md` | Every hook point and game helper of the Horizon it was made on (when the kit had its source) |
                | `AGENTS.md` | Notes for an AI coding agent working on the mod |

                Text and settings are at the top of `runtime.rs`. Edit them, then in Horizon Translator use **Make a mod > Check and
                pack a folder** to check your code and pack a new `.hnmod`. With Rust installed, `cargo test` here runs the tests.

                The guide that comes with Horizon Translator (Make a mod > Guide) lists every hook and game helper you can use.

                Native mods run with the player's rights and aren't sandboxed: only share mods whose code you know.
                """.formatted(s.id, s.id, s.id, s.id));
        return b.toString();
    }

    private static String keyOf(Spec s, String name) {
        return s.keys.stream().filter(k -> k.name().equals(name)).map(Key::key).findFirst().orElse("?");
    }

    // ------------------------------------------------------------------ checking and packing an edited project

    /**
     * The hook points' API the code needs: the newest API among the `native_*` names it uses (hooks in its toml,
     * helpers in its Rust), as Horizon's native_hooks.rs says when each came: its header ("API 27 adds the
     * player-health HUD rows (`native_health_hud`)") or the function's own doc ("... API 19."). 1 when the source
     * says nothing.
     */
    static int hooksApi(String code, Path horizonSrc) throws IOException {
        Path hooks = horizonSrc.resolve("crates/horizon-client/src/game/native_hooks.rs");
        if (!Files.isRegularFile(hooks)) return HOOKS_API;
        java.util.Map<String, Integer> since = HookList.since(Text.read(hooks));
        int api = HOOKS_API;
        Matcher used = Pattern.compile("\\bnative_\\w+").matcher(code);
        while (used.find()) api = Math.max(api, since.getOrDefault(used.group(), HOOKS_API));
        return api;
    }

    /**
     * Packs (and installs) an existing kit project again after its code was edited: checks it with cargo when there is
     * one (`check` against Horizon's helpers, then the mod's own tests), then writes `<id>.hnmod`.
     */
    static int pack(Path dir, Path horizonSrc, Path install) throws Exception {
        return pack(dir, horizonSrc, install, true, null, null);
    }

    /**
     * `check` false skips the check against Horizon's helpers (for code that reaches past them, into the game's own
     * types: the launcher's build compiles it); the mod's own tests still run. `revision` names the Horizon commit
     * when the source folder has no `.horizon-revision`. `compileWork` not null: the real compile check (`compile`,
     * in that work folder) instead of `check`.
     */
    static int pack(Path dir, Path horizonSrc, Path install, boolean check, String revisionOverride, Path compileWork) throws Exception {
        Path mixins = dir.resolve("mixins");
        List<Path> mods;
        try (var l = Files.list(mixins)) { mods = l.filter(p -> Files.isRegularFile(p.resolve("horizon-mixin.toml"))).toList(); }
        if (mods.size() != 1) throw new IllegalArgumentException("expected one mod under " + mixins + ", found " + mods.size());
        Path mod = mods.get(0);
        String toml = Text.read(mod.resolve("horizon-mixin.toml"));
        Matcher idm = Pattern.compile("(?m)^id = \"([^\"]+)\"").matcher(toml);
        Matcher vm = Pattern.compile("(?m)^version = \"([^\"]+)\"").matcher(toml);
        if (!idm.find() || !vm.find()) throw new IllegalArgumentException("no id/version in " + mod.resolve("horizon-mixin.toml"));
        String id = idm.group(1), version = vm.group(1);
        // Hook points only: the launcher may build it on newer Horizon commits too.
        boolean hooksOnly = !Pattern.compile("(?m)^target = \"(?!horizon_client::game::native_hooks::Game<'_>::native_)").matcher(toml).find();
        // The hook points' version the code needs: API 2 added the item panel and the recipes; later ones are read
        // from the Horizon source (see hooksApi).
        int api = HOOKS_API;
        StringBuilder all = new StringBuilder(toml);
        try (var files = Files.walk(mod)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".rs")).toList()) {
                String text = Text.read(f);
                all.append('\n').append(text);
                if (Pattern.compile("native_(panel_|recipes|known_recipes|place_recipe|give)").matcher(text).find()) api = Math.max(api, 2);
            }
        }
        api = Math.max(api, hooksApi(all.toString(), horizonSrc));
        System.out.println("The mod needs Horizon's hook points API " + api + " or newer.");

        int code = 0;
        if (NativeCheck.cargo() != null) {
            if (compileWork != null) {
                code = RealCompile.run(dir, horizonSrc, compileWork);
                if (code == 2) code = 1; // not checked: don't pack as if it were
            } else if (check) code = NativeCheck.run(dir, horizonSrc);
            else System.out.println("Not checked against Horizon's helpers (--no-check): the launcher's build compiles it (or use --compile).");
            if (code == 0 && Files.isRegularFile(dir.resolve("Cargo.toml"))) {
                System.out.println("Running the mod's own tests (cargo test)...");
                ProcessBuilder pb = new ProcessBuilder(NativeCheck.cargo(), "test", "--quiet").directory(dir.toFile()).inheritIO();
                code = pb.start().waitFor();
                System.out.println(code == 0 ? "ok: the mod's tests pass" : "The mod's tests fail (above).");
            }
            if (code != 0) {
                System.out.println("Not packed: fix the errors above first.");
                return 1;
            }
        } else {
            System.out.println("Rust (cargo) isn't installed here, so the code wasn't checked: the launcher's build will say if something is wrong.");
        }
        String revision = revisionOverride, mixinsRev = null;
        Path r = horizonSrc.resolve(".horizon-revision");
        if (revision == null && Files.isRegularFile(r)) revision = Text.read(r).strip();
        Path nm = horizonSrc.resolve("launcher/src/native_mods.rs");
        if (Files.isRegularFile(nm)) {
            Matcher mm = Pattern.compile("MIXINS_REV: &str = \"([0-9a-f]{40})\"").matcher(Text.read(nm));
            if (mm.find()) mixinsRev = mm.group(1);
        }
        NativePackage.Outcome o = NativePackage.write(dir, null, id, version, GAME, revision, mixinsRev, hooksOnly ? api : null, List.of());
        if (!o.full()) {
            System.out.println("Not packed:\n- " + String.join("\n- ", o.gaps()));
            return 1;
        }
        System.out.println("Packed " + o.file());
        if (install != null) System.out.println("installed as " + NativePackage.install(o.file(), id, install));
        return 0;
    }
}
