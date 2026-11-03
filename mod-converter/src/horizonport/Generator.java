package horizonport;

import horizonport.Analyzer.Feature;
import horizonport.Analyzer.Injection;
import horizonport.Analyzer.ModAnalysis;
import horizonport.SourceMatch.RustFn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a Horizon native-mod bundle for an analysed Fabric mod, laid out as horizon-rust/horizon-mods'
 * Coords HUD is: Cargo.toml, compatibility.json, and mixins/<id>/ with horizon-mixin.toml, runtime.rs
 * and one helper per injection.
 *
 * Everything it switches on leaves the game as it was (an empty HEAD, a RETURN that passes its value
 * on, a WRAP_OPERATION that calls the original), so the bundle builds into a game that plays like
 * vanilla until the TODOs are filled in. What would change behaviour blindly (OVERWRITE) or needs a
 * choice only a person can make (which local, which literal) is written commented out, with why, in
 * PORTING.md. The Java bodies themselves are never read: each helper lists the game calls its Java
 * class makes and what they are in Horizon.
 */
final class Generator {
    record Binding(String name, String ty, String value) {}

    /** A Rust parameter: self is "&self", "&mut self" or "self" for the receiver, else null. */
    record Param(String name, String type, String self) {}

    record Signature(boolean generic, List<Param> params, String ret, String body) {}

    private static final Set<String> PRIMITIVES = Set.of("bool", "char", "f32", "f64", "i8", "i16", "i32", "i64",
            "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize");
    private static final Pattern NAMED_LIFETIME = Pattern.compile("'(?!static\\b)[a-z_]\\w*");
    private static final Pattern KEY_NAME = Pattern.compile("key\\.[a-z0-9_]+\\.[a-z0-9_.]+");

    private final ModAnalysis a;
    private final Mappings m;
    private final SourceMatch src;
    /** The mod's own Java source, if its author gave it: copied next to the Rust it becomes. */
    private final JavaSource java;
    private final Map<String, List<JavaSource.Snippet>> hookJava = new LinkedHashMap<>();
    private int javaCopied;
    private final String revision;
    private final String id;
    private final String module;
    private final Map<String, String> files = new LinkedHashMap<>();
    private final StringBuilder toml = new StringBuilder();
    private final List<String[]> porting = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private int active, commented;
    private int hooksApi = 1;
    /** Hook points that only run the translated code (initializer, game-thread queue, key state): nothing to port there. */
    private final Set<String> glueHooks = new HashSet<>();
    /** Hook points a mixin HEAD/RETURN hooks: the mixin's handler isn't translated, so they keep a TODO. */
    private final Set<String> mixinHooks = new HashSet<>();
    private boolean rawTargets;
    /** The mod's own Java translated to Rust (with --java-src or port), or null. */
    private RustTranslator.Result translation;

    private Generator(ModAnalysis a, Mappings m, SourceMatch src, JavaSource java, String revision) {
        this.java = java;
        this.a = a;
        this.m = m;
        this.src = src;
        this.revision = revision;
        this.id = a.modId().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        this.module = "port_" + id;
        if (java != null) {
            try {
                translation = RustTranslator.translate(java.root(), id, keys(), poseHooks(), eventHooks());
            } catch (IOException | RuntimeException e) {
                porting.add(new String[] {"Java logic", "not translated", "The translator stopped: " + e.getMessage()});
            }
        }
    }

    /** Writes the bundle into dir and returns a one-line summary. */
    static String generate(ModAnalysis a, Mappings m, SourceMatch src, JavaSource java, String revision, Path dir) throws IOException {
        return generate(a, m, src, java, revision, dir, null, null).summary();
    }

    /** summary: one line; pack: whether it's a full port, and its .hnmod if so. */
    record Generated(String summary, NativePackage.Outcome pack) {}

    /**
     * Writes the bundle into dir, then, when nothing is left to finish, packs it as dir/<id>.hnmod (and puts that into
     * the game folder `install`, when given). jar: the mod's jar, to see what a content mod would have to carry.
     */
    static Generated generate(ModAnalysis a, Mappings m, SourceMatch src, JavaSource java, String revision, Path dir, Path jar, Path install)
            throws IOException {
        Generator g = new Generator(a, m, src, java, revision);
        g.build();
        // Regenerating: clear the folders this tool wrote last time (only where its PORTING.md says it did).
        if (Files.isRegularFile(dir.resolve("PORTING.md")) && Text.read(dir.resolve("PORTING.md")).contains("generated by horizon-port")) {
            for (String sub : List.of("mixins", "todo")) deleteTree(dir.resolve(sub));
        }
        for (Map.Entry<String, String> f : g.files.entrySet()) {
            Path p = dir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
        }
        // Only a bundle on hook points alone may build on newer commits; injections into Horizon's own functions and
        // game_side.rs (which reads Game's fields) need exactly the commit they were made on.
        Integer hooks = g.rawTargets || g.gameSide() ? null : g.hooksApi;
        NativePackage.Outcome pack = NativePackage.write(dir, jar, g.id, g.cargoVersion(), "26.3", revision, g.mixinsRevision(), hooks, g.gaps());
        String installed = "";
        if (pack.full() && install != null) installed = ", installed as " + NativePackage.install(pack.file(), g.id, install);
        Path porting = dir.resolve("PORTING.md");
        Files.writeString(porting, Text.read(porting) + readyDoc(g.id, pack));
        return new Generated(g.active + " injections on, " + g.commented + " written commented out for a person to finish"
                + (java != null ? ", " + g.javaCopied + " pieces of original Java copied in" : "")
                + (pack.full() ? "; FULL PORT: " + pack.file().getFileName() + " is ready to install" + installed
                        : "; not a full port (" + pack.gaps().size() + " things left, listed in PORTING.md)"), pack);
    }

    /** What keeps the bundle from being complete, as far as the generator knows (NativePackage adds TODOs and content). */
    private List<String> gaps() {
        List<String> out = new ArrayList<>();
        if (commented > 0) out.add(commented + " mixins are written commented out (notes in `todo/`).");
        if (translation == null) {
            out.add("The mod's Java wasn't translated (use `port`, or `generate --java-src`).");
        } else {
            long left = translation.methods() - translation.translated() - translation.covered();
            if (left > 0) out.add(left + " of " + translation.methods() + " Java methods aren't translated (see `report/translation.md`).");
            long fields = translation.items().stream().filter(i -> i.what().equals("field") && !i.ok()).count();
            if (fields > 0) out.add(fields + " static fields aren't translated (see `report/translation.md`).");
        }
        for (String[] r : porting) if (r[1].startsWith("not ") || r[1].startsWith("partly")) out.add(r[0] + ": " + r[1] + ".");
        if (a.intermediary()) out.add("The jar uses intermediary names (a Minecraft version before 26.1).");
        for (Feature f : a.features()) {
            if (f.category().equals("mixin") && f.status().equals("needs-translation")) out.add(ClassFile.simpleName(f.source()) + ": " + f.detail() + ".");
            if (f.category().equals("content") && f.detail().startsWith("registers")) out.add(ClassFile.simpleName(f.source()) + " " + f.detail() + ": that needs a content mod (.hmod).");
        }
        return out;
    }

    private static String readyDoc(String id, NativePackage.Outcome pack) {
        StringBuilder b = new StringBuilder("\n## Ready to use?\n\n");
        if (pack.full()) {
            b.append("**Yes: this is a full port.** `").append(id).append(".hnmod` is Horizon's file for a native mod. To play it:\n\n")
                    .append("1. Put it in `horizon-native-mods/` in Horizon's game folder (`%APPDATA%\\horizon` on Windows, `~/.local/share/horizon` on Linux) and add the line `mod.")
                    .append(id).append(":true` to `horizon-mods.txt` there (the app's \"Add it to Horizon\" does both).\n")
                    .append("2. In Horizon's launcher, turn on Settings > Game > Native mods and press **Build** on the Mods page, or run\n")
                    .append("   `horizon-launcher native-build --trust-native-code`. The launcher compiles it into the game (the first build downloads Rust and takes 10 to 30 minutes).\n\n")
                    .append("Native mods run with your rights and aren't sandboxed: build only code you trust.\n");
        } else {
            b.append("**No: this isn't a full port**, so no `.hnmod` was made. What's left:\n\n");
            for (String g : pack.gaps()) b.append(g.startsWith("  ") ? "  - " + g.strip() : "- " + g).append('\n');
        }
        return b.toString();
    }

    private void build() throws IOException {
        String dir = "mixins/" + id + "/";
        List<String[]> keys = keys();

        // Hook points: Fabric events, and mixins whose target is a hook point.
        Map<String, List<String>> hookUses = new LinkedHashMap<>();
        Map<String, Set<String>> hookClasses = new LinkedHashMap<>();
        for (Feature f : a.features()) {
            String hook = hookName(f.horizon());
            if (!f.category().equals("event") || hook == null) continue;
            hookUses.computeIfAbsent(hook, k -> new ArrayList<>()).add("Fabric " + f.detail() + " in " + ClassFile.simpleName(f.source())
                    + (f.note() != null ? ". " + f.note() : ""));
            hookClasses.computeIfAbsent(hook, k -> new LinkedHashSet<>()).add(f.source());
            addJava(hook, eventJava(f));
        }
        for (Injection inj : a.injections()) {
            String hook = inj.target() == null ? null : Json.str(inj.target().get("hook"));
            if (hook == null || inj.kind() == null || !(inj.kind().equals("HEAD") || inj.kind().equals("RETURN"))) continue;
            hookUses.computeIfAbsent(hook, k -> new ArrayList<>()).add("Mixin @" + inj.injector() + "(" + inj.atValue() + ") on "
                    + ClassFile.simpleName(inj.targetClass()) + "." + inj.targetMethod() + " in "
                    + ClassFile.simpleName(inj.mixinClass()) + "." + inj.handler());
            hookClasses.computeIfAbsent(hook, k -> new LinkedHashSet<>()).add(inj.mixinClass());
            mixinHooks.add(hook);
            addJava(hook, java == null ? null : java.method(inj.mixinClass(), inj.handler()));
        }
        for (Injection inj : a.injections()) {
            if (!joinPoint(inj)) continue;
            hookUses.computeIfAbsent("tick", k -> new ArrayList<>()).add("Mixin @Inject on ClientPacketListener.handleLogin in "
                    + ClassFile.simpleName(inj.mixinClass()) + "." + inj.handler() + ": joining a world, seen by the tick hook");
            hookClasses.computeIfAbsent("tick", k -> new LinkedHashSet<>()).add(inj.mixinClass());
        }
        if (gameSide() && translation.game().contains("pub fn j_run_game_queue(") && !hookUses.containsKey("tick")) {
            hookUses.put("tick", new ArrayList<>(List.of("Work the translated code hands to the game thread")));
            glueHooks.add("tick");
        }
        // The Fabric API layer's entry points (jfabricapi.rs): each needs its hook point.
        if (layerFn("tick") != null && !hookUses.containsKey("tick"))
            hookUses.put("tick", new ArrayList<>(List.of("Fabric's client tick, join and disconnect events, run by the Fabric API layer")));
        if (layerFn("frame") != null) hookUses.computeIfAbsent("frame", k -> new ArrayList<>()).add("Fabric's CLIENT_STARTED, run by the Fabric API layer");
        if (layerFn("command") != null && !hookUses.containsKey("command"))
            hookUses.put("command", new ArrayList<>(List.of("The mod's client commands, run by the Fabric API layer's command engine")));
        if (!keys.isEmpty()) hookUses.computeIfAbsent("key", k -> new ArrayList<>()).add("The mod's keys: " + keys.size() + " found");
        if (gameSide() && translation.game().contains("pub fn key_event(") && !hookUses.containsKey("key"))
            hookUses.put("key", new ArrayList<>(List.of("The translated code reads the mod's keys (consumeClick / isDown)")));
        if (gameSide() && translation.game().contains("pub fn key_event(")) glueHooks.add("key");
        // The initializer runs from the first hook that has the game (ensure_init): give it one when nothing else does.
        if (gameSide() && translation.game().contains("pub fn on_init(") && hookUses.keySet().stream().noneMatch(this::hasGame)) {
            hookUses.put("tick", new ArrayList<>(List.of("The mod's initializer, run once on the first tick")));
            glueHooks.add("tick");
        }
        boolean settings = config() != null || a.entrypoints().containsKey("modmenu")
                || a.features().stream().anyMatch(f -> f.category().equals("class") && f.detail().endsWith("Screen"));

        toml.append("[mod]\nid = \"").append(id).append("\"\nversion = \"").append(cargoVersion()).append("\"\npriority = 100\n\n");
        int tomlCompat = toml.length();
        toml.append("[[module]]\nid = \"runtime\"\nfile = \"crates/horizon-client/src/game.rs\"\nname = \"")
                .append(module).append("\"\nsource = \"runtime.rs\"\n");

        if (gameSide()) {
            toml.append("\n[[module]]\nid = \"game_side\"\nfile = \"crates/horizon-client/src/game.rs\"\nname = \"")
                    .append(module).append("_game\"\nsource = \"game_side.rs\"\n");
            files.put(dir + "game_side.rs", gameSideFile());
        }
        hook("mods", "list", dir, listHelper(keys), List.of("The mod's row in Options > Mods."));
        for (Map.Entry<String, List<String>> h : hookUses.entrySet()) {
            String hook = h.getKey();
            if (m.hooks.get(hook) == null || m.hooks.get(hook).get("bindings") == null) {
                porting.add(new String[] {"hook " + hook, "not generated", "No bindings known for this hook point yet."});
                continue;
            }
            hook(hook, hook.replace('_', '-'), dir, hookHelper(hook, h.getValue(), hookClasses.getOrDefault(hook, Set.of()), keys), h.getValue());
        }
        if (settings) {
            hook("settings", "settings", dir, settingsHelper(), List.of("The mod's settings page (it has a config screen)."));
            hook("settings_click", "click", dir, clickHelper(), List.of("Clicks on that page."));
        }

        boolean posed = poseInjections(dir);
        for (Injection inj : a.injections()) {
            String hook = inj.target() == null ? null : Json.str(inj.target().get("hook"));
            if (hook != null && inj.kind() != null && (inj.kind().equals("HEAD") || inj.kind().equals("RETURN"))) continue;
            if (joinPoint(inj) && handlers("join").stream().anyMatch(RustTranslator.Handler::ok)) {
                porting.add(new String[] {"@" + inj.injector() + " on ClientPacketListener.handleLogin (" + ClassFile.simpleName(inj.mixinClass()) + "." + inj.handler() + ")",
                        "on, through the tick hook", "Translated into game_side.rs; the Fabric API layer runs it on the first tick with a player after none."});
                continue;
            }
            if (posed && (posePoint(inj) || poseCovered(inj))) {
                porting.add(new String[] {"@" + inj.injector() + " on " + ClassFile.simpleName(inj.targetClass()) + "." + inj.targetMethod()
                        + " (" + ClassFile.simpleName(inj.mixinClass()) + "." + inj.handler() + ")", "on, through the hand drawing hooks",
                        posePoint(inj) ? "Translated into game_side.rs and run for each hand by `hands-head.rs`; its pose change or its own drawing reach Horizon through `hands-item-pose.rs` and `hands-arm-pose.rs`, and `hands-head.rs` draws what it draws itself."
                                : "Hiding the vanilla draw of a hand the mod draws itself: done by the hand drawing hooks."});
                continue;
            }
            injection(inj, dir);
        }

        if (rawTargets && revision != null) {
            toml.insert(tomlCompat, "# Raw targets are exact functions of one Horizon commit: build on that commit.\n[compatibility]\nhorizon_revision = \""
                    + revision + "\"\n\n");
        } else if (rawTargets) {
            toml.insert(tomlCompat, "# Raw targets are exact functions of one Horizon commit. Pin it (generate --horizon-revision SHA):\n"
                    + "# [compatibility]\n# horizon_revision = \"<40-character commit>\"\n\n");
        }

        files.put(dir + "horizon-mixin.toml", toml.toString());
        files.put(dir + "runtime.rs", runtime(keys));
        if (translation != null) {
            files.put("report/translation.md", TranslationReport.markdown(translation, a.name()));
            porting.add(new String[] {"Java logic (classes that don't need the game)", "translated " + translation.translated() + " of " + translation.methods() + " methods",
                    "In runtime.rs, `pub mod java`. Each method is translated whole or left out with why: report/translation.md."});
            if (translation.gameSide() > 0) {
                porting.add(new String[] {"Java that calls the game", "translated " + translation.gameSide() + " methods",
                        "In game_side.rs: player position, chat, HUD text and boxes, keys, through Horizon's native helpers."});
            }
            for (RustTranslator.Handler h : translation.handlers()) {
                if (h.hook().equals("pose")) {
                    porting.add(new String[] {"Mixin hand code at " + h.where(), !h.ok() ? "not translated" : h.left().isEmpty() ? "translated" : "partly translated",
                            !h.ok() ? "Not translated: " + h.reason() + "." : "game_side.rs `" + h.fn() + "`, run by hands-head.rs" + (h.left().isEmpty() ? "" : ". Left out as TODOs: " + String.join("; ", h.left()))});
                    continue;
                }
                if (h.hook().equals("init")) {
                    porting.add(new String[] {"Fabric initializer at " + h.where(), !h.ok() ? "not translated" : h.left().isEmpty() ? "translated, run once by the hooks" : "partly translated, run once by the hooks",
                            !h.ok() ? "Not translated: " + h.reason() + "." : "game_side.rs `on_init`" + (h.left().isEmpty() ? "" : ". Left out as TODOs: " + String.join("; ", h.left()))});
                    continue;
                }
                porting.add(new String[] {"Fabric " + h.hook() + " handler at " + h.where(),
                        !h.ok() ? "not translated" : h.left().isEmpty() ? "translated, called from the hook" : "partly translated, called from the hook",
                        !h.ok() ? "Not translated: " + h.reason() + ". The hook keeps a TODO."
                                : "game_side.rs `" + h.fn() + "`" + (h.left().isEmpty() ? "" : ". Left out as TODOs: " + String.join("; ", h.left()))});
            }
        }
        files.put("Cargo.toml", cargoToml(dir));
        files.put("compatibility.json", compatibility());
        files.put("PORTING.md", portingDoc());
        files.put(".gitignore", "/target\n");
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    // ---- hook points ----

    private void hook(String hook, String fileBase, String dir, String helper, List<String> uses) {
        Map<String, Object> h = m.hooks.get(hook);
        String injId = uniqueId(fileBase);
        String file = injId + ".rs";
        files.put(dir + file, helper);
        toml.append("\n[[injection]]\nid = \"").append(injId).append("\"\nkind = \"HEAD\"\ntarget = \"")
                .append(Json.str(h.get("target"))).append("\"\nhelper = \"").append(file).append("\"\n");
        appendBindings(hookBindings(hook));
        Object api = h.get("api");
        if (api instanceof Number n) hooksApi = Math.max(hooksApi, n.intValue());
        active++;
        porting.add(new String[] {"hook `" + hook + "`", "on", String.join("; ", uses)});
    }

    /** Whether the hook point hands its helper the game (so the initializer can run from it). */
    private boolean hasGame(String hook) {
        return m.hooks.get(hook) != null && m.hooks.get(hook).get("bindings") != null
                && hookBindings(hook).stream().anyMatch(b -> b.name().equals("game"));
    }

    private List<Binding> hookBindings(String hook) {
        List<Binding> out = new ArrayList<>();
        for (Object b : Json.list(m.hooks.get(hook).get("bindings"))) {
            List<?> t = Json.list(b);
            out.add(new Binding(String.valueOf(t.get(0)), String.valueOf(t.get(1)), String.valueOf(t.get(2))));
        }
        return out;
    }

    private String hookHelper(String hook, List<String> uses, Set<String> classes, List<String[]> keys) {
        List<Binding> bs = hookBindings(hook);
        StringBuilder b = new StringBuilder();
        b.append("// The `").append(hook).append("` hook point (").append(Json.str(m.hooks.get(hook).get("when"))).append(").\n");
        b.append("// Generated by horizon-port from ").append(a.name()).append(' ').append(a.version()).append(". It stands in for:\n");
        for (String u : uses) b.append("//   ").append(u).append('\n');
        hints(b, classes);
        for (JavaSource.Snippet s : hookJava.getOrDefault(hook, List.of())) javaBlock(b, s);
        b.append("fn ").append(id).append('_').append(hook).append('(').append(params(bs)).append(") {\n");
        b.append("    use crate::game::").append(module).append(" as m;\n");
        String game = bs.stream().anyMatch(x -> x.name().equals("game")) ? "game" : null;
        if (game != null) b.append("    // Turned off in Options > Mods: nothing until the next build leaves it out.\n    if !game.native_on(m::ID) {\n        return;\n    }\n");
        if (game != null && gameSide()) b.append("    // The mod's data folder and its initializer, the first time.\n    crate::game::").append(module).append("_game::ensure_init(game);\n");
        String layer = layerFn(hook);
        List<RustTranslator.Handler> hs = layer != null ? layerHandlers(hook) : handlers(hook);
        if (hook.equals("tick") && gameSide() && translation.game().contains("pub fn j_run_game_queue("))
            b.append("    // Work the translated code handed to the game thread (future callbacks).\n    crate::game::").append(module).append("_game::j_run_game_queue(game);\n");
        // Single player runs the tick on the in-process server's Game too: Fabric client tick handlers run on the
        // window's only, or the server's copy takes the key presses and reads its own inventory.
        boolean windowOnly = hook.equals("tick") && game != null;
        if (layer != null) {
            // The Fabric API layer runs the handlers (J_FABRIC, the command trees), in Fabric's order.
            b.append("    // The Fabric API layer (jfabricapi.rs) runs the mod's Fabric ").append(hook.equals("command") ? "commands" : "events").append(":\n");
            for (RustTranslator.Handler h : hs) {
                if (!h.ok()) b.append("    // TODO: the ").append(layerLabel(h)).append(" at ").append(h.where()).append(" wasn't translated: ").append(h.reason()).append(".\n");
                else if (h.left().isEmpty()) b.append("    //   ").append(layerLabel(h)).append(" at ").append(h.where()).append(", translated (").append(h.fn()).append(").\n");
                else {
                    b.append("    //   ").append(layerLabel(h)).append(" at ").append(h.where()).append(", translated except:\n");
                    for (String l : h.left()) b.append("    // TODO: ").append(l).append('\n');
                }
            }
            if (hook.equals("command")) b.append("    if crate::game::").append(module).append("_game::").append(layer).append("(game, command) {\n        *ran = true;\n        return;\n    }\n");
            else b.append("    crate::game::").append(module).append("_game::").append(layer).append("(game);\n");
        }
        for (RustTranslator.Handler h : layer != null ? List.<RustTranslator.Handler>of() : hs) {
            if (h.ok()) {
                b.append("    // The Fabric handler at ").append(h.where()).append(h.left().isEmpty() ? ", translated.\n" : ", translated except:\n");
                for (String l : h.left()) b.append("    // TODO: ").append(l).append('\n');
                String call = "crate::game::" + module + "_game::" + h.fn() + (hook.equals("hud") ? "(game, batch);" : "(game);");
                b.append(windowOnly ? "    if game.native_window() {\n        " + call + "\n    }\n" : "    " + call + "\n");
            } else {
                b.append("    // TODO: the Fabric handler at ").append(h.where()).append(" wasn't translated: ").append(h.reason()).append(".\n");
            }
        }
        boolean keyGlue = hook.equals("key") && gameSide() && translation.game().contains("pub fn key_event(");
        if (keyGlue) b.append("    // Counts presses of the mod's keys for the translated code (consumeClick / isDown).\n    crate::game::")
                .append(module).append("_game::key_event(game, key, pressed);\n");
        boolean done = (!hs.isEmpty() || glueHooks.contains(hook)) && !mixinHooks.contains(hook) && hs.stream().allMatch(h -> h.ok() && h.left().isEmpty());
        if (layer != null && !done) b.append("    // TODO: finish the parts above the converter couldn't translate.\n");
        switch (done || layer != null ? "" : hook) {
            case "" -> {}
            case "key" -> {
                if (!keys.isEmpty()) {
                    b.append("    // The mod's own keys: the player picks them in Options > Mods (list.rs lists them).\n");
                    for (String[] k : keys) {
                        b.append("    if pressed && key == game.native_mod_key(m::ID, \"").append(k[0]).append("\", \"").append(k[2]).append("\") {\n");
                        b.append("        // TODO: what `").append(k[3]).append("` did when pressed.\n    }\n");
                    }
                }
                b.append("    // TODO: port what the Java code did on key presses.\n");
            }
            case "command" -> {
                Set<String> names = commandNames(classes);
                b.append("    let name = command.split(' ').next().unwrap_or(\"\");\n");
                b.append("    // Command names guessed from the Java class's strings: check them.\n");
                b.append("    if !matches!(name, ").append(names.isEmpty() ? "\"" + id + "\"" : String.join(" | ", names.stream().map(n -> "\"" + n + "\"").toList())).append(") {\n        return;\n    }\n");
                b.append("    *ran = true;\n    // TODO: port the command. game.native_chat(text) answers in chat.\n");
            }
            case "hud" -> b.append("    // TODO: port the drawing: game.native_text(batch, text, [x, y], rgb, shadow),\n"
                    + "    // game.native_fill(batch, [x, y, w, h], argb), corners.place(corner, w, h).\n");
            case "tick" -> b.append("    // TODO: port the tick handler. game.native_window() is false in the server's Game.\n");
            default -> b.append("    // TODO: port it.\n");
        }
        b.append("    let _ = (").append(String.join(", ", bs.stream().map(Binding::name).toList())).append(", &m::ID);\n}\n");
        return b.toString();
    }

    private boolean gameSide() {
        return translation != null && translation.game() != null;
    }

    /** The Fabric API layer's function this hook calls in game_side.rs (fabric_tick, fabric_frame, fabric_command), or null. */
    private String layerFn(String hook) {
        String fn = switch (hook) {
            case "tick" -> "fabric_tick";
            case "frame" -> "fabric_frame";
            case "command" -> "fabric_command";
            default -> null;
        };
        return fn != null && gameSide() && translation.game().contains("pub fn " + fn + "(") ? fn : null;
    }

    /** The Fabric events the layer runs from this hook. */
    private static final Map<String, Set<String>> LAYER_HOOKS = Map.of(
            "tick", Set.of("started", "tick_start", "join", "disconnect", "level_tick_start", "level_tick", "tick"),
            "frame", Set.of("started"),
            "command", Set.of("command"));

    private List<RustTranslator.Handler> layerHandlers(String hook) {
        Set<String> evs = LAYER_HOOKS.getOrDefault(hook, Set.of());
        return translation.handlers().stream().filter(h -> evs.contains(h.hook())).toList();
    }

    private static String layerLabel(RustTranslator.Handler h) {
        return switch (h.hook()) {
            case "command" -> h.fn().equals("j_fabric_commands") ? "command tree" : "command's `executes`";
            case "started" -> "CLIENT_STARTED handler";
            case "tick_start" -> "START_CLIENT_TICK handler";
            case "tick" -> "END_CLIENT_TICK handler";
            case "level_tick_start" -> "START_LEVEL_TICK handler";
            case "level_tick" -> "END_LEVEL_TICK handler";
            case "disconnect" -> "DISCONNECT handler";
            default -> h.hook() + " handler";
        };
    }

    private String gameSideFile() {
        return "// Generated by horizon-port from " + a.name() + " " + a.version() + ": the mod's Java that calls the game,\n"
                + "// translated to Horizon's native helpers. Methods it couldn't translate are listed in report/translation.md.\n"
                + "#[allow(unused_imports)]\nuse crate::game::" + module + "::java::*;\n#[allow(unused_imports)]\nuse std::sync::LazyLock;\n\n"
                + translation.game();
    }

    /** Fabric handlers for this hook that were translated (calls), and the reasons for the ones that weren't. */
    private List<RustTranslator.Handler> handlers(String hook) {
        return translation == null ? List.of() : translation.handlers().stream().filter(h -> h.hook().equals(hook)).toList();
    }

    private String listHelper(List<String[]> keys) {
        StringBuilder b = new StringBuilder("// The mod's row in Options > Mods, and its keys (name, label, default key).\n");
        b.append("fn ").append(id).append("_listed(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {\n");
        b.append("    use crate::game::").append(module).append(" as m;\n");
        b.append("    mods.push(crate::game::native_hooks::NativeMod {\n");
        b.append("        id: m::ID.into(),\n        title: format!(\"{} {}\", m::NAME, m::VERSION),\n        description: m::DESCRIPTION.into(),\n");
        b.append("        keys: m::KEYS.iter().map(|[n, l, d]| [n.to_string(), l.to_string(), d.to_string()]).collect(),\n    });\n}\n");
        return b.toString();
    }

    private RustTranslator.Config config() {
        return translation == null ? null : translation.config();
    }

    private String settingsHelper() {
        RustTranslator.Config c = config();
        if (c != null && !c.toggles().isEmpty()) {
            return "// The mod's settings page: one ON/OFF button per option of " + c.type() + ", then a - and a + button per\n"
                    + "// number option the mod's own screen has a slider for (same range and step), kept with Horizon's settings\n"
                    + "// (one file per option in the mod's data folder). Other number and text options keep their values.\n"
                    + "fn " + id + "_settings(game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {\n"
                    + "    use crate::game::" + module + " as m;\n"
                    + "    use crate::game::" + module + "::java::" + c.module() + " as cfg;\n"
                    + "    if mod_id != m::ID {\n        return;\n    }\n"
                    + "    cfg::ensure_loaded(|k| game.native_setting(m::ID, k));\n"
                    + "    let c = cfg::" + c.instance() + ".get();\n"
                    + "    for (key, label) in cfg::" + c.type() + "::TOGGLES {\n"
                    + "        buttons.push(format!(\"{label}: {}\", if c.flag(key) { \"ON\" } else { \"OFF\" }));\n"
                    + "    }\n"
                    + "    for (key, label, ..) in cfg::" + c.type() + "::NUMBERS {\n"
                    + "        buttons.push(format!(\"{label}: {}  [-]\", c.number_text(key)));\n"
                    + "        buttons.push(format!(\"{label}  [+]\"));\n"
                    + "    }\n}\n";
        }
        return settingsTodo();
    }

    private String settingsTodo() {
        Set<String> screens = new TreeSet<>();
        for (Feature f : a.features()) if (f.category().equals("class") && f.detail().endsWith("Screen")) screens.add(ClassFile.simpleName(f.source()));
        return "// The mod's settings page: one button per option, labels pushed in order.\n"
                + "// Stands in for the Java config screen" + (screens.isEmpty() ? "" : " (" + String.join(", ", screens) + ")") + ".\n"
                + "fn " + id + "_settings(game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {\n"
                + "    use crate::game::" + module + " as m;\n"
                + "    if mod_id != m::ID {\n        return;\n    }\n"
                + "    // TODO: push a label per option, e.g. format!(\"Enabled: {}\", if on { \"ON\" } else { \"OFF\" }),\n"
                + "    // reading each with game.native_setting(m::ID, key).\n"
                + "    let _ = (game, buttons);\n}\n";
    }

    private String clickHelper() {
        RustTranslator.Config c = config();
        if (c != null && !c.toggles().isEmpty()) {
            return "// A click on button `button` of the mod's settings page: flips that option, or moves that number a\n"
                    + "// step, and keeps it.\n"
                    + "fn " + id + "_click(game: &mut crate::game::Game<'_>, mod_id: &str, button: usize) {\n"
                    + "    use crate::game::" + module + " as m;\n"
                    + "    use crate::game::" + module + "::java::" + c.module() + " as cfg;\n"
                    + "    if mod_id != m::ID {\n        return;\n    }\n"
                    + "    cfg::ensure_loaded(|k| game.native_setting(m::ID, k));\n"
                    + "    let toggles = cfg::" + c.type() + "::TOGGLES;\n"
                    + "    cfg::" + c.instance() + ".with(|c| {\n"
                    + "        if let Some(&(key, _)) = toggles.get(button) {\n"
                    + "            c.toggle(key);\n"
                    + "        } else if let Some(&(key, ..)) = cfg::" + c.type() + "::NUMBERS.get((button - toggles.len()) / 2) {\n"
                    + "            c.nudge(key, if (button - toggles.len()) % 2 == 0 { -1 } else { 1 });\n"
                    + "        } else {\n"
                    + "            return;\n"
                    + "        }\n"
                    + "        c.save_settings(|k, v| game.native_set_setting(m::ID, k, v));\n"
                    + "    });\n}\n";
        }
        return clickTodo();
    }

    private String clickTodo() {
        return "// A click on button `button` of the mod's settings page.\n"
                + "fn " + id + "_click(game: &mut crate::game::Game<'_>, mod_id: &str, button: usize) {\n"
                + "    use crate::game::" + module + " as m;\n"
                + "    if mod_id != m::ID {\n        return;\n    }\n"
                + "    // TODO: change the option behind `button` and keep it: game.native_set_setting(m::ID, key, value).\n"
                + "    let _ = (game, button);\n}\n";
    }

    // ---- mixins into Horizon's own functions ----

    // ---- first-person hand drawing ----

    /** `@Inject(at = @At(value = "INVOKE", target = "...PoseStack;pushPose()V"))` into the arm-with-item drawing. */
    private static boolean posePoint(Injection inj) {
        return inj.targetClass() != null && ClassFile.simpleName(inj.targetClass()).equals("ItemInHandRenderer")
                && Set.of("submitArmWithItem", "renderArmWithItem").contains(inj.targetMethod()) && "Inject".equals(inj.injector())
                && "INVOKE".equals(inj.atValue()) && inj.atTarget() != null && inj.atTarget().contains("pushPose");
    }

    /** The same mixin's wrappers around the vanilla arm and item draws there, and its RETURN reset. */
    private boolean poseCovered(Injection inj) {
        if (inj.targetClass() == null || !ClassFile.simpleName(inj.targetClass()).equals("ItemInHandRenderer")
                || !Set.of("submitArmWithItem", "renderArmWithItem").contains(inj.targetMethod())) return false;
        boolean sameMixin = a.injections().stream().anyMatch(o -> posePoint(o) && o.mixinClass().equals(inj.mixinClass()));
        if (!sameMixin) return false;
        if ("RETURN".equals(inj.atValue())) return true;
        return "WrapOperation".equals(inj.injector()) && inj.atTarget() != null && (inj.atTarget().contains("renderPlayerArm") || inj.atTarget().contains("renderItem"));
    }

    /** Mixins that are events in Horizon: ClientPacketListener.handleLogin is joining a world (the tick hook sees it). */
    private List<RustTranslator.EventHook> eventHooks() {
        List<RustTranslator.EventHook> out = new ArrayList<>();
        for (Injection inj : a.injections()) {
            if (joinPoint(inj)) out.add(new RustTranslator.EventHook("join", ClassFile.simpleName(inj.mixinClass()).replace('$', '.'), inj.handler()));
        }
        return out;
    }

    private static boolean joinPoint(Injection inj) {
        return inj.targetClass() != null && inj.targetClass().endsWith("ClientPacketListener") && "handleLogin".equals(inj.targetMethod())
                && "Inject".equals(inj.injector());
    }

    /** The handlers the translator puts on the game side, with the vanilla method's parameters by role. */
    private List<RustTranslator.PoseHook> poseHooks() {
        List<RustTranslator.PoseHook> out = new ArrayList<>();
        for (Injection inj : a.injections()) {
            if (!posePoint(inj)) continue;
            // submitArmWithItem(player, frameInterp, xRot, hand, attack, stack, inverseArmHeight, pose, nodes, light), then CallbackInfo.
            // The translator knows classes by their source name (Outer.Inner), without the package.
            out.add(new RustTranslator.PoseHook(ClassFile.simpleName(inj.mixinClass()).replace('$', '.'), inj.handler(), "pose_" + snake(handlerBase(inj.handler())),
                    List.of("player", "partial", "xrot", "hand", "attack", "stack", "inverse_height", "pose", "drop", "drop", "drop")));
        }
        return out;
    }

    /**
     * Horizon draws first-person hands in Game::hand_geometry: each hand's matrix is the view times
     * `first_person::item_pose(..)` (or `player_arm_pose(..)` for the bare arm). hand_geometry has closures, and
     * horizon-mixins takes only HEAD hooks in such a function (E_UNSUPPORTED_CONTROL_FLOW), so: one cancellable
     * HEAD runs the mod's translated hand code for each hand, draws the hands by calling hand_geometry itself (its
     * own HEAD then lets the body run), adds the arms and items the mod draws itself, and returns that;
     * cancellable HEADs on item_pose and player_arm_pose (the same way) multiply in the pose the mod made, or hide
     * a hand it draws itself, while that drawing runs. Returns whether they were written.
     */
    private boolean poseInjections(String dir) throws IOException {
        if (translation == null) return false;
        List<RustTranslator.Handler> hs = translation.handlers().stream().filter(h -> h.hook().equals("pose") && h.ok()).toList();
        if (hs.isEmpty()) return false;
        String game = "crate::game::" + module + "_game";
        String target = "horizon_client::game::Game<'_>::hand_geometry";
        String uses = String.join(", ", hs.stream().map(h -> h.where()).toList());

        String head = "// HEAD of Horizon's first-person hand drawing (Game::hand_geometry, `FirstPersonHandsAndItemsRenderer`): runs the mod's\n"
                + "// hand code for each hand Horizon shows (" + uses + ", translated in game_side.rs), then draws the\n"
                + "// hands with hand_geometry itself (the item-pose/arm-pose hooks apply the mod's poses meanwhile) and adds the\n"
                + "// arms and items the mod draws itself (renderRightHand, renderLeftHand, renderItem), placed in the world as\n"
                + "// hand_geometry places the hands. A HEAD hook that cancels: hand_geometry has closures, where horizon-mixins\n"
                + "// takes no RETURN or call hooks.\n"
                + "fn " + id + "_hands_head(game: &mut crate::game::Game<'_>, alpha: f64) -> std::ops::ControlFlow<Vec<Vertex>> {\n"
                + "    use std::sync::atomic::{AtomicBool, Ordering};\n"
                + "    // set while the call below runs, so its own HEAD lets the body draw\n"
                + "    static INSIDE: AtomicBool = AtomicBool::new(false);\n"
                + "    if INSIDE.load(Ordering::Relaxed) || !game.native_on(crate::game::" + module + "::ID) {\n"
                + "        return std::ops::ControlFlow::Continue(());\n    }\n"
                + "    " + game + "::ensure_init(game);\n"
                + "    // the pose hooks sit in Horizon's library crate, which can't see game.rs: they ask through this\n"
                + "    let _ = horizon_client::first_person::" + module + "_pose::POSE_MOTION.set(" + game + "::pose_motion);\n"
                + "    " + game + "::pose_frame(game, alpha);\n"
                + "    INSIDE.store(true, Ordering::Relaxed);\n"
                + "    let mut value = game.hand_geometry(alpha);\n"
                + "    INSIDE.store(false, Ordering::Relaxed);\n"
                + "    let draws = " + game + "::pose_end();\n"
                + "    if draws.is_empty() {\n        return std::ops::ControlFlow::Break(value);\n    }\n"
                + "    let a = alpha as f32;\n"
                + "    let view = horizon_client::camera::view_matrix(game.yaw, game.pitch);\n"
                + "    let [sway_x, sway_y] = game.sway.rotation(a, game.pitch, game.yaw);\n"
                + "    let to_world = view.inverse() * glam::Mat4::from_rotation_x(sway_x.to_radians()) * glam::Mat4::from_rotation_y(sway_y.to_radians());\n"
                + "    let eye = game.eye_position().unwrap_or_default();\n"
                + "    let light = game.world.light(eye[0].floor() as i32, eye[1].floor() as i32, eye[2].floor() as i32);\n"
                + "    let packed = (u32::from(light >> 4) * 16) | ((u32::from(light & 15) * 16) << 8);\n"
                + "    for d in draws {\n"
                + "        let m = to_world * glam::Mat4::from_cols_array(&d.m);\n"
                + "        if let Some(left) = d.arm {\n            game.first_person_arm(left, m, packed, &mut value);\n        }\n"
                + "        if let Some((Some(stack), context)) = &d.item {\n"
                + "            use horizon_assets::item_model::DisplayContext as C;\n"
                + "            // Seeded like vanilla: the context's ordinal.\n"
                + "            let (display, seed) = match *context {\n"
                + "                \"third_person_left_hand\" => (C::ThirdPersonLeftHand, 1),\n"
                + "                \"third_person_right_hand\" => (C::ThirdPersonRightHand, 2),\n"
                + "                \"first_person_left_hand\" => (C::FirstPersonLeftHand, 3),\n"
                + "                _ => (C::FirstPersonRightHand, 4),\n            };\n"
                + "            let owner = game.player_owner(Some(stack));\n"
                + "            let state = game.resolve_item(stack, display, Some(&owner), seed);\n"
                + "            game.submit_item_world(&state, m, horizon_client::block_entity_render::vertex_light(light), &mut value);\n"
                + "        }\n    }\n"
                + "    std::ops::ControlFlow::Break(value)\n}\n";
        rawInjection(dir, "hands-head", "HEAD", target, null, head,
                List.of(new Binding("game", "&mut crate::game::Game<'_>", "&mut *self"), new Binding("alpha", "f64", "alpha")), true);

        // item_pose and player_arm_pose are in Horizon's library crate (first_person.rs), which can't name the game
        // crate's modules: a public module there holds the game side's pose_motion, set by hands-head.rs.
        // (plain comments: horizon-mixins refuses `//!` file attributes in a module source)
        String bridge = "// The hand-pose hooks in first_person.rs are in Horizon's library crate, which can't see the mod's code in\n"
                + "// game.rs (the game crate): hands-head.rs puts the game side's pose_motion here.\n\n"
                + "/// For the hand on the `left` arm: the mod's change to its pose (column-major), and whether the mod draws it itself.\n"
                + "pub static POSE_MOTION: std::sync::OnceLock<fn(bool) -> (Option<[f32; 16]>, bool)> = std::sync::OnceLock::new();\n\n"
                + "/// POSE_MOTION's answer, or no change before the mod has run.\n"
                + "pub fn pose_motion(left: bool) -> (Option<[f32; 16]>, bool) {\n"
                + "    POSE_MOTION.get().map_or((None, false), |f| f(left))\n}\n";
        files.put(dir + "pose-bridge.rs", bridge);
        toml.append("\n[[module]]\nid = \"pose_bridge\"\nfile = \"crates/horizon-client/src/first_person.rs\"\nname = \"")
                .append(module).append("_pose\"\nsource = \"pose-bridge.rs\"\nvisibility = \"pub\"\n");

        // item_pose and player_arm_pose begin with `use` lines, which horizon-mixins counts as nested items (no
        // RETURN hooks there either): a cancellable HEAD calls the function itself and changes what it returns.
        String wrap = "// HEAD of `first_person::%1$s(..)`, while Horizon draws the hands (hands-head.rs): the pose the mod's\n"
                + "// hand code made comes first (the Java code changed the PoseStack before vanilla's own arm and item\n"
                + "// transforms); a hand the mod draws itself is hidden here and drawn by hands-head.rs. A HEAD hook that\n"
                + "// cancels: %1$s has `use` lines, where horizon-mixins takes no RETURN hooks.\n"
                + "fn %2$s(%3$s) -> std::ops::ControlFlow<glam::Mat4> {\n"
                + "    use std::sync::atomic::{AtomicBool, Ordering};\n"
                + "    // set while the call below runs, so its own HEAD lets the body run\n"
                + "    static INSIDE: AtomicBool = AtomicBool::new(false);\n"
                + "    if INSIDE.load(Ordering::Relaxed) {\n        return std::ops::ControlFlow::Continue(());\n    }\n"
                + "    let c = match crate::first_person::" + module + "_pose::pose_motion(left) {\n"
                + "        (_, true) => return std::ops::ControlFlow::Break(glam::Mat4::ZERO),\n"
                + "        (None, false) => return std::ops::ControlFlow::Continue(()),\n"
                + "        (Some(c), false) => c,\n    };\n"
                + "    INSIDE.store(true, Ordering::Relaxed);\n"
                + "    let m = %1$s(%4$s);\n"
                + "    INSIDE.store(false, Ordering::Relaxed);\n"
                + "    std::ops::ControlFlow::Break(glam::Mat4::from_cols_array(&c) * m)\n}\n";
        List<Binding> common = List.of(new Binding("left", "bool", "left"), new Binding("inverse_height", "f32", "inverse_height"),
                new Binding("attack", "f32", "attack"));
        List<Binding> itemBs = new ArrayList<>(common);
        itemBs.add(new Binding("pose", "ItemPose", "pose"));
        rawInjection(dir, "hands-item-pose", "HEAD", "horizon_client::first_person::item_pose", null,
                String.format(wrap, "item_pose", id + "_hands_item_pose", "left: bool, inverse_height: f32, attack: f32, pose: ItemPose",
                        "left, inverse_height, attack, pose"), itemBs, true);
        rawInjection(dir, "hands-arm-pose", "HEAD", "horizon_client::first_person::player_arm_pose", null,
                String.format(wrap, "player_arm_pose", id + "_hands_arm_pose", "left: bool, inverse_height: f32, attack: f32",
                        "left, inverse_height, attack"), common, true);
        rawTargets = true;
        return true;
    }

    /** An injection into one of Horizon's own functions (pinned to the revision), with its helper file. */
    private void rawInjection(String dir, String fileBase, String kind, String target, String selector, String helper, List<Binding> bs,
            boolean cancellable) {
        String injId = uniqueId(fileBase);
        files.put(dir + injId + ".rs", helper);
        toml.append("\n[[injection]]\nid = \"").append(injId).append("\"\nkind = \"").append(kind).append("\"\ntarget = \"").append(target).append("\"\n");
        if (cancellable) toml.append("cancellable = true\n");
        if (selector != null) toml.append("selector = \"").append(selector).append("\"\nrequire = 1\nordinal = 0\n");
        toml.append("helper = \"").append(injId).append(".rs\"\n");
        appendBindings(bs);
        active++;
    }

    private void injection(Injection inj, String dir) throws IOException {
        current = inj;
        try {
            injectionFor(inj, dir);
        } finally {
            current = null;
        }
    }

    /** The mixin being written, so a skipped one still gets its notes file. */
    private Injection current;

    private void injectionFor(Injection inj, String dir) throws IOException {
        String base = ClassFile.simpleName(inj.mixinClass()) + "-" + handlerBase(inj.handler());
        String injId = uniqueId(kebab(base));
        String fn = (id + "_" + snake(handlerBase(inj.handler()))).replaceAll("_+", "_");
        String what = "@" + inj.injector() + (inj.atValue() != null ? "(" + inj.atValue() + ")" : "") + " on "
                + ClassFile.simpleName(inj.targetClass()) + "." + inj.targetMethod() + " (" + ClassFile.simpleName(inj.mixinClass()) + "." + inj.handler() + ")";
        String kind = inj.kind();
        if (kind == null) {
            skip(what, "No horizon-mixins counterpart: " + String.join(" ", inj.notes()), null, injId, null);
            return;
        }
        boolean bridge = kind.equals("GET") || kind.equals("INVOKE");
        String rust = inj.target() == null ? null : Json.str(inj.target().get("rust"));
        if (rust == null) {
            String hook = inj.target() == null ? null : Json.str(inj.target().get("hook"));
            String why = inj.target() == null
                    ? "No Horizon function mapped for " + ClassFile.simpleName(inj.targetClass()) + "." + inj.targetMethod() + " (run `match`, or map it in a --mappings file)."
                    : hook != null ? "Its target is the `" + hook + "` hook point, an empty function: " + kind + " has nothing inside it to select. Port it in the `" + hook + "` hook's helper."
                    : "Mapped to a helper call, not a function to inject into.";
            skip(what, why, kind, injId, null);
            return;
        }
        List<?> pieces = Json.list(inj.target().get("pieces"));
        if (pieces.size() > 1 && !Boolean.TRUE.equals(inj.target().get("picked"))) {
            skip(what, "Horizon splits " + ClassFile.simpleName(inj.targetClass()) + "." + inj.targetMethod() + " across " + pieces.size()
                    + " functions; pick one: " + String.join("; ", pieces.stream().map(String::valueOf).toList()), kind, injId, rust);
            return;
        }
        if (bridge) {
            bridge(inj, injId, rust, what);
            return;
        }
        Signature sig = signature(rust);
        if (sig == null) {
            skip(what, src == null ? "Run with --horizon-src to read the target's signature." : "`" + rust + "` isn't in the Horizon source given.", kind, injId, rust);
            return;
        }
        if (sig.generic()) {
            skip(what, "`" + rust + "` is generic: horizon-mixins rejects generic targets.", kind, injId, rust);
            return;
        }

        List<Binding> bs = new ArrayList<>();
        String helperRet = null, body;
        Map<String, String> extra = new LinkedHashMap<>();
        boolean on = true;
        String why = null;
        List<Integer> ordinals = List.of(-1);

        // Every kind but HEAD rewrites the target's body, which horizon-mixins refuses to do over
        // `?`, closures, async and most macros rather than risk missing an exit.
        String blocker = kind.equals("HEAD") ? null : bodyBlocker(sig.body());
        if (blocker != null) {
            skip(what, "`" + rust + "` has " + blocker + " in its body: horizon-mixins doesn't rewrite such bodies with " + kind
                    + ". A HEAD hook on it, or a " + kind + " on a function it calls, may work instead.", kind, injId, rust);
            return;
        }

        switch (kind) {
            case "HEAD" -> {
                bs.addAll(paramBindings(sig, rust));
                if (inj.cancellable()) {
                    helperRet = "std::ops::ControlFlow<" + orUnit(sig.ret()) + ">";
                    extra.put("cancellable", "true");
                    body = "    // TODO: port the Java handler. Break(value) returns early (Java's ci.cancel()).\n    std::ops::ControlFlow::Continue(())\n";
                } else {
                    body = "    // TODO: port the Java handler.\n";
                }
            }
            case "RETURN" -> {
                // One $value binding of the target's result type, unit included; the helper returns it.
                String r = orUnit(sig.ret());
                bs.add(new Binding("value", r, "$value"));
                helperRet = r.equals("()") ? null : r;
                body = r.equals("()") ? "    // TODO: port the Java handler (runs as the target returns).\n"
                        : "    // TODO: port the Java handler" + (inj.injector().equals("ModifyReturnValue") ? " (it replaces the value)" : "") + ".\n    value\n";
            }
            case "BEFORE_CALL", "AFTER_CALL", "MODIFY_ARG", "WRAP_OPERATION" -> {
                String callRust = inj.call() == null ? null : Json.str(inj.call().get("rust"));
                if (callRust == null) {
                    skip(what, "The call it hooks (" + inj.atTarget() + ") isn't mapped to a Rust function.", kind, injId, rust);
                    return;
                }
                String selector = callRust.substring(callRust.lastIndexOf(':') + 1);
                int require = countCalls(sig.body(), selector);
                if (require == 0) {
                    skip(what, "`" + rust + "` never calls `" + selector + "`: the mapping of the call is off.", kind, injId, rust);
                    return;
                }
                extra.put("selector", "\"" + selector + "\"");
                extra.put("require", String.valueOf(require));
                // Java's @At without an ordinal hooks every matching call: one injection per call.
                ordinals = inj.atOrdinal() != null ? List.of(inj.atOrdinal()) : range(require);
                Signature callSig = signature(callRust);
                switch (kind) {
                    case "BEFORE_CALL" -> {
                        bs.addAll(paramBindings(sig, rust));
                        body = "    // TODO: port the Java handler (runs before `" + selector + "`).\n";
                    }
                    case "AFTER_CALL" -> {
                        String r = callSig == null ? null : callSig.ret();
                        if (r != null) {
                            extra.put("result", "\"" + escape(r) + "\"");
                            bs.add(new Binding("result", "&mut " + r, "$result_mut"));
                        }
                        body = "    // TODO: port the Java handler (runs after `" + selector + "`).\n";
                    }
                    case "MODIFY_ARG" -> {
                        int arg = inj.argIndex() == null ? 0 : inj.argIndex();
                        List<Param> args = callSig == null ? List.of() : callSig.params().stream().filter(p -> p.self() == null).toList();
                        if (arg >= args.size()) {
                            skip(what, "Can't tell the type of argument " + arg + " of `" + callRust + "`.", kind, injId, rust);
                            return;
                        }
                        extra.put("argument", String.valueOf(arg));
                        String t = args.get(arg).type();
                        bs.add(new Binding("value", t, "$value"));
                        helperRet = t;
                        body = "    // TODO: port the Java handler (it changes argument " + arg + " of `" + selector + "`).\n    value\n";
                    }
                    default -> {
                        if (callSig == null || callSig.generic()) {
                            skip(what, "Can't read the signature of `" + callRust + "` for the wrapper.", kind, injId, rust);
                            return;
                        }
                        List<String> types = new ArrayList<>(), names = new ArrayList<>();
                        int n = 0;
                        for (Param p : callSig.params()) {
                            if (p.self() != null) {
                                String recv = (p.self().equals("&self") ? "&" : "&mut ") + selfType(callRust);
                                bs.add(new Binding("receiver", recv, "$receiver"));
                                types.add(recv);
                                names.add("receiver");
                            } else {
                                bs.add(new Binding("arg" + n, p.type(), "$arg" + n));
                                types.add(p.type());
                                names.add("arg" + n);
                                n++;
                            }
                        }
                        String r = orUnit(callSig.ret());
                        bs.add(new Binding("original", "impl FnOnce(" + String.join(", ", types) + ") -> " + r, "$operation"));
                        helperRet = r;
                        String note = inj.injector().equals("Redirect") ? " A Java @Redirect replaced the call: return your own value instead."
                                : inj.injector().equals("WrapWithCondition") ? " Java's @WrapWithCondition: call `original` only when the condition holds." : "";
                        body = "    // TODO: port the Java handler." + note + "\n    original(" + String.join(", ", names) + ")\n";
                    }
                }
            }
            case "OVERWRITE" -> {
                bs.addAll(paramBindings(sig, rust));
                helperRet = sig.ret();
                body = "    todo!(\"port the Java @Overwrite\")\n";
                on = false;
                why = "OVERWRITE replaces the whole function: off until the Rust is written.";
            }
            default -> {
                on = false;
                body = "    // TODO: port the Java handler.\n";
                why = kind + " needs " + (kind.equals("MODIFY_VARIABLE") ? "the Rust local's name" : kind.equals("MODIFY_CONSTANT") ? "the exact Rust literal"
                        : kind.equals("MODIFY_EXPRESSION_VALUE") ? "the exact Rust expression" : "a choice") + " in `" + rust + "`: fill in `selector`/`variable`.";
                extra.put("selector", "\"TODO\"");
                extra.put("require", "1");
                extra.put("ordinal", "0");
            }
        }

        String file = injId + ".rs";
        StringBuilder h = new StringBuilder();
        h.append("// ").append(kind).append(" on `").append(rust).append("`.\n");
        h.append("// Generated by horizon-port: stands in for the Java ").append(what).append(".\n");
        for (String note : inj.notes()) h.append("// Note: ").append(note).append('\n');
        hints(h, Set.of(inj.mixinClass()));
        if (java != null) javaBlock(h, java.method(inj.mixinClass(), inj.handler()));
        h.append("fn ").append(fn).append('(').append(params(bs)).append(')');
        if (helperRet != null) h.append(" -> ").append(helperRet);
        h.append(" {\n");
        List<String> unused = bs.stream().map(Binding::name).filter(nm -> !body.contains(nm + ")") && !body.contains(nm + "\n") && !body.contains("(" + nm)).toList();
        if (!unused.isEmpty() && !body.contains("todo!")) {
            String refs = String.join(", ", unused.stream().map(u -> "&" + u).toList());
            h.append("    let _ = ").append(unused.size() == 1 ? refs : "(" + refs + ")").append(";\n");
        }
        h.append(body).append("}\n");
        files.put(dir + file, h.toString());

        rawTargets |= on;
        for (int ordinal : ordinals) {
            String oneId = ordinal < 0 || ordinals.size() == 1 ? injId : uniqueId(injId + "-" + ordinal);
            StringBuilder t = new StringBuilder();
            t.append("\n[[injection]]\nid = \"").append(oneId).append("\"\nkind = \"").append(kind).append("\"\ntarget = \"").append(rust).append("\"\n");
            for (Map.Entry<String, String> e : extra.entrySet()) t.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            if (ordinal >= 0) t.append("ordinal = ").append(ordinal).append('\n');
            t.append("helper = \"").append(file).append("\"\n");
            StringBuilder bt = new StringBuilder();
            appendBindings(bt, bs);
            t.append(bt);
            if (on) {
                toml.append(t);
                active++;
            } else {
                toml.append("\n# Off: ").append(why).append('\n').append(t.toString().strip().lines().map(l -> "# " + l).reduce((x, y) -> x + "\n" + y).orElse("")).append('\n');
                commented++;
            }
        }
        porting.add(new String[] {what, on ? "on" + (ordinals.size() > 1 ? " (" + ordinals.size() + " call sites)" : "") : "off", on ? (why != null ? why : "Fill in " + file + ".") : why});
    }

    private void bridge(Injection inj, String injId, String rust, String what) {
        boolean get = inj.injector().equals("Accessor");
        String setter = get && inj.handler().replaceAll(".*\\$", "").startsWith("set") ? "SET" : null;
        String kind = setter != null ? setter : get ? "GET" : "INVOKE";
        toml.append("\n[[bridge]]\nid = \"").append(injId).append("\"\nkind = \"").append(kind).append("\"\n");
        if (kind.equals("INVOKE")) toml.append("target = \"").append(rust).append("\"\n");
        else {
            int cut = rust.lastIndexOf("::");
            toml.append("target = \"").append(rust, 0, cut).append("\"\nmember = \"").append(rust.substring(cut + 2)).append("\"\n");
        }
        active++;
        String gen = "__horizon_mixins_" + id + "_" + injId.replace('-', '_');
        porting.add(new String[] {what, "on (bridge)", "Call `" + gen + "` where the Java code called the accessor."});
    }

    private void skip(String what, String why, String kind, String injId, String rust) {
        commented++;
        toml.append("\n# Off: ").append(what).append("\n#   ").append(why).append('\n');
        if (kind != null) {
            toml.append("# [[injection]]\n# id = \"").append(injId).append("\"\n# kind = \"").append(kind).append("\"\n# target = \"")
                    .append(rust != null ? rust : "TODO").append("\"\n");
        }
        String note = why;
        if (current != null) {
            // Notes for whoever ports it: outside mixins/, so the build never reads them.
            String file = "todo/" + injId + ".rs";
            StringBuilder b = new StringBuilder();
            b.append("// Not ported yet: the Java ").append(what).append(".\n// Why it's off: ").append(why).append('\n');
            if (kind != null) b.append("// horizon-mixins kind: ").append(kind).append(rust != null ? " on `" + rust + "`" : "").append('\n');
            for (String n : current.notes()) b.append("// Note: ").append(n).append('\n');
            hints(b, Set.of(current.mixinClass()));
            try {
                if (java != null) javaBlock(b, java.method(current.mixinClass(), current.handler()));
            } catch (IOException e) {
                b.append("// (couldn't read its Java: ").append(e.getMessage()).append(")\n");
            }
            files.put(file, b.toString());
            note = why + " Notes: `" + file + "`.";
        }
        porting.add(new String[] {what, "off", note});
    }

    // ---- reading Horizon's source ----

    private Signature signature(String path) throws IOException {
        if (src == null) return null;
        RustFn fn = src.find(path);
        if (fn == null) return null;
        List<String> lines = Text.lines(src.root.resolve(fn.file()));
        StringBuilder text = new StringBuilder();
        for (int i = fn.line() - 1; i < lines.size() && i < fn.line() + 400; i++) text.append(lines.get(i)).append('\n');
        String s = text.toString();
        int at = s.indexOf("fn " + fn.name());
        if (at < 0) return null;
        int i = at + 3 + fn.name().length();
        boolean generic = false;
        if (i < s.length() && s.charAt(i) == '<') {
            int end = matching(s, i, '<', '>');
            String gens = s.substring(i + 1, end);
            generic = gens.chars().anyMatch(Character::isUpperCase) || gens.contains("const ");
            i = end + 1;
        }
        int open = s.indexOf('(', i);
        int close = matching(s, open, '(', ')');
        List<Param> params = new ArrayList<>();
        for (String p : splitTop(s.substring(open + 1, close))) {
            p = p.trim();
            if (p.isEmpty()) continue;
            p = p.replaceAll("#\\[[^\\]]*\\]\\s*", "");
            if (p.matches("&\\s*mut\\s+self")) params.add(new Param("self", null, "&mut self"));
            else if (p.matches("&\\s*('\\w+\\s+)?self")) params.add(new Param("self", null, "&self"));
            else if (p.matches("(mut\\s+)?self")) params.add(new Param("self", null, "self"));
            else {
                int colon = p.indexOf(':');
                if (colon < 0) return null;
                String name = p.substring(0, colon).trim().replaceFirst("^mut\\s+", "");
                if (!name.matches("[A-Za-z_]\\w*")) return null;
                params.add(new Param(name, NAMED_LIFETIME.matcher(p.substring(colon + 1).trim()).replaceAll("'_"), null));
            }
        }
        int bodyOpen = -1, depth = 0;
        String ret = null;
        for (int k = close + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' && k > 0 && s.charAt(k - 1) != '-' || c == ')' || c == ']') depth--;
            else if (depth == 0 && (c == '{' || c == ';')) { bodyOpen = c == '{' ? k : -1; break; }
        }
        String between = s.substring(close + 1, bodyOpen < 0 ? s.length() : bodyOpen);
        int arrow = between.indexOf("->");
        if (arrow >= 0) {
            String r = between.substring(arrow + 2);
            int where = r.indexOf(" where ");
            if (where < 0) where = r.indexOf("\nwhere");
            ret = NAMED_LIFETIME.matcher((where >= 0 ? r.substring(0, where) : r).trim()).replaceAll("'_");
            if (ret.isEmpty() || ret.equals("()")) ret = null;
            if (ret != null && (ret.contains("impl ") || ret.contains("Self"))) ret = ret.replace("Self", selfType(path));
        }
        if (between.contains("where ") || between.contains("where\n")) generic = true;
        String body = bodyOpen < 0 ? "" : s.substring(bodyOpen, Math.min(s.length(), matching(s, bodyOpen, '{', '}') + 1));
        return new Signature(generic, params, ret, body);
    }

    private List<Binding> paramBindings(Signature sig, String path) {
        List<Binding> out = new ArrayList<>();
        for (Param p : sig.params()) {
            if (p.self() != null) {
                String t = selfType(path);
                switch (p.self()) {
                    case "&mut self" -> out.add(new Binding("this", "&mut " + t, "&mut *self"));
                    case "&self" -> out.add(new Binding("this", "&" + t, "&*self"));
                    default -> out.add(new Binding("this", "&" + t, "&self"));
                }
                continue;
            }
            String t = p.type();
            if (t.startsWith("&mut ")) out.add(new Binding(p.name(), t, "&mut *" + p.name()));
            else if (t.startsWith("&") || PRIMITIVES.contains(t)) out.add(new Binding(p.name(), t, p.name()));
            else out.add(new Binding(p.name(), "&" + t, "&" + p.name()));
        }
        return out;
    }

    /** horizon_client::game::Game<'_>::tick to Game<'_>; ...::<GameLevel<'_> as LevelAccess>::f to GameLevel<'_>. */
    private static String selfType(String path) {
        String owner = path.substring(0, path.lastIndexOf("::"));
        if (owner.endsWith(">") && owner.contains("::<")) {
            String seg = owner.substring(owner.lastIndexOf("::<") + 3, owner.length() - 1);
            return seg.substring(0, seg.lastIndexOf(" as "));
        }
        int depth = 0;
        for (int i = owner.length() - 1; i >= 0; i--) {
            char c = owner.charAt(i);
            if (c == '>') depth++;
            else if (c == '<') depth--;
            else if (depth == 0 && c == ':') return owner.substring(i + 1);
        }
        return owner;
    }

    private static final Pattern TRY = Pattern.compile("[)\\]\\w]\\?");
    /** A closure starts where an expression starts; `a || b` and `x |= y` follow a value instead. */
    private static final Pattern CLOSURE = Pattern.compile("(?:[(,={;]|=>|\\breturn|\\bmove)\\s*\\|[^|;{}]*\\|");
    private static final Pattern MACRO = Pattern.compile("\\b(\\w+)!\\s*[(\\[{]");
    private static final Set<String> BODY_MACROS = Set.of("format", "matches", "vec");

    /** What in a body stops horizon-mixins rewriting it (its docs: `?`, closures, async, macros), or null. */
    static String bodyBlocker(String body) {
        String code = body.replaceAll("//[^\\n]*", "").replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])'", "' '");
        if (TRY.matcher(code).find()) return "a `?`";
        if (CLOSURE.matcher(code).find()) return "a closure";
        if (code.contains(".await") || code.contains("async ")) return "async code";
        Matcher mm = MACRO.matcher(code);
        while (mm.find()) if (!BODY_MACROS.contains(mm.group(1))) return "a `" + mm.group(1) + "!` macro";
        return null;
    }

    private static int countCalls(String body, String name) {
        Matcher mm = Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "\\s*\\(").matcher(body.replaceAll("//[^\\n]*", ""));
        int n = 0;
        while (mm.find()) n++;
        return n;
    }

    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) depth++;
            else if (ch == c && !(c == '>' && i > 0 && s.charAt(i - 1) == '-') && --depth == 0) return i;
        }
        return s.length() - 1;
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if ((c == '>' && (i == 0 || s.charAt(i - 1) != '-')) || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    // ---- what the Java side gives ----

    /** The mod's keys: [name, label, default key, Java key name], from its KeyMapping strings. */
    private List<String[]> keys() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Feature f : a.features()) {
            if (!f.category().equals("event") || !"hook `key`".equals(f.horizon())) continue;
            for (String s : a.classStrings().getOrDefault(f.source(), List.of())) {
                if (KEY_NAME.matcher(s).matches() && !s.startsWith("key.categories.") && !s.startsWith("key.keyboard.") && !s.startsWith("key.mouse.")) found.add(s);
            }
        }
        List<String[]> out = new ArrayList<>();
        Map<String, String> lang = java == null ? Map.of() : RustTranslator.lang(java.root());
        for (String k : found) {
            String name = k.substring(k.lastIndexOf('.') + 1);
            // The mod's own name for the key, else its id in words.
            String label = lang.getOrDefault(k, Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('_', ' '));
            out.add(new String[] {name, label, java == null ? "key.keyboard.unknown" : java.defaultKey(k), k});
        }
        return out;
    }

    private Set<String> commandNames(Set<String> classes) {
        Set<String> names = new TreeSet<>();
        for (String c : classes) {
            for (String s : a.classStrings().getOrDefault(c, List.of())) {
                if (s.matches("[a-z][a-z0-9_]{1,24}") && (s.equals(id) || s.startsWith(id) || id.startsWith(s))) names.add(s);
            }
        }
        return names;
    }

    private void addJava(String hook, JavaSource.Snippet s) {
        if (s == null) return;
        List<JavaSource.Snippet> l = hookJava.computeIfAbsent(hook, k -> new ArrayList<>());
        if (l.stream().noneMatch(x -> x.file().equals(s.file()) && x.line() == s.line())) l.add(s);
    }

    /** Where the Java code registers this event: "HudRenderCallback.EVENT" as written, else its last part. */
    private JavaSource.Snippet eventJava(Feature f) throws IOException {
        if (java == null) return null;
        JavaSource.Snippet s = java.event(f.source(), f.detail());
        return s != null ? s : java.event(f.source(), f.detail().substring(f.detail().lastIndexOf('.') + 1));
    }

    private static final int JAVA_LINES = 150;

    /** The original Java as comments, to port from. */
    private void javaBlock(StringBuilder b, JavaSource.Snippet s) {
        if (s == null) return;
        javaCopied++;
        b.append("//\n// ").append(java.label).append(", ").append(s.file()).append(':').append(s.line()).append(":\n");
        String[] lines = s.text().split("\n", -1);
        for (int i = 0; i < lines.length && i < JAVA_LINES; i++) b.append(lines[i].isEmpty() ? "// |" : "// | " + lines[i]).append('\n');
        if (lines.length > JAVA_LINES) b.append("// | ... ").append(lines.length - JAVA_LINES).append(" more lines in ").append(s.file()).append('\n');
    }

    /** The Java game calls a class makes, and what each is in Horizon. */
    private void hints(StringBuilder b, Set<String> classes) {
        Set<String> calls = new TreeSet<>();
        for (String c : classes) calls.addAll(a.classCalls().getOrDefault(c, Set.of()));
        if (calls.isEmpty()) return;
        b.append("//\n// Game calls the Java code makes here, and what they are in Horizon:\n");
        int shown = 0;
        for (String call : calls) {
            if (shown++ == 40) { b.append("//   ... ").append(calls.size() - 40).append(" more: see the report's call list.\n"); break; }
            String[] p = call.split("#", 2);
            Map<String, Object> e = m.method(p[0], p[1]);
            String horizon = e == null ? "(not mapped yet)" : Analyzer.orElse(Mappings.horizonTarget(e), Analyzer.orElse(Json.str(e.get("status")), "mapped"));
            if (e != null && e.get("note") != null) horizon += ". " + e.get("note");
            if (e != null && Boolean.TRUE.equals(e.get("suggested"))) horizon += " (suggested, " + e.get("confidence") + ")";
            b.append("//   ").append(ClassFile.simpleName(p[0])).append('.').append(p[1]).append(" -> ").append(horizon).append('\n');
        }
    }

    // ---- the bundle's other files ----

    private String runtime(List<String[]> keys) {
        StringBuilder b = new StringBuilder();
        b.append("// ").append(a.name()).append(", ported to Horizon by horizon-port from ").append(a.jar()).append(".\n");
        b.append("// This file is both the bundle's Cargo library (its tests) and the module the bundle adds to\n");
        b.append("// the game (crate::game::").append(module).append("). Put the mod's own logic here, in plain Rust\n");
        b.append("// that doesn't need the game, and call it from the helpers. No `//!` comments: module payloads\n// can't hold inner attributes.\n\n");
        b.append("/// The mod's id (its settings folder), name, version and description.\n");
        b.append("pub const ID: &str = \"").append(id).append("\";\n");
        b.append("pub const NAME: &str = \"").append(escape(a.name())).append("\";\n");
        b.append("pub const VERSION: &str = \"").append(escape(a.version())).append("\";\n");
        b.append("pub const DESCRIPTION: &str = \"").append(escape(a.description().isEmpty() ? a.name() + ", ported from Fabric." : a.description())).append("\";\n\n");
        b.append("/// The mod's keys for Options > Mods: name, label, default key.\n");
        b.append("pub const KEYS: &[[&str; 3]] = &[");
        if (keys.isEmpty()) b.append("];\n");
        else {
            b.append('\n');
            for (String[] k : keys) b.append("    [\"").append(k[0]).append("\", \"").append(escape(k[1])).append("\", \"").append(k[2]).append("\"],\n");
            b.append("];\n");
        }
        if (translation != null) b.append('\n').append(TranslationReport.module(translation));
        b.append("\n#[cfg(test)]\nmod tests {\n    use super::*;\n\n    #[test]\n    fn names_are_set() {\n");
        b.append("        assert_eq!(ID, \"").append(id).append("\");\n        assert!(!NAME.is_empty());\n");
        b.append("        assert!(KEYS.iter().all(|[n, _, d]| !n.is_empty() && d.starts_with(\"key.\")));\n    }\n");
        RustTranslator.Config c = config();
        if (c != null && !c.toggles().isEmpty()) {
            String t = "java::" + c.module() + "::" + c.type();
            b.append("\n    #[test]\n    fn settings_round_trip() {\n");
            b.append("        let key = ").append(t).append("::TOGGLES[0].0;\n");
            b.append("        let mut c = ").append(t).append("::new();\n        c.toggle(key);\n");
            b.append("        let mut kept = std::collections::HashMap::new();\n");
            b.append("        c.save_settings(|k, v| {\n            kept.insert(k.to_string(), v.to_string());\n        });\n");
            b.append("        let mut d = ").append(t).append("::new();\n        d.load_settings(|k| kept.get(k).cloned());\n");
            b.append("        assert_eq!(d.flag(key), c.flag(key));\n        assert_ne!(d.flag(key), ").append(t).append("::new().flag(key));\n    }\n");
        }
        b.append("}\n");
        return b.toString();
    }

    private String cargoToml(String dir) {
        return "[package]\nname = \"horizon-mod-" + id.replace('_', '-') + "\"\nversion = \"" + cargoVersion() + "\"\nedition = \"2024\"\npublish = false\n"
                + "description = \"" + escape(a.name()) + ", ported from Fabric to a Horizon native mod by horizon-port.\"\n\n"
                + "# The mod's logic and its tests: the same file the bundle adds to the game.\n[lib]\npath = \"" + dir + "runtime.rs\"\n";
    }

    private String compatibility() throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("game", "26.3");
        c.put("hooks", hooksApi);
        c.put("horizon_repository", "https://github.com/horizon-rust/horizon");
        if (revision != null) c.put("horizon_revision", revision);
        c.put("horizon_mixins_repository", "https://github.com/horizon-rust/horizon-mixins");
        String mixins = mixinsRevision();
        if (mixins != null) c.put("horizon_mixins_revision", mixins);
        return Json.write(c);
    }

    /** The horizon-mixins commit the Horizon source's launcher builds with (native_mods::MIXINS_REV). */
    private String mixinsRevision() throws IOException {
        if (src == null) return null;
        Path f = src.root.resolve("launcher/src/native_mods.rs");
        if (!Files.isRegularFile(f)) return null;
        Matcher mm = Pattern.compile("MIXINS_REV: &str = \"([0-9a-f]{40})\"").matcher(Text.read(f));
        return mm.find() ? mm.group(1) : null;
    }

    private String portingDoc() {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(a.name()).append(" on Horizon\n\n");
        b.append("A Horizon native-mod bundle generated by horizon-port from `").append(a.jar()).append("`. ");
        b.append("It builds as it is and plays like vanilla: every injection that is on leaves the game unchanged until its `TODO` is filled in. ");
        b.append(active).append(" injections are on; ").append(commented).append(" are written commented out in `mixins/").append(id).append("/horizon-mixin.toml`, with why.\n\n");
        b.append("## What each part became\n\n| Java | State | What to do |\n|---|---|---|\n");
        for (String[] r : porting) b.append("| ").append(cell(r[0])).append(" | ").append(r[1]).append(" | ").append(cell(r[2])).append(" |\n");
        b.append("\n## Build it\n\n");
        b.append("Horizon's launcher builds a bundle folder itself (it downloads its own Rust the first time):\n\n");
        b.append("```bash\nhorizon-launcher native-build \"<this folder>\" --trust-native-code\n```\n\n");
        b.append("Native mods run with your rights and aren't sandboxed: build only code you have read. ");
        b.append("`cargo test` here runs `runtime.rs`'s own tests without the game.\n\n");
        b.append("## Limits\n\n");
        if (translation != null) {
            b.append("- The mod's own logic that doesn't need the game is translated into `runtime.rs` (`pub mod java`): ")
                    .append(TranslationReport.summary(translation)).append(". Code that uses Minecraft classes isn't: each helper lists the game calls its Java makes and what they are in Horizon. See `report/translation.md`.\n");
            if (config() != null) b.append("- The settings page's ON/OFF buttons are real: they change and keep `").append(config().type())
                    .append("`'s options. The mod's other code reads them only once that code is ported.\n");
        } else {
            b.append("- The Java bodies aren't translated (give `--java-src`, or use `port`, to translate the parts that don't need the game). Each helper lists the game calls its Java class makes and what they are in Horizon.\n");
        }
        b.append("- `BEFORE_CALL`, `AFTER_CALL`, `MODIFY_ARG` and `WRAP_OPERATION` select the called function by name (`selector`), with `require` counted in the target's source. horizon-mixins checks both when it plans: a mismatch fails the build with a message, it never applies silently.\n");
        b.append("- Raw targets are functions of one Horizon commit");
        b.append(revision != null ? " (`" + revision + "`)" : "") .append(": a newer Nightly may move them.\n");
        return b.toString();
    }

    // ---- small helpers ----

    private void appendBindings(List<Binding> bs) {
        appendBindings(toml, bs);
    }

    private static void appendBindings(StringBuilder t, List<Binding> bs) {
        if (bs.isEmpty()) return;
        t.append("bindings = [\n");
        for (Binding x : bs) t.append("  { name = \"").append(x.name()).append("\", ty = \"").append(escape(x.ty())).append("\", value = \"").append(escape(x.value())).append("\" },\n");
        t.append("]\n");
    }

    private static String params(List<Binding> bs) {
        return String.join(", ", bs.stream().map(x -> x.name() + ": " + x.ty()).toList());
    }

    private String uniqueId(String base) {
        String s = base;
        for (int n = 2; !ids.add(s); n++) s = base + "-" + n;
        return s;
    }

    private static String hookName(String horizon) {
        if (horizon == null || !horizon.startsWith("hook `")) return null;
        return horizon.substring(6, horizon.indexOf('`', 6));
    }

    /** "notxtpacks$sizeOn" to "sizeOn". */
    private static String handlerBase(String handler) {
        return handler.substring(handler.lastIndexOf('$') + 1);
    }

    private static String kebab(String s) {
        return SourceMatch.snake(s.replace("-", "_")).replace('_', '-').replaceAll("[^a-z0-9-]", "").replaceAll("-+", "-");
    }

    private static String snake(String s) {
        return SourceMatch.snake(s).replaceAll("[^a-z0-9_]", "_");
    }

    private static List<Integer> range(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(i);
        return l;
    }

    private static String orUnit(String t) {
        return t == null ? "()" : t;
    }

    private String cargoVersion() {
        String v = a.version();
        return v.matches("\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.+-]*)?") ? v : "0.1.0";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
