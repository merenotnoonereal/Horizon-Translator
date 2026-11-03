package horizonport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * `check`: compiles a bundle's Rust against Horizon's native-hook helpers without building Horizon. It writes a
 * small crate whose `Game` has exactly the `native_*` signatures of the given Horizon source (bodies left out),
 * puts the bundle's modules and hook-point helpers where horizon-mixins puts them, and runs `cargo check`.
 * Helpers for injections into other Horizon functions aren't checked: they need the real types.
 */
final class NativeCheck {
    private NativeCheck() {}

    /** The types the stub carries: Horizon's own (as empty structs or copies) and std's. */
    private static final Set<String> KNOWN = Set.of("HudBatch", "Corners", "Corner", "NativePlayer", "NativeMod", "NativeStack",
            "Option", "Vec", "String", "PathBuf", "Duration", "Result", "Self");
    private static final Pattern STRUCT = Pattern.compile("((?:[ \\t]*(?:#\\[[^\\n]*\\]|///[^\\n]*)\\n)*)pub\\(crate\\) struct (\\w+) \\{\\n(.*?)\\n\\}", Pattern.DOTALL);
    private static final Pattern FN = Pattern.compile("pub\\(super\\) fn (native_\\w+)\\s*\\((.*?)\\)\\s*(->\\s*[^{]+?)?\\s*\\{", Pattern.DOTALL);
    private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z]\\w*");

    /** Horizon's public types the state reads use, with the fields used (the declarations are checked against the source). */
    /** ureq 2's API as the translated code's HTTP glue calls it (Horizon's Cargo.lock has ureq 2.12.1). */
    private static final String UREQ_STUB = """
            #![allow(unused)]
            pub struct AgentBuilder;
            pub struct Agent;
            pub struct Request;
            pub struct Response;
            #[derive(Debug)]
            pub struct Transport;
            pub enum Error {
                Status(u16, Response),
                Transport(Transport),
            }
            impl std::fmt::Display for Error {
                fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                    f.write_str("error")
                }
            }
            impl AgentBuilder {
                pub fn new() -> Self {
                    AgentBuilder
                }
                pub fn timeout_connect(self, d: std::time::Duration) -> Self {
                    self
                }
                pub fn build(self) -> Agent {
                    Agent
                }
            }
            impl Agent {
                pub fn request(&self, method: &str, path: &str) -> Request {
                    Request
                }
            }
            impl Request {
                pub fn timeout(self, d: std::time::Duration) -> Self {
                    self
                }
                pub fn set(self, header: &str, value: &str) -> Self {
                    self
                }
                pub fn call(self) -> Result<Response, Error> {
                    unimplemented!()
                }
            }
            impl Response {
                pub fn status(&self) -> u16 {
                    unimplemented!()
                }
                pub fn into_string(self) -> std::io::Result<String> {
                    unimplemented!()
                }
            }
            """;

    /** Horizon's Text and chat, as the translated code uses them (each declaration is in RustTranslator.STATE_DECLS). */
    private static final String TEXT_TYPES = """
            pub mod text {
                #[derive(Clone, Debug, Default, PartialEq)]
                pub struct Text;
                impl Text {
                    pub fn empty() -> Self {
                        unimplemented!()
                    }
                    pub fn literal(s: impl Into<String>) -> Self {
                        unimplemented!()
                    }
                    pub fn append(mut self, other: impl Into<Text>) -> Self {
                        unimplemented!()
                    }
                    pub fn with_style(mut self, style: &Style) -> Self {
                        unimplemented!()
                    }
                    pub fn plain(&self) -> String {
                        unimplemented!()
                    }
                }
                impl From<&str> for Text {
                    fn from(s: &str) -> Self {
                        unimplemented!()
                    }
                }
                impl From<String> for Text {
                    fn from(s: String) -> Self {
                        unimplemented!()
                    }
                }
                #[derive(Clone, Debug, Default, PartialEq)]
                pub struct Style {
                    pub color: Option<u32>,
                    pub bold: Option<bool>,
                    pub italic: Option<bool>,
                    pub underlined: Option<bool>,
                    pub strikethrough: Option<bool>,
                    pub obfuscated: Option<bool>,
                    pub click: Option<ClickEvent>,
                    pub hover: Option<Box<Text>>,
                    pub insertion: Option<String>,
                }
                #[derive(Clone, Debug, PartialEq)]
                pub enum ClickEvent {
                    OpenUrl(String),
                    SuggestCommand(String),
                    RunCommand(String),
                    CopyToClipboard(String),
                }
            }

            pub mod mod_list {
                pub struct ModList {
                    pub installed: Vec<Installed>,
                }
                pub struct Installed {
                    pub id: String,
                }
            }

            pub mod options_ui {
                pub enum Page {
                    NativeMod(usize),
                }
            }

            pub mod chat {
                pub enum ChatSource {
                    Player,
                    Server,
                    Client,
                }
                pub struct Chat;
                impl Chat {
                    pub fn add(&mut self, atlas: &crate::hud::HudAtlas, content: crate::text::Text, indicator: Option<u32>, source: ChatSource, ticks: u64, focused: bool) {
                        unimplemented!()
                    }
                }
            }

            """;

    private static final String STATE_TYPES = """
            pub mod items {
                #[derive(Clone, Debug, PartialEq)]
                pub struct ItemStack {
                    pub item: std::sync::Arc<str>,
                    pub count: u32,
                    pub damage: u32,
                }
                pub struct StackData {
                    pub charged: Vec<ItemStack>,
                }
                impl ItemStack {
                    pub fn data(&self) -> Option<&StackData> {
                        unimplemented!()
                    }
                }
            }
            pub mod pose {
                #[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
                pub enum Pose {
                    #[default]
                    Standing,
                    Crouching,
                }
            }
            pub mod first_person {
                #[derive(Clone, Copy, Debug, PartialEq, Eq)]
                pub enum Hand {
                    Main,
                    Off,
                }
                #[derive(Clone, Copy, Debug, PartialEq, Eq)]
                pub struct SwingAnimation {
                    pub kind: u8,
                    pub duration: i32,
                }
                impl SwingAnimation {
                    pub const DEFAULT: SwingAnimation = SwingAnimation { kind: 1, duration: 6 };
                }
            }
            pub mod spear {
                pub struct Kinetic;
                impl Kinetic {
                    pub fn of(item: &str) -> Option<Self> {
                        unimplemented!()
                    }
                    pub fn attack_animation(&self) -> crate::first_person::SwingAnimation {
                        unimplemented!()
                    }
                }
            }
            pub mod motion {
                pub struct Vec3 {
                    pub x: f64,
                    pub y: f64,
                    pub z: f64,
                }
                pub struct GroundState {
                    pub on_ground: bool,
                }
                pub struct PlayerMotion {
                    pub position: Vec3,
                    pub sprinting: bool,
                    pub shift_key_down: bool,
                    pub pose: crate::pose::Pose,
                    pub ground: GroundState,
                    pub velocity: Vec3,
                    pub swimming: bool,
                    pub fall_flying: bool,
                    pub sleeping: bool,
                    pub auto_spin_attack_ticks: i32,
                }
            }
            pub mod settings {
                pub struct Settings {
                    pub main_hand_left: bool,
                    pub mods: crate::mod_list::ModList,
                }
            }
            pub mod item_model {
                #[derive(Clone, Copy, Debug, PartialEq, Eq)]
                pub enum DisplayContext {
                    None,
                    ThirdPersonLeftHand,
                    ThirdPersonRightHand,
                    FirstPersonLeftHand,
                    FirstPersonRightHand,
                    Head,
                    Gui,
                    Ground,
                    Fixed,
                    OnShelf,
                }
            }
            pub mod stations {
                pub fn hover_name(names: &dyn Fn(&str) -> String, stack: &horizon_client::items::ItemStack) -> String {
                    unimplemented!()
                }
            }
            pub mod creative {
                pub struct Names;
                impl Names {
                    pub fn display_item(&self, id: &str) -> String {
                        unimplemented!()
                    }
                }
            }
            pub mod clock {
                pub struct FixedStep;
                impl FixedStep {
                    pub fn fraction(&self) -> f64 {
                        unimplemented!()
                    }
                    pub fn camera_fraction(&self) -> f64 {
                        unimplemented!()
                    }
                }
            }
            pub mod item_render {
                pub struct ItemRenderState;
                impl ItemRenderState {
                    pub fn uses_block_light(&self) -> bool {
                        unimplemented!()
                    }
                }
            }
            pub mod consumable {
                #[derive(Clone, Copy, Debug, PartialEq, Eq)]
                pub enum UseAnimation {
                    None,
                    Eat,
                }
                pub struct Consumable {
                    pub animation: UseAnimation,
                }
            }
            pub mod item_info {
                pub struct BlocksAttacks;
                pub struct ItemInfo {
                    pub block: Option<u32>,
                    pub consumable: Option<crate::consumable::Consumable>,
                    pub blocks_attacks: Option<BlocksAttacks>,
                }
                pub struct Items {
                    pub item_tags: std::collections::HashMap<String, std::collections::HashSet<String>>,
                }
                impl Items {
                    pub fn get(&self, id: &str) -> Option<&ItemInfo> {
                        unimplemented!()
                    }
                }
            }
            pub mod swing {
                pub struct SwingState {
                    pub ticks: i32,
                }
                pub struct HandHeights;
                impl HandHeights {
                    pub fn interpolated(&self, partial: f32) -> [f32; 2] {
                        unimplemented!()
                    }
                }
                impl SwingState {
                    pub fn animation(&self, partial: f32) -> f32 {
                        unimplemented!()
                    }
                    pub fn swinging(&self) -> bool {
                        unimplemented!()
                    }
                    pub fn attack_hand(&self) -> crate::first_person::Hand {
                        unimplemented!()
                    }
                }
            }

            """;

    static int run(Path bundle, Path horizonSrc) throws IOException, InterruptedException {
        Path hooksFile = horizonSrc.resolve("crates/horizon-client/src/game/native_hooks.rs");
        if (!Files.isRegularFile(hooksFile)) throw new IllegalArgumentException("no " + hooksFile + ": --horizon-src is Horizon's source folder");
        Path mixins = bundle.resolve("mixins");
        List<Path> dirs;
        try (Stream<Path> s = Files.list(mixins)) {
            dirs = s.filter(d -> Files.isRegularFile(d.resolve("horizon-mixin.toml"))).toList();
        }
        if (dirs.size() != 1) throw new IllegalArgumentException("expected one mod under " + mixins + ", found " + dirs.size());
        Path dir = dirs.get(0).toAbsolutePath();
        List<Map<String, String>> sections = sections(Text.read(dir.resolve("horizon-mixin.toml")));

        StringBuilder mods = new StringBuilder();
        List<String> skipped = new ArrayList<>();
        int checked = 0;
        for (Map<String, String> s : sections) {
            String kind = s.get("");
            if (kind.equals("[[module]]") && "crates/horizon-client/src/game.rs".equals(s.get("file"))) {
                mods.append("    #[path = \"").append(path(dir.resolve(s.get("source")))).append("\"]\n    pub mod ").append(s.get("name")).append(";\n");
                checked++;
            } else if (kind.equals("[[module]]")) {
                skipped.add("module " + s.get("id") + " (goes into " + s.get("file") + ")");
            } else if (kind.equals("[[injection]]") && s.get("helper") != null) {
                String target = s.getOrDefault("target", "");
                if (target.startsWith("horizon_client::game::native_hooks::Game<'_>::native_")) {
                    mods.append("    #[path = \"").append(path(dir.resolve(s.get("helper")))).append("\"]\n    mod hook_")
                            .append(s.get("id").replaceAll("\\W", "_")).append(";\n");
                    checked++;
                } else {
                    skipped.add(s.get("helper") + " (into " + target + ")");
                }
            }
        }

        // The Game state the translated code reads directly: each declaration must be in this Horizon as written.
        Path crates = horizonSrc.resolve("crates");
        int missing = 0;
        for (String[] d : RustTranslator.STATE_DECLS) {
            Path f = crates.resolve(d[0]);
            if (!Files.isRegularFile(f) || !Text.read(f).replace("\r\n", "\n").contains("\n" + d[1])) {
                System.out.println("  Horizon changed: `" + d[1].strip() + "` isn't in " + d[0]);
                missing++;
            }
        }
        if (missing > 0) {
            System.out.println("The state the translated code reads isn't all in this Horizon: regenerate the bundle for it.");
            return 1;
        }
        String hooks = Text.read(hooksFile);
        StringBuilder types = new StringBuilder(), fns = new StringBuilder();
        Matcher m = STRUCT.matcher(hooks);
        while (m.find()) {
            if (known(m.group(3).replaceAll("///[^\\n]*", ""))) types.append(m.group(0).strip()).append("\n\n");
        }
        m = FN.matcher(hooks);
        int helpers = 0;
        while (m.find()) {
            String sig = m.group(2) + (m.group(3) == null ? "" : m.group(3));
            if (!known(sig)) continue;
            fns.append("        pub(super) fn ").append(m.group(1)).append('(').append(m.group(2).strip().replaceAll("\\s+", " ").replaceAll(",$", ""))
                    .append(')').append(m.group(3) == null ? "" : " " + m.group(3).strip()).append(" {\n            unimplemented!()\n        }\n");
            helpers++;
        }

        String lib = "// Written by horizon-port check: Horizon's native-hook helpers (signatures from " + path(hooksFile) + "),\n"
                + "// with the bundle's modules and hook-point helpers where horizon-mixins puts them. Only for `cargo check`.\n"
                + "#![allow(dead_code, unused, clippy::all)]\nextern crate self as horizon_client;\nextern crate self as horizon_assets;\n\n"
                + "pub mod hud {\n    pub struct HudBatch;\n    pub struct HudAtlas;\n}\npub mod extras_hud {\n    pub struct Corners;\n"
                + "    impl Corners {\n        pub fn place(&mut self, corner: horizon_client::extras::Corner, w: f32, h: f32) -> (f32, f32) {\n"
                + "            unimplemented!()\n        }\n    }\n}\n"
                + "pub mod extras {\n    #[derive(Clone, Copy, Debug, PartialEq, Eq)]\n    pub enum Corner {\n        TopLeft,\n        TopRight,\n"
                + "        BottomLeft,\n        BottomRight,\n    }\n}\n\n" + TEXT_TYPES
                + STATE_TYPES
                + "pub mod game {\n    use horizon_client::{first_person::Hand, items::ItemStack, motion::PlayerMotion, settings::Settings};\n\n"
                + "    /// The fields the translated code reads, with Horizon's types (checked against its source above).\n"
                + "    pub struct Game<'a> {\n        _life: std::marker::PhantomData<&'a ()>,\n        inventory: Vec<Option<ItemStack>>,\n"
                + "        selected: usize,\n        offhand: Option<ItemStack>,\n        using: Option<(usize, u32)>,\n        player: Option<PlayerMotion>,\n"
                + "        settings: Settings,\n        potions: potions::PotionState,\n        res: std::sync::Arc<Resources>,\n"
                + "        swing: horizon_client::swing::SwingState,\n        ticks: u64,\n        yaw: f32,\n        pitch: f32,\n        hook: Option<()>,\n        mining: Option<()>,\n"
                + "        heights: horizon_client::swing::HandHeights,\n        player_model: Option<PlayerModel>,\n        clock: horizon_client::clock::FixedStep,\n        pack: PackResources,\n"
                + "        screen: Screen,\n        gui: UiResources,\n        ui: chat::UiState,\n    }\n\n"
                + "    #[derive(PartialEq)]\n    enum Screen {\n        Playing,\n        Chat,\n    }\n\n"
                + "    pub struct UiResources {\n        pub atlas: horizon_client::hud::HudAtlas,\n    }\n\n"
                + "    mod chat {\n        pub struct UiState {\n            pub chat: horizon_client::chat::Chat,\n        }\n    }\n\n"
                + "    pub struct PackResources {\n        names: std::sync::Arc<horizon_client::creative::Names>,\n    }\n\n"
                + "    pub struct PlayerModel {\n        pub slim: bool,\n    }\n\n"
                + "    pub struct Resources;\n    impl Resources {\n        fn items(&self) -> &horizon_client::item_info::Items {\n            unimplemented!()\n        }\n    }\n\n"
                + "    impl Game<'_> {\n        fn used_hand(&self) -> Option<Hand> {\n            unimplemented!()\n        }\n"
                + "        pub(super) fn charge_ticks(stack: &ItemStack) -> u32 {\n            unimplemented!()\n        }\n"
                + "        pub(super) fn open_options_at(&mut self, from_title: bool, page: horizon_client::options_ui::Page) {\n            unimplemented!()\n        }\n"
                + "        pub(super) fn visible_hand_items(&self) -> [Option<ItemStack>; 2] {\n            unimplemented!()\n        }\n"
                + "        pub(super) fn hands_to_render(&self) -> [bool; 2] {\n            unimplemented!()\n        }\n"
                + "        pub(super) fn player_owner(&self, stack: Option<&ItemStack>) -> OwnerState {\n            unimplemented!()\n        }\n"
                + "        pub(super) fn resolve_item(&self, stack: &ItemStack, display: horizon_assets::item_model::DisplayContext, owner: Option<&OwnerState>, seed: i32)"
                + " -> horizon_client::item_render::ItemRenderState {\n            unimplemented!()\n        }\n    }\n\n"
                + "    pub struct OwnerState {\n        pub use_item_remaining_ticks: i32,\n    }\n\n"
                + "    mod maps {\n        pub(super) fn map_id(stack: &horizon_client::items::ItemStack) -> Option<i32> {\n            unimplemented!()\n        }\n    }\n\n"
                + "    mod potions {\n        pub(super) struct PotionState {\n            pub(super) effects: PlayerEffects,\n        }\n"
                + "        pub struct PlayerEffects;\n        pub struct EffectInstance;\n        impl PlayerEffects {\n"
                + "            pub fn get(&self, id: &str) -> Option<&EffectInstance> {\n                unimplemented!()\n            }\n        }\n    }\n\n"
                + "    pub mod native_hooks {\n        use super::Game;\n        use horizon_client::{extras::Corner, extras_hud::Corners, hud::HudBatch};\n"
                + "        use std::{path::PathBuf, time::Duration};\n\n"
                + indent(types.toString().strip(), "        ") + "\n\n        impl Game<'_> {\n" + fns + "        }\n    }\n\n" + mods + "}\n";
        Path crate = bundle.resolve("target").resolve("horizon-check");
        Files.createDirectories(crate.resolve("src"));
        Files.writeString(crate.resolve("src/lib.rs"), lib, StandardCharsets.UTF_8);
        // Horizon's own ureq (HTTPS), as the API the translated code uses: a local stand-in, nothing to download.
        Files.createDirectories(crate.resolve("ureq/src"));
        Files.writeString(crate.resolve("ureq/Cargo.toml"), "[package]\nname = \"ureq\"\nversion = \"2.12.1\"\nedition = \"2021\"\npublish = false\n", StandardCharsets.UTF_8);
        Files.writeString(crate.resolve("ureq/src/lib.rs"), UREQ_STUB, StandardCharsets.UTF_8);
        Files.writeString(crate.resolve("Cargo.toml"), "[package]\nname = \"horizon-check\"\nversion = \"0.0.0\"\nedition = \"2024\"\npublish = false\n\n"
                + "[lib]\nname = \"horizon_client\"\npath = \"src/lib.rs\"\n\n[dependencies]\nureq = { path = \"ureq\" }\n\n# Not part of any other workspace.\n[workspace]\n", StandardCharsets.UTF_8);
        System.out.println("Checking " + checked + " files of " + bundle + " against " + helpers + " helpers of Horizon's native_hooks.rs");
        for (String s : skipped) System.out.println("  not checked: " + s + ", it needs Horizon's real types");

        String cargo = cargo();
        if (cargo == null) {
            System.out.println("No cargo found. The check crate is in " + crate + ": run `cargo check` there.");
            return 0;
        }
        ProcessBuilder pb = new ProcessBuilder(cargo, "check", "--quiet", "--message-format", "short").directory(crate.toFile()).inheritIO();
        pb.environment().put("CARGO_TARGET_DIR", crate.resolve("target").toAbsolutePath().toString());
        int code = pb.start().waitFor();
        System.out.println(code == 0 ? "ok: the bundle's Rust compiles against Horizon's helpers" : "The bundle's Rust doesn't compile (above). The crate is in " + crate + ".");
        int plan = plan(bundle, horizonSrc);
        return code == 0 && plan == 0 ? 0 : 1;
    }

    /**
     * horizon-mixins' own `plan` (it indexes the source and validates every hook; nothing is compiled or run), when
     * the launcher has installed the tool: it rejects hooks the compile check can't see, such as a RETURN or call hook
     * in a function with closures (E_UNSUPPORTED_CONTROL_FLOW).
     */
    private static int plan(Path bundle, Path horizonSrc) throws IOException, InterruptedException {
        Path tool = mixinsTool(bundle);
        if (tool == null) {
            System.out.println("  not checked: horizon-mixins plan (the tool isn't installed; the launcher installs it on its first native build, or set HORIZON_MIXINS)");
            return 0;
        }
        if (horizonSrc.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
            System.out.println("  not checked: horizon-mixins plan (it needs Horizon's source unpacked, not in a .zip; the launcher's build runs it)");
            return 0;
        }
        Path out = bundle.resolve("target").resolve("horizon-plan.json").toAbsolutePath();
        Files.createDirectories(out.getParent());
        ProcessBuilder pb = new ProcessBuilder(tool.toString(), "plan", "--workspace", horizonSrc.toAbsolutePath().toString(),
                "--mods", bundle.resolve("mixins").toAbsolutePath().toString(), "--out", out.toString()).redirectErrorStream(true);
        Process p = pb.start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code == 0) {
            System.out.println("ok: horizon-mixins plan accepts every hook (" + tool + ")");
        } else {
            System.out.println("horizon-mixins plan rejects the bundle:\n" + text.strip());
            if (text.contains("E_REVISION")) {
                System.out.println("(The bundle pins a Horizon commit: the source folder needs it in a `.horizon-revision` file, as the launcher's"
                        + " copy has, or a git checkout.)");
            }
        }
        return code == 0 ? 0 : 1;
    }

    /** $HORIZON_MIXINS, else the launcher's copy at the revision the bundle names (any copy when it names none). */
    static Path mixinsTool(Path bundle) throws IOException {
        String env = System.getenv("HORIZON_MIXINS");
        if ("none".equals(env)) return null; // the test fixture's mappings are made up
        if (env != null && Files.isRegularFile(Path.of(env))) return Path.of(env);
        String exe = File.separatorChar == '\\' ? "horizon-mixins.exe" : "horizon-mixins";
        String home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        List<Path> roots = new ArrayList<>();
        if (appData != null) roots.add(Path.of(appData, "horizon-launcher"));
        roots.add(Path.of(home, "Library", "Application Support", "horizon-launcher"));
        roots.add(Path.of(home, ".local", "share", "horizon-launcher"));
        String rev = "";
        Path compat = bundle.resolve("compatibility.json");
        if (Files.isRegularFile(compat)) {
            Matcher m = Pattern.compile("\"horizon_mixins_revision\"\\s*:\\s*\"([0-9a-f]{12})").matcher(Text.read(compat));
            if (m.find()) rev = m.group(1);
        }
        for (Path root : roots) {
            Path tools = root.resolve("native").resolve("tools");
            if (!Files.isDirectory(tools)) continue;
            try (Stream<Path> s = Files.list(tools)) {
                for (Path d : s.sorted().toList()) {
                    Path t = d.resolve("bin").resolve(exe);
                    if (Files.isRegularFile(t) && (rev.isEmpty() || d.getFileName().toString().equals(rev))) return t;
                }
            }
        }
        return null;
    }

    /** Every capitalised name in it is one the stub has. */
    private static boolean known(String rust) {
        Matcher t = TYPE_NAME.matcher(rust);
        while (t.find()) if (!KNOWN.contains(t.group())) return false;
        return true;
    }

    /** The toml's sections as key -> string value; "" is the header. Enough for what the generator writes. */
    private static List<Map<String, String>> sections(String toml) {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> cur = null;
        Pattern kv = Pattern.compile("^(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");
        for (String line : toml.split("\n")) {
            String l = line.strip();
            if (l.startsWith("[") && !l.startsWith("[[") || l.startsWith("[[") && l.endsWith("]]")) {
                cur = new LinkedHashMap<>();
                cur.put("", l);
                out.add(cur);
            } else if (cur != null) {
                Matcher x = kv.matcher(l);
                if (x.matches()) cur.put(x.group(1), x.group(2));
            }
        }
        return out;
    }

    private static String indent(String s, String by) {
        return by + s.replace("\n", "\n" + by).replace(by + "\n", "\n");
    }

    private static String path(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    static String cargo() {
        String exe = File.separatorChar == '\\' ? "cargo.exe" : "cargo";
        for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!d.isEmpty() && Files.isRegularFile(Path.of(d, exe))) return Path.of(d, exe).toString();
        }
        Path home = Path.of(System.getProperty("user.home"), ".cargo", "bin", exe);
        return Files.isRegularFile(home) ? home.toString() : null;
    }
}
