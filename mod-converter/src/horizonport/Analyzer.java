package horizonport;

import horizonport.ClassFile.Annotation;
import horizonport.ClassFile.Member;
import horizonport.ClassFile.Ref;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads a Fabric jar and sorts everything in it by how it carries over to Horizon. */
final class Analyzer {
    /** One thing the mod does. status: auto, needs-mapping, needs-translation, unsupported, content. */
    record Feature(String category, String source, String detail, String horizon, String status, String note) {}

    /** A Minecraft method or field the mod uses, with how many of its classes use it. */
    record Call(String key, String kind, int uses, String horizon) {}

    /** One mixin injector, with everything the generator needs to write its [[injection]]. */
    record Injection(
            String mixinClass, String handler, String injector, String atValue, String shift, String atTarget,
            Integer atOrdinal, Integer argIndex, boolean cancellable,
            String targetClass, String targetMethod, Map<String, Object> target, Map<String, Object> call,
            String kind, String status, List<String> notes) {}

    record ModAnalysis(
            String modId, String name, String version, String description, String jar,
            Map<String, List<String>> entrypoints,
            List<Feature> features,
            List<Call> mappedCalls,
            List<Call> unmappedCalls,
            int assetFiles, int dataFiles, int classes,
            boolean intermediary,
            List<String> warnings,
            List<Injection> injections,
            Map<String, Set<String>> classCalls,
            Map<String, List<String>> classStrings) {}

    private static final Pattern INTERMEDIARY = Pattern.compile("net/minecraft/class_\\d+|(method|field|comp)_\\d+");
    private static final Set<String> INJECTORS = Set.of(
            "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant", "Overwrite",
            "Accessor", "Invoker", "ModifyReturnValue", "ModifyExpressionValue", "WrapOperation", "WrapWithCondition");

    private final Mappings mappings;
    private final List<Feature> features = new ArrayList<>();
    private final Map<String, Integer> callUses = new TreeMap<>();
    private final Map<String, String> callKinds = new LinkedHashMap<>();
    private final Set<String> seenEvents = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<Injection> injections = new ArrayList<>();
    private final Map<String, Set<String>> classCalls = new TreeMap<>();
    private final Map<String, List<String>> classStrings = new TreeMap<>();
    private boolean intermediary;

    private Analyzer(Mappings mappings) {
        this.mappings = mappings;
    }

    static ModAnalysis analyze(Path jar, Mappings mappings) throws IOException {
        return new Analyzer(mappings).run(jar);
    }

    private ModAnalysis run(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Map<String, Object> fmj = readJson(zip, "fabric.mod.json");
            if (fmj == null) {
                warnings.add("No fabric.mod.json: this doesn't look like a Fabric mod. Analysing its classes anyway.");
                fmj = Map.of();
            }
            String id = orElse(Json.str(fmj.get("id")), stem(jar));
            String name = orElse(Json.str(fmj.get("name")), id);
            String version = orElse(Json.str(fmj.get("version")), "?");
            String description = orElse(Json.str(fmj.get("description")), "");

            Map<String, List<String>> entrypoints = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : Json.map(fmj.get("entrypoints")).entrySet()) {
                List<String> classes = new ArrayList<>();
                for (Object v : Json.list(e.getValue())) {
                    String c = v instanceof String s ? s : Json.str(Json.map(v).get("value"));
                    if (c != null) classes.add(c);
                }
                entrypoints.put(e.getKey(), classes);
            }

            Set<String> mixinClasses = new LinkedHashSet<>();
            for (Object v : Json.list(fmj.get("mixins"))) {
                String config = v instanceof String s ? s : Json.str(Json.map(v).get("config"));
                if (config == null) continue;
                Map<String, Object> mc = readJson(zip, config);
                if (mc == null) { warnings.add("Mixin config " + config + " is listed but missing."); continue; }
                String pkg = orElse(Json.str(mc.get("package")), "").replace('.', '/');
                for (String side : List.of("mixins", "client", "server")) {
                    for (Object m : Json.list(mc.get(side))) {
                        if (m instanceof String s) mixinClasses.add(pkg.isEmpty() ? s.replace('.', '/') : pkg + "/" + s.replace('.', '/'));
                    }
                }
            }
            String widener = Json.str(fmj.get("accessWidener"));
            if (widener != null) {
                features.add(new Feature("access", widener, "access widener (opens private game fields)", null,
                        "needs-translation", "Native helpers already see the game's fields, once each field is mapped."));
            }

            int assets = 0, data = 0, classes = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.startsWith("assets/")) assets++;
                else if (n.startsWith("data/")) data++;
                else if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    classes++;
                    ClassFile cf;
                    try (InputStream in = zip.getInputStream(e)) {
                        cf = ClassFile.parse(in.readAllBytes());
                    } catch (IOException | RuntimeException ex) {
                        warnings.add("Couldn't read " + n + ": " + ex.getMessage());
                        continue;
                    }
                    analyzeClass(cf, mixinClasses.contains(cf.name));
                }
            }
            if (assets + data > 0) {
                features.add(new Feature("content", "jar resources", assets + " asset files, " + data + " data files",
                        "content mod (.hmod)", "content", "Recipes, loot, tags, models, textures, worldgen: horizon-port-pack carries these."));
            }
            if (intermediary) {
                warnings.add("The jar uses intermediary names (class_1234, method_5678): it was made for Minecraft before 26.1. "
                        + "Only 26.1+ jars name the game by Mojang's names, which the mapping table uses.");
            }

            List<Call> mapped = new ArrayList<>(), unmapped = new ArrayList<>();
            for (Map.Entry<String, Integer> e : callUses.entrySet()) {
                String[] p = e.getKey().split("#", 2);
                Map<String, Object> m = mappings.method(p[0], p[1]);
                Call c = new Call(e.getKey(), callKinds.get(e.getKey()), e.getValue(), describe(m));
                (m != null ? mapped : unmapped).add(c);
            }
            return new ModAnalysis(id, name, version, description, jar.getFileName().toString(), entrypoints, features,
                    mapped, unmapped, assets, data, classes, intermediary, warnings, injections, classCalls, classStrings);
        }
    }

    private void analyzeClass(ClassFile cf, boolean listedAsMixin) {
        if (!cf.strings.isEmpty()) classStrings.put(cf.name, cf.strings);
        Annotation mixin = cf.annotation("Lorg/spongepowered/asm/mixin/Mixin;");
        if (mixin != null || listedAsMixin) {
            analyzeMixin(cf, mixin);
        } else if (cf.superName != null && isGame(cf.superName)) {
            Map<String, Object> sc = mappings.superclasses.get(cf.superName);
            List<String> overrides = new ArrayList<>();
            for (Member m : cf.methods) if (!m.name().startsWith("<") && !m.name().startsWith("lambda$")) overrides.add(m.name());
            String status = sc != null ? orElse(Json.str(sc.get("status")), "needs-translation") : "needs-translation";
            String note = sc != null ? Json.str(sc.get("note")) : "No mapping for this superclass yet.";
            if (status.equals("content") && !overrides.isEmpty()) {
                status = "needs-translation";
                note = "Overrides " + String.join(", ", overrides) + ": the content ports, that behaviour needs native code.";
            }
            features.add(new Feature("class", cf.name, "extends " + ClassFile.simpleName(cf.superName), null, status, note));
        }

        for (Ref r : cf.refs) {
            if (INTERMEDIARY.matcher(r.owner()).find() || INTERMEDIARY.matcher(r.name()).matches()) intermediary = true;
            Map<String, Object> ev = mappings.fabricEvents.get(r.owner());
            if (ev != null && !r.name().startsWith("<") && seenEvents.add(r.owner() + "." + r.name())) {
                String hook = Json.str(ev.get("hook"));
                features.add(new Feature("event", cf.name, ClassFile.simpleName(r.owner()) + "." + r.name(),
                        hook == null ? null : "hook `" + hook + "`", orElse(Json.str(ev.get("status")), "needs-translation"),
                        Json.str(ev.get("note"))));
            }
            if (isGame(r.owner()) && !r.name().equals("<clinit>")) {
                callUses.merge(r.key(), 1, Integer::sum);
                callKinds.putIfAbsent(r.key(), r.kind());
                classCalls.computeIfAbsent(cf.name, k -> new LinkedHashSet<>()).add(r.key());
                Map<String, Object> m = mappings.method(r.owner(), r.name());
                // One row per class that registers content, not one per call it makes doing so.
                if (m != null && Boolean.TRUE.equals(m.get("registers")) && seenEvents.add("content:" + cf.name)) {
                    features.add(new Feature("content", cf.name, "registers blocks or items in code",
                            "content mod (.hmod)", "content", "Registration and properties: horizon-port-pack carries them as a content mod."));
                }
            }
        }
    }

    private void analyzeMixin(ClassFile cf, Annotation mixin) {
        List<String> targets = new ArrayList<>();
        if (mixin != null) {
            for (Object v : Json.list(mixin.get("value"))) targets.add(ClassFile.descToInternal(String.valueOf(v)));
            for (Object v : Json.list(mixin.get("targets"))) targets.add(String.valueOf(v).replace('.', '/'));
        }
        String targetNames = targets.isEmpty() ? "?" : String.join(", ", targets.stream().map(ClassFile::simpleName).toList());
        int injectors = 0;
        for (Member m : cf.methods) {
            for (Annotation a : m.annotations()) {
                String injector = injectorName(a.type());
                if (injector == null) continue;
                injectors++;
                Annotation at = firstAt(a.get("at"));
                String atValue = at == null ? null : Json.str(at.get("value"));
                String atTarget = at == null ? null : Json.str(at.get("target"));
                String shift = at == null ? null : Json.str(at.get("shift"));
                if ("NONE".equals(shift)) shift = null;
                Integer atOrdinal = at != null && at.get("ordinal") instanceof Integer o ? o : null;
                Integer argIndex = a.get("index") instanceof Integer ix ? ix : null;
                boolean cancellable = Boolean.TRUE.equals(a.get("cancellable"));

                List<String> selectors = new ArrayList<>();
                switch (injector) {
                    case "Overwrite", "Invoker" -> selectors.add(orElse(Json.str(first(a.get("value"))), m.name()));
                    case "Accessor" -> selectors.add(orElse(Json.str(first(a.get("value"))), accessorField(m.name())));
                    default -> { for (Object s : Json.list(a.get("method"))) selectors.add(String.valueOf(s)); }
                }

                Map<String, Object> kindEntry = mappings.mixinKind(injector, atValue, shift);
                String kind = kindEntry == null ? null : Json.str(kindEntry.get("kind"));
                String kindStatus = kindEntry == null ? null : Json.str(kindEntry.get("status"));
                String kindNote = kindEntry == null ? null : Json.str(kindEntry.get("note"));

                for (String target : targets.isEmpty() ? List.of("?") : targets) {
                    for (String sel : selectors.isEmpty() ? List.of("?") : selectors) {
                        String method = selectorName(sel);
                        String detail = "@" + injector + (atValue != null ? "(" + atValue + ")" : "") + " on "
                                + ClassFile.simpleName(target) + "." + method
                                + (atTarget != null ? " at " + atTarget : "");
                        List<String> notes = new ArrayList<>();
                        if (kindNote != null) notes.add(kindNote);

                        Map<String, Object> tm = mappings.method(target, method);
                        String horizonTarget = Mappings.horizonTarget(tm);
                        // Annotation strings aren't in the constant pool's method refs, so count the targets here.
                        if (!method.contains("*") && !target.equals("?")) recordCall(cf.name, target + "#" + method, "method");

                        String status;
                        if (method.contains("*") || method.equals("?")) {
                            status = "needs-translation";
                            notes.add("Wildcard or unknown selector `" + sel + "`.");
                        } else if (kindStatus != null) {
                            status = kindStatus;
                        } else if (kind == null) {
                            status = "needs-translation";
                        } else if (tm == null) {
                            status = "needs-mapping";
                            notes.add("No Horizon function known for " + ClassFile.simpleName(target) + "." + method + ": run `match`.");
                        } else if (tm.get("hook") == null && tm.get("rust") == null) {
                            status = "needs-translation";
                            notes.add("Horizon has no function to inject into here" + (horizonTarget != null ? ", only the helper " + horizonTarget : "") + ".");
                        } else if (tm.get("hook") != null && !kind.equals("HEAD") && !kind.equals("RETURN")) {
                            status = "needs-translation";
                            notes.add("Hook points are empty functions: use HEAD on the hook instead of " + kind + ".");
                        } else if (Json.list(tm.get("pieces")).size() > 1 && !Boolean.TRUE.equals(tm.get("picked"))) {
                            status = "needs-mapping";
                            notes.add("Horizon splits " + ClassFile.simpleName(target) + "." + method + " across "
                                    + Json.list(tm.get("pieces")).size() + " functions: pick the piece this hooks, then remove the others from `pieces`.");
                        } else {
                            status = "auto";
                        }

                        // Kinds that select a call inside the target need that call mapped too.
                        Map<String, Object> callMapping = null;
                        String[] call = callOf(atTarget);
                        if (call != null && Boolean.TRUE.equals(kindEntry == null ? null : kindEntry.get("call"))) {
                            recordCall(cf.name, call[0] + "#" + call[1], "method");
                            callMapping = mappings.method(call[0], call[1]);
                            if (callMapping == null || callMapping.get("rust") == null) {
                                if (status.equals("auto")) status = "needs-mapping";
                                notes.add("The call it hooks (" + ClassFile.simpleName(call[0]) + "." + call[1] + ") isn't mapped to a Rust function yet.");
                            }
                        }
                        if ("approximate".equals(tm == null ? null : tm.get("confidence"))) notes.add("Target mapping is approximate.");
                        String horizon = kind == null ? null : kind + (horizonTarget != null ? " on " + horizonTarget : "");
                        features.add(new Feature("mixin", cf.name + "." + m.name(), detail, horizon, status, notes.isEmpty() ? null : String.join(" ", notes)));
                        injections.add(new Injection(cf.name, m.name(), injector, atValue, shift, atTarget, atOrdinal, argIndex,
                                cancellable, target, method, tm, callMapping, kind, status, List.copyOf(notes)));
                    }
                }
            }
        }
        if (injectors == 0) {
            features.add(new Feature("mixin", cf.name, "mixin into " + targetNames + " with no injectors (interfaces or @Shadow only)",
                    null, "needs-translation", "Usually adds an interface or fields to a game class: needs a manual change."));
        }
    }

    private void recordCall(String inClass, String key, String kind) {
        callUses.merge(key, 1, Integer::sum);
        callKinds.putIfAbsent(key, kind);
        classCalls.computeIfAbsent(inClass, k -> new LinkedHashSet<>()).add(key);
    }

    private static String injectorName(String desc) {
        if (!desc.startsWith("Lorg/spongepowered/asm/mixin/") && !desc.startsWith("Lcom/llamalad7/mixinextras/")) return null;
        String simple = ClassFile.simpleName(ClassFile.descToInternal(desc));
        return INJECTORS.contains(simple) ? simple : null;
    }

    private static Annotation firstAt(Object at) {
        Object f = first(at);
        return f instanceof Annotation a ? a : null;
    }

    private static Object first(Object v) {
        List<?> l = Json.list(v);
        return l.isEmpty() ? null : l.get(0);
    }

    /** "Lnet/minecraft/client/Minecraft;tick()V" or "tick()V" or "tick" to "tick". */
    static String selectorName(String sel) {
        String s = sel;
        int semi = s.indexOf(';');
        if (semi >= 0 && s.startsWith("L")) s = s.substring(semi + 1);
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /** An @At target "Lowner;name(desc)" to {owner, name}. */
    private static String[] callOf(String atTarget) {
        if (atTarget == null || !atTarget.startsWith("L")) return null;
        int semi = atTarget.indexOf(';');
        if (semi < 0) return null;
        return new String[] {atTarget.substring(1, semi), selectorName(atTarget.substring(semi + 1))};
    }

    private static String accessorField(String method) {
        for (String p : List.of("get", "set", "is")) {
            if (method.startsWith(p) && method.length() > p.length()) {
                return Character.toLowerCase(method.charAt(p.length())) + method.substring(p.length() + 1);
            }
        }
        return method;
    }

    static boolean isGame(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/blaze3d/");
    }

    private static String describe(Map<String, Object> m) {
        if (m == null) return null;
        String t = Mappings.horizonTarget(m);
        if (t != null) return t + (Boolean.TRUE.equals(m.get("suggested")) ? " (suggested)" : "");
        return orElse(Json.str(m.get("status")), "mapped");
    }

    private static Map<String, Object> readJson(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
            return Json.map(Json.parse(text));
        }
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String orElse(String s, String fallback) {
        return s != null ? s : fallback;
    }
}
