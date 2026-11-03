package horizonport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The table that says what each Java thing is in Horizon. Several files can be loaded; a later file
 * overrides an earlier one entry by entry, so suggestions from `match` sit on top of the base table.
 */
final class Mappings {
    final Map<String, Map<String, Object>> fabricEvents = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> methods = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> superclasses = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> mixinKinds = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> hooks = new LinkedHashMap<>();
    /** A vanilla class to its superclass. */
    final Map<String, String> parents = new LinkedHashMap<>();

    static Mappings load(List<Path> files) throws IOException {
        Mappings m = new Mappings();
        for (Path f : files) {
            Map<String, Object> root = Json.map(Json.parse(Text.read(f)));
            merge(m.fabricEvents, root.get("fabricEvents"));
            merge(m.methods, root.get("methods"));
            merge(m.superclasses, root.get("superclasses"));
            merge(m.mixinKinds, root.get("mixinKinds"));
            merge(m.hooks, root.get("hooks"));
            for (Map.Entry<String, Object> e : Json.map(root.get("parents")).entrySet()) {
                if (!e.getKey().startsWith("_") && e.getValue() instanceof String s) m.parents.put(e.getKey(), s);
            }
        }
        return m;
    }

    /** The class and the vanilla superclasses the table knows, nearest first. */
    List<String> ancestry(String owner) {
        List<String> out = new ArrayList<>();
        for (String c = owner; c != null && !out.contains(c); c = parents.get(c)) out.add(c);
        return out;
    }

    private static void merge(Map<String, Map<String, Object>> into, Object section) {
        for (Map.Entry<String, Object> e : Json.map(section).entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            into.put(e.getKey(), Json.map(e.getValue()));
        }
    }

    Map<String, Object> method(String owner, String name) {
        return methods.get(owner + "#" + name);
    }

    /** "Inject@INVOKE:AFTER" first, then "Inject@INVOKE", then "Inject". */
    Map<String, Object> mixinKind(String injector, String at, String shift) {
        Map<String, Object> k = at == null || shift == null ? null : mixinKinds.get(injector + "@" + at + ":" + shift);
        if (k == null && at != null) k = mixinKinds.get(injector + "@" + at);
        return k != null ? k : mixinKinds.get(injector);
    }

    /** The Horizon target an entry names: "hook `tick`", a Rust path, or the helper call a mod writes. */
    static String horizonTarget(Map<String, Object> entry) {
        if (entry == null) return null;
        String hook = Json.str(entry.get("hook"));
        if (hook != null) return "hook `" + hook + "`";
        String rust = Json.str(entry.get("rust"));
        if (rust != null) return rust;
        String api = Json.str(entry.get("api"));
        return api == null ? null : "`" + api + "`";
    }
}
