package horizonport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Horizon's hook points and helpers, read from its source: every `native_*` function of native_hooks.rs (and the hook
 * points native-hooks.json names elsewhere), with its signature, its doc, the hooks API it came in, and for a hook
 * point the `[[injection]]` a horizon-mixin.toml needs to hook it. The `hooks` command and the kit's HOOKS.md.
 */
final class HookList {
    static final String HOOKS_FILE = "crates/horizon-client/src/game/native_hooks.rs";

    record Binding(String name, String ty, String value) {}

    /** kind: "hook" (empty: a mod hooks it) or "helper" (a mod calls it). api 1 when the source names no later one. */
    record Hook(String name, String kind, String owner, String target, int api, String side, String signature,
                String doc, String file, List<Binding> bindings) {}

    private HookList() {}

    // ------------------------------------------------------------------ when each came

    private static final Pattern API_LINE = Pattern.compile("^//! API (\\d+) ");
    private static final Pattern NATIVE_NAME = Pattern.compile("`(native_\\w+)");
    private static final Pattern FN_DOC = Pattern.compile("((?:[ \\t]*///[^\\n]*\\n)+)[ \\t]*pub(?:\\((?:super|crate)\\))? fn (native_\\w+)");
    private static final Pattern FN_API = Pattern.compile("API (\\d+)");

    /**
     * native_* name -> the hooks API it came in, as native_hooks.rs says: its header ("API 27 adds the player-health
     * HUD rows (`native_health_hud`)") or the function's own doc ("... API 19.").
     */
    static Map<String, Integer> since(String src) {
        src = src.replace("\r\n", "\n");
        Map<String, Integer> since = new HashMap<>();
        int current = 0;
        for (String line : src.split("\n")) {
            if (!line.startsWith("//!")) break;
            Matcher a = API_LINE.matcher(line);
            if (a.find()) current = Integer.parseInt(a.group(1));
            else if (line.strip().equals("//!")) current = 0;
            if (current == 0) continue;
            Matcher n = NATIVE_NAME.matcher(line);
            while (n.find()) since.merge(n.group(1), current, Math::min);
        }
        Matcher f = FN_DOC.matcher(src);
        while (f.find()) {
            Matcher a = FN_API.matcher(f.group(1));
            if (a.find()) since.merge(f.group(2), Integer.parseInt(a.group(1)), Math::min);
        }
        return since;
    }

    // ------------------------------------------------------------------ reading the source

    private static final Pattern FN = Pattern.compile("(?m)^[ \\t]*pub(?:\\((?:super|crate)\\))? fn (native_\\w+)");
    private static final Pattern IMPL = Pattern.compile("(?m)^impl(?:<[^>\\n]*>)? (\\w+)(<[^>\\n]*>)? \\{\\n");
    private static final Pattern USE = Pattern.compile("(?ms)^use (.*?);");
    private static final Pattern TYPE_DEF = Pattern.compile("(?m)^pub(?:\\(crate\\))? (?:struct|enum|type) (\\w+)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern LIFETIME = Pattern.compile("'(?!_|static\\b)[a-z]\\w*");

    static List<Hook> read(Path horizonSrc) throws IOException {
        Path hooksFile = horizonSrc.resolve(HOOKS_FILE);
        if (!Files.isRegularFile(hooksFile)) throw new IllegalArgumentException("no " + hooksFile + ": --horizon-src is Horizon's source folder");
        String main = Text.read(hooksFile).replace("\r\n", "\n");
        Map<String, Integer> since = since(main);
        // The stamped list of hook points, when this Horizon has it: name -> target and side.
        Map<String, Map<String, Object>> listed = new LinkedHashMap<>();
        Path stamp = horizonSrc.resolve("native-hooks.json");
        if (Files.isRegularFile(stamp)) {
            for (Object o : Json.list(Json.map(Json.parse(Text.read(stamp))).get("hooks"))) {
                Map<String, Object> h = Json.map(o);
                String t = Json.str(h.get("target"));
                if (t != null) listed.put(t.substring(t.lastIndexOf("::") + 2), h);
            }
        }
        List<Hook> out = new ArrayList<>();
        out.addAll(readFile(HOOKS_FILE, main, since, listed));
        // Hook points listed in other files (horizon_world::loot::native_loot_table_load...).
        Map<String, List<String>> elsewhere = new LinkedHashMap<>();
        for (Map<String, Object> h : listed.values()) {
            String t = Json.str(h.get("target"));
            if (t.startsWith("horizon_client::game::native_hooks::")) continue;
            String file = fileOf(horizonSrc, t);
            if (file != null) elsewhere.computeIfAbsent(file, k -> new ArrayList<>()).add(t.substring(t.lastIndexOf("::") + 2));
        }
        for (Map.Entry<String, List<String>> e : elsewhere.entrySet()) {
            String src = Text.read(horizonSrc.resolve(e.getKey())).replace("\r\n", "\n");
            for (Hook h : readFile(e.getKey(), src, since, listed)) if (e.getValue().contains(h.name())) out.add(h);
        }
        // Listed hook points whose source wasn't found: name and target only.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Hook h : out) seen.add(h.name());
        for (Map.Entry<String, Map<String, Object>> e : listed.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            out.add(new Hook(e.getKey(), "hook", null, Json.str(e.getValue().get("target")), since.getOrDefault(e.getKey(), 1),
                    Json.str(e.getValue().get("side")), null, "", null, List.of()));
        }
        return out;
    }

    /** crates/<crate>/src/<module path>.rs (or mod.rs) for a target like horizon_world::loot::native_x. */
    private static String fileOf(Path horizonSrc, String target) {
        String[] p = target.replaceAll("<[^>]*>", "").split("::");
        if (p.length < 3) return null;
        String crate = "crates/" + p[0].replace('_', '-') + "/src/";
        // Drop the function, and a type when the last segment before it is one (Game<'_>).
        int end = p.length - 1;
        if (Character.isUpperCase(p[end - 1].charAt(0))) end--;
        StringBuilder mod = new StringBuilder();
        for (int i = 1; i < end; i++) mod.append(i > 1 ? "/" : "").append(p[i]);
        for (String f : new String[] {crate + mod + ".rs", crate + mod + "/mod.rs"}) {
            if (Files.isRegularFile(horizonSrc.resolve(f))) return f;
        }
        return null;
    }

    private static List<Hook> readFile(String file, String src, Map<String, Integer> since, Map<String, Map<String, Object>> listed) {
        String modPath = modulePath(file);
        Map<String, String> names = names(src, modPath);
        // impl blocks: [start, end, type, generics]
        List<Object[]> impls = new ArrayList<>();
        Matcher im = IMPL.matcher(src);
        while (im.find()) {
            int end = src.indexOf("\n}\n", im.end());
            impls.add(new Object[] {im.start(), end < 0 ? src.length() : end, im.group(1), im.group(2) == null ? "" : im.group(2)});
        }
        // Each native_* name once: a second definition (another impl, a cfg) keeps the first.
        Map<String, Hook> byName = new LinkedHashMap<>();
        Matcher m = FN.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            if (byName.containsKey(name)) continue;
            int open = signatureEnd(src, m.end());
            if (open < 0) continue;
            String sig = src.substring(src.indexOf("fn ", m.start()), open).strip().replaceAll("\\s+", " ").replace("( ", "(").replace(", )", ")").replace(",)", ")");
            int close = matching(src, open);
            String body = close < 0 ? "x" : src.substring(open + 1, close);
            String owner = null, ownerGen = "";
            for (Object[] i : impls) {
                if ((int) i[0] < m.start() && m.start() < (int) i[1]) { owner = (String) i[2]; ownerGen = (String) i[3]; }
            }
            Map<String, Object> l = listed.get(name);
            boolean hook = l != null || emptyBody(body);
            String target = l != null ? Json.str(l.get("target"))
                    : hook ? qualifiedModule(modPath, file) + "::" + (owner == null ? "" : owner + (ownerGen.isEmpty() ? "" : "<'_>") + "::") + name : null;
            List<Binding> bindings = hook ? bindings(sig, owner, ownerGen, names) : List.of();
            byName.put(name, new Hook(name, hook ? "hook" : "helper", owner, target, since.getOrDefault(name, 1),
                    l == null ? null : Json.str(l.get("side")), sig, doc(src, m.start()), file, bindings));
        }
        return new ArrayList<>(byName.values());
    }

    /** crates/horizon-client/src/game/native_hooks.rs -> crate::game::native_hooks */
    private static String modulePath(String file) {
        String rest = file.substring(file.indexOf("/src/") + 5).replaceAll("(/mod)?\\.rs$", "");
        if (rest.equals("lib") || rest.equals("main")) return "crate";
        return "crate::" + rest.replace("/", "::");
    }

    private static String crateIdent(String file) {
        String c = file.substring("crates/".length());
        return c.substring(0, c.indexOf('/')).replace('-', '_');
    }

    /** The module as a target names it: horizon_client::game::native_hooks. */
    private static String qualifiedModule(String modPath, String file) {
        return crateIdent(file) + modPath.substring("crate".length());
    }

    /** Simple type name -> its path, from the file's `use` lines and its own types. */
    private static Map<String, String> names(String src, String modPath) {
        Map<String, String> out = new HashMap<>();
        Matcher t = TYPE_DEF.matcher(src);
        while (t.find()) out.put(t.group(1), modPath + "::" + t.group(1));
        Matcher u = USE.matcher(src);
        // `crate::` stays: a helper is pasted into the crate whose function it hooks.
        while (u.find()) flatten("", u.group(1).replaceAll("\\s+", " ").strip(), modPath, out);
        return out;
    }

    private static void flatten(String prefix, String s, String modPath, Map<String, String> out) {
        s = s.strip();
        if (s.isEmpty()) return;
        int brace = s.indexOf('{');
        if (brace >= 0) {
            String head = s.substring(0, brace);
            String inner = s.substring(brace + 1, s.lastIndexOf('}'));
            for (String part : split(inner, ',')) flatten(prefix + head, part, modPath, out);
            return;
        }
        String path = prefix + s, name;
        int as = path.indexOf(" as ");
        if (as >= 0) {
            name = path.substring(as + 4).strip();
            path = path.substring(0, as).strip();
        } else {
            if (path.endsWith("::self")) path = path.substring(0, path.length() - 6);
            name = path.substring(path.lastIndexOf("::") + 2);
        }
        if (name.equals("_") || name.equals("*")) return;
        String parent = modPath.contains("::") ? modPath.substring(0, modPath.lastIndexOf("::")) : modPath;
        if (path.startsWith("super::")) path = parent + path.substring(5);
        else if (path.startsWith("self::")) path = modPath + path.substring(4);
        out.put(name, path);
    }

    /** Parts of s split at `sep` outside (), [], <> and {}. */
    private static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || (c == '>' && (i == 0 || s.charAt(i - 1) != '-'))) depth--;
            else if (c == sep && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }

    /** The `{` that opens the function's body, after its parameters. */
    private static int signatureEnd(String src, int from) {
        int depth = 0;
        boolean params = false;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') { depth++; params = true; }
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) return -1; // a declaration without a body
            else if (c == '{' && depth == 0 && params) return i;
        }
        return -1;
    }

    /** The `}` that closes the `{` at `open`, skipping strings, chars and comments. */
    private static int matching(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                i = src.indexOf('\n', i);
                if (i < 0) return -1;
            } else if (c == '"') {
                for (i++; i < src.length() && src.charAt(i) != '"'; i++) if (src.charAt(i) == '\\') i++;
            } else if (c == '\'') {
                if (i + 2 < src.length() && src.charAt(i + 1) == '\\') i = src.indexOf('\'', i + 2);
                else if (i + 2 < src.length() && src.charAt(i + 2) == '\'') i += 2;
                // else a lifetime
            } else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** Nothing in it but `let _ = ...;` and comments: a hook point. */
    private static boolean emptyBody(String body) {
        String b = body.replaceAll("//[^\\n]*", "").replaceAll("(?s)let _ = [^;]*;", "").strip();
        return b.isEmpty();
    }

    private static String doc(String src, int fnStart) {
        String[] before = src.substring(0, fnStart).split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int i = before.length - 2; i >= 0; i--) {
            String l = before[i].strip();
            if (l.startsWith("///")) lines.add(0, l.substring(3).strip());
            else if (l.startsWith("#[")) continue;
            else break;
        }
        return String.join(" ", lines).strip();
    }

    /** The bindings a HEAD injection passes on: `self` as `game` (or the impl's type), then every parameter. */
    private static List<Binding> bindings(String sig, String owner, String ownerGen, Map<String, String> names) {
        List<Binding> out = new ArrayList<>();
        int open = sig.indexOf('(');
        int depth = 0, close = -1;
        for (int i = open; i < sig.length(); i++) {
            char c = sig.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) { close = i; break; }
        }
        if (open < 0 || close < 0) return out;
        for (String p : split(sig.substring(open + 1, close), ',')) {
            p = p.strip();
            if (p.isEmpty()) continue;
            if (p.endsWith("self")) {
                if (owner == null) continue;
                boolean mut = p.contains("mut");
                String ty = (mut ? "&mut " : "&") + names.getOrDefault(owner, owner) + (ownerGen.isEmpty() ? "" : "<'_>");
                String name = owner.equals("Game") ? "game" : owner.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
                out.add(new Binding(name, ty, mut ? "&mut *self" : "&*self"));
                continue;
            }
            int colon = p.indexOf(':');
            if (colon < 0) continue;
            String name = p.substring(0, colon).strip().replaceFirst("^mut ", "");
            String ty = qualify(p.substring(colon + 1).strip(), names);
            out.add(new Binding(name, ty, ty.startsWith("&mut ") ? "&mut *" + name : name));
        }
        return out;
    }

    /** Each type name the file imports, written with its whole path; named lifetimes become '_. */
    private static String qualify(String ty, Map<String, String> names) {
        ty = LIFETIME.matcher(ty).replaceAll("'_");
        StringBuilder b = new StringBuilder();
        Matcher m = IDENT.matcher(ty);
        int last = 0;
        while (m.find()) {
            int s = m.start();
            boolean pathTail = s >= 2 && ty.startsWith("::", s - 2);
            boolean lifetime = s >= 1 && ty.charAt(s - 1) == '\'';
            b.append(ty, last, s);
            String full = names.get(m.group());
            b.append(!pathTail && !lifetime && full != null ? full : m.group());
            last = m.end();
        }
        return b.append(ty.substring(last)).toString();
    }

    /** The helper function a hook's injection calls, as the mod's file writes it. */
    static String helperFn(Hook h, String modId) {
        StringBuilder b = new StringBuilder("fn ").append(modId).append('_').append(h.name().replaceFirst("^native_", "")).append('(');
        for (int i = 0; i < h.bindings().size(); i++) {
            Binding x = h.bindings().get(i);
            b.append(i > 0 ? ", " : "").append(x.name()).append(": ").append(x.ty());
        }
        return b.append(") {\n    // ...\n}").toString();
    }

    static String injection(Hook h, String helperFile) {
        StringBuilder b = new StringBuilder("[[injection]]\nid = \"").append(h.name().replaceFirst("^native_", "")).append("\"\nkind = \"HEAD\"\n")
                .append("target = \"").append(h.target()).append("\"\nhelper = \"").append(helperFile).append("\"\nbindings = [\n");
        for (Binding x : h.bindings()) {
            b.append("  { name = \"").append(x.name()).append("\", ty = \"").append(x.ty()).append("\", value = \"").append(x.value()).append("\" },\n");
        }
        return b.append("]").toString();
    }

    // ------------------------------------------------------------------ writing

    static String revision(Path horizonSrc) throws IOException {
        Path r = horizonSrc.resolve(".horizon-revision");
        return Files.isRegularFile(r) ? Text.read(r).strip() : null;
    }

    static int api(List<Hook> hooks) {
        int api = 1;
        for (Hook h : hooks) api = Math.max(api, h.api());
        return api;
    }

    static String json(List<Hook> hooks, String revision) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", 1);
        out.put("horizon_revision", revision);
        out.put("hooks_api", api(hooks));
        List<Object> list = new ArrayList<>();
        for (Hook h : hooks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.name());
            m.put("kind", h.kind());
            if (h.api() > 0) m.put("api", h.api());
            if (h.side() != null) m.put("side", h.side());
            if (h.target() != null) m.put("target", h.target());
            if (h.owner() != null) m.put("owner", h.owner());
            if (h.signature() != null) m.put("signature", h.signature());
            m.put("doc", h.doc());
            if (h.file() != null) m.put("file", h.file());
            if (!h.bindings().isEmpty()) {
                List<Object> bs = new ArrayList<>();
                for (Binding x : h.bindings()) {
                    Map<String, Object> bm = new LinkedHashMap<>();
                    bm.put("name", x.name());
                    bm.put("ty", x.ty());
                    bm.put("value", x.value());
                    bs.add(bm);
                }
                m.put("bindings", bs);
            }
            list.add(m);
        }
        out.put("hooks", list);
        return Json.write(out);
    }

    static String markdown(List<Hook> hooks, String revision) {
        StringBuilder b = new StringBuilder();
        long nHooks = hooks.stream().filter(h -> h.kind().equals("hook")).count();
        b.append("# Horizon's hook points and helpers\n\n")
                .append("Made by `horizon-port hooks` from Horizon's source")
                .append(revision == null ? "" : " at commit `" + revision + "`")
                .append(" (hooks API ").append(api(hooks)).append("): ").append(nHooks).append(" hook points and ")
                .append(hooks.size() - nHooks).append(" helpers. Newer Horizon commits may have more: make the list again with\n")
                .append("`java -jar horizon-port.jar hooks --horizon-src <Horizon's source>` (add `--json` for a machine-readable list).\n\n")
                .append("""
                        ## How to use them

                        - **Hook points** are empty functions Horizon calls at fixed places. A mod hooks one with a HEAD
                          injection in its `horizon-mixin.toml` (each entry below has the `[[injection]]` to paste) and a
                          helper file with one function taking the bindings, in that order. Hooks that answer something
                          take an out parameter (`claimed`, `draws`, `size`...): set it, and check it first, since
                          another mod may have answered already.
                        - **Helpers** are functions on `Game` (or the type named) a mod calls from its hook files, for
                          example `game.native_sprite(...)` in a hook that has the `game` binding.
                        - **API**: the hooks API the function came in. A mod that only hooks hook points needs the newest
                          API among those it uses (`pack` works this out and writes it into the .hnmod), and the launcher
                          builds it on any Horizon commit that has that API.
                        - The kit's runtime module (`crate::game::kit_<id>`) sits inside Horizon's `game` module, so its
                          code can also read `Game`'s own fields and methods (`game.player`, `game.items()`...). That
                          isn't part of the hook points: it can break on another Horizon commit, the kit's `check` can't
                          see it (use `pack --no-check`), and only `compile` (the real build) checks it.

                        """);
        b.append("## Hook points\n\n");
        for (Hook h : hooks) if (h.kind().equals("hook")) entry(b, h);
        b.append("## Helpers\n\n");
        for (Hook h : hooks) if (h.kind().equals("helper")) entry(b, h);
        return b.toString();
    }

    private static void entry(StringBuilder b, Hook h) {
        b.append("### `").append(h.name()).append("`\n\n");
        List<String> facts = new ArrayList<>();
        facts.add("API " + h.api());
        if (h.side() != null) facts.add(h.side() + " side");
        if (h.owner() != null && !h.owner().equals("Game")) facts.add("on `" + h.owner() + "`");
        if (h.file() != null) facts.add("`" + h.file() + "`");
        b.append(String.join(" · ", facts)).append("\n\n");
        if (!h.doc().isEmpty()) b.append(h.doc()).append("\n\n");
        if (h.signature() != null) b.append("```rust\n").append(h.signature()).append("\n```\n\n");
        if (h.kind().equals("hook") && h.target() != null && h.signature() != null) {
            String file = h.name().replaceFirst("^native_", "") + ".rs";
            b.append("<details><summary>Hook it</summary>\n\n```toml\n").append(injection(h, file)).append("\n```\n\n`").append(file)
                    .append("`:\n\n```rust\n").append(helperFn(h, "mymod")).append("\n```\n\n</details>\n\n");
        } else if (h.target() != null && h.signature() == null) {
            b.append("Target: `").append(h.target()).append("` (its source wasn't found).\n\n");
        }
    }
}
