package horizonport;

import horizonport.Analyzer.Call;
import horizonport.Analyzer.ModAnalysis;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds, for each Java method a mod uses, the Horizon function that is most likely the same one.
 * Horizon is a rewrite that keeps vanilla's concepts, so `LocalPlayer.isShiftKeyDown` tends to be a
 * `fn is_shift_key_down` in an `impl LocalPlayer` or a `local_player` module. Matches are scored and
 * written as suggested mappings, for a person to check.
 */
final class SourceMatch {
    /**
     * docRefs: the Java "Class.method" names the function's doc comment gives, in order. Horizon names
     * the method a function reimplements first; later names are what it uses.
     */
    record RustFn(String path, String type, String name, String file, int line, List<String> docRefs) {
        String primary() {
            return docRefs.isEmpty() ? null : docRefs.get(0);
        }
    }

    private static final Pattern IMPL_START = Pattern.compile("^\\s*(?:unsafe\\s+)?impl\\b");
    private static final Pattern NAMED_LIFETIME = Pattern.compile("'(?!static\\b)[a-z_]\\w*");

    /** An impl block: the type's bare name, and its path segment as horizon-mixins writes targets. */
    record ImplHead(String typeName, String segment) {}

    /**
     * `impl<'a> Game<'a> {` to (Game, Game<'_>); `impl LevelAccess for GameLevel<'_> {` to
     * (GameLevel, <GameLevel<'_> as LevelAccess>). Named lifetimes become '_ as in Horizon's targets.
     */
    static ImplHead parseImpl(String code) {
        Matcher m = IMPL_START.matcher(code);
        if (!m.find()) return null;
        int i = skipSpace(code, m.end());
        if (i < code.length() && code.charAt(i) == '<') i = skipSpace(code, skipGenerics(code, i));
        int end = typeEnd(code, i);
        String first = code.substring(i, end);
        i = skipSpace(code, end);
        String trait = null, type = first;
        if (code.startsWith("for ", i)) {
            trait = first;
            int start = skipSpace(code, i + 4);
            type = code.substring(start, typeEnd(code, start));
        }
        if (type.startsWith("&")) type = type.substring(1).trim();
        if (type.isEmpty() || !Character.isLetter(type.charAt(0)) && type.charAt(0) != '_') return null;
        type = NAMED_LIFETIME.matcher(type).replaceAll("'_");
        String bare = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        bare = bare.substring(bare.lastIndexOf(':') + 1);
        String segment = trait == null ? type : "<" + type + " as " + NAMED_LIFETIME.matcher(trait).replaceAll("'_") + ">";
        return new ImplHead(bare, segment);
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** From a '<', past its matching '>'. */
    private static int skipGenerics(String s, int i) {
        int depth = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && --depth == 0) return i + 1;
        }
        return i;
    }

    /** The end of a type like `foo::Bar<'_, T>`: whitespace or `{` outside angle brackets. */
    private static int typeEnd(String s, int i) {
        int depth = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (depth == 0 && (Character.isWhitespace(c) || c == '{')) break;
        }
        return i;
    }

    private static final Pattern FN = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:const\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:extern\\s+\"[^\"]*\"\\s+)?fn\\s+(\\w+)");
    /** `LocalPlayer.updateAutoJump(xa, za)` or LocalPlayer.tick in a comment. */
    private static final Pattern JAVA_REF = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\.([a-z][A-Za-z0-9]*)\\b");

    final List<RustFn> fns = new ArrayList<>();
    /** The source folder indexed: RustFn.file is relative to it. */
    Path root;
    private final Map<String, RustFn> byPath = new HashMap<>();
    private final Map<String, List<RustFn>> byName = new HashMap<>();
    private final Map<String, List<RustFn>> byDoc = new HashMap<>();
    private final Map<String, List<RustFn>> byPrimary = new HashMap<>();

    static SourceMatch index(Path root) throws IOException {
        SourceMatch idx = new SourceMatch();
        idx.root = root;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".rs"))::iterator) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (skip(rel)) continue;
                idx.scan(rel, Text.lines(f));
            }
        }
        for (RustFn fn : idx.fns) {
            idx.byName.computeIfAbsent(fn.name(), k -> new ArrayList<>()).add(fn);
            idx.byPath.putIfAbsent(fn.path(), fn);
            for (String ref : fn.docRefs()) idx.byDoc.computeIfAbsent(ref, k -> new ArrayList<>()).add(fn);
            if (fn.primary() != null) idx.byPrimary.computeIfAbsent(fn.primary(), k -> new ArrayList<>()).add(fn);
        }
        return idx;
    }

    /** The function at an exact path, as the mapping table and suggestions name it. */
    RustFn find(String path) {
        return byPath.get(path);
    }

    /** Build output, tests, examples and vendored code aren't the game. */
    private static boolean skip(String rel) {
        String r = "/" + rel;
        return r.contains("/target/") || r.contains("/tests/") || r.contains("/examples/") || r.contains("/benches/")
                || r.contains("/third_party/") || r.endsWith("_test.rs") || r.endsWith("_tests.rs") || r.endsWith("/tests.rs");
    }

    private void scan(String rel, List<String> lines) {
        String module = modulePath(rel);
        int depth = 0;
        ImplHead pendingImpl = null;
        Deque<Map.Entry<ImplHead, Integer>> impls = new ArrayDeque<>();
        Set<String> docs = new LinkedHashSet<>();
        // Unit tests live beside the code (`#[cfg(test)] mod tests`, `#[test] fn`): they aren't the game.
        boolean cfgTest = false, testAttr = false;
        int testDepth = -1;
        for (int ln = 0; ln < lines.size(); ln++) {
            String raw = lines.get(ln);
            String trimmed = raw.trim();
            if (trimmed.startsWith("///")) {
                Matcher jr = JAVA_REF.matcher(trimmed);
                while (jr.find()) docs.add(jr.group(1) + "." + jr.group(2));
                continue;
            }
            if (trimmed.startsWith("#[cfg(test)]")) cfgTest = true;
            if (trimmed.startsWith("#[test]") || trimmed.contains("::test]")) testAttr = true;
            String code = stripComment(raw);
            boolean opensTestMod = cfgTest && testDepth < 0 && code.matches("^\\s*(pub\\s+)?mod\\s+\\w+\\s*\\{.*");
            ImplHead head = parseImpl(code);
            if (head != null) pendingImpl = head;
            Matcher fm = FN.matcher(code);
            if (fm.find()) {
                if (testDepth < 0 && !testAttr && !cfgTest) {
                    ImplHead impl = impls.isEmpty() ? null : impls.peek().getKey();
                    String type = impl == null ? null : impl.typeName();
                    String path = module + (impl != null ? "::" + impl.segment() : "") + "::" + fm.group(1);
                    fns.add(new RustFn(path, type, fm.group(1), rel, ln + 1, List.copyOf(docs)));
                }
                docs.clear();
                testAttr = false;
                cfgTest = false;
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#[") && !trimmed.startsWith("//")) {
                docs.clear();
                if (!opensTestMod) cfgTest = false;
            }
            for (int k = 0; k < code.length(); k++) {
                char ch = code.charAt(k);
                if (ch == '{') {
                    depth++;
                    if (opensTestMod && testDepth < 0) testDepth = depth;
                    if (pendingImpl != null) {
                        impls.push(new AbstractMap.SimpleEntry<>(pendingImpl, depth));
                        pendingImpl = null;
                    }
                } else if (ch == '}') {
                    if (!impls.isEmpty() && impls.peek().getValue() == depth) impls.pop();
                    if (depth == testDepth) testDepth = -1;
                    depth--;
                }
            }
            if (opensTestMod) cfgTest = false;
        }
    }

    /** crates/horizon-client/src/game/native_hooks.rs to horizon_client::game::native_hooks. */
    static String modulePath(String rel) {
        String[] parts = rel.split("/");
        int src = -1;
        for (int i = parts.length - 2; i >= 0; i--) if (parts[i].equals("src")) { src = i; break; }
        List<String> out = new ArrayList<>();
        out.add(src > 0 ? parts[src - 1].replace('-', '_') : "crate");
        for (int i = src + 1; i < parts.length; i++) {
            String p = parts[i];
            if (i == parts.length - 1) {
                p = p.substring(0, p.length() - 3);
                if (p.equals("lib") || p.equals("main") || p.equals("mod")) continue;
            }
            out.add(p);
        }
        return String.join("::", out);
    }

    private static String stripComment(String line) {
        int c = line.indexOf("//");
        String s = c >= 0 ? line.substring(0, c) : line;
        // Braces inside string and char literals would throw the depth off.
        return s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'[{}]'", "''");
    }

    record Candidate(RustFn fn, int score, String why) {}

    /** ancestry: the class the mod names, then its vanilla superclasses, nearest first. */
    List<Candidate> candidates(List<String> ancestry, String javaName) {
        List<String> classes = ancestry.stream().map(ClassFile::simpleName).toList();
        String cls = classes.get(0);
        String clsSnake = snake(cls);
        // The method may be documented under the class that declares it (LivingEntity.setSprinting).
        List<String> docRefs = classes.stream().map(c -> c + "." + javaName).toList();
        // Rust names to look for, with what each name match is worth. A constructor is a `new`
        // somewhere, which says little on its own.
        Map<String, Integer> names = new LinkedHashMap<>();
        if (javaName.equals("<init>")) names.put("new", 1);
        else {
            names.put(snake(javaName), 4);
            for (String prefix : List.of("get", "set")) {
                if (javaName.startsWith(prefix) && javaName.length() > prefix.length() && Character.isUpperCase(javaName.charAt(prefix.length()))) {
                    String rest = snake(javaName.substring(prefix.length()));
                    names.putIfAbsent(prefix.equals("get") ? rest : "set_" + rest, 2);
                }
            }
        }
        Set<RustFn> pool = new LinkedHashSet<>();
        for (String ref : docRefs) pool.addAll(byDoc.getOrDefault(ref, List.of()));
        for (String n : names.keySet()) pool.addAll(byName.getOrDefault(n, List.of()));

        List<Candidate> out = new ArrayList<>();
        for (RustFn fn : pool) {
            int score = 0;
            List<String> why = new ArrayList<>();
            // Horizon documents a function with the vanilla method it reimplements first; a name
            // further on is usually something the function uses.
            for (int i = 0; i < docRefs.size(); i++) {
                String ref = docRefs.get(i);
                if (ref.equals(fn.primary())) {
                    score += i == 0 ? 10 : 5;
                    why.add("doc is " + ref);
                    break;
                }
                if (fn.docRefs().contains(ref)) {
                    score += i == 0 ? 3 : 2;
                    why.add("doc mentions " + ref);
                    break;
                }
            }
            Integer nameScore = names.get(fn.name());
            if (nameScore != null) { score += nameScore; why.add("name " + fn.name()); }
            int classScore = 0;
            if (fn.type() != null && classes.stream().anyMatch(fn.type()::equalsIgnoreCase)) { classScore += 4; why.add("impl " + fn.type()); }
            List<String> segs = List.of(fn.path().split("::"));
            for (String c : classes) {
                if (segs.contains(snake(c))) { classScore += 2; why.add("module " + snake(c)); break; }
            }
            String lowerPath = fn.path().toLowerCase();
            int tokens = 0;
            for (String t : clsSnake.split("_")) if (t.length() > 2 && lowerPath.contains(t)) tokens++;
            if (tokens > 0) { classScore += Math.min(tokens, 2); why.add(tokens + " class word(s) in path"); }
            score += classScore;
            // A name nothing else in Horizon has counts, as long as something also ties it to the class.
            if (nameScore != null && nameScore > 1 && classScore > 0 && byName.get(fn.name()).size() == 1) {
                score += 2;
                why.add("the only fn of that name");
            }
            out.add(new Candidate(fn, score, String.join(", ", why)));
        }
        return out.stream().sorted(Comparator.comparingInt(Candidate::score).reversed()).limit(3).toList();
    }

    /**
     * high: the doc and the name or impl agree. medium: the doc alone (it sometimes opens with a
     * mention: "item identity follows ItemStack.is"), or name and impl together. low: anything less.
     */
    static String confidence(String why) {
        boolean doc = why.contains("doc is ");
        boolean name = why.contains("name ");
        boolean impl = why.contains("impl ");
        if (doc && (name || impl)) return "high";
        if (doc || (name && impl)) return "medium";
        return "low";
    }

    /** Suggested mappings for every unmapped call, in the mapping table's own format. */
    Map<String, Object> suggest(ModAnalysis a, Mappings mappings, int minScore) {
        Map<String, Object> methods = new LinkedHashMap<>();
        int found = 0;
        for (Call call : a.unmappedCalls()) {
            if (!call.kind().equals("method")) continue;
            String[] p = call.key().split("#", 2);
            List<String> ancestry = mappings.ancestry(p[0]);
            List<Candidate> cs = candidates(ancestry, p[1]);
            if (cs.isEmpty() || cs.get(0).score() < minScore) continue;
            found++;
            Candidate top = cs.get(0);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rust", top.fn().path());
            e.put("suggested", true);
            e.put("score", top.score());
            e.put("why", top.why());
            e.put("at", top.fn().file() + ":" + top.fn().line());
            List<Object> alts = new ArrayList<>();
            for (Candidate c : cs.subList(1, cs.size())) alts.add(c.fn().path() + " (score " + c.score() + ")");
            if (!alts.isEmpty()) e.put("alternatives", alts);
            e.put("confidence", confidence(top.why()));
            // Horizon often splits one vanilla method into several functions, each documented as it.
            List<RustFn> documented = List.of();
            for (String c : ancestry) {
                documented = byPrimary.getOrDefault(ClassFile.simpleName(c) + "." + p[1], List.of());
                if (!documented.isEmpty()) break;
            }
            if (documented.size() > 1) {
                List<Object> pieces = new ArrayList<>();
                for (RustFn fn : documented) pieces.add(fn.path() + " (" + fn.file() + ":" + fn.line() + ")");
                e.put("pieces", pieces);
                // The piece documented as the method that also has its name is the method itself.
                if (top.why().contains("doc is ") && top.fn().name().equals(snake(p[1]))) e.put("picked", true);
            }
            methods.put(call.key(), e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("_about", "Suggested by `horizon-port match` for " + a.modId() + ": " + found + " of "
                + a.unmappedCalls().size() + " unmapped. Check each before relying on it; pass this file with --mappings.");
        root.put("methods", methods);
        return root;
    }

    /** isShiftKeyDown to is_shift_key_down, LocalPlayer to local_player, getUUID to get_uuid. */
    static String snake(String camel) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                boolean prevLower = i > 0 && !Character.isUpperCase(camel.charAt(i - 1));
                boolean nextLower = i + 1 < camel.length() && Character.isLowerCase(camel.charAt(i + 1));
                boolean prevUpper = i > 0 && Character.isUpperCase(camel.charAt(i - 1));
                if (i > 0 && (prevLower || (prevUpper && nextLower)) && b.charAt(b.length() - 1) != '_') b.append('_');
                b.append(Character.toLowerCase(c));
            } else b.append(c);
        }
        return b.toString();
    }
}
