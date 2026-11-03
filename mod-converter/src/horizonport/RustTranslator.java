package horizonport;

import horizonport.JavaParser.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Translates the parts of a mod's Java that don't need the game into plain Rust: constants,
 * enums, data classes, maths, string handling, loops and the like. Each method is translated
 * whole or not at all, and anything it can't be sure of (Minecraft classes, lambdas, null,
 * try/catch, shared object references...) leaves that method out with the reason, as do the
 * methods that call it. What comes out goes in the bundle's `runtime.rs` as `pub mod java`.
 *
 * <p>Config classes (public option fields and a static {@code INSTANCE}) also get loading and
 * saving through Horizon's per-mod settings, and their on/off options become settings buttons.
 */
final class RustTranslator {

    /** What happened to one piece of Java. */
    record Item(String name, String what, boolean ok, String reason, String file, int line) {}

    /** A config class: its Rust path, its toggles (key, label) and all its option keys. */
    /** numbers: the number options a slider in the mod's own screen sets (field, label, min, max, step). */
    record Config(String module, String type, String instance, List<String[]> toggles, List<String> keys, List<String[]> numbers) {}

    /** A Fabric event handler. ok with `left` non-empty: translated except those statements (each "what: why"). */
    record Handler(String hook, String fn, boolean ok, String reason, String where, List<String> left) {}

    /**
     * rust: the module for runtime.rs; game: the game side (null if none), for game_side.rs; notes: where the Rust is
     * close to the Java but not the same ("Class.method: what").
     */
    record Result(String rust, List<Item> items, Config config, String game, List<Handler> handlers, List<String> notes) {
        long gameSide() {
            return items.stream().filter(i -> i.ok() && "game".equals(i.reason())).count();
        }

        long translated() {
            return items.stream().filter(i -> i.ok() && i.what().equals("method")).count();
        }

        long methods() {
            return items.stream().filter(i -> i.what().equals("method")).count();
        }

        /** Methods not translated because Horizon does their job another way (they're among methods()). */
        long covered() {
            return items.stream().filter(i -> i.what().equals("method") && !i.ok() && i.reason() != null && i.reason().startsWith(COVERED)).count();
        }
    }

    static final String COVERED = "done another way in Horizon: ";

    /**
     * Java whose job the bundle does another way: the mod initializer is the init hook, a ModMenu entry point is
     * the mod's page in Options > Mods, KeyMappings and their category are the mod's Horizon keys.
     */
    private List<Item> coveredElsewhere(List<Item> items) {
        boolean initHandled = handlers.stream().anyMatch(h -> h.hook().equals("init") && h.ok());
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            String why = null;
            if (!i.ok() && i.what().equals("method")) {
                Cls c = clsOfItem(i.name());
                if (c != null && c.d.impls.stream().anyMatch(t -> Set.of("ClientModInitializer", "ModInitializer").contains(t.simple()))
                        && i.name().endsWith(".onInitializeClient(0)") && initHandled)
                    why = "it runs as `on_init`, from Horizon's init hook";
                else if (c != null && c.d.impls.stream().anyMatch(t -> t.simple().equals("ModMenuApi")))
                    why = "the mod's page in Options > Mods (its settings buttons)";
            }
            if (!i.ok() && i.what().equals("field") && i.reason() != null && !modKeys.isEmpty()
                    && (i.reason().contains("Minecraft's `KeyMapping`") || i.reason().contains("Minecraft's `Category`")))
                why = "the mod's keys in Options > Mods, read through Horizon's key hook";
            out.add(why == null ? i : new Item(i.name(), i.what(), false, COVERED + why, i.file(), i.line()));
        }
        return out;
    }

    /** The class of a report item named `a.b.Class.method(n)` or `a.b.Class.FIELD`. */
    private Cls clsOfItem(String name) {
        int end = name.contains("(") ? name.indexOf('(') : name.length();
        int dot = name.lastIndexOf('.', end);
        if (dot < 0) return null;
        String owner = name.substring(0, dot);
        for (Cls c : all) if (c.d.qualified.equals(owner)) return c;
        return null;
    }

    // ---- types ----

    /**
     * k: prim, string, array, list, cls, enum, void, ext; map (key, elem = value), set, opt (name "Optional" or
     * "nullable": a Java reference that can be null), view (a map's keys/values/entries: name says which), entry.
     */
    record JT(String k, String name, JT elem, JT key) {
        JT(String k, String name, JT elem) {
            this(k, name, elem, null);
        }

        static JT opt(String name, JT elem) {
            return new JT("opt", name, elem);
        }

        static JT prim(String n) {
            return new JT("prim", n, null);
        }

        static final JT INT = prim("int"), LONG = prim("long"), FLOAT = prim("float"), DOUBLE = prim("double"),
                BOOL = prim("boolean"), CHAR = prim("char");
        static final JT STRING = new JT("string", "String", null);
        static final JT VOID = new JT("void", "void", null);

        boolean is(String kind) {
            return k.equals(kind);
        }

        boolean numeric() {
            return k.equals("prim") && !name.equals("boolean");
        }

        boolean copy() {
            return k.equals("prim") || k.equals("enum") || k.equals("hand") || k.equals("arm") || k.equals("vec3d")
                    || k.equals("vec3f") || k.equals("quat") || k.equals("mat4") || k.equals("mat3") || k.equals("useanim") || k.equals("displayctx")
                    || k.equals("modeltype") || k.equals("duration") || k.equals("depkind") || k.equals("timeunit");
        }

        boolean isBool() {
            return k.equals("prim") && name.equals("boolean");
        }

        boolean floating() {
            return k.equals("prim") && (name.equals("float") || name.equals("double"));
        }

        @Override
        public String toString() {
            return switch (k) {
                case "array" -> elem + "[]";
                case "list" -> "List<" + elem + ">";
                case "map" -> "Map<" + key + ", " + elem + ">";
                case "set" -> "Set<" + elem + ">";
                case "opt" -> name.equals("Optional") ? "Optional<" + elem + ">" : elem + " (or null)";
                default -> name;
            };
        }
    }

    /** A translated expression: its Rust code, Java type, and whether it's a fresh value (owned) or a place. */
    record Ex(String code, JT type, boolean owned, String place) {
        Ex(String code, JT type) {
            this(code, type, true, null);
        }
    }

    // ---- the mod's classes ----

    static final class Cls {
        ClassDecl d;
        String file;
        Map<String, String> imports;
        String module; // rust module in `java`
        String rust; // the type name
        boolean simpleEnum;
        String failed; // why the type itself can't be used
        Map<String, FieldDecl> fields = new LinkedHashMap<>();
        Map<String, List<MethodDecl>> methods = new LinkedHashMap<>();
        Cls outer;
        String singleton; // static field holding the config instance
        boolean mixin;
        Map<String, String> staticNames;
    }

    private final Map<String, Cls> classes = new LinkedHashMap<>(); // by qualified and simple name
    private final List<Cls> all = new ArrayList<>();

    // ---- facts that grow until the translation settles ----

    private final Set<String> mutSelf = new HashSet<>();
    private final Set<String> mutParam = new HashSet<>();
    private final Set<String> mutLocal = new HashSet<>();
    private final Set<String> mutStatic = new HashSet<>();
    /** Java's functional interfaces in use: a JT "fn" named "fn#i" has parameters and result fnSigs.get(i). */
    private final List<List<JT>> fnSigs = new ArrayList<>();

    /** The type of a function value (Supplier, Predicate, Consumer...): parameters, then the result (VOID for none). */
    private JT fnType(List<JT> params, JT ret) {
        List<JT> sig = new ArrayList<>(params);
        sig.add(ret);
        int i = fnSigs.indexOf(sig);
        if (i < 0) {
            fnSigs.add(sig);
            i = fnSigs.size() - 1;
        }
        return new JT("fn", "fn#" + i, ret);
    }

    private List<JT> fnParams(JT t) {
        List<JT> sig = fnSigs.get(Integer.parseInt(t.name().substring(3)));
        return sig.subList(0, sig.size() - 1);
    }

    /** The Rust type a function parameter takes: a borrowed closure whose arguments are borrowed too. */
    private String fnRust(JT t, Cls ctx) {
        List<String> ps = new ArrayList<>();
        for (JT p : fnParams(t)) ps.add("&" + owned(p, ctx));
        return "&dyn Fn(" + String.join(", ", ps) + ")" + (t.elem().is("void") ? "" : " -> " + owned(t.elem(), ctx));
    }

    /** Java's functional interfaces, as a parameter list and a result. */
    private JT functional(TypeRef t, Cls ctx) {
        List<TypeRef> a = t.args();
        java.util.function.IntFunction<JT> arg = i -> {
            if (i >= a.size()) throw new JavaParser.Unsupported("a raw " + t.simple());
            return resolve(a.get(i), ctx);
        };
        return switch (t.simple()) {
            case "Supplier" -> fnType(List.of(), arg.apply(0));
            case "BooleanSupplier" -> fnType(List.of(), JT.BOOL);
            case "IntSupplier" -> fnType(List.of(), JT.INT);
            case "LongSupplier" -> fnType(List.of(), JT.LONG);
            case "DoubleSupplier" -> fnType(List.of(), JT.DOUBLE);
            case "Runnable" -> fnType(List.of(), JT.VOID);
            case "Predicate" -> fnType(List.of(arg.apply(0)), JT.BOOL);
            case "BiPredicate" -> fnType(List.of(arg.apply(0), arg.apply(1)), JT.BOOL);
            case "IntPredicate" -> fnType(List.of(JT.INT), JT.BOOL);
            case "Function" -> fnType(List.of(arg.apply(0)), arg.apply(1));
            case "BiFunction" -> fnType(List.of(arg.apply(0), arg.apply(1)), arg.apply(2));
            case "UnaryOperator" -> fnType(List.of(arg.apply(0)), arg.apply(0));
            case "BinaryOperator" -> fnType(List.of(arg.apply(0), arg.apply(0)), arg.apply(0));
            case "Consumer" -> fnType(List.of(arg.apply(0)), JT.VOID);
            case "BiConsumer" -> fnType(List.of(arg.apply(0), arg.apply(1)), JT.VOID);
            case "IntConsumer" -> fnType(List.of(JT.INT), JT.VOID);
            case "DoubleConsumer" -> fnType(List.of(JT.DOUBLE), JT.VOID);
            case "IntFunction" -> fnType(List.of(JT.INT), arg.apply(0));
            case "DoubleFunction" -> fnType(List.of(JT.DOUBLE), arg.apply(0));
            case "ToIntFunction" -> fnType(List.of(arg.apply(0)), JT.INT);
            case "ToDoubleFunction" -> fnType(List.of(arg.apply(0)), JT.DOUBLE);
            case "IntUnaryOperator" -> fnType(List.of(JT.INT), JT.INT);
            case "DoubleUnaryOperator" -> fnType(List.of(JT.DOUBLE), JT.DOUBLE);
            default -> null;
        };
    }

    private static final Set<String> FN_CALLS = Set.of("get", "getAsBoolean", "getAsInt", "getAsLong", "getAsDouble", "run", "test", "apply",
            "accept", "applyAsInt", "applyAsLong", "applyAsDouble");

    /** Calling a function value: `get()`, `test(x)`, `apply(x)`, `accept(x)`... */
    private Ex fnCall(Ex f, Call c, Ctx x) {
        if (!FN_CALLS.contains(c.name())) throw new JavaParser.Unsupported("." + c.name() + " on a function");
        List<JT> ps = fnParams(f.type());
        if (c.args().size() != ps.size()) throw new JavaParser.Unsupported("a function called with " + c.args().size() + " values");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) args.add("&" + paren(valueTo(c.args().get(i), ps.get(i), x).code()));
        return new Ex("(" + f.code() + ")(" + String.join(", ", args) + ")", f.type().elem());
    }

    /** A lambda, method reference or function parameter passed where a function goes. */
    private String fnArg(Expr e, JT pt, Ctx x) {
        if (e instanceof Lambda || e instanceof MethodRef) {
            Fn f = closure(e, fnParams(pt), true, pt.elem().is("void") ? JT.VOID : pt.elem(), x);
            return "&(" + f.code() + ")";
        }
        Ex v = ex(e, x);
        if (!v.type().equals(pt)) throw new JavaParser.Unsupported("a " + v.type() + " passed as a function");
        return v.code();
    }
    /** Classes Gson reads or writes: they get to_jjson / from_jjson. */
    private final Set<String> jsonClasses = new TreeSet<>();
    private final Map<String, String> failed = new TreeMap<>();
    private boolean changed;
    /** Declarations Java sets to or compares with null: fieldKey, methodKey + ":" + local, methodKey + "#ret". */
    private final Set<String> nullable = new HashSet<>();
    /** Approximations made, for the report. */
    private final Set<String> notes = new TreeSet<>();
    /** Static fields (fieldKey) and the methods that write them, for the port-constant facts. */
    private final Map<String, List<String>> staticWriters = new HashMap<>();

    // ---- the game side: code that needs the game, compiled inside Horizon ----

    /** True while translating for game_side.rs, where Minecraft's types map to Horizon's helpers. */
    private boolean gameMode;
    private final Set<String> gameOk = new HashSet<>();
    /** Static fields (fieldKey) kept in game_side.rs because their type is a game one (an ItemStack, a hand...). */
    private final Set<String> gameStatics = new HashSet<>();
    /** True while emitting a game-side static: its Rust name is gameStaticName. */
    private boolean gameStaticNaming;
    private final Map<String, String> gameFailed = new HashMap<>();
    /** Game-side functions that draw on the HUD, so they take `batch`. */
    private final Set<String> needsBatch = new HashSet<>();
    /** The mod's own KeyMapping statics (fieldKey) to their key name (`inspect`). */
    private final Map<String, String> keyFields = new LinkedHashMap<>();
    /** Key name to default key (`key.keyboard.y`), in order. */
    private final Map<String, String> keyDefaults = new LinkedHashMap<>();
    private List<String[]> modKeys = List.of();
    /** The mod's English texts (assets/<ns>/lang/en_us.json), for labels. */
    private Map<String, String> lang = Map.of();

    /** assets/<ns>/lang/en_us.json under a decompiled mod, merged. */
    static Map<String, String> lang(Path root) {
        Map<String, String> out = new TreeMap<>();
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return out;
        try (Stream<Path> walk = Files.walk(assets, 3)) {
            for (Path f : (Iterable<Path>) walk.filter(p -> p.getFileName().toString().equals("en_us.json") && p.getParent().getFileName().toString().equals("lang"))::iterator) {
                if (Json.parse(Text.read(f)) instanceof Map<?, ?> m)
                    m.forEach((k, v) -> { if (v instanceof String sv) out.put(String.valueOf(k), sv); });
            }
        } catch (IOException | RuntimeException e) {
            // no labels from the mod then
        }
        return out;
    }

    /** The mod's text for a key used in its screens (`"arm_x"` -> "handanimations.option.arm_x" -> "Arm X"). */
    private String langLabel(String key) {
        if (key == null) return null;
        String best = null;
        for (Map.Entry<String, String> e : lang.entrySet()) {
            if (!e.getKey().endsWith("." + key)) continue;
            if (best == null || e.getKey().contains(".option") || e.getKey().contains(".config")) best = e.getValue();
        }
        return best;
    }

    /**
     * The string key each option has in the mod's own screens: a call with a string literal and the option (as a
     * value `c.showArm`, or in a lambda `() -> c.armX` / `v -> c.armX = v`).
     */
    private Map<String, String> optionKeys(Cls cfg) {
        Set<String> names = new HashSet<>();
        for (FieldDecl f : options(cfg)) names.add(f.name);
        Map<String, String> out = new HashMap<>();
        for (Cls k : all)
            for (MethodDecl m : k.d.methods) {
                if (m.body == null) continue;
                walk(m.body, n -> {
                    if (!(n instanceof Call call)) return;
                    String literal = null;
                    Set<String> fields = new LinkedHashSet<>();
                    for (Expr a : call.args()) {
                        if (a instanceof Lit l && l.kind().equals("string") && literal == null) literal = l.text().substring(1, l.text().length() - 1);
                        walk(a, x -> {
                            if (x instanceof Field f && names.contains(f.name())) fields.add(f.name());
                        });
                    }
                    if (literal == null || fields.size() != 1) return;
                    out.putIfAbsent(fields.iterator().next(), literal);
                });
            }
        return out;
    }

    /** An option's label: the mod's own text for it, else its field name in words. */
    private String optionLabel(Cls cfg, String field) {
        String l = langLabel(optionKeys(cfg).get(field));
        return l != null ? l : label(field);
    }
    private final List<Handler> handlers = new ArrayList<>();
    private String runtimePath = "crate::game::port_mod";

    static Result translate(Path root, String modId) throws IOException {
        return translate(root, modId, List.of());
    }

    /**
     * A mixin handler the generator wires into Horizon's first-person hand drawing: translated on the game side with
     * its parameters in POSE_ORDER, whatever their Java order (roles: the role of each Java parameter, by position).
     */
    record PoseHook(String mixinClass, String handler, String fn, List<String> roles) {}

    /** A mixin handler that is a Fabric-like event in Horizon (`join`: ClientPacketListener.handleLogin). */
    record EventHook(String hook, String mixinClass, String handler) {}

    private List<EventHook> eventHooks = List.of();

    static final List<String> POSE_ORDER = List.of("pose", "hand", "stack", "attack", "inverse_height", "partial", "xrot");
    private List<PoseHook> poseHooks = List.of();
    /** Names a method's body sees that aren't its parameters (a mixin handler's player is the game's). */
    private final Map<MethodDecl, Map<String, Var>> preAliases = new java.util.IdentityHashMap<>();

    static Result translate(Path root, String modId, List<String[]> keys) throws IOException {
        return translate(root, modId, keys, List.of());
    }

    static Result translate(Path root, String modId, List<String[]> keys, List<PoseHook> hooks) throws IOException {
        return translate(root, modId, keys, hooks, List.of());
    }

    /** keys: the mod's keys as the generator found them: name, label, default key, full name. */
    static Result translate(Path root, String modId, List<String[]> keys, List<PoseHook> hooks, List<EventHook> events) throws IOException {
        RustTranslator t = new RustTranslator();
        t.eventHooks = events;
        t.modKeys = keys;
        t.lang = lang(root);
        t.poseHooks = hooks;
        Path fmj = root.resolve("fabric.mod.json");
        if (Files.isRegularFile(fmj) && Json.parse(Text.read(fmj)) instanceof Map<?, ?> fm) t.fabricMod = fm;
        t.runtimePath = "crate::game::port_" + modId;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java")).sorted()::iterator) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                Unit u;
                try {
                    u = JavaParser.parseUnit(Text.read(f), rel);
                } catch (JavaParser.Unsupported e) {
                    t.unparsed.add(new Item(rel, "file", false, "couldn't be read: " + e.getMessage(), rel, 1));
                    continue;
                }
                t.add(u);
            }
        }
        return t.run(modId);
    }

    /** Translates source text directly (for tests). */
    static Result translateText(String javaSource) {
        RustTranslator t = new RustTranslator();
        t.runtimePath = "crate::game::port_test";
        t.add(JavaParser.parseUnit(javaSource, "Test.java"));
        return t.run("test");
    }

    private final List<Item> unparsed = new ArrayList<>();

    private void add(Unit u) {
        Map<String, String> imports = new HashMap<>();
        for (String i : u.imports()) {
            if (i.startsWith("static ") || i.endsWith(".*")) continue;
            imports.put(i.substring(i.lastIndexOf('.') + 1), i);
        }
        for (ClassDecl d : u.classes()) addClass(d, null, u.file(), imports);
    }

    private void addClass(ClassDecl d, Cls outer, String file, Map<String, String> imports) {
        Cls c = new Cls();
        c.d = d;
        c.file = file;
        c.imports = imports;
        c.outer = outer;
        c.module = snake(d.qualified.replace('.', '_'));
        // A class named Type, Box or Loop: `mod type` isn't Rust.
        if (KEYWORDS.contains(c.module) || c.module.equals("self") || c.module.equals("super") || c.module.equals("crate")) c.module = c.module + "_";
        c.rust = RUST_PRELUDE.contains(d.name) ? d.name + "_" : d.name;
        if (d.kind.equals("record")) record(d);
        for (FieldDecl f : d.fields) c.fields.put(f.name, f);
        for (MethodDecl m : d.methods) c.methods.computeIfAbsent(m.name, k -> new ArrayList<>()).add(m);
        all.add(c);
        classes.put(d.qualified, c);
        classes.putIfAbsent(d.name, c);
        for (ClassDecl n : d.nested) addClass(n, c, file, imports);
    }

    /** A record's components become final fields, its canonical constructor and its accessors. */
    private static void record(ClassDecl d) {
        boolean ctor = d.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.params.size() == d.components.size());
        List<Stmt> assigns = new ArrayList<>();
        for (Param p : d.components) {
            FieldDecl f = new FieldDecl();
            f.mods = Set.of("private", "final");
            f.type = p.type();
            f.name = p.name();
            f.line = d.line;
            d.fields.add(f);
            assigns.add(new ExprStmt(new Assign("=", new Field(new This(), p.name()), new Name(p.name()))));
            if (d.methods.stream().noneMatch(m -> m.name.equals(p.name()) && m.params.isEmpty())) {
                MethodDecl a = new MethodDecl();
                a.mods = Set.of("public");
                a.ret = p.type();
                a.name = p.name();
                a.body = new Block(List.of(new Return(new Field(new This(), p.name()))));
                a.line = d.line;
                d.methods.add(a);
            }
        }
        if (!ctor) {
            MethodDecl m = new MethodDecl();
            m.mods = Set.of("public");
            m.name = "<init>";
            m.params.addAll(d.components);
            m.body = new Block(assigns);
            m.line = d.line;
            d.methods.add(0, m);
        }
    }

    // ---- the run ----

    /** Passes until no fact changes; a long chain of callers needs one pass per link. */
    private static final int MAX_ROUNDS = 200;
    int rounds;

    private Result run(String modId) {
        for (Cls c : all) classCheck(c);
        // Two classes with one name in different packages: the translator finds classes by name, so it can't tell
        // which one a use means. Neither is translated, and each keeps a module name of its own.
        Map<String, List<Cls>> byModule = new LinkedHashMap<>();
        for (Cls c : all) byModule.computeIfAbsent(c.module, k -> new ArrayList<>()).add(c);
        for (List<Cls> same : byModule.values()) {
            if (same.size() < 2) continue;
            for (int i = 0; i < same.size(); i++) {
                Cls c = same.get(i);
                if (i > 0) c.module = c.module + "_" + (i + 1);
                if (!c.mixin) c.failed = "another class has the same name, in a different package";
            }
        }
        findKeys();
        findNullables();
        findStaticWriters();
        String rust = "", game = "";
        List<Item> items = new ArrayList<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            changed = false;
            items = new ArrayList<>(unparsed);
            rust = emitAll(items);
            game = emitGame();
            rounds = round + 1;
            if (!changed) break;
        }
        // The report's reasons from the last pass of both sides.
        items = new ArrayList<>(unparsed);
        rust = emitAll(items);
        Config config = null;
        for (Cls c : all) {
            if (c.singleton != null && c.failed == null && !failed.containsKey(fieldKey(c, c.singleton))) {
                config = configOf(c);
                break;
            }
        }
        if (java.util.regex.Pattern.compile("\\bJ(Vec3|Quat|Mat4|Mat3|PoseStack)\\b").matcher(rust + game).find()) rust = rust + "\n" + joml();
        if (java.util.regex.Pattern.compile("\\bJ(Regex|Matcher)\\b").matcher(rust + game).find()) rust = rust + "\n" + resource("jregex.rs");
        if (java.util.regex.Pattern.compile("\\bJ(Json|Gson)\\b").matcher(rust + game).find()) rust = rust + "\n" + resource("jjson.rs");
        if (java.util.regex.Pattern.compile("\\bJProps\\b").matcher(rust + game).find()) rust = rust + "\n" + resource("jprops.rs");
        if (usesModInfo && (rust + game).contains("j_mod(")) rust = rust + "\n" + modInfo();
        if (!game.isBlank() && java.util.regex.Pattern.compile("\\bJHttpClient\\b").matcher(rust + game).find()) {
            game = game.replace("    INIT.call_once(|| {\n", "    INIT.call_once(|| {\n        let _ = J_HTTP.set(j_http_ureq);\n") + HTTP_GLUE;
        }
        if (java.util.regex.Pattern.compile("\\bJ(Version|Mod|ModDep)\\b").matcher(rust + game).find()) rust = rust + "\n" + resource("jfabric.rs");
        if (java.util.regex.Pattern.compile("\\bJHttp(Client|Request|Response)\\b|\\bj_url_encode\\(").matcher(rust + game).find()) rust = rust + "\n" + resource("jhttp.rs");
        if (java.util.regex.Pattern.compile("\\bJFuture\\b").matcher(rust + game).find()) rust = rust + "\n" + resource("jfuture.rs");
        if (java.util.regex.Pattern.compile("\\bJIter\\b").matcher(rust + game).find()) rust = rust + "\n" + resource("jiter.rs");
        // The Fabric API layer: events and client commands (game side: it runs on Horizon's Game).
        if (java.util.regex.Pattern.compile("\\bJ_FABRIC\\b|\\bJCmdNode\\b").matcher(game).find()) game = game + "\n" + resource("jfabricapi.rs");
        items = coveredElsewhere(items);
        return new Result(rust, items, config, game.isBlank() ? null : game, new ArrayList<>(handlers), new ArrayList<>(new TreeSet<>(notes)));
    }

    /** JOML's vectors, quaternions and matrices and Minecraft's PoseStack, in Rust (joml.rs, shipped in the jar). */
    private static String joml() {
        return resource("joml.rs");
    }

    /** Rust shipped in the jar next to this class (joml.rs, jregex.rs). */
    private static String resource(String name) {
        try (var in = RustTranslator.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " isn't in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Whether the class's own shape can be Rust: kind, inheritance, instance fields. */
    private void classCheck(Cls c) {
        ClassDecl d = c.d;
        if (d.mods.contains("@Mixin")) {
            c.failed = "a Mixin class: its handlers are the injections, in the bundle's helpers";
            c.mixin = true;
        } else if (d.kind.equals("interface") || d.kind.equals("annotation")) c.failed = "it's an " + d.kind;
        else if (d.generic) c.failed = "it's generic";
        else if (d.ext != null) c.failed = "it extends " + describe(d.ext.simple(), c);
        else if (!d.impls.isEmpty()) c.failed = "it implements " + describe(d.impls.get(0).simple(), c);
        if (c.failed == null) {
            for (FieldDecl f : d.fields) {
                if (f.mods.contains("static")) continue;
                if (f.type.dims() == 0 && f.type.simple().equals(d.name)) c.failed = "it holds an object of its own class (a linked structure)";
                else if (f.type.name().startsWith("Atomic") && f.type.name().length() > 6) c.failed = "it keeps an atomic value in an object's field";
            }
        }
        if (d.kind.equals("enum")) {
            boolean simple = d.constants.stream().noneMatch(k -> !k.args().isEmpty() || k.body());
            boolean fields = d.fields.stream().anyMatch(f -> !f.mods.contains("static"));
            if (!simple || fields) c.failed = "its constants carry values";
            else c.simpleEnum = true;
        }
        for (FieldDecl f : d.fields) {
            if (f.mods.contains("static") && f.type.name().equals(d.name) && f.type.dims() == 0 && f.init instanceof New nw
                    && nw.type().name().equals(d.name) && nw.args().isEmpty() && !d.kind.equals("enum")) {
                boolean options = d.fields.stream().anyMatch(o -> !o.mods.contains("static") && o.mods.contains("public"));
                if (options) c.singleton = f.name;
            }
        }
    }

    private String describe(String simple, Cls c) {
        String full = c.imports.get(simple);
        if (full == null) return "`" + simple + "`";
        if (full.startsWith("net.minecraft.") || full.startsWith("com.mojang.")) return "Minecraft's `" + simple + "`";
        if (full.startsWith("net.fabricmc.")) return "Fabric's `" + simple + "`";
        return "`" + simple + "` (" + full.substring(0, full.lastIndexOf('.')) + ")";
    }

    // ---- emitting ----

    private String emitAll(List<Item> items) {
        StringBuilder b = new StringBuilder();
        b.append(PRELUDE);
        for (Cls c : all) {
            if (c.mixin) {
                items.add(new Item(c.d.qualified, "mixin", false, c.failed, c.file, c.d.line));
                for (FieldDecl f : c.d.fields) if (f.mods.contains("static")) failed.putIfAbsent(fieldKey(c, f.name), "a Mixin's static (game side only)");
                continue;
            }
            StringBuilder m = new StringBuilder();
            emitClass(c, m, items);
            b.append('\n').append(m);
        }
        return b.toString();
    }

    private void emitClass(Cls c, StringBuilder b, List<Item> items) {
        String jname = c.d.qualified;
        b.append("/// `").append(jname).append("` (").append(c.file).append(")\n");
        b.append("pub mod ").append(c.module).append(" {\n    use super::*;\n");
        if (c.failed != null) {
            items.add(new Item(jname, "class", false, c.failed, c.file, c.d.line));
            b.append("    // The class itself isn't translated: ").append(c.failed).append(". Its static parts are below.\n");
        } else {
            items.add(new Item(jname, "class", true, null, c.file, c.d.line));
        }
        // Constants and statics.
        for (FieldDecl f : c.d.fields) {
            if (!f.mods.contains("static")) continue;
            String key = fieldKey(c, f.name);
            try {
                String code = staticField(c, f);
                if (code == null) continue;
                b.append(indent(code, 1));
                items.add(new Item(jname + "." + f.name, "field", true, null, c.file, f.line));
            } catch (JavaParser.Unsupported e) {
                fail(key, e.getMessage());
                if (gameStatics.contains(key)) {
                    items.add(new Item(jname + "." + f.name, "field", true, "game", c.file, f.line));
                    b.append("    // ").append(f.name).append(": a game value, so it's in game_side.rs.\n");
                    continue;
                }
                items.add(new Item(jname + "." + f.name, "field", false, e.getMessage(), c.file, f.line));
                b.append("    // ").append(f.name).append(": not translated, ").append(e.getMessage()).append(".\n");
            }
        }
        // The type.
        boolean typeOk = c.failed == null;
        if (typeOk && c.simpleEnum) {
            b.append("    #[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Hash)]\n    pub enum ").append(c.rust).append(" {\n");
            for (EnumConst k : c.d.constants) b.append("        ").append(camel(k.name())).append(",\n");
            b.append("    }\n    impl ").append(c.rust).append(" {\n        pub const VALUES: [").append(c.rust).append("; ")
                    .append(c.d.constants.size()).append("] = [");
            for (EnumConst k : c.d.constants) b.append(c.rust).append("::").append(camel(k.name())).append(", ");
            b.append("];\n        /// Java's `name()`.\n        pub fn java_name(self) -> &'static str {\n            match self {\n");
            for (EnumConst k : c.d.constants) b.append("                ").append(c.rust).append("::").append(camel(k.name()))
                    .append(" => \"").append(k.name()).append("\",\n");
            b.append("            }\n        }\n        /// Java's `valueOf`: panics on a name that isn't one, as Java throws.\n        pub fn value_of(name: &str) -> Self {\n            match name {\n");
            for (EnumConst k : c.d.constants) b.append("                \"").append(k.name()).append("\" => ").append(c.rust).append("::").append(camel(k.name())).append(",\n");
            b.append("                _ => panic!(\"No enum constant {name}\"),\n            }\n        }\n    }\n");
        } else if (typeOk) {
            StringBuilder s = new StringBuilder();
            try {
                // A record compares by its components, as Java's record equals does.
                s.append(c.d.kind.equals("record") && eqable(new JT("cls", c.d.qualified, null)) ? "    #[derive(Clone, Debug, PartialEq)]\n" : "    #[derive(Clone, Debug)]\n")
                        .append("    pub struct ").append(c.rust).append(" {\n");
                for (FieldDecl f : c.d.fields) {
                    if (f.mods.contains("static")) continue;
                    if (f.error != null) throw new JavaParser.Unsupported("field " + f.name + ": " + f.error);
                    JT t = fieldType(c, f);
                    s.append("        pub ").append(rustName(f.name)).append(": ").append(owned(t, c)).append(",\n");
                }
                s.append("    }\n");
                b.append(s);
            } catch (JavaParser.Unsupported e) {
                c.failed = e.getMessage();
                changed = true;
                b.append("    // The struct isn't translated: ").append(e.getMessage()).append(".\n");
            }
        }
        // Methods.
        StringBuilder statics = new StringBuilder();
        StringBuilder impl = new StringBuilder();
        boolean hasCtor = c.d.methods.stream().anyMatch(m -> m.name.equals("<init>"));
        if (!hasCtor && c.failed == null && !c.simpleEnum) {
            MethodDecl implicit = new MethodDecl();
            implicit.mods = Set.of("public");
            implicit.name = "<init>";
            implicit.body = new Block(List.of());
            implicit.line = c.d.line;
            emitMethod(c, implicit, statics, impl, items, true);
        }
        for (MethodDecl m : c.d.methods) emitMethod(c, m, statics, impl, items, false);
        if (c.singleton != null && c.failed == null) impl.append(indent(configMethods(c), 2));
        if (jsonClasses.contains(c.d.qualified) && c.failed == null) {
            try {
                impl.append(indent(jsonMethods(c), 2));
            } catch (JavaParser.Unsupported e) {
                fail(c.d.qualified + "#json", "Gson can't be done for it: " + e.getMessage());
                impl.append("        // Gson's reading and writing of it isn't translated: ").append(e.getMessage()).append(".\n");
            }
        }
        if (impl.length() > 0 && c.failed == null) b.append("    impl ").append(c.rust).append(" {\n").append(impl).append("    }\n");
        b.append(statics);
        if (c.singleton != null && c.failed == null) b.append(indent(configStatics(c), 1));
        b.append("}\n");
    }

    private void emitMethod(Cls c, MethodDecl m, StringBuilder statics, StringBuilder impl, List<Item> items, boolean implicit) {
        String key = methodKey(c, m);
        String jname = c.d.qualified + "." + (m.name.equals("<init>") ? c.d.name : m.name) + "(" + m.params.size() + ")";
        try {
            if (failed.containsKey(key)) throw new JavaParser.Unsupported(failed.get(key));
            if (m.body == null) throw new JavaParser.Unsupported(m.error != null ? "its body couldn't be read: " + m.error : "it has no body");
            if (m.generic) throw new JavaParser.Unsupported("it's generic");
            if (!m.isStatic() && c.failed != null) throw new JavaParser.Unsupported("its class isn't translated (" + c.failed + ")");
            if (m.mods.contains("abstract") || m.mods.contains("native")) throw new JavaParser.Unsupported("it has no body");
            String code = method(c, m);
            if (m.isStatic()) statics.append(indent(code, 1));
            else impl.append(indent(code, 2));
            if (!implicit) items.add(new Item(jname, "method", true, null, c.file, m.line));
        } catch (JavaParser.Unsupported e) {
            fail(key, e.getMessage());
            if (!implicit && gameOk.contains(key)) {
                items.add(new Item(jname, "method", true, "game", c.file, m.line));
                statics.append("    // ").append(jname).append(": needs the game, so it's in game_side.rs.\n");
                return;
            }
            String why = e.getMessage();
            if (gameFailed.containsKey(key) && (why.contains("Minecraft's") || why.contains("isn't translated"))) why = gameFailed.get(key);
            if (!implicit) items.add(new Item(jname, c.mixin ? "handler" : "method", false, why, c.file, m.line));
            StringBuilder target = m.isStatic() || c.failed != null ? statics : impl;
            int depth = m.isStatic() || c.failed != null ? 1 : 2;
            if (!implicit && !c.mixin) target.append("    ".repeat(depth)).append("// ").append(jname).append(": not translated, ")
                    .append(e.getMessage().replace("\n", " ")).append(".\n");
        }
    }

    private void fail(String key, String why) {
        if (!failed.containsKey(key)) {
            failed.put(key, why);
            changed = true;
        }
    }

    private static String fieldKey(Cls c, String name) {
        return c.d.qualified + "#" + name;
    }

    private static String methodKey(Cls c, MethodDecl m) {
        return c.d.qualified + "." + m.name + "/" + m.params.size();
    }

    // ---- statics ----

    /** How a static field is kept in Rust. */
    private String staticKind(Cls c, FieldDecl f) {
        if (isLogger(f)) return "log";
        boolean fin = f.mods.contains("final");
        boolean mutated = mutStatic.contains(fieldKey(c, f.name)) || fieldKey(c, f.name).equals(singletonKey(c));
        if (fin && !mutated && f.init != null && constExpr(f.init, c) && (isPrimType(f.type) || f.type.name().equals("String") && f.type.dims() == 0)) return "const";
        if (fin && !mutated) return "lazy";
        return "cell";
    }

    private String singletonKey(Cls c) {
        return c.singleton == null ? null : fieldKey(c, c.singleton);
    }

    private static boolean isLogger(FieldDecl f) {
        return f.type.simple().equals("Logger");
    }

    private static boolean isPrimType(TypeRef t) {
        return t.dims() == 0 && Set.of("int", "long", "float", "double", "boolean", "char", "byte", "short").contains(t.name());
    }

    private String staticField(Cls c, FieldDecl f) {
        if (f.error != null) throw new JavaParser.Unsupported("its value couldn't be read: " + f.error);
        String kind = staticKind(c, f);
        if (kind.equals("log")) return "// " + f.name + ": the logger. Log calls become comments.\n";
        JT t = fieldType(c, f);
        Ctx x = new Ctx(c, null, true);
        String name = gameStaticNaming ? gameStaticName(c, f.name) : staticName(c, f.name);
        String ty = owned(t, c);
        Ex init = f.init == null ? null : valueTo(f.init, t, x);
        String value = init != null ? init.code() : zero(t);
        switch (kind) {
            case "const": {
                String cty = t.is("string") ? "&str" : ty;
                return "pub const " + name + ": " + cty + " = " + (t.is("string") ? stripToString(value) : value) + ";\n";
            }
            case "lazy":
                if (x.usesStatics) throw new JavaParser.Unsupported("its value reads other changing statics");
                return "pub static " + name + ": LazyLock<" + ty + "> = LazyLock::new(|| " + value + ");\n";
            default:
                if (x.usesStatics) throw new JavaParser.Unsupported("its value reads other changing statics");
                if (constValue(value, t)) return "pub static " + name + ": JCell<" + ty + "> = JCell::new(" + value + ");\n";
                return "pub static " + name + ": LazyLock<JCell<" + ty + ">> = LazyLock::new(|| JCell::new(" + value + "));\n";
        }
    }

    private static String stripToString(String v) {
        return v.endsWith(".to_string()") ? v.substring(0, v.length() - ".to_string()".length()) : v;
    }

    private static boolean constValue(String v, JT t) {
        if (t.is("string")) return v.equals("String::new()");
        return t.copy() && !v.contains("(") || t.copy() && v.matches("[-\\w.:]+ as \\w+|\\(?-?[\\w.]+\\)?");
    }

    private boolean constExpr(Expr e, Cls c) {
        if (e instanceof Lit l) return !l.kind().equals("null");
        if (e instanceof Unary u) return !u.op().startsWith("++") && !u.op().startsWith("--") && constExpr(u.e(), c);
        if (e instanceof Binary bn) return constExpr(bn.l(), c) && constExpr(bn.r(), c) && !isStringish(bn, c);
        if (e instanceof Cast cs) return isPrimType(cs.type()) && constExpr(cs.e(), c);
        if (e instanceof Name n) {
            FieldDecl f = c.fields.get(n.id());
            return f != null && f.mods.contains("static") && f.mods.contains("final") && f.init != null && f != null
                    && !mutStatic.contains(fieldKey(c, n.id())) && constExpr(f.init, c);
        }
        return false;
    }

    private boolean isStringish(Binary b, Cls c) {
        return b.op().equals("+") && (b.l() instanceof Lit l && l.kind().equals("string") || b.r() instanceof Lit r && r.kind().equals("string"));
    }

    // ---- methods ----

    /** Translation state for one method body. */
    final class Ctx {
        final Cls cls;
        final MethodDecl m;
        final boolean isStatic;
        final boolean ctor;
        final Deque<Map<String, Var>> scopes = new ArrayDeque<>();
        JT ret;
        int loops;
        int switches;
        int tmp;
        boolean usesStatics;
        /** Game side: `batch` (the HUD) is in scope; this is an event handler. */
        boolean batch, handler;
        /** Inside code whose errors go to an io::Result: a `throws IOException` method or a try block. */
        boolean io;
        /** How `return v` is written: "plain", "ok" (in a throws method: Ok(v)), "some" (in a try closure: Ok(Some(v))). */
        String returns = "plain";
        /** A try closure saw a `return`. */
        boolean tryReturned;
        /** Locals that are always null in the port (a component Horizon doesn't keep): no Rust variable. */
        final Set<String> constNull = new HashSet<>();
        /** The last statement was a fixed `if` whose branch always returns. */
        boolean unreachable;
        final StringBuilder out = new StringBuilder();
        int depth = 1;
        /** The target of the assignment whose value is being translated. */
        Expr assignTarget;
        /** The statements after the current one, innermost block first. */
        final Deque<List<Stmt>> after = new ArrayDeque<>();

        Ctx(Cls cls, MethodDecl m, boolean isStatic) {
            this.cls = cls;
            this.m = m;
            this.isStatic = isStatic;
            this.ctor = m != null && m.name.equals("<init>");
            scopes.push(new HashMap<>());
        }

        String key() {
            return m == null ? cls.d.qualified + "#static" : methodKey(cls, m);
        }

        Var find(String name) {
            for (Map<String, Var> s : scopes) if (s.containsKey(name)) return s.get(name);
            return null;
        }

        void line(String s) {
            out.append("    ".repeat(depth)).append(s).append('\n');
        }

        String self() {
            return ctor ? "s" : "self";
        }
    }

    /** kind: local (owns its value), ref (borrows a place), param, iter (a loop's borrowed element). */
    record Var(String java, String rust, JT type, String kind, int index) {}

    private String method(Cls c, MethodDecl m) {
        Ctx x = new Ctx(c, m, m.isStatic());
        boolean ctor = x.ctor;
        x.ret = ctor ? null : retType(c, m);
        StringBuilder sig = new StringBuilder();
        if (gameMode) sig.append("#[allow(unused_mut, unused_variables, unused_parens, unreachable_code, clippy::all)]\n");
        sig.append("pub fn ").append(gameMode ? glueName(c, m) : ctor ? ctorName(c, m) : methodName(c, m)).append('(');
        List<String> ps = new ArrayList<>();
        if (!m.isStatic() && !ctor) ps.add(mutSelf.contains(methodKey(c, m)) ? "&mut self" : "&self");
        Map<String, Var> pre = preAliases.get(m);
        if (pre != null) x.scopes.peek().putAll(pre);
        if (gameMode) {
            x.handler = m.name.startsWith("\u0001");
            x.batch = needsBatch.contains(methodKey(c, m));
            for (Param p : m.params) if (p.type().simple().equals("GuiGraphics")) x.batch = true;
            ps.add("game: &mut crate::game::Game<'_>");
            if (x.batch) ps.add("batch: &mut horizon_client::hud::HudBatch");
        }
        for (int i = 0; i < m.params.size(); i++) {
            Param p = m.params.get(i);
            JT t = paramType(c, m, i);
            if (implicitGame(t)) {
                x.scopes.peek().put(p.name(), new Var(p.name(), implicitName(t), t, "alias", i));
                continue;
            }
            boolean mut = mutParam.contains(methodKey(c, m) + ":" + i);
            String rn = varName(p.name(), x);
            String rebind = mutLocal.contains(x.key() + ":" + p.name()) && (t.copy() || t.is("opt") && param(t, false, c).startsWith("Option<")) || t.is("string") && mutLocal.contains(x.key() + ":" + p.name()) ? "mut " : "";
            if (t.is("string") && !rebind.isEmpty()) {
                ps.add(rn + ": &str");
                x.scopes.peek().put(p.name(), new Var(p.name(), rn, t, "local", i));
                x.out.append("    let mut ").append(rn).append(": String = ").append(rn).append(".to_string();\n");
                continue;
            }
            ps.add(rebind + rn + ": " + param(t, mut, c));
            x.scopes.peek().put(p.name(), new Var(p.name(), rn, t, "param", i));
        }
        sig.append(String.join(", ", ps)).append(')');
        boolean throwing = !ctor && throwsIo(m);
        if (throwing) {
            x.io = true;
            x.returns = "ok";
            sig.append(" -> std::io::Result<").append(x.ret.is("void") ? "()" : owned(x.ret, c)).append('>');
        } else if (ctor) sig.append(" -> Self");
        else if (!x.ret.is("void")) sig.append(" -> ").append(owned(x.ret, c));
        List<Stmt> bodyStmts = m.body.stmts();
        if (ctor && !bodyStmts.isEmpty() && bodyStmts.get(0) instanceof ExprStmt es0 && es0.e() instanceof Call tc && tc.name().equals("this")) {
            // this(...): the other constructor makes it, then the rest of this one.
            MethodDecl other = null;
            for (MethodDecl md : c.methods.getOrDefault("<init>", List.of())) if (md != m && md.params.size() == tc.args().size()) other = md;
            if (other == null) throw new JavaParser.Unsupported("this(...) with no matching constructor");
            if (failed.containsKey(methodKey(c, other))) throw new JavaParser.Unsupported("it calls a constructor that isn't translated");
            x.line("let mut s = Self::" + ctorName(c, other) + "(" + argList(c, other, tc.args(), x) + ");");
            bodyStmts = bodyStmts.subList(1, bodyStmts.size());
        } else if (ctor) {
            StringBuilder init = new StringBuilder("let mut s = Self {");
            List<String> parts = new ArrayList<>();
            for (FieldDecl f : c.d.fields) {
                if (f.mods.contains("static")) continue;
                JT t = fieldType(c, f);
                String v;
                if (f.init != null) v = valueTo(f.init, t, x).code();
                else if (t.is("string")) v = "String::new()";
                else if (t.is("array") || t.is("list")) v = "Vec::new()";
                else {
                    // A field with no zero value (an object): start it as the parameter the constructor stores in it.
                    Expr from = ctorParamFor(m, f.name);
                    v = from != null ? valueTo(from, t, x).code() : zero(t);
                }
                parts.add(" " + rustName(f.name) + ": " + v);
            }
            init.append(String.join(",", parts)).append(parts.isEmpty() ? "};" : " };");
            x.line(init.toString());
        }
        List<Stmt> top = bodyStmts;
        for (int i = 0; i < top.size(); i++) {
            x.after.push(top.subList(i + 1, top.size()));
            try {
                stmt(top.get(i), x);
            } finally {
                x.after.pop();
            }
            if (x.unreachable) {
                x.unreachable = false;
                break;
            }
        }
        if (ctor) x.line("s");
        if (throwing && x.ret.is("void")) x.line("Ok(())");
        // Java doesn't let a method with a result run off its end: after a try/catch whose every way returns, say so.
        if (!ctor && !x.ret.is("void") && !top.isEmpty() && !(top.get(top.size() - 1) instanceof Return) && !(top.get(top.size() - 1) instanceof Throw))
            x.line("#[allow(unreachable_code)]\n    unreachable!()");
        return sig + " {\n" + x.out + "}\n";
    }

    private String methodName(Cls c, MethodDecl m) {
        List<MethodDecl> same = c.methods.get(m.name);
        String base = rustName(snake(m.name));
        if (same.size() == 1) return base;
        long arity = same.stream().filter(o -> o.params.size() == m.params.size()).count();
        if (arity > 1) throw new JavaParser.Unsupported("it's overloaded with the same number of parameters");
        return base + "_" + m.params.size();
    }

    private String ctorName(Cls c, MethodDecl m) {
        List<MethodDecl> same = c.methods.get("<init>");
        if (same == null || same.size() == 1) return "new";
        long arity = same.stream().filter(o -> o.params.size() == m.params.size()).count();
        if (arity > 1) throw new JavaParser.Unsupported("its constructors are overloaded with the same number of parameters");
        return m.params.isEmpty() ? "new" : "new_" + m.params.size();
    }

    // ---- statements ----

    private void stmt(Stmt s, Ctx x) {
        switch (s) {
            case Block b -> {
                x.line("{");
                body(b.stmts(), x);
                x.line("}");
            }
            case Empty e -> { }
            case Note n -> x.line("// TODO: " + n.text().replace("\n", " "));
            case Yield y -> throw new JavaParser.Unsupported("a `yield` outside a switch expression's last statement");
            case Local l -> local(l, x);
            case ExprStmt es -> exprStmt(es.e(), x);
            case If i -> ifStmt(i, x, false);
            case While w -> {
                Ex c = cond(w.c(), x);
                x.line(c.code().equals("true") ? "loop {" : "while " + c.code() + " {");
                loop(w.body(), x);
                x.line("}");
            }
            case DoWhile d -> {
                if (hasContinue(d.body())) throw new JavaParser.Unsupported("a do-while loop with `continue`");
                x.line("loop {");
                loop(d.body(), x);
                x.depth++;
                x.line("if !(" + cond(d.c(), x).code() + ") {");
                x.line("    break;");
                x.line("}");
                x.depth--;
                x.line("}");
            }
            case For f -> forStmt(f, x);
            case ForEach f -> forEach(f, x);
            case Return r -> {
                String v;
                if (r.e() == null) {
                    if (x.ctor) {
                        if (!x.returns.equals("plain")) throw new JavaParser.Unsupported("a return from a constructor inside a try");
                        x.line("return s;");
                        break;
                    }
                    v = x.returns.equals("plain") ? null : "()";
                } else {
                    if (x.ret == null || x.ret.is("void")) throw new JavaParser.Unsupported("returns a value from a void method");
                    v = valueTo(r.e(), x.ret, x).code();
                }
                switch (x.returns) {
                    case "ok" -> x.line("return Ok(" + v + ");");
                    case "some" -> {
                        x.tryReturned = true;
                        x.line("return Ok(Some(" + v + "));");
                    }
                    default -> x.line(v == null ? "return;" : "return " + v + ";");
                }
            }
            case Break b -> {
                if (b.label() != null) throw new JavaParser.Unsupported("a labelled break");
                if (x.switches > 0 && x.loops == 0) throw new JavaParser.Unsupported("a `break` inside a switch case");
                x.line("break;");
            }
            case Continue cn -> {
                if (cn.label() != null) throw new JavaParser.Unsupported("a labelled continue");
                if (x.loops == 0) throw new JavaParser.Unsupported("a `continue` outside a loop");
                x.line("continue;");
            }
            case Throw t -> {
                String msg = "\"" + (t.e() instanceof New n ? n.type().simple() : "exception") + "\"";
                if (t.e() instanceof New n && n.args().size() == 1) {
                    Ex m = ex(n.args().get(0), x);
                    if (m.type().is("string")) msg = "\"{}\", " + m.code();
                }
                x.line("panic!(" + msg + ");");
            }
            case Try t -> tryStmt(t, x);
            case Switch sw -> switchStmt(sw, x);
            case Sync sy -> {
                x.line("// (was synchronized: the statics it uses are locked one at a time)");
                x.line("{");
                body(sy.body().stmts(), x);
                x.line("}");
            }
        }
    }

    private void body(List<Stmt> stmts, Ctx x) {
        x.scopes.push(new HashMap<>());
        x.depth++;
        for (int i = 0; i < stmts.size(); i++) {
            Stmt s = stmts.get(i);
            x.after.push(stmts.subList(i + 1, stmts.size()));
            try {
                stmt(s, x);
            } finally {
                x.after.pop();
            }
            if (x.unreachable) {
                // A fixed `if` returned: the rest of this block never runs in Horizon.
                x.unreachable = false;
                break;
            }
        }
        x.depth--;
        x.scopes.pop();
    }

    /** The statement always returns or throws. */
    private static boolean alwaysEnds(Stmt s) {
        if (s instanceof Block b) return !b.stmts().isEmpty() && alwaysEnds(b.stmts().get(b.stmts().size() - 1));
        return s instanceof Return || s instanceof Throw;
    }

    /** The prefix `++x`/`--x` a condition evaluates first (its leftmost operand), if it has one. */
    private static Unary firstIncrement(Expr e) {
        if (e instanceof Unary u && !u.postfix() && (u.op().equals("++") || u.op().equals("--"))) return u;
        if (e instanceof Binary b) return firstIncrement(b.l());
        return null;
    }

    private static Expr withoutFirstIncrement(Expr e) {
        if (e instanceof Unary u && !u.postfix() && (u.op().equals("++") || u.op().equals("--"))) return u.e();
        if (e instanceof Binary b) return new Binary(b.op(), withoutFirstIncrement(b.l()), b.r());
        return e;
    }

    private void loop(Stmt body, Ctx x) {
        x.loops++;
        int sw = x.switches;
        x.switches = 0;
        body(body instanceof Block b ? b.stmts() : List.of(body), x);
        x.switches = sw;
        x.loops--;
    }

    private void ifStmt(If i, Ctx x, boolean elseIf) {
        Unary first = elseIf ? null : firstIncrement(i.c());
        if (first != null) {
            // `if (++n >= 56 || ..)`: the condition does the ++ before anything else, so it's done just before the `if`.
            exprStmt(first, x);
            i = new If(withoutFirstIncrement(i.c()), i.then(), i.otherwise());
        }
        Boolean k = fold(i.c(), x);
        if (k != null) {
            // Fixed in Horizon: only the branch that runs.
            Stmt live = k ? i.then() : i.otherwise();
            x.line("// (`if` always " + k + " in Horizon: " + (k ? "the other branch is left out)" : "only the else part)"));
            if (live instanceof If e) {
                ifStmt(e, x, false);
            } else if (live != null) {
                x.line("{");
                body(live instanceof Block b ? b.stmts() : List.of(live), x);
                x.line("}");
                if (alwaysEnds(live)) x.unreachable = true;
            }
            return;
        }
        Ex c = cond(i.c(), x);
        x.line((elseIf ? "} else if " : "if ") + c.code() + " {");
        body(i.then() instanceof Block b ? b.stmts() : List.of(i.then()), x);
        if (i.otherwise() instanceof If e) {
            // An else-if: written on the same line as the closing brace.
            ifChain(e, x);
            return;
        }
        if (i.otherwise() != null) {
            x.line("} else {");
            body(i.otherwise() instanceof Block b ? b.stmts() : List.of(i.otherwise()), x);
        }
        x.line("}");
    }

    private void ifChain(If i, Ctx x) {
        Boolean k = fold(i.c(), x);
        if (k != null) {
            Stmt live = k ? i.then() : i.otherwise();
            if (!k && live instanceof If e) {
                ifChain(e, x);
                return;
            }
            if (live != null) {
                x.line("} else {");
                body(live instanceof Block b ? b.stmts() : List.of(live), x);
            }
            x.line("}");
            return;
        }
        Ex c = cond(i.c(), x);
        x.line("} else if " + c.code() + " {");
        body(i.then() instanceof Block b ? b.stmts() : List.of(i.then()), x);
        if (i.otherwise() instanceof If e) {
            ifChain(e, x);
            return;
        }
        if (i.otherwise() != null) {
            x.line("} else {");
            body(i.otherwise() instanceof Block b ? b.stmts() : List.of(i.otherwise()), x);
        }
        x.line("}");
    }

    private Ex cond(Expr e, Ctx x) {
        Ex c = unwrapOpt(ex(e, x));
        if (!c.type().isBool()) throw new JavaParser.Unsupported("a condition that isn't a boolean");
        return c;
    }

    private void local(Local l, Ctx x) {
        for (Declarator d : l.vars()) {
            if (d.init() instanceof Call nc && !mutLocal.contains(x.key() + ":" + d.name()) && alwaysNullCall(nc, x)) {
                x.constNull.add(d.name());
                x.line("// " + d.name() + ": always null in Horizon (" + nc.name() + " only returns null there).");
                continue;
            }
            if (d.init() != null && !mutLocal.contains(x.key() + ":" + d.name()) && (d.init() instanceof Name || d.init() instanceof Field)) {
                Expr init = portConstant(d.init(), x);
                if (init != null && isNull(init)) {
                    x.constNull.add(d.name());
                    x.line("// " + d.name() + ": always null in Horizon (only callbacks Horizon never calls would set it).");
                    continue;
                }
            }
            if (gameMode && absentComponent(d.init()) != null && !mutLocal.contains(x.key() + ":" + d.name())) {
                String comp = absentComponent(d.init());
                x.constNull.add(d.name());
                notes.add(x.cls.d.name + "." + (x.m == null ? "" : x.m.name) + ": the item's " + comp + " is treated as absent (Horizon doesn't keep it)");
                x.line("// " + d.name() + ": the item's " + comp + ", which Horizon doesn't keep: always absent here.");
                continue;
            }
            TypeRef tr = d.dims() == 0 ? l.type() : new TypeRef(l.type().name(), l.type().args(), l.type().dims() + d.dims());
            JT t = resolve(tr, x.cls);
            String rn = varName(d.name(), x);
            if (t.is("iter")) {
                // `Iterator it = list.iterator();`: next() moves it along, so it's always `mut`.
                if (d.init() == null) throw new JavaParser.Unsupported("an Iterator with no value");
                Ex v = ex(d.init(), x);
                if (!v.type().is("iter")) throw new JavaParser.Unsupported("an Iterator from " + v.type());
                if (mutLocal.contains(x.key() + ":" + d.name())) throw new JavaParser.Unsupported("it gives " + d.name() + " another Iterator");
                x.line("let mut " + rn + ": " + owned(v.type(), x.cls) + " = " + v.code() + ";");
                x.scopes.peek().put(d.name(), new Var(d.name(), rn, v.type(), "local", -1));
                continue;
            }
            boolean mut = mutLocal.contains(x.key() + ":" + d.name());
            if (implicitGame(t) || GAME_VALUES.contains(t.k())) {
                // `Minecraft mc = Minecraft.getInstance();`: another name for `game`.
                if (d.init() == null) throw new JavaParser.Unsupported("a Minecraft object with no value");
                Ex v = ex(d.init(), x);
                if (!v.type().k().equals(t.k())) throw new JavaParser.Unsupported("a " + v.type() + " where Minecraft's " + javaName(t) + " goes");
                if (mut) throw new JavaParser.Unsupported("it gives " + d.name() + " another Minecraft object");
                x.scopes.peek().put(d.name(), new Var(d.name(), v.code(), t, "alias", -1));
                continue;
            }
            if (absentComponent(d.init()) != null && mutLocal.stream().noneMatch(k -> k.equals(x.key() + ":" + d.name()))) {
                String comp = absentComponent(d.init());
                x.constNull.add(d.name());
                notes.add(x.cls.d.name + "." + (x.m == null ? "" : x.m.name) + ": the item's " + comp + " is treated as absent (Horizon doesn't keep it)");
                x.line("// " + d.name() + ": the item's " + comp + ", which Horizon doesn't keep: always absent here.");
                continue;
            }
            if (!isPrimType(tr) && nullable.contains(x.key() + ":" + d.name()) && !t.is("opt") && canBeNull(t)) t = JT.opt("nullable", t);
            if (t.is("opt") && d.init() != null) {
                Ex v = valueTo(d.init(), t, x);
                x.line("let " + (mut ? "mut " : "") + rn + ": " + owned(t, x.cls) + " = " + v.code() + ";");
                x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "local", -1));
                continue;
            }
            if (t.is("player") || t.is("ident")) {
                Ex v = valueTo(d.init() == null ? new Lit("null", "null") : d.init(), t, x);
                x.line("let " + (mut ? "mut " : "") + rn + ": " + owned(t, x.cls) + " = " + v.code() + ";");
                x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "local", -1));
                continue;
            }
            if (d.init() == null) {
                x.line("let " + (mut ? "mut " : "") + rn + ": " + owned(t, x.cls) + ";");
                x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "local", -1));
                continue;
            }
            if (!t.copy() && !t.is("string")) {
                Ex v = ex(d.init(), x, t);
                // A value that may be null (JsonObject.getAsJsonObject(name)) kept where the code never checks: Java
                // would throw on its first use, Rust panics here.
                if (v.type().is("opt") && !t.is("opt")) v = unwrapOpt(v);
                if (!v.owned() && "staticobj".equals(v.place()) && !mut && (t.is("array") || t.is("list") || t.is("cls")) && !v.code().contains(".with(")
                        && !writesStaticAfter(x, d.init())) {
                    // Only read here, and the static isn't changed in this method: a copy reads the same.
                    x.line("let " + rn + ": " + owned(t, x.cls) + " = " + v.code() + ".get();");
                    x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "local", -1));
                    continue;
                }
                if (!v.owned()) {
                    // A second name for an existing array or object: borrow it.
                    if (mut) throw new JavaParser.Unsupported("it changes an array or object through a second name (" + d.name() + ")");
                    String borrow = borrowOf(v);
                    x.line("let " + rn + " = " + borrow + ";");
                    x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "ref", -1));
                    continue;
                }
                checkAssignable(v.type(), t);
                x.line("let " + (mut ? "mut " : "") + rn + ": " + owned(t, x.cls) + " = " + v.code() + ";");
                x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "local", -1));
                continue;
            }
            Ex v = valueTo(d.init(), t, x);
            x.line("let " + (mut ? "mut " : "") + rn + ": " + owned(t, x.cls) + " = " + v.code() + ";");
            x.scopes.peek().put(d.name(), new Var(d.name(), rn, t, "local", -1));
        }
    }

    /** The method writes the static `init` names (directly: element or field writes, or a call on it). */
    private boolean writesStaticAfter(Ctx x, Expr init) {
        String name = init instanceof Name n ? n.id() : init instanceof Field f && f.target() instanceof Name ? f.name() : null;
        if (x.m == null || x.m.body == null || name == null) return true;
        boolean[] hit = {false};
        walk(x.m.body, node -> {
            Expr target = node instanceof Assign as ? as.target() : node instanceof Unary u && (u.op().equals("++") || u.op().equals("--")) ? u.e() : null;
            while (target instanceof Index || target instanceof Field && !(isStaticRef(target, name))) {
                target = target instanceof Index i ? i.array() : ((Field) target).target();
            }
            if (isStaticRef(target, name)) hit[0] = true;
            if (node instanceof Call c && isStaticRef(c.target(), name)) hit[0] = true;
            if (node instanceof Call c && c.target() instanceof Name an && an.id().equals("Arrays") && !c.args().isEmpty()
                    && isStaticRef(c.args().get(0), name)) hit[0] = true;
        });
        return hit[0];
    }

    /** `name` or `Cls.name`. */
    private static boolean isStaticRef(Expr e, String name) {
        return e instanceof Name n && n.id().equals(name) || e instanceof Field f && f.target() instanceof Name && f.name().equals(name);
    }

    private void forStmt(For f, Ctx x) {
        // for (int i = A; i < B; i++) where the body leaves i (and what B reads) alone: a range.
        if (f.init().size() == 1 && f.init().get(0) instanceof Local l && l.vars().size() == 1 && f.update().size() == 1
                && l.type().dims() == 0 && Set.of("int", "long").contains(l.type().name()) && f.cond() instanceof Binary cb
                && cb.l() instanceof Name cn && cn.id().equals(l.vars().get(0).name()) && l.vars().get(0).init() != null) {
            String v = cn.id();
            Expr up = f.update().get(0);
            boolean inc = up instanceof Unary u && u.op().equals("++") && u.e() instanceof Name un && un.id().equals(v)
                    || up instanceof Assign a && a.op().equals("+=") && a.target() instanceof Name an && an.id().equals(v) && a.value() instanceof Lit lit && lit.text().equals("1");
            boolean dec = up instanceof Unary u2 && u2.op().equals("--") && u2.e() instanceof Name un2 && un2.id().equals(v);
            Set<String> written = new HashSet<>();
            writes(f.body(), written);
            Set<String> read = new HashSet<>();
            names(cb.r(), read);
            boolean pure = !written.contains(v) && read.stream().noneMatch(written::contains) && !hasCalls(cb.r());
            if (pure && (inc && (cb.op().equals("<") || cb.op().equals("<=")) || dec && (cb.op().equals(">") || cb.op().equals(">=")))) {
                JT t = resolve(l.type(), x.cls);
                Ex a = valueTo(l.vars().get(0).init(), t, x);
                Ex b = numeric(ex(cb.r(), x), t);
                String rn = varName(v, x);
                String range;
                if (inc) range = paren(a.code()) + (cb.op().equals("<") ? ".." : "..=") + paren(b.code());
                else range = "(" + (cb.op().equals(">=") ? paren(b.code()) : paren(b.code()) + " + 1") + "..=" + paren(a.code()) + ").rev()";
                x.line("for " + rn + " in " + range + " {");
                x.scopes.push(new HashMap<>());
                x.scopes.peek().put(v, new Var(v, rn, t, "local", -1));
                loop(f.body(), x);
                x.scopes.pop();
                x.line("}");
                return;
            }
        }
        x.line("{");
        x.depth++;
        x.scopes.push(new HashMap<>());
        for (Stmt s : f.init()) stmt(s, x);
        String cond = f.cond() == null ? "true" : cond(f.cond(), x).code();
        if (!hasContinue(f.body())) {
            x.line(cond.equals("true") ? "loop {" : "while " + cond + " {");
            x.loops++;
            int sw = x.switches;
            x.switches = 0;
            x.scopes.push(new HashMap<>());
            x.depth++;
            for (Stmt s : f.body() instanceof Block b ? b.stmts() : List.of(f.body())) stmt(s, x);
            for (Expr u : f.update()) exprStmt(u, x);
            x.depth--;
            x.scopes.pop();
            x.switches = sw;
            x.loops--;
            x.line("}");
        } else {
            String first = "first_" + (x.tmp++);
            x.line("let mut " + first + " = true;");
            x.line("loop {");
            x.depth++;
            x.line("if !" + first + " {");
            x.depth++;
            for (Expr u : f.update()) exprStmt(u, x);
            x.depth--;
            x.line("}");
            x.line(first + " = false;");
            if (!cond.equals("true")) {
                x.line("if !(" + cond + ") {");
                x.line("    break;");
                x.line("}");
            }
            x.depth--;
            loop(f.body(), x);
            x.line("}");
        }
        x.scopes.pop();
        x.depth--;
        x.line("}");
    }

    private void forEach(ForEach f, Ctx x) {
        Ex it = unwrapOpt(ex(f.iter(), x));
        if (it.type().is("view")) {
            if ("staticobj".equals(it.place())) throw new JavaParser.Unsupported("a for-each over a map kept in a changing static");
            JT t = it.type().elem();
            String rn = varName(f.var(), x);
            if (t.copy()) x.line("for &" + rn + " in " + it.code() + " {");
            else x.line("for " + rn + " in " + it.code() + " {");
            x.scopes.push(new HashMap<>());
            x.scopes.peek().put(f.var(), new Var(f.var(), rn, t, t.copy() ? "local" : "iter", -1));
            if (mutLocal.contains(x.key() + ":" + f.var())) throw new JavaParser.Unsupported("it changes a for-each variable");
            loop(f.body(), x);
            x.scopes.pop();
            x.line("}");
            return;
        }
        if (it.type().is("json")) {
            String rn = varName(f.var(), x);
            x.line("for " + rn + " in " + paren(it.code()) + ".elements() {");
            x.scopes.push(new HashMap<>());
            x.scopes.peek().put(f.var(), new Var(f.var(), rn, JSON, "iter", -1));
            if (mutLocal.contains(x.key() + ":" + f.var())) throw new JavaParser.Unsupported("it changes a for-each variable");
            loop(f.body(), x);
            x.scopes.pop();
            x.line("}");
            return;
        }
        if (!it.type().is("array") && !it.type().is("list") && !it.type().is("set")) throw new JavaParser.Unsupported("a for-each over " + it.type());
        if (it.place() != null && it.place().equals("static")) throw new JavaParser.Unsupported("a for-each over a changing static");
        JT t = resolve(f.type(), x.cls);
        JT elem = it.type().elem();
        String rn = varName(f.var(), x);
        String src = paren(it.code()) + ".iter()";
        if (t.copy()) {
            String conv = elem != null && !elem.equals(t) ? numeric(new Ex(rn + "_v", elem), t).code() : null;
            if (conv != null) {
                x.line("for &" + rn + "_v in " + src + " {");
                x.depth++;
                x.line("let " + rn + " = " + conv + ";");
                x.depth--;
            } else x.line("for &" + rn + " in " + src + " {");
            x.scopes.push(new HashMap<>());
            x.scopes.peek().put(f.var(), new Var(f.var(), rn, t, "local", -1));
        } else {
            x.line("for " + rn + " in " + src + " {");
            x.scopes.push(new HashMap<>());
            x.scopes.peek().put(f.var(), new Var(f.var(), rn, t, "iter", -1));
        }
        if (mutLocal.contains(x.key() + ":" + f.var())) throw new JavaParser.Unsupported("it changes a for-each variable");
        loop(f.body(), x);
        x.scopes.pop();
        x.line("}");
    }

    private void tryStmt(Try t, Ctx x) {
        if (t.resources()) t = withoutResources(t);
        if (t.fin() != null) throw new JavaParser.Unsupported("a try/finally");
        // try { <one statement with one Integer.parseInt(..)> } catch (NumberFormatException e) { ... }
        boolean nfe = t.catches().size() == 1 && t.catches().get(0).types().stream()
                .allMatch(n -> Set.of("NumberFormatException", "RuntimeException", "Exception", "IllegalArgumentException").contains(n));
        if (nfe && t.body().stmts().size() == 1) {
            List<Call> parses = new ArrayList<>();
            findParses(t.body().stmts().get(0), parses);
            if (parses.size() == 1) {
                Call pc = parses.get(0);
                String what = pc.target() instanceof Name n ? n.id() : "";
                JT pt = switch (what + "." + pc.name()) {
                    case "Integer.parseInt", "Integer.valueOf" -> JT.INT;
                    case "Long.parseLong", "Long.valueOf" -> JT.LONG;
                    case "Float.parseFloat", "Float.valueOf" -> JT.FLOAT;
                    case "Double.parseDouble", "Double.valueOf" -> JT.DOUBLE;
                    default -> null;
                };
                if (pt != null && pc.args().size() == 1) {
                    Ex s = ex(pc.args().get(0), x);
                    if (!s.type().is("string")) throw new JavaParser.Unsupported("parses something that isn't a string");
                    String v = "parsed_" + (x.tmp++);
                    x.line("match " + paren(s.code()) + ".parse::<" + rustPrim(pt.name()) + ">() {");
                    x.depth++;
                    x.line("Ok(" + v + ") => {");
                    x.scopes.push(new HashMap<>());
                    x.scopes.peek().put("\u0000parse", new Var("\u0000parse", v, pt, "local", -1));
                    parseVar = v;
                    parseType = pt;
                    parseCall = pc;
                    try {
                        body(List.of(t.body().stmts().get(0)), x);
                    } finally {
                        parseCall = null;
                    }
                    x.scopes.pop();
                    x.line("}");
                    x.line("Err(_) => {");
                    body(t.catches().get(0).body().stmts(), x);
                    x.line("}");
                    x.depth--;
                    x.line("}");
                    return;
                }
            }
        }
        if (ioTry(t, x)) return;
        throw new JavaParser.Unsupported("a try/catch (" + String.join(", ", t.catches().stream().flatMap(c -> c.types().stream()).toList()) + ")");
    }

    /** Exceptions an io::Error stands for. */
    private static final Set<String> IO_ERRORS = Set.of("VersionParsingException", "IOException", "UncheckedIOException", "FileNotFoundException", "NoSuchFileException",
            "FileAlreadyExistsException", "AccessDeniedException", "DirectoryNotEmptyException", "Exception", "Throwable");
    /** Exceptions std::fs never gives: their catch blocks can't run. */
    private static final Set<String> NEVER_IO = Set.of("AtomicMoveNotSupportedException",
            // Rust threads aren't interrupted.
            "InterruptedException");
    /** Exceptions Java code throws without declaring them; Rust panics on these, so their catch can't run. */
    private static final Set<String> RUNTIME = Set.of("RuntimeException", "IllegalStateException", "IllegalArgumentException",
            "NumberFormatException", "NullPointerException", "IndexOutOfBoundsException", "ClassCastException", "UnsupportedOperationException",
            "JsonParseException", "JsonSyntaxException", "PatternSyntaxException", "ArithmeticException", "ArrayIndexOutOfBoundsException",
            "StringIndexOutOfBoundsException", "NoSuchElementException", "ConcurrentModificationException");

    private static boolean throwsIo(MethodDecl m) {
        return m.throwsTypes.stream().anyMatch(IO_ERRORS::contains);
    }

    /**
     * `try { files } catch (IOException e) { ... }`: the body as a closure returning io::Result, so `?` works in it,
     * then the catch block on Err. A `return` in the body returns from the method after the closure.
     */
    /**
     * try-with-resources over files: a reader is the file's text (read when the try starts), a writer collects text
     * that's written to the file when the try body ends, as close() would.
     */
    private Try withoutResources(Try t) {
        if (t.res().isEmpty()) throw new JavaParser.Unsupported("a try-with-resources");
        List<Stmt> pre = new ArrayList<>(), post = new ArrayList<>();
        for (Local l : t.res()) {
            Declarator d = l.vars().get(0);
            if (!(d.init() instanceof Call c && c.target() instanceof Name f && f.id().equals("Files") && !c.args().isEmpty()))
                throw new JavaParser.Unsupported("a try-with-resources over something other than a file reader or writer");
            Expr path = c.args().get(0);
            switch (c.name()) {
                case "newBufferedReader" -> pre.add(new Local(new TypeRef("String", List.of(), 0), List.of(new Declarator(d.name(), 0, new Call(f, "readString", List.of(path))))));
                case "newBufferedWriter" -> {
                    for (Expr o : c.args().subList(1, c.args().size()))
                        if (o instanceof Field of && of.name().equals("APPEND")) throw new JavaParser.Unsupported("a file writer that appends");
                    pre.add(new Local(new TypeRef("StringBuilder", List.of(), 0), List.of(new Declarator(d.name(), 0, new New(new TypeRef("StringBuilder", List.of(), 0), List.of())))));
                    post.add(new ExprStmt(new Call(f, "writeString", List.of(path, new Call(new Name(d.name()), "toString", List.of())))));
                }
                default -> throw new JavaParser.Unsupported("a try-with-resources over Files." + c.name());
            }
        }
        boolean[] returns = {false};
        walk(t.body(), n -> { if (n instanceof Return) returns[0] = true; });
        if (returns[0] && !post.isEmpty()) throw new JavaParser.Unsupported("a return inside a try-with-resources that writes a file");
        List<Stmt> body = new ArrayList<>(pre);
        body.addAll(t.body().stmts());
        body.addAll(post);
        if (t.catches().isEmpty() && t.fin() == null) throw new JavaParser.Unsupported("a try-with-resources without a catch (its errors go up)");
        return new Try(new Block(body), t.catches(), t.fin(), false, List.of());
    }

    private Ex propsCall(Ex p, Call c, Ctx x) {
        String r = paren(p.code());
        List<Ex> a = new ArrayList<>();
        for (Expr e : c.args()) a.add(unwrapOpt(ex(e, x)));
        switch (c.name() + "/" + a.size()) {
            case "getProperty/1": return new Ex(r + ".get_property(" + strArg(a.get(0)) + ")", JT.opt("nullable", JT.STRING));
            case "getProperty/2": return new Ex(r + ".get_property_or(" + strArg(a.get(0)) + ", " + strArg(a.get(1)) + ")", JT.STRING);
            case "setProperty/2", "put/2":
                markRecv(p, x);
                return new Ex(r + ".set_property(" + strArg(a.get(0)) + ", " + strArg(a.get(1)) + ")", JT.opt("nullable", JT.STRING));
            case "containsKey/1": return new Ex(r + ".contains_key(" + strArg(a.get(0)) + ")", JT.BOOL);
            case "remove/1":
                markRecv(p, x);
                return new Ex(r + ".remove(" + strArg(a.get(0)) + ")", JT.opt("nullable", JT.STRING));
            case "stringPropertyNames/0": return new Ex(r + ".names()", new JT("set", "set", JT.STRING));
            case "isEmpty/0": return new Ex(r + ".0.is_empty()", JT.BOOL);
            case "size/0": return new Ex("(" + r + ".0.len() as i32)", JT.INT);
            case "load/1":
                if (!a.get(0).type().is("string")) throw new JavaParser.Unsupported("Properties.load from a " + a.get(0).type());
                markRecv(p, x);
                return new Ex(r + ".load(" + strArg(a.get(0)) + ")", JT.VOID);
            case "store/2": {
                if (!a.get(0).type().is("string")) throw new JavaParser.Unsupported("Properties.store to a " + a.get(0).type());
                markRecv(a.get(0), x);
                String comment = isNull(c.args().get(1)) ? "None" : "Some(" + strArg(a.get(1)) + ")";
                return new Ex(paren(a.get(0).code()) + ".push_str(&" + r + ".store_text(" + comment + "))", JT.VOID);
            }
            default: throw new JavaParser.Unsupported("Properties." + c.name());
        }
    }

    private boolean ioTry(Try t, Ctx x) {
        if (t.resources() || t.fin() != null) return false;
        Catch io = null;
        for (Catch c : t.catches()) {
            if (c.types().stream().allMatch(NEVER_IO::contains)) continue;
            if (c.types().stream().anyMatch(k -> IO_ERRORS.contains(k) || RUNTIME.contains(k)) && c.types().stream().allMatch(k -> IO_ERRORS.contains(k) || RUNTIME.contains(k) || NEVER_IO.contains(k))) {
                if (io != null) return false;
                io = c;
                continue;
            }
            return false;
        }
        // Java's runtime exceptions are Rust panics: a catch of them catches the panic (Horizon unwinds).
        boolean panics = io != null && io.types().stream().anyMatch(k -> RUNTIME.contains(k) || k.equals("Exception") || k.equals("Throwable"));
        if (io == null) {
            // Only catches that can't happen: the body as it is.
            if (!x.io) return false;
            x.line("{");
            body(t.body().stmts(), x);
            x.line("}");
            return true;
        }
        Set<String> used = new HashSet<>();
        walk(io.body(), n -> { if (n instanceof Name nm) used.add(nm.id()); });
        if (io.name() != null && used.contains(io.name()) && !onlyLogged(io.body(), io.name())) throw new JavaParser.Unsupported("a catch block that uses its exception");
        if (!t.body().stmts().isEmpty() && t.body().stmts().get(0) instanceof ExprStmt es && es.e() instanceof Assign as && as.op().equals("=")
                && as.target() instanceof Name tn && x.find(tn.id()) != null && x.find(tn.id()).kind().equals("local")) {
            // `try { v = parse(..); } catch (..) { ... }`: the value comes out of the closure, so a `v` declared
            // without one is set where Rust can see it. Statements after it (`break;`) run once it's set, outside
            // the closure, so a `break` still leaves the loop.
            Var v = x.find(tn.id());
            List<Stmt> rest = t.body().stmts().subList(1, t.body().stmts().size());
            String r = "result_" + (x.tmp++);
            boolean io0 = x.io;
            String returns0 = x.returns;
            int mark = x.out.length();
            String val;
            x.io = true;
            x.returns = "ok";
            try {
                val = valueTo(as.value(), v.type(), x).code();
            } finally {
                x.io = io0;
                x.returns = returns0;
            }
            String pre = x.out.substring(mark);
            x.out.setLength(mark);
            x.line("let " + r + " = " + (panics ? "std::panic::catch_unwind(std::panic::AssertUnwindSafe(" : "(") + "|| -> std::io::Result<" + owned(v.type(), x.cls) + "> {");
            x.out.append(pre);
            x.depth++;
            x.line("Ok(" + val + ")");
            x.depth--;
            x.line(panics ? "})).unwrap_or_else(|_| Err(std::io::Error::other(\"a runtime exception\")));" : "})();");
            x.line("match " + r + " {");
            x.depth++;
            if (rest.isEmpty()) x.line("Ok(hp_ok) => " + v.rust() + " = hp_ok,");
            else {
                x.line("Ok(hp_ok) => {");
                x.depth++;
                x.line(v.rust() + " = hp_ok;");
                x.depth--;
                body(rest, x);
                x.line("}");
            }
            x.line("Err(_) => {");
            body(io.body().stmts(), x);
            x.line("}");
            x.depth--;
            x.line("}");
            return true;
        }
        String rt = x.ret == null || x.ret.is("void") ? "()" : owned(x.ret, x.cls);
        String r = "result_" + (x.tmp++);
        boolean io0 = x.io, ret0 = x.tryReturned;
        String returns0 = x.returns;
        int loops = x.loops, sw = x.switches;
        x.io = true;
        x.returns = "some";
        x.tryReturned = false;
        x.loops = 0;
        x.switches = 0;
        boolean returned;
        try {
            x.line("let " + r + " = " + (panics ? "std::panic::catch_unwind(std::panic::AssertUnwindSafe(" : "(") + "|| -> std::io::Result<Option<" + rt + ">> {");
            body(t.body().stmts(), x);
            x.depth++;
            x.line("Ok(None)");
            x.depth--;
            x.line(panics ? "})).unwrap_or_else(|_| Err(std::io::Error::other(\"a runtime exception\")));" : "})();");
            returned = x.tryReturned;
        } finally {
            x.io = io0;
            x.returns = returns0;
            x.tryReturned = ret0;
            x.loops = loops;
            x.switches = sw;
        }
        x.line("match " + r + " {");
        x.depth++;
        if (returned) {
            String back = switch (x.returns) {
                case "ok" -> "return Ok(v)";
                case "some" -> "{ " + markReturned(x) + "return Ok(Some(v)) }";
                default -> rt.equals("()") ? "return" : "return v";
            };
            x.line("Ok(Some(v)) => " + back + ",");
            x.line("Ok(None) => {}");
        } else {
            x.line("Ok(_) => {}");
        }
        x.line("Err(_) => {");
        body(io.body().stmts(), x);
        x.line("}");
        x.depth--;
        x.line("}");
        return true;
    }

    private String markReturned(Ctx x) {
        x.tryReturned = true;
        return "";
    }

    /** The exception only goes to a log call (which becomes a comment). */
    private static boolean onlyLogged(Block b, String name) {
        boolean[] other = {false};
        walk(b, n -> {
            if (n instanceof Call c && !Set.of("info", "warn", "error", "debug", "trace", "printStackTrace").contains(c.name())) {
                for (Expr a : c.args()) if (a instanceof Name an && an.id().equals(name)) other[0] = true;
            }
            if (n instanceof Assign a && a.value() instanceof Name an && an.id().equals(name)) other[0] = true;
            if (n instanceof Throw th) other[0] = true;
        });
        return !other[0];
    }

    private Call parseCall;
    private String parseVar;
    private JT parseType;

    private void findParses(Object node, List<Call> out) {
        walk(node, n -> {
            if (n instanceof Call c && c.target() instanceof Name t && Set.of("Integer", "Long", "Float", "Double").contains(t.id())
                    && (c.name().startsWith("parse") || c.name().equals("valueOf"))) out.add(c);
        });
    }

    private void switchStmt(Switch sw, Ctx x) {
        Ex sel = ex(sw.sel(), x);
        String head;
        if (sel.type().is("string")) head = "match &*" + paren(sel.code()) + " {";
        else if (sel.type().is("enum") || sel.type().numeric() || sel.type().is("useanim")) head = "match " + sel.code() + " {";
        else throw new JavaParser.Unsupported("a switch on " + sel.type());
        x.line(head);
        x.depth++;
        List<Expr> pending = new ArrayList<>();
        boolean hasDefault = false;
        for (int i = 0; i < sw.cases().size(); i++) {
            Case k = sw.cases().get(i);
            if (k.labels().isEmpty()) hasDefault = true;
            if (!k.arrow() && k.body().isEmpty() && i + 1 < sw.cases().size()) {
                if (k.labels().isEmpty()) throw new JavaParser.Unsupported("a default case that falls through");
                pending.addAll(k.labels());
                continue;
            }
            List<Expr> labels = new ArrayList<>(pending);
            labels.addAll(k.labels());
            pending.clear();
            List<String> pats = new ArrayList<>();
            for (Expr l : labels) pats.add(pattern(l, sel.type(), x));
            String pat = k.labels().isEmpty() ? "_" : String.join(" | ", pats);
            List<Stmt> body = new ArrayList<>(k.body());
            if (!k.arrow()) {
                if (!body.isEmpty() && body.get(body.size() - 1) instanceof Break br && br.label() == null) body.remove(body.size() - 1);
                else if (!body.isEmpty() && !ends(body.get(body.size() - 1)) && i + 1 < sw.cases().size())
                    throw new JavaParser.Unsupported("a switch case that falls through");
            } else if (body.size() == 1 && body.get(0) instanceof Block b) {
                body = new ArrayList<>(b.stmts());
            }
            x.line(pat + " => {");
            x.switches++;
            int loops = x.loops;
            x.loops = 0;
            body(body, x);
            x.loops = loops;
            x.switches--;
            x.line("}");
        }
        if (!hasDefault) x.line("_ => {}");
        x.depth--;
        x.line("}");
    }

    private static boolean ends(Stmt s) {
        return s instanceof Return || s instanceof Throw || s instanceof Continue || s instanceof Break;
    }

    private String pattern(Expr l, JT selType, Ctx x) {
        if (selType.is("useanim")) {
            String n = l instanceof Name nm ? nm.id() : l instanceof Field f ? f.name() : null;
            if (n != null && n.matches("[A-Z_]+")) return "\"" + n + "\"";
        }
        if (selType.is("enum") && l instanceof Name n) {
            Cls e = classes.get(selType.name());
            if (e != null && e.d.constants.stream().anyMatch(k -> k.name().equals(n.id())))
                return e.module + "::" + e.rust + "::" + camel(n.id());
        }
        Ex v = ex(l, x);
        if (selType.is("string")) {
            if (l instanceof Lit) return v.code();
            throw new JavaParser.Unsupported("a switch case that isn't a literal");
        }
        if (l instanceof Lit) return numeric(v, selType).code();
        if (v.code().matches("[\\w:]+")) return v.code();
        throw new JavaParser.Unsupported("a switch case that isn't a constant");
    }

    // ---- expression statements ----

    private void exprStmt(Expr e, Ctx x) {
        if (logCall(e, x)) return;
        if (e instanceof Assign a) {
            if (a.value() instanceof Assign inner && a.op().equals("=") && inner.op().equals("=")) {
                // a = b = v: v once, then each.
                List<Expr> targets = new ArrayList<>();
                Expr v = a;
                while (v instanceof Assign as && as.op().equals("=")) {
                    targets.add(as.target());
                    v = as.value();
                }
                String t = "chain_" + (x.tmp++);
                JT tt = ex(targets.get(targets.size() - 1), x).type();
                if (!tt.copy()) throw new JavaParser.Unsupported("a chained assignment of objects");
                x.line("let " + t + " = " + valueTo(v, tt, x).code() + ";");
                for (int i = targets.size() - 1; i >= 0; i--) assign(targets.get(i), "=", new Ex(t, tt), x);
                return;
            }
            if (a.op().equals("=")) {
                Ex target = ex(a.target(), x);
                Expr outer = x.assignTarget;
                x.assignTarget = a.target();
                Ex value;
                try {
                    value = valueTo(a.value(), target.type(), x);
                } finally {
                    x.assignTarget = outer;
                }
                assign(a.target(), "=", value, x);
            } else {
                assign(a.target(), a.op(), ex(a.value(), x), x);
            }
            return;
        }
        if (e instanceof Unary u && (u.op().equals("++") || u.op().equals("--"))) {
            assign(u.e(), u.op().equals("++") ? "+=" : "-=", new Ex("1", JT.INT, true, "lit"), x);
            return;
        }
        if (e instanceof Call ac && ac.name().equals("append")) {
            // sb.append(a).append(b): one push_str each, on sb.
            List<Expr> parts = new ArrayList<>();
            Expr t = ac;
            while (t instanceof Call cc && cc.name().equals("append") && cc.args().size() == 1) {
                parts.add(0, cc.args().get(0));
                t = cc.target();
            }
            Ex sb = t == null ? null : ex(t, x);
            if (sb != null && sb.type().is("string")) {
                markRecv(sb, x);
                for (Expr part : parts) {
                    Ex v = stringOf(ex(part, x));
                    x.line(paren(sb.code()) + ".push_str(" + strArg(v) + ");");
                }
                return;
            }
        }
        if (e instanceof Call || e instanceof New) {
            Ex c = ex(e, x);
            x.line(c.code() + ";");
            return;
        }
        throw new JavaParser.Unsupported("an expression statement that does nothing Rust allows (" + e.getClass().getSimpleName() + ")");
    }

    /** `LOG.info(...)`, `System.out.println(...)`, `e.printStackTrace()`: kept as comments. */
    private boolean logCall(Expr e, Ctx x) {
        if (!(e instanceof Call c)) return false;
        boolean log = false;
        if (Set.of("info", "warn", "error", "debug", "trace").contains(c.name())) {
            if (c.target() instanceof Name n && loggerField(n.id(), x.cls) != null) log = true;
            if (c.target() instanceof Field f && f.target() instanceof Name cn && classes.containsKey(cn.id())
                    && loggerField(f.name(), classes.get(cn.id())) != null) log = true;
        }
        if (c.target() instanceof Field f && f.target() instanceof Name sys && sys.id().equals("System")
                && (f.name().equals("out") || f.name().equals("err"))) log = true;
        if (c.name().equals("printStackTrace") && c.args().isEmpty()) log = true;
        if (!log) return false;
        String first = !c.args().isEmpty() && c.args().get(0) instanceof Lit l && l.kind().equals("string") ? " " + l.text() : "";
        x.line("// log:" + first);
        return true;
    }

    private FieldDecl loggerField(String name, Cls c) {
        for (Cls k = c; k != null; k = k.outer) {
            FieldDecl f = k.fields.get(name);
            if (f != null && f.mods.contains("static") && isLogger(f)) return f;
        }
        return null;
    }

    private void assign(Expr target, String op, Ex value, Ctx x) {
        Ex t = ex(target, x);
        JT tt = t.type();
        String bin = op.equals("=") ? null : op.substring(0, op.length() - 1);
        // The value, converted as Java would (compound assignments cast back to the target's type).
        String v;
        if (bin == null) {
            v = value.code();
        } else if (tt.is("string") && bin.equals("+")) {
            v = null;
        } else {
            if (!tt.numeric() && !(tt.isBool() && Set.of("&", "|", "^").contains(bin))) throw new JavaParser.Unsupported("a compound assignment on " + tt);
            v = null;
        }
        markWrite(target, x);
        String place = t.place();
        if ("static".equals(place)) {
            String cur = t.code();
            String setter = staticSetter(target, x);
            String nv = bin == null ? v : compound(new Ex(cur, tt), bin, value, tt);
            x.line(setter.replace("$V", nv) + ";");
            return;
        }
        if ("staticobj".equals(place)) {
            String nv = bin == null ? v : compound(t, bin, value, tt);
            String tv = "value_" + (x.tmp++);
            x.line("let " + tv + " = " + nv + ";");
            if (!t.code().contains(".with(|o| o")) {
                if (!(tt.copy() || tt.is("string") || value.owned())) throw new JavaParser.Unsupported("it stores a second reference in a changing static");
                x.line(t.code() + ".set(" + tv + ");");
                return;
            }
            x.line(staticObjSetter(target, tv, x) + ";");
            return;
        }
        if ("alias".equals(place)) throw new JavaParser.Unsupported("it gives a Minecraft object a new value");
        if (place == null || place.equals("lit")) throw new JavaParser.Unsupported("an assignment to something that isn't a variable");
        if (place.equals("iter")) throw new JavaParser.Unsupported("it changes a for-each variable");
        if (place.equals("ref") && target instanceof Index && t.code().indexOf(".as_ref().expect(") > 0
                && t.code().indexOf(".as_ref().expect(") == t.code().lastIndexOf(".as_ref().expect(")) {
            // An element of an array a nullable field or local holds: changed in place, through as_mut.
            int at = t.code().indexOf(".as_ref().expect(");
            t = new Ex(t.code().substring(0, at) + ".as_mut().expect(" + t.code().substring(at + ".as_ref().expect(".length()), tt, t.owned(), "field");
        } else if (place.equals("ref") && !(target instanceof Index)) {
            // A field of an object a nullable local holds: changed in place, through as_mut.
            int at = t.code().indexOf(".as_ref().expect(");
            if (!(target instanceof Field) || at < 0) throw new JavaParser.Unsupported("it points a reference at another object");
            t = new Ex(t.code().substring(0, at) + ".as_mut().expect(" + t.code().substring(at + ".as_ref().expect(".length()), tt, t.owned(), "field");
        }
        if (bin == null) {
            if (!tt.copy() && !tt.is("string") && !value.owned()) throw new JavaParser.Unsupported("it stores a second reference to an array or object");
            x.line(t.code() + " = " + v + ";");
        } else if (tt.is("string")) {
            x.line(t.code() + ".push_str(" + strArg(stringOf(value)) + ");");
        } else {
            JT pt = tt.isBool() ? tt : promote(tt, value.type());
            if (pt.equals(tt)) x.line(t.code() + " " + bin + "= " + numeric(value, tt).code() + ";");
            else x.line(t.code() + " = " + compound(t, bin, value, tt) + ";");
        }
    }

    private String compound(Ex cur, String bin, Ex value, JT tt) {
        if (tt.is("string")) return "format!(\"{}{}\", " + cur.code() + ", " + fmtArg(value) + ")";
        JT pt = tt.isBool() ? tt : promote(tt, value.type());
        String r = paren(numeric(cur, pt).code()) + " " + bin + " " + paren(numeric(value, pt).code());
        return pt.equals(tt) ? r : numeric(new Ex("(" + r + ")", pt), tt).code();
    }

    /** Records that the root of `target` is written, so its binding becomes `mut`. */
    private void markWrite(Expr target, Ctx x) {
        Expr root = target;
        boolean whole = true;
        while (true) {
            if (root instanceof Index i) { root = i.array(); whole = false; }
            else if (root instanceof Field f && !(f.target() instanceof This) && !(f.target() instanceof Name n && isClassName(n.id(), x))) { root = f.target(); whole = false; }
            else break;
        }
        if (root instanceof Field f && f.target() instanceof This) {
            if (!x.isStatic && !x.ctor) grow(mutSelf, x.key());
            return;
        }
        if (root instanceof Field f && f.target() instanceof Name n && isClassName(n.id(), x)) {
            Cls c = ownClass(n.id(), x.cls);
            if (c != null) grow(mutStatic, fieldKey(c, f.name()));
            return;
        }
        if (root instanceof Name n) {
            Var v = x.find(n.id());
            if (v != null) {
                if (v.kind().equals("param") && !whole) grow(mutParam, x.key() + ":" + v.index());
                else grow(mutLocal, x.key() + ":" + n.id());
                return;
            }
            Cls owner = fieldOwner(n.id(), x.cls, false);
            if (owner != null && !x.isStatic && !x.ctor) { grow(mutSelf, x.key()); return; }
            Cls st = fieldOwner(n.id(), x.cls, true);
            if (st != null) grow(mutStatic, fieldKey(st, n.id()));
        }
    }

    private void grow(Set<String> s, String k) {
        if (s.add(k)) changed = true;
    }

    // ---- expressions ----

    private Ex valueTo(Expr e, JT t, Ctx x) {
        if (t.is("opt")) {
            if (isNull(e)) return new Ex("None", t);
            Ex v = ex(e, x, t);
            if (v.type().is("opt")) {
                if (v.type().elem() != null && t.elem() != null && !v.type().elem().equals(t.elem()))
                    checkAssignable(v.type().elem(), t.elem());
                if ("staticobj".equals(v.place())) return new Ex(withRead(v, ".clone()"), t);
                // A map read (`get`) is an Option<&T>: `cloned` gives the Option<T>.
                if (!v.owned() && ("ref".equals(v.place()) || "optref".equals(v.place()))) return new Ex(paren(v.code()) + ".cloned()", t);
                return v.owned() ? new Ex(v.code(), t) : new Ex(paren(v.code()) + ".clone()", t);
            }
            Ex inner = valueTo(e, t.elem(), x);
            return new Ex("Some(" + inner.code() + ")", t);
        }
        Ex v = staticValue(ex(e, x, t));
        if (v.type().is("opt")) v = unwrapOpt(v);
        if (t.copy()) {
            if (t.is("enum")) {
                if (!v.type().equals(t)) throw new JavaParser.Unsupported("a " + v.type() + " where a " + t + " goes");
                return v;
            }
            if (t.isBool() != v.type().isBool() || !v.type().copy()) throw new JavaParser.Unsupported("a " + v.type() + " where a " + t + " goes");
            return numeric(v, t);
        }
        if (t.is("string") && v.type().is("comp")) return new Ex(paren(v.code()) + ".plain()", JT.STRING);
        if (t.is("string")) {
            if (!v.type().is("string")) throw new JavaParser.Unsupported("a " + v.type() + " where a String goes");
            return v.owned() ? v : new Ex(paren(v.code()) + ".to_string()", JT.STRING);
        }
        if (t.is("comp") && v.type().is("string")) return new Ex("horizon_client::text::Text::literal(" + (v.owned() ? v.code() : paren(v.code()) + ".to_string()") + ")", COMP);
        checkAssignable(v.type(), t);
        if (!v.owned() && t.is("path")) return new Ex(paren(v.code()) + ".to_path_buf()", t);
        if (!v.owned() && (t.is("player") || t.is("ident") || t.is("stack") || t.is("item") || t.is("json") || t.is("comp")
                || t.is("version") || t.is("modc") || t.is("moddep") || t.is("future"))) return new Ex(paren(v.code()) + ".clone()", t);
        if (!v.owned()) {
            if (t.is("array") || t.is("list")) return new Ex(paren(v.code()) + ".to_vec()", t);
            if (t.is("map") || t.is("set")) return new Ex(paren(v.code()) + ".clone()", t);
            if (t.is("cls") && immutable(classes.get(t.name()))) return new Ex(paren(v.code()) + ".clone()", t);
            // A local's object, and the local is done with: nothing is left to share it with.
            if (lastUse(e, x)) return new Ex(paren(v.code()) + ".clone()", t);
            throw new JavaParser.Unsupported("it stores a second reference to an object (Rust would need it copied or shared)");
        }
        return v;
    }

    /** Item data components Horizon keeps only as raw NBT: reading them gives nothing in the port. */
    private static final Map<String, String> ABSENT_COMPONENTS = Map.of("LORE", "lore", "CUSTOM_DATA", "custom data",
            "CUSTOM_MODEL_DATA", "custom model data", "ITEM_NAME", "item name");

    /** `(ItemLore) stack.get(DataComponents.LORE)` and the like: the component's name, else null. */
    private static String absentComponent(Expr init) {
        Expr e = init;
        while (e instanceof Cast c) e = c.e();
        if (e instanceof Call c && c.name().equals("get") && c.args().size() == 1 && c.args().get(0) instanceof Field f
                && f.target() instanceof Name n && n.id().equals("DataComponents")) return ABSENT_COMPONENTS.get(f.name());
        return null;
    }

    /** Every constructor sets `this.name` / `name` (at its top level). */
    private static boolean setByEveryConstructor(Cls c, String name) {
        List<MethodDecl> ctors = c.d.methods.stream().filter(m -> m.name.equals("<init>")).toList();
        if (ctors.isEmpty()) return false;
        for (MethodDecl m : ctors) {
            if (m.body == null) return false;
            boolean set = false;
            for (Stmt s : m.body.stmts()) {
                if (s instanceof ExprStmt es && es.e() instanceof Assign a && a.op().equals("=")
                        && (a.target() instanceof Name n && n.id().equals(name) || a.target() instanceof Field f && f.target() instanceof This && f.name().equals(name))) set = true;
            }
            if (!set) return false;
        }
        return true;
    }

    // ---- facts fixed in the port: conditions that are always true or false in Horizon ----

    /** Methods Horizon never calls: callbacks of classes that implement or extend Minecraft or Fabric types (not the initializer). */
    private boolean deadInPort(Cls c, MethodDecl m) {
        if (m.isStatic() || c.failed == null || m.name.equals("onInitialize") || m.name.equals("onInitializeClient")) return false;
        return c.failed.matches("it (implements|extends) (Minecraft's|Fabric's) .*");
    }

    private static final Set<String> MUTATORS = Set.of("add", "addAll", "put", "putAll", "remove", "clear", "set", "sort", "merge",
            "computeIfAbsent", "putIfAbsent", "removeIf", "replaceAll", "fill", "push", "pop", "offer", "poll");

    /** Who writes each static: assignments, ++/--, and changing calls on it. */
    private void findStaticWriters() {
        for (Cls c : all) {
            for (MethodDecl m : c.d.methods) {
                if (m.body == null) continue;
                String who = deadInPort(c, m) ? "dead" : "live";
                walkLive(m.body, n -> {
                    Expr target = n instanceof Assign a ? a.target() : n instanceof Unary u && (u.op().equals("++") || u.op().equals("--")) ? u.e()
                            : n instanceof Call call && MUTATORS.contains(call.name()) ? call.target() : null;
                    while (target instanceof Index i) target = i.array();
                    String k = staticKeyOf(target, c);
                    // Setting a static that starts as null back to null leaves it as it was.
                    boolean nullAgain = n instanceof Assign as && as.op().equals("=") && isNull(as.value()) && k != null && startsNull(k);
                    if (k != null && !nullAgain) staticWriters.computeIfAbsent(k, z -> new ArrayList<>()).add(who);
                    if (n instanceof Call call && call.target() instanceof Name an && an.id().equals("Arrays") && !call.args().isEmpty()) {
                        String k2 = staticKeyOf(call.args().get(0), c);
                        if (k2 != null) staticWriters.computeIfAbsent(k2, z -> new ArrayList<>()).add(who);
                    }
                });
            }
        }
    }

    private boolean startsNull(String fieldKey) {
        for (Cls c : all) for (FieldDecl f : c.d.fields)
            if (f.mods.contains("static") && fieldKey(c, f.name).equals(fieldKey)) return f.init == null && !isPrimType(f.type) || isNull(f.init);
        return false;
    }

    /** walk, leaving out the branches of `if (FabricLoader.getInstance().isModLoaded(..))` that can't run in Horizon. */
    private static void walkLive(Object node, Visitor v) {
        if (node instanceof If i && modLoadedCheck(i.c()) != null) {
            boolean value = modLoadedCheck(i.c());
            walkLive(value ? i.then() : i.otherwise(), v);
            return;
        }
        if (node instanceof Block b) {
            v.visit(b);
            for (Stmt s : b.stmts()) walkLive(s, v);
            return;
        }
        if (node instanceof If i) {
            v.visit(i);
            walk(i.c(), v);
            walkLive(i.then(), v);
            walkLive(i.otherwise(), v);
            return;
        }
        walk(node, v);
    }

    /** `isModLoaded(..)` is false in Horizon, `!isModLoaded(..)` true; null for anything else. */
    private static Boolean modLoadedCheck(Expr c) {
        if (c instanceof Unary u && u.op().equals("!")) {
            Boolean b = modLoadedCheck(u.e());
            return b == null ? null : !b;
        }
        if (c instanceof Call call && call.name().equals("isModLoaded") && call.target() instanceof Call g && g.name().equals("getInstance")
                && g.target() instanceof Name fl && fl.id().equals("FabricLoader")) return Boolean.FALSE;
        return null;
    }

    private String staticKeyOf(Expr e, Cls c) {
        if (e instanceof Name n) {
            Cls o = fieldOwner(n.id(), c, true);
            return o == null ? null : fieldKey(o, n.id());
        }
        if (e instanceof Field f && f.target() instanceof Name cn) {
            Cls o = ownClass(cn.id(), c);
            if (o != null && o.fields.containsKey(f.name()) && o.fields.get(f.name()).mods.contains("static")) return fieldKey(o, f.name());
        }
        return null;
    }

    /** A static only Minecraft callbacks Horizon never runs would change: its first value, else null. */
    private Expr portConstant(Expr e, Ctx x) {
        String k = staticKeyOf(e, x.cls);
        if (k == null) return null;
        List<String> w = staticWriters.getOrDefault(k, List.of());
        if (w.contains("live")) return null;
        Cls o = e instanceof Name n ? fieldOwner(n.id(), x.cls, true) : ownClass(((Name) ((Field) e).target()).id(), x.cls);
        FieldDecl f = o.fields.get(e instanceof Name n2 ? n2.id() : ((Field) e).name());
        return f.init == null ? new Lit("null", "null") : f.init;
    }

    private static boolean emptyCollection(Expr init) {
        if (init instanceof Call c && c.args().isEmpty() && c.target() instanceof Name n
                && (Set.of("List", "Set", "Map").contains(n.id()) && c.name().equals("of") || n.id().equals("Collections") && c.name().startsWith("empty"))) return true;
        return init instanceof New nw && nw.args().isEmpty() && Set.of("ArrayList", "HashMap", "HashSet", "LinkedHashMap", "TreeMap", "LinkedList").contains(nw.type().simple());
    }

    /** A call to one of the mod's methods that, in Horizon, only returns null (and does nothing else on the way). */
    private boolean alwaysNullCall(Call call, Ctx x) {
        Cls o = call.target() == null || call.target() instanceof This ? x.cls
                : call.target() instanceof Name cn && x.find(cn.id()) == null ? ownClass(cn.id(), x.cls) : null;
        if (o == null) return false;
        for (MethodDecl md : o.methods.getOrDefault(call.name(), List.of())) {
            if (md.params.size() != call.args().size() || md.body == null) continue;
            if (!pureArgs(call)) return false;
            Ctx mx = new Ctx(o, md, md.isStatic());
            return onlyReturnsNull(md.body.stmts(), mx);
        }
        return false;
    }

    private static boolean pureArgs(Call call) {
        for (Expr a : call.args()) if (!pure(a)) return false;
        return true;
    }

    /** The live path (fixed `if`s followed) is just `return null`. */
    private boolean onlyReturnsNull(List<Stmt> stmts, Ctx x) {
        for (Stmt s : stmts) {
            if (s instanceof Return r) return r.e() != null && isNull(r.e());
            if (s instanceof If i) {
                Boolean k = fold(i.c(), x);
                if (k == null) return false;
                Stmt live = k ? i.then() : i.otherwise();
                if (live == null) continue;
                List<Stmt> ls = live instanceof Block b ? b.stmts() : List.of(live);
                if (alwaysEnds(live)) return onlyReturnsNull(ls, x);
                if (!onlyReturnsNull(ls, x)) return false;
                continue;
            }
            return false;
        }
        return false;
    }

    /** Calls without side effects, safe to leave out of a folded condition. */
    private static final Set<String> PURE_CALLS = Set.of("isEmpty", "size", "length", "getCount", "isPresent", "equals", "contains", "containsKey",
            "is", "has", "getItem", "isUsingItem", "isShiftKeyDown", "isCrouching", "isSprinting");

    private static boolean pure(Expr e) {
        boolean[] ok = {true};
        walk(e, n -> {
            if (n instanceof Assign || n instanceof Unary u && (u.op().equals("++") || u.op().equals("--")) || n instanceof New) ok[0] = false;
            if (n instanceof Call c && !PURE_CALLS.contains(c.name())) ok[0] = false;
        });
        return ok[0];
    }

    /** A condition's value when it's fixed in Horizon: literals, isModLoaded, statics only dead callbacks write, absent components. */
    private Boolean fold(Expr e, Ctx x) {
        switch (e) {
            case Lit l when l.kind().equals("boolean"):
                return null;
            case Unary u when u.op().equals("!"): {
                Boolean v = fold(u.e(), x);
                return v == null ? null : !v;
            }
            case Binary b when b.op().equals("&&") || b.op().equals("||"): {
                boolean and = b.op().equals("&&");
                Boolean l = fold(b.l(), x), r = fold(b.r(), x);
                if (l != null && l != and) return l;
                if (l != null) return r;
                if (r != null && r != and && pure(b.l())) return r;
                return null;
            }
            case Binary b when (b.op().equals("==") || b.op().equals("!=")) && (isNull(b.l()) || isNull(b.r())): {
                Expr o = isNull(b.l()) ? b.r() : b.l();
                boolean isNullNow;
                if (o instanceof Name n && x.constNull.contains(n.id()) && x.find(n.id()) == null) isNullNow = true;
                else {
                    Expr init = portConstant(o, x);
                    if (init == null) return null;
                    if (isNull(init)) isNullNow = true;
                    else if (init instanceof New || init instanceof Lit) isNullNow = false;
                    else return null;
                }
                return b.op().equals("==") == isNullNow;
            }
            case Call c when c.name().equals("isEmpty") && c.args().isEmpty() && c.target() != null: {
                Expr init = portConstant(c.target(), x);
                return init != null && emptyCollection(init) ? Boolean.TRUE : null;
            }
            case Binary b when (b.op().equals("==") || b.op().equals("!=")) && (isNull(b.l()) || isNull(b.r()))
                    && (isNull(b.l()) ? b.r() : b.l()) instanceof Call nc && alwaysNullCall(nc, x):
                return b.op().equals("==");
            case Call c when c.name().equals("isModLoaded") && c.args().size() == 1 && c.target() instanceof Call g && g.name().equals("getInstance")
                    && g.target() instanceof Name fl && fl.id().equals("FabricLoader"):
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * Objects Java code treats as values: they don't change after they're made (numbers, strings, identifiers,
     * records and classes with final fields), or collections of them. Reading one out of a shared map as a copy
     * behaves the same as Java's shared reference, unless something changes the collection afterwards, which
     * markRecv refuses ("copy").
     */
    private boolean valueLike(JT t) {
        if (t == null) return false;
        // A future is a shared handle: its copies are the same future.
        if (t.copy() || t.is("string") || t.is("ident") || t.is("path") || t.is("json") || t.is("version") || t.is("modc") || t.is("moddep") || t.is("future")
                || t.is("comp")) return true;
        if (t.is("cls")) return immutable(classes.get(t.name()));
        if (t.is("opt")) return valueLike(t.elem());
        if (t.is("list") || t.is("array") || t.is("set")) return valueLike(t.elem());
        if (t.is("map")) return valueLike(t.elem()) && valueLike(t.key());
        return false;
    }

    /** `e` is a local variable that nothing after this statement uses (and not in a loop, which could come back to it). */
    private boolean lastUse(Expr e, Ctx x) {
        while (e instanceof Cast c) e = c.e();
        if (!(e instanceof Name n) || x.loops > 0) return false;
        Var v = x.find(n.id());
        if (v == null || !v.kind().equals("local")) return false;
        // Later uses that only store it in the same place again (`INSTANCE = read;` twice) share nothing new.
        int[] uses = {0, 0};
        Expr same = x.assignTarget;
        for (List<Stmt> rest : x.after)
            for (Stmt st : rest)
                walk(st, node -> {
                    if (node instanceof Name nm && nm.id().equals(n.id())) uses[0]++;
                    if (same != null && node instanceof Assign as && as.op().equals("=") && as.target().equals(same)
                            && (as.value() instanceof Name vn && vn.id().equals(n.id()) || as.value() instanceof Cast vc && vc.e() instanceof Name cn && cn.id().equals(n.id())))
                        uses[1]++;
                });
        return uses[0] == uses[1];
    }

    /** No method changes it and its fields are final: a copy behaves as Java's shared object does. */
    /** Values Java's equals compares by content, so Rust's == can: records of such values and collections of them. */
    private boolean eqable(JT t) {
        return eqable(t, new HashSet<>());
    }

    private boolean eqable(JT t, Set<String> seen) {
        if (t == null) return false;
        if (t.is("prim") || t.is("enum") || t.is("string") || t.is("ident") || t.is("json")) return true;
        if (t.is("opt") || t.is("list") || t.is("array") || t.is("set")) return eqable(t.elem(), seen);
        if (t.is("map")) return eqable(t.elem(), seen) && eqable(t.key(), seen);
        if (t.is("cls")) {
            Cls c = classes.get(t.name());
            if (c == null || !c.d.kind.equals("record") || c.failed != null) return false;
            if (!seen.add(c.d.qualified)) return true;
            for (FieldDecl f : c.d.fields) {
                if (f.mods.contains("static")) continue;
                try {
                    if (!eqable(fieldType(c, f), seen)) return false;
                } catch (JavaParser.Unsupported u) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean immutable(Cls c) {
        if (c == null || c.failed != null) return false;
        for (FieldDecl f : c.d.fields) if (!f.mods.contains("static") && !f.mods.contains("final")) return false;
        for (MethodDecl m : c.d.methods) if (!m.name.equals("<init>") && mutSelf.contains(methodKey(c, m))) return false;
        return true;
    }

    private boolean compatible(JT from, JT to) {
        try {
            checkAssignable(from, to);
            return true;
        } catch (JavaParser.Unsupported u) {
            return false;
        }
    }

    private void checkAssignable(JT from, JT to) {
        if (from.equals(to)) return;
        if ((from.is("map") && to.is("map") || from.is("set") && to.is("set") || from.is("opt") && to.is("opt"))
                && (from.elem() == null || to.elem() == null || from.elem().equals(to.elem()))
                && (from.key() == null || to.key() == null || from.key().equals(to.key()))
                && (from.is("opt") || from.name().equals(to.name()))) return;
        if ((from.is("array") || from.is("list")) && (to.is("array") || to.is("list")) && (from.elem() == null || to.elem() == null || from.elem().equals(to.elem()))) return;
        throw new JavaParser.Unsupported("a " + from + " where a " + to + " goes");
    }

    private Ex ex(Expr e, Ctx x) {
        return ex(e, x, null);
    }

    /** `want` is the type the value goes into, if known (for array initializers and `new ArrayList<>()`). */
    private Ex ex(Expr e, Ctx x, JT want) {
        if (parseCall != null && e == parseCall) return new Ex(parseVar, parseType);
        switch (e) {
            case Lit l:
                if (l.kind().equals("null") && want != null && want.is("opt")) return new Ex("None", want);
                return literal(l);
            case Name n:
                return name(n.id(), x);
            case This t:
                throw new JavaParser.Unsupported("`this` used as a value");
            case Field f:
                return field(f, x);
            case Index i: {
                Ex a = unwrapOpt(ex(i.array(), x));
                if (!a.type().is("array") && !a.type().is("list")) throw new JavaParser.Unsupported("indexing a " + a.type());
                String idx = usize(ex(i.index(), x));
                if ("static".equals(a.place()) || "staticobj".equals(a.place())) {
                    if (!a.type().elem().copy()) throw new JavaParser.Unsupported("an element of a changing static array that isn't a number");
                    if (hasCalls(i.index())) throw new JavaParser.Unsupported("a call inside a changing static's index");
                    return new Ex(withRead(a, "[" + idx + "]"), a.type().elem(), true, "staticobj");
                }
                return new Ex(paren(a.code()) + "[" + idx + "]", a.type().elem(), false, placeOf(a));
            }
            case Call c:
                return call(c, x);
            case New n:
                return newObj(n, x, want);
            case NewArray na:
                return newArray(na, x);
            case ArrayInit ai: {
                if (want == null || !want.is("array")) throw new JavaParser.Unsupported("an array initializer without a type");
                return arrayInit(ai, want, x);
            }
            case Unary u:
                return unary(u, x);
            case Binary b:
                return binary(b, x);
            case Assign a:
                throw new JavaParser.Unsupported("an assignment inside an expression");
            case Cond c: {
                Boolean k = fold(c.c(), x);
                if (k != null) return ex(k ? c.a() : c.b(), x, want);
                Ex cc = cond(c.c(), x);
                if (want != null && want.is("opt") && (isNull(c.a()) || isNull(c.b()))) {
                    Ex na = valueTo(c.a(), want, x), nb = valueTo(c.b(), want, x);
                    return new Ex("if " + cc.code() + " { " + na.code() + " } else { " + nb.code() + " }", want);
                }
                Ex a = isNull(c.a()) ? null : ex(c.a(), x, want), b = isNull(c.b()) ? null : ex(c.b(), x, want);
                if (a == null && b == null) throw new JavaParser.Unsupported("`null` (Rust has no null: this needs an Option)");
                if ((a == null ? b : a).type().is("opt") == false && (a == null || b == null))
                    throw new JavaParser.Unsupported("`null` (Rust has no null: this needs an Option)");
                if (a != null && b != null && a.type().is("opt") && !b.type().is("opt")) a = unwrapOpt(a);
                if (a != null && b != null && b.type().is("opt") && !a.type().is("opt")) b = unwrapOpt(b);
                if (a == null || b == null) {
                    JT ot = isNull(c.a()) ? b.type() : a.type();
                    Ex na = isNull(c.a()) ? new Ex("None", ot) : valueTo(c.a(), ot, x);
                    Ex nb = isNull(c.b()) ? new Ex("None", ot) : valueTo(c.b(), ot, x);
                    return new Ex("if " + cc.code() + " { " + na.code() + " } else { " + nb.code() + " }", ot);
                }
                if (want != null && want.is("opt") && (isNull(c.a()) || isNull(c.b()))) {
                    Ex na = valueTo(c.a(), want, x), nb = valueTo(c.b(), want, x);
                    return new Ex("if " + cc.code() + " { " + na.code() + " } else { " + nb.code() + " }", want);
                }
                JT t;
                // Optional.empty() beside an Optional of something: that something.
                if (a.type().is("opt") && b.type().is("opt") && (a.type().elem() == null) != (b.type().elem() == null)) {
                    JT ot = a.type().elem() != null ? a.type() : b.type();
                    a = new Ex(a.code(), ot, a.owned(), a.place());
                    b = new Ex(b.code(), ot, b.owned(), b.place());
                }
                if (a.type().numeric() && b.type().numeric()) {
                    t = promote(a.type(), b.type());
                    a = numeric(a, t);
                    b = numeric(b, t);
                } else if (a.type().equals(b.type())) t = a.type();
                else if (a.type().k().equals(b.type().k()) && Set.of("list", "set", "map", "array").contains(a.type().k()) && compatible(a.type(), b.type()))
                    t = a.type().elem() != null ? a.type() : b.type(); // Set.of() and a Set<String>
                else throw new JavaParser.Unsupported("a ?: with two different types");
                if (!t.copy() && !t.is("string") && !(a.owned() && b.owned()) && (a.owned() || b.owned()) && valueLike(t)) {
                    // A new value or an existing one that never changes: a copy of the existing one.
                    if (!a.owned()) a = new Ex("staticobj".equals(a.place()) ? withRead(a, ".clone()") : copyOf(a, t), t);
                    if (!b.owned()) b = new Ex("staticobj".equals(b.place()) ? withRead(b, ".clone()") : copyOf(b, t), t);
                }
                if (t.is("cls") && immutable(classes.get(t.name())) || t.is("opt") && t.elem() != null && t.elem().is("cls") && immutable(classes.get(t.elem().name()))) {
                    // Objects that never change: a copy is as good as the shared one.
                    if (!a.owned()) a = new Ex(paren(staticValue(a).code()) + (a.place() != null && a.place().equals("staticobj") ? "" : ".clone()"), t);
                    if (!b.owned()) b = new Ex(paren(staticValue(b).code()) + (b.place() != null && b.place().equals("staticobj") ? "" : ".clone()"), t);
                }
                if (t.is("opt") && t.elem() != null && (t.elem().copy() || t.elem().is("string"))) {
                    if (!a.owned()) a = new Ex(copyOf(a, t), t);
                    if (!b.owned()) b = new Ex(copyOf(b, t), t);
                }
                boolean owned = true;
                boolean literals = "lit".equals(a.place()) && "lit".equals(b.place());
                if (t.is("string") && !literals && !(a.owned() && b.owned())) {
                    if (!a.owned()) a = new Ex(paren(a.code()) + ".to_string()", t);
                    if (!b.owned()) b = new Ex(paren(b.code()) + ".to_string()", t);
                } else if (!t.copy()) owned = a.owned();
                if (!t.copy() && !t.is("string") && !(a.owned() && b.owned()) && (a.owned() || b.owned())) {
                    // A new object or one a local holds (nothing else in Rust can share it): the local's is copied.
                    Ex ex2 = a.owned() ? b : a;
                    if ("local".equals(ex2.place()) && !ex2.code().contains(".")) {
                        if (a.owned()) b = new Ex(copyOf(b, t), t);
                        else a = new Ex(copyOf(a, t), t);
                    } else if (ex2.code().matches("\\(?[a-z_0-9]+\\)?\\.as_ref\\(\\)\\.expect\\(\"null\"\\)")) {
                        // the same, for a nullable local checked just before
                        if (a.owned()) b = new Ex(copyOf(b, t), t);
                        else a = new Ex(copyOf(a, t), t);
                    }
                }
                if (!t.copy() && !t.is("string") && !(a.owned() && b.owned())) {
                    if (a.owned() || b.owned()) throw new JavaParser.Unsupported("a ?: choosing between a new object and an existing one");
                    return new Ex("if " + cc.code() + " { " + borrowOf(a) + " } else { " + borrowOf(b) + " }", t, false, "ref");
                }
                return new Ex("if " + cc.code() + " { " + a.code() + " } else { " + b.code() + " }", t, owned, null);
            }
            case Cast c:
                return cast(c, x);
            case InstanceOf io: {
                if (gameMode && Set.of("LocalPlayer", "AbstractClientPlayer", "Player").contains(io.type().simple())) {
                    Ex v = ex(io.e(), x);
                    if (v.type().is("player")) {
                        // The hand renderer only draws the local player.
                        if (io.binding() != null) x.scopes.peek().put(io.binding(), new Var(io.binding(), v.code(), PLAYER, "alias", -1));
                        return new Ex("true", JT.BOOL, true, "lit");
                    }
                }
                if (gameMode && io.binding() == null) {
                    Ex v = unwrapOpt(ex(io.e(), x));
                    if (v.type().is("item")) {
                        String cls = io.type().simple();
                        if (cls.equals("BlockItem")) return new Ex("j_is_block_item(game, &" + paren(v.code()) + ")", JT.BOOL);
                        if (cls.equals("BucketItem") || cls.equals("MobBucketItem"))
                            return new Ex("{ let id: &str = &" + paren(v.code()) + "; id == \"minecraft:bucket\" || id.ends_with(\"_bucket\") && id != \"minecraft:milk_bucket\" && id != \"minecraft:powder_snow_bucket\""
                                    + (cls.equals("MobBucketItem") ? " && !matches!(id, \"minecraft:water_bucket\" | \"minecraft:lava_bucket\")" : "") + " }", JT.BOOL);
                        if (cls.equals("SolidBucketItem")) return new Ex(paren(v.code()) + " == \"minecraft:powder_snow_bucket\"", JT.BOOL);
                        String id = ITEM_CLASSES.get(cls);
                        if (id != null) return new Ex(paren(v.code()) + " == \"" + id + "\"", JT.BOOL);
                        throw new JavaParser.Unsupported("`instanceof " + cls + "` on an item (no single item id for it)");
                    }
                }
                throw new JavaParser.Unsupported("`instanceof`");
            }
            case Lambda l:
                throw new JavaParser.Unsupported("a lambda");
            case MethodRef r:
                throw new JavaParser.Unsupported("a method reference");
            case SwitchExpr se:
                return switchExpr(se.sw(), x, want);
        }
    }

    /** A switch expression as a `match` with a value per arm. Arms are `case .. ->` (an expression, a block ending in yield, or a throw). */
    private Ex switchExpr(Switch sw, Ctx x, JT want) {
        Ex sel = ex(sw.sel(), x);
        String head;
        if (sel.type().is("string")) head = "match &*" + paren(sel.code()) + " {";
        else if (sel.type().is("enum") || sel.type().numeric()) head = "match " + sel.code() + " {";
        else throw new JavaParser.Unsupported("a switch on " + sel.type());
        JT t = want;
        if (t == null) {
            for (Case k : sw.cases()) {
                if (k.body().size() == 1 && k.body().get(0) instanceof ExprStmt es) {
                    t = ex(es.e(), x).type();
                    break;
                }
            }
        }
        if (t == null) throw new JavaParser.Unsupported("a switch expression whose type isn't clear");
        if (!t.copy() && !t.is("string") && !t.is("ident") && !(t.is("cls") && immutable(classes.get(t.name())))
                && !(t.is("opt") && t.elem() != null && t.elem().is("cls") && immutable(classes.get(t.elem().name()))))
            throw new JavaParser.Unsupported("a switch expression giving a " + t);
        StringBuilder b = new StringBuilder(head).append('\n');
        String in = "    ".repeat(x.depth + 1);
        boolean hasDefault = false;
        for (Case k : sw.cases()) {
            if (!k.arrow()) throw new JavaParser.Unsupported("a switch expression with `case X:` (not ->)");
            if (k.labels().isEmpty()) hasDefault = true;
            List<String> pats = new ArrayList<>();
            for (Expr l : k.labels()) pats.add(pattern(l, sel.type(), x));
            String pat = k.labels().isEmpty() ? "_" : String.join(" | ", pats);
            Stmt s = k.body().get(0);
            String arm;
            if (s instanceof ExprStmt es) arm = valueTo(es.e(), t, x).code();
            else {
                List<Stmt> stmts = s instanceof Block blk ? new ArrayList<>(blk.stmts()) : new ArrayList<>(List.of(s));
                Expr value = null;
                if (!stmts.isEmpty() && stmts.get(stmts.size() - 1) instanceof Yield y) {
                    value = y.e();
                    stmts.remove(stmts.size() - 1);
                }
                int mark = x.out.length();
                x.depth += 1;
                x.scopes.push(new HashMap<>());
                for (Stmt st : stmts) stmt(st, x);
                String v = value == null ? null : valueTo(value, t, x).code();
                x.scopes.pop();
                x.depth -= 1;
                String lines = x.out.substring(mark);
                x.out.setLength(mark);
                arm = "{\n" + lines + (v == null ? "" : in + "    " + v + "\n") + in + "}";
            }
            b.append(in).append(pat).append(" => ").append(arm).append(",\n");
        }
        if (!hasDefault) b.append(in).append("#[allow(unreachable_patterns)]\n").append(in).append("_ => unreachable!(\"no case matched\"),\n");
        b.append("    ".repeat(x.depth)).append('}');
        return new Ex(b.toString(), t);
    }

    /** A whole changing static used as a value: a copy of it, read under its lock. */
    private static Ex staticValue(Ex v) {
        if ("staticobj".equals(v.place()) && !v.code().contains(".with(") && !v.type().is("map") && !v.type().is("set"))
            return new Ex(v.code() + ".get()", v.type(), true, null);
        return v;
    }

    /** A borrow of a place: `&x`, or `x` when it's already a reference. */
    private String borrowOf(Ex v) {
        String p = v.place();
        if (p != null && (p.equals("param") || p.equals("iter") || p.equals("ref")) && !v.code().contains("[") && !v.code().contains(".")) return v.code();
        if ("ref".equals(p) && v.code().startsWith("if ")) return v.code();
        // An unwrapped nullable is already a reference.
        if ("ref".equals(p) && (v.code().endsWith(".as_ref().expect(\"null\")") || v.code().endsWith(".as_deref().expect(\"null\")"))) return v.code();
        if ("lazy".equals(p)) return "&" + v.code();
        if ("staticobj".equals(p)) {
            if (!valueLike(v.type()) || v.code().contains(".with(")) throw new JavaParser.Unsupported("a second name for a changing static (" + v.code() + ")");
            return "&" + withRead(v, ".clone()");
        }
        return "&" + paren(v.code());
    }

    private static String placeOf(Ex a) {
        return a.place() == null ? "local" : a.place().equals("param") ? "param" : a.place().equals("iter") ? "iter" : a.place();
    }

    private Ex literal(Lit l) {
        String s = l.text().replace("_", "");
        switch (l.kind()) {
            case "int": {
                long v = parseLong(s);
                if (s.toLowerCase().startsWith("0x") && v > Integer.MAX_VALUE) return new Ex("(" + s + "u32 as i32)", JT.INT, true, "lit");
                return new Ex(s, JT.INT, true, "lit");
            }
            case "long":
                return new Ex(s.substring(0, s.length() - 1) + "i64", JT.LONG, true, "lit");
            case "float":
                return new Ex(floatLit(s.substring(0, s.length() - 1)) + "f32", JT.FLOAT, true, "lit");
            case "double":
                return new Ex(floatLit(s.endsWith("d") || s.endsWith("D") ? s.substring(0, s.length() - 1) : s) + "f64", JT.DOUBLE, true, "lit");
            case "boolean":
                return new Ex(s, JT.BOOL, true, "lit");
            case "char":
                return new Ex("'" + escapes(l.text().substring(1, l.text().length() - 1), '\'') + "'", JT.CHAR, true, "lit");
            case "string":
                return new Ex("\"" + escapes(l.text().substring(1, l.text().length() - 1), '"') + "\"", JT.STRING, false, "lit");
            default:
                throw new JavaParser.Unsupported("`null` (Rust has no null: this needs an Option)");
        }
    }

    private static long parseLong(String s) {
        String low = s.toLowerCase();
        if (low.startsWith("0x")) return Long.parseLong(low.substring(2), 16);
        if (low.length() > 1 && low.startsWith("0")) return Long.parseLong(low, 8);
        return Long.parseLong(low);
    }

    private static String floatLit(String s) {
        String t = s.toLowerCase();
        if (t.startsWith("0x")) throw new JavaParser.Unsupported("a hex float literal");
        if (t.startsWith(".")) t = "0" + t;
        if (t.contains("e")) {
            String m = t.substring(0, t.indexOf('e'));
            if (!m.contains(".")) t = m + ".0" + t.substring(t.indexOf('e'));
        } else if (!t.contains(".")) t = t + ".0";
        else if (t.endsWith(".")) t = t + "0";
        return t;
    }

    /** Java escapes to Rust ones: \\uXXXX becomes \\u{XXXX}; octal escapes aren't translated. */
    private static String escapes(String s, char quote) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n', 't', 'r', '\\', '"', '\'', '0' -> b.append('\\').append(n);
                    case 'b' -> b.append("\\u{8}");
                    case 'f' -> b.append("\\u{c}");
                    case 's' -> b.append(' ');
                    case 'u' -> {
                        while (i + 1 < s.length() && s.charAt(i + 1) == 'u') i++;
                        b.append("\\u{").append(s, i + 1, Math.min(i + 5, s.length())).append('}');
                        i += 4;
                    }
                    default -> throw new JavaParser.Unsupported("an octal escape in a string");
                }
            } else if (c == '{' || c == '}') {
                b.append(c);
            } else if (c < 0x20 || c > 0x7e) {
                b.append("\\u{").append(Integer.toHexString(c)).append('}');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private boolean isClassName(String id, Ctx x) {
        if (x.find(id) != null) return false;
        if (fieldOwner(id, x.cls, true) != null || fieldOwner(id, x.cls, false) != null) return false;
        return ownClass(id, x.cls) != null || Character.isUpperCase(id.charAt(0));
    }

    private Cls ownClass(String name, Cls ctx) {
        for (Cls k = ctx; k != null; k = k.outer) {
            Cls n = classes.get(k.d.qualified + "." + name);
            if (n != null) return n;
        }
        return classes.get(name);
    }

    /** The class (this one or an outer one) that declares field `name`, static or not. */
    private Cls fieldOwner(String name, Cls c, boolean statics) {
        for (Cls k = c; k != null; k = k.outer) {
            FieldDecl f = k.fields.get(name);
            if (f != null && f.mods.contains("static") == statics) return k;
            if (!statics) return null; // instance fields: only this class's
        }
        return null;
    }

    private Ex name(String id, Ctx x) {
        Var v = x.find(id);
        if (v != null && v.kind().equals("alias")) return new Ex(v.rust(), v.type(), true, "alias");
        if (v != null) {
            boolean owned = v.type().copy();
            return new Ex(v.rust(), v.type(), owned, v.kind());
        }
        if (!x.isStatic || x.ctor) {
            Cls owner = fieldOwner(id, x.cls, false);
            if (owner != null) return instanceField(x.self(), owner, id, x);
        }
        Cls st = fieldOwner(id, x.cls, true);
        if (st != null) return staticRead(st, id, x);
        for (Cls k = x.cls; k != null; k = k.outer) {
            if (k.simpleEnum && k.failed == null && k.d.constants.stream().anyMatch(e -> e.name().equals(id)))
                return new Ex(k.module + "::" + k.rust + "::" + camel(id), new JT("enum", k.d.qualified, null), true, "const");
        }
        if (x.isStatic && fieldOwner(id, x.cls, false) != null) throw new JavaParser.Unsupported("an instance field used from a static method");
        Cls own = ownClass(id, x.cls);
        if (own != null) return new Ex(own.module, new JT("classref", own.d.qualified, null), false, "class");
        if (Character.isUpperCase(id.charAt(0))) return new Ex(id, new JT("extref", id, null), false, "class");
        throw new JavaParser.Unsupported("unknown name `" + id + "`");
    }

    private Ex instanceField(String target, Cls owner, String id, Ctx x) {
        FieldDecl f = owner.fields.get(id);
        if (f.error != null) throw new JavaParser.Unsupported("field " + id + "'s value couldn't be read");
        if (owner.failed != null) throw new JavaParser.Unsupported("class " + owner.d.name + " isn't translated (" + owner.failed + ")");
        JT t = fieldType(owner, f);
        return new Ex(target + "." + rustName(id), t, t.copy(), "field");
    }

    private Ex staticRead(Cls c, String id, Ctx x) {
        FieldDecl f = c.fields.get(id);
        String key = fieldKey(c, id);
        if (gameMode && keyFields.containsKey(key)) return new Ex("\"" + keyFields.get(key) + "\"", new JT("modkey", "KeyMapping", null), true, "alias");
        boolean gameSide = gameMode && gameStatics.contains(key);
        if (failed.containsKey(key) && !gameSide) throw new JavaParser.Unsupported("it uses " + c.d.name + "." + id + ", which isn't translated (" + failed.get(key) + ")");
        String kind = staticKind(c, f);
        if (kind.equals("log")) throw new JavaParser.Unsupported("it uses the logger as a value");
        JT t = fieldType(c, f);
        String path = gameSide ? gameStaticName(c, id) : c.module + "::" + staticName(c, id);
        switch (kind) {
            case "const":
                return new Ex(path, t, t.copy(), t.is("string") ? "lit" : "const");
            case "lazy":
                if (t.copy()) return new Ex("*" + path, t, true, "const");
                return new Ex("(*" + path + ")", t, false, "lazy");
            default:
                x.usesStatics = true;
                // Handles (a future) read out as copies of the handle, like plain values.
                if (t.copy() || t.is("string") || t.is("future") || t.is("opt") && t.elem() != null && (t.elem().copy() || t.elem().is("string") || t.elem().is("future")))
                    return new Ex(path + ".get()", t, true, "static");
                return new Ex(path, t, false, "staticobj");
        }
    }

    /** `NAME.with(|o| o<rest>)`: a read inside a changing static object. */
    private static String withRead(Ex obj, String rest) {
        if (obj.code().contains(".with(|o| o")) {
            int k = obj.code().lastIndexOf(')');
            return obj.code().substring(0, k) + rest + ")";
        }
        return obj.code() + ".with(|o| o" + rest + ")";
    }

    private String staticSetter(Expr target, Ctx x) {
        Ex t = ex(target, x);
        String code = t.code();
        if (!code.endsWith(".get()")) throw new JavaParser.Unsupported("an assignment to a static that isn't a plain value");
        return code.substring(0, code.length() - ".get()".length()) + ".set($V)";
    }

    private String staticObjSetter(Expr target, String value, Ctx x) {
        Ex t = ex(target, x);
        String code = t.code();
        int k = code.lastIndexOf(')');
        if (!code.contains(".with(|o| o") || k < 0) throw new JavaParser.Unsupported("an assignment into a changing static");
        return code.substring(0, k) + " = " + value + ")";
    }

    private Ex field(Field f, Ctx x) {
        if (gameMode && x.cls.mixin && f.target() instanceof This) {
            FieldDecl fd = x.cls.fields.get(f.name());
            if (fd != null && !fd.mods.contains("static")) {
                switch (fd.type.simple()) {
                    case "Minecraft": return new Ex("game", GAME, true, "alias");
                    case "EntityRenderDispatcher": return new Ex("dispatcher", new JT("dispatcher", "EntityRenderDispatcher", null), true, "alias");
                    default: throw new JavaParser.Unsupported("it uses the mixin's field " + f.name() + " (" + fd.type.simple() + ")");
                }
            }
        }
        if (f.target() instanceof This) {
            if (x.isStatic && !x.ctor) throw new JavaParser.Unsupported("`this` in a static method");
            Cls owner = fieldOwner(f.name(), x.cls, false);
            if (owner == null) {
                Cls st = fieldOwner(f.name(), x.cls, true);
                if (st != null) return staticRead(st, f.name(), x);
                throw new JavaParser.Unsupported("unknown field `" + f.name() + "`");
            }
            return instanceField(x.self(), owner, f.name(), x);
        }
        Ex t = unwrapOpt(ex(f.target(), x));
        JT tt = t.type();
        if (gameMode && (implicitGame(tt) || GAME_VALUES.contains(tt.k()) || tt.is("player"))) return gameField(t, f.name(), x);
        if (tt.is("classref")) {
            Cls c = classes.get(tt.name());
            if (c.simpleEnum && c.d.constants.stream().anyMatch(k -> k.name().equals(f.name()))) {
                if (c.failed != null) throw new JavaParser.Unsupported("enum " + c.d.name + " isn't translated");
                return new Ex(c.module + "::" + c.rust + "::" + camel(f.name()), new JT("enum", c.d.qualified, null), true, "const");
            }
            Cls nested = classes.get(c.d.qualified + "." + f.name());
            if (nested != null) return new Ex(nested.module, new JT("classref", nested.d.qualified, null), false, "class");
            if (c.fields.containsKey(f.name()) && c.fields.get(f.name()).mods.contains("static")) return staticRead(c, f.name(), x);
            throw new JavaParser.Unsupported("unknown static " + c.d.name + "." + f.name());
        }
        if (gameMode && tt.is("extref")) {
            Ex g = gameConst(tt.name(), f.name());
            if (g != null) return g;
        }
        if ((tt.is("vec3f") && Set.of("x", "y", "z").contains(f.name())) || (tt.is("quat") && Set.of("x", "y", "z", "w").contains(f.name())))
            return new Ex(paren(t.code()) + "." + f.name(), JT.FLOAT, true, t.place() == null ? null : placeOf(t));
        if (tt.is("extref") && tt.name().equals("Axis") && f.name().matches("[XYZ][PN]"))
            return new Ex("", new JT("axis", f.name(), null), true, "lit");
        if (tt.is("vec3d") && Set.of("x", "y", "z").contains(f.name()))
            return new Ex(paren(t.code()) + "[" + "xyz".indexOf(f.name()) + "]", JT.DOUBLE);
        if (tt.is("inventory") && f.name().equals("selected")) return new Ex("(game.selected as i32)", JT.INT);
        if (tt.is("extref") && tt.name().equals("Locale")) return new Ex("()", new JT("locale", "Locale", null), true, "lit");
        if (tt.is("extref") && tt.name().equals("TimeUnit")) {
            String ms = switch (f.name()) {
                case "MILLISECONDS" -> "1";
                case "SECONDS" -> "1000";
                case "MINUTES" -> "60_000";
                case "HOURS" -> "3_600_000";
                default -> throw new JavaParser.Unsupported("TimeUnit." + f.name());
            };
            return new Ex(ms, new JT("timeunit", f.name(), null), true, "lit");
        }
        if (tt.is("extref") && (tt.name().equals("Kind") || tt.name().equals("ModDependency.Kind"))
                && Set.of("DEPENDS", "RECOMMENDS", "SUGGESTS", "CONFLICTS", "BREAKS").contains(f.name()))
            return new Ex("\"" + f.name().toLowerCase() + "\"", DEPKIND, true, "lit");
        if (tt.is("extref") && (tt.name().equals("StandardCharsets") || tt.name().equals("StandardCopyOption") || tt.name().equals("LinkOption")))
            return new Ex("()", new JT("option", tt.name() + "." + f.name(), null), true, "lit");
        if (tt.is("extref")) {
            String v = EXT_FIELDS.get(tt.name() + "." + f.name());
            if (v == null) throw new JavaParser.Unsupported("it uses " + describe(tt.name(), x.cls) + "." + f.name());
            JT type = v.startsWith("f32:") ? JT.FLOAT : v.startsWith("f64:") ? JT.DOUBLE : v.startsWith("i64:") ? JT.LONG : JT.INT;
            return new Ex(v.substring(4), type, true, "const");
        }
        if ((tt.is("array")) && f.name().equals("length")) {
            if ("staticobj".equals(t.place())) return new Ex(withRead(t, ".len()") + " as i32", JT.INT);
            return new Ex("(" + paren(t.code()) + ".len() as i32)", JT.INT);
        }
        if (tt.is("cls")) {
            Cls c = classes.get(tt.name());
            if (c.fields.containsKey(f.name()) && !c.fields.get(f.name()).mods.contains("static")) {
                Ex fe = instanceField(paren(t.code()), c, f.name(), x);
                if ("staticobj".equals(t.place())) {
                    if (!fe.type().copy() && !fe.type().is("string")) return new Ex(withRead(t, "." + rustName(f.name())), fe.type(), false, "staticobj");
                    String read = withRead(t, "." + rustName(f.name()) + (fe.type().is("string") ? ".clone()" : ""));
                    return new Ex(read, fe.type(), true, "staticobj");
                }
                return new Ex(fe.code(), fe.type(), fe.owned(), placeOf(t).equals("param") || placeOf(t).equals("iter") || placeOf(t).equals("ref") ? placeOf(t) : "field");
            }
        }
        throw new JavaParser.Unsupported("it reads ." + f.name() + " of a " + tt);
    }

    private static final Map<String, String> EXT_FIELDS = Map.ofEntries(
            Map.entry("Math.PI", "f64:std::f64::consts::PI"), Map.entry("Math.E", "f64:std::f64::consts::E"),
            Map.entry("Mth.PI", "f32:std::f32::consts::PI"), Map.entry("Mth.TWO_PI", "f32:std::f32::consts::TAU"),
            Map.entry("Mth.HALF_PI", "f32:std::f32::consts::FRAC_PI_2"), Map.entry("Mth.DEG_TO_RAD", "f32:(std::f32::consts::PI / 180.0)"),
            Map.entry("Mth.RAD_TO_DEG", "f32:(180.0 / std::f32::consts::PI)"),
            Map.entry("Integer.MAX_VALUE", "i32:i32::MAX"), Map.entry("Integer.MIN_VALUE", "i32:i32::MIN"),
            Map.entry("Long.MAX_VALUE", "i64:i64::MAX"), Map.entry("Long.MIN_VALUE", "i64:i64::MIN"),
            Map.entry("Float.MAX_VALUE", "f32:f32::MAX"), Map.entry("Float.MIN_VALUE", "f32:f32::from_bits(1)"),
            Map.entry("Float.POSITIVE_INFINITY", "f32:f32::INFINITY"), Map.entry("Float.NEGATIVE_INFINITY", "f32:f32::NEG_INFINITY"),
            Map.entry("Float.NaN", "f32:f32::NAN"), Map.entry("Double.MAX_VALUE", "f64:f64::MAX"), Map.entry("Double.NaN", "f64:f64::NAN"),
            Map.entry("Double.POSITIVE_INFINITY", "f64:f64::INFINITY"), Map.entry("Double.NEGATIVE_INFINITY", "f64:f64::NEG_INFINITY"));

    private Ex cast(Cast c, Ctx x) {
        Ex v = ex(c.e(), x);
        TypeRef tr = c.type();
        if (isPrimType(tr)) {
            JT t = JT.prim(tr.name());
            if (t.isBool() || v.type().isBool()) {
                if (t.equals(v.type())) return v;
                throw new JavaParser.Unsupported("a cast between boolean and a number");
            }
            if (!v.type().numeric()) throw new JavaParser.Unsupported("a cast of a " + v.type() + " to " + tr.name());
            return numeric(v, t);
        }
        JT t = resolve(tr, x.cls);
        if (t.equals(v.type())) return v;
        // (Foo) of a Foo that may be null: still that, null and all.
        if (v.type().is("opt") && t.equals(v.type().elem())) return v;
        if (v.type().is("prim") && tr.dims() == 0 && Set.of("Integer", "Float", "Double", "Long", "Boolean").contains(tr.name())) return v;
        throw new JavaParser.Unsupported("a cast to " + tr.simple() + " (a downcast)");
    }

    private Ex unary(Unary u, Ctx x) {
        if (u.op().equals("++") || u.op().equals("--")) throw new JavaParser.Unsupported("`" + u.op() + "` inside an expression");
        Ex v = unwrapOpt(ex(u.e(), x));
        switch (u.op()) {
            case "!":
                if (!v.type().isBool()) throw new JavaParser.Unsupported("`!` on a " + v.type());
                return new Ex("!" + paren(v.code()), JT.BOOL);
            case "-": {
                if (!v.type().numeric()) throw new JavaParser.Unsupported("`-` on a " + v.type());
                JT t = promote(v.type(), JT.INT);
                return new Ex("-" + paren(numeric(v, t).code()), t);
            }
            case "+":
                return numeric(v, promote(v.type(), JT.INT));
            case "~": {
                if (!v.type().numeric() || v.type().floating()) throw new JavaParser.Unsupported("`~` on a " + v.type());
                JT t = promote(v.type(), JT.INT);
                return new Ex("!" + paren(numeric(v, t).code()), t);
            }
            default:
                throw new JavaParser.Unsupported("unary " + u.op());
        }
    }

    private Ex binary(Binary b, Ctx x) {
        String op = b.op();
        if (Set.of("&&", "||", "==", "!=").contains(op)) {
            Boolean k = fold(b, x);
            if (k != null) return new Ex(k.toString(), JT.BOOL, true, "lit");
        }
        if ((op.equals("==") || op.equals("!=")) && (isNull(b.l()) || isNull(b.r()))) return nullCheck(isNull(b.l()) ? b.r() : b.l(), op.equals("=="), x);
        if (op.equals("+") && (stringy(b.l(), x) || stringy(b.r(), x))) return concat(b, x);
        Ex l0 = ex(b.l(), x), r0 = ex(b.r(), x);
        if ((op.equals("==") || op.equals("!=")) && (l0.type().is("opt") || r0.type().is("opt"))) {
            // `hand == busyHand` with busyHand null is false in Java, not a NullPointerException: compare the
            // Options (`busy == Some(hand)`) for values compared by identity (enums and the like).
            JT lt = l0.type().is("opt") ? l0.type().elem() : l0.type(), rt = r0.type().is("opt") ? r0.type().elem() : r0.type();
            if (lt != null && lt.equals(rt) && lt.copy() && identityKind(lt) && !"staticobj".equals(l0.place()) && !"staticobj".equals(r0.place())) {
                String ls = l0.type().is("opt") ? paren(l0.code()) : "Some(" + l0.code() + ")";
                String rs = r0.type().is("opt") ? paren(r0.code()) : "Some(" + r0.code() + ")";
                return new Ex(ls + " " + op + " " + rs, JT.BOOL);
            }
        }
        Ex l = unwrapOpt(l0), r = unwrapOpt(r0);
        switch (op) {
            case "&&": case "||":
                if (!l.type().isBool() || !r.type().isBool()) throw new JavaParser.Unsupported("`" + op + "` on non-booleans");
                return new Ex(paren(l.code()) + " " + op + " " + paren(r.code()), JT.BOOL);
            case "==": case "!=": {
                if (l.type().equals(JT.CHAR) && r.type().equals(JT.CHAR)) return new Ex(paren(l.code()) + " " + op + " " + paren(r.code()), JT.BOOL);
                if (l.type().numeric() && r.type().numeric()) {
                    JT t = promote(l.type(), r.type());
                    return new Ex(paren(numeric(l, t).code()) + " " + op + " " + paren(numeric(r, t).code()), JT.BOOL);
                }
                if (l.type().equals(r.type()) && (l.type().isBool() || l.type().is("enum") || l.type().is("ident") || l.type().is("hand")
                        || l.type().is("arm") || l.type().is("item") || l.type().is("depkind")))
                    return new Ex(paren(l.code()) + " " + op + " " + paren(r.code()), JT.BOOL);
                if (l.type().is("string") && r.type().is("string"))
                    return new Ex((op.equals("!=") ? "!" : "") + "j_eq(" + strArg(l) + ", " + strArg(r) + ")", JT.BOOL);
                // Values or references alike: compare what they hold.
                if (l.type().is("stack") && r.type().is("stack")) {
                    l = staticValue(l);
                    r = staticValue(r);
                    return new Ex("Option::as_ref(&" + paren(l.code()) + ") " + op + " Option::as_ref(&" + paren(r.code()) + ")", JT.BOOL);
                }
                if (l.type().is("useanim") && r.type().is("useanim") || l.type().is("swingtype") && r.type().is("swingtype")
                        || l.type().is("modeltype") && r.type().is("modeltype") || l.type().is("displayctx") && r.type().is("displayctx"))
                    return new Ex(paren(l.code()) + " " + op + " " + paren(r.code()), JT.BOOL);
                throw new JavaParser.Unsupported("`" + op + "` between a " + l.type() + " and a " + r.type() + " (object identity)");
            }
            case "<": case ">": case "<=": case ">=": {
                if (l.type().equals(JT.CHAR) && r.type().equals(JT.CHAR)) return new Ex(paren(l.code()) + " " + op + " " + paren(r.code()), JT.BOOL);
                if (!l.type().numeric() || !r.type().numeric()) throw new JavaParser.Unsupported("`" + op + "` on non-numbers");
                JT t = promote(l.type(), r.type());
                return new Ex(paren(numeric(l, t).code()) + " " + op + " " + paren(numeric(r, t).code()), JT.BOOL);
            }
            case "&": case "|": case "^":
                if (l.type().isBool() && r.type().isBool()) return new Ex(paren(l.code()) + " " + op + " " + paren(r.code()), JT.BOOL);
                // fall through to integers
            case "<<": case ">>": case ">>>": {
                if (!l.type().numeric() || !r.type().numeric() || l.type().floating() || r.type().floating())
                    throw new JavaParser.Unsupported("`" + op + "` on " + l.type() + " and " + r.type());
                if (op.startsWith("<<") || op.startsWith(">>")) {
                    JT t = promote(l.type(), JT.INT);
                    String rr = paren(numeric(r, JT.INT).code());
                    if (op.equals(">>>")) {
                        String u = t.equals(JT.LONG) ? "u64" : "u32";
                        return new Ex("((" + paren(numeric(l, t).code()) + " as " + u + ") >> " + rr + ") as " + rustPrim(t.name()), t);
                    }
                    return new Ex(paren(numeric(l, t).code()) + " " + op + " " + rr, t);
                }
                JT t = promote(l.type(), r.type());
                return new Ex(paren(numeric(l, t).code()) + " " + op + " " + paren(numeric(r, t).code()), t);
            }
            case "+": case "-": case "*": case "/": case "%": {
                if (!l.type().numeric() || !r.type().numeric()) throw new JavaParser.Unsupported("`" + op + "` on " + l.type() + " and " + r.type());
                JT t = promote(l.type(), r.type());
                return new Ex(paren(numeric(l, t).code()) + " " + op + " " + paren(numeric(r, t).code()), t);
            }
            default:
                throw new JavaParser.Unsupported("operator " + op);
        }
    }

    private boolean stringy(Expr e, Ctx x) {
        if (e instanceof Lit l) return l.kind().equals("string");
        if (e instanceof Binary b && b.op().equals("+")) return stringy(b.l(), x) || stringy(b.r(), x);
        try {
            return ex(e, x).type().is("string");
        } catch (JavaParser.Unsupported u) {
            return false;
        }
    }

    private Ex concat(Binary b, Ctx x) {
        List<Expr> parts = new ArrayList<>();
        flatten(b, parts, x);
        StringBuilder fmt = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (Expr p : parts) {
            if (p instanceof Lit l && l.kind().equals("string")) {
                fmt.append(escapes(l.text().substring(1, l.text().length() - 1).replace("{", "{{").replace("}", "}}"), (char) 34));
                continue;
            }
            Ex raw = ex(p, x);
            if (raw.type().is("opt") && raw.type().elem() != null && !"staticobj".equals(raw.place())
                    && (raw.type().elem().is("string") || raw.type().elem().is("ident") || raw.type().elem().is("prim") && !raw.type().elem().floating())) {
                // Java prints a null as "null".
                fmt.append("{}");
                args.add(paren(raw.code()) + ".as_ref().map_or_else(|| \"null\".to_string(), |v| v.to_string())");
                continue;
            }
            Ex v = unwrapOpt(raw);
            if (v.type().floating()) {
                fmt.append("{}");
                args.add("j_float(" + numeric(v, JT.DOUBLE).code() + ")");
            } else if (v.type().is("enum")) {
                fmt.append("{}");
                args.add(paren(v.code()) + ".java_name()");
            } else if (v.type().is("prim") || v.type().is("string") || v.type().is("ident")) {
                fmt.append("{}");
                args.add(v.code());
            } else if (v.type().is("item")) {
                // Item.toString is its registered id.
                fmt.append("{}");
                args.add(v.code());
            } else if (v.type().is("path")) {
                fmt.append("{}");
                args.add(paren(v.code()) + ".display()");
            } else if ((v.type().is("map") && plainText(v.type().key()) && plainText(v.type().elem()))
                    || ((v.type().is("list") || v.type().is("set")) && plainText(v.type().elem()))) {
                // Java's AbstractMap / AbstractCollection toString: {a=1, b=2} and [a, b].
                boolean map = v.type().is("map");
                String join = map ? ".iter().map(|(k, v)| format!(\"{}={}\", k, v)).collect::<Vec<_>>().join(\", \")"
                        : ".iter().map(|v| v.to_string()).collect::<Vec<_>>().join(\", \")";
                fmt.append(map ? "{{{}}}" : "[{}]");
                args.add("staticobj".equals(v.place()) ? withRead(v, join) : paren(v.code()) + join);
            } else throw new JavaParser.Unsupported("a " + v.type() + " turned into text");
        }
        return new Ex("format!(\"" + fmt + "\"" + (args.isEmpty() ? "" : ", " + String.join(", ", args)) + ")", JT.STRING);
    }

    /** A map key or collection element that prints in Rust as Java prints it: whole numbers, booleans, chars, strings. */
    private static boolean plainText(JT t) {
        return t != null && (t.is("string") || t.is("ident") || t.is("prim") && !t.floating());
    }

    /** Java's + is left to right: "a" + 1 + 2 is "a12", but 1 + 2 + "a" is "3a". */
    private void flatten(Expr e, List<Expr> out, Ctx x) {
        if (e instanceof Binary b && b.op().equals("+") && stringy(b, x)) {
            if (stringy(b.l(), x)) flatten(b.l(), out, x);
            else out.add(b.l());
            out.add(b.r() instanceof Binary rb && rb.op().equals("+") && stringy(rb, x) ? b.r() : b.r());
            if (out.get(out.size() - 1) instanceof Binary rb2 && rb2.op().equals("+") && stringy(rb2, x)) {
                out.remove(out.size() - 1);
                flatten(rb2, out, x);
            }
        } else out.add(e);
    }

    private String fmtArg(Ex v) {
        if (v.type().floating()) return "j_float(" + numeric(v, JT.DOUBLE).code() + ")";
        if (v.type().is("enum")) return paren(v.code()) + ".java_name()";
        if (v.type().is("opt")) {
            JT e = v.type().elem();
            if (e == null || "staticobj".equals(v.place()) || !(e.is("string") || e.is("ident") || e.is("prim") && !e.floating()))
                throw new JavaParser.Unsupported("a nullable " + e + " turned into text");
            // Java prints a null as "null".
            return paren(v.code()) + ".as_ref().map_or_else(|| \"null\".to_string(), |v| v.to_string())";
        }
        return v.code();
    }

    private Ex stringOf(Ex v) {
        if (v.type().is("string")) return v;
        return new Ex("format!(\"{}\", " + fmtArg(v) + ")", JT.STRING);
    }

    // ---- calls ----

    private Ex call(Call c, Ctx x) {
        if (c.name().equals("super") || c.target() instanceof Name sn && sn.id().equals("super")) throw new JavaParser.Unsupported("`super`");
        if (c.name().equals("this")) throw new JavaParser.Unsupported("this(...) after the start of a constructor");
        if (c.target() == null) return ownCall(x.cls, c.name(), c.args(), x, null, true);
        if (gameMode && x.cls.mixin && (c.target() == null || c.target() instanceof This)) {
            Ex shadow = shadowCall(c, x);
            if (shadow != null) return shadow;
        }
        if (c.target() instanceof This) return ownCall(x.cls, c.name(), c.args(), x, null, false);
        Ex optional = optionalStatic(c, x);
        if (optional != null) return optional;
        Ex chain = jomlChain(c, x);
        if (chain != null) return chain;
        Ex t = ex(c.target(), x);
        if (t.type().is("opt") && !(t.type().name().equals("Optional") && OPTIONAL_METHODS.contains(c.name()))) t = unwrapOpt(t);
        JT tt = t.type();
        if (c.name().equals("iterator") && c.args().isEmpty() && (tt.is("list") || tt.is("array") || tt.is("set") || tt.is("json"))) {
            // An explicit Iterator loop walks a copy taken here (the loop body can't change the collection under it).
            if ("staticobj".equals(t.place())) return new Ex("JIter::new(" + withRead(t, ".iter().cloned().collect::<Vec<_>>()") + ")", new JT("iter", "Iterator", tt.elem()));
            if (tt.is("json")) return new Ex("JIter::new(" + paren(t.code()) + ".elements().cloned().collect::<Vec<_>>())", new JT("iter", "Iterator", JSON));
            return new Ex("JIter::new(" + iterOf(t) + ".cloned().collect::<Vec<_>>())", new JT("iter", "Iterator", tt.elem()));
        }
        switch (tt.k()) {
            case "iter":
                if (c.args().isEmpty() && c.name().equals("hasNext")) return new Ex(paren(t.code()) + ".has_next()", JT.BOOL);
                if (c.args().isEmpty() && c.name().equals("next")) {
                    if (tt.elem() == null) throw new JavaParser.Unsupported("an Iterator of unknown items");
                    return new Ex(paren(t.code()) + ".next()", tt.elem());
                }
                throw new JavaParser.Unsupported("Iterator." + c.name());
            case "classref": {
                Cls cl = classes.get(tt.name());
                if (cl.simpleEnum && c.name().equals("valueOf") && c.args().size() == 1 && !cl.methods.containsKey("valueOf")) {
                    Ex v = ex(c.args().get(0), x);
                    if (v.type().is("string")) return new Ex(cl.module + "::" + cl.rust + "::value_of(" + strArg(v) + ")", new JT("enum", cl.d.qualified, null));
                }
                if (cl.simpleEnum && c.name().equals("values") && c.args().isEmpty())
                    return new Ex(cl.module + "::" + cl.rust + "::VALUES.to_vec()", new JT("array", "array", new JT("enum", cl.d.qualified, null)));
                return ownCall(cl, c.name(), c.args(), x, null, true);
            }
            case "extref":
                return extStatic(tt.name(), c, x);
            case "string":
                return stringCall(t, c, x);
            case "array":
                if (c.name().equals("clone") && c.args().isEmpty()) {
                    if ("staticobj".equals(t.place())) return new Ex(withRead(t, ".clone()"), tt);
                    return new Ex(paren(t.code()) + ".to_vec()", tt);
                }
                throw new JavaParser.Unsupported("array ." + c.name() + "()");
            case "list":
                return listCall(t, c, x);
            case "map":
                return mapCall(t, c, x);
            case "set":
                return setCall(t, c, x);
            case "view":
                return viewCall(t, c, x);
            case "entry":
                return entryCall(t, c, x);
            case "opt":
                return optionalCall(t, c, x);
            case "stream":
                return streamCall(t, c, x);
            case "comparator":
                return comparatorCall(t, c, x);
            case "enum": {
                if (c.name().equals("ordinal") && c.args().isEmpty()) return new Ex("(" + paren(t.code()) + " as i32)", JT.INT);
                if (c.name().equals("name") && c.args().isEmpty()) return new Ex(paren(t.code()) + ".java_name()", JT.STRING, false, "lit");
                if (c.name().equals("equals") && c.args().size() == 1) {
                    Ex o = ex(c.args().get(0), x);
                    return new Ex(paren(t.code()) + " == " + paren(o.code()), JT.BOOL);
                }
                return ownCall(classes.get(tt.name()), c.name(), c.args(), x, t, false);
            }
            case "cls": {
                Cls k = classes.get(tt.name());
                if (c.name().equals("equals") && c.args().size() == 1 && !k.methods.containsKey("equals") && eqable(tt)) {
                    Ex o = unwrapOpt(ex(c.args().get(0), x));
                    if (!o.type().equals(tt)) return new Ex("false", JT.BOOL);
                    return new Ex("(" + paren(t.code()) + " == " + paren(o.code()) + ")", JT.BOOL);
                }
                return ownCall(k, c.name(), c.args(), x, t, false);
            }
            case "game", "player", "gfx", "font", "delta", "modkey", "vkey", "screen", "level", "options",
                    "stack", "item", "hand", "arm", "vec3d", "inventory", "swinganim", "useanim", "gamemode", "dispatcher", "avatar", "skin",
                    "resolver", "renderstate", "text", "gui", "cmdsrc":
                return gameCall(t, c, x);
            case "ident":
                return identCall(t, c, x);
            case "path":
                return pathCall(t, c, x);
            case "regex":
                return regexCall(t, c, x);
            case "fn":
                return fnCall(t, c, x);
            case "props":
                return propsCall(t, c, x);
            case "httpclient", "httpreq", "httpresp", "version", "modc", "moddep", "future", "thread":
                return netCall(t, c, x);
            case "comp":
                return compCall(t, c, x);
            case "json":
                return jsonCall(t, c, x);
            case "gson":
                return gsonCall(t, c, x);
            case "atomic":
                return atomicCall(t, c, x);
            case "matcher":
                return matcherCall(t, c, x);
            case "posestack":
                return poseCall(t, c, x);
            case "random":
                return randomCall(t, c, x);
            case "poselast":
                if (c.name().equals("pose") && c.args().isEmpty()) return new Ex(t.code(), MAT4);
                throw new JavaParser.Unsupported("PoseStack.Pose." + c.name());
            case "axis": {
                String ax = tt.name().substring(0, 1).toLowerCase();
                String sign = tt.name().endsWith("N") ? "-" : "";
                if (c.args().size() != 1) throw new JavaParser.Unsupported("Axis." + c.name());
                String a = numeric(unwrapOpt(ex(c.args().get(0), x)), JT.FLOAT).code();
                if (c.name().equals("rotationDegrees")) return new Ex("JQuat::rotation_" + ax + "(" + sign + paren(a) + ".to_radians())", QUAT);
                if (c.name().equals("rotation")) return new Ex("JQuat::rotation_" + ax + "(" + sign + paren(a) + ")", QUAT);
                throw new JavaParser.Unsupported("Axis." + c.name());
            }
            case "vec3f", "quat", "mat4", "mat3":
                throw new JavaParser.Unsupported("JOML's " + tt.name() + "." + c.name());
            case "fabric":
                return fabricCall(c, x);
            case "prim":
                throw new JavaParser.Unsupported("a method call on a " + tt);
            default:
                throw new JavaParser.Unsupported("it calls " + describe(tt.name(), x.cls) + "." + c.name());
        }
    }

    /** A call to one of the mod's own methods. `recv` is the object for an instance call (null: this or static). */
    private Ex ownCall(Cls c, String name, List<Expr> args, Ctx x, Ex recv, boolean unqualified) {
        List<String[]> copies = new ArrayList<>();
        List<Expr> args2 = staticArgs(args, x, copies);
        if (copies.isEmpty()) return ownCall0(c, name, args, x, recv, unqualified);
        for (Cls k = c; k != null; k = unqualified ? k.outer : null) {
            for (MethodDecl md : k.methods.getOrDefault(name, List.of())) {
                if (md.params.size() == args.size() && touchesStatics(k, md)) {
                    for (String[] cp : copies) x.scopes.peek().remove(cp[0]);
                    throw new JavaParser.Unsupported("a changing static passed to a method that uses statics itself");
                }
            }
        }
        try {
            Ex call = ownCall0(c, name, args2, x, recv, unqualified);
            StringBuilder b = new StringBuilder("{ ");
            for (String[] cp : copies) b.append("let mut ").append(cp[0]).append(" = ").append(cp[1]).append(".get(); ");
            b.append("let r = ").append(call.code()).append("; ");
            for (String[] cp : copies) {
                if (mutLocal.contains(x.key() + ":" + cp[0])) {
                    markWrite(args.get(Integer.parseInt(cp[3])), x);
                    b.append(cp[1]).append(".set(").append(cp[0]).append("); ");
                }
            }
            return new Ex(b.append("r }").toString(), call.type(), true, null);
        } finally {
            for (String[] cp : copies) x.scopes.peek().remove(cp[0]);
        }
    }

    /**
     * Arguments that are a whole changing static array or list: passed as a copy (and copied back when the method
     * changes it). Only when the method uses no statics itself, so it can't see the difference.
     */
    private List<Expr> staticArgs(List<Expr> args, Ctx x, List<String[]> copies) {
        List<Expr> out = new ArrayList<>(args);
        for (int i = 0; i < args.size(); i++) {
            Expr a = args.get(i);
            if (!(a instanceof Name || a instanceof Field)) continue;
            Ex v;
            try {
                v = ex(a, x);
            } catch (JavaParser.Unsupported e) {
                continue;
            }
            if (!"staticobj".equals(v.place()) || v.code().contains(".with(") || !(v.type().is("array") || v.type().is("list"))) continue;
            String tn = "static_arg_" + (x.tmp++);
            x.scopes.peek().put(tn, new Var(tn, tn, v.type(), "local", -1));
            out.set(i, new Name(tn));
            copies.add(new String[] {tn, v.code(), tn, String.valueOf(i)});
        }
        return out;
    }

    private Ex ownCall0(Cls c, String name, List<Expr> args, Ctx x, Ex recv, boolean unqualified) {
        Cls owner = c;
        MethodDecl m = null;
        for (Cls k = c; k != null; k = unqualified ? k.outer : null) {
            List<MethodDecl> ms = k.methods.get(name);
            if (ms != null) {
                for (MethodDecl md : ms) if (md.params.size() == args.size() || varargsFits(md, args.size())) { m = md; owner = k; break; }
            }
            if (m != null) break;
        }
        if (m == null) throw new JavaParser.Unsupported("it calls " + c.d.name + "." + name + ", which isn't in the mod's source");
        String key = methodKey(owner, m);
        if (failed.containsKey(key) && gameMode && gameOk.contains(key) && m.isStatic()) {
            List<String> pre = new ArrayList<>(List.of("game"));
            if (needsBatch.contains(key) || m.params.stream().anyMatch(q -> q.type().simple().equals("GuiGraphics"))) {
                requireBatch(x);
                pre.add("batch");
            }
            String rest = argList(owner, m, args, x);
            if (!rest.isEmpty()) pre.add(rest);
            return new Ex(glueName(owner, m) + "(" + String.join(", ", pre) + ")", retType(owner, m), true, null);
        }
        if (failed.containsKey(key)) throw new JavaParser.Unsupported("it calls " + owner.d.name + "." + name + ", which isn't translated");
        String fn = methodName(owner, m);
        String argList = argList(owner, m, args, x);
        JT rt = retType(owner, m);
        boolean owned = !rt.is("string") || true;
        String q = "";
        if (throwsIo(m)) {
            if (!x.io) throw new JavaParser.Unsupported("it calls " + owner.d.name + "." + name + ", which throws, outside a try/catch");
            q = "?";
        }
        if (m.isStatic()) {
            if (!x.isStatic || x.ctor) { /* fine */ }
            if (touchesStatics(owner, m)) x.usesStatics = true;
            return new Ex(owner.module + "::" + fn + "(" + argList + ")" + q, rt, owned, null);
        }
        if (!q.isEmpty()) throw new JavaParser.Unsupported("an instance method that throws");
        boolean mut = mutSelf.contains(key);
        if (recv == null) {
            if (x.isStatic && !x.ctor) throw new JavaParser.Unsupported("an instance method called from a static one");
            if (mut && !x.ctor) grow(mutSelf, x.key());
            return new Ex(x.self() + "." + fn + "(" + argList + ")", rt, owned, null);
        }
        if ("staticobj".equals(recv.place())) {
            if (recv.code().contains(".with(")) throw new JavaParser.Unsupported("a call on an object inside a changing static");
            if (touchesStatics(owner, m)) throw new JavaParser.Unsupported("a call on a changing static that itself uses statics");
            if (mut) markRecv(recv, x);
            String tmp = "args_" + (x.tmp++);
            return new Ex("{ let " + tmp + " = (" + argList + (args.size() == 1 ? "," : "") + "); " + recv.code() + ".with(|o| o." + fn + "("
                    + tupleArgs(tmp, args.size()) + ")) }", rt, owned, null);
        }
        if (mut && "ref".equals(recv.place())) {
            Ex m2 = asMutUnwrap(recv, x);
            if (m2 != null) recv = m2;
        }
        if (mut) markRecv(recv, x);
        return new Ex(paren(recv.code()) + "." + fn + "(" + argList + ")", rt, owned, null);
    }

    /** `(local).as_ref().expect(..)` changed: `(local).as_mut().expect(..)`, with the local made `mut`. */
    private Ex asMutUnwrap(Ex recv, Ctx x) {
        String code = recv.code();
        int at = code.indexOf(".as_ref().expect(");
        if (at < 0 || code.indexOf(".as_ref().expect(", at + 1) >= 0) return null;
        String root = code.substring(0, at);
        if (root.startsWith("(") && root.endsWith(")")) root = root.substring(1, root.length() - 1);
        if (!root.matches("[a-z_][a-z_0-9]*")) return null;
        Var v = varByRust(root, x);
        if (v == null || !v.kind().equals("local")) return null;
        grow(mutLocal, x.key() + ":" + v.java());
        return new Ex(code.substring(0, at) + ".as_mut().expect(" + code.substring(at + ".as_ref().expect(".length()), recv.type(), false, "mutref");
    }

    private static String tupleArgs(String tmp, int n) {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < n; i++) s.add(tmp + "." + i);
        return String.join(", ", s);
    }

    private void markRecv(Ex recv, Ctx x) {
        String p = recv.place();
        if (p == null) return;
        switch (p) {
            case "copy" -> throw new JavaParser.Unsupported("it changes an object read out of a map (in Rust that's a copy)");
            case "staticobj" -> {
                // Find the static by its path.
                for (Cls c : all) for (FieldDecl f : c.d.fields)
                    if (f.mods.contains("static") && (startsPath(recv.code(), c.module + "::" + staticName(c, f.name))
                            || startsPath(recv.code(), gameStaticName(c, f.name)))) grow(mutStatic, fieldKey(c, f.name));
            }
            case "lazy" -> {
                for (Cls c : all) for (FieldDecl f : c.d.fields)
                    if (f.mods.contains("static") && (recv.code().equals("(*" + c.module + "::" + staticName(c, f.name) + ")")
                            || recv.code().equals("(*" + gameStaticName(c, f.name) + ")"))) grow(mutStatic, fieldKey(c, f.name));
            }
            case "field" -> {
                // `local.list.add(..)`: the local's own field, so the local is what changes.
                String root = recv.code().replaceFirst("^\\(+", "");
                int dot = root.indexOf('.');
                Var owner = dot > 0 ? varByRust(root.substring(0, dot), x) : null;
                if (owner != null && owner.kind().equals("local")) grow(mutLocal, x.key() + ":" + owner.java());
                else if (owner != null && owner.kind().equals("param")) grow(mutParam, x.key() + ":" + owner.index());
                else if (!x.isStatic && !x.ctor) grow(mutSelf, x.key());
            }
            case "param" -> {
                Var v = varByRust(recv.code(), x);
                if (v != null) grow(mutParam, x.key() + ":" + v.index());
            }
            case "local" -> {
                Var v = varByRust(recv.code(), x);
                if (v != null) grow(mutLocal, x.key() + ":" + v.java());
            }
            case "mutref" -> { }
            default -> throw new JavaParser.Unsupported("it changes an object through a second name");
        }
    }

    private Var varByRust(String code, Ctx x) {
        String root = code.replaceAll("[\\[.].*$", "");
        for (Map<String, Var> s : x.scopes) for (Var v : s.values()) if (v.rust().equals(root)) return v;
        return null;
    }

    private boolean touchesStatics(Cls c, MethodDecl m) {
        Set<String> seen = new HashSet<>();
        return touches(c, m, seen);
    }

    private boolean touches(Cls c, MethodDecl m, Set<String> seen) {
        if (!seen.add(methodKey(c, m)) || m.body == null) return false;
        boolean[] hit = {false};
        walk(m.body, n -> {
            if (hit[0]) return;
            if (n instanceof Name nm && fieldOwner(nm.id(), c, true) != null) {
                Cls o = fieldOwner(nm.id(), c, true);
                FieldDecl f = o.fields.get(nm.id());
                if (!staticKind(o, f).equals("const") && !staticKind(o, f).equals("log")) hit[0] = true;
            }
            if (n instanceof Field fl && fl.target() instanceof Name tn && classes.containsKey(tn.id())) {
                Cls o = classes.get(tn.id());
                FieldDecl f = o.fields.get(fl.name());
                if (f != null && f.mods.contains("static") && !staticKind(o, f).equals("const") && !staticKind(o, f).equals("log")) hit[0] = true;
            }
            if (n instanceof Call cl) {
                Cls target = cl.target() == null ? c : cl.target() instanceof Name tn && classes.containsKey(tn.id()) ? classes.get(tn.id()) : null;
                if (target != null && target.methods.containsKey(cl.name()))
                    for (MethodDecl md : target.methods.get(cl.name())) if (touches(target, md, seen)) hit[0] = true;
            }
        });
        return hit[0];
    }

    private static boolean varargsFits(MethodDecl m, int n) {
        return !m.params.isEmpty() && m.params.get(m.params.size() - 1).varargs() && n >= m.params.size() - 1;
    }

    private String argList(Cls owner, MethodDecl m, List<Expr> args, Ctx x) {
        List<String> out = new ArrayList<>();
        int fixed = m.params.size();
        boolean va = !m.params.isEmpty() && m.params.get(fixed - 1).varargs();
        for (int i = 0; i < fixed; i++) {
            Param p = m.params.get(i);
            JT pt = paramType(owner, m, i);
            boolean mut = mutParam.contains(methodKey(owner, m) + ":" + i);
            if (pt.is("fn")) {
                out.add(fnArg(args.get(i), pt, x));
                continue;
            }
            if (pt.is("opt") && pt.elem() != null && pt.elem().is("fn")) {
                Expr arg = args.get(i);
                if (isNull(arg)) out.add("None");
                else if (arg instanceof Lambda || arg instanceof MethodRef) out.add("Some(" + fnArg(arg, pt.elem(), x) + ")");
                else {
                    Ex v = ex(arg, x);
                    if (v.type().equals(pt)) out.add(v.code());
                    else if (v.type().equals(pt.elem())) out.add("Some(" + v.code() + ")");
                    else throw new JavaParser.Unsupported("a " + v.type() + " passed as a function");
                }
                continue;
            }
            if (pt.is("opt") && !(va && i == fixed - 1)) {
                // A parameter Java code passes null to or checks for null: an owned Option.
                out.add(valueTo(args.get(i), pt, x).code());
                continue;
            }
            if (implicitGame(pt)) {
                Ex v = ex(args.get(i), x);
                if (!v.type().k().equals(pt.k())) throw new JavaParser.Unsupported("a " + v.type() + " passed as Minecraft's " + javaName(pt));
                if (pt.is("gfx")) requireBatch(x);
                continue;
            }
            if (va && i == fixed - 1) {
                // Varargs: an array passed as is, or the values packed into one.
                if (args.size() == fixed) {
                    try {
                        Ex single = ex(args.get(i), x);
                        if (single.type().equals(pt)) {
                            out.add(arg(single, pt, mut, x));
                            continue;
                        }
                    } catch (JavaParser.Unsupported ignored) {
                        // packed below
                    }
                }
                List<String> items = new ArrayList<>();
                for (int k = i; k < args.size(); k++) items.add(element(args.get(k), pt.elem(), x));
                out.add((mut ? "&mut " : "&") + "[" + String.join(", ", items) + "]");
                continue;
            }
            Ex v = ex(args.get(i), x, pt);
            out.add(arg(v, pt, mut, x));
        }
        return String.join(", ", out);
    }

    /** An array element or a varargs value, owned. */
    private String element(Expr e, JT t, Ctx x) {
        if (e instanceof ArrayInit ai) return arrayInit(ai, t, x).code();
        return valueTo(e, t, x).code();
    }

    /** A value passed to a parameter of type `pt`. */
    private String arg(Ex v, JT pt, boolean mut, Ctx x) {
        if (v.type().is("opt") && !pt.is("opt")) v = unwrapOpt(v);
        if (pt.is("opt")) throw new JavaParser.Unsupported("an Optional or nullable parameter");
        if (pt.is("map") || pt.is("set")) {
            checkAssignable(v.type(), pt);
            if ("staticobj".equals(v.place())) throw new JavaParser.Unsupported("a changing static passed to a method");
            if (mut) {
                markRecv(v, x);
                return "&mut " + paren(v.code());
            }
            return borrowOf(v);
        }
        if (pt.copy()) {
            if (pt.is("enum")) {
                if (!v.type().equals(pt)) throw new JavaParser.Unsupported("a " + v.type() + " passed as a " + pt);
                return v.code();
            }
            if (pt.isBool() != v.type().isBool() || !v.type().copy()) throw new JavaParser.Unsupported("a " + v.type() + " passed as a " + pt);
            return numeric(v, pt).code();
        }
        if (pt.is("string")) {
            if (!v.type().is("string")) throw new JavaParser.Unsupported("a " + v.type() + " passed as a String");
            if (mut) {
                if (v.owned() || "staticobj".equals(v.place()) || "lit".equals(v.place())) throw new JavaParser.Unsupported("a StringBuilder that isn't a variable passed to a method that appends to it");
                markRecv(v, x);
                // A StringBuilder this method was lent: lent on again.
                if ("param".equals(v.place())) return "&mut *" + paren(v.code());
                return "&mut " + paren(v.code());
            }
            return strArg(v);
        }
        if (pt.is("player")) {
            checkAssignable(v.type(), pt);
            return v.owned() ? v.code() : paren(v.code()) + ".clone()";
        }
        if (pt.is("stack")) {
            checkAssignable(v.type(), pt);
            if ("staticobj".equals(v.place()) && !v.code().contains(".with(")) return "&" + v.code() + ".get()";
            return v.owned() ? "&" + paren(v.code()) : borrowOf(v);
        }
        if (pt.is("posestack")) {
            checkAssignable(v.type(), pt);
            markRecv(v, x);
            if ("param".equals(v.place())) return v.code();
            return "&mut " + paren(v.code());
        }
        if (pt.is("item")) {
            checkAssignable(v.type(), pt);
            return "&" + paren(v.code());
        }
        if (pt.is("path")) {
            checkAssignable(v.type(), pt);
            if ("lazy".equals(v.place())) return "&" + v.code();
            return "&" + paren(v.code());
        }
        checkAssignable(v.type(), pt);
        String p = v.place();
        if ("staticobj".equals(p)) throw new JavaParser.Unsupported("a changing static passed to a method");
        if (mut) {
            if (p == null || "lit".equals(p)) return "&mut " + paren(v.code());
            switch (p) {
                case "param" -> {
                    Var var = varByRust(v.code(), x);
                    if (var != null && !v.code().contains("[") && !v.code().contains(".")) {
                        grow(mutParam, x.key() + ":" + var.index());
                        return "&mut *" + v.code();
                    }
                    if (var != null) grow(mutParam, x.key() + ":" + var.index());
                    return "&mut " + v.code();
                }
                case "local" -> {
                    Var var = varByRust(v.code(), x);
                    if (var != null) grow(mutLocal, x.key() + ":" + var.java());
                    return "&mut " + v.code();
                }
                case "field" -> {
                    if (!x.isStatic && !x.ctor) grow(mutSelf, x.key());
                    return "&mut " + v.code();
                }
                default -> throw new JavaParser.Unsupported("an array or object changed through a second name");
            }
        }
        if (p != null && (p.equals("param") || p.equals("iter") || p.equals("ref")) && !v.code().contains("[") && !v.code().contains(".")) return v.code();
        if ("lazy".equals(p)) return "&" + v.code();
        return "&" + paren(v.code());
    }

    private String strArg(Ex v) {
        if ("lit".equals(v.place()) && v.code().startsWith("\"")) return v.code();
        // A for-each's string is already a reference.
        if ("iter".equals(v.place()) && v.type().is("string")) return v.code();
        return "&" + paren(v.code());
    }

    private Ex newObj(New n, Ctx x, JT want) {
        String simple = n.type().simple();
        if (Set.of("ArrayList", "LinkedList").contains(simple)) {
            if (!n.args().isEmpty()) {
                Ex a = ex(n.args().get(0), x);
                if (a.type().numeric()) return new Ex("Vec::with_capacity(" + usize(a) + ")", want != null ? want : new JT("list", "list", null));
                if (a.type().is("list") || a.type().is("array")) return new Ex(paren(a.code()) + ".to_vec()", a.type().is("list") ? a.type() : new JT("list", "list", a.type().elem()));
                if (a.type().is("set") || a.type().is("view") && !a.type().name().equals("entries"))
                    return new Ex(iterOf(a) + ".cloned().collect::<Vec<_>>()", new JT("list", "list", a.type().elem()));
                throw new JavaParser.Unsupported("new " + simple + "(" + a.type() + ")");
            }
            JT t = want != null && want.is("list") ? want : new JT("list", "list", n.type().args().isEmpty() ? null : resolve(n.type().args().get(0), x.cls));
            return new Ex("Vec::new()", t);
        }
        if (Set.of("HashMap", "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet", "ConcurrentHashMap", "ConcurrentSkipListMap", "IdentityHashMap", "WeakHashMap",
                "CopyOnWriteArraySet", "ConcurrentSkipListSet").contains(simple)) {
            boolean map = simple.endsWith("Map"), tree = simple.startsWith("Tree") || simple.contains("SkipList");
            simple = map ? "HashMap" : "HashSet";
            String rt = map ? "JMap" : "JSet", sorted = tree ? "_sorted" : "";
            JT t;
            if (want != null && want.is(map ? "map" : "set")) t = want;
            else if (n.type().args().size() == (map ? 2 : 1)) t = resolve(new TypeRef(simple, n.type().args(), 0), x.cls);
            else throw new JavaParser.Unsupported("new " + simple + "<>() without its types");
            if (!n.args().isEmpty()) {
                Ex a = unwrapOpt(ex(n.args().get(0), x));
                if (a.type().numeric()) return new Ex(rt + "::new" + sorted + "()", t);
                if (a.type().is(map ? "map" : "set")) {
                    checkAssignable(a.type(), t);
                    return new Ex(rt + "::from_iter" + sorted + "(" + paren(a.code()) + ".clone_pairs())", t);
                }
                if (!map && (a.type().is("list") || a.type().is("array") || a.type().is("view")))
                    return new Ex(rt + "::from_iter" + sorted + "(" + iterOf(a) + ".cloned())", t);
                throw new JavaParser.Unsupported("new " + simple + "(" + a.type() + ")");
            }
            return new Ex(rt + "::new" + sorted + "()", t);
        }
        Ex joml = newJoml(simple, n.args(), x);
        if (joml != null) return joml;
        if (gameMode && simple.equals("ItemStackRenderState") && n.args().isEmpty()) return new Ex("JRenderState::default()", RENDERSTATE);
        if (simple.equals("Properties") && n.args().isEmpty()) return new Ex("JProps::new()", PROPS);
        if (simple.equals("JsonObject") && n.args().isEmpty()) return new Ex("JJson::object()", JSON);
        if (simple.equals("JsonArray") && n.args().isEmpty()) return new Ex("JJson::array()", JSON);
        if (simple.equals("JsonPrimitive") && n.args().size() == 1) return new Ex(toJsonValue(ex(n.args().get(0), x)), JSON);
        if ((simple.equals("Gson") || simple.equals("GsonBuilder")) && n.args().isEmpty()) return new Ex("JGson::default()", GSON);
        if (Set.of("AtomicInteger", "AtomicLong", "AtomicBoolean").contains(simple)) {
            JT t = resolve(n.type(), x.cls);
            String v = n.args().isEmpty() ? zero(t.elem()) : valueTo(n.args().get(0), t.elem(), x).code();
            return new Ex("std::sync::atomic::" + t.name() + "::new(" + v + ")", t);
        }
        if (simple.equals("StringBuilder")) {
            if (n.args().isEmpty()) return new Ex("String::new()", JT.STRING);
            Ex a = ex(n.args().get(0), x);
            if (a.type().is("string")) return new Ex(paren(a.code()) + ".to_string()", JT.STRING);
            if (a.type().numeric()) return new Ex("String::with_capacity(" + usize(a) + ")", JT.STRING);
        }
        if (simple.equals("String")) {
            if (n.args().isEmpty()) return new Ex("String::new()", JT.STRING);
            Ex a = ex(n.args().get(0), x);
            if (a.type().is("string")) return new Ex(paren(a.code()) + ".to_string()", JT.STRING);
        }
        Cls c = ownClass(n.type().name(), x.cls);
        if (c == null) c = classes.get(n.type().name());
        if (c == null) throw new JavaParser.Unsupported("it creates " + describe(simple, x.cls));
        if (c.failed != null) throw new JavaParser.Unsupported("it creates a " + c.d.name + ", which isn't translated (" + c.failed + ")");
        List<MethodDecl> ctors = c.methods.getOrDefault("<init>", List.of());
        MethodDecl m = null;
        for (MethodDecl md : ctors) if (md.params.size() == n.args().size()) m = md;
        if (m == null && ctors.isEmpty() && n.args().isEmpty()) return new Ex(c.module + "::" + c.rust + "::new()", new JT("cls", c.d.qualified, null));
        if (m == null) throw new JavaParser.Unsupported("no constructor of " + c.d.name + " takes " + n.args().size() + " values");
        if (failed.containsKey(methodKey(c, m))) throw new JavaParser.Unsupported("it creates a " + c.d.name + " with a constructor that isn't translated");
        return new Ex(c.module + "::" + c.rust + "::" + ctorName(c, m) + "(" + argList(c, m, n.args(), x) + ")", new JT("cls", c.d.qualified, null));
    }

    private Ex newArray(NewArray na, Ctx x) {
        JT t = resolve(na.type(), x.cls);
        if (na.init() != null) return arrayInit(na.init(), t, x);
        if (na.dims().isEmpty()) throw new JavaParser.Unsupported("an array without a size");
        return new Ex(sized(t, na.dims(), 0, x), t);
    }

    private String sized(JT t, List<Expr> dims, int i, Ctx x) {
        if (i >= dims.size()) {
            if (t.is("array")) return "Vec::new()";
            return zero(t);
        }
        String n = usize(ex(dims.get(i), x));
        return "vec![" + sized(t.elem(), dims, i + 1, x) + "; " + n + "]";
    }

    private Ex arrayInit(ArrayInit ai, JT t, Ctx x) {
        if (!t.is("array")) throw new JavaParser.Unsupported("an array initializer for a " + t);
        List<String> items = new ArrayList<>();
        for (Expr e : ai.items()) items.add(element(e, t.elem(), x));
        return new Ex("vec![" + String.join(", ", items) + "]", t);
    }

    // ---- library calls ----

    private Ex extStatic(String cls, Call c, Ctx x) {
        String k = cls + "." + c.name();
        if (gameMode && Set.of("Component.translatable", "Text.translatable", "Component.translatableEscape").contains(k)) return translatable(c, x);
        if (k.equals("CompletableFuture.supplyAsync") && c.args().size() == 1) {
            Fn f = closure(c.args().get(0), List.of(), false, null, x);
            return new Ex("JFuture::supply_async(move " + f.code() + ")", new JT("future", "CompletableFuture", f.ret()));
        }
        List<Ex> a = new ArrayList<>();
        for (Expr e : c.args()) a.add(ex(e, x));
        int n = a.size();
        switch (k) {
            case "IntegerArgumentType.getInteger": case "LongArgumentType.getLong": case "FloatArgumentType.getFloat":
            case "DoubleArgumentType.getDouble": case "BoolArgumentType.getBool": case "StringArgumentType.getString": {
                // An argument of the command the Fabric API layer is running.
                if (!gameMode || n != 2 || !a.get(0).type().is("cmdsrc") || !a.get(1).type().is("string")) return unknown(k, x);
                String getter = switch (c.name()) {
                    case "getInteger" -> "int";
                    case "getLong" -> "long";
                    case "getFloat" -> "float";
                    case "getDouble" -> "double";
                    case "getBool" -> "bool";
                    default -> "string";
                };
                JT t = switch (getter) {
                    case "int" -> JT.INT;
                    case "long" -> JT.LONG;
                    case "float" -> JT.FLOAT;
                    case "double" -> JT.DOUBLE;
                    case "bool" -> JT.BOOL;
                    default -> JT.STRING;
                };
                return new Ex("j_cmd_arg_" + getter + "(" + strArg(a.get(1)) + ")", t, true, null);
            }
            case "Minecraft.getInstance":
                if (gameMode && n == 0) return new Ex("game", GAME, true, "alias");
                return unknown(k, x);
            case "Component.literal": case "Text.literal":
                if (gameMode && n == 1 && a.get(0).type().is("string"))
                    return new Ex("horizon_client::text::Text::literal(" + (a.get(0).owned() ? a.get(0).code() : paren(a.get(0).code()) + ".to_string()") + ")", COMP);
                return unknown(k, x);
            case "Component.empty":
                if (gameMode && n == 0) return new Ex("horizon_client::text::Text::empty()", COMP);
                return unknown(k, x);
            case "Component.translatable": case "Text.translatable": case "Component.translatableEscape":
                if (gameMode) return translatable(c, x);
                return unknown(k, x);
            case "RandomSource.create": case "Random.create":
                return n == 0 ? new Ex("JRandom::new()", RANDOM) : unknown(k, x);
            case "CrossbowItem.isCharged":
                if (gameMode && n == 1 && a.get(0).type().is("stack"))
                    return new Ex(paren(a.get(0).code()) + ".as_ref().is_some_and(|s| s.data().is_some_and(|d| !d.charged.is_empty()))", JT.BOOL);
                return unknown(k, x);
            case "CrossbowItem.getChargeDuration":
                if (gameMode && n >= 1 && a.get(0).type().is("stack"))
                    return new Ex(paren(a.get(0).code()) + ".as_ref().map_or(25, |s| crate::game::Game::charge_ticks(s).max(1) as i32)", JT.INT);
                return unknown(k, x);
            case "HttpClient.newBuilder": case "HttpClient.newHttpClient":
                return n == 0 ? new Ex("JHttpClient::new()", HTTPCLIENT) : unknown(k, x);
            case "HttpRequest.newBuilder":
                return n == 1 && a.get(0).type().is("string") ? new Ex("JHttpRequest::new(" + strArg(a.get(0)) + ")", HTTPREQ) : unknown(k, x);
            case "Duration.ofSeconds": case "Duration.ofMillis": case "Duration.ofMinutes": {
                if (n != 1 || !a.get(0).type().numeric()) return unknown(k, x);
                String v = numeric(a.get(0), JT.LONG).code();
                return new Ex(switch (c.name()) {
                    case "ofSeconds" -> "std::time::Duration::from_secs((" + v + ") as u64)";
                    case "ofMillis" -> "std::time::Duration::from_millis((" + v + ") as u64)";
                    default -> "std::time::Duration::from_secs((" + v + ") as u64 * 60)";
                }, DURATION);
            }
            case "URI.create":
                return n == 1 && a.get(0).type().is("string") ? (a.get(0).owned() ? a.get(0) : new Ex(paren(a.get(0).code()) + ".to_string()", JT.STRING)) : unknown(k, x);
            case "URLEncoder.encode":
                return n >= 1 && a.get(0).type().is("string") ? new Ex("j_url_encode(" + strArg(a.get(0)) + ")", JT.STRING) : unknown(k, x);
            case "BodyHandlers.ofString": case "HttpResponse.BodyHandlers.ofString":
                return new Ex("()", new JT("bodyhandler", "BodyHandlers.ofString", null), true, "lit");
            case "SemanticVersion.parse": case "Version.parse": {
                if (n != 1 || !a.get(0).type().is("string")) return unknown(k, x);
                if (!x.io) throw new JavaParser.Unsupported(k + " outside a try/catch of VersionParsingException");
                return new Ex("JVersion::" + (cls.equals("SemanticVersion") ? "parse_semantic" : "parse") + "(" + strArg(a.get(0)) + ")?", VERSION);
            }
            case "CompletableFuture.delayedExecutor": {
                if (n != 2 || !a.get(0).type().numeric() || !a.get(1).type().is("timeunit")) return unknown(k, x);
                String v = numeric(a.get(0), JT.LONG).code();
                return new Ex("std::time::Duration::from_millis((" + v + ") as u64 * " + a.get(1).code() + ")", DURATION);
            }
            case "Thread.currentThread":
                return n == 0 ? new Ex("()", new JT("thread", "Thread", null), true, "lit") : unknown(k, x);
            case "Thread.sleep":
                return n == 1 && a.get(0).type().numeric() ? new Ex("std::thread::sleep(std::time::Duration::from_millis((" + numeric(a.get(0), JT.LONG).code() + ") as u64))", JT.VOID) : unknown(k, x);
            case "FabricLoader.getInstance":
                return n == 0 ? new Ex("()", new JT("fabric", "FabricLoader", null), true, "lit") : unknown(k, x);
            case "Path.of": case "Paths.get": {
                if (n == 0 || !a.stream().allMatch(e -> e.type().is("string"))) return unknown(k, x);
                StringBuilder b = new StringBuilder("std::path::PathBuf::from(" + strArg(a.get(0)) + ")");
                for (int i = 1; i < n; i++) b.append(".join(").append(strArg(a.get(i))).append(")");
                return new Ex(b.toString(), PATH);
            }
            case "Files.exists": case "Files.isRegularFile": case "Files.isDirectory": case "Files.notExists": {
                if (n < 1 || !a.get(0).type().is("path")) return unknown(k, x);
                String p = paren(a.get(0).code());
                return new Ex(switch (c.name()) {
                    case "exists" -> p + ".exists()";
                    case "notExists" -> "!" + p + ".exists()";
                    case "isRegularFile" -> p + ".is_file()";
                    default -> p + ".is_dir()";
                }, JT.BOOL);
            }
            case "Files.readString": case "Files.readAllLines": case "Files.writeString": case "Files.createDirectories": case "Files.createDirectory":
            case "Files.move": case "Files.copy": case "Files.delete": case "Files.deleteIfExists": case "Files.size":
                return filesCall(c.name(), a, c, x);
            case "Util.getMillis":
                return n == 0 ? new Ex("j_millis()", JT.LONG) : unknown(k, x);
            case "Util.getNanos":
                return n == 0 ? new Ex("j_nanos()", JT.LONG) : unknown(k, x);
            case "Identifier.fromNamespaceAndPath": case "Identifier.of":
                if (n == 2 && a.get(0).type().is("string") && a.get(1).type().is("string"))
                    return new Ex("JIdentifier::new(" + strArg(a.get(0)) + ", " + strArg(a.get(1)) + ")", IDENT);
                if (n == 1 && a.get(0).type().is("string")) return new Ex("JIdentifier::parse(" + strArg(a.get(0)) + ")", IDENT);
                return unknown(k, x);
            case "Identifier.withDefaultNamespace":
                return n == 1 && a.get(0).type().is("string") ? new Ex("JIdentifier::new(\"minecraft\", " + strArg(a.get(0)) + ")", IDENT) : unknown(k, x);
            case "Identifier.tryParse":
                return n == 1 && a.get(0).type().is("string") ? new Ex("JIdentifier::try_parse(" + strArg(a.get(0)) + ")", JT.opt("nullable", IDENT)) : unknown(k, x);
            case "Identifier.tryBuild":
                return n == 2 && a.get(0).type().is("string") && a.get(1).type().is("string")
                        ? new Ex("JIdentifier::try_build(" + strArg(a.get(0)) + ", " + strArg(a.get(1)) + ")", JT.opt("nullable", IDENT)) : unknown(k, x);
            case "Identifier.parse":
                return n == 1 && a.get(0).type().is("string") ? new Ex("JIdentifier::parse(" + strArg(a.get(0)) + ")", IDENT) : unknown(k, x);
            case "Math.abs": case "Mth.abs":
                return n == 1 ? un(a.get(0), ".abs()", promote(a.get(0).type(), JT.INT)) : unknown(k, x);
            case "Math.max": case "Math.min": case "Mth.max": case "Mth.min": {
                if (n != 2) return unknown(k, x);
                JT t = promote(a.get(0).type(), a.get(1).type());
                return new Ex(recv(numeric(a.get(0), t)) + "." + c.name() + "(" + numeric(a.get(1), t).code() + ")", t);
            }
            case "Math.clamp": case "Mth.clamp": {
                if (n != 3) return unknown(k, x);
                JT t = promote(promote(a.get(0).type(), a.get(1).type()), a.get(2).type());
                if (k.equals("Math.clamp") && !a.get(0).type().floating() && t.equals(JT.LONG) && !a.get(1).type().equals(JT.LONG)) t = JT.INT;
                return new Ex("j_clamp(" + numeric(a.get(0), t).code() + ", " + numeric(a.get(1), t).code() + ", " + numeric(a.get(2), t).code() + ")", t);
            }
            case "Math.sqrt": case "Math.sin": case "Math.cos": case "Math.tan": case "Math.asin": case "Math.acos": case "Math.atan":
            case "Math.exp": case "Math.floor": case "Math.ceil": case "Math.cbrt": case "Math.log10": case "Math.signum": case "Math.toRadians": case "Math.toDegrees": {
                if (n != 1) return unknown(k, x);
                String fn = switch (c.name()) {
                    case "log10" -> "log10";
                    case "toRadians" -> "to_radians";
                    case "toDegrees" -> "to_degrees";
                    default -> c.name();
                };
                JT t = c.name().equals("signum") && a.get(0).type().equals(JT.FLOAT) ? JT.FLOAT : JT.DOUBLE;
                if (c.name().equals("signum")) return new Ex((t.equals(JT.FLOAT) ? "j_signum_f(" : "j_signum_d(") + numeric(a.get(0), t).code() + ")", t);
                return un(numeric(a.get(0), t), "." + fn + "()", t);
            }
            case "Math.log":
                return n == 1 ? un(numeric(a.get(0), JT.DOUBLE), ".ln()", JT.DOUBLE) : unknown(k, x);
            case "Math.pow":
                return n == 2 ? new Ex(paren(numeric(a.get(0), JT.DOUBLE).code()) + ".powf(" + numeric(a.get(1), JT.DOUBLE).code() + ")", JT.DOUBLE) : unknown(k, x);
            case "Math.atan2": case "Mth.atan2":
                return n == 2 ? new Ex(paren(numeric(a.get(0), JT.DOUBLE).code()) + ".atan2(" + numeric(a.get(1), JT.DOUBLE).code() + ")", JT.DOUBLE) : unknown(k, x);
            case "Math.hypot":
                return n == 2 ? new Ex(paren(numeric(a.get(0), JT.DOUBLE).code()) + ".hypot(" + numeric(a.get(1), JT.DOUBLE).code() + ")", JT.DOUBLE) : unknown(k, x);
            case "Math.round": {
                if (n != 1) return unknown(k, x);
                if (a.get(0).type().equals(JT.FLOAT) || !a.get(0).type().floating() && !a.get(0).type().equals(JT.LONG))
                    return new Ex("j_round_f(" + numeric(a.get(0), JT.FLOAT).code() + ")", JT.INT);
                return new Ex("j_round_d(" + numeric(a.get(0), JT.DOUBLE).code() + ")", JT.LONG);
            }
            case "Math.floorMod": case "Math.floorDiv": {
                if (n != 2) return unknown(k, x);
                JT t = promote(a.get(0).type(), a.get(1).type());
                if (t.floating()) return unknown(k, x);
                return new Ex(paren(numeric(a.get(0), t).code()) + (c.name().equals("floorMod") ? ".rem_euclid(" : ".div_euclid(") + numeric(a.get(1), t).code() + ")", t);
            }
            case "Math.random":
                throw new JavaParser.Unsupported("Math.random (no random numbers without a crate)");
            case "Mth.sin": case "Mth.cos": case "Mth.sqrt":
                return n == 1 ? un(numeric(a.get(0), JT.FLOAT), "." + c.name() + "()", JT.FLOAT) : unknown(k, x);
            case "Mth.floor": case "Mth.ceil":
                return n == 1 ? new Ex(paren(numeric(a.get(0), JT.DOUBLE).code()) + "." + c.name() + "() as i32", JT.INT) : unknown(k, x);
            case "Mth.lerp": {
                if (n != 3) return unknown(k, x);
                JT t = promote(promote(a.get(0).type(), a.get(1).type()), a.get(2).type());
                if (!t.floating()) t = JT.FLOAT;
                return new Ex("j_lerp(" + numeric(a.get(0), t).code() + ", " + numeric(a.get(1), t).code() + ", " + numeric(a.get(2), t).code() + ")", t);
            }
            case "Mth.square": {
                if (n != 1) return unknown(k, x);
                JT t = promote(a.get(0).type(), JT.INT);
                String v = paren(numeric(a.get(0), t).code());
                return new Ex(v + " * " + v, t);
            }
            case "Mth.wrapDegrees": {
                if (n != 1) return unknown(k, x);
                JT t = a.get(0).type().floating() ? a.get(0).type() : JT.INT;
                return new Ex("j_wrap_degrees(" + numeric(a.get(0), JT.DOUBLE).code() + ") as " + rustPrim(t.name()), t);
            }
            case "Mth.frac":
                return n == 1 ? new Ex("j_frac(" + numeric(a.get(0), JT.DOUBLE).code() + ") as " + rustPrim(a.get(0).type().equals(JT.FLOAT) ? "float" : "double"),
                        a.get(0).type().equals(JT.FLOAT) ? JT.FLOAT : JT.DOUBLE) : unknown(k, x);
            case "Mth.clampedLerp": {
                if (n != 3) return unknown(k, x);
                JT t = JT.FLOAT;
                for (Ex e : a) t = promote(t, e.type());
                return new Ex("j_lerp(j_clamp(" + numeric(a.get(0), t).code() + ", 0.0, 1.0), " + numeric(a.get(1), t).code() + ", " + numeric(a.get(2), t).code() + ")", t);
            }
            case "Float.compare": case "Double.compare": case "Integer.compare": case "Long.compare": {
                if (n != 2) return unknown(k, x);
                JT t = promote(a.get(0).type(), a.get(1).type());
                if (t.floating()) return new Ex("j_compare_f(" + numeric(a.get(0), JT.DOUBLE).code() + ", " + numeric(a.get(1), JT.DOUBLE).code() + ")", JT.INT);
                return new Ex("(" + paren(numeric(a.get(0), t).code()) + ".cmp(&" + paren(numeric(a.get(1), t).code()) + ") as i32)", JT.INT);
            }
            case "Float.isNaN": case "Double.isNaN":
                return n == 1 ? un(a.get(0), ".is_nan()", JT.BOOL) : unknown(k, x);
            case "Float.isInfinite": case "Double.isInfinite":
                return n == 1 ? un(a.get(0), ".is_infinite()", JT.BOOL) : unknown(k, x);
            case "Float.isFinite": case "Double.isFinite":
                return n == 1 ? un(a.get(0), ".is_finite()", JT.BOOL) : unknown(k, x);
            case "Boolean.parseBoolean":
                return n == 1 && a.get(0).type().is("string") ? new Ex("j_parse_bool(" + strArg(a.get(0)) + ")", JT.BOOL) : unknown(k, x);
            case "Boolean.toString": case "Integer.toString": case "Long.toString": case "Float.toString": case "Double.toString": case "String.valueOf":
                if (n != 1 || !(a.get(0).type().is("prim") || a.get(0).type().is("string"))) return unknown(k, x);
                return stringOf(a.get(0)).type().is("string") && a.get(0).type().is("string") ? new Ex(paren(a.get(0).code()) + ".to_string()", JT.STRING) : stringOf(a.get(0));
            case "Character.isDigit":
                return n == 1 ? un(a.get(0), ".is_ascii_digit()", JT.BOOL) : unknown(k, x);
            case "Character.isLetter":
                return n == 1 ? un(a.get(0), ".is_alphabetic()", JT.BOOL) : unknown(k, x);
            case "Character.isLetterOrDigit":
                return n == 1 ? un(a.get(0), ".is_alphanumeric()", JT.BOOL) : unknown(k, x);
            case "Character.isWhitespace":
                return n == 1 ? un(a.get(0), ".is_whitespace()", JT.BOOL) : unknown(k, x);
            case "Character.isUpperCase":
                return n == 1 ? un(a.get(0), ".is_uppercase()", JT.BOOL) : unknown(k, x);
            case "Character.isLowerCase":
                return n == 1 ? un(a.get(0), ".is_lowercase()", JT.BOOL) : unknown(k, x);
            case "Character.toUpperCase":
                return n == 1 && a.get(0).type().equals(JT.CHAR) ? un(a.get(0), ".to_ascii_uppercase()", JT.CHAR) : unknown(k, x);
            case "Character.toLowerCase":
                return n == 1 && a.get(0).type().equals(JT.CHAR) ? un(a.get(0), ".to_ascii_lowercase()", JT.CHAR) : unknown(k, x);
            case "String.format": {
                if (n < 1 || !(c.args().get(0) instanceof Lit fl) || !fl.kind().equals("string")) return unknown(k, x);
                String f = fl.text().substring(1, fl.text().length() - 1);
                StringBuilder out = new StringBuilder();
                List<String> args = new ArrayList<>();
                int next = 1;
                java.util.regex.Matcher fm = FORMAT.matcher(f);
                for (int i = 0; i < f.length(); i++) {
                    char ch = f.charAt(i);
                    if (ch == '{' || ch == '}') { out.append(ch).append(ch); continue; }
                    if (ch != '%') { out.append(ch); continue; }
                    if (!fm.find(i) || fm.start() != i) throw new JavaParser.Unsupported("String.format with `" + f.substring(i, Math.min(f.length(), i + 4)) + "`");
                    String conv = fm.group(4);
                    String plus = fm.group(1);
                    i = fm.end() - 1;
                    if (conv.equals("%")) { out.append('%'); continue; }
                    if (conv.equals("n")) { out.append("\\n"); continue; }
                    if (next >= n) throw new JavaParser.Unsupported("String.format with too few values");
                    Ex v = a.get(next++);
                    if (!plus.isEmpty() && !conv.equals("d") && !conv.equals("f")) throw new JavaParser.Unsupported("String.format with `%+" + conv + "`");
                    switch (conv) {
                        case "d" -> {
                            if (!v.type().numeric() || v.type().floating()) throw new JavaParser.Unsupported("String.format %d of a " + v.type());
                            out.append("{" + (plus.isEmpty() ? "" : ":+") + "}");
                            args.add(v.code());
                        }
                        case "x" -> {
                            if (!v.type().numeric() || v.type().floating()) throw new JavaParser.Unsupported("String.format %x of a " + v.type());
                            out.append("{:x}");
                            args.add(v.code());
                        }
                        case "f" -> {
                            if (!v.type().numeric()) throw new JavaParser.Unsupported("String.format %f of a " + v.type());
                            out.append("{:").append(plus).append('.').append(fm.group(3) != null ? fm.group(3) : "6").append('}');
                            args.add(numeric(v, JT.DOUBLE).code());
                        }
                        default -> {
                            out.append("{}");
                            args.add(fmtArg(v));
                        }
                    }
                }
                if (next != n) throw new JavaParser.Unsupported("String.format with extra values");
                return new Ex("format!(\"" + escapes(out.toString(), '"') + "\"" + (args.isEmpty() ? "" : ", " + String.join(", ", args)) + ")", JT.STRING);
            }
            case "System.getProperty":
                // A Java system property (-Dname=value); here, an environment variable of that name.
                if (n == 1 && a.get(0).type().is("string")) return new Ex("std::env::var(" + strArg(a.get(0)) + ").ok()", JT.opt("nullable", JT.STRING));
                if (n == 2 && a.get(0).type().is("string") && a.get(1).type().is("string"))
                    return new Ex("std::env::var(" + strArg(a.get(0)) + ").unwrap_or_else(|_| " + strArg(a.get(1)) + ".to_string())", JT.STRING);
                return unknown(k, x);
            case "Boolean.getBoolean":
                // A Java system property (-Dname=true); here, an environment variable of that name.
                return n == 1 && a.get(0).type().is("string")
                        ? new Ex("std::env::var(" + strArg(a.get(0)) + ").is_ok_and(|v| v.eq_ignore_ascii_case(\"true\"))", JT.BOOL) : unknown(k, x);
            case "System.currentTimeMillis":
                return new Ex("j_millis()", JT.LONG);
            case "System.nanoTime":
                return new Ex("j_nanos()", JT.LONG);
            case "System.arraycopy": {
                if (n != 5) return unknown(k, x);
                markWrite(c.args().get(2), x);
                String sp = usize(a.get(1)), dp = usize(a.get(3)), len = usize(a.get(4));
                if (a.get(2).type().is("opt")) throw new JavaParser.Unsupported("System.arraycopy into an array that may be null");
                if ("staticobj".equals(a.get(0).place()) || "staticobj".equals(a.get(2).place())) throw new JavaParser.Unsupported("System.arraycopy on an array kept in a changing static");
                String src = paren(unwrapOpt(a.get(0)).code()), dst = paren(a.get(2).code());
                return new Ex(dst + "[" + dp + ".." + dp + " + " + len + "].copy_from_slice(&" + src + "[" + sp + ".." + sp + " + " + len + "])", JT.VOID);
            }
            case "System.lineSeparator":
                return new Ex("\"\\n\"", JT.STRING, false, "lit");
            case "Arrays.fill": {
                if (n != 2) return unknown(k, x);
                markWrite(c.args().get(0), x);
                if (!a.get(0).type().is("array")) return unknown(k, x);
                String fillWith = valueTo(c.args().get(1), a.get(0).type().elem(), x).code();
                if ("staticobj".equals(a.get(0).place())) {
                    markRecv(a.get(0), x);
                    return locked(a.get(0), List.of(fillWith), ".fill(a_0)", JT.VOID);
                }
                return new Ex(paren(a.get(0).code()) + ".fill(" + fillWith + ")", JT.VOID);
            }
            case "Arrays.copyOf": {
                if (n != 2 || !a.get(0).type().is("array")) return unknown(k, x);
                return new Ex("j_copy_of(&" + paren(a.get(0).code()) + ", " + usize(a.get(1)) + ")", a.get(0).type());
            }
            case "String.join": {
                if (n < 1 || !a.get(0).type().is("string")) return unknown(k, x);
                String sep = strArg(a.get(0));
                if (n == 2 && a.get(1).type().is("opt")) a.set(1, unwrapOpt(a.get(1)));
                if (n == 2 && (a.get(1).type().is("list") || a.get(1).type().is("array") || a.get(1).type().is("set") || a.get(1).type().is("view"))
                        && a.get(1).type().elem() != null && a.get(1).type().elem().is("string")) {
                    Ex l = a.get(1);
                    if ("staticobj".equals(l.place())) return new Ex(withRead(l, ".iter().map(|s| s.as_str()).collect::<Vec<&str>>().join(" + sep + ")"), JT.STRING);
                    return new Ex(iterOf(l) + ".map(|s| s.as_str()).collect::<Vec<&str>>().join(" + sep + ")", JT.STRING);
                }
                List<String> parts = new ArrayList<>();
                for (int i = 1; i < n; i++) {
                    if (!a.get(i).type().is("string")) return unknown(k, x);
                    parts.add(strArg(a.get(i)));
                }
                return new Ex("[" + String.join(", ", parts) + "].join(" + sep + ")", JT.STRING);
            }
            case "Arrays.copyOfRange": {
                if (n != 3 || !(a.get(0).type().is("array") || a.get(0).type().is("list"))) return unknown(k, x);
                return new Ex("j_copy_of_range(&" + paren(a.get(0).code()) + ", " + usize(a.get(1)) + ", " + usize(a.get(2)) + ")", new JT("array", "array", a.get(0).type().elem()));
            }
            case "List.copyOf": case "Set.copyOf": case "Collections.unmodifiableList": case "Collections.unmodifiableSet":
            case "Collections.unmodifiableMap": case "Map.copyOf": {
                if (n != 1) return unknown(k, x);
                Ex v = a.get(0);
                String want = k.contains("List") ? "list" : k.contains("Set") ? "set" : "map";
                if (v.type().is(want) || want.equals("list") && v.type().is("array")) {
                    JT rt = want.equals("list") ? new JT("list", "list", v.type().elem()) : v.type();
                    if ("staticobj".equals(v.place())) return new Ex(withRead(v, want.equals("list") ? ".to_vec()" : ".clone()"), rt);
                    return new Ex(paren(v.code()) + (want.equals("list") ? ".to_vec()" : ".clone()"), rt);
                }
                if (want.equals("set") && (v.type().is("list") || v.type().is("array") || v.type().is("view")) && v.type().elem() != null) {
                    hashKey(v.type().elem());
                    if ("staticobj".equals(v.place())) return new Ex("JSet::from_iter(" + withRead(v, ".clone()") + ")", new JT("set", "set", v.type().elem()));
                    return new Ex("JSet::from_iter(" + iterOf(v) + ".cloned())", new JT("set", "set", v.type().elem()));
                }
                if (want.equals("list") && (v.type().is("set") || v.type().is("view")) && v.type().elem() != null) {
                    if ("staticobj".equals(v.place())) return new Ex(withRead(v, ".iter().cloned().collect::<Vec<_>>()"), new JT("list", "list", v.type().elem()));
                    return new Ex(iterOf(v) + ".cloned().collect::<Vec<_>>()", new JT("list", "list", v.type().elem()));
                }
                return unknown(k, x);
            }
            case "Collections.disjoint": {
                if (n != 2) return unknown(k, x);
                for (Ex v : a) if ("staticobj".equals(v.place()) || v.type().elem() == null || !(v.type().is("list") || v.type().is("set") || v.type().is("array"))) return unknown(k, x);
                return new Ex("!" + iterOf(a.get(0)) + ".any(|e| " + paren(a.get(1).code()) + ".contains(e))", JT.BOOL);
            }
            case "Pattern.compile": {
                if (n == 1 && a.get(0).type().is("string")) return new Ex("JRegex::new(" + strArg(a.get(0)) + ")", REGEX);
                if (n == 2 && a.get(0).type().is("string") && a.get(1).type().numeric())
                    return new Ex("JRegex::with_flags(" + strArg(a.get(0)) + ", " + numeric(a.get(1), JT.INT).code() + ")", REGEX);
                return unknown(k, x);
            }
            case "JsonParser.parseString": case "JsonParser.parseReader":
                if (n == 1 && a.get(0).type().is("string")) return new Ex("JJson::parse(" + strArg(a.get(0)) + ")", JSON);
                return unknown(k, x);
            case "Pattern.matches":
                if (n == 2 && a.get(0).type().is("string") && a.get(1).type().is("string"))
                    return new Ex("JRegex::new(" + strArg(a.get(0)) + ").matches(" + strArg(a.get(1)) + ")", JT.BOOL);
                return unknown(k, x);
            case "Pattern.quote":
                if (n == 1 && a.get(0).type().is("string")) return new Ex("format!(\"\\\\Q{}\\\\E\", " + strArg(a.get(0)) + ")", JT.STRING);
                return unknown(k, x);
            case "Arrays.stream": {
                if (n != 1 || !(a.get(0).type().is("array") || a.get(0).type().is("list"))) return unknown(k, x);
                JT et = a.get(0).type().elem();
                if ("staticobj".equals(a.get(0).place())) return new Ex(withRead(a.get(0), ".clone()") + ".into_iter()", new JT("stream", "Stream", et));
                return new Ex(paren(a.get(0).code()) + ".iter().cloned()", new JT("stream", "Stream", et));
            }
            case "Stream.of": {
                if (n == 0) return unknown(k, x);
                JT et = a.get(0).type().is("opt") ? a.get(0).type().elem() : a.get(0).type();
                List<String> items = new ArrayList<>();
                for (Expr e : c.args()) items.add(valueTo(e, et, x).code());
                return new Ex("vec![" + String.join(", ", items) + "].into_iter()", new JT("stream", "Stream", et));
            }
            case "IntStream.range": case "IntStream.rangeClosed": {
                if (n != 2) return unknown(k, x);
                String op = k.endsWith("Closed") ? "..=" : "..";
                return new Ex("(" + numeric(a.get(0), JT.INT).code() + op + numeric(a.get(1), JT.INT).code() + ")", new JT("stream", "IntStream", JT.INT));
            }
            case "Comparator.naturalOrder": return new Ex("natural", new JT("comparator", "natural", null), true, "cmp");
            case "Comparator.reverseOrder": return new Ex("reverse", new JT("comparator", "reverse", null), true, "cmp");
            case "Collections.sort": {
                if (n < 1 || n > 2 || !a.get(0).type().is("list") || a.get(0).type().elem() == null) return unknown(k, x);
                markWrite(c.args().get(0), x);
                String cmp = n == 1 || isNull(c.args().get(1)) ? naturalOrder(a.get(0).type().elem()) : comparator(c.args().get(1), a.get(0).type().elem(), x);
                return new Ex(paren(a.get(0).code()) + ".sort_by(" + cmp + ")", JT.VOID);
            }
            case "Collections.reverse": {
                if (n != 1 || !a.get(0).type().is("list")) return unknown(k, x);
                markWrite(c.args().get(0), x);
                return new Ex(paren(a.get(0).code()) + ".reverse()", JT.VOID);
            }
            case "Collections.emptyList": return new Ex("Vec::new()", new JT("list", "list", null));
            case "Collections.emptySet": return new Ex("JSet::new()", new JT("set", "set", null));
            case "Collections.emptyMap": return new Ex("JMap::new()", new JT("map", "map", null, null));
            case "ConcurrentHashMap.newKeySet": return new Ex("JSet::new()", new JT("set", "set", null));
            case "Objects.equals":
                if (n == 2 && a.get(0).type().is("string") && a.get(1).type().is("string")) return new Ex("j_eq(" + strArg(a.get(0)) + ", " + strArg(a.get(1)) + ")", JT.BOOL);
                return unknown(k, x);
            case "List.of": case "Arrays.asList": {
                if (n == 0) return new Ex("Vec::new()", new JT("list", "list", null));
                if (n == 1 && a.get(0).type().is("array")) {
                    // varargs given an array: its elements
                    if ("staticobj".equals(a.get(0).place())) return new Ex(withRead(a.get(0), ".clone()"), new JT("list", "list", a.get(0).type().elem()));
                    return new Ex(a.get(0).owned() ? a.get(0).code() : paren(a.get(0).code()) + ".to_vec()", new JT("list", "list", a.get(0).type().elem()));
                }
                // List.of(null) throws in Java: the elements aren't nullable.
                JT t = a.get(0).type().is("opt") && a.get(0).type().elem() != null ? a.get(0).type().elem() : a.get(0).type();
                List<String> items = new ArrayList<>();
                for (Expr e : c.args()) items.add(valueTo(e, t, x).code());
                return new Ex("vec![" + String.join(", ", items) + "]", new JT("list", "list", t));
            }
            case "Set.of": case "EnumSet.of": {
                if (n == 0) return new Ex("JSet::new()", new JT("set", "set", null));
                JT t = a.get(0).type().is("opt") && a.get(0).type().elem() != null ? a.get(0).type().elem() : a.get(0).type();
                hashKey(t);
                List<String> items = new ArrayList<>();
                for (Expr e : c.args()) items.add(valueTo(e, t, x).code());
                return new Ex("JSet::from_iter([" + String.join(", ", items) + "])", new JT("set", "set", t));
            }
            case "Map.of": {
                if (n % 2 != 0) return unknown(k, x);
                if (n == 0) return new Ex("JMap::new()", new JT("map", "map", null, null));
                JT kt = a.get(0).type(), vt = a.get(1).type();
                hashKey(kt);
                List<String> items = new ArrayList<>();
                for (int i = 0; i < n; i += 2) items.add("(" + valueTo(c.args().get(i), kt, x).code() + ", " + valueTo(c.args().get(i + 1), vt, x).code() + ")");
                return new Ex("JMap::from_iter([" + String.join(", ", items) + "])", new JT("map", "map", vt, kt));
            }
            default:
                return unknown(k, x);
        }
    }

    private static final java.util.regex.Pattern FORMAT = java.util.regex.Pattern.compile("%([+]?)(\\.(\\d+))?([dsfnx%])");

    private Ex unknown(String k, Ctx x) {
        String cls = k.substring(0, k.indexOf('.'));
        throw new JavaParser.Unsupported("it calls " + describe(cls, x.cls) + "." + k.substring(k.indexOf('.') + 1));
    }

    private static Ex un(Ex v, String suffix, JT t) {
        return new Ex(recv(v) + suffix, t);
    }

    /** A method receiver: a bare literal needs its type (`1i32.max(..)`). */
    private static String recv(Ex v) {
        if (v.code().matches("-?\\d+")) return "(" + v.code() + rustPrim(v.type().name()) + ")";
        if (v.code().matches("-?\\d+\\.\\d+(e-?\\d+)?")) return "(" + v.code() + rustPrim(v.type().name()) + ")";
        return paren(v.code());
    }

    private Ex stringCall(Ex s, Call c, Ctx x) {
        List<Ex> a = new ArrayList<>();
        for (Expr e : c.args()) a.add(ex(e, x));
        int n = a.size();
        String r = paren(s.code());
        String sa = strArg(s);
        switch (c.name() + "/" + n) {
            case "length/0": return new Ex("j_len(" + sa + ")", JT.INT);
            case "isEmpty/0": return new Ex(r + ".is_empty()", JT.BOOL);
            case "isBlank/0": return new Ex(r + ".trim().is_empty()", JT.BOOL);
            case "trim/0": case "strip/0": return new Ex(r + ".trim()", JT.STRING, false, placeOf(s).equals("lit") ? "lit" : "borrow");
            case "toLowerCase/0": case "toLowerCase/1": return new Ex(r + ".to_lowercase()", JT.STRING);
            case "toUpperCase/0": case "toUpperCase/1": return new Ex(r + ".to_uppercase()", JT.STRING);
            case "equals/1": case "contentEquals/1":
                return a.get(0).type().is("string") ? new Ex("j_eq(" + sa + ", " + strArg(a.get(0)) + ")", JT.BOOL) : unknownStr(c);
            case "equalsIgnoreCase/1":
                return a.get(0).type().is("string") ? new Ex("j_eq_ignore_case(" + sa + ", " + strArg(a.get(0)) + ")", JT.BOOL) : unknownStr(c);
            case "contains/1":
                return a.get(0).type().is("string") ? new Ex(r + ".contains(" + strArg(a.get(0)) + ")", JT.BOOL) : unknownStr(c);
            case "startsWith/1":
                return a.get(0).type().is("string") ? new Ex(r + ".starts_with(" + strArg(a.get(0)) + ")", JT.BOOL) : unknownStr(c);
            case "endsWith/1":
                return a.get(0).type().is("string") ? new Ex(r + ".ends_with(" + strArg(a.get(0)) + ")", JT.BOOL) : unknownStr(c);
            case "indexOf/1":
                if (a.get(0).type().is("string")) return new Ex("j_index_of(" + sa + ", " + strArg(a.get(0)) + ")", JT.INT);
                if (a.get(0).type().equals(JT.CHAR)) return new Ex("j_index_of_char(" + sa + ", " + a.get(0).code() + ")", JT.INT);
                if (a.get(0).type().equals(JT.INT)) return new Ex("j_index_of_char(" + sa + ", " + numeric(a.get(0), JT.CHAR).code() + ")", JT.INT);
                return unknownStr(c);
            case "lastIndexOf/1":
                if (a.get(0).type().is("string")) return new Ex("j_last_index_of(" + sa + ", " + strArg(a.get(0)) + ")", JT.INT);
                return unknownStr(c);
            case "charAt/1": return new Ex("j_char_at(" + sa + ", " + numeric(a.get(0), JT.INT).code() + ")", JT.CHAR);
            case "substring/1": return new Ex("j_substring(" + sa + ", " + numeric(a.get(0), JT.INT).code() + ", None)", JT.STRING);
            case "substring/2": return new Ex("j_substring(" + sa + ", " + numeric(a.get(0), JT.INT).code() + ", Some(" + numeric(a.get(1), JT.INT).code() + "))", JT.STRING);
            case "replace/2":
                if (a.get(0).type().is("string") && a.get(1).type().is("string")) return new Ex(r + ".replace(" + strArg(a.get(0)) + ", " + strArg(a.get(1)) + ")", JT.STRING);
                if (a.get(0).type().equals(JT.CHAR) && a.get(1).type().equals(JT.CHAR)) return new Ex(r + ".replace(" + a.get(0).code() + ", &" + a.get(1).code() + ".to_string())", JT.STRING);
                return unknownStr(c);
            case "repeat/1": return new Ex(r + ".repeat(" + usize(a.get(0)) + ")", JT.STRING);
            case "concat/1": return new Ex("format!(\"{}{}\", " + s.code() + ", " + a.get(0).code() + ")", JT.STRING);
            case "toString/0": return s;
            case "write/1":
                if (!a.get(0).type().is("string")) return unknownStr(c);
                markRecv(s, x);
                return new Ex(r + ".push_str(" + strArg(a.get(0)) + ")", JT.VOID);
            case "newLine/0":
                markRecv(s, x);
                return new Ex(r + ".push('\\n')", JT.VOID);
            case "setLength/1":
                if (c.args().get(0) instanceof Lit z && z.text().equals("0")) {
                    markRecv(s, x);
                    return new Ex(r + ".clear()", JT.VOID);
                }
                return unknownStr(c);
            case "append/1":
                throw new JavaParser.Unsupported("StringBuilder.append used as a value");
            case "split/1": {
                if (c.args().get(0) instanceof Lit l && l.kind().equals("string")) {
                    String lit = l.text().substring(1, l.text().length() - 1);
                    if (lit.matches("[^\\\\.\\[\\]{}()*+?^$|]+") || lit.matches("\\\\\\\\[.|]")) {
                        String plain = lit.startsWith("\\\\") ? lit.substring(2) : lit;
                        return new Ex("j_split(" + sa + ", \"" + escapes(plain, '"') + "\")", new JT("array", "array", JT.STRING));
                    }
                }
                if (!a.get(0).type().is("string")) return unknownStr(c);
                return new Ex(regexOf(c.args().get(0), a.get(0)) + ".split(" + sa + ", 0)", new JT("array", "array", JT.STRING));
            }
            case "split/2": {
                if (c.args().get(0) instanceof Lit l && l.kind().equals("string") && a.get(1).type().numeric()) {
                    String lit = l.text().substring(1, l.text().length() - 1);
                    if (lit.matches("[^\\\\.\\[\\]{}()*+?^$|]+") || lit.matches("\\\\\\\\[.|]")) {
                        String plain = lit.startsWith("\\\\") ? lit.substring(2) : lit;
                        return new Ex("j_split_limit(" + sa + ", \"" + escapes(plain, '"') + "\", " + numeric(a.get(1), JT.INT).code() + ")", new JT("array", "array", JT.STRING));
                    }
                }
                if (!a.get(0).type().is("string") || !a.get(1).type().numeric()) return unknownStr(c);
                return new Ex(regexOf(c.args().get(0), a.get(0)) + ".split(" + sa + ", " + numeric(a.get(1), JT.INT).code() + ")", new JT("array", "array", JT.STRING));
            }
            case "matches/1":
                if (!a.get(0).type().is("string")) return unknownStr(c);
                return new Ex(regexOf(c.args().get(0), a.get(0)) + ".matches(" + sa + ")", JT.BOOL);
            case "replaceAll/2": case "replaceFirst/2":
                if (!a.get(0).type().is("string") || !a.get(1).type().is("string")) return unknownStr(c);
                return new Ex(regexOf(c.args().get(0), a.get(0)) + (c.name().equals("replaceAll") ? ".replace_all(" : ".replace_first(") + sa + ", " + strArg(a.get(1)) + ")", JT.STRING);
            case "hashCode/0":
                return new Ex("j_hash(" + sa + ")", JT.INT);
            case "compareTo/1":
                return a.get(0).type().is("string") ? new Ex("j_compare(" + sa + ", " + strArg(a.get(0)) + ")", JT.INT) : unknownStr(c);
            case "compareToIgnoreCase/1":
                return a.get(0).type().is("string") ? new Ex("j_compare(&" + r + ".to_lowercase(), &" + paren(a.get(0).code()) + ".to_lowercase())", JT.INT) : unknownStr(c);
            case "regionMatches/5":
                if (!a.get(2).type().is("string") || !a.get(0).type().isBool()) return unknownStr(c);
                return new Ex("j_region_matches(" + sa + ", " + a.get(0).code() + ", " + numeric(a.get(1), JT.INT).code() + ", " + strArg(a.get(2)) + ", "
                        + numeric(a.get(3), JT.INT).code() + ", " + numeric(a.get(4), JT.INT).code() + ")", JT.BOOL);
            case "regionMatches/4":
                if (!a.get(1).type().is("string")) return unknownStr(c);
                return new Ex("j_region_matches(" + sa + ", false, " + numeric(a.get(0), JT.INT).code() + ", " + strArg(a.get(1)) + ", "
                        + numeric(a.get(2), JT.INT).code() + ", " + numeric(a.get(3), JT.INT).code() + ")", JT.BOOL);
            case "chars/0":
                return new Ex(r + ".chars().map(|ch| ch as i32).collect::<Vec<i32>>().into_iter()", new JT("stream", "IntStream", JT.INT));
            case "lines/0":
                return new Ex(r + ".lines().map(str::to_string).collect::<Vec<String>>().into_iter()", new JT("stream", "Stream", JT.STRING));
            case "toCharArray/0":
                return new Ex(r + ".chars().collect::<Vec<char>>()", new JT("array", "array", JT.CHAR));
            default:
                return unknownStr(c);
        }
    }

    /** A regex given as a String: a literal is compiled once (a LazyLock), anything else on each call. */
    private String regexOf(Expr e, Ex v) {
        if (e instanceof Lit l && l.kind().equals("string")) {
            String lit = escapes(l.text().substring(1, l.text().length() - 1), '"');
            return "{ static RE: LazyLock<JRegex> = LazyLock::new(|| JRegex::new(\"" + lit + "\")); &*RE }";
        }
        return "JRegex::new(" + strArg(v) + ")";
    }

    private Ex regexCall(Ex re, Call c, Ctx x) {
        String r = re.code().startsWith("JRegex::") ? paren(re.code()) : "lazy".equals(re.place()) ? "(&" + re.code() + ")" : "(&*" + paren(re.code()) + ")";
        List<Ex> a = new ArrayList<>();
        for (Expr e : c.args()) a.add(unwrapOpt(ex(e, x)));
        switch (c.name() + "/" + a.size()) {
            case "matcher/1":
                if (!a.get(0).type().is("string")) throw new JavaParser.Unsupported("Pattern.matcher of a " + a.get(0).type());
                return new Ex("JMatcher::new(" + r + ", " + strArg(a.get(0)) + ")", MATCHER);
            case "split/1": return new Ex(r + ".split(" + strArg(a.get(0)) + ", 0)", new JT("array", "array", JT.STRING));
            case "split/2": return new Ex(r + ".split(" + strArg(a.get(0)) + ", " + numeric(a.get(1), JT.INT).code() + ")", new JT("array", "array", JT.STRING));
            default: throw new JavaParser.Unsupported("Pattern." + c.name());
        }
    }

    /** A Java value as a JSON primitive (JsonPrimitive / addProperty). */
    private String toJsonValue(Ex v) {
        v = unwrapOpt(v);
        JT t = v.type();
        if (t.is("string")) return "JJson::str(" + strArg(v) + ")";
        if (t.is("json")) return v.owned() ? v.code() : paren(v.code()) + ".clone()";
        if (t.isBool()) return "JJson::Bool(" + v.code() + ")";
        if (t.equals(JT.CHAR)) return "JJson::str(&" + paren(v.code()) + ".to_string())";
        if (t.equals(JT.FLOAT)) return "JJson::float(" + v.code() + ")";
        if (t.equals(JT.DOUBLE)) return "JJson::double(" + v.code() + ")";
        if (t.numeric()) return "JJson::int(" + v.code() + " as i64)";
        if (t.is("enum")) return "JJson::str(" + paren(v.code()) + ".java_name())";
        throw new JavaParser.Unsupported("a " + t + " as a JSON value");
    }

    private Ex jsonCall(Ex j, Call c, Ctx x) {
        String r = paren(j.code());
        List<Ex> a = new ArrayList<>();
        for (Expr e : c.args()) a.add(unwrapOpt(ex(e, x)));
        JT opt = JT.opt("nullable", JSON);
        switch (c.name() + "/" + a.size()) {
            case "has/1": return new Ex(r + ".has(" + strArg(a.get(0)) + ")", JT.BOOL);
            case "get/1":
                if (a.get(0).type().is("string")) return new Ex(r + ".get(" + strArg(a.get(0)) + ")", opt);
                return new Ex(r + ".at(" + numeric(a.get(0), JT.INT).code() + ")", JSON);
            case "getAsJsonObject/0": return new Ex(r + ".as_object()", JSON);
            case "getAsJsonArray/0": return new Ex(r + ".as_array()", JSON);
            case "getAsJsonPrimitive/0", "deepCopy/0": return new Ex(r + ".clone()", JSON);
            case "getAsJsonObject/1": return new Ex(r + ".member_object(" + strArg(a.get(0)) + ")", opt);
            case "getAsJsonArray/1": return new Ex(r + ".member_array(" + strArg(a.get(0)) + ")", opt);
            case "getAsJsonPrimitive/1": return new Ex(r + ".get(" + strArg(a.get(0)) + ")", opt);
            case "getAsString/0": return new Ex(r + ".as_string()", JT.STRING);
            case "getAsInt/0": return new Ex(r + ".as_int()", JT.INT);
            case "getAsLong/0": return new Ex(r + ".as_long()", JT.LONG);
            case "getAsFloat/0": return new Ex(r + ".as_float()", JT.FLOAT);
            case "getAsDouble/0": return new Ex(r + ".as_double()", JT.DOUBLE);
            case "getAsBoolean/0": return new Ex(r + ".as_bool()", JT.BOOL);
            case "isJsonObject/0", "isJsonArray/0", "isJsonNull/0", "isJsonPrimitive/0", "isString/0", "isNumber/0", "isBoolean/0":
                return new Ex(r + "." + snake(c.name()) + "()", JT.BOOL);
            case "size/0": return new Ex(r + ".size()", JT.INT);
            case "isEmpty/0": return new Ex(r + ".is_empty()", JT.BOOL);
            case "entrySet/0": return new Ex(r + ".entries()", new JT("view", "entries", new JT("entry", "entry", JSON, JT.STRING), JT.STRING), false, placeOf(j));
            case "keySet/0": return new Ex(r + ".keys()", new JT("view", "keys", JT.STRING, JT.STRING), false, placeOf(j));
            case "addProperty/2":
                markRecv(j, x);
                return new Ex(r + ".add(" + strArg(a.get(0)) + ", " + toJsonValue(a.get(1)) + ")", JT.VOID);
            case "add/2":
                markRecv(j, x);
                return new Ex(r + ".add(" + strArg(a.get(0)) + ", " + toJsonValue(a.get(1)) + ")", JT.VOID);
            case "add/1":
                markRecv(j, x);
                return new Ex(r + ".push(" + toJsonValue(a.get(0)) + ")", JT.VOID);
            case "remove/1":
                markRecv(j, x);
                return new Ex(r + ".remove(" + strArg(a.get(0)) + ")", opt);
            case "toString/0": return new Ex(r + ".to_string()", JT.STRING);
            case "equals/1": return new Ex("(" + r + " == " + paren(a.get(0).code()) + ")", JT.BOOL);
            default: throw new JavaParser.Unsupported("JSON's " + c.name());
        }
    }

    private Ex gsonCall(Ex g, Call c, Ctx x) {
        String r = paren(g.code());
        switch (c.name() + "/" + c.args().size()) {
            case "setPrettyPrinting/0": return new Ex("JGson { pretty: true, .." + r + " }", GSON);
            case "disableHtmlEscaping/0": return new Ex("JGson { html: false, .." + r + " }", GSON);
            case "serializeNulls/0": return new Ex("JGson { nulls: true, .." + r + " }", GSON);
            case "create/0", "setLenient/0": return g;
            case "toJson/1": return new Ex(r + ".to_json(&" + paren(jsonOf(c.args().get(0), x)) + ")", JT.STRING);
            case "toJsonTree/1": return new Ex(jsonOf(c.args().get(0), x), JSON);
            case "fromJson/2": {
                Expr what = c.args().get(1);
                if (!(what instanceof Field f && f.name().equals("class") && f.target() instanceof Name tn))
                    throw new JavaParser.Unsupported("Gson.fromJson into something other than a class (a TypeToken)");
                Ex src = unwrapOpt(ex(c.args().get(0), x));
                String json = src.type().is("json") ? "&" + paren(src.code()) : src.type().is("string") ? "&JJson::parse(" + strArg(src) + ")" : null;
                if (json == null) throw new JavaParser.Unsupported("Gson.fromJson from a " + src.type());
                if (Set.of("JsonObject", "JsonElement").contains(tn.id())) return new Ex(paren(json.substring(1)) + ".clone()", JSON);
                if (tn.id().equals("JsonArray")) return new Ex(paren(json.substring(1)) + ".as_array()", JSON);
                Cls k = ownClass(tn.id(), x.cls);
                if (k == null) throw new JavaParser.Unsupported("Gson.fromJson into " + describe(tn.id(), x.cls));
                needJson(k);
                if (failed.containsKey(k.d.qualified + "#json")) throw new JavaParser.Unsupported(failed.get(k.d.qualified + "#json"));
                return new Ex(k.module + "::" + k.rust + "::from_jjson(" + json + ")", JT.opt("nullable", new JT("cls", k.d.qualified, null)));
            }
            default: throw new JavaParser.Unsupported("Gson." + c.name());
        }
    }

    /** A value as JSON for Gson.toJson: JSON as is, a class through its to_jjson. */
    private String jsonOf(Expr e, Ctx x) {
        Ex v = ex(e, x);
        if (v.type().is("opt")) v = unwrapOpt(v);
        if (v.type().is("cls")) {
            Cls k = classes.get(v.type().name());
            needJson(k);
            if (failed.containsKey(k.d.qualified + "#json")) throw new JavaParser.Unsupported(failed.get(k.d.qualified + "#json"));
            if ("staticobj".equals(v.place())) return withRead(v, ".to_jjson()");
            return paren(v.code()) + ".to_jjson()";
        }
        return toJsonValue(v);
    }

    private void needJson(Cls k) {
        if (jsonClasses.add(k.d.qualified)) changed = true;
    }

    /** Gson's reflective reading and writing of a class: its instance fields by name. */
    private String jsonMethods(Cls c) {
        StringBuilder to = new StringBuilder("/// Gson.toJson: the instance fields by name.\npub fn to_jjson(&self) -> JJson {\n    let mut j = JJson::object();\n");
        StringBuilder from = new StringBuilder("/// Gson.fromJson: a new one (its constructor's values), with the fields the JSON has.\npub fn from_jjson(j: &JJson) -> Option<Self> {\n"
                + "    if j.is_json_null() {\n        return None;\n    }\n");
        MethodDecl noArgs = c.methods.getOrDefault("<init>", List.of()).stream().filter(m -> m.params.isEmpty()).findFirst().orElse(null);
        if (noArgs == null && !c.methods.getOrDefault("<init>", List.of()).isEmpty()) throw new JavaParser.Unsupported("it has no constructor without parameters");
        if (noArgs != null && failed.containsKey(methodKey(c, noArgs))) throw new JavaParser.Unsupported("its constructor isn't translated");
        from.append("    let mut s = Self::").append(noArgs == null ? "new" : ctorName(c, noArgs)).append("();\n");
        for (FieldDecl f : c.d.fields) {
            if (f.mods.contains("static") || f.mods.contains("transient")) continue;
            JT t = fieldType(c, f);
            String rn = rustName(f.name);
            to.append("    j.add(\"").append(f.name).append("\", ").append(toJ("self." + rn, t, false)).append(");\n");
            from.append("    if let Some(v) = j.get(\"").append(f.name).append("\") && !v.is_json_null() {\n        s.").append(rn).append(" = ")
                    .append(fromJ("v", t)).append(";\n    }\n");
        }
        to.append("    j\n}\n");
        from.append("    Some(s)\n}\n");
        return to.toString() + from;
    }

    /** A field's value as JSON. ref: `v` is a reference. */
    private String toJ(String v, JT t, boolean ref) {
        String val = ref && t.copy() ? "*" + v : v;
        if (t.is("opt")) return "match &" + v + " { Some(v) => " + toJ("v", t.elem(), true) + ", None => JJson::Null }";
        if (t.is("string")) return "JJson::str(&" + v + ")";
        if (t.isBool()) return "JJson::Bool(" + val + ")";
        if (t.equals(JT.CHAR)) return "JJson::str(&" + val + ".to_string())";
        if (t.equals(JT.FLOAT)) return "JJson::float(" + val + ")";
        if (t.equals(JT.DOUBLE)) return "JJson::double(" + val + ")";
        if (t.numeric()) return "JJson::int(" + val + " as i64)";
        if (t.is("enum")) return "JJson::str(" + val + ".java_name())";
        if (t.is("ident")) return "JJson::str(&" + v + ".to_string())";
        if (t.is("json")) return v + ".clone()";
        if ((t.is("array") || t.is("list") || t.is("set")) && t.elem() != null)
            return "JJson::Arr(" + v + ".iter().map(|e| " + toJ("e", t.elem(), true) + ").collect())";
        if (t.is("map") && t.key() != null && (t.key().is("string") || t.key().is("enum") || t.key().numeric()))
            return "{ let mut o = JJson::object(); for (k, e) in " + v + ".iter() { o.add(&" + (t.key().is("enum") ? "k.java_name()" : "k.to_string()") + ", " + toJ("e", t.elem(), true) + "); } o }";
        if (t.is("cls")) {
            needJson(classes.get(t.name()));
            return v + ".to_jjson()";
        }
        throw new JavaParser.Unsupported("a " + t + " field");
    }

    /** A field's value read from JSON `v` (a &JJson). */
    private String fromJ(String v, JT t) {
        if (t.is("opt")) return "if " + v + ".is_json_null() { None } else { Some(" + fromJ(v, t.elem()) + ") }";
        if (t.is("string")) return v + ".as_string()";
        if (t.isBool()) return v + ".as_bool()";
        if (t.equals(JT.CHAR)) return v + ".as_string().chars().next().unwrap_or('\\0')";
        if (t.equals(JT.FLOAT)) return v + ".as_float()";
        if (t.equals(JT.DOUBLE)) return v + ".as_double()";
        if (t.equals(JT.LONG)) return v + ".as_long()";
        if (t.numeric()) return v + ".as_int() as " + rustPrim(t.name());
        if (t.is("enum")) {
            Cls e = classes.get(t.name());
            return e.module + "::" + e.rust + "::value_of(&" + v + ".as_string())";
        }
        if (t.is("ident")) return "JIdentifier::parse(&" + v + ".as_string())";
        if (t.is("json")) return v + ".clone()";
        if ((t.is("array") || t.is("list")) && t.elem() != null) return v + ".elements().map(|e| " + fromJ("e", t.elem()) + ").collect()";
        if (t.is("set") && t.elem() != null) return "JSet::from_iter(" + v + ".elements().map(|e| " + fromJ("e", t.elem()) + "))";
        if (t.is("map") && t.key() != null && t.key().is("string")) return "JMap::from_iter(" + v + ".entries().map(|(k, e)| (k.clone(), " + fromJ("e", t.elem()) + ")))";
        if (t.is("cls")) {
            Cls k = classes.get(t.name());
            needJson(k);
            return k.module + "::" + k.rust + "::from_jjson(" + v + ").expect(\"null\")";
        }
        throw new JavaParser.Unsupported("a " + t + " field");
    }

    /** AtomicInteger / AtomicLong / AtomicBoolean as Rust's atomics (shared, so no lock and no `mut`). */
    private Ex atomicCall(Ex at, Call c, Ctx x) {
        String r = paren(at.code()) + ".";
        JT e = at.type().elem();
        String o = "std::sync::atomic::Ordering::SeqCst";
        List<Ex> a = new ArrayList<>();
        for (Expr arg : c.args()) a.add(ex(arg, x));
        java.util.function.IntFunction<String> arg = i -> e.isBool() ? a.get(i).code() : numeric(a.get(i), e).code();
        String one = e.isBool() ? "true" : "1";
        switch (c.name() + "/" + a.size()) {
            case "get/0", "intValue/0", "longValue/0", "getPlain/0", "getAcquire/0": return new Ex(r + "load(" + o + ")", e);
            case "set/1", "lazySet/1", "setPlain/1", "setRelease/1": return new Ex(r + "store(" + arg.apply(0) + ", " + o + ")", JT.VOID);
            case "getAndSet/1": return new Ex(r + "swap(" + arg.apply(0) + ", " + o + ")", e);
            case "compareAndSet/2", "weakCompareAndSet/2":
                return new Ex(r + "compare_exchange(" + arg.apply(0) + ", " + arg.apply(1) + ", " + o + ", " + o + ").is_ok()", JT.BOOL);
            case "incrementAndGet/0": return new Ex(r + "fetch_add(" + one + ", " + o + ").wrapping_add(1)", e);
            case "decrementAndGet/0": return new Ex(r + "fetch_sub(" + one + ", " + o + ").wrapping_sub(1)", e);
            case "getAndIncrement/0": return new Ex(r + "fetch_add(" + one + ", " + o + ")", e);
            case "getAndDecrement/0": return new Ex(r + "fetch_sub(" + one + ", " + o + ")", e);
            case "addAndGet/1": {
                String d = arg.apply(0);
                return new Ex(r + "fetch_add(" + d + ", " + o + ").wrapping_add(" + d + ")", e);
            }
            case "getAndAdd/1": return new Ex(r + "fetch_add(" + arg.apply(0) + ", " + o + ")", e);
            default: throw new JavaParser.Unsupported(at.type().name() + "." + c.name());
        }
    }

    private Ex matcherCall(Ex m, Call c, Ctx x) {
        String r = paren(m.code());
        List<Ex> a = new ArrayList<>();
        for (Expr e : c.args()) a.add(unwrapOpt(ex(e, x)));
        switch (c.name() + "/" + a.size()) {
            case "find/0", "matches/0", "lookingAt/0":
                markRecv(m, x);
                return new Ex(r + "." + (c.name().equals("lookingAt") ? "looking_at" : c.name()) + "()", JT.BOOL);
            case "find/1":
                markRecv(m, x);
                return new Ex(r + ".find_at(" + numeric(a.get(0), JT.INT).code() + ")", JT.BOOL);
            case "group/0": return new Ex(r + ".group(0)", JT.opt("nullable", JT.STRING));
            case "group/1":
                if (a.get(0).type().is("string")) return new Ex(r + ".group_named(" + strArg(a.get(0)) + ")", JT.opt("nullable", JT.STRING));
                return new Ex(r + ".group(" + numeric(a.get(0), JT.INT).code() + ")", JT.opt("nullable", JT.STRING));
            case "start/0", "end/0": return new Ex(r + "." + c.name() + "(0)", JT.INT);
            case "start/1", "end/1": return new Ex(r + "." + c.name() + "(" + numeric(a.get(0), JT.INT).code() + ")", JT.INT);
            case "groupCount/0": return new Ex(r + ".group_count()", JT.INT);
            case "replaceAll/1": return new Ex(r + ".replace_all(" + strArg(a.get(0)) + ")", JT.STRING);
            default: throw new JavaParser.Unsupported("Matcher." + c.name());
        }
    }

    private static Ex unknownStr(Call c) {
        throw new JavaParser.Unsupported("String." + c.name() + " with " + c.args().size() + " values");
    }

    private Ex lockedList(Ex l, Call c, Ctx x) {
        JT e = l.type().elem();
        List<Expr> a = c.args();
        switch (c.name() + "/" + a.size()) {
            case "size/0": return new Ex("(" + withRead(l, ".len()") + " as i32)", JT.INT);
            case "isEmpty/0": return new Ex(withRead(l, ".is_empty()"), JT.BOOL);
            case "get/1": {
                if (e == null || !(e.copy() || e.is("string") || e.is("ident") || e.is("stack"))) throw new JavaParser.Unsupported("an object read out of a list kept in a changing static");
                return locked(l, List.of(usize(unwrapOpt(ex(a.get(0), x)))), "[a_0].clone()", e);
            }
            case "add/1":
                if (e == null) throw new JavaParser.Unsupported("a list of unknown type");
                markRecv(l, x);
                return locked(l, List.of(valueTo(a.get(0), e, x).code()), ".push(a_0)", JT.VOID);
            case "clear/0":
                markRecv(l, x);
                return new Ex(withRead(l, ".clear()"), JT.VOID);
            case "contains/1":
                if (e == null || !(e.copy() || e.is("string"))) throw new JavaParser.Unsupported("List.contains on objects");
                return locked(l, List.of(valueTo(a.get(0), e, x).code()), ".contains(&a_0)", JT.BOOL);
            case "stream/0":
                if (e == null) throw new JavaParser.Unsupported("a list of unknown type");
                return new Ex(withRead(l, ".clone()") + ".into_iter()", new JT("stream", "Stream", e));
            default:
                throw new JavaParser.Unsupported("List." + c.name() + " on a list kept in a changing static");
        }
    }

    private Ex listCall(Ex l, Call c, Ctx x) {
        JT t = l.type();
        String r = paren(l.code());
        if ("staticobj".equals(l.place())) return lockedList(l, c, x);
        switch (c.name() + "/" + c.args().size()) {
            case "size/0": return new Ex("(" + r + ".len() as i32)", JT.INT);
            case "isEmpty/0": return new Ex(r + ".is_empty()", JT.BOOL);
            case "get/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                return new Ex(r + "[" + usize(ex(c.args().get(0), x)) + "]", t.elem(), t.elem().copy(), placeOf(l));
            }
            case "add/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                markRecv(l, x);
                return new Ex(r + ".push(" + valueTo(c.args().get(0), t.elem(), x).code() + ")", JT.VOID);
            }
            case "clear/0":
                markRecv(l, x);
                return new Ex(r + ".clear()", JT.VOID);
            case "stream/0":
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                return new Ex(r + (l.owned() ? ".into_iter()" : ".iter().cloned()"), new JT("stream", "Stream", t.elem()));
            case "equals/1": {
                Ex o = unwrapOpt(ex(c.args().get(0), x));
                if (!(o.type().is("list") || o.type().is("array")) || "staticobj".equals(o.place()) || !compatible(o.type(), t)) throw new JavaParser.Unsupported("List.equals of a " + o.type());
                if (t.elem() != null && t.elem().floating()) throw new JavaParser.Unsupported("List.equals on floating-point values");
                if (t.elem() == null || !eqable(t.elem())) throw new JavaParser.Unsupported("List.equals on objects that compare by identity");
                return new Ex("(" + r + "[..] == " + paren(o.code()) + "[..])", JT.BOOL);
            }
            case "containsAll/1": {
                Ex o = unwrapOpt(ex(c.args().get(0), x));
                if (o.type().elem() == null || t.elem() == null || !o.type().elem().equals(t.elem()) || "staticobj".equals(o.place())) throw new JavaParser.Unsupported("List.containsAll of a " + o.type());
                return new Ex(iterOf(o) + ".all(|v| " + r + ".contains(v))", JT.BOOL);
            }
            case "forEach/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                Fn f = closure(c.args().get(0), List.of(t.elem()), true, JT.VOID, x);
                return new Ex(r + ".iter().for_each(" + f.code() + ")", JT.VOID);
            }
            case "removeIf/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                markRecv(l, x);
                Fn f = closure(c.args().get(0), List.of(t.elem()), true, JT.BOOL, x);
                String v = "removed_" + (x.tmp++);
                return new Ex("{ let " + v + " = " + r + ".len(); " + r + ".retain(|e| !(" + f.code() + ")(e)); " + r + ".len() != " + v + " }", JT.BOOL);
            }
            case "sort/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                markRecv(l, x);
                if (isNull(c.args().get(0))) return new Ex(r + ".sort_by(" + naturalOrder(t.elem()) + ")", JT.VOID);
                return new Ex(r + ".sort_by(" + comparator(c.args().get(0), t.elem(), x) + ")", JT.VOID);
            }
            case "indexOf/1": {
                if (t.elem() == null || !(t.elem().copy() || t.elem().is("string"))) throw new JavaParser.Unsupported("List.indexOf on objects");
                Ex v = ex(c.args().get(0), x);
                String probe = t.elem().is("string") ? "e.as_str() == " + strArg(v) : "*e == " + paren(valueTo(c.args().get(0), t.elem(), x).code());
                return new Ex(r + ".iter().position(|e| " + probe + ").map_or(-1, |i| i as i32)", JT.INT);
            }
            case "remove/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                Ex v = ex(c.args().get(0), x);
                markRecv(l, x);
                if (v.type().equals(JT.INT))
                    return new Ex(r + ".remove(" + usize(v) + ")", t.elem());
                if (t.elem().is("string") && v.type().is("string"))
                    return new Ex(r + ".iter().position(|e| e.as_str() == " + strArg(v) + ").map(|i| " + r + ".remove(i)).is_some()", JT.BOOL);
                if (t.elem().copy() && !t.elem().equals(JT.INT))
                    return new Ex(r + ".iter().position(|e| *e == " + paren(valueTo(c.args().get(0), t.elem(), x).code()) + ").map(|i| " + r + ".remove(i)).is_some()", JT.BOOL);
                throw new JavaParser.Unsupported("List.remove of an object");
            }
            case "addAll/1": {
                if (t.elem() == null) throw new JavaParser.Unsupported("a list of unknown type");
                Ex o = unwrapOpt(ex(c.args().get(0), x));
                if (o.type().elem() == null || !o.type().elem().equals(t.elem())) throw new JavaParser.Unsupported("List.addAll of a " + o.type());
                markRecv(l, x);
                if ("staticobj".equals(o.place())) return new Ex(r + ".extend(" + withRead(o, ".clone()") + ")", JT.VOID);
                if (!(o.type().is("list") || o.type().is("array") || o.type().is("set") || o.type().is("view"))) throw new JavaParser.Unsupported("List.addAll of a " + o.type());
                return new Ex(r + ".extend(" + iterOf(o) + ".cloned().collect::<Vec<_>>())", JT.VOID);
            }
            case "contains/1": {
                Ex v = ex(c.args().get(0), x);
                if (t.elem() != null && t.elem().is("string")) return new Ex(r + ".iter().any(|e| e == " + strArg(v) + ")", JT.BOOL);
                if (t.elem() != null && t.elem().copy()) return new Ex(r + ".contains(&" + paren(valueTo(c.args().get(0), t.elem(), x).code()) + ")", JT.BOOL);
                throw new JavaParser.Unsupported("List.contains on objects");
            }
            default:
                throw new JavaParser.Unsupported("List." + c.name());
        }
    }

    // ---- Map, Set, Optional, null ----

    private static final Set<String> OPTIONAL_METHODS = Set.of("isPresent", "isEmpty", "get", "orElse", "orElseThrow", "orElseGet", "map", "flatMap", "filter",
            "ifPresent", "getAsInt", "getAsDouble");

    /** A nullable used where Java uses its value: Java would throw on null, Rust panics. */
    /** An owned copy of a borrowed value: a slice's Vec, a Path's PathBuf, or the clone. */
    private static String copyOf(Ex v, JT t) {
        if (t.is("array") || t.is("list")) return paren(v.code()) + ".to_vec()";
        if (t.is("path")) return paren(v.code()) + ".to_path_buf()";
        return paren(v.code()) + ".clone()";
    }

    /** Java compares these by identity, and Rust's `==` on them means the same. */
    private static boolean identityKind(JT t) {
        return t.is("enum") || t.is("hand") || t.is("arm") || t.is("depkind") || t.is("useanim") || t.is("swingtype")
                || t.is("modeltype") || t.is("displayctx");
    }

    private Ex unwrapOpt(Ex v) {
        if (!v.type().is("opt")) return v;
        JT t = v.type().elem();
        if (t == null) throw new JavaParser.Unsupported("an Optional of unknown type");
        if ("staticobj".equals(v.place())) throw new JavaParser.Unsupported("a nullable object kept in a changing static");
        String what = v.type().name().equals("Optional") ? "\"empty Optional\"" : "\"null\"";
        if (t.copy()) return new Ex(paren(v.code()) + ".expect(" + what + ")", t);
        if (v.owned()) return new Ex(paren(v.code()) + ".expect(" + what + ")", t);
        if ("optref".equals(v.place())) return new Ex(paren(v.code()) + ".expect(" + what + ")", t, false, "ref");
        if (t.is("string")) return new Ex(paren(v.code()) + ".as_deref().expect(" + what + ")", t, false, "ref");
        return new Ex(paren(v.code()) + ".as_ref().expect(" + what + ")", t, false, "ref");
    }

    /** `x.iter()` for a collection, or the iterator a map view already is. */
    private static String iterOf(Ex a) {
        return a.type().is("view") ? a.code() : paren(a.code()) + ".iter()";
    }

    /** A map or set key passed by reference: `&k`, or the &str of a string. */
    private Ex keyArg(Expr e, JT k, Ctx x) {
        if (k.is("string")) {
            Ex v = unwrapOpt(ex(e, x));
            if (!v.type().is("string")) throw new JavaParser.Unsupported("a " + v.type() + " used as a String key");
            return new Ex("&*" + paren(v.code()), k);
        }
        Ex v = valueTo(e, k, x);
        return new Ex("&" + paren(v.code()), k);
    }

    /** A value read out of a collection: copied or cloned when small, else borrowed. */
    private static Ex readOut(String code, JT v, boolean owned) {
        if (v.copy() || v.is("string") || v.is("ident")) return new Ex(code + ".cloned()", JT.opt("nullable", v));
        return new Ex(code, JT.opt("nullable", v), owned, "optref");
    }

    /** Calls on a map kept in a changing static go through its lock, with the arguments worked out first. */
    private Ex locked(Ex recv, List<String> args, String call, JT type) {
        StringBuilder b = new StringBuilder("{ ");
        for (int i = 0; i < args.size(); i++) b.append("let a_").append(i).append(" = ").append(args.get(i)).append("; ");
        b.append(withRead(recv, call)).append(" }");
        return new Ex(b.toString(), type);
    }

    private Ex mapCall(Ex m, Call c, Ctx x) {
        JT t = m.type(), k = t.key(), v = t.elem();
        String r = paren(m.code());
        boolean st = "staticobj".equals(m.place());
        List<Expr> a = c.args();
        switch (c.name() + "/" + a.size()) {
            case "size/0":
                return st ? new Ex("(" + withRead(m, ".len()") + " as i32)", JT.INT) : new Ex("(" + r + ".len() as i32)", JT.INT);
            case "isEmpty/0":
                return st ? new Ex(withRead(m, ".is_empty()"), JT.BOOL) : new Ex(r + ".is_empty()", JT.BOOL);
            case "clear/0":
                markRecv(m, x);
                return st ? new Ex(withRead(m, ".clear()"), JT.VOID) : new Ex(r + ".clear()", JT.VOID);
            case "containsKey/1": {
                if (st) return locked(m, List.of(valueTo(a.get(0), k, x).code()), ".contains_key(&a_0)", JT.BOOL);
                return new Ex(r + ".contains_key(" + keyArg(a.get(0), k, x).code() + ")", JT.BOOL);
            }
            case "get/1": {
                if (st) {
                    if (!valueLike(v)) throw new JavaParser.Unsupported("an object read out of a map kept in a changing static");
                    Ex got = locked(m, List.of(valueTo(a.get(0), k, x).code()), ".get(&a_0).cloned()", JT.opt("nullable", v));
                    return new Ex(got.code(), got.type(), true, "copy");
                }
                return readOut(r + ".get(" + keyArg(a.get(0), k, x).code() + ")", v, false);
            }
            case "getOrDefault/2": {
                if (!valueLike(v)) throw new JavaParser.Unsupported("Map.getOrDefault on objects that can change");
                String d = valueTo(a.get(1), v, x).code();
                if (st) {
                    Ex got = locked(m, List.of(valueTo(a.get(0), k, x).code(), d), ".get(&a_0).cloned().unwrap_or(a_1)", v);
                    return new Ex(got.code(), v, true, "copy");
                }
                return new Ex(r + ".get(" + keyArg(a.get(0), k, x).code() + ").cloned().unwrap_or_else(|| " + d + ")", v, true, "copy");
            }
            case "put/2": {
                markRecv(m, x);
                String kv = valueTo(a.get(0), k, x).code(), vv = valueTo(a.get(1), v, x).code();
                if (st) return locked(m, List.of(kv, vv), ".insert(a_0, a_1)", JT.opt("nullable", v));
                return new Ex(r + ".insert(" + kv + ", " + vv + ")", JT.opt("nullable", v));
            }
            case "remove/1": {
                markRecv(m, x);
                if (st) return locked(m, List.of(valueTo(a.get(0), k, x).code()), ".remove(&a_0)", JT.opt("nullable", v));
                return new Ex(r + ".remove(" + keyArg(a.get(0), k, x).code() + ")", JT.opt("nullable", v));
            }
            case "putIfAbsent/2": {
                markRecv(m, x);
                String kv = valueTo(a.get(0), k, x).code(), vv = valueTo(a.get(1), v, x).code();
                if (st) return new Ex("{ let a_0 = " + kv + "; let a_1 = " + vv + "; " + m.code() + ".with(|o| { o.or_insert_with(a_0, || a_1); }) }", JT.VOID);
                return new Ex("{ " + r + ".or_insert_with(" + kv + ", || " + vv + "); }", JT.VOID);
            }
            case "computeIfAbsent/2": {
                markRecv(m, x);
                String kv = valueTo(a.get(0), k, x).code();
                String make;
                try {
                    make = supplier(a.get(1), v, x);
                } catch (JavaParser.Unsupported u) {
                    // A function of the key (`Choices::computeResolve`, `k -> load(k)`): called with a copy of it.
                    Fn f = closure(a.get(1), List.of(k), true, v, x);
                    String key = "key_" + (x.tmp++);
                    String call = "(" + f.code() + ")(&" + key + ")";
                    if (st) {
                        if (!(v.copy() || v.is("string") || v.is("ident") || valueLike(v))) throw u;
                        return new Ex("{ let " + key + " = " + kv + "; let a_0 = " + key + ".clone(); let made = " + m.code() + ".with(|o| o.get(&" + key + ").cloned());"
                                + " match made { Some(v) => v, None => { let v = " + call + "; " + m.code() + ".with(|o| o.or_insert_with(a_0, || v.clone()).clone()) } } }", v, true, null);
                    }
                    return new Ex("{ let " + key + " = " + kv + "; let a_0 = " + key + ".clone(); " + r + ".or_insert_with(a_0, || " + call + ") }", v, false, "mutref");
                }
                if (st) {
                    if (!(v.copy() || v.is("string") || v.is("ident"))) throw new JavaParser.Unsupported("Map.computeIfAbsent on a map kept in a changing static, giving an object");
                    return locked(m, List.of(kv), ".or_insert_with(a_0, " + make + ").clone()", v);
                }
                return new Ex(r + ".or_insert_with(" + kv + ", " + make + ")", v, false, "mutref");
            }
            case "merge/3": {
                markRecv(m, x);
                if (!v.numeric()) throw new JavaParser.Unsupported("Map.merge on " + v);
                String op = mergeOp(a.get(2));
                String kv = valueTo(a.get(0), k, x).code(), vv = valueTo(a.get(1), v, x).code();
                String body = switch (op) {
                    case "+" -> "*e += a_1;";
                    case "max" -> "*e = (*e).max(a_1);";
                    default -> "*e = (*e).min(a_1);";
                };
                // Absent: the value itself. Present: combined with it.
                String code = "let a_1 = " + vv + "; let mut fresh = false; let e = " + (st ? "o" : r) + ".or_insert_with(" + (st ? "a_0" : kv)
                        + ", || { fresh = true; a_1 }); if !fresh { " + body + " }";
                if (st) return new Ex("{ let a_0 = " + kv + "; let a_1 = " + vv + "; " + m.code() + ".with(|o| { let mut fresh = false; let e = o.or_insert_with(a_0, || { fresh = true; a_1 }); if !fresh { " + body + " } }) }", JT.VOID);
                return new Ex("{ " + code + " }", JT.VOID);
            }
            case "keySet/0":
                if (st) {
                    // A snapshot: the keys can't change under the caller.
                    return new Ex("JSet::from_iter(" + withRead(m, ".keys().cloned().collect::<Vec<_>>()") + ")", new JT("set", "set", k), true, "copy");
                }
                return new Ex(r + ".keys()", new JT("view", "keys", k, k), false, placeOf(m));
            case "values/0":
                if (st) {
                    if (!valueLike(v)) throw new JavaParser.Unsupported("the values of a map kept in a changing static");
                    return new Ex(withRead(m, ".values().cloned().collect::<Vec<_>>()"), new JT("list", "list", v), true, "copy");
                }
                return new Ex(r + ".values()", new JT("view", "values", v, k), false, placeOf(m));
            case "entrySet/0":
                if (st) {
                    if (!valueLike(v) || !valueLike(k)) throw new JavaParser.Unsupported("the entries of a map kept in a changing static");
                    // A snapshot, walked like the map itself.
                    return new Ex(withRead(m, ".clone_pairs()") + ".iter().map(|(k, v)| (k, v))", new JT("view", "entries", new JT("entry", "entry", v, k), k), false, "copy");
                }
                return new Ex(r + ".iter()", new JT("view", "entries", new JT("entry", "entry", v, k), k), false, placeOf(m));
            default:
                throw new JavaParser.Unsupported("Map." + c.name());
        }
    }

    // ---- lambdas, streams, comparators ----

    /** A Java lambda or method reference turned into a Rust closure. */
    record Fn(String code, JT ret) {}

    /** A method reference as the lambda it stands for, with `arity` parameters. */
    private Lambda asLambda(Expr f, int arity, Ctx x) {
        if (f instanceof Lambda l) return l;
        if (!(f instanceof MethodRef mr) || mr.name().equals("new")) return null;
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < arity; i++) ps.add("\u0002" + (char) ('a' + i));
        List<Expr> names = ps.stream().map(p -> (Expr) new Name(p)).toList();
        Cls qualified = mr.target() instanceof Field qf && Character.isUpperCase(qf.name().charAt(0)) ? ownClass(qf.name(), x.cls) : null;
        if (qualified != null || mr.target() instanceof Name tn && x.find(tn.id()) == null && fieldOwner(tn.id(), x.cls, true) == null
                && (x.isStatic || fieldOwner(tn.id(), x.cls, false) == null) && Character.isUpperCase(tn.id().charAt(0))) {
            String typeName = qualified != null ? ((Field) mr.target()).name() : ((Name) mr.target()).id();
            Cls own = qualified != null ? qualified : ownClass(typeName, x.cls);
            Name tn = new Name(typeName);
            boolean staticForm = own != null ? own.d.methods.stream().anyMatch(m -> m.name.equals(mr.name()) && m.params.size() == arity && m.mods.contains("static"))
                    : Set.of("Integer", "Long", "Double", "Float", "Math", "Character", "Boolean", "String", "Objects", "Mth").contains(typeName)
                            && !(tn.id().equals("String") && arity == 1 && !mr.name().equals("valueOf"))
                            && !(tn.id().equals("String") && arity == 2 && !mr.name().equals("join"));
            if (staticForm) return new Lambda(ps, new Call(qualified != null ? tn : mr.target(), mr.name(), names));
            return new Lambda(ps, new Call(names.get(0), mr.name(), names.subList(1, arity)));
        }
        return new Lambda(ps, new Call(mr.target(), mr.name(), names));
    }

    /**
     * A lambda or method reference as a Rust closure taking `pts`. byRef: it gets `&T` (filter, sort keys, forEach),
     * else it owns its value (map, anyMatch). want: what it returns (VOID for a statement body; null: whatever the body is).
     */
    private Fn closure(Expr f, List<JT> pts, boolean byRef, JT want, Ctx x) {
        Lambda l = asLambda(f, pts.size(), x);
        if (l == null) throw new JavaParser.Unsupported(f instanceof MethodRef ? "a constructor reference" : "a function kept in a variable");
        if (l.params().size() != pts.size()) throw new JavaParser.Unsupported("a lambda with " + l.params().size() + " parameters where " + pts.size() + " go");
        Map<String, Var> scope = new HashMap<>();
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            String java = l.params().get(i);
            JT t = pts.get(i);
            if (t == null) throw new JavaParser.Unsupported("a lambda over values of unknown type");
            String rn = java.startsWith("\u0002") ? "p_" + java.substring(1) : rustName(java);
            String rt = owned(t, x.cls);
            if (t.copy()) {
                ps.add(byRef ? "&" + rn + ": &" + rt : rn + ": " + rt);
                scope.put(java, new Var(java, rn, t, "local", -1));
            } else {
                ps.add(rn + ": " + (byRef ? "&" : "") + rt);
                scope.put(java, new Var(java, rn, t, byRef ? "iter" : "local", -1));
            }
            if (mutLocal.contains(x.key() + ":" + java)) throw new JavaParser.Unsupported("a lambda that changes its parameter");
        }
        String head = "|" + String.join(", ", ps) + "|";
        JT ret0 = x.ret;
        String returns0 = x.returns;
        int loops = x.loops, sw = x.switches, mark = x.out.length(), depth = x.depth;
        boolean io0 = x.io;
        x.scopes.push(scope);
        x.loops = 0;
        x.switches = 0;
        x.io = false;
        x.depth = 1;
        try {
            if (l.body() instanceof Expr body && (want == null || !want.is("void"))) {
                Ex v = want != null ? valueTo(body, want, x) : ex(body, x);
                if (want == null && !v.owned() && !v.type().is("stream")) v = valueTo(body, v.type(), x);
                String lines = x.out.substring(mark);
                if (lines.isEmpty()) return new Fn(head + " " + v.code(), v.type());
                return new Fn(head + " {\n" + lines + "    " + v.code() + "\n}", v.type());
            }
            if (want == null) throw new JavaParser.Unsupported("a lambda with a block body here");
            x.ret = want;
            x.returns = "plain";
            if (l.body() instanceof Expr body) exprStmt(body, x);
            else for (Stmt s : ((Block) l.body()).stmts()) stmt(s, x);
            String lines = x.out.substring(mark);
            return new Fn(head + (want.is("void") ? "" : " -> " + owned(want, x.cls)) + " {\n" + lines + "}", want);
        } finally {
            x.scopes.pop();
            x.out.setLength(mark);
            x.ret = ret0;
            x.returns = returns0;
            x.loops = loops;
            x.switches = sw;
            x.depth = depth;
            x.io = io0;
        }
    }

    /** Comparing two keys of type t as Java's natural order would, to an Ordering. */
    private static String cmpOf(String a, String b, JT t) {
        if (t.is("string")) return "j_compare(&" + a + ", &" + b + ").cmp(&0)";
        if (t.floating()) return "j_compare_f(" + a + " as f64, " + b + " as f64).cmp(&0)";
        if (t.copy()) return "(" + a + ").cmp(&" + b + ")";
        throw new JavaParser.Unsupported("comparing " + t + " values (they aren't Comparable here)");
    }

    private static String naturalOrder(JT e) {
        String deref = e.copy() ? "*" : "";
        return "|a: &" + "_" + ", b: &_| " + cmpOf(deref + "a", deref + "b", e);
    }

    /** A Comparator as a Rust `|a: &T, b: &T| -> Ordering` closure. */
    private String comparator(Expr f, JT e, Ctx x) {
        if (f instanceof Call c && c.target() instanceof Name n && n.id().equals("Comparator") && x.find("Comparator") == null) {
            switch (c.name() + "/" + c.args().size()) {
                case "naturalOrder/0": return naturalOrder(e);
                case "reverseOrder/0": return "|a: &_, b: &_| (" + naturalOrder(e) + ")(b, a)";
                case "comparing/1", "comparingInt/1", "comparingDouble/1", "comparingLong/1": {
                    Fn k = closure(c.args().get(0), List.of(e), true, null, x);
                    return "|a: &_, b: &_| { let (ka, kb) = ((" + k.code() + ")(a), (" + k.code() + ")(b)); " + cmpOf("ka", "kb", k.ret()) + " }";
                }
                case "comparing/2": {
                    Fn k = closure(c.args().get(0), List.of(e), true, null, x);
                    String inner = comparator(c.args().get(1), k.ret(), x);
                    return "|a: &_, b: &_| { let (ka, kb) = ((" + k.code() + ")(a), (" + k.code() + ")(b)); (" + inner + ")(&ka, &kb) }";
                }
                default:
                    throw new JavaParser.Unsupported("Comparator." + c.name());
            }
        }
        if (f instanceof Call c && c.target() != null && Set.of("reversed", "thenComparing", "thenComparingInt", "thenComparingDouble", "thenComparingLong").contains(c.name())) {
            String first = comparator(c.target(), e, x);
            if (c.name().equals("reversed") && c.args().isEmpty()) return "|a: &_, b: &_| (" + first + ")(b, a)";
            if (c.args().size() == 1) {
                Expr g = c.args().get(0);
                String then;
                boolean isCmp = g instanceof Lambda gl && gl.params().size() == 2 || g instanceof Call gc && gc.target() instanceof Name gn && gn.id().equals("Comparator");
                if (isCmp || c.name().equals("thenComparing") && g instanceof Call) then = comparator(g, e, x);
                else {
                    Fn k = closure(g, List.of(e), true, null, x);
                    then = "|a: &_, b: &_| { let (ka, kb) = ((" + k.code() + ")(a), (" + k.code() + ")(b)); " + cmpOf("ka", "kb", k.ret()) + " }";
                }
                return "|a: &_, b: &_| (" + first + ")(a, b).then_with(|| (" + then + ")(a, b))";
            }
        }
        if (f instanceof Field fl && fl.target() instanceof Name sn && sn.id().equals("String") && fl.name().equals("CASE_INSENSITIVE_ORDER"))
            return "|a: &_, b: &_| j_compare(&a.to_lowercase(), &b.to_lowercase()).cmp(&0)";
        if (f instanceof MethodRef mr && mr.target() instanceof Name mn && (mr.name().equals("compare") || mr.name().equals("compareTo"))
                && Set.of("Integer", "Long", "Double", "Float", "String", "Character", "Boolean").contains(mn.id()))
            return naturalOrder(e);
        Fn body = closure(f, List.of(e, e), true, JT.INT, x);
        return "|a: &_, b: &_| (" + body.code() + ")(a, b).cmp(&0)";
    }

    private Ex comparatorCall(Ex t, Call c, Ctx x) {
        throw new JavaParser.Unsupported("a Comparator kept in a variable");
    }

    /** A Java Stream: the Rust code is an iterator of owned values. */
    private Ex streamCall(Ex s, Call c, Ctx x) {
        JT e = s.type().elem();
        if (e == null) throw new JavaParser.Unsupported("a stream of unknown type");
        String it = s.code();
        List<Expr> a = c.args();
        java.util.function.Function<JT, JT> stream = t -> new JT("stream", "Stream", t);
        switch (c.name() + "/" + a.size()) {
            case "filter/1": {
                Fn f = closure(a.get(0), List.of(e), true, JT.BOOL, x);
                return new Ex(it + ".filter(" + f.code() + ")", s.type());
            }
            case "map/1", "mapToObj/1": {
                Fn f = closure(a.get(0), List.of(e), false, null, x);
                return new Ex(it + ".map(" + f.code() + ")", stream.apply(f.ret()));
            }
            case "mapToInt/1", "mapToLong/1", "mapToDouble/1": {
                JT to = c.name().equals("mapToInt") ? JT.INT : c.name().equals("mapToLong") ? JT.LONG : JT.DOUBLE;
                Fn f = closure(a.get(0), List.of(e), false, to, x);
                return new Ex(it + ".map(" + f.code() + ")", stream.apply(to));
            }
            case "asDoubleStream/0": return new Ex(it + ".map(|v| v as f64)", stream.apply(JT.DOUBLE));
            case "asLongStream/0": return new Ex(it + ".map(|v| v as i64)", stream.apply(JT.LONG));
            case "boxed/0", "sequential/0", "parallel/0", "unordered/0": return s;
            case "flatMap/1": {
                Fn f = closure(a.get(0), List.of(e), false, null, x);
                if (!f.ret().is("stream")) throw new JavaParser.Unsupported("flatMap to something other than a stream");
                return new Ex(it + ".flat_map(" + f.code() + ")", stream.apply(f.ret().elem()));
            }
            case "anyMatch/1", "allMatch/1", "noneMatch/1": {
                Fn f = closure(a.get(0), List.of(e), false, JT.BOOL, x);
                String m = c.name().equals("allMatch") ? ".all(" : ".any(";
                return new Ex((c.name().equals("noneMatch") ? "!" : "") + it + m + f.code() + ")", JT.BOOL);
            }
            case "count/0": return new Ex("(" + it + ".count() as i64)", JT.LONG);
            case "sum/0": {
                if (!e.numeric()) throw new JavaParser.Unsupported("sum of a " + e);
                return new Ex(it + ".sum::<" + rustPrim(e.name()) + ">()", e);
            }
            case "average/0": {
                if (!e.numeric()) throw new JavaParser.Unsupported("average of a " + e);
                String v = "avg_" + (x.tmp++);
                return new Ex("{ let " + v + ": Vec<f64> = " + it + ".map(|v| v as f64).collect(); if " + v + ".is_empty() { None } else { Some(" + v + ".iter().sum::<f64>() / " + v + ".len() as f64) } }",
                        JT.opt("Optional", JT.DOUBLE));
            }
            case "max/0", "min/0": {
                if (e.floating()) return new Ex(it + ".reduce(|p, q| if j_compare_f(p as f64, q as f64) " + (c.name().equals("max") ? ">=" : "<=") + " 0 { p } else { q })", JT.opt("Optional", e));
                if (!e.copy()) throw new JavaParser.Unsupported("Stream." + c.name() + " without a comparator");
                return new Ex(it + "." + c.name() + "()", JT.opt("Optional", e));
            }
            case "max/1", "min/1": {
                String cmp = comparator(a.get(0), e, x);
                return new Ex(it + "." + c.name() + "_by(" + cmp + ")", JT.opt("Optional", e));
            }
            case "findFirst/0", "findAny/0": return new Ex("{ let mut it = " + it + "; it.next() }", JT.opt("Optional", e));
            case "limit/1": return new Ex(it + ".take(" + usize(ex(a.get(0), x)) + ")", s.type());
            case "skip/1": return new Ex(it + ".skip(" + usize(ex(a.get(0), x)) + ")", s.type());
            case "distinct/0": {
                hashKey(e);
                return new Ex("{ let mut seen = JSet::new(); " + it + ".filter(move |v| seen.insert(v.clone())) }", s.type());
            }
            case "sorted/0": return new Ex("{ let mut v: Vec<_> = " + it + ".collect(); v.sort_by(" + naturalOrder(e) + "); v.into_iter() }", s.type());
            case "sorted/1": return new Ex("{ let mut v: Vec<_> = " + it + ".collect(); v.sort_by(" + comparator(a.get(0), e, x) + "); v.into_iter() }", s.type());
            case "forEach/1", "forEachOrdered/1": {
                Fn f = closure(a.get(0), List.of(e), false, JT.VOID, x);
                return new Ex(it + ".for_each(" + f.code() + ")", JT.VOID);
            }
            case "toList/0": return new Ex(it + ".collect::<Vec<_>>()", new JT("list", "list", e));
            case "toArray/0", "toArray/1": return new Ex(it + ".collect::<Vec<_>>()", new JT("array", "array", e));
            case "collect/1": return collect(s, a.get(0), x);
            case "reduce/2": {
                Ex id = ex(a.get(0), x);
                Fn f = closure(a.get(1), List.of(e, e), false, e, x);
                return new Ex(it + ".fold(" + valueTo(a.get(0), e, x).code() + ", " + f.code() + ")", e);
            }
            default:
                throw new JavaParser.Unsupported("Stream." + c.name());
        }
    }

    private Ex collect(Ex s, Expr how, Ctx x) {
        JT e = s.type().elem();
        String it = s.code();
        if (!(how instanceof Call c && c.target() instanceof Name n && n.id().equals("Collectors")))
            throw new JavaParser.Unsupported("Stream.collect with something other than a Collectors method");
        switch (c.name() + "/" + c.args().size()) {
            case "toList/0", "toUnmodifiableList/0": return new Ex(it + ".collect::<Vec<_>>()", new JT("list", "list", e));
            case "toSet/0", "toUnmodifiableSet/0": hashKey(e); return new Ex("JSet::from_iter(" + it + ")", new JT("set", "set", e));
            case "joining/0", "joining/1", "joining/3": {
                if (!e.is("string")) throw new JavaParser.Unsupported("Collectors.joining of a " + e);
                String sep = c.args().isEmpty() ? "\"\"" : strArg(ex(c.args().get(0), x));
                String body = it + ".collect::<Vec<String>>().join(" + sep + ")";
                if (c.args().size() == 3) body = "format!(\"{}{}{}\", " + ex(c.args().get(1), x).code() + ", " + body + ", " + ex(c.args().get(2), x).code() + ")";
                return new Ex(body, JT.STRING);
            }
            case "counting/0": return new Ex("(" + it + ".count() as i64)", JT.LONG);
            case "toMap/2": {
                Fn k = closure(c.args().get(0), List.of(e), true, null, x);
                Fn v = closure(c.args().get(1), List.of(e), true, null, x);
                hashKey(k.ret());
                return new Ex("JMap::from_iter(" + it + ".map(|e| ((" + k.code() + ")(&e), (" + v.code() + ")(&e))))", new JT("map", "map", v.ret(), k.ret()));
            }
            case "groupingBy/1": {
                Fn k = closure(c.args().get(0), List.of(e), true, null, x);
                hashKey(k.ret());
                String m = "groups_" + (x.tmp++);
                return new Ex("{ let mut " + m + " = JMap::new(); for e in " + it + " { " + m + ".or_insert_with((" + k.code() + ")(&e), Vec::new).push(e); } " + m + " }",
                        new JT("map", "map", new JT("list", "list", e), k.ret()));
            }
            default:
                throw new JavaParser.Unsupported("Collectors." + c.name());
        }
    }

    /** `Integer::sum`, `(a, b) -> a + b`, `Math::max`, `Math::min` as merge's function. */
    private static String mergeOp(Expr f) {
        if (f instanceof MethodRef mr && mr.target() instanceof Name n) {
            if (Set.of("Integer", "Long", "Float", "Double").contains(n.id()) && mr.name().equals("sum")) return "+";
            if (n.id().equals("Math") && (mr.name().equals("max") || mr.name().equals("min"))) return mr.name();
            if (Set.of("Integer", "Long").contains(n.id()) && (mr.name().equals("max") || mr.name().equals("min"))) return mr.name();
        }
        if (f instanceof Lambda l && l.params().size() == 2 && l.body() instanceof Binary b && b.op().equals("+")
                && b.l() instanceof Name bl && b.r() instanceof Name br
                && (bl.id().equals(l.params().get(0)) && br.id().equals(l.params().get(1)) || bl.id().equals(l.params().get(1)) && br.id().equals(l.params().get(0))))
            return "+";
        throw new JavaParser.Unsupported("Map.merge with a function other than a sum, max or min");
    }

    /** computeIfAbsent's function, as a closure that makes the value: `k -> new ArrayList<>()`, `ArrayList::new`. */
    private String supplier(Expr f, JT v, Ctx x) {
        if (f instanceof MethodRef mr && mr.name().equals("new") && mr.target() instanceof Name n)
            return "|| " + valueTo(new New(new TypeRef(n.id(), List.of(), 0), List.of()), v, x).code();
        if (f instanceof Lambda l && l.params().size() == 1 && l.body() instanceof Expr body) {
            Set<String> used = new HashSet<>();
            names(body, used);
            if (used.contains(l.params().get(0))) throw new JavaParser.Unsupported("a computeIfAbsent function that uses its key");
            return "|| " + valueTo(body, v, x).code();
        }
        if (f instanceof Lambda l && l.params().size() == 1 && l.body() instanceof Block blk) {
            Set<String> used = new HashSet<>();
            walk(blk, n -> {
                if (n instanceof Name nm) used.add(nm.id());
            });
            if (used.contains(l.params().get(0))) throw new JavaParser.Unsupported("a computeIfAbsent function that uses its key");
            // The block becomes the closure's body: its `return v` is the closure's value, as in the Java lambda.
            JT ret0 = x.ret;
            String returns0 = x.returns;
            int loops = x.loops, sw = x.switches, mark = x.out.length();
            x.ret = v;
            x.returns = "plain";
            x.loops = 0;
            x.switches = 0;
            try {
                x.depth += 1;
                x.scopes.push(new HashMap<>());
                for (Stmt s : blk.stmts()) stmt(s, x);
                x.scopes.pop();
                x.depth -= 1;
                String lines = x.out.substring(mark);
                return "|| -> " + owned(v, x.cls) + " {\n" + lines + "    ".repeat(x.depth) + "}";
            } finally {
                x.out.setLength(mark);
                x.ret = ret0;
                x.returns = returns0;
                x.loops = loops;
                x.switches = sw;
            }
        }
        throw new JavaParser.Unsupported("a computeIfAbsent function that isn't a short lambda");
    }

    private Ex setCall(Ex s, Call c, Ctx x) {
        JT e = s.type().elem();
        String r = paren(s.code());
        boolean st = "staticobj".equals(s.place());
        List<Expr> a = c.args();
        switch (c.name() + "/" + a.size()) {
            case "size/0":
                return st ? new Ex("(" + withRead(s, ".len()") + " as i32)", JT.INT) : new Ex("(" + r + ".len() as i32)", JT.INT);
            case "isEmpty/0":
                return st ? new Ex(withRead(s, ".is_empty()"), JT.BOOL) : new Ex(r + ".is_empty()", JT.BOOL);
            case "clear/0":
                markRecv(s, x);
                return st ? new Ex(withRead(s, ".clear()"), JT.VOID) : new Ex(r + ".clear()", JT.VOID);
            case "add/1": {
                markRecv(s, x);
                String v = valueTo(a.get(0), e, x).code();
                if (st) return locked(s, List.of(v), ".insert(a_0)", JT.BOOL);
                return new Ex(r + ".insert(" + v + ")", JT.BOOL);
            }
            case "contains/1":
                if (st) return locked(s, List.of(valueTo(a.get(0), e, x).code()), ".contains(&a_0)", JT.BOOL);
                return new Ex(r + ".contains(" + keyArg(a.get(0), e, x).code() + ")", JT.BOOL);
            case "remove/1":
                markRecv(s, x);
                if (st) return locked(s, List.of(valueTo(a.get(0), e, x).code()), ".remove(&a_0)", JT.BOOL);
                return new Ex(r + ".remove(" + keyArg(a.get(0), e, x).code() + ")", JT.BOOL);
            case "addAll/1": {
                markRecv(s, x);
                if (st) throw new JavaParser.Unsupported("Set.addAll on a changing static");
                Ex o = unwrapOpt(ex(a.get(0), x));
                if (!(o.type().is("list") || o.type().is("array") || o.type().is("set") || o.type().is("view")) || o.type().elem() == null || !o.type().elem().equals(e))
                    throw new JavaParser.Unsupported("Set.addAll of a " + o.type());
                return new Ex("{ for v in " + iterOf(o) + ".cloned().collect::<Vec<_>>() { " + r + ".insert(v); } }", JT.VOID);
            }
            case "containsAll/1": {
                if (st) throw new JavaParser.Unsupported("Set.containsAll on a changing static");
                Ex o = unwrapOpt(ex(a.get(0), x));
                if (o.type().elem() == null || !o.type().elem().equals(e) || "staticobj".equals(o.place())) throw new JavaParser.Unsupported("Set.containsAll of a " + o.type());
                return new Ex(iterOf(o) + ".all(|v| " + r + ".contains(v))", JT.BOOL);
            }
            case "equals/1": {
                if (st) throw new JavaParser.Unsupported("Set.equals on a changing static");
                Ex o = unwrapOpt(ex(a.get(0), x));
                if (!o.type().is("set") || "staticobj".equals(o.place())) throw new JavaParser.Unsupported("Set.equals of a " + o.type());
                return new Ex("(" + r + ".len() == " + paren(o.code()) + ".len() && " + paren(o.code()) + ".iter().all(|v| " + r + ".contains(v)))", JT.BOOL);
            }
            case "stream/0":
                if (e == null) throw new JavaParser.Unsupported("a set of unknown type");
                if (st) return new Ex(withRead(s, ".iter().cloned().collect::<Vec<_>>()") + ".into_iter()", new JT("stream", "Stream", e));
                return new Ex(r + ".iter().cloned()", new JT("stream", "Stream", e));
            case "forEach/1": {
                if (e == null) throw new JavaParser.Unsupported("a set of unknown type");
                if (st) throw new JavaParser.Unsupported("Set.forEach on a changing static");
                Fn f = closure(a.get(0), List.of(e), true, JT.VOID, x);
                return new Ex(r + ".iter().for_each(" + f.code() + ")", JT.VOID);
            }
            case "removeIf/1": {
                if (e == null) throw new JavaParser.Unsupported("a set of unknown type");
                if (st) throw new JavaParser.Unsupported("Set.removeIf on a changing static");
                markRecv(s, x);
                Fn f = closure(a.get(0), List.of(e), true, JT.BOOL, x);
                String v = "removed_" + (x.tmp++);
                return new Ex("{ let " + v + " = " + r + ".len(); " + r + ".retain(|e| !(" + f.code() + ")(e)); " + r + ".len() != " + v + " }", JT.BOOL);
            }
            default:
                throw new JavaParser.Unsupported("Set." + c.name());
        }
    }

    private Ex viewCall(Ex vw, Call c, Ctx x) {
        String code = vw.code();
        String map = code.substring(0, code.lastIndexOf('.'));
        switch (c.name() + "/" + c.args().size()) {
            case "size/0": return new Ex("(" + map + ".len() as i32)", JT.INT);
            case "isEmpty/0": return new Ex(map + ".is_empty()", JT.BOOL);
            case "contains/1":
                if (vw.type().name().equals("keys")) return new Ex(map + ".contains_key(" + keyArg(c.args().get(0), vw.type().key(), x).code() + ")", JT.BOOL);
                throw new JavaParser.Unsupported("contains on a map's " + vw.type().name());
            case "stream/0":
                if (vw.type().name().equals("entries")) throw new JavaParser.Unsupported("a stream of a map's entries");
                if ("staticobj".equals(vw.place())) throw new JavaParser.Unsupported("a stream over a map kept in a changing static");
                return new Ex(code + ".cloned()", new JT("stream", "Stream", vw.type().elem()));
            default:
                throw new JavaParser.Unsupported("." + c.name() + " on a map's " + vw.type().name());
        }
    }

    private Ex entryCall(Ex en, Call c, Ctx x) {
        String r = paren(en.code());
        JT t = switch (c.name()) {
            case "getKey" -> en.type().key();
            case "getValue" -> en.type().elem();
            default -> throw new JavaParser.Unsupported("Map.Entry." + c.name());
        };
        String part = r + (c.name().equals("getKey") ? ".0" : ".1");
        if (t.copy()) return new Ex("*" + part, t);
        return new Ex(part, t, false, "iter");
    }

    /** `Optional.empty()`, `Optional.of(x)`, `Optional.ofNullable(x)`. */
    private Ex optionalStatic(Call c, Ctx x) {
        if (!(c.target() instanceof Name n) || !n.id().equals("Optional") || x.find("Optional") != null) return null;
        JT want = null;
        switch (c.name() + "/" + c.args().size()) {
            case "empty/0":
                return new Ex("None", JT.opt("Optional", null));
            case "of/1", "ofNullable/1": {
                Ex v = ex(c.args().get(0), x);
                if (v.type().is("opt")) return new Ex(v.owned() ? v.code() : paren(v.code()) + ".clone()", JT.opt("Optional", v.type().elem()));
                Ex owned = valueTo(c.args().get(0), v.type(), x);
                return new Ex("Some(" + owned.code() + ")", JT.opt("Optional", v.type()));
            }
            default:
                throw new JavaParser.Unsupported("Optional." + c.name());
        }
    }

    private Ex optionalCall(Ex o, Call c, Ctx x) {
        JT t = o.type().elem();
        String r = paren(o.code());
        if ("staticobj".equals(o.place())) throw new JavaParser.Unsupported("an Optional kept in a changing static");
        switch (c.name() + "/" + c.args().size()) {
            case "isPresent/0": return new Ex(r + ".is_some()", JT.BOOL);
            case "isEmpty/0": return new Ex(r + ".is_none()", JT.BOOL);
            case "get/0", "orElseThrow/0", "getAsInt/0", "getAsDouble/0": return unwrapOpt(o);
            case "orElse/1": {
                if (t == null) throw new JavaParser.Unsupported("an Optional of unknown type");
                if (isNull(c.args().get(0))) return new Ex(o.owned() ? o.code() : r + ".clone()", JT.opt("nullable", t));
                String d = valueTo(c.args().get(0), t, x).code();
                if (t.copy()) return new Ex(r + ".unwrap_or(" + d + ")", t);
                return new Ex((o.owned() ? r : r + ".clone()") + ".unwrap_or(" + d + ")", t);
            }
            case "map/1", "flatMap/1": {
                if (t == null) throw new JavaParser.Unsupported("an Optional of unknown type");
                Fn f = closure(c.args().get(0), List.of(t), false, null, x);
                boolean flat = c.name().equals("flatMap");
                if (flat && !f.ret().is("opt")) throw new JavaParser.Unsupported("flatMap to something other than an Optional");
                String src = o.owned() || t.copy() ? r : r + ".clone()";
                return new Ex(src + (flat ? ".and_then(" : ".map(") + f.code() + ")", flat ? JT.opt("Optional", f.ret().elem()) : JT.opt("Optional", f.ret()));
            }
            case "filter/1": {
                if (t == null) throw new JavaParser.Unsupported("an Optional of unknown type");
                Fn f = closure(c.args().get(0), List.of(t), true, JT.BOOL, x);
                return new Ex((o.owned() || t.copy() ? r : r + ".clone()") + ".filter(" + f.code() + ")", JT.opt("Optional", t));
            }
            case "ifPresent/1": {
                if (t == null) throw new JavaParser.Unsupported("an Optional of unknown type");
                Fn f = closure(c.args().get(0), List.of(t), false, JT.VOID, x);
                return new Ex("if let Some(hp_v) = " + (o.owned() || t.copy() ? r : r + ".clone()") + " { (" + f.code() + ")(hp_v); }", JT.VOID);
            }
            case "orElseGet/1": {
                if (t == null) throw new JavaParser.Unsupported("an Optional of unknown type");
                if (!(c.args().get(0) instanceof Lambda l) || !l.params().isEmpty() || !(l.body() instanceof Expr body))
                    throw new JavaParser.Unsupported("orElseGet with something other than a short lambda");
                String d = valueTo(body, t, x).code();
                if (t.copy()) return new Ex(r + ".unwrap_or_else(|| " + d + ")", t);
                return new Ex((o.owned() ? r : r + ".clone()") + ".unwrap_or_else(|| " + d + ")", t);
            }
            default:
                throw new JavaParser.Unsupported("Optional." + c.name());
        }
    }

    /** Which Java names are set to null or checked for null, so they become Options. Grows to a fixpoint. */
    private void findNullables() {
        for (int round = 0; round < 8; round++) {
            int before = nullable.size();
            for (Cls c : all) {
                for (FieldDecl f : c.d.fields) {
                    // Java starts a static reference with no value as null.
                    if (isNull(f.init) || f.init == null && f.mods.contains("static") && !isPrimType(f.type)) nullable.add(fieldKey(c, f.name));
                    // An instance reference no constructor sets starts as null.
                    if (f.init == null && !f.mods.contains("static") && !isPrimType(f.type) && !setByEveryConstructor(c, f.name)) nullable.add(fieldKey(c, f.name));
                }
                for (MethodDecl m : c.d.methods) {
                    if (m.body == null) continue;
                    String mk = methodKey(c, m);
                    Set<String> locals = new HashSet<>();
                    for (Param p : m.params) locals.add(p.name());
                    walk(m.body, n -> {
                        if (n instanceof Local l) l.vars().forEach(d -> locals.add(d.name()));
                    });
                    walk(m.body, n -> {
                        if (n instanceof Binary b && (b.op().equals("==") || b.op().equals("!=")) && (isNull(b.l()) || isNull(b.r())))
                            markNullable(isNull(b.l()) ? b.r() : b.l(), c, mk, locals);
                        // A value that may be null flows on, except a local, which code usually checks before using it.
                        if (n instanceof Assign a && a.op().equals("=") && (isNull(a.value()) || mayFlowNull(a.value(), m, c, mk, locals)))
                            markNullable(a.target(), c, mk, locals);
                        if (n instanceof Local l)
                            for (Declarator d : l.vars()) if (isNull(d.init()) || d.init() != null && mayFlowNull(d.init(), m, c, mk, locals)) nullable.add(mk + ":" + d.name());
                        // null, or something that may be null, passed to one of the mod's own methods or constructors
                        List<Expr> args = n instanceof Call call ? call.args() : n instanceof New nw ? nw.args() : null;
                        if (args != null) {
                            for (int i = 0; i < args.size(); i++) {
                                if (!isNull(args.get(i)) && !mayFlowNull(args.get(i), m, c, mk, locals)) continue;
                                MethodDecl md = n instanceof Call call ? ownMethod(call, c, locals) : ownCtor((New) n, c);
                                Cls o = n instanceof Call call ? ownMethodCls(call, c, locals) : ownCtorCls((New) n, c);
                                if (md != null && i < md.params.size() && !md.params.get(i).varargs()) nullable.add(methodKey(o, md) + ":" + md.params.get(i).name());
                            }
                        }
                        if (n instanceof Return r && r.e() != null && (nullish(r.e()) || isNullableExpr(r.e(), c, mk, locals))) nullable.add(mk + "#ret");
                    });
                }
            }
            if (nullable.size() == before) break;
        }
    }

    /** null, or a `?:` that can give null. */
    private static boolean nullish(Expr e) {
        return isNull(e) || e instanceof Cond c && (nullish(c.a()) || nullish(c.b()));
    }

    private void markNullable(Expr e, Cls c, String mk, Set<String> locals) {
        String k = nullableKey(e, c, mk, locals);
        if (k != null) nullable.add(k);
    }

    private boolean isNullableExpr(Expr e, Cls c, String mk, Set<String> locals) {
        String k = nullableKey(e, c, mk, locals);
        return k != null && nullable.contains(k);
    }

    private boolean mayFlowNull(Expr e, MethodDecl m, Cls c, String mk, Set<String> locals) {
        if (e instanceof Name n && locals.contains(n.id()) && m.params.stream().noneMatch(p -> p.name().equals(n.id()))) return false;
        // System.getProperty(name): null when the property isn't set (here, the environment variable).
        if (e instanceof Call call && call.name().equals("getProperty") && call.args().size() == 1
                && call.target() instanceof Name t && t.id().equals("System") && !locals.contains("System")) return true;
        return isNullableExpr(e, c, mk, locals);
    }

    private Cls ownMethodCls(Call call, Cls c, Set<String> locals) {
        return call.target() == null || call.target() instanceof This ? c
                : call.target() instanceof Name cn && !locals.contains(cn.id()) ? ownClass(cn.id(), c) : null;
    }

    /** The mod's own method a call goes to, when it's plain which (by class, name and count). */
    private MethodDecl ownMethod(Call call, Cls c, Set<String> locals) {
        Cls o = ownMethodCls(call, c, locals);
        if (o == null) return null;
        List<MethodDecl> ms = o.methods.getOrDefault(call.name(), List.of()).stream().filter(md -> md.params.size() == call.args().size()).toList();
        return ms.size() == 1 ? ms.get(0) : null;
    }

    private Cls ownCtorCls(New nw, Cls c) {
        Cls o = ownClass(nw.type().name(), c);
        return o != null ? o : classes.get(nw.type().name());
    }

    private MethodDecl ownCtor(New nw, Cls c) {
        Cls o = ownCtorCls(nw, c);
        if (o == null) return null;
        List<MethodDecl> ms = o.methods.getOrDefault("<init>", List.of()).stream().filter(md -> md.params.size() == nw.args().size()).toList();
        return ms.size() == 1 ? ms.get(0) : null;
    }

    /** The declaration a name, field or own-method call refers to, for the nullable facts. */
    private String nullableKey(Expr e, Cls c, String mk, Set<String> locals) {
        if (e instanceof Name n) {
            if (locals.contains(n.id())) return mk + ":" + n.id();
            Cls o = fieldOwner(n.id(), c, false);
            if (o == null) o = fieldOwner(n.id(), c, true);
            return o == null ? null : fieldKey(o, n.id());
        }
        if (e instanceof Field f) {
            if (f.target() instanceof This) {
                Cls o = fieldOwner(f.name(), c, false);
                return o == null ? null : fieldKey(o, f.name());
            }
            if (f.target() instanceof Name cn && !locals.contains(cn.id())) {
                Cls o = ownClass(cn.id(), c);
                if (o != null && o.fields.containsKey(f.name())) return fieldKey(o, f.name());
            }
            return null;
        }
        if (e instanceof Call call) {
            Cls o = call.target() == null || call.target() instanceof This ? c
                    : call.target() instanceof Name cn && !locals.contains(cn.id()) ? ownClass(cn.id(), c) : null;
            if (o == null) return null;
            for (MethodDecl md : o.methods.getOrDefault(call.name(), List.of()))
                if (md.params.size() == call.args().size()) return methodKey(o, md) + "#ret";
        }
        return null;
    }

    // ---- numbers ----

    private static final List<String> RANK = List.of("byte", "short", "char", "int", "long", "float", "double");

    private static JT promote(JT a, JT b) {
        if (!a.numeric() || !b.numeric()) throw new JavaParser.Unsupported("arithmetic on " + a + " and " + b);
        int ra = Math.max(RANK.indexOf(a.name()), 3), rb = Math.max(RANK.indexOf(b.name()), 3);
        return JT.prim(RANK.get(Math.max(ra, rb)));
    }

    /** `v` as type `t`, with an `as` cast where Java would widen or narrow. */
    private static Ex numeric(Ex v, JT t) {
        if (v.type().equals(t)) return v;
        if (!v.type().numeric() || !t.numeric()) {
            if (v.type().equals(t)) return v;
            throw new JavaParser.Unsupported("a " + v.type() + " used as a " + t);
        }
        if ("lit".equals(v.place()) && v.code().matches("-?\\d+") && t.floating())
            return new Ex(v.code() + ".0" + rustPrim(t.name()), t, true, "lit");
        if ("lit".equals(v.place()) && v.code().matches("-?\\d+") && Set.of("long", "int", "short", "byte").contains(t.name()))
            return new Ex(v.code() + rustPrim(t.name()), t, true, "lit");
        if (t.name().equals("char")) return new Ex("char::from_u32(" + paren(v.code()) + " as u32).unwrap_or('\\0')", t);
        if (v.type().name().equals("char") && t.floating()) return new Ex("(" + paren(v.code()) + " as u32 as " + rustPrim(t.name()) + ")", t);
        return new Ex("(" + paren(v.code()) + " as " + rustPrim(t.name()) + ")", t);
    }

    private static String usize(Ex v) {
        if (!v.type().numeric() || v.type().floating()) throw new JavaParser.Unsupported("an index that isn't a whole number");
        if (v.code().matches("\\d+")) return v.code();
        return paren(v.code()) + " as usize";
    }

    static String rustPrim(String java) {
        return switch (java) {
            case "int" -> "i32";
            case "long" -> "i64";
            case "float" -> "f32";
            case "double" -> "f64";
            case "boolean" -> "bool";
            case "char" -> "char";
            case "byte" -> "i8";
            case "short" -> "i16";
            default -> throw new JavaParser.Unsupported("type " + java);
        };
    }

    // ---- types ----

    private JT resolve(TypeRef t, Cls ctx) {
        if (t == null) return JT.VOID;
        if (t.dims() > 0) {
            JT e = resolve(t.elem(), ctx);
            return new JT("array", "array", e);
        }
        String n = t.name();
        switch (n) {
            case "void": return JT.VOID;
            case "int": case "Integer": return JT.INT;
            case "long": case "Long": return JT.LONG;
            case "float": case "Float": return JT.FLOAT;
            case "double": case "Double": return JT.DOUBLE;
            case "boolean": case "Boolean": return JT.BOOL;
            case "char": case "Character": return JT.CHAR;
            case "byte": case "Byte": return JT.prim("byte");
            case "short": case "Short": return JT.prim("short");
            case "String": case "java.lang.String": case "CharSequence": case "StringBuilder": return JT.STRING;
            case "List": case "ArrayList": case "java.util.List": case "Collection": case "Iterable":
                if (t.args().size() != 1) throw new JavaParser.Unsupported("a raw List");
                return new JT("list", "list", resolve(t.args().get(0), ctx));
            case "Identifier": case "ResourceLocation":
                return IDENT;
            case "Supplier": case "BooleanSupplier": case "IntSupplier": case "LongSupplier": case "DoubleSupplier": case "Runnable":
            case "Predicate": case "BiPredicate": case "IntPredicate": case "Function": case "BiFunction": case "UnaryOperator":
            case "BinaryOperator": case "Consumer": case "BiConsumer": case "IntConsumer": case "DoubleConsumer": case "IntFunction":
            case "DoubleFunction": case "ToIntFunction": case "ToDoubleFunction": case "IntUnaryOperator": case "DoubleUnaryOperator":
                return functional(t, ctx);
            case "Pattern": case "java.util.regex.Pattern": return REGEX;
            case "Properties": return PROPS;
            case "HttpClient": case "HttpClient.Builder": return HTTPCLIENT;
            case "HttpRequest": case "HttpRequest.Builder": return HTTPREQ;
            case "HttpResponse": return HTTPRESP;
            case "Duration": case "java.time.Duration": case "Executor": return DURATION;
            case "URI": case "java.net.URI": return JT.STRING;
            case "Version": case "SemanticVersion": return VERSION;
            case "ModContainer": case "ModMetadata": return MODC;
            case "ModDependency": return MODDEP;
            case "Iterator": case "java.util.Iterator":
                // Raw (as decompilers write it): the element type comes from the `.iterator()` it's set from.
                return new JT("iter", "Iterator", t.args().size() == 1 ? resolve(t.args().get(0), ctx) : null);
            case "CompletableFuture":
                if (t.args().size() != 1) throw new JavaParser.Unsupported("a raw CompletableFuture");
                return new JT("future", "CompletableFuture", resolve(t.args().get(0), ctx));
            // File readers and writers become the file's text (see withoutResources).
            case "Reader": case "BufferedReader": case "Writer": case "BufferedWriter": return JT.STRING;
            case "JsonElement": case "JsonObject": case "JsonArray": case "JsonPrimitive": return JSON;
            case "Gson": case "GsonBuilder": return GSON;
            case "AtomicInteger": return new JT("atomic", "AtomicI32", JT.INT);
            case "AtomicLong": return new JT("atomic", "AtomicI64", JT.LONG);
            case "AtomicBoolean": return new JT("atomic", "AtomicBool", JT.BOOL);
            case "Matcher": case "java.util.regex.Matcher": return MATCHER;
            case "Path": case "java.nio.file.Path":
                return PATH;
            case "RandomSource": case "Random": case "java.util.Random":
                return RANDOM;
            case "Vector3f": case "Vector3fc": return VEC3F;
            case "Quaternionf": case "Quaternionfc": return QUAT;
            case "Matrix4f": case "Matrix4fc": return MAT4;
            case "Matrix3f": case "Matrix3fc": return MAT3;
            case "PoseStack": case "MatrixStack": return POSESTACK;
            case "PoseStack.Pose": return POSELAST;
            case "Map": case "HashMap": case "LinkedHashMap": case "java.util.Map": case "TreeMap": case "SortedMap": case "NavigableMap":
            case "ConcurrentHashMap": case "ConcurrentMap": case "ConcurrentSkipListMap": case "IdentityHashMap": case "WeakHashMap":
            case "Map.Entry": case "Entry": {
                if (t.args().size() != 2) throw new JavaParser.Unsupported("a raw Map");
                JT k = resolve(t.args().get(0), ctx), v = resolve(t.args().get(1), ctx);
                hashKey(k);
                if (n.endsWith("Entry")) return new JT("entry", "entry", v, k);
                return new JT("map", "map", v, k);
            }
            case "Set": case "HashSet": case "LinkedHashSet": case "java.util.Set": case "TreeSet": case "SortedSet": case "NavigableSet":
            case "CopyOnWriteArraySet": case "ConcurrentSkipListSet": {
                if (t.args().size() != 1) throw new JavaParser.Unsupported("a raw Set");
                JT e = resolve(t.args().get(0), ctx);
                hashKey(e);
                return new JT("set", "set", e);
            }
            case "Optional": case "java.util.Optional": {
                if (t.args().size() != 1) throw new JavaParser.Unsupported("a raw Optional");
                return JT.opt("Optional", resolve(t.args().get(0), ctx));
            }
            case "OptionalInt": return JT.opt("Optional", JT.INT);
            case "OptionalDouble": return JT.opt("Optional", JT.DOUBLE);
            default:
                break;
        }
        if (gameMode) {
            JT g = gameType(t.simple());
            if (g != null) return g;
        }
        Cls c = ownClass(n, ctx);
        if (c == null && n.contains(".")) c = classes.get(n);
        if (c != null) {
            if (c.failed != null) throw new JavaParser.Unsupported("it uses " + c.d.name + ", which isn't translated (" + c.failed + ")");
            return new JT(c.simpleEnum ? "enum" : "cls", c.d.qualified, null);
        }
        throw new JavaParser.Unsupported("it uses " + describe(t.simple(), ctx));
    }

    private String owned(JT t, Cls ctx) {
        return switch (t.k()) {
            case "prim" -> rustPrim(t.name());
            case "string" -> "String";
            case "array", "list" -> "Vec<" + owned(t.elem(), ctx) + ">";
            case "cls", "enum" -> {
                Cls c = classes.get(t.name());
                yield c.module + "::" + c.rust;
            }
            case "void" -> "()";
            case "player" -> "Option<crate::game::native_hooks::NativePlayer>";
            case "comp" -> "horizon_client::text::Text";
            case "ident" -> "JIdentifier";
            case "path" -> "std::path::PathBuf";
            case "regex" -> "JRegex";
            case "fn" -> throw new JavaParser.Unsupported("a function kept in a variable or field (only parameters can be)");
            case "props" -> "JProps";
            case "httpclient" -> "JHttpClient";
            case "httpreq" -> "JHttpRequest";
            case "httpresp" -> "JHttpResponse";
            case "duration" -> "std::time::Duration";
            case "version" -> "JVersion";
            case "modc" -> "JMod";
            case "moddep" -> "JModDep";
            case "depkind" -> "&'static str";
            case "future" -> "JFuture<" + owned(t.elem(), ctx) + ">";
            case "iter" -> "JIter<" + (t.elem() == null ? "_" : owned(t.elem(), ctx)) + ">";
            case "json" -> "JJson";
            case "gson" -> "JGson";
            case "atomic" -> "std::sync::atomic::" + t.name();
            case "matcher" -> "JMatcher";
            case "random" -> "JRandom";
            case "useanim", "displayctx" -> "&'static str";
            case "renderstate" -> "JRenderState";
            case "modeltype" -> "bool";
            case "vec3f" -> "JVec3";
            case "quat" -> "JQuat";
            case "mat4", "poselast" -> "JMat4";
            case "mat3" -> "JMat3";
            case "posestack" -> "JPoseStack";
            case "stack" -> "Option<horizon_client::items::ItemStack>";
            case "item" -> "String";
            case "hand" -> HAND_T;
            case "arm" -> "bool";
            case "vec3d" -> "[f64; 3]";
            case "map" -> "JMap<" + owned(t.key(), ctx) + ", " + owned(t.elem(), ctx) + ">";
            case "set" -> "JSet<" + owned(t.elem(), ctx) + ">";
            case "opt" -> {
                if (t.elem() == null) throw new JavaParser.Unsupported("an Optional of unknown type");
                yield "Option<" + owned(t.elem(), ctx) + ">";
            }
            default -> throw new JavaParser.Unsupported("Minecraft's " + javaName(t) + " kept in a variable or field");
        };
    }

    /** Map keys and set elements: whole numbers, chars, booleans, enums, strings, identifiers (Rust needs Eq and Hash). */
    private static void hashKey(JT k) {
        if (k.floating()) throw new JavaParser.Unsupported("a Map or Set of floating-point numbers");
        if (!(k.copy() || k.is("string") || k.is("ident") || k.is("item"))) throw new JavaParser.Unsupported("a Map or Set keyed by a " + k);
    }

    /** A field's type, as an Option when Java sets it to null or checks it for null. */
    private JT fieldType(Cls c, FieldDecl f) {
        JT t = resolve(f.type, c);
        if (!isPrimType(f.type) && !t.is("opt") && canBeNull(t) && nullable.contains(fieldKey(c, f.name))) return JT.opt("nullable", t);
        return t;
    }

    /** A method's return type, as an Option when it can return null. */
    private JT retType(Cls c, MethodDecl m) {
        JT t = resolve(m.ret, c);
        if (m.ret != null && !isPrimType(m.ret) && !t.is("void") && !t.is("opt") && canBeNull(t) && nullable.contains(methodKey(c, m) + "#ret"))
            return JT.opt("nullable", t);
        return t;
    }

    /** Types whose null becomes None. The player is already an Option; other Minecraft stand-ins have their own null checks. */
    private static boolean canBeNull(JT t) {
        return Set.of("string", "array", "list", "cls", "enum", "ident", "map", "set", "prim", "hand", "useanim", "vec3f", "quat", "mat4", "json",
                "version", "modc", "moddep", "future", "httpresp", "httpreq", "httpclient", "duration", "fn").contains(t.k());
    }

    /** A parameter's type: an Option when the method checks it for null or a caller passes null. */
    private JT paramType(Cls c, MethodDecl m, int i) {
        Param p = m.params.get(i);
        JT t = resolve(p.type(), c);
        if (!isPrimType(p.type()) && !t.is("opt") && canBeNull(t) && !p.varargs() && nullable.contains(methodKey(c, m) + ":" + p.name())) return JT.opt("nullable", t);
        return t;
    }

    private String param(JT t, boolean mut, Cls ctx) {
        return switch (t.k()) {
            case "ident" -> "&JIdentifier";
            case "json" -> (mut ? "&mut " : "&") + "JJson";
            case "fn" -> fnRust(t, ctx);
            case "path" -> "&std::path::Path";
            case "posestack" -> "&mut JPoseStack";
            case "iter" -> throw new JavaParser.Unsupported("an Iterator passed as a parameter");
            case "stack" -> "&Option<horizon_client::items::ItemStack>";
            case "item" -> "&str";
            case "string" -> mut ? "&mut String" : "&str";
            case "map", "set" -> (mut ? "&mut " : "&") + owned(t, ctx);
            case "opt" -> {
                if (t.name().equals("Optional")) throw new JavaParser.Unsupported("an Optional parameter");
                // A function Java passes null for: Option<&dyn Fn(..)>.
                if (t.elem() != null && t.elem().is("fn")) yield "Option<" + fnRust(t.elem(), ctx) + ">";
                yield owned(t, ctx);
            }
            case "array", "list" -> (mut && t.is("list") ? "&mut Vec<" + owned(t.elem(), ctx) + ">" : (mut ? "&mut [" : "&[") + owned(t.elem(), ctx) + "]");
            case "cls" -> (mut ? "&mut " : "&") + owned(t, ctx);
            default -> owned(t, ctx);
        };
    }

    // ---- the game side ----

    /** Minecraft's Component: Horizon's Text (styled spans), on the game side. */
    static final JT COMP = new JT("comp", "Component", null);
    private static final JT GAME = new JT("game", "Minecraft", null), PLAYER = new JT("player", "LocalPlayer", null),
            IDENT = new JT("ident", "Identifier", null);
    /** Minecraft objects that only stand for something Horizon answers (no Rust value of their own). */
    private static final Set<String> GAME_VALUES = Set.of("modkey", "vkey", "screen", "level", "options", "inventory", "gamemode", "hook", "dispatcher",
            "avatar", "skin", "resolver");

    /**
     * Game state read straight from Horizon's Game (its private fields, which code inside game.rs can see). These are
     * exact field names of one Horizon commit, so a bundle using them pins that commit; `check` verifies each one.
     */
    static final JT USEANIM = new JT("useanim", "ItemUseAnimation", null);
    static final JT RENDERSTATE = new JT("renderstate", "ItemStackRenderState", null);
    static final JT STACK = new JT("stack", "ItemStack", null), ITEM = new JT("item", "Item", null), HAND = new JT("hand", "InteractionHand", null),
            ARM = new JT("arm", "HumanoidArm", null), VEC3D = new JT("vec3d", "Vec3", null);
    private static final String MAIN_STACK = "game.inventory.get(game.selected).cloned().flatten()";
    private static final String HAND_T = "horizon_client::first_person::Hand";
    /** The Horizon declarations the state mappings rely on: file under crates/, and the line as it is there. */
    static final List<String[]> STATE_DECLS = List.of(
            new String[] {"horizon-client/src/game.rs", "    inventory: Vec<Option<ItemStack>>,"},
            new String[] {"horizon-client/src/game.rs", "    selected: usize,"},
            new String[] {"horizon-client/src/game.rs", "    offhand: Option<ItemStack>,"},
            new String[] {"horizon-client/src/game.rs", "    using: Option<(usize, u32)>,"},
            new String[] {"horizon-client/src/game.rs", "    player: Option<PlayerMotion>,"},
            new String[] {"horizon-client/src/game.rs", "    settings: Settings,"},
            new String[] {"horizon-client/src/game.rs", "    potions: potions::PotionState,"},
            new String[] {"horizon-client/src/game.rs", "    fn used_hand(&self) -> Option<Hand> {"},
            new String[] {"horizon-items/src/items.rs", "    pub item: Arc<str>,"},
            new String[] {"horizon-items/src/items.rs", "    pub count: u32,"},
            new String[] {"horizon-items/src/items.rs", "    pub damage: u32,"},
            new String[] {"horizon-client/src/motion.rs", "    pub sprinting: bool,"},
            new String[] {"horizon-client/src/motion.rs", "    pub shift_key_down: bool,"},
            new String[] {"horizon-client/src/motion.rs", "    pub pose: crate::pose::Pose,"},
            new String[] {"horizon-client/src/motion.rs", "    pub ground: GroundState,"},
            new String[] {"horizon-client/src/motion.rs", "    pub velocity: Vec3,"},
            new String[] {"horizon-client/src/motion.rs", "    pub swimming: bool,"},
            new String[] {"horizon-client/src/motion.rs", "    pub fall_flying: bool,"},
            new String[] {"horizon-client/src/motion.rs", "    pub sleeping: bool,"},
            new String[] {"horizon-client/src/motion.rs", "    pub auto_spin_attack_ticks: i32,"},
            new String[] {"horizon-world/src/ground_support.rs", "    pub on_ground: bool,"},
            new String[] {"horizon-client/src/pose.rs", "    Crouching,"},
            new String[] {"horizon-client/src/first_person.rs", "pub enum Hand {"},
            new String[] {"horizon-client-render/src/settings.rs", "    pub main_hand_left: bool,"},
            new String[] {"horizon-client/src/game/potions.rs", "    pub(super) effects: PlayerEffects,"},
            new String[] {"horizon-client-render/src/effects.rs", "    pub fn get(&self, id: &str) -> Option<&EffectInstance> {"},
            new String[] {"horizon-client/src/game.rs", "    res: Arc<Resources>,"},
            new String[] {"horizon-client/src/game.rs", "    fn items(&self) -> &Items {"},
            new String[] {"horizon-client/src/game.rs", "    swing: horizon_client::first_person::SwingState,"},
            new String[] {"horizon-client/src/game.rs", "    ticks: u64,"},
            new String[] {"horizon-client/src/game.rs", "    yaw: f32,"},
            new String[] {"horizon-items/src/items.rs", "    pub fn get(&self, id: &str) -> Option<&ItemInfo> {"},
            new String[] {"horizon-items/src/items.rs", "    pub consumable: Option<horizon_world::consumable::Consumable<String, ConsumeEffect>>,"},
            new String[] {"horizon-items/src/items.rs", "    pub blocks_attacks: Option<BlocksAttacks>,"},
            new String[] {"horizon-world/src/consumable.rs", "    pub animation: UseAnimation,"},
            new String[] {"horizon-items/src/items.rs", "    pub block: Option<BlockStateId>,"},
            new String[] {"horizon-items/src/items.rs", "    pub item_tags: HashMap<String, HashSet<String>>,"},
            new String[] {"horizon-client/src/spear.rs", "    pub fn of(item: &str) -> Option<Self> {"},
            new String[] {"horizon-client/src/motion.rs", "    pub position: Vec3,"},
            new String[] {"horizon-client/src/game.rs", "    hook: Option<fishing::Hook>,"},
            new String[] {"horizon-client/src/game.rs", "    mining: Option<Mining>,"},
            new String[] {"horizon-client/src/game.rs", "    heights: horizon_client::first_person::HandHeights,"},
            new String[] {"horizon-client/src/game/hand.rs", "    pub(super) fn visible_hand_items(&self) -> [Option<ItemStack>; 2] {"},
            new String[] {"horizon-client/src/game/hand.rs", "    pub(super) fn hands_to_render(&self) -> [bool; 2] {"},
            new String[] {"horizon-client/src/first_person.rs", "    pub fn interpolated(&self, partial: f32) -> [f32; 2] {"},
            new String[] {"horizon-client/src/first_person.rs", "    pub fn animation(&self, partial: f32) -> f32 {"},
            new String[] {"horizon-client/src/game.rs", "    player_model: Option<Mob>,"},
            new String[] {"horizon-client/src/game/maps.rs", "pub(super) fn map_id(stack: &ItemStack) -> Option<i32> {"},
            new String[] {"horizon-client/src/game/item_draw.rs", "    pub(super) fn player_owner(&self, stack: Option<&ItemStack>) -> OwnerState {"},
            new String[] {"horizon-assets/src/item_model.rs", "    pub use_item_remaining_ticks: i32,"},
            new String[] {"horizon-client/src/game/item_draw.rs", "    pub(super) fn resolve_item("},
            new String[] {"horizon-client/src/game.rs", "    clock: FixedStep,"},
            new String[] {"horizon-client/src/stations.rs", "pub fn hover_name(names: &dyn Fn(&str) -> String, stack: &ItemStack) -> String {"},
            new String[] {"horizon-client/src/game.rs", "    names: Arc<Names>,"},
            new String[] {"horizon-client/src/creative.rs", "    pub fn display_item(&self, id: &str) -> String {"},
            new String[] {"horizon-client/src/game.rs", "    pack: PackResources,"},
            new String[] {"horizon-client/src/clock.rs", "    pub fn fraction(&self) -> f64 {"},
            new String[] {"horizon-client/src/clock.rs", "    pub fn camera_fraction(&self) -> f64 {"},
            new String[] {"horizon-client/src/game/item_draw.rs", "        display: DisplayContext,"},
            new String[] {"horizon-client/src/item_render.rs", "    pub fn uses_block_light(&self) -> bool {"},
            new String[] {"horizon-assets/src/item_model.rs", "    ThirdPersonRightHand,"},
            new String[] {"horizon-assets/src/item_model.rs", "    OnShelf,"},
            new String[] {"horizon-client/src/mobs.rs", "    pub slim: bool,"},
            new String[] {"horizon-items/src/item_data.rs", "    pub fn data(&self) -> Option<&StackData> {"},
            new String[] {"horizon-items/src/item_data.rs", "    pub charged: Vec<ItemStack>,"},
            new String[] {"horizon-client/src/game/crossbow.rs", "    pub(super) fn charge_ticks(stack: &ItemStack) -> u32 {"},
            new String[] {"horizon-client/src/spear.rs", "    pub fn attack_animation(&self) -> SwingAnimation {"},
            new String[] {"horizon-client/src/first_person.rs", "    pub const DEFAULT: SwingAnimation = SwingAnimation {"},
            new String[] {"horizon-client/src/first_person.rs", "    pub kind: u8,"},
            new String[] {"horizon-client/src/first_person.rs", "    pub duration: i32,"},
            new String[] {"horizon-client/src/game.rs", "    pitch: f32,"},
            new String[] {"horizon-world/src/consumable.rs", "    TootHorn,"},
            new String[] {"horizon-client/src/first_person.rs", "    pub ticks: i32,"},
            new String[] {"horizon-client/src/first_person.rs", "    pub fn swinging(&self) -> bool {"},
            new String[] {"horizon-client/src/first_person.rs", "    pub fn attack_hand(&self) -> Hand {"},
            // Chat messages with styles and links (Component), as native_chat adds plain ones.
            new String[] {"horizon-client/src/game.rs", "    screen: Screen,"},
            new String[] {"horizon-client/src/game.rs", "    Chat,"},
            new String[] {"horizon-client/src/game.rs", "    gui: UiResources,"},
            new String[] {"horizon-client/src/game.rs", "    pub atlas: HudAtlas,"},
            new String[] {"horizon-client/src/game.rs", "    ui: chat::UiState,"},
            new String[] {"horizon-client/src/game/chat.rs", "    pub chat: Chat,"},
            new String[] {"horizon-client-render/src/chat.rs", "    pub fn add(\n        &mut self,\n        atlas: &HudAtlas,\n        content: Text,\n        indicator: Option<u32>,\n        source: ChatSource,\n        ticks: u64,\n        focused: bool,\n    ) {"},
            new String[] {"horizon-client-render/src/chat.rs", "    Client,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub fn empty() -> Self {"},
            new String[] {"horizon-client-core/src/text.rs", "    pub fn literal(s: impl Into<String>) -> Self {"},
            new String[] {"horizon-client-core/src/text.rs", "    pub fn append(mut self, other: impl Into<Text>) -> Self {"},
            new String[] {"horizon-client-core/src/text.rs", "    pub fn with_style(mut self, style: &Style) -> Self {"},
            new String[] {"horizon-client-core/src/text.rs", "    pub fn plain(&self) -> String {"},
            new String[] {"horizon-client-core/src/text.rs", "    pub color: Option<u32>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub bold: Option<bool>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub italic: Option<bool>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub underlined: Option<bool>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub strikethrough: Option<bool>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub obfuscated: Option<bool>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub click: Option<ClickEvent>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub hover: Option<Box<Text>>,"},
            new String[] {"horizon-client-core/src/text.rs", "    pub insertion: Option<String>,"},
            new String[] {"horizon-client-core/src/text.rs", "    OpenUrl(String),"},
            new String[] {"horizon-client-core/src/text.rs", "    RunCommand(String),"},
            new String[] {"horizon-client-core/src/text.rs", "    SuggestCommand(String),"},
            new String[] {"horizon-client-core/src/text.rs", "    CopyToClipboard(String),"},
            // Opening the mod's page in Options > Mods (setScreen of the mod's own screen).
            new String[] {"horizon-client-render/src/settings.rs", "    pub mods: crate::mod_list::ModList,"},
            new String[] {"horizon-client-render/src/mod_list.rs", "    pub installed: Vec<Installed>,"},
            new String[] {"horizon-client-render/src/mod_list.rs", "    pub id: String,"},
            new String[] {"horizon-client-render/src/options_ui.rs", "    NativeMod(usize),"},
            new String[] {"horizon-client/src/game/options.rs", "    pub(super) fn open_options_at(&mut self, from_title: bool, page: Page) {"});

    /** `Items.FISHING_ROD`, `InteractionHand.MAIN_HAND`, `HumanoidArm.LEFT`, `ItemStack.EMPTY`. */
    private static Ex gameConst(String cls, String name) {
        switch (cls) {
            case "Items":
                if (name.matches("[A-Z0-9_]+")) return new Ex("\"minecraft:" + name.toLowerCase() + "\".to_string()", ITEM);
                return null;
            case "InteractionHand":
                if (name.equals("MAIN_HAND")) return new Ex(HAND_T + "::Main", HAND, true, "const");
                if (name.equals("OFF_HAND")) return new Ex(HAND_T + "::Off", HAND, true, "const");
                return null;
            case "HumanoidArm":
                if (name.equals("LEFT")) return new Ex("true", ARM, true, "const");
                if (name.equals("RIGHT")) return new Ex("false", ARM, true, "const");
                return null;
            case "ItemStack":
                if (name.equals("EMPTY")) return new Ex("None", STACK);
                return null;
            case "ItemUseAnimation":
                if (name.matches("[A-Z_]+")) return new Ex("\"" + name + "\"", USEANIM, true, "const");
                return null;
            case "SwingAnimationType":
                return switch (name) {
                    case "NONE" -> new Ex("0u8", new JT("swingtype", "SwingAnimationType", null), true, "const");
                    case "WHACK" -> new Ex("1u8", new JT("swingtype", "SwingAnimationType", null), true, "const");
                    case "STAB" -> new Ex("2u8", new JT("swingtype", "SwingAnimationType", null), true, "const");
                    default -> null;
                };
            case "ItemDisplayContext":
                if (name.matches("[A-Z_]+")) return new Ex("\"" + name.toLowerCase() + "\"", new JT("displayctx", "ItemDisplayContext", null), true, "const");
                return null;
            case "PlayerModelType":
                if (name.equals("SLIM")) return new Ex("true", new JT("modeltype", "PlayerModelType", null), true, "const");
                if (name.equals("WIDE")) return new Ex("false", new JT("modeltype", "PlayerModelType", null), true, "const");
                return null;
            case "PlayerModelPart":
                return new Ex("()", new JT("modelpart", "PlayerModelPart", null), true, "const");
            case "ItemTags":
                if (name.matches("[A-Z0-9_]+")) return new Ex("\"minecraft:" + name.toLowerCase() + "\"", new JT("itemtag", "TagKey", null), true, "const");
                return null;
            default:
                return null;
        }
    }

    /** The local player's motion state (Java would throw without a player too). */
    private static String motion(String field) {
        return "game.player.as_ref().expect(\"no player\")." + field;
    }

    /** Player, item stack, inventory and Vec3 reads from Horizon's own state. Null when not one of these. */
    /** A mixin's @Shadow method on the hand renderer: renderItem draws the stack at the pose. */
    private Ex shadowCall(Call c, Ctx x) {
        MethodDecl m = null;
        for (MethodDecl md : x.cls.methods.getOrDefault(c.name(), List.of())) if (md.params.size() == c.args().size()) m = md;
        if (m == null || m.body != null) return null;
        if (c.name().equals("renderItem") && c.args().size() == 6) {
            Ex stack = ex(c.args().get(1), x), ctx = ex(c.args().get(2), x), pose = ex(c.args().get(3), x);
            if (!stack.type().is("stack") || !ctx.type().is("displayctx") || !pose.type().is("posestack"))
                throw new JavaParser.Unsupported("renderItem with other values");
            return new Ex("j_pose_draw_item(&" + paren(staticValue(stack).code()) + ", " + ctx.code() + ", " + paren(pose.code()) + ".last_pose())", JT.VOID);
        }
        throw new JavaParser.Unsupported("it calls the mixin's @Shadow " + c.name());
    }

    private Ex stateCall(Ex t, Call c, Ctx x) {
        // A stack kept in a changing static: the stack calls below only read it, so they read a copy.
        if (t.type().is("stack") && "staticobj".equals(t.place())) t = new Ex(withRead(t, ".clone()"), t.type(), true, null);
        String r = paren(t.code());
        List<Expr> a = c.args();
        switch (t.type().k() + "." + c.name() + "/" + a.size()) {
            case "player.isUsingItem/0": return new Ex("game.using.is_some()", JT.BOOL);
            case "dispatcher.getPlayerRenderer/1": return new Ex("avatar", new JT("avatar", "AvatarRenderer", null), true, "alias");
            case "game.getItemModelResolver/0": return new Ex("resolver", new JT("resolver", "ItemModelResolver", null), true, "alias");
            case "gui.screen/0": return new Ex("screen", new JT("screen", "Screen", null), true, "alias");
            case "game.getDeltaTracker/0": return new Ex("delta", new JT("delta", "DeltaTracker", null), true, "alias");
            case "delta.getGameTimeDeltaPartialTick/1": {
                // Horizon's clock answers both forms: the camera one ignores a frozen game.
                boolean camera = a.get(0) instanceof Lit l && l.text().equals("true");
                if (!(a.get(0) instanceof Lit)) throw new JavaParser.Unsupported("getGameTimeDeltaPartialTick with a computed flag");
                return new Ex("(game.clock." + (camera ? "camera_fraction" : "fraction") + "() as f32)", JT.FLOAT);
            }
            case "resolver.updateForLiving/4": case "resolver.updateForTopItem/4": {
                Ex st = ex(a.get(0), x), stack = ex(a.get(1), x), ctx = ex(a.get(2), x);
                if (!st.type().is("renderstate") || !stack.type().is("stack") || !ctx.type().is("displayctx"))
                    throw new JavaParser.Unsupported("ItemModelResolver." + c.name() + " with other values");
                String v = "JRenderState { stack: " + paren(staticValue(stack).code()) + ".clone(), context: " + ctx.code() + " }";
                return new Ex(assignCode(a.get(0), v, x), JT.VOID);
            }
            case "renderstate.usesBlockLight/0": return new Ex("j_uses_block_light(game, &" + paren(staticValue(t).code()) + ")", JT.BOOL);
            case "renderstate.clear/0": return new Ex(assignCode(c.target(), "JRenderState::default()", x), JT.VOID);
            case "avatar.renderRightHand/5": case "avatar.renderLeftHand/5": {
                Ex pose = ex(a.get(0), x);
                if (!pose.type().is("posestack")) throw new JavaParser.Unsupported("an arm drawn at something other than a PoseStack");
                return new Ex("j_pose_draw_arm(" + c.name().equals("renderLeftHand") + ", " + paren(pose.code()) + ".last_pose())", JT.VOID);
            }
            case "player.getSkin/0": return new Ex("skin", new JT("skin", "PlayerSkin", null), true, "alias");
            case "skin.body/0": return new Ex("skin", new JT("skin", "PlayerSkin", null), true, "alias");
            case "skin.texturePath/0": case "skin.texture/0": return new Ex("JIdentifier::new(\"minecraft\", \"skin\")", IDENT);
            case "skin.model/0": return new Ex("game.player_model.as_ref().is_some_and(|m| m.slim)", new JT("modeltype", "PlayerModelType", null));
            case "player.isModelPartShown/1": return new Ex("true", JT.BOOL, true, "lit");
            case "gamemode.isDestroying/0": return new Ex("game.mining.is_some()", JT.BOOL);
            case "player.getTicksUsingItem/0": return new Ex("game.using.map_or(0, |u| u.1 as i32)", JT.INT);
            case "player.getUseItemRemainingTicks/0":
                // Horizon works the remaining ticks out for the item renderer's owner state.
                return new Ex("(match game.used_hand() { Some(" + HAND_T + "::Main) => " + MAIN_STACK + ", Some(" + HAND_T + "::Off) => game.offhand.clone(), None => None })"
                        + ".as_ref().map_or(0, |s| game.player_owner(Some(s)).use_item_remaining_ticks)", JT.INT);
            case "player.getTicksUsingItem/1":
                return new Ex("(game.using.map_or(0, |u| u.1) as f32 + " + numeric(unwrapOpt(ex(a.get(0), x)), JT.FLOAT).code() + ")", JT.FLOAT);
            case "player.getUsedItemHand/0": return new Ex("game.used_hand().unwrap_or(" + HAND_T + "::Main)", HAND);
            case "player.getMainHandItem/0": return new Ex(MAIN_STACK, STACK);
            case "player.getOffhandItem/0": return new Ex("game.offhand.clone()", STACK);
            case "player.getItemInHand/1": {
                Ex h = valueTo(a.get(0), HAND, x);
                return new Ex("(match " + h.code() + " { " + HAND_T + "::Main => " + MAIN_STACK + ", " + HAND_T + "::Off => game.offhand.clone() })", STACK);
            }
            case "player.getUseItem/0":
                return new Ex("(match game.used_hand() { Some(" + HAND_T + "::Main) => " + MAIN_STACK + ", Some(" + HAND_T + "::Off) => game.offhand.clone(), None => None })", STACK);
            case "player.onGround/0": return new Ex(motion("ground.on_ground"), JT.BOOL);
            case "player.isSprinting/0": return new Ex(motion("sprinting"), JT.BOOL);
            case "player.isShiftKeyDown/0": return new Ex(motion("shift_key_down"), JT.BOOL);
            case "player.isCrouching/0": return new Ex("(" + motion("pose") + " == horizon_client::pose::Pose::Crouching)", JT.BOOL);
            case "player.isSwimming/0": return new Ex(motion("swimming"), JT.BOOL);
            case "player.isFallFlying/0": return new Ex(motion("fall_flying"), JT.BOOL);
            case "player.isSleeping/0": return new Ex(motion("sleeping"), JT.BOOL);
            case "player.isAutoSpinAttack/0": return new Ex("(" + motion("auto_spin_attack_ticks") + " > 0)", JT.BOOL);
            case "player.isInvisible/0": return new Ex("game.potions.effects.get(\"invisibility\").is_some()", JT.BOOL);
            case "player.getMainArm/0": return new Ex("game.settings.main_hand_left", ARM);
            case "player.getDeltaMovement/0":
                return new Ex("{ let v = &" + motion("velocity") + "; [v.x, v.y, v.z] }", VEC3D);
            case "player.getInventory/0": return new Ex("inventory", new JT("inventory", "Inventory", null), true, "alias");
            case "inventory.getSelectedSlot/0": return new Ex("(game.selected as i32)", JT.INT);
            case "inventory.getSelectedItem/0": return new Ex(MAIN_STACK, STACK);
            case "inventory.getItem/1": return new Ex("game.inventory.get(" + usize(ex(a.get(0), x)) + ").cloned().flatten()", STACK);
            case "stack.isEmpty/0": return new Ex(r + ".is_none()", JT.BOOL);
            case "stack.getUseAnimation/0": return new Ex("j_use_animation(game, &" + r + ")", USEANIM);
            case "stack.has/1": {
                if (!(a.get(0) instanceof Field cf && cf.target() instanceof Name dn && dn.id().equals("DataComponents")))
                    throw new JavaParser.Unsupported("ItemStack.has with something other than a DataComponents constant");
                if (cf.name().equals("MAP_ID")) return new Ex(r + ".as_ref().is_some_and(|s| crate::game::maps::map_id(s).is_some())", JT.BOOL);
                String comp = ABSENT_COMPONENTS.get(cf.name());
                if (comp == null) throw new JavaParser.Unsupported("ItemStack.has(DataComponents." + cf.name() + ") (not mapped yet)");
                notes.add(x.cls.d.name + "." + (x.m == null ? "" : x.m.name.replace("\u0001", "")) + ": the item's " + comp + " is treated as absent (Horizon doesn't keep it)");
                return new Ex("false", JT.BOOL, true, "lit");
            }
            case "stack.getSwingAnimation/0":
                return new Ex(r + ".as_ref().and_then(|s| horizon_client::spear::Kinetic::of(&s.item)).map_or(horizon_client::first_person::SwingAnimation::DEFAULT, |k| k.attack_animation())",
                        new JT("swinganim", "SwingAnimation", null));
            case "swinganim.type/0": return new Ex(r + ".kind", new JT("swingtype", "SwingAnimationType", null));
            case "swinganim.duration/0": return new Ex(r + ".duration", JT.INT);
            case "useanim.equals/1": {
                Ex o = ex(a.get(0), x);
                return new Ex(r + " == " + paren(o.code()), JT.BOOL);
            }
            case "stack.getItem/0": return new Ex(r + ".as_ref().map_or_else(|| \"minecraft:air\".to_string(), |s| s.item.to_string())", ITEM);
            case "stack.getHoverName/0":
                // ItemStack.getHoverName through Horizon's anvil helper: the custom name, the item name component, else the
                // item's translated name (no written-book title or potion name).
                notes.add(x.cls.d.name + "." + (x.m == null ? "" : x.m.name.replace("", "")) + ": an item's name (getHoverName) leaves out a written book's title and a potion's name");
                return new Ex(r + ".as_ref().map_or_else(|| \"Air\".to_string(), |s| horizon_client::stations::hover_name(&|id| game.pack.names.display_item(id), s))",
                        new JT("text", "Component", null));
            case "text.getString/0": return new Ex(r, JT.STRING);
            case "stack.getCount/0": return new Ex(r + ".as_ref().map_or(0, |s| s.count as i32)", JT.INT);
            case "stack.getDamageValue/0": return new Ex(r + ".as_ref().map_or(0, |s| s.damage as i32)", JT.INT);
            case "stack.copy/0": return new Ex(r + ".clone()", STACK);
            case "stack.is/1": {
                Ex i = ex(a.get(0), x);
                if (i.type().is("itemtag")) return new Ex("j_item_tag(game, &" + r + ", " + i.code() + ")", JT.BOOL);
                if (!i.type().is("item")) throw new JavaParser.Unsupported("ItemStack.is with a " + javaName(i.type()) + " (item tags aren't mapped)");
                return new Ex(r + ".as_ref().is_some_and(|s| *s.item == *" + paren(i.code()) + ")", JT.BOOL);
            }
            case "item.equals/1": {
                Ex i = ex(a.get(0), x);
                if (!i.type().is("item")) return null;
                return new Ex(r + " == " + paren(i.code()), JT.BOOL);
            }
            case "hand.equals/1": case "arm.equals/1": {
                Ex o = ex(a.get(0), x);
                return new Ex(r + " == " + paren(o.code()), JT.BOOL);
            }
            case "arm.getOpposite/0": return new Ex("!" + r, ARM);
            case "vec3d.x/0": return new Ex(r + "[0]", JT.DOUBLE);
            case "vec3d.y/0": return new Ex(r + "[1]", JT.DOUBLE);
            case "vec3d.z/0": return new Ex(r + "[2]", JT.DOUBLE);
            case "vec3d.horizontalDistance/0": return new Ex("(" + r + "[0] * " + r + "[0] + " + r + "[2] * " + r + "[2]).sqrt()", JT.DOUBLE);
            case "vec3d.horizontalDistanceSqr/0": return new Ex("(" + r + "[0] * " + r + "[0] + " + r + "[2] * " + r + "[2])", JT.DOUBLE);
            case "vec3d.length/0": return new Ex("(" + r + "[0] * " + r + "[0] + " + r + "[1] * " + r + "[1] + " + r + "[2] * " + r + "[2]).sqrt()", JT.DOUBLE);
            case "vec3d.lengthSqr/0": return new Ex("(" + r + "[0] * " + r + "[0] + " + r + "[1] * " + r + "[1] + " + r + "[2] * " + r + "[2])", JT.DOUBLE);
            default: return null;
        }
    }

    private static JT gameType(String simple) {
        return switch (simple) {
            case "Minecraft" -> GAME;
            case "LocalPlayer", "Player", "AbstractClientPlayer" -> PLAYER;
            case "GuiGraphics", "GuiGraphicsExtractor" -> new JT("gfx", "GuiGraphics", null);
            case "Font", "TextRenderer" -> new JT("font", "Font", null);
            case "DeltaTracker", "RenderTickCounter" -> new JT("delta", "DeltaTracker", null);
            case "Component", "MutableComponent", "Text", "MutableText" -> COMP;
            case "KeyMapping", "KeyBinding" -> new JT("modkey", "KeyMapping", null);
            case "ItemStack" -> STACK;
            case "Item" -> ITEM;
            case "InteractionHand", "Hand" -> HAND;
            case "HumanoidArm", "Arm" -> ARM;
            case "Vec3", "Vec3d" -> VEC3D;
            case "Inventory", "PlayerInventory" -> new JT("inventory", "Inventory", null);
            case "ItemUseAnimation", "UseAction" -> USEANIM;
            case "ItemDisplayContext" -> new JT("displayctx", "ItemDisplayContext", null);
            case "AvatarRenderer", "PlayerRenderer" -> new JT("avatar", "AvatarRenderer", null);
            case "ItemStackRenderState" -> RENDERSTATE;
            case "ItemModelResolver" -> new JT("resolver", "ItemModelResolver", null);
            // A command's context and source: the command the Fabric API layer is running (no Rust value of its own).
            case "CommandContext", "FabricClientCommandSource", "CommandSourceStack", "ServerCommandSource", "SharedSuggestionProvider" -> CMDSRC;
            default -> null;
        };
    }

    /** Game objects a game-side function gets without a parameter of its own: game, batch. */
    private static boolean implicitGame(JT t) {
        return t.is("game") || t.is("gfx") || t.is("font") || t.is("delta") || t.is("cmdsrc");
    }

    static final JT CMDSRC = new JT("cmdsrc", "FabricClientCommandSource", null);

    private static String implicitName(JT t) {
        return switch (t.k()) {
            case "game" -> "game";
            case "gfx" -> "batch";
            case "font" -> "font";
            case "cmdsrc" -> "hp_cmd";
            default -> "delta";
        };
    }

    private static String javaName(JT t) {
        return switch (t.k()) {
            case "game" -> "Minecraft";
            case "player" -> "LocalPlayer";
            case "gfx" -> "GuiGraphics";
            case "font" -> "Font";
            case "delta" -> "DeltaTracker";
            case "modkey" -> "KeyMapping";
            case "vkey" -> "KeyMapping (vanilla)";
            case "screen" -> "Screen";
            case "level" -> "ClientLevel";
            case "options" -> "Options";
            case "ident" -> "Identifier";
            case "stack" -> "ItemStack";
            case "item" -> "Item";
            case "hand" -> "InteractionHand";
            case "arm" -> "HumanoidArm";
            case "vec3d" -> "Vec3";
            case "inventory" -> "Inventory";
            case "cmdsrc" -> "FabricClientCommandSource";
            default -> t.toString();
        };
    }

    /** A game-side static's name in game_side.rs: the class module and the static, `HAND_ANIMATOR_HOLD_HAND`. */
    private String gameStaticName(Cls c, String field) {
        return (c.module + "_" + staticName(c, field)).toUpperCase();
    }

    private String glueName(Cls c, MethodDecl m) {
        if (m.name.startsWith("\u0001")) return m.name.substring(1);
        return c.module + "_" + methodName(c, m);
    }

    private void requireBatch(Ctx x) {
        if (x.batch) return;
        if (x.handler || !gameMode) throw new JavaParser.Unsupported("it draws on the HUD outside the HUD hook");
        grow(needsBatch, x.key());
        throw new JavaParser.Unsupported("it draws on the HUD (it will take the HUD batch)");
    }

    private static boolean isNull(Expr e) {
        return e instanceof Lit l && l.kind().equals("null");
    }

    private Ex nullCheck(Expr e, boolean eq, Ctx x) {
        Ex o = ex(e, x);
        return switch (o.type().k()) {
            case "player" -> new Ex(paren(o.code()) + (eq ? ".is_none()" : ".is_some()"), JT.BOOL);
            // No screen over the world: what native_playing answers.
            case "screen" -> new Ex(eq ? "game.native_playing()" : "!game.native_playing()", JT.BOOL);
            case "level" -> new Ex("game.native_player()" + (eq ? ".is_none()" : ".is_some()"), JT.BOOL);
            case "hook" -> new Ex("game.hook" + (eq ? ".is_none()" : ".is_some()"), JT.BOOL);
            // Horizon's hands (the swinging arm, the hand in use) are never missing.
            case "hand" -> new Ex(eq ? "false" : "true", JT.BOOL, true, "lit");
            // There's a game mode whenever there's a world (a player).
            case "gamemode" -> new Ex("game.native_player()" + (eq ? ".is_none()" : ".is_some()"), JT.BOOL);
            case "opt" -> {
                if ("staticobj".equals(o.place())) yield new Ex(withRead(o, eq ? ".is_none()" : ".is_some()"), JT.BOOL);
                yield new Ex(paren(o.code()) + (eq ? ".is_none()" : ".is_some()"), JT.BOOL);
            }
            default -> throw new JavaParser.Unsupported("`null` (Rust has no null: this needs an Option)");
        };
    }

    private static final Map<String, String> VANILLA_KEYS = Map.of("keyUp", "key.forward", "keyDown", "key.back", "keyLeft", "key.left",
            "keyRight", "key.right", "keyJump", "key.jump", "keyShift", "key.sneak", "keySprint", "key.sprint", "keyAttack", "key.attack",
            "keyUse", "key.use");

    private Ex gameField(Ex t, String name, Ctx x) {
        String k = t.type().k() + "." + name;
        switch (k) {
            case "player.swinging":
                return new Ex("game.swing.swinging()", JT.BOOL);
            case "player.swingTime":
                return new Ex("game.swing.ticks", JT.INT);
            case "player.swingingArm":
                return new Ex("game.swing.attack_hand()", HAND);
            case "player.yRotO":
                return new Ex("j_y_rot_o(game)", JT.FLOAT);
            case "player.xRotO":
                return new Ex("j_x_rot_o(game)", JT.FLOAT);
            case "player.moveDist":
                return new Ex("j_move_dist(game)", JT.FLOAT);
            case "game.gameMode":
                return new Ex("game_mode", new JT("gamemode", "MultiPlayerGameMode", null), true, "alias");
            case "player.fishing":
                return new Ex("game.hook", new JT("hook", "FishingHook", null), true, "alias");
            case "game.player":
                return new Ex("game.native_player()", PLAYER, true, null);
            case "game.font":
                return new Ex("font", new JT("font", "Font", null), true, "alias");
            case "game.screen":
                return new Ex("screen", new JT("screen", "Screen", null), true, "alias");
            // 26.x keeps the open screen on Minecraft.gui.
            case "game.gui":
                return new Ex("gui", new JT("gui", "Gui", null), true, "alias");
            case "game.level":
                return new Ex("level", new JT("level", "ClientLevel", null), true, "alias");
            case "game.options":
                return new Ex("options", new JT("options", "Options", null), true, "alias");
            case "font.lineHeight":
                return new Ex("9", JT.INT, true, "lit");
            default:
                if (t.type().is("options") && VANILLA_KEYS.containsKey(name))
                    return new Ex("\"" + VANILLA_KEYS.get(name) + "\"", new JT("vkey", "KeyMapping", null), true, "alias");
                throw new JavaParser.Unsupported("it uses Minecraft's " + javaName(t.type()) + "." + name + " (no Horizon helper for it yet)");
        }
    }

    /** `p.as_ref().expect(..)`: Java would throw on a missing player too. */
    private static String player(Ex t) {
        return paren(t.code()) + ".as_ref().expect(\"no player\")";
    }

    private Ex gameCall(Ex t, Call c, Ctx x) {
        if (!gameMode) throw new JavaParser.Unsupported("it calls Minecraft's " + javaName(t.type()) + "." + c.name());
        String k = t.type().k() + "." + c.name() + "/" + c.args().size();
        List<Expr> a = c.args();
        Ex state = stateCall(t, c, x);
        if (state != null) return state;
        switch (k) {
            case "player.getX/0": case "player.getY/0": case "player.getZ/0":
                return new Ex(player(t) + "." + c.name().substring(3).toLowerCase(), JT.DOUBLE);
            case "player.getYRot/0":
                return new Ex(player(t) + ".yaw", JT.FLOAT);
            case "player.getXRot/0":
                return new Ex(player(t) + ".pitch", JT.FLOAT);
            case "player.displayClientMessage/2":
                if (!(a.get(1) instanceof Lit l && l.text().equals("false")))
                    throw new JavaParser.Unsupported("a message over the hotbar (Horizon's native_chat writes to chat)");
                return chat(a.get(0), x);
            case "player.sendSystemMessage/1": case "player.sendMessage/1":
                return chat(a.get(0), x);
            case "gfx.drawString/5": case "gfx.drawString/6": case "gfx.text/5": case "gfx.text/6": {
                requireBatch(x);
                String shadow = a.size() == 6 ? valueTo(a.get(5), JT.BOOL, x).code() : "true";
                return new Ex("{ let hp_text: String = " + valueTo(a.get(1), JT.STRING, x).code() + "; let hp_at = ["
                        + numeric(ex(a.get(2), x), JT.FLOAT).code() + ", " + numeric(ex(a.get(3), x), JT.FLOAT).code() + "]; let hp_rgb = "
                        + color(a.get(4), x) + " & 0xFFFFFF; game.native_text(batch, &hp_text, hp_at, hp_rgb, " + shadow + ") }", JT.VOID);
            }
            case "gfx.drawCenteredString/5": case "gfx.centeredText/5": {
                requireBatch(x);
                return new Ex("{ let hp_text: String = " + valueTo(a.get(1), JT.STRING, x).code() + "; let hp_x = "
                        + numeric(ex(a.get(2), x), JT.FLOAT).code() + "; let hp_y = " + numeric(ex(a.get(3), x), JT.FLOAT).code() + "; let hp_rgb = "
                        + color(a.get(4), x) + " & 0xFFFFFF; let hp_w = game.native_text_width(&hp_text); "
                        + "game.native_text(batch, &hp_text, [hp_x - hp_w / 2.0, hp_y], hp_rgb, true) }", JT.VOID);
            }
            case "gfx.fill/5": {
                requireBatch(x);
                List<String> v = new ArrayList<>();
                for (int i = 0; i < 4; i++) v.add(numeric(ex(a.get(i), x), JT.FLOAT).code());
                return new Ex("{ let (hp_x1, hp_y1, hp_x2, hp_y2) = (" + String.join(", ", v) + "); let hp_argb = " + color(a.get(4), x)
                        + "; game.native_fill(batch, [hp_x1, hp_y1, hp_x2 - hp_x1, hp_y2 - hp_y1], hp_argb) }", JT.VOID);
            }
            case "font.width/1": {
                Ex s = valueTo(a.get(0), JT.STRING, x);
                return new Ex("{ let hp_text: String = " + s.code() + "; game.native_text_width(&hp_text) as i32 }", JT.INT);
            }
            case "cmdsrc.getSource/0":
                return new Ex("hp_cmd", CMDSRC, true, "alias");
            case "cmdsrc.getClient/0":
                return new Ex("game", GAME, true, "alias");
            case "cmdsrc.getPlayer/0": case "cmdsrc.getPlayerOrException/0":
                return new Ex("game.native_player()", PLAYER, true, null);
            case "cmdsrc.getInput/0":
                return new Ex("j_cmd_input()", JT.STRING, true, null);
            case "cmdsrc.sendFeedback/1": case "cmdsrc.sendSystemMessage/1": case "cmdsrc.sendMessage/1":
                return chat(a.get(0), x);
            case "cmdsrc.sendSuccess/2": {
                // The server source's (Supplier<Component>, broadcast): the message, in the player's chat.
                Expr msg = a.get(0);
                while (msg instanceof Cast cm) msg = cm.e();
                if (msg instanceof Lambda sl && sl.params().isEmpty() && sl.body() instanceof Expr se) return chat(se, x);
                throw new JavaParser.Unsupported("sendSuccess with a message that isn't a `() -> ..` lambda");
            }
            case "cmdsrc.sendError/1": case "cmdsrc.sendFailure/1": {
                // Brigadier shows errors in red.
                try {
                    return chat(new Call(a.get(0), "withStyle", List.of(new Field(new Name("ChatFormatting"), "RED"))), x);
                } catch (JavaParser.Unsupported e) {
                    return chat(a.get(0), x);
                }
            }
            case "modkey.consumeClick/0": case "modkey.wasPressed/0":
                return new Ex("key_consume(" + t.code() + ")", JT.BOOL);
            case "modkey.isDown/0": case "modkey.isPressed/0":
                return new Ex("key_down(" + t.code() + ")", JT.BOOL);
            case "vkey.isDown/0": case "vkey.isPressed/0":
                return mapping(t.code());
            case "game.execute/1": case "game.submit/1": case "game.tell/1": {
                // Translated code runs on the game thread (hooks, and the queue the tick hook empties).
                Fn f = closure(a.get(0), List.of(), false, JT.VOID, x);
                return new Ex("(" + f.code() + ")()", JT.VOID);
            }
            case "game.setScreen/1": case "gui.setScreen/1": {
                // The mod's own screen (its settings): in Horizon, the mod's page in Options > Mods.
                Cls own = a.get(0) instanceof New nw ? ownClass(nw.type().name(), x.cls) : null;
                if (own != null && own.d.ext != null && own.d.ext.simple().endsWith("Screen")) {
                    notes.add(x.cls.d.name + "." + (x.m == null ? "" : x.m.name.replace("\u0001", "")) + ": opening " + own.d.name
                            + " opens the mod's page in Options > Mods (its settings buttons) instead");
                    return new Ex("{ if let Some(hp_i) = game.settings.mods.installed.iter().position(|m| m.id == " + runtimePath
                            + "::ID) { game.open_options_at(false, horizon_client::options_ui::Page::NativeMod(hp_i)); game.native_settings_refresh(hp_i); } }", JT.VOID);
                }
                throw new JavaParser.Unsupported("it opens a screen (Horizon has no screen API for native mods yet)");
            }
            default:
                throw new JavaParser.Unsupported("it calls Minecraft's " + javaName(t.type()) + "." + c.name() + " (no Horizon helper for it yet)");
        }
    }

    private static Ex mapping(String name) {
        return new Ex("game.native_mapping(" + name + ").is_some_and(|(held, _)| held)", JT.BOOL);
    }

    private Ex chat(Expr text, Ctx x) {
        Ex v = ex(text, x);
        String plain = v.type().is("comp") ? literalText(v.code()) : null;
        if (plain != null) return new Ex("{ let hp_text: String = " + plain + "; game.native_chat(&hp_text) }", JT.VOID);
        if (v.type().is("comp")) {
            // As native_chat adds a line, with the Component's styles and links kept.
            String t = v.owned() ? v.code() : paren(v.code()) + ".clone()";
            return new Ex("{ let hp_text = " + t + "; let hp_focused = game.screen == crate::game::Screen::Chat; let hp_ticks = game.ticks; "
                    + "game.ui.chat.add(&game.gui.atlas, hp_text, None, horizon_client::chat::ChatSource::Client, hp_ticks, hp_focused) }", JT.VOID);
        }
        return new Ex("{ let hp_text: String = " + valueTo(text, JT.STRING, x).code() + "; game.native_chat(&hp_text) }", JT.VOID);
    }

    /** A Java int colour (ARGB) as Horizon's u32. */
    private String color(Expr e, Ctx x) {
        Ex v = ex(e, x);
        if (!v.type().numeric() || v.type().floating()) throw new JavaParser.Unsupported("a colour that isn't a whole number");
        return "(" + paren(numeric(v, JT.INT).code()) + " as u32)";
    }

    private static final JT PATH = new JT("path", "Path", null);
    static final JT VEC3F = new JT("vec3f", "Vector3f", null), QUAT = new JT("quat", "Quaternionf", null), MAT4 = new JT("mat4", "Matrix4f", null),
            MAT3 = new JT("mat3", "Matrix3f", null), POSESTACK = new JT("posestack", "PoseStack", null), POSELAST = new JT("poselast", "PoseStack.Pose", null);

    private String f32(Expr e, Ctx x) {
        return numeric(unwrapOpt(ex(e, x)), JT.FLOAT).code();
    }

    private String jomlArg(Expr e, JT want, Ctx x) {
        Ex v = unwrapOpt(ex(e, x));
        if (!v.type().is(want.k())) throw new JavaParser.Unsupported("a " + v.type() + " where JOML's " + want.name() + " goes");
        return v.code();
    }

    /** `new Vector3f(..)`, `new Matrix4f(..)`, `new Quaternionf(..)`, `new Matrix3f(..)`, `new PoseStack()`. */
    private Ex newJoml(String simple, List<Expr> a, Ctx x) {
        switch (simple + "/" + a.size()) {
            case "Vector3f/0": return new Ex("JVec3::new(0.0, 0.0, 0.0)", VEC3F);
            case "Vector3f/3": return new Ex("JVec3::new(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", VEC3F);
            case "Vector3f/1": return new Ex(jomlArg(a.get(0), VEC3F, x), VEC3F);
            case "Quaternionf/0": return new Ex("JQuat::IDENTITY", QUAT);
            case "Quaternionf/4":
                return new Ex("JQuat::new(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ", " + f32(a.get(3), x) + ")", QUAT);
            case "Quaternionf/1": return new Ex(jomlArg(a.get(0), QUAT, x), QUAT);
            case "Matrix4f/0": return new Ex("JMat4::IDENTITY", MAT4);
            case "Matrix4f/1": return new Ex(jomlArg(a.get(0), MAT4, x), MAT4);
            case "Matrix3f/3":
                return new Ex("JMat3::from_cols(" + jomlArg(a.get(0), VEC3F, x) + ", " + jomlArg(a.get(1), VEC3F, x) + ", " + jomlArg(a.get(2), VEC3F, x) + ")", MAT3);
            case "PoseStack/0": case "MatrixStack/0": return new Ex("JPoseStack::new()", POSESTACK);
            default: return null;
        }
    }

    private static final Set<String> JOML_OPS = Set.of("add", "sub", "mul", "negate", "normalize", "cross", "lerp", "set", "zero", "length",
            "lengthSquared", "distance", "dot", "x", "y", "z", "w", "identity", "translate", "rotate", "rotateX", "rotateY", "rotateZ", "scale",
            "invert", "rotateAround", "set3x3", "transpose3x3", "translation", "rotation", "transformPosition", "transformDirection",
            "mulPosition", "mulDirection", "getTranslation", "rotationX", "rotationY", "rotationZ", "rotateXYZ");

    /**
     * A JOML call chain, `v.add(a).mul(b)`, `new Matrix4f(m).invert().mul(n)`: JOML changes the object and returns it,
     * so the chain is worked out on a copy and, when it starts at a variable, written back to it once.
     */
    private Ex jomlChain(Call outer, Ctx x) {
        if (!JOML_OPS.contains(outer.name())) return null;
        List<Call> ops = new ArrayList<>();
        Expr e = outer;
        while (e instanceof Call c && JOML_OPS.contains(c.name()) && c.target() != null) {
            ops.add(0, c);
            e = c.target();
        }
        Ex root;
        try {
            root = unwrapOpt(ex(e, x));
        } catch (JavaParser.Unsupported u) {
            return null;
        }
        if (!Set.of("vec3f", "quat", "mat4").contains(root.type().k())) return null;
        boolean fresh = root.place() == null || "lit".equals(root.place());
        String code = root.code();
        JT type = root.type();
        boolean mutates = false;
        Expr writeOther = null;
        for (int i = 0; i < ops.size(); i++) {
            Call op = ops.get(i);
            boolean last = i == ops.size() - 1;
            String[] r = jomlOp(code, type, op, x);
            if (r == null) throw new JavaParser.Unsupported("JOML's " + type.name() + "." + op.name() + " with " + op.args().size() + " values");
            String kind = r[2];
            if (!kind.equals("this") && !last) throw new JavaParser.Unsupported("a JOML chain going on after ." + op.name());
            code = r[0];
            type = jt(r[1]);
            if (kind.equals("this")) mutates = true;
            if (kind.startsWith("arg")) writeOther = op.args().get(Integer.parseInt(kind.substring(3)));
        }
        // Where the changed receiver goes back to: the variable, or the PoseStack whose top matrix it is.
        String back = null;
        if (mutates && !fresh) back = "VAR";
        else if (mutates && e instanceof Call pc && pc.name().equals("pose") && pc.args().isEmpty() && pc.target() instanceof Call lc
                && lc.name().equals("last") && lc.args().isEmpty() && lc.target() != null) {
            Ex stack = ex(lc.target(), x);
            if (stack.type().is("posestack") && stack.place() != null && !"staticobj".equals(stack.place())) {
                markRecv(stack, x);
                back = paren(stack.code()) + ".set_last(";
            }
        }
        boolean writes = writeOther != null && isPlace(writeOther);
        if (back == null && !writes) return new Ex(code, type);
        String v = "joml_" + (x.tmp++);
        Call lastOp = ops.get(ops.size() - 1);
        boolean lastIsThis = kindOf(root, ops, x).equals("this");
        StringBuilder b = new StringBuilder("{ ");
        if (back != null && !lastIsThis) {
            // The receiver after its changing calls, then the last call on it.
            String m = "joml_m" + (x.tmp++);
            String recv = root.code();
            JT rt = root.type();
            for (int i = 0; i < ops.size() - 1; i++) {
                String[] r = jomlOp(recv, rt, ops.get(i), x);
                recv = r[0];
                rt = jt(r[1]);
            }
            b.append("let ").append(m).append(" = ").append(recv).append("; ");
            b.append(back.equals("VAR") ? assignCode(e, m, x) : back + m + ")").append("; ");
            String[] r = jomlOp(m, rt, lastOp, x);
            b.append("let ").append(v).append(" = ").append(r[0]).append("; ");
        } else {
            b.append("let ").append(v).append(" = ").append(code).append("; ");
            if (back != null) b.append(back.equals("VAR") ? assignCode(e, v, x) : back + v + ")").append("; ");
        }
        if (writes) b.append(assignCode(writeOther, v, x)).append("; ");
        return new Ex(b.append(v).append(" }").toString(), type);
    }

    /** What the chain's last call gives: "this" (the receiver), "value" or "argN". */
    private String kindOf(Ex root, List<Call> ops, Ctx x) {
        String recv = root.code();
        JT rt = root.type();
        String kind = "this";
        for (Call op : ops) {
            String[] r = jomlOp(recv, rt, op, x);
            if (r == null) return "value";
            recv = r[0];
            rt = jt(r[1]);
            kind = r[2];
        }
        return kind;
    }

    private static boolean isPlace(Expr e) {
        return e instanceof Name || e instanceof Field || e instanceof Index;
    }

    private static JT jt(String k) {
        return switch (k) {
            case "vec3f" -> VEC3F;
            case "quat" -> QUAT;
            case "mat4" -> MAT4;
            case "mat3" -> MAT3;
            case "float" -> JT.FLOAT;
            default -> throw new IllegalStateException(k);
        };
    }

    /** One JOML method on `r`: {code, result type, "this" (returns the changed receiver) | "value" | "argN" (writes argument N)}. */
    private String[] jomlOp(String r, JT t, Call c, Ctx x) {
        List<Expr> a = c.args();
        String k = t.k() + "." + c.name() + "/" + a.size();
        String p = paren(r);
        return switch (k) {
            case "vec3f.add/1" -> new String[] {p + ".add(" + jomlArg(a.get(0), VEC3F, x) + ")", "vec3f", "this"};
            case "vec3f.add/3" -> new String[] {p + ".add3(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", "vec3f", "this"};
            case "vec3f.sub/1" -> new String[] {p + ".sub(" + jomlArg(a.get(0), VEC3F, x) + ")", "vec3f", "this"};
            case "vec3f.mul/1" -> {
                Ex o = unwrapOpt(ex(a.get(0), x));
                if (o.type().numeric()) yield new String[] {p + ".mul_s(" + numeric(o, JT.FLOAT).code() + ")", "vec3f", "this"};
                if (o.type().is("vec3f")) yield new String[] {p + ".mul_v(" + o.code() + ")", "vec3f", "this"};
                if (o.type().is("mat4")) yield new String[] {paren(o.code()) + ".transform_position(" + r + ")", "vec3f", "this"};
                yield null;
            }
            case "vec3f.mulPosition/1" -> new String[] {paren(jomlArg(a.get(0), MAT4, x)) + ".transform_position(" + r + ")", "vec3f", "this"};
            case "vec3f.mulDirection/1" -> new String[] {paren(jomlArg(a.get(0), MAT4, x)) + ".transform_direction(" + r + ")", "vec3f", "this"};
            case "vec3f.negate/0" -> new String[] {p + ".negate()", "vec3f", "this"};
            case "vec3f.normalize/0" -> new String[] {p + ".normalize()", "vec3f", "this"};
            case "vec3f.cross/1" -> new String[] {p + ".cross(" + jomlArg(a.get(0), VEC3F, x) + ")", "vec3f", "this"};
            case "vec3f.lerp/2" -> new String[] {p + ".lerp(" + jomlArg(a.get(0), VEC3F, x) + ", " + f32(a.get(1), x) + ")", "vec3f", "this"};
            case "vec3f.set/1" -> new String[] {jomlArg(a.get(0), VEC3F, x), "vec3f", "this"};
            case "vec3f.set/3" -> new String[] {"JVec3::new(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", "vec3f", "this"};
            case "vec3f.zero/0" -> new String[] {"JVec3::new(0.0, 0.0, 0.0)", "vec3f", "this"};
            case "vec3f.length/0" -> new String[] {p + ".length()", "float", "value"};
            case "vec3f.lengthSquared/0" -> new String[] {p + ".length_squared()", "float", "value"};
            case "vec3f.distance/1" -> new String[] {p + ".distance(" + jomlArg(a.get(0), VEC3F, x) + ")", "float", "value"};
            case "vec3f.dot/1" -> new String[] {p + ".dot(" + jomlArg(a.get(0), VEC3F, x) + ")", "float", "value"};
            case "vec3f.x/0", "vec3f.y/0", "vec3f.z/0" -> new String[] {p + "." + c.name(), "float", "value"};
            case "quat.normalize/0" -> new String[] {p + ".normalize()", "quat", "this"};
            case "quat.mul/1" -> new String[] {p + ".mul(" + jomlArg(a.get(0), QUAT, x) + ")", "quat", "this"};
            case "quat.rotateX/1", "quat.rotateY/1", "quat.rotateZ/1" ->
                    new String[] {p + ".rotate_" + c.name().substring(6).toLowerCase() + "(" + f32(a.get(0), x) + ")", "quat", "this"};
            case "quat.rotationX/1", "quat.rotationY/1", "quat.rotationZ/1" ->
                    new String[] {"JQuat::rotation_" + c.name().substring(8).toLowerCase() + "(" + f32(a.get(0), x) + ")", "quat", "this"};
            case "quat.identity/0" -> new String[] {"JQuat::IDENTITY", "quat", "this"};
            case "quat.set/1" -> new String[] {jomlArg(a.get(0), QUAT, x), "quat", "this"};
            case "mat4.identity/0" -> new String[] {"JMat4::IDENTITY", "mat4", "this"};
            case "mat4.set/1" -> new String[] {jomlArg(a.get(0), MAT4, x), "mat4", "this"};
            case "mat4.translate/3" -> new String[] {p + ".translate(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", "mat4", "this"};
            case "mat4.translate/1" -> new String[] {p + ".translate_v(" + jomlArg(a.get(0), VEC3F, x) + ")", "mat4", "this"};
            case "mat4.translation/3" -> new String[] {"JMat4::translation(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", "mat4", "this"};
            case "mat4.rotate/1" -> new String[] {p + ".rotate(" + jomlArg(a.get(0), QUAT, x) + ")", "mat4", "this"};
            case "mat4.rotation/1" -> new String[] {"JMat4::rotation(" + jomlArg(a.get(0), QUAT, x) + ")", "mat4", "this"};
            case "mat4.rotateX/1", "mat4.rotateY/1", "mat4.rotateZ/1" ->
                    new String[] {p + ".rotate_" + c.name().substring(6).toLowerCase() + "(" + f32(a.get(0), x) + ")", "mat4", "this"};
            case "mat4.rotateXYZ/3" -> new String[] {p + ".rotate_xyz(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", "mat4", "this"};
            case "mat4.scale/1" -> new String[] {p + ".scale_s(" + f32(a.get(0), x) + ")", "mat4", "this"};
            case "mat4.scale/3" -> new String[] {p + ".scale(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", "mat4", "this"};
            case "mat4.mul/1" -> new String[] {p + ".mul(" + jomlArg(a.get(0), MAT4, x) + ")", "mat4", "this"};
            case "mat4.invert/0" -> new String[] {p + ".invert()", "mat4", "this"};
            case "mat4.rotateAround/4" -> new String[] {p + ".rotate_around(" + jomlArg(a.get(0), QUAT, x) + ", " + f32(a.get(1), x) + ", "
                    + f32(a.get(2), x) + ", " + f32(a.get(3), x) + ")", "mat4", "this"};
            case "mat4.set3x3/1" -> new String[] {p + ".set3x3(" + jomlArg(a.get(0), MAT3, x) + ")", "mat4", "this"};
            case "mat4.transpose3x3/0" -> new String[] {p + ".transpose3x3()", "mat4", "this"};
            case "mat4.transformPosition/1" -> new String[] {p + ".transform_position(" + jomlArg(a.get(0), VEC3F, x) + ")", "vec3f", "arg0"};
            case "mat4.transformPosition/2" -> new String[] {p + ".transform_position(" + jomlArg(a.get(0), VEC3F, x) + ")", "vec3f", "arg1"};
            case "mat4.transformDirection/1" -> new String[] {p + ".transform_direction(" + jomlArg(a.get(0), VEC3F, x) + ")", "vec3f", "arg0"};
            case "mat4.getTranslation/1" -> new String[] {p + ".get_translation()", "vec3f", "arg0"};
            default -> null;
        };
    }

    /** `target = value` as Rust code, for a variable, field or changing static; `value` is a fresh value. */
    private String assignCode(Expr target, String value, Ctx x) {
        Ex t = ex(target, x);
        markWrite(target, x);
        String place = t.place();
        if ("static".equals(place)) return staticSetter(target, x).replace("$V", value);
        if ("staticobj".equals(place) && !t.code().contains(".with(")) return t.code() + ".set(" + value + ")";
        if ("lazy".equals(place)) throw new JavaParser.Unsupported("a JOML static that changes (next pass keeps it behind a lock)");
        if (place == null || "lit".equals(place) || "alias".equals(place) || "iter".equals(place) || "ref".equals(place) || "optref".equals(place) || "staticobj".equals(place))
            throw new JavaParser.Unsupported("a JOML object changed in place that isn't a variable");
        return t.code() + " = " + value;
    }

    private static final JT RANDOM = new JT("random", "RandomSource", null);
    static final JT REGEX = new JT("regex", "Pattern", null), MATCHER = new JT("matcher", "Matcher", null);
    static final JT JSON = new JT("json", "JsonElement", null), GSON = new JT("gson", "Gson", null), PROPS = new JT("props", "Properties", null);
    static final JT HTTPCLIENT = new JT("httpclient", "HttpClient", null), HTTPREQ = new JT("httpreq", "HttpRequest", null),
            HTTPRESP = new JT("httpresp", "HttpResponse", null), DURATION = new JT("duration", "Duration", null), VERSION = new JT("version", "Version", null),
            MODC = new JT("modc", "ModContainer", null), MODDEP = new JT("moddep", "ModDependency", null), DEPKIND = new JT("depkind", "ModDependency.Kind", null);

    /** FabricLoader.getModContainer was called: the runtime gets j_mod, from the mod's fabric.mod.json. */
    private boolean usesModInfo;
    /** The mod's fabric.mod.json, when the source has it. */
    private Map<?, ?> fabricMod;
    /** The Minecraft version the Horizon being built for plays (its horizon-core VANILLA_VERSION), when known. */
    static String gameVersion;

    /** `j_mod(id)`: this mod as fabric.mod.json describes it, and Minecraft as the version Horizon plays; no other mod is loaded. */
    private String modInfo() {
        StringBuilder b = new StringBuilder("/// FabricLoader.getModContainer: this mod (its fabric.mod.json) and Minecraft (the version Horizon plays).\n");
        b.append("pub fn j_mod(id: &str) -> Option<JMod> {\n    match id {\n");
        if (fabricMod != null && fabricMod.get("id") instanceof String id) {
            List<String> deps = new ArrayList<>();
            for (String kind : List.of("depends", "recommends", "suggests", "conflicts", "breaks")) {
                if (!(fabricMod.get(kind) instanceof Map<?, ?> dm)) continue;
                for (Map.Entry<?, ?> e : dm.entrySet()) {
                    List<String> ranges = new ArrayList<>();
                    if (e.getValue() instanceof List<?> l) for (Object o : l) ranges.add(rustStr(String.valueOf(o)));
                    else ranges.add(rustStr(String.valueOf(e.getValue())));
                    deps.add("(\"" + kind + "\", " + rustStr(String.valueOf(e.getKey())) + ", &[" + String.join(", ", ranges) + "])");
                }
            }
            b.append("        ").append(rustStr(id)).append(" => Some(JMod::new(").append(rustStr(id)).append(", ")
                    .append(rustStr(String.valueOf(fabricMod.get("version") == null ? "0" : fabricMod.get("version")))).append(", &[").append(String.join(", ", deps)).append("])),\n");
        }
        if (gameVersion != null) b.append("        \"minecraft\" => Some(JMod::new(\"minecraft\", ").append(rustStr(gameVersion)).append(", &[])),\n");
        b.append("        _ => None,\n    }\n}\n");
        return b.toString();
    }

    private static String rustStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** `Text::literal(s)` with nothing more: the `s`, else null. */
    private static String literalText(String code) {
        String head = "horizon_client::text::Text::literal(";
        if (!code.startsWith(head) || !code.endsWith(")")) return null;
        String inner = code.substring(head.length(), code.length() - 1);
        int depth = 0;
        for (char ch : inner.toCharArray()) {
            if (ch == '(') depth++;
            if (ch == ')' && --depth < 0) return null;
        }
        return depth == 0 ? inner : null;
    }

    /** ChatFormatting's colours, as RGB. */
    private static final Map<String, Integer> CHAT_COLORS = Map.ofEntries(Map.entry("BLACK", 0x000000), Map.entry("DARK_BLUE", 0x0000AA),
            Map.entry("DARK_GREEN", 0x00AA00), Map.entry("DARK_AQUA", 0x00AAAA), Map.entry("DARK_RED", 0xAA0000), Map.entry("DARK_PURPLE", 0xAA00AA),
            Map.entry("GOLD", 0xFFAA00), Map.entry("GRAY", 0xAAAAAA), Map.entry("DARK_GRAY", 0x555555), Map.entry("BLUE", 0x5555FF),
            Map.entry("GREEN", 0x55FF55), Map.entry("AQUA", 0x55FFFF), Map.entry("RED", 0xFF5555), Map.entry("LIGHT_PURPLE", 0xFF55FF),
            Map.entry("YELLOW", 0xFFFF55), Map.entry("WHITE", 0xFFFFFF));
    private static final Map<String, String> CHAT_FLAGS = Map.of("BOLD", "bold", "ITALIC", "italic", "UNDERLINE", "underlined",
            "STRIKETHROUGH", "strikethrough", "OBFUSCATED", "obfuscated");

    /** `ChatFormatting.GOLD`: the Style field it sets, else null. */
    private static String formatting(Expr e) {
        if (!(e instanceof Field f) || !(f.target() instanceof Name n) || !n.id().equals("ChatFormatting")) return null;
        if (CHAT_COLORS.containsKey(f.name())) return "hp_s.color = Some(0x" + String.format("%06X", CHAT_COLORS.get(f.name())) + ");";
        if (CHAT_FLAGS.containsKey(f.name())) return "hp_s." + CHAT_FLAGS.get(f.name()) + " = Some(true);";
        if (f.name().equals("RESET")) return "";
        return null;
    }

    private static String styleBlock(List<String> sets) {
        return "{ let mut hp_s = horizon_client::text::Style::default(); " + String.join(" ", sets) + " hp_s }";
    }

    /** `style -> style.withColor(..).withUnderlined(true).withClickEvent(new OpenUrl(URI.create(url)))...` as a Style. */
    private String styleOf(Lambda l, Ctx x) {
        if (l.params().size() != 1 || !(l.body() instanceof Expr body)) throw new JavaParser.Unsupported("a style lambda with a block body");
        List<Call> chain = new ArrayList<>();
        Expr e = body;
        while (e instanceof Call c) {
            chain.add(0, c);
            e = c.target();
        }
        if (!(e instanceof Name n) || !n.id().equals(l.params().get(0))) throw new JavaParser.Unsupported("a style lambda that doesn't build on its Style");
        List<String> sets = new ArrayList<>();
        for (Call c : chain) {
            List<Expr> a = c.args();
            String f = a.size() == 1 ? formatting(a.get(0)) : null;
            switch (c.name() + "/" + a.size()) {
                case "withColor/1", "applyFormat/1", "withFormat/1" -> {
                    if (f != null) sets.add(f);
                    else if (c.name().equals("withColor")) sets.add("hp_s.color = Some((" + numeric(ex(a.get(0), x), JT.INT).code() + ") as u32 & 0xFF_FFFF);");
                    else throw new JavaParser.Unsupported("Style." + c.name() + " of something other than a ChatFormatting");
                }
                case "withBold/1", "withItalic/1", "withUnderlined/1", "withStrikethrough/1", "withObfuscated/1" -> {
                    String field = c.name().substring(4).toLowerCase();
                    sets.add("hp_s." + field + " = Some(" + valueTo(a.get(0), JT.BOOL, x).code() + ");");
                }
                case "withInsertion/1" -> sets.add("hp_s.insertion = Some(" + valueTo(a.get(0), JT.STRING, x).code() + ");");
                case "withClickEvent/1" -> {
                    if (!(a.get(0) instanceof New ev) || ev.args().size() != 1) throw new JavaParser.Unsupported("a click event that isn't made here");
                    String kind = switch (ev.type().simple()) {
                        case "OpenUrl" -> "OpenUrl";
                        case "RunCommand" -> "RunCommand";
                        case "SuggestCommand" -> "SuggestCommand";
                        case "CopyToClipboard" -> "CopyToClipboard";
                        default -> throw new JavaParser.Unsupported("a " + ev.type().simple() + " click event");
                    };
                    sets.add("hp_s.click = Some(horizon_client::text::ClickEvent::" + kind + "(" + valueTo(ev.args().get(0), JT.STRING, x).code() + "));");
                }
                case "withHoverEvent/1" -> {
                    if (!(a.get(0) instanceof New ev) || ev.args().size() != 1 || !ev.type().simple().equals("ShowText"))
                        throw new JavaParser.Unsupported("a hover event other than text");
                    sets.add("hp_s.hover = Some(Box::new(" + valueTo(ev.args().get(0), COMP, x).code() + "));");
                }
                default -> throw new JavaParser.Unsupported("Style." + c.name());
            }
        }
        return styleBlock(sets);
    }

    /** Component.translatable: the mod's English text for the key (its en_us.json), with the arguments put in. */
    private Ex translatable(Call c, Ctx x) {
        List<Expr> args = new ArrayList<>(c.args());
        if (args.isEmpty() || !(args.get(0) instanceof Lit key) || !key.text().startsWith("\"")) throw new JavaParser.Unsupported("a translated text with a computed key");
        args.remove(0);
        // Decompilers write the varargs as `new Object[]{...}`.
        if (args.size() == 1 && args.get(0) instanceof NewArray na && na.init() != null) args = new ArrayList<>(na.init().items());
        String k = key.text().substring(1, key.text().length() - 1);
        String pattern = lang.get(k);
        if (pattern == null) {
            notes.add(x.cls.d.name + "." + (x.m == null ? "" : x.m.name) + ": the text " + k + " isn't in the mod's en_us.json, so the key itself shows");
            pattern = k;
        }
        List<String> parts = new ArrayList<>();
        for (Expr a : args) {
            Ex v = ex(a, x);
            parts.add(v.type().is("comp") ? paren(v.code()) + ".plain()" : "format!(\"{}\", " + fmtArg(v) + ")");
        }
        return new Ex("horizon_client::text::Text::literal(j_lang(" + rustStr(pattern) + ", &[" + String.join(", ", parts) + "]))", COMP);
    }

    /** Component's methods, on Horizon's Text. */
    private Ex compCall(Ex t, Call c, Ctx x) {
        String own = t.owned() ? t.code() : paren(t.code()) + ".clone()";
        List<Expr> a = c.args();
        switch (c.name() + "/" + a.size()) {
            case "getString/0", "toString/0":
                return new Ex(paren(t.code()) + ".plain()", JT.STRING);
            case "copy/0", "plainCopy/0":
                return new Ex(own, COMP);
            case "append/1": {
                Ex v = ex(a.get(0), x);
                if (v.type().is("comp")) return new Ex(own + ".append(" + (v.owned() ? v.code() : paren(v.code()) + ".clone()") + ")", COMP);
                return new Ex(own + ".append(" + valueTo(a.get(0), JT.STRING, x).code() + ")", COMP);
            }
            default:
                if (c.name().equals("withStyle") && !a.isEmpty()) {
                    String style;
                    if (a.size() == 1 && a.get(0) instanceof Lambda l) style = styleOf(l, x);
                    else {
                        List<String> sets = new ArrayList<>();
                        for (Expr e : a) {
                            String f = formatting(e);
                            if (f == null) throw new JavaParser.Unsupported("Component.withStyle of something other than ChatFormatting or a style lambda");
                            sets.add(f);
                        }
                        style = styleBlock(sets);
                    }
                    return new Ex(own + ".with_style(&" + style + ")", COMP);
                }
                throw new JavaParser.Unsupported("Component." + c.name());
        }
    }

    static final String LANG_FORMAT = """
            /// A translated text's English pattern with its arguments: `%s`, `%d`, `%1$s` and `%%`, as Minecraft fills them.
            fn j_lang(pattern: &str, args: &[String]) -> String {
                let mut out = String::new();
                let mut next = 0;
                let mut chars = pattern.chars().peekable();
                while let Some(c) = chars.next() {
                    if c != '%' {
                        out.push(c);
                        continue;
                    }
                    let mut digits = String::new();
                    while let Some(d) = chars.peek().copied().filter(|d| d.is_ascii_digit()) {
                        digits.push(d);
                        chars.next();
                    }
                    let index = if chars.peek() == Some(&'$') {
                        chars.next();
                        digits.parse::<usize>().ok().map(|n| n.saturating_sub(1))
                    } else {
                        None
                    };
                    match chars.next() {
                        Some('%') => out.push('%'),
                        Some('s') | Some('d') => {
                            let i = index.unwrap_or_else(|| {
                                next += 1;
                                next - 1
                            });
                            out.push_str(args.get(i).map_or("", |s| s.as_str()));
                        }
                        Some(o) => {
                            out.push('%');
                            out.push(o);
                        }
                        None => out.push('%'),
                    }
                }
                out
            }

            """;

    /** HttpClient/HttpRequest/HttpResponse, Fabric's versions and mod metadata, CompletableFuture, Thread. */
    private Ex netCall(Ex t, Call c, Ctx x) {
        String r = paren(t.code());
        List<Expr> args = c.args();
        String k = t.type().k() + "." + c.name() + "/" + args.size();
        switch (k) {
            case "httpclient.build/0", "httpreq.build/0", "httpreq.GET/0", "httpclient.followRedirects/1", "modc.getMetadata/0":
                return t;
            case "httpclient.connectTimeout/1":
                return new Ex(r + ".with_connect_timeout(" + valueTo(args.get(0), DURATION, x).code() + ")", HTTPCLIENT);
            case "httpclient.send/2": {
                if (!x.io) throw new JavaParser.Unsupported("HttpClient.send outside a try/catch");
                Ex req = ex(args.get(0), x);
                if (!req.type().is("httpreq")) throw new JavaParser.Unsupported("HttpClient.send of a " + req.type());
                Ex h = ex(args.get(1), x);
                if (!h.type().is("bodyhandler")) throw new JavaParser.Unsupported("a response body other than a String");
                return new Ex(r + ".send(&" + paren(req.code()) + ")?", HTTPRESP);
            }
            case "httpreq.timeout/1":
                return new Ex(r + ".with_timeout(" + valueTo(args.get(0), DURATION, x).code() + ")", HTTPREQ);
            case "httpreq.header/2", "httpreq.setHeader/2":
                return new Ex(r + ".with_header(" + strArg(ex(args.get(0), x)) + ", " + strArg(ex(args.get(1), x)) + ")", HTTPREQ);
            case "httpresp.statusCode/0":
                return new Ex(r + ".status", JT.INT);
            case "httpresp.body/0":
                return new Ex(r + ".body.clone()", JT.STRING);
            case "version.compareTo/1": {
                Ex o = unwrapOpt(ex(args.get(0), x));
                if (!o.type().is("version")) throw new JavaParser.Unsupported("Version.compareTo a " + o.type());
                return new Ex(r + ".compare_to(&" + paren(o.code()) + ")", JT.INT);
            }
            case "version.getFriendlyString/0", "version.toString/0":
                return new Ex(r + ".friendly_string()", JT.STRING);
            case "modc.getVersion/0":
                return new Ex(r + ".version.clone()", VERSION);
            case "modc.getId/0":
                return new Ex(r + ".id.clone()", JT.STRING);
            case "modc.getDependencies/0":
                return new Ex(r + ".deps", new JT("list", "list", MODDEP), false, "ref");
            case "moddep.getKind/0":
                return new Ex(r + ".kind", DEPKIND);
            case "moddep.getModId/0":
                return new Ex(r + ".mod_id.clone()", JT.STRING);
            case "moddep.matches/1": {
                Ex v = unwrapOpt(ex(args.get(0), x));
                if (!v.type().is("version")) throw new JavaParser.Unsupported("ModDependency.matches a " + v.type());
                return new Ex(r + ".matches(&" + paren(v.code()) + ")", JT.BOOL);
            }
            case "thread.interrupt/0":
                return new Ex("()", JT.VOID);
            case "future.join/0", "future.get/0":
                return new Ex(r + ".join()", t.type().elem());
            case "future.thenAcceptAsync/1", "future.thenAcceptAsync/2", "future.thenAccept/1": {
                // The callback runs on the game thread: after the delay, the tick hook's queue runs it.
                if (!gameMode) throw new JavaParser.Unsupported("a future's callback (it runs on the game thread)");
                String delay = args.size() == 2 ? valueTo(args.get(1), DURATION, x).code() : "std::time::Duration::ZERO";
                Fn f = closure(args.get(0), List.of(t.type().elem()), false, JT.VOID, x);
                return new Ex(r + ".then_accept_after(" + delay + ", move |hp_v| j_on_game(Box::new(move |game: &mut crate::game::Game<'_>| { (move "
                        + f.code() + ")(hp_v); })))", JT.VOID);
            }
            default:
                throw new JavaParser.Unsupported("it calls " + javaName(t.type()) + "." + c.name());
        }
    }

    private Ex randomCall(Ex rnd, Call c, Ctx x) {
        List<Expr> a = c.args();
        String call;
        JT t;
        List<String> args = new ArrayList<>();
        switch (c.name() + "/" + a.size()) {
            case "nextInt/1" -> { call = ".next_int(a_0)"; t = JT.INT; args.add(numeric(unwrapOpt(ex(a.get(0), x)), JT.INT).code()); }
            case "nextInt/2" -> { call = ".next_int_between(a_0, a_1)"; t = JT.INT; args.add(numeric(unwrapOpt(ex(a.get(0), x)), JT.INT).code()); args.add(numeric(unwrapOpt(ex(a.get(1), x)), JT.INT).code()); }
            case "nextFloat/0" -> { call = ".next_float()"; t = JT.FLOAT; }
            case "nextDouble/0" -> { call = ".next_double()"; t = JT.DOUBLE; }
            case "nextBoolean/0" -> { call = ".next_boolean()"; t = JT.BOOL; }
            case "nextGaussian/0" -> { call = ".next_gaussian()"; t = JT.DOUBLE; }
            default -> throw new JavaParser.Unsupported("RandomSource." + c.name());
        }
        markRecv(rnd, x);
        if ("staticobj".equals(rnd.place()) || "lazy".equals(rnd.place())) {
            if ("lazy".equals(rnd.place())) throw new JavaParser.Unsupported("a random source that changes (next pass keeps it behind a lock)");
            return locked(rnd, args, call, t);
        }
        String code = call;
        for (int i = 0; i < args.size(); i++) code = code.replace("a_" + i, args.get(i));
        return new Ex(paren(rnd.code()) + code, t);
    }

    private Ex poseCall(Ex p, Call c, Ctx x) {
        String r = paren(p.code());
        List<Expr> a = c.args();
        if ("staticobj".equals(p.place())) throw new JavaParser.Unsupported("a PoseStack kept in a changing static");
        switch (c.name() + "/" + a.size()) {
            case "last/0": return new Ex(r + ".last_pose()", POSELAST);
            case "pushPose/0": case "popPose/0": case "setIdentity/0": {
                markRecv(p, x);
                String fn = switch (c.name()) {
                    case "pushPose" -> "push_pose";
                    case "popPose" -> "pop_pose";
                    default -> "set_identity";
                };
                return new Ex(r + "." + fn + "()", JT.VOID);
            }
            case "translate/3": case "scale/3":
                markRecv(p, x);
                return new Ex(r + "." + c.name() + "(" + f32(a.get(0), x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ")", JT.VOID);
            case "mulPose/1": {
                markRecv(p, x);
                Ex o = unwrapOpt(ex(a.get(0), x));
                if (o.type().is("quat")) return new Ex(r + ".mul_pose(" + o.code() + ")", JT.VOID);
                if (o.type().is("mat4")) return new Ex(r + ".mul_pose_matrix(" + o.code() + ")", JT.VOID);
                throw new JavaParser.Unsupported("PoseStack.mulPose with a " + o.type());
            }
            case "rotateAround/4":
                markRecv(p, x);
                return new Ex(r + ".rotate_around(" + jomlArg(a.get(0), QUAT, x) + ", " + f32(a.get(1), x) + ", " + f32(a.get(2), x) + ", "
                        + f32(a.get(3), x) + ")", JT.VOID);
            default:
                throw new JavaParser.Unsupported("PoseStack." + c.name());
        }
    }

    private static String pathRef(Ex p) {
        return "lazy".equals(p.place()) ? "&" + p.code() : "&" + paren(p.code());
    }

    private Ex pathCall(Ex t, Call c, Ctx x) {
        String r = paren(t.code());
        switch (c.name() + "/" + c.args().size()) {
            case "resolve/1": case "resolveSibling/1": {
                Ex o = unwrapOpt(ex(c.args().get(0), x));
                String arg = o.type().is("string") ? strArg(o) : o.type().is("path") ? "&" + paren(o.code()) : null;
                if (arg == null) throw new JavaParser.Unsupported("Path." + c.name() + " with a " + o.type());
                return new Ex(r + (c.name().equals("resolve") ? ".join(" : ".with_file_name(") + arg + ")", PATH);
            }
            case "getParent/0":
                return new Ex(r + ".parent().map(|p| p.to_path_buf()).unwrap_or_default()", PATH);
            case "getFileName/0":
                return new Ex("std::path::PathBuf::from(" + r + ".file_name().unwrap_or_default())", PATH);
            case "toString/0":
                return new Ex(r + ".display().to_string()", JT.STRING);
            case "toAbsolutePath/0": case "normalize/0":
                return new Ex(r + ".clone()", PATH);
            case "equals/1": {
                Ex o = ex(c.args().get(0), x);
                if (!o.type().is("path")) throw new JavaParser.Unsupported("Path.equals with a " + o.type());
                return new Ex(r + " == " + paren(o.code()), JT.BOOL);
            }
            default:
                throw new JavaParser.Unsupported("Path." + c.name());
        }
    }

    /** FabricLoader: the config and game folders are the mod's data folder in Horizon; no Fabric mod is ever loaded there. */
    private Ex fabricCall(Call c, Ctx x) {
        switch (c.name() + "/" + c.args().size()) {
            case "getConfigDir/0": case "getGameDir/0":
                return new Ex("j_config_dir()", PATH);
            case "isModLoaded/1":
                return new Ex("false", JT.BOOL, true, "lit");
            case "getModContainer/1": {
                if (!(c.args().get(0) instanceof Lit l) || !l.text().startsWith("\"")) throw new JavaParser.Unsupported("FabricLoader.getModContainer of a computed id");
                usesModInfo = true;
                return new Ex("j_mod(" + l.text() + ")", JT.opt("Optional", MODC));
            }
            case "isDevelopmentEnvironment/0":
                return new Ex("false", JT.BOOL, true, "lit");
            default:
                throw new JavaParser.Unsupported("it calls Fabric's FabricLoader." + c.name());
        }
    }

    /** java.nio.file.Files as std::fs; errors go to the enclosing try/catch or `throws` with `?`. */
    private Ex filesCall(String name, List<Ex> a, Call c, Ctx x) {
        if (!x.io) throw new JavaParser.Unsupported("Files." + name + " outside a try/catch or a method that throws IOException");
        for (int i = 0; i < a.size(); i++) {
            Ex e = a.get(i);
            if (e.type().is("option") && e.type().name().endsWith(".APPEND")) throw new JavaParser.Unsupported("Files." + name + " appending");
        }
        if (a.isEmpty() || !a.get(0).type().is("path")) throw new JavaParser.Unsupported("Files." + name + " without a Path");
        String p = pathRef(a.get(0));
        switch (name) {
            case "readString":
                return new Ex("std::fs::read_to_string(" + p + ")?", JT.STRING);
            case "readAllLines":
                return new Ex("std::fs::read_to_string(" + p + ")?.lines().map(String::from).collect::<Vec<String>>()", new JT("list", "list", JT.STRING));
            case "writeString": {
                if (a.size() < 2 || !a.get(1).type().is("string")) throw new JavaParser.Unsupported("Files.writeString without text");
                return new Ex("std::fs::write(" + p + ", " + strArg(a.get(1)) + ")?", JT.VOID);
            }
            case "createDirectories":
                return new Ex("std::fs::create_dir_all(" + p + ")?", JT.VOID);
            case "createDirectory":
                return new Ex("std::fs::create_dir(" + p + ")?", JT.VOID);
            case "move": case "copy": {
                if (a.size() < 2 || !a.get(1).type().is("path")) throw new JavaParser.Unsupported("Files." + name + " without two Paths");
                String to = pathRef(a.get(1));
                return name.equals("move") ? new Ex("std::fs::rename(" + p + ", " + to + ")?", JT.VOID)
                        : new Ex("{ std::fs::copy(" + p + ", " + to + ")?; }", JT.VOID);
            }
            case "delete":
                return new Ex("std::fs::remove_file(" + p + ")?", JT.VOID);
            case "deleteIfExists":
                return new Ex("std::fs::remove_file(" + p + ").is_ok()", JT.BOOL);
            case "size":
                return new Ex("(std::fs::metadata(" + p + ")?.len() as i64)", JT.LONG);
            default:
                throw new JavaParser.Unsupported("Files." + name);
        }
    }

    private Ex identCall(Ex t, Call c, Ctx x) {
        String r = paren(t.code());
        switch (c.name() + "/" + c.args().size()) {
            case "getNamespace/0": case "namespace/0": return new Ex(r + ".namespace.clone()", JT.STRING);
            case "getPath/0": case "path/0": return new Ex(r + ".path.clone()", JT.STRING);
            case "toString/0": return new Ex(r + ".to_string()", JT.STRING);
            case "equals/1": {
                Ex o = ex(c.args().get(0), x);
                if (!o.type().is("ident")) throw new JavaParser.Unsupported("Identifier.equals with a " + o.type());
                return new Ex(r + " == " + paren(o.code()), JT.BOOL);
            }
            case "withPath/1": {
                Ex o = ex(c.args().get(0), x);
                if (!o.type().is("string")) throw new JavaParser.Unsupported("Identifier.withPath with a " + o.type());
                return new Ex("JIdentifier::new(&" + r + ".namespace, " + strArg(o) + ")", IDENT);
            }
            default:
                throw new JavaParser.Unsupported("it calls Minecraft's Identifier." + c.name());
        }
    }

    /** The game side: every left-out static method tried again with Horizon's helpers, then the event handlers and key state. */
    private String emitGame() {
        gameMode = true;
        handlers.clear();
        StringBuilder b = new StringBuilder();
        try {
            for (Cls c : all) {
                for (FieldDecl f : c.d.fields) {
                    String key = fieldKey(c, f.name);
                    if (c.mixin && !f.mods.contains("static")) continue;
                    if (!f.mods.contains("static") || !failed.containsKey(key) && !gameStatics.contains(key) || keyFields.containsKey(key) || isLogger(f)) continue;
                    gameStaticNaming = true;
                    try {
                        String code = staticField(c, f);
                        // A static's value is made the first time it's read, with no `game` in reach.
                        if (code != null && java.util.regex.Pattern.compile("(?<![\\w.])game\\b(?!_)").matcher(code.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"")).find())
                            throw new JavaParser.Unsupported("its value needs the game, and a static is made before there's one");
                        if (code != null) b.append("/// `").append(c.d.qualified).append('.').append(f.name).append("`\n").append(code).append('\n');
                        if (gameStatics.add(key)) changed = true;
                    } catch (JavaParser.Unsupported e) {
                        if (gameStatics.remove(key)) changed = true;
                    } finally {
                        gameStaticNaming = false;
                    }
                }
            }
            for (Cls c : all) {
                for (MethodDecl m : c.d.methods) {
                    String key = methodKey(c, m);
                    // A mixin's own static helpers (@Unique private static ...) are plain code: game side only.
                    if (c.mixin && m.isStatic() && m.body != null && !m.mods.stream().anyMatch(a -> a.startsWith("@Inject") || a.startsWith("@Wrap")
                            || a.startsWith("@Redirect") || a.startsWith("@Modify"))) failed.putIfAbsent(key, "a Mixin helper (game side only)");
                    if (!failed.containsKey(key) || !m.isStatic() || m.body == null || m.generic || m.name.equals("<init>")) continue;
                    try {
                        String code = method(c, m);
                        b.append(code).append('\n');
                        if (gameOk.add(key)) changed = true;
                        gameFailed.remove(key);
                    } catch (JavaParser.Unsupported e) {
                        if (gameOk.remove(key)) changed = true;
                        gameFailed.put(key, e.getMessage());
                        if (System.getenv("HP_DEBUG") != null && key.contains(System.getenv("HP_DEBUG"))) System.err.println("game side: " + key + ": " + e.getMessage());
                    }
                }
            }
            eventHandlers(b);
            initializer(b);
            poseHandlers(b);
            if (b.indexOf("j_on_game(") >= 0) b.append(GAME_QUEUE);
            if (b.indexOf("j_lang(") >= 0) b.append(LANG_FORMAT);
            if (b.indexOf("j_use_animation(") >= 0) b.append(USE_ANIMATION);
            if (b.indexOf("j_y_rot_o(") >= 0) b.append(Y_ROT_O);
            if (b.indexOf("j_x_rot_o(") >= 0) b.append(Y_ROT_O.replace("Y_ROT_O", "X_ROT_O").replace("j_y_rot_o", "j_x_rot_o").replace("game.yaw", "game.pitch")
                    .replace("yRotO, the yaw", "xRotO, the pitch").replace("no yaw", "no pitch"));
            if (b.indexOf("j_is_block_item(") >= 0) b.append(BLOCK_ITEM);
            if (b.indexOf("j_item_tag(") >= 0) b.append(ITEM_TAG);
            if (b.indexOf("j_move_dist(") >= 0) b.append(MOVE_DIST);
            if (b.indexOf("JRenderState") >= 0) b.append(RENDER_STATE);
            // Key state only when translated code reads it.
            if (!keyDefaults.isEmpty() && (b.indexOf("key_consume(") >= 0 || b.indexOf("key_down(") >= 0)) b.append(keyGlue());
        } finally {
            gameMode = false;
        }
        return b.toString();
    }

    static final String GAME_QUEUE = """
            type JGameJob = Box<dyn FnOnce(&mut crate::game::Game<'_>) + Send>;

            /// Work for the game thread (a future's callback, Minecraft.execute from another thread): the tick hook runs it.
            static J_GAME_QUEUE: std::sync::Mutex<Vec<JGameJob>> = std::sync::Mutex::new(Vec::new());

            pub fn j_on_game(job: JGameJob) {
                J_GAME_QUEUE.lock().unwrap_or_else(|e| e.into_inner()).push(job);
            }

            /// Runs the queued work, in the window's Game.
            pub fn j_run_game_queue(game: &mut crate::game::Game<'_>) {
                if !game.native_window() {
                    return;
                }
                let jobs = std::mem::take(&mut *J_GAME_QUEUE.lock().unwrap_or_else(|e| e.into_inner()));
                for job in jobs {
                    job(game);
                }
            }

            """;

    static final String HTTP_GLUE = """
            /// java.net.http's requests through Horizon's own HTTPS client (ureq, as the game's session join uses).
            fn j_http_ureq(r: &JHttpRequest, connect: Option<std::time::Duration>) -> std::io::Result<JHttpResponse> {
                let mut agent = ureq::AgentBuilder::new();
                if let Some(d) = connect {
                    agent = agent.timeout_connect(d);
                }
                let mut req = agent.build().request(&r.method, &r.url);
                if let Some(t) = r.timeout {
                    req = req.timeout(t);
                }
                for (k, v) in &r.headers {
                    req = req.set(k, v);
                }
                let resp = match req.call() {
                    Ok(resp) => resp,
                    // Java hands back any status; ureq calls 4xx and 5xx errors.
                    Err(ureq::Error::Status(_, resp)) => resp,
                    Err(e) => return Err(std::io::Error::other(e.to_string())),
                };
                let status = resp.status() as i32;
                Ok(JHttpResponse { status, body: resp.into_string()? })
            }

            """;

    /** Fabric event registrations: `ClientTickEvents.END_CLIENT_TICK.register(mc -> ...)`, HUD callbacks. */
    private void eventHandlers(StringBuilder b) {
        Map<String, Integer> counts = new HashMap<>();
        cmdRoots.clear();
        cmdCount = 0;
        for (Cls c : all) {
            if (c.mixin) continue;
            for (MethodDecl m : c.d.methods) {
                if (m.body == null) continue;
                List<Call> regs = new ArrayList<>();
                walk(m.body, n -> {
                    if (n instanceof Call call && eventHook(call) != null) regs.add(call);
                });
                for (Call call : regs) {
                    String hook = eventHook(call);
                    if (hook.equals("commands")) {
                        commandHandler(c, m, call, b);
                        continue;
                    }
                    int i = counts.merge(hook, 1, Integer::sum) - 1;
                    String fn = "on_" + hook + "_" + i;
                    String where = c.file + ":" + m.line;
                    String doc = "/// The `" + hook + "` handler registered in " + c.d.qualified + '.'
                            + (m.name.equals("<init>") ? c.d.name : m.name) + " (" + where + ").\n";
                    MethodDecl h;
                    try {
                        h = handlerMethod(hook, fn, call.args().get(call.args().size() - 1));
                    } catch (JavaParser.Unsupported e) {
                        handlers.add(new Handler(hook, fn, false, e.getMessage(), where, List.of()));
                        continue;
                    }
                    try {
                        b.append(doc).append(method(c, h)).append('\n');
                        handlers.add(new Handler(hook, fn, true, null, where, List.of()));
                    } catch (JavaParser.Unsupported e) {
                        partialHandler(c, h, hook, fn, where, doc, e.getMessage(), b);
                    }
                }
            }
        }
        for (EventHook eh : eventHooks) {
            Cls c = classes.get(eh.mixinClass());
            MethodDecl m = c == null ? null : c.methods.getOrDefault(eh.handler(), List.of()).stream().filter(md -> md.body != null).findFirst().orElse(null);
            int i = counts.merge(eh.hook(), 1, Integer::sum) - 1;
            String fn = "on_" + eh.hook() + "_" + i;
            String where = c == null ? eh.mixinClass() : c.file + ":" + (m == null ? c.d.line : m.line);
            if (m == null) {
                handlers.add(new Handler(eh.hook(), fn, false, "the handler isn't in the source", where, List.of()));
                continue;
            }
            MethodDecl h = new MethodDecl();
            h.mods = Set.of("static");
            h.ret = new TypeRef("void", List.of(), 0);
            h.name = "\u0001" + fn;
            h.line = m.line;
            h.body = m.body;
            String doc = "/// The mixin " + c.d.qualified + "." + m.name + " (" + where + "), as a `" + eh.hook() + "` handler.\n";
            try {
                b.append(doc).append(method(c, h)).append('\n');
                handlers.add(new Handler(eh.hook(), fn, true, null, where, List.of()));
            } catch (JavaParser.Unsupported e) {
                partialHandler(c, h, eh.hook(), fn, where, doc, e.getMessage(), b);
            }
        }
        fabricLayer(b);
    }

    /**
     * The Fabric entrypoint's onInitializeClient / onInitialize, run once from the first hook. Its event and key
     * registrations are left out (the generator wires those as hooks); the rest is translated statement by statement.
     */
    private void initializer(StringBuilder b) {
        String body = null;
        for (Cls c : all) {
            if (c.mixin) continue;
            boolean entry = c.d.impls.stream().anyMatch(i -> Set.of("ClientModInitializer", "ModInitializer").contains(i.simple()));
            if (!entry) continue;
            for (MethodDecl m : c.d.methods) {
                if (m.body == null || !(m.name.equals("onInitializeClient") || m.name.equals("onInitialize")) || !m.params.isEmpty()) continue;
                List<Stmt> kept = new ArrayList<>();
                for (Stmt s : m.body.stmts()) if (!registration(s)) kept.add(s);
                MethodDecl h = new MethodDecl();
                h.mods = Set.of("static");
                h.ret = new TypeRef("void", List.of(), 0);
                h.name = "\u0001on_init";
                h.body = new Block(kept);
                h.line = m.line;
                // the nullable facts found for onInitializeClient's locals, under this method's key
                String from = methodKey(c, m) + ":", to = methodKey(c, h) + ":";
                for (String k : List.copyOf(nullable)) if (k.startsWith(from)) nullable.add(to + k.substring(from.length()));
                String where = c.file + ":" + m.line;
                String doc = "/// " + c.d.qualified + "." + m.name + " (" + where + "), without its Fabric registrations (they are the hooks).\n";
                if (kept.isEmpty()) {
                    handlers.add(new Handler("init", "on_init", true, null, where, List.of()));
                    b.append(doc).append("pub fn on_init(game: &mut crate::game::Game<'_>) {}\n\n");
                } else {
                    try {
                        b.append(doc).append(method(c, h)).append('\n');
                        handlers.add(new Handler("init", "on_init", true, null, where, List.of()));
                    } catch (JavaParser.Unsupported e) {
                        int before = handlers.size();
                        partialHandler(c, h, "init", "on_init", where, doc, e.getMessage(), b);
                        if (handlers.size() > before && !handlers.get(handlers.size() - 1).ok()) b.append("pub fn on_init(game: &mut crate::game::Game<'_>) {}\n\n");
                    }
                }
                body = "        on_init(game);\n";
                break;
            }
            if (body != null) break;
        }
        b.append("static INIT: std::sync::Once = std::sync::Once::new();\n\n");
        b.append("/// Called first by every hook: points FabricLoader's config folder at the mod's data folder, then runs\n");
        b.append("/// the mod's initializer, once.\n");
        b.append("pub fn ensure_init(game: &mut crate::game::Game<'_>) {\n");
        b.append("    if INIT.is_completed() {\n        return;\n    }\n");
        b.append("    INIT.call_once(|| {\n");
        b.append("        J_DATA_DIR.set(Some(game.native_data_dir(").append(runtimePath).append("::ID)));\n");
        if (body != null) b.append(body);
        b.append("    });\n}\n\n");
    }

    /** A Fabric registration statement: events, keys, commands, registries (`X.EVENT.register(..)`, `KeyBindingHelper.registerKeyBinding(..)`). */
    private boolean registration(Stmt s) {
        Expr e = s instanceof ExprStmt es ? es.e() : null;
        if (e instanceof Assign a) e = a.value();
        if (s instanceof Local l && l.vars().size() == 1) e = l.vars().get(0).init();
        while (e instanceof Cast cast) e = cast.e();
        if (!(e instanceof Call call) || !call.name().startsWith("register")) return false;
        Expr t = call.target();
        while (t instanceof Field f) t = f.target();
        if (t instanceof Call inner) {
            Expr it = inner.target();
            while (it instanceof Field f) it = f.target();
            t = it;
        }
        return t instanceof Name n && !classes.containsKey(n.id());
    }

    /**
     * A handler that didn't translate whole: its top-level statements are tried in order, and the ones that
     * can't be translated become TODO comments, so a tick handler with one unmapped call still does the rest.
     */
    private void partialHandler(Cls c, MethodDecl h, String hook, String fn, String where, String doc, String reason, StringBuilder b) {
        List<String> left = new ArrayList<>();
        Block whole = h.body;
        List<Stmt> stmts = partialize(whole.stmts(), inner -> new Block(inner), c, h, left);
        h.body = whole;
        boolean kept = stmts.stream().anyMatch(st -> !(st instanceof JavaParser.Note));
        if (left.isEmpty() || !kept) {
            h.body = whole;
            handlers.add(new Handler(hook, fn, false, reason, where, List.of()));
            return;
        }
        h.body = new Block(stmts);
        try {
            b.append(doc).append("/// Partly: ").append(left.size()).append(" statement(s) left out as TODOs.\n").append(method(c, h)).append('\n');
            handlers.add(new Handler(hook, fn, true, null, where, left));
        } catch (JavaParser.Unsupported e) {
            handlers.add(new Handler(hook, fn, false, reason, where, List.of()));
        } finally {
            h.body = whole;
        }
    }

    /**
     * The statements with the ones that can't be translated as TODO notes; an `if` or block that fails as a whole keeps
     * its translatable parts. `wrap` puts a list back into the whole method body, to test it in place.
     */
    private List<Stmt> partialize(List<Stmt> stmts, java.util.function.Function<List<Stmt>, Block> wrap, Cls c, MethodDecl h, List<String> left) {
        List<Stmt> done = new ArrayList<>();
        for (Stmt s : stmts) {
            List<Stmt> trial = new ArrayList<>(done);
            trial.add(s);
            String why = translates(c, h, wrap.apply(trial));
            if (why == null) {
                done.add(s);
                continue;
            }
            Stmt inner = partialInside(s, done, wrap, c, h, left);
            if (inner != null) {
                done.add(inner);
                continue;
            }
            String what = describe(s);
            left.add(what + ": " + why);
            done.add(new JavaParser.Note("left out " + what + ": " + why));
        }
        return done;
    }

    /** An `if` or block kept with only its translatable parts, or null. */
    private Stmt partialInside(Stmt s, List<Stmt> before, java.util.function.Function<List<Stmt>, Block> wrap, Cls c, MethodDecl h, List<String> left) {
        java.util.function.Function<Stmt, Block> at = st -> {
            List<Stmt> l = new ArrayList<>(before);
            l.add(st);
            return wrap.apply(l);
        };
        if (s instanceof If i) {
            // The condition must translate on its own.
            if (translates(c, h, at.apply(new If(i.c(), new Block(List.of()), null))) != null) return null;
            List<String> got = new ArrayList<>();
            List<Stmt> thenP = partialize(stmtsOf(i.then()), inner -> at.apply(new If(i.c(), new Block(inner), null)), c, h, got);
            Stmt elseP = null;
            if (i.otherwise() != null) {
                List<Stmt> elseL = partialize(stmtsOf(i.otherwise()), inner -> at.apply(new If(i.c(), new Block(thenP), new Block(inner))), c, h, got);
                elseP = new Block(elseL);
            }
            If kept = new If(i.c(), new Block(thenP), elseP);
            if (translates(c, h, at.apply(kept)) != null) return null;
            left.addAll(got);
            return kept;
        }
        if (s instanceof Block blk) {
            List<String> got = new ArrayList<>();
            List<Stmt> inner = partialize(blk.stmts(), l -> at.apply(new Block(l)), c, h, got);
            Block kept = new Block(inner);
            if (translates(c, h, at.apply(kept)) != null) return null;
            left.addAll(got);
            return kept;
        }
        return null;
    }

    private static List<Stmt> stmtsOf(Stmt s) {
        return s instanceof Block b ? b.stmts() : List.of(s);
    }

    /** Null when the method translates with this body, else why not. */
    private String translates(Cls c, MethodDecl h, Block body) {
        Block keep = h.body;
        h.body = body;
        try {
            method(c, h);
            return null;
        } catch (JavaParser.Unsupported e) {
            return e.getMessage();
        } finally {
            h.body = keep;
        }
    }

    /**
     * Mixin handlers on Horizon's hand drawing (see PoseHook), and the per-frame state the generated injections read:
     * the pose change for each hand, or the arm and item the mod draws itself.
     */
    private void poseHandlers(StringBuilder b) {
        List<String> ok = new ArrayList<>();
        for (PoseHook ph : poseHooks) {
            Cls c = classes.get(ph.mixinClass());
            MethodDecl m = c == null ? null : c.methods.getOrDefault(ph.handler(), List.of()).stream().filter(md -> md.body != null).findFirst().orElse(null);
            String where = c == null ? ph.mixinClass() : c.file + ":" + (m == null ? c.d.line : m.line);
            if (m == null) {
                handlers.add(new Handler("pose", ph.fn(), false, "the handler isn't in the source", where, List.of()));
                continue;
            }
            MethodDecl h = new MethodDecl();
            h.mods = Set.of("static");
            h.ret = new TypeRef("void", List.of(), 0);
            h.name = "\u0001" + ph.fn();
            h.line = m.line;
            Map<String, String> byRole = new HashMap<>();
            Map<String, Var> aliases = new HashMap<>();
            for (int i = 0; i < m.params.size() && i < ph.roles().size(); i++) {
                String role = ph.roles().get(i), name = m.params.get(i).name();
                if (role.equals("player")) aliases.put(name, new Var(name, "game.native_player()", PLAYER, "alias", -1));
                else byRole.put(role, name);
            }
            for (String role : POSE_ORDER) {
                TypeRef t = switch (role) {
                    case "pose" -> new TypeRef("PoseStack", List.of(), 0);
                    case "hand" -> new TypeRef("InteractionHand", List.of(), 0);
                    case "stack" -> new TypeRef("ItemStack", List.of(), 0);
                    default -> new TypeRef("float", List.of(), 0);
                };
                h.params.add(new Param(t, byRole.getOrDefault(role, "unused_" + role), false));
            }
            h.body = m.body;
            preAliases.put(h, aliases);
            String doc = "/// " + c.d.qualified + "." + m.name + " (" + where + "), in Horizon's first-person hand drawing.\n";
            int before = handlers.size();
            try {
                b.append(doc).append(method(c, h)).append('\n');
                handlers.add(new Handler("pose", ph.fn(), true, null, where, List.of()));
            } catch (JavaParser.Unsupported e) {
                partialHandler(c, h, "pose", ph.fn(), where, doc, e.getMessage(), b);
            }
            if (handlers.size() > before && handlers.get(handlers.size() - 1).ok()) ok.add(ph.fn());
        }
        if (ok.isEmpty()) return;
        StringBuilder calls = new StringBuilder();
        for (String fn : ok) calls.append("        ").append(fn).append("(game, &mut pose, hand, &items[i], attack, 1.0 - heights[i], a, pitch);\n");
        b.append(POSE_FRAME.replace("$CALLS\n", calls.toString()));
    }

    private static final String POSE_FRAME = """
            /// What the mod's hand code drew for one hand: its own arm (left or right) or a stack in a display context, at a pose.
            #[derive(Clone, Debug)]
            pub struct JPoseDraw {
                pub hand_left: bool,
                pub arm: Option<bool>,
                pub item: Option<(Option<horizon_client::items::ItemStack>, &'static str)>,
                pub m: [f32; 16],
            }

            #[derive(Default)]
            struct JPoseFrame {
                left: bool,
                // inside Horizon's hand drawing (between pose_frame and pose_end)
                active: bool,
                motion: [Option<[f32; 16]>; 2],
                drawn: [bool; 2],
                seen: [bool; 2],
                draws: Vec<JPoseDraw>,
            }

            static POSE_FRAME: std::sync::Mutex<Option<JPoseFrame>> = std::sync::Mutex::new(None);

            fn pose_frame_mut<R>(f: impl FnOnce(&mut JPoseFrame) -> R) -> R {
                let mut g = POSE_FRAME.lock().unwrap_or_else(|e| e.into_inner());
                f(g.get_or_insert_with(Default::default))
            }

            /// The mod draws an arm itself (renderRightHand / renderLeftHand).
            fn j_pose_draw_arm(left_arm: bool, m: JMat4) {
                pose_frame_mut(|f| {
                    let h = f.left;
                    f.drawn[h as usize] = true;
                    f.draws.push(JPoseDraw { hand_left: h, arm: Some(left_arm), item: None, m: m.m });
                });
            }

            /// The mod draws a stack itself (renderItem), in a display context.
            fn j_pose_draw_item(stack: &Option<horizon_client::items::ItemStack>, context: &'static str, m: JMat4) {
                pose_frame_mut(|f| {
                    let h = f.left;
                    f.drawn[h as usize] = true;
                    f.draws.push(JPoseDraw { hand_left: h, arm: None, item: Some((stack.clone(), context)), m: m.m });
                });
            }

            /// HEAD of Horizon's first-person hand drawing: the mod's hand code, for each hand Horizon shows.
            pub fn pose_frame(game: &mut crate::game::Game<'_>, alpha: f64) {
                pose_frame_mut(|f| *f = JPoseFrame { active: true, ..Default::default() });
                let a = alpha as f32;
                let items = game.visible_hand_items();
                let shown = game.hands_to_render();
                let heights = game.heights.interpolated(a);
                let swing = game.swing.animation(a);
                let pitch = game.pitch;
                for (i, hand) in [horizon_client::first_person::Hand::Main, horizon_client::first_person::Hand::Off].into_iter().enumerate() {
                    if !shown[i] {
                        continue;
                    }
                    let left = game.settings.main_hand_left != (hand == horizon_client::first_person::Hand::Off);
                    let attack = if game.swing.attack_hand() == hand { swing } else { 0.0 };
                    pose_frame_mut(|f| f.left = left);
                    let mut pose = JPoseStack::new();
            $CALLS
                    let m = pose.last_pose();
                    pose_frame_mut(|f| {
                        if !f.drawn[left as usize] && m != JMat4::IDENTITY {
                            f.motion[left as usize] = Some(m.m);
                        }
                    });
                }
            }

            /// For the hand on the `left` arm, while Horizon poses it: the mod's change to the pose (column-major), and
            /// whether the mod draws that hand itself (then Horizon's own drawing of it is hidden).
            pub fn pose_motion(left: bool) -> (Option<[f32; 16]>, bool) {
                pose_frame_mut(|f| {
                    if !f.active {
                        return (None, false);
                    }
                    f.seen[left as usize] = true;
                    (f.motion[left as usize], f.drawn[left as usize])
                })
            }

            /// What the mod draws itself, for the hands Horizon posed this frame.
            pub fn pose_draws() -> Vec<JPoseDraw> {
                pose_frame_mut(|f| f.draws.iter().filter(|d| f.seen[d.hand_left as usize]).cloned().collect())
            }

            /// The end of Horizon's hand drawing: what the mod draws itself, and the frame cleared (the pose
            /// hooks then leave other calls of the same functions alone).
            pub fn pose_end() -> Vec<JPoseDraw> {
                let draws = pose_draws();
                pose_frame_mut(|f| *f = JPoseFrame::default());
                draws
            }

            """;

    /** A few words for a statement, for TODOs ("the call to player.resetFallDistance()"). */
    private static String describe(Stmt s) {
        return switch (s) {
            case ExprStmt es when es.e() instanceof Call call -> "the call to " + callText(call);
            case ExprStmt es when es.e() instanceof Assign a -> "an assignment";
            case If i -> "an `if` whose first call is " + firstCall(i);
            case While w -> "a `while` loop";
            case For f -> "a `for` loop";
            case ForEach f -> "a `for` loop";
            case Local l -> "the local `" + l.vars().get(0).name() + "`";
            case Return r -> "the `return`";
            case Try t -> "a `try`";
            case Switch sw -> "a `switch`";
            default -> "a statement";
        };
    }

    private static String callText(Call call) {
        String t = call.target() instanceof Name n ? n.id() + "." : call.target() instanceof Field f ? f.name() + "." : "";
        return "`" + t + call.name() + "(..)`";
    }

    private static String firstCall(Stmt s) {
        Call[] found = {null};
        walk(s, n -> {
            if (found[0] == null && n instanceof Call call) found[0] = call;
        });
        return found[0] == null ? "none" : callText(found[0]);
    }

    /**
     * The Fabric events the Fabric API layer (jfabricapi.rs) runs, as the mod's code names them (`Class.FIELD`), and
     * the layer's name for each. Mojang and Yarn names both.
     */
    static final Map<String, String> FABRIC_EVENTS = Map.ofEntries(
            Map.entry("ClientTickEvents.START_CLIENT_TICK", "tick_start"),
            Map.entry("ClientTickEvents.END_CLIENT_TICK", "tick"),
            Map.entry("ClientTickEvents.START_LEVEL_TICK", "level_tick_start"),
            Map.entry("ClientTickEvents.END_LEVEL_TICK", "level_tick"),
            Map.entry("ClientTickEvents.START_WORLD_TICK", "level_tick_start"),
            Map.entry("ClientTickEvents.END_WORLD_TICK", "level_tick"),
            Map.entry("ClientPlayConnectionEvents.JOIN", "join"),
            Map.entry("ClientPlayConnectionEvents.DISCONNECT", "disconnect"),
            Map.entry("ClientLifecycleEvents.CLIENT_STARTED", "started"),
            Map.entry("HudRenderCallback.EVENT", "hud"),
            Map.entry("ClientCommandRegistrationCallback.EVENT", "commands"));

    /** The layer's events in JFabricEvents (Rust field, translator hook), in the order Fabric fires them. */
    static final List<String[]> LAYER_EVENTS = List.of(new String[] {"started", "started"}, new String[] {"tick_start", "tick_start"},
            new String[] {"join", "join"}, new String[] {"disconnect", "disconnect"}, new String[] {"level_tick_start", "level_tick_start"},
            new String[] {"level_tick_end", "level_tick"}, new String[] {"tick_end", "tick"});

    private static String eventHook(Call call) {
        if (call.name().equals("register") && call.args().size() == 1 && call.target() instanceof Field ef && ef.target() instanceof Name en)
            return FABRIC_EVENTS.get(en.id() + "." + ef.name());
        if (call.target() instanceof Name hn && (hn.id().equals("HudElementRegistry") || hn.id().equals("HudLayerRegistrationCallback"))
                && Set.of("addLast", "addFirst", "attachElementBefore", "attachElementAfter").contains(call.name()) && !call.args().isEmpty())
            return "hud";
        return null;
    }

    /**
     * What each event's handler gets, by position: a Minecraft type, "level" (the world, which has no Rust value of its
     * own) or null (a value nothing here stands for: using it leaves the handler untranslated).
     */
    private static List<String> eventParams(String hook) {
        return switch (hook) {
            case "tick", "tick_start", "started" -> List.of("Minecraft");
            case "level_tick", "level_tick_start" -> List.of("level");
            case "join" -> Arrays.asList(null, null, "Minecraft");
            case "disconnect" -> Arrays.asList(null, "Minecraft");
            default -> List.of("GuiGraphics", "DeltaTracker");
        };
    }

    private static final JT LEVEL = new JT("level", "ClientLevel", null);

    /** The handler as a method, its parameters typed as the event passes them (eventParams). */
    private MethodDecl handlerMethod(String hook, String fn, Expr handler) {
        List<String> types = eventParams(hook);
        MethodDecl h = new MethodDecl();
        h.mods = Set.of("static");
        h.ret = new TypeRef("void", List.of(), 0);
        h.name = "\u0001" + fn;
        // Decompilers write `(EndTick) mc -> {...}`.
        while (handler instanceof Cast cast) handler = cast.e();
        if (handler instanceof Lambda l) {
            if (l.params().size() != types.size()) throw new JavaParser.Unsupported("a handler taking " + l.params().size() + " values");
            Map<String, Var> aliases = new HashMap<>();
            for (int i = 0; i < types.size(); i++) {
                String t = types.get(i), name = l.params().get(i);
                if (t == null) continue;
                if (t.equals("level")) aliases.put(name, new Var(name, "level", LEVEL, "alias", -1));
                else h.params.add(new Param(new TypeRef(t, List.of(), 0), name, false));
            }
            if (!aliases.isEmpty()) preAliases.put(h, aliases);
            h.body = l.body() instanceof Block blk ? blk : new Block(List.of(new ExprStmt((Expr) l.body())));
        } else if (handler instanceof MethodRef r && r.target() instanceof Name cls) {
            if (types.stream().anyMatch(t -> t == null || t.equals("level")))
                throw new JavaParser.Unsupported("a `" + hook + "` handler given as a method reference (its values have no Rust form)");
            List<Expr> args = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                h.params.add(new Param(new TypeRef(types.get(i), List.of(), 0), "arg" + i, false));
                args.add(new Name("arg" + i));
            }
            h.body = new Block(List.of(new ExprStmt(new Call(new Name(cls.id()), r.name(), args))));
        } else {
            throw new JavaParser.Unsupported("a handler that isn't a lambda or a Class::method reference");
        }
        return h;
    }

    /**
     * The table the Fabric API layer runs (J_FABRIC), and the functions the generator's hooks call: fabric_tick,
     * fabric_frame, fabric_command.
     */
    private void fabricLayer(StringBuilder b) {
        boolean events = false, started = false;
        StringBuilder table = new StringBuilder();
        for (String[] ev : LAYER_EVENTS) {
            List<String> fns = handlers.stream().filter(hd -> hd.hook().equals(ev[1]) && hd.ok()).map(Handler::fn).toList();
            if (fns.isEmpty()) continue;
            events = true;
            if (ev[1].equals("started")) started = true;
            table.append("    ").append(ev[0]).append(": &[").append(String.join(", ", fns)).append("],\n");
        }
        if (events) {
            b.append("/// The handlers the Fabric API layer (jfabricapi.rs) runs for each Fabric event.\n");
            b.append("pub static J_FABRIC: JFabricEvents = JFabricEvents {\n").append(table).append("    ..JFabricEvents::NONE\n};\n\n");
            b.append("/// Horizon's tick hook: Fabric's client tick, level tick, join and disconnect events.\n");
            b.append("pub fn fabric_tick(game: &mut crate::game::Game<'_>) {\n    j_fabric_tick(game, &J_FABRIC);\n}\n\n");
            if (started) {
                b.append("/// Horizon's frame hook: Fabric's CLIENT_STARTED, on the window's first frame.\n");
                b.append("pub fn fabric_frame(game: &mut crate::game::Game<'_>) {\n    j_fabric_frame(game, &J_FABRIC);\n}\n\n");
            }
        }
        if (!cmdRoots.isEmpty()) {
            b.append("/// The mod's client commands (Brigadier trees), for the Fabric API layer's command engine.\n");
            b.append("pub fn j_fabric_commands() -> Vec<JCmdNode> {\n    vec![\n");
            for (String r : cmdRoots) b.append("        ").append(r).append(",\n");
            b.append("    ]\n}\n\n");
            b.append("static J_FABRIC_COMMANDS: std::sync::LazyLock<Vec<JCmdNode>> = std::sync::LazyLock::new(j_fabric_commands);\n\n");
            b.append("/// Horizon's command hook: whether the command was one of the mod's (it then ran).\n");
            b.append("pub fn fabric_command(game: &mut crate::game::Game<'_>, command: &str) -> bool {\n");
            b.append("    j_cmd_run(game, &J_FABRIC_COMMANDS, command)\n}\n\n");
        }
    }

    // ---- client commands (Brigadier), for the layer's command engine ----

    /** The Rust trees of the mod's commands, in the order registered. */
    private final List<String> cmdRoots = new ArrayList<>();
    private int cmdCount;

    /** Builders whose static `literal` / `argument` start a command node. */
    private static final Set<String> CMD_BUILDERS = Set.of("ClientCommands", "ClientCommandManager", "Commands", "CommandManager",
            "LiteralArgumentBuilder", "RequiredArgumentBuilder");

    /**
     * `ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(TREE))`: each tree in
     * Rust for the layer, with each `executes` translated into a function of its own.
     */
    private void commandHandler(Cls c, MethodDecl m, Call reg, StringBuilder b) {
        String where = c.file + ":" + m.line;
        int handlersBefore = handlers.size(), countBefore = cmdCount;
        Expr handler = reg.args().get(reg.args().size() - 1);
        while (handler instanceof Cast cast) handler = cast.e();
        StringBuilder out = new StringBuilder();
        List<String> roots = new ArrayList<>();
        try {
            if (!(handler instanceof Lambda l) || l.params().isEmpty()) throw new JavaParser.Unsupported("a command registration that isn't a lambda");
            List<Object[]> trees = new ArrayList<>();
            dispatcherTrees(c, l.params().get(0), l.body(), trees, 0);
            if (trees.isEmpty()) throw new JavaParser.Unsupported("no `dispatcher.register(..)` the converter could read");
            for (Object[] t : trees) roots.add(cmdNode((Cls) t[0], (Expr) t[1], where, out));
        } catch (JavaParser.Unsupported e) {
            while (handlers.size() > handlersBefore) handlers.remove(handlers.size() - 1);
            cmdCount = countBefore;
            handlers.add(new Handler("command", "j_fabric_commands", false, "its command tree: " + e.getMessage(), where, List.of()));
            return;
        }
        b.append(out);
        cmdRoots.addAll(roots);
    }

    /** The trees passed to `dispatcher.register(..)` in `body`, also inside the mod's own methods it hands the dispatcher to. */
    private void dispatcherTrees(Cls c, String dispatcher, Object body, List<Object[]> out, int depth) {
        walk(body, n -> {
            if (!(n instanceof Call call)) return;
            if (call.name().equals("register") && call.args().size() == 1 && call.target() instanceof Name dn && dn.id().equals(dispatcher)) {
                out.add(new Object[] {c, call.args().get(0)});
                return;
            }
            int at = -1;
            for (int i = 0; i < call.args().size(); i++) if (call.args().get(i) instanceof Name an && an.id().equals(dispatcher)) at = i;
            if (at < 0 || depth >= 3) return;
            Cls owner = call.target() == null ? c : call.target() instanceof Name tn ? classes.get(tn.id()) : null;
            if (owner == null) return;
            for (MethodDecl md : owner.methods.getOrDefault(call.name(), List.of())) {
                if (md.body == null || md.params.size() != call.args().size()) continue;
                dispatcherTrees(owner, md.params.get(at).name(), md.body, out, depth + 1);
                return;
            }
        });
    }

    /** A Brigadier builder chain as a JCmdNode expression; its `executes` bodies go into `out`. */
    private String cmdNode(Cls c, Expr e, String where, StringBuilder out) {
        while (e instanceof Cast cast) e = cast.e();
        if (!(e instanceof Call call)) throw new JavaParser.Unsupported("a command built from something other than builder calls");
        List<Expr> a = call.args();
        switch (call.name()) {
            case "then":
                if (a.size() == 1 && call.target() != null) return cmdNode(c, call.target(), where, out) + "\n            .then(" + cmdNode(c, a.get(0), where, out) + ")";
                break;
            case "executes":
                if (a.size() == 1 && call.target() != null) return cmdNode(c, call.target(), where, out) + ".executes(" + cmdExec(c, a.get(0), where, out) + ")";
                break;
            case "requires":
                if (a.size() == 1 && call.target() != null) {
                    notes.add(c.d.name + ": a command's `requires(..)` check is left out (Horizon runs client commands for the player)");
                    return cmdNode(c, call.target(), where, out);
                }
                break;
            case "suggests":
                if (call.target() != null) {
                    notes.add(c.d.name + ": a command argument's suggestions are left out (no tab completion for mod commands)");
                    return cmdNode(c, call.target(), where, out);
                }
                break;
            case "literal":
                if (a.size() == 1 && call.target() instanceof Name bn && CMD_BUILDERS.contains(bn.id())) return "JCmdNode::literal(" + cmdString(c, a.get(0)) + ")";
                break;
            case "argument":
                if (a.size() == 2 && call.target() instanceof Name bn && CMD_BUILDERS.contains(bn.id()))
                    return "JCmdNode::argument(" + cmdString(c, a.get(0)) + ", " + argType(a.get(1)) + ")";
                break;
            default:
                break;
        }
        throw new JavaParser.Unsupported("a command builder's `" + call.name() + "(..)`");
    }

    /** A command or argument name: a string literal or a constant of the mod's. */
    private String cmdString(Cls c, Expr e) {
        if (e instanceof Lit l && l.kind().equals("string")) return literal(l).code();
        if (e instanceof Name n) {
            Cls owner = fieldOwner(n.id(), c, true);
            FieldDecl f = owner == null ? null : owner.fields.get(n.id());
            if (f != null && f.mods.contains("static") && f.mods.contains("final") && f.init instanceof Lit l && l.kind().equals("string")) return literal(l).code();
        }
        if (e instanceof Field fl && fl.target() instanceof Name tn && classes.containsKey(tn.id())) {
            FieldDecl f = classes.get(tn.id()).fields.get(fl.name());
            if (f != null && f.mods.contains("static") && f.mods.contains("final") && f.init instanceof Lit l && l.kind().equals("string")) return literal(l).code();
        }
        throw new JavaParser.Unsupported("a command name that isn't a fixed string");
    }

    /** Brigadier's argument types, with their bounds, as the layer's JArgType. */
    private static String argType(Expr e) {
        while (e instanceof Cast cast) e = cast.e();
        if (!(e instanceof Call call) || !(call.target() instanceof Name tn)) throw new JavaParser.Unsupported("a command argument of a type it doesn't know");
        List<Expr> a = call.args();
        String k = tn.id() + "." + call.name();
        return switch (k) {
            case "IntegerArgumentType.integer" -> "JArgType::Int(" + bound(a, 0, "int", "i32::MIN") + ", " + bound(a, 1, "int", "i32::MAX") + ")";
            case "LongArgumentType.longArg" -> "JArgType::Long(" + bound(a, 0, "long", "i64::MIN") + ", " + bound(a, 1, "long", "i64::MAX") + ")";
            case "FloatArgumentType.floatArg" -> "JArgType::Float(" + bound(a, 0, "float", "f32::MIN") + ", " + bound(a, 1, "float", "f32::MAX") + ")";
            case "DoubleArgumentType.doubleArg" -> "JArgType::Double(" + bound(a, 0, "double", "f64::MIN") + ", " + bound(a, 1, "double", "f64::MAX") + ")";
            case "BoolArgumentType.bool" -> "JArgType::Bool";
            case "StringArgumentType.word" -> "JArgType::Word";
            case "StringArgumentType.string" -> "JArgType::Quotable";
            case "StringArgumentType.greedyString" -> "JArgType::Greedy";
            default -> throw new JavaParser.Unsupported("a command argument of type " + k + " (only numbers, booleans and strings are mapped)");
        };
    }

    /** An argument type's bound: a number literal or MIN_VALUE / MAX_VALUE, else the type's own limit. */
    private static String bound(List<Expr> a, int i, String kind, String dflt) {
        if (i >= a.size()) return dflt;
        Expr e = a.get(i);
        if (e instanceof Field f && f.target() instanceof Name n && Set.of("Integer", "Long", "Float", "Double").contains(n.id())) {
            String rust = switch (n.id()) {
                case "Integer" -> "i32";
                case "Long" -> "i64";
                case "Float" -> "f32";
                default -> "f64";
            };
            if (f.name().equals("MAX_VALUE")) return rust + "::MAX";
            if (f.name().equals("MIN_VALUE")) return rust.startsWith("f") ? rust + "::MIN_POSITIVE" : rust + "::MIN";
        }
        if (e instanceof Lit l && Set.of("int", "long", "float", "double").contains(l.kind())) {
            String t = l.text().replace("_", "");
            boolean hex = t.toLowerCase().startsWith("0x") || t.toLowerCase().startsWith("-0x");
            if (!hex) t = t.replaceAll("[fFdDlL]$", "");
            if (kind.equals("float") || kind.equals("double")) {
                if (hex) throw new JavaParser.Unsupported("a hexadecimal bound");
                return Double.toString(Double.parseDouble(t));
            }
            if (l.kind().equals("float") || l.kind().equals("double")) throw new JavaParser.Unsupported("a fractional bound on a whole-number argument");
            return hex ? t.replaceAll("[lL]$", "") : t;
        }
        throw new JavaParser.Unsupported("a command argument bound that isn't a fixed number");
    }

    /**
     * An `executes(..)` as a Rust function the layer calls: the lambda (or `Class::method`) translated with its
     * CommandContext standing for the running command. A body that ends in `return 1;` may be translated in part,
     * like an event handler; one that can't be gets a stand-in that says so in chat.
     */
    private String cmdExec(Cls c, Expr f, String where, StringBuilder out) {
        String fn = "fabric_cmd_" + (cmdCount++);
        while (f instanceof Cast cast) f = cast.e();
        String p;
        Object body;
        if (f instanceof Lambda l && l.params().size() == 1) {
            p = l.params().get(0);
            body = l.body();
        } else if (f instanceof MethodRef r && r.target() instanceof Name cls && !r.name().equals("new")) {
            p = "hp_ctx";
            body = new Return(new Call(new Name(cls.id()), r.name(), List.of(new Name(p))));
        } else {
            String why = "an `executes` that isn't a lambda or a Class::method reference";
            handlers.add(new Handler("command", fn, false, why, where, List.of()));
            return cmdStub(fn, where, why, out);
        }
        Block blk = body instanceof Block bb ? bb : body instanceof Return ret ? new Block(List.of(ret)) : new Block(List.of(new Return((Expr) body)));
        MethodDecl h = new MethodDecl();
        h.mods = Set.of("static");
        h.name = "\u0001" + fn;
        h.line = 0;
        h.params.add(new Param(new TypeRef("CommandContext", List.of(), 0), p, false));
        String doc = "/// An `executes` of the command registered at " + where + ".\n";
        List<Stmt> st = blk.stmts();
        Lit fixed = null;
        if (!st.isEmpty() && st.get(st.size() - 1) instanceof Return r && r.e() instanceof Lit lit && lit.kind().equals("int")) {
            boolean[] other = {false};
            for (Stmt s : st.subList(0, st.size() - 1)) walk(s, n -> {
                if (n instanceof Return) other[0] = true;
            });
            if (!other[0]) fixed = lit;
        }
        if (fixed != null) {
            h.ret = new TypeRef("void", List.of(), 0);
            h.body = new Block(st.subList(0, st.size() - 1));
            StringBuilder tmp = new StringBuilder();
            int before = handlers.size();
            try {
                tmp.append(doc).append(method(c, h)).append('\n');
                handlers.add(new Handler("command", fn, true, null, where, List.of()));
            } catch (JavaParser.Unsupported e) {
                partialHandler(c, h, "command", fn, where, doc, e.getMessage(), tmp);
            }
            Handler got = handlers.size() > before ? handlers.get(handlers.size() - 1) : null;
            if (got == null || !got.ok()) return cmdStub(fn + "_run", where, got == null ? "not translated" : got.reason(), out);
            out.append(tmp);
            out.append("fn ").append(fn).append("_run(game: &mut crate::game::Game<'_>) -> i32 {\n    ").append(fn).append("(game);\n    ")
                    .append(fixed.text()).append("\n}\n\n");
            return fn + "_run";
        }
        h.ret = new TypeRef("int", List.of(), 0);
        h.body = blk;
        try {
            out.append(doc).append(method(c, h)).append('\n');
            handlers.add(new Handler("command", fn, true, null, where, List.of()));
            return fn;
        } catch (JavaParser.Unsupported e) {
            handlers.add(new Handler("command", fn, false, e.getMessage(), where, List.of()));
            return cmdStub(fn, where, e.getMessage(), out);
        }
    }

    /** A command part that wasn't translated: it says so in chat, and Brigadier's result is 0 (failure). */
    private static String cmdStub(String fn, String where, String reason, StringBuilder out) {
        out.append("/// An `executes` of the command registered at ").append(where).append(", not translated: ")
                .append(reason.replace('\n', ' ')).append(".\n");
        out.append("fn ").append(fn).append("(game: &mut crate::game::Game<'_>) -> i32 {\n");
        out.append("    game.native_chat(\"This part of the command isn't ported to Horizon yet.\");\n    0\n}\n\n");
        return fn;
    }

    /** The mod's KeyMapping statics and their key names, from `X = ...new KeyMapping("key.mod.name", ...)`. */
    private void findKeys() {
        for (Cls c : all) {
            for (FieldDecl f : c.d.fields) {
                String full = f.init == null ? null : keyMappingName(f.init);
                if (full != null && f.mods.contains("static")) addKey(fieldKey(c, f.name), full);
            }
            for (MethodDecl m : c.d.methods) {
                if (m.body == null) continue;
                walk(m.body, n -> {
                    if (!(n instanceof Assign a) || !a.op().equals("=")) return;
                    String full = keyMappingName(a.value());
                    if (full == null) return;
                    if (a.target() instanceof Name tn) {
                        Cls owner = fieldOwner(tn.id(), c, true);
                        if (owner != null) addKey(fieldKey(owner, tn.id()), full);
                    } else if (a.target() instanceof Field tf && tf.target() instanceof Name cn && classes.containsKey(cn.id())) {
                        addKey(fieldKey(classes.get(cn.id()), tf.name()), full);
                    }
                });
            }
        }
    }

    private void addKey(String field, String full) {
        String name = full.substring(full.lastIndexOf('.') + 1), def = "key.keyboard.unknown";
        for (String[] k : modKeys) {
            if (k.length > 3 && k[3].equals(full)) {
                name = k[0];
                def = k[2];
            }
        }
        keyFields.put(field, name);
        keyDefaults.put(name, def);
    }

    private static String keyMappingName(Expr e) {
        String[] found = {null};
        walk(e, n -> {
            if (found[0] == null && n instanceof New nw && (nw.type().simple().equals("KeyMapping") || nw.type().simple().equals("KeyBinding"))
                    && !nw.args().isEmpty() && nw.args().get(0) instanceof Lit l && l.kind().equals("string"))
                found[0] = l.text().substring(1, l.text().length() - 1);
        });
        return found[0];
    }

    private static final String USE_ANIMATION = """
            /// Java's ItemStack.getUseAnimation, from Horizon's item data: a consumable's animation, a shield's block, else by item.
            fn j_use_animation(game: &crate::game::Game<'_>, s: &Option<horizon_client::items::ItemStack>) -> &'static str {
                let Some(s) = s else { return "NONE" };
                let id: &str = &s.item;
                if let Some(info) = game.res.items().get(id) {
                    if let Some(c) = &info.consumable {
                        return match format!("{:?}", c.animation).as_str() {
                            "Eat" => "EAT",
                            "Drink" => "DRINK",
                            "Block" => "BLOCK",
                            "Bow" => "BOW",
                            "Trident" => "TRIDENT",
                            "Crossbow" => "CROSSBOW",
                            "Spyglass" => "SPYGLASS",
                            "TootHorn" => "TOOT_HORN",
                            "Brush" => "BRUSH",
                            "Bundle" => "BUNDLE",
                            "Spear" => "SPEAR",
                            _ => "NONE",
                        };
                    }
                    if info.blocks_attacks.is_some() {
                        return "BLOCK";
                    }
                }
                match id {
                    "minecraft:bow" => "BOW",
                    "minecraft:crossbow" => "CROSSBOW",
                    "minecraft:trident" => "TRIDENT",
                    "minecraft:spyglass" => "SPYGLASS",
                    "minecraft:goat_horn" => "TOOT_HORN",
                    "minecraft:brush" => "BRUSH",
                    _ if id.ends_with("bundle") => "BUNDLE",
                    _ if id.ends_with("_spear") => "SPEAR",
                    _ => "NONE",
                }
            }

            """;

    /** Item classes that are one item: `item instanceof CrossbowItem` is the crossbow. */
    private static final Map<String, String> ITEM_CLASSES = Map.ofEntries(Map.entry("CrossbowItem", "minecraft:crossbow"),
            Map.entry("BowItem", "minecraft:bow"), Map.entry("TridentItem", "minecraft:trident"), Map.entry("ShieldItem", "minecraft:shield"),
            Map.entry("FishingRodItem", "minecraft:fishing_rod"), Map.entry("SpyglassItem", "minecraft:spyglass"), Map.entry("MaceItem", "minecraft:mace"),
            Map.entry("BrushItem", "minecraft:brush"), Map.entry("FlintAndSteelItem", "minecraft:flint_and_steel"), Map.entry("ShearsItem", "minecraft:shears"),
            Map.entry("CompassItem", "minecraft:compass"), Map.entry("MapItem", "minecraft:filled_map"), Map.entry("EmptyMapItem", "minecraft:map"),
            Map.entry("ElytraItem", "minecraft:elytra"), Map.entry("WindChargeItem", "minecraft:wind_charge"), Map.entry("EnderpearlItem", "minecraft:ender_pearl"));

    private static final String RENDER_STATE = """
            /// Minecraft's ItemStackRenderState, as far as the mod reads it: the stack and display context it was resolved for.
            #[derive(Clone, Debug, Default)]
            pub struct JRenderState {
                pub stack: Option<horizon_client::items::ItemStack>,
                pub context: &'static str,
            }

            /// ItemStackRenderState.usesBlockLight: Horizon resolves the item for that context and answers the same.
            fn j_uses_block_light(game: &crate::game::Game<'_>, s: &JRenderState) -> bool {
                let Some(stack) = &s.stack else { return false };
                use horizon_assets::item_model::DisplayContext as C;
                let display = match s.context {
                    "third_person_left_hand" => C::ThirdPersonLeftHand,
                    "third_person_right_hand" => C::ThirdPersonRightHand,
                    "first_person_left_hand" => C::FirstPersonLeftHand,
                    "first_person_right_hand" => C::FirstPersonRightHand,
                    "head" => C::Head,
                    "gui" => C::Gui,
                    "ground" => C::Ground,
                    "fixed" => C::Fixed,
                    _ => C::None,
                };
                let owner = game.player_owner(Some(stack));
                game.resolve_item(stack, display, Some(&owner), 0).uses_block_light()
            }

            """;

    private static final String MOVE_DIST = """
            static MOVE_DIST: std::sync::Mutex<(u64, f64, f64, f32)> = std::sync::Mutex::new((u64::MAX, 0.0, 0.0, 0.0));

            /// Entity.moveDist: horizontal distance walked times 0.6, as Entity.move adds it. Horizon keeps none,
            /// so it's added up here once a tick from the player's position.
            fn j_move_dist(game: &crate::game::Game<'_>) -> f32 {
                let mut s = MOVE_DIST.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(p) = game.player.as_ref() {
                    if s.0 != game.ticks {
                        if s.0 != u64::MAX {
                            let (dx, dz) = (p.position.x - s.1, p.position.z - s.2);
                            s.3 += (dx * dx + dz * dz).sqrt() as f32 * 0.6;
                        }
                        s.0 = game.ticks;
                        s.1 = p.position.x;
                        s.2 = p.position.z;
                    }
                }
                s.3
            }

            """;

    private static final String ITEM_TAG = """
            /// `stack.is(ItemTags.X)`: the stack's item is in Horizon's item tag.
            fn j_item_tag(game: &crate::game::Game<'_>, s: &Option<horizon_client::items::ItemStack>, tag: &str) -> bool {
                s.as_ref().is_some_and(|s| game.res.items().item_tags.get(tag).is_some_and(|t| t.contains(&*s.item)))
            }

            """;

    private static final String BLOCK_ITEM = """
            /// `item instanceof BlockItem`: Horizon's item data says the item places a block.
            fn j_is_block_item(game: &crate::game::Game<'_>, id: &str) -> bool {
                game.res.items().get(id).is_some_and(|i| i.block.is_some())
            }

            """;

    private static final String Y_ROT_O = """
            static Y_ROT_O: std::sync::Mutex<(u64, f32, f32)> = std::sync::Mutex::new((u64::MAX, 0.0, 0.0));

            /// LocalPlayer.yRotO, the yaw when this tick began: Horizon keeps none, so it's kept here tick by tick.
            fn j_y_rot_o(game: &crate::game::Game<'_>) -> f32 {
                let mut s = Y_ROT_O.lock().unwrap_or_else(|e| e.into_inner());
                if s.0 != game.ticks {
                    let before = if s.0 == u64::MAX { game.yaw } else { s.1 };
                    *s = (game.ticks, game.yaw, before);
                }
                s.2
            }

            """;

    /** Key state the key hook keeps, so `consumeClick()` and `isDown()` work as KeyMapping's did. */
    private String keyGlue() {
        int n = keyDefaults.size();
        StringBuilder b = new StringBuilder();
        b.append("/// The mod's keys: name and default key, as Options > Mods lists them.\n");
        b.append("const KEY_NAMES: [(&str, &str); ").append(n).append("] = [");
        for (Map.Entry<String, String> k : keyDefaults.entrySet()) b.append("(\"").append(k.getKey()).append("\", \"").append(k.getValue()).append("\"), ");
        b.append("];\n");
        b.append("static KEY_CLICKS: [std::sync::atomic::AtomicU32; ").append(n).append("] = [const { std::sync::atomic::AtomicU32::new(0) }; ").append(n).append("];\n");
        b.append("static KEY_DOWN: [std::sync::atomic::AtomicBool; ").append(n).append("] = [const { std::sync::atomic::AtomicBool::new(false) }; ").append(n).append("];\n\n");
        b.append("/// Called by the key hook: counts presses of the mod's keys and keeps whether each is held.\n");
        b.append("pub fn key_event(game: &mut crate::game::Game<'_>, key: &str, pressed: bool) {\n");
        b.append("    for (i, (name, default)) in KEY_NAMES.iter().enumerate() {\n");
        b.append("        if key == game.native_mod_key(").append(runtimePath).append("::ID, name, default) {\n");
        b.append("            KEY_DOWN[i].store(pressed, std::sync::atomic::Ordering::Relaxed);\n");
        b.append("            if pressed {\n                KEY_CLICKS[i].fetch_add(1, std::sync::atomic::Ordering::Relaxed);\n            }\n");
        b.append("        }\n    }\n}\n\n");
        b.append("fn key_index(name: &str) -> usize {\n    KEY_NAMES.iter().position(|k| k.0 == name).expect(\"not one of the mod's keys\")\n}\n\n");
        b.append("/// Java's KeyMapping.consumeClick: true once per press not yet taken.\n");
        // fetch_update is try_update after Rust 1.95; Horizon builds with 1.85.
        b.append("#[allow(deprecated)]\npub fn key_consume(name: &str) -> bool {\n");
        b.append("    let r = std::sync::atomic::Ordering::Relaxed;\n");
        b.append("    KEY_CLICKS[key_index(name)].fetch_update(r, r, |n| n.checked_sub(1)).is_ok()\n}\n\n");
        b.append("/// Java's KeyMapping.isDown.\npub fn key_down(name: &str) -> bool {\n");
        b.append("    KEY_DOWN[key_index(name)].load(std::sync::atomic::Ordering::Relaxed)\n}\n");
        return b.toString();
    }

    /** `this.f = p;` / `f = p;` at the top level of a constructor, with p one of its parameters: p. */
    private static Expr ctorParamFor(MethodDecl m, String field) {
        for (Stmt st : m.body.stmts()) {
            if (!(st instanceof ExprStmt es) || !(es.e() instanceof Assign as) || !as.op().equals("=")) continue;
            boolean target = as.target() instanceof Field fl && fl.target() instanceof This && fl.name().equals(field)
                    || as.target() instanceof Name tn && tn.id().equals(field) && m.params.stream().noneMatch(p -> p.name().equals(field));
            if (target && as.value() instanceof Name vn && m.params.stream().anyMatch(p -> p.name().equals(vn.id()))) return vn;
        }
        return null;
    }

    private String zero(JT t) {
        return switch (t.k()) {
            case "prim" -> switch (t.name()) {
                case "boolean" -> "false";
                case "char" -> "'\\0'";
                case "float" -> "0.0f32";
                case "double" -> "0.0f64";
                default -> "0" + rustPrim(t.name());
            };
            case "opt" -> "None";
            case "ident" -> "JIdentifier::new(\"\", \"\")";
            case "vec3f" -> "JVec3::new(0.0, 0.0, 0.0)";
            case "quat" -> "JQuat::IDENTITY";
            case "mat4" -> "JMat4::IDENTITY";
            case "posestack" -> "JPoseStack::new()";
            case "renderstate" -> "JRenderState::default()";
            case "map" -> "JMap::new()";
            case "set" -> "JSet::new()";
            case "string" -> throw new JavaParser.Unsupported("a String with no value (null in Java)");
            case "array", "list" -> throw new JavaParser.Unsupported("an array or list with no value (null in Java)");
            default -> throw new JavaParser.Unsupported("a " + t + " with no value (null in Java)");
        };
    }

    // ---- config classes ----

    private List<FieldDecl> options(Cls c) {
        List<FieldDecl> out = new ArrayList<>();
        for (FieldDecl f : c.d.fields) {
            if (f.mods.contains("static") || !f.mods.contains("public")) continue;
            if (isPrimType(f.type) || f.type.name().equals("String") && f.type.dims() == 0) out.add(f);
        }
        return out;
    }

    private String configMethods(Cls c) {
        StringBuilder b = new StringBuilder();
        b.append("/// Reads each option kept with Horizon's `native_setting` (one key per field); missing or\n");
        b.append("/// unreadable ones keep their value.\n");
        b.append("pub fn load_settings(&mut self, get: impl Fn(&str) -> Option<String>) {\n");
        for (FieldDecl f : options(c)) {
            String ty = f.type.name().equals("String") ? "String" : rustPrim(f.type.name());
            b.append("    if let Some(v) = get(\"").append(f.name).append("\").and_then(|v| v.trim().parse::<").append(ty).append(">().ok()) {\n");
            b.append("        self.").append(rustName(f.name)).append(" = v;\n    }\n");
        }
        b.append("}\n/// Keeps every option with Horizon's `native_set_setting`.\n");
        b.append("pub fn save_settings(&self, mut set: impl FnMut(&str, &str)) {\n");
        for (FieldDecl f : options(c)) b.append("    set(\"").append(f.name).append("\", &self.").append(rustName(f.name)).append(".to_string());\n");
        b.append("}\n/// The on/off options, for the settings page: key and label.\n");
        b.append("pub const TOGGLES: &[(&str, &str)] = &[\n");
        for (FieldDecl f : options(c)) if (f.type.name().equals("boolean")) b.append("    (\"").append(f.name).append("\", \"").append(escapes(optionLabel(c, f.name).replace("\\", "\\\\").replace("\"", "\\\""), '"')).append("\"),\n");
        b.append("];\n/// An on/off option's value.\npub fn flag(&self, key: &str) -> bool {\n    match key {\n");
        for (FieldDecl f : options(c)) if (f.type.name().equals("boolean")) b.append("        \"").append(f.name).append("\" => self.").append(rustName(f.name)).append(",\n");
        b.append("        _ => false,\n    }\n}\n/// Flips an on/off option.\npub fn toggle(&mut self, key: &str) {\n    match key {\n");
        for (FieldDecl f : options(c)) if (f.type.name().equals("boolean")) b.append("        \"").append(f.name).append("\" => self.").append(rustName(f.name)).append(" = !self.").append(rustName(f.name)).append(",\n");
        b.append("        _ => {}\n    }\n}\n");
        List<String[]> nums = numberOptions(c);
        b.append("/// The number options the mod's own screen has sliders for: key, label, min, max, step.\n");
        b.append("pub const NUMBERS: &[(&str, &str, f64, f64, f64)] = &[\n");
        for (String[] n : nums) b.append("    (\"").append(n[0]).append("\", \"").append(n[1]).append("\", ").append(n[2]).append(", ").append(n[3]).append(", ").append(n[4]).append("),\n");
        b.append("];\n/// A number option's value.\npub fn number(&self, key: &str) -> f64 {\n    match key {\n");
        for (String[] n : nums) b.append("        \"").append(n[0]).append("\" => self.").append(rustName(n[0])).append(" as f64,\n");
        b.append("        _ => 0.0,\n    }\n}\n");
        b.append("/// Moves a number option `steps` slider steps, kept in its range and on its steps, as the slider would.\n");
        b.append("pub fn nudge(&mut self, key: &str, steps: i32) {\n");
        b.append("    let Some(&(_, _, min, max, step)) = Self::NUMBERS.iter().find(|n| n.0 == key) else {\n        return;\n    };\n");
        b.append("    let v = (((self.number(key) + steps as f64 * step - min) / step).round() * step + min).clamp(min, max);\n");
        b.append("    match key {\n");
        for (String[] n : nums) {
            FieldDecl f = c.fields.get(n[0]);
            b.append("        \"").append(n[0]).append("\" => self.").append(rustName(n[0])).append(" = v");
            if (!f.type.name().equals("double")) b.append(" as ").append(rustPrim(f.type.name()));
            b.append(",\n");
        }
        b.append("        _ => {}\n    }\n}\n");
        b.append("/// How a number option reads on the page: as many decimals as its step has.\n");
        b.append("pub fn number_text(&self, key: &str) -> String {\n");
        b.append("    let step = Self::NUMBERS.iter().find(|n| n.0 == key).map_or(1.0, |n| n.4);\n");
        b.append("    let decimals = if step >= 1.0 { 0 } else { (-step.log10()).ceil() as usize };\n");
        b.append("    format!(\"{:.*}\", decimals, self.number(key))\n}\n");
        return b.toString();
    }

    /**
     * Number options with a slider in the mod's own screens: a call whose arguments have three number literals
     * (min, max, step) and a getter lambda that reads the option (`() -> c.armX`), as config screens build sliders.
     */
    private List<String[]> numberOptions(Cls cfg) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<String> numeric = new HashSet<>();
        for (FieldDecl f : options(cfg)) if (isPrimType(f.type) && !f.type.name().equals("boolean") && !f.type.name().equals("char")) numeric.add(f.name);
        for (Cls k : all)
            for (MethodDecl m : k.d.methods) {
                if (m.body == null) continue;
                walk(m.body, n -> {
                    if (!(n instanceof Call call)) return;
                    List<Double> nums = new ArrayList<>();
                    String field = null;
                    for (Expr a : call.args()) {
                        Double d = numberLit(a);
                        if (d != null && field == null) nums.add(d);
                        if (a instanceof Lambda l && l.params().isEmpty() && l.body() instanceof Expr body) {
                            String name = body instanceof Field fb ? fb.name() : body instanceof Name nb ? nb.id() : null;
                            if (name != null && numeric.contains(name)) field = name;
                        }
                    }
                    if (field == null || nums.size() < 3 || out.containsKey(field)) return;
                    double min = nums.get(nums.size() - 3), max = nums.get(nums.size() - 2), step = nums.get(nums.size() - 1);
                    if (!(min < max) || !(step > 0) || step > max - min) return;
                    out.put(field, new String[] {field, escapes(optionLabel(cfg, field).replace("\\", "\\\\").replace("\"", "\\\""), '"'), lit(min), lit(max), lit(step)});
                });
            }
        return new ArrayList<>(out.values());
    }

    private static Double numberLit(Expr e) {
        if (e instanceof Unary u && u.op().equals("-") && !u.postfix()) {
            Double d = numberLit(u.e());
            return d == null ? null : -d;
        }
        if (e instanceof Lit l && (l.kind().equals("int") || l.kind().equals("double") || l.kind().equals("float") || l.kind().equals("long"))) {
            try {
                return Double.parseDouble(l.text().replaceAll("[fFdDlL_]", ""));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String lit(double d) {
        String s = Double.toString(d);
        return s.contains("E") ? String.format("%.10f", d) : s;
    }

    private String configStatics(Cls c) {
        return "static SETTINGS_LOADED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);\n"
                + "/// Loads " + c.d.name + "." + c.singleton + " from Horizon's settings the first time it's asked.\n"
                + "pub fn ensure_loaded(get: impl Fn(&str) -> Option<String>) {\n"
                + "    if !SETTINGS_LOADED.swap(true, std::sync::atomic::Ordering::SeqCst) {\n"
                + "        " + staticName(c, c.singleton) + ".with(|c| c.load_settings(get));\n    }\n}\n";
    }

    private Config configOf(Cls c) {
        List<String[]> toggles = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (FieldDecl f : options(c)) {
            keys.add(f.name);
            if (f.type.name().equals("boolean")) toggles.add(new String[] {f.name, optionLabel(c, f.name)});
        }
        return new Config(c.module, c.rust, staticName(c, c.singleton), toggles, keys, numberOptions(c));
    }

    /** showOffhandArm -> "Show offhand arm". */
    static String label(String camel) {
        String s = camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- tree walks ----

    private interface Visitor {
        void visit(Object node);
    }

    /** Every Expr and Stmt under `node`, in order. */
    private static void walk(Object node, Visitor v) {
        if (node == null) return;
        v.visit(node);
        switch (node) {
            case Block b -> b.stmts().forEach(s -> walk(s, v));
            case Local l -> l.vars().forEach(d -> walk(d.init(), v));
            case ExprStmt e -> walk(e.e(), v);
            case If i -> { walk(i.c(), v); walk(i.then(), v); walk(i.otherwise(), v); }
            case While w -> { walk(w.c(), v); walk(w.body(), v); }
            case DoWhile d -> { walk(d.body(), v); walk(d.c(), v); }
            case For f -> { f.init().forEach(s -> walk(s, v)); walk(f.cond(), v); f.update().forEach(u -> walk(u, v)); walk(f.body(), v); }
            case ForEach f -> { walk(f.iter(), v); walk(f.body(), v); }
            case Return r -> walk(r.e(), v);
            case Throw t -> walk(t.e(), v);
            case Try t -> { walk(t.body(), v); t.catches().forEach(c -> walk(c.body(), v)); walk(t.fin(), v); }
            case Switch s -> { walk(s.sel(), v); s.cases().forEach(c -> { c.labels().forEach(l -> walk(l, v)); c.body().forEach(b -> walk(b, v)); }); }
            case Sync s -> { walk(s.lock(), v); walk(s.body(), v); }
            case Field f -> walk(f.target(), v);
            case Index i -> { walk(i.array(), v); walk(i.index(), v); }
            case Call c -> { walk(c.target(), v); c.args().forEach(a -> walk(a, v)); }
            case New n -> n.args().forEach(a -> walk(a, v));
            case NewArray n -> { n.dims().forEach(d -> walk(d, v)); walk(n.init(), v); }
            case ArrayInit a -> a.items().forEach(i -> walk(i, v));
            case Unary u -> walk(u.e(), v);
            case Binary b -> { walk(b.l(), v); walk(b.r(), v); }
            case Assign a -> { walk(a.target(), v); walk(a.value(), v); }
            case Cond c -> { walk(c.c(), v); walk(c.a(), v); walk(c.b(), v); }
            case Cast c -> walk(c.e(), v);
            case InstanceOf i -> walk(i.e(), v);
            case Lambda l -> walk(l.body(), v);
            case MethodRef r -> walk(r.target(), v);
            case SwitchExpr s -> walk(s.sw(), v);
            case Yield y -> walk(y.e(), v);
            default -> { }
        }
    }

    private static boolean hasContinue(Stmt body) {
        boolean[] hit = {false};
        hasContinue(body, hit, 0);
        return hit[0];
    }

    private static void hasContinue(Object node, boolean[] hit, int depth) {
        if (node == null) return;
        if (node instanceof Continue && depth == 0) hit[0] = true;
        switch (node) {
            case Block b -> b.stmts().forEach(s -> hasContinue(s, hit, depth));
            case If i -> { hasContinue(i.then(), hit, depth); hasContinue(i.otherwise(), hit, depth); }
            case Switch s -> s.cases().forEach(c -> c.body().forEach(b -> hasContinue(b, hit, depth)));
            case Try t -> { hasContinue(t.body(), hit, depth); t.catches().forEach(c -> hasContinue(c.body(), hit, depth)); }
            case Sync s -> hasContinue(s.body(), hit, depth);
            default -> { }
        }
    }

    /** Names (variables and fields) given a new value anywhere under node. Writing an element doesn't count: it can't change a length. */
    private static void writes(Object node, Set<String> out) {
        walk(node, n -> {
            Expr t = n instanceof Assign a ? a.target() : n instanceof Unary u && (u.op().equals("++") || u.op().equals("--")) ? u.e() : null;
            if (t instanceof Name nm) out.add(nm.id());
            if (t instanceof Field f) out.add(f.name());
            if (n instanceof Call c && c.name().matches("add|remove|clear|addAll|removeIf|set") ) {
                if (c.target() instanceof Name nm) out.add(nm.id());
                if (c.target() instanceof Field f) out.add(f.name());
            }
        });
    }

    private static void names(Expr e, Set<String> out) {
        walk(e, n -> {
            if (n instanceof Name nm) out.add(nm.id());
            if (n instanceof Field f) out.add(f.name());
        });
    }

    private static boolean hasCalls(Expr e) {
        boolean[] hit = {false};
        walk(e, n -> {
            if (n instanceof Call) hit[0] = true;
        });
        return hit[0];
    }

    // ---- names and text ----

    private static final Set<String> KEYWORDS = Set.of("as", "async", "await", "box", "break", "const", "continue", "crate", "dyn", "else",
            "enum", "extern", "false", "fn", "for", "gen", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref",
            "return", "self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "yield", "abstract",
            "become", "do", "final", "macro", "override", "priv", "try", "typeof", "unsized", "virtual");

    /** A parameter's or local's Rust name: in a constructor `s` is the object being made, so a Java `s` moves aside. */
    private static String varName(String javaName, Ctx x) {
        String rn = rustName(javaName);
        return x.ctor && rn.equals("s") ? "s_" : rn;
    }

    private static final Set<String> RUST_PRELUDE = Set.of("Option", "Some", "None", "Result", "Ok", "Err", "Vec", "String", "Box",
            "Clone", "Copy", "Debug", "Default", "Drop", "Fn", "FnMut", "FnOnce", "Iterator", "IntoIterator", "Into", "From", "PartialEq",
            "Eq", "Ord", "PartialOrd", "Send", "Sync", "Sized", "ToString", "ToOwned", "AsRef", "AsMut", "Extend", "Hash", "Self",
            "LazyLock", "JMap", "JSet", "JCell", "JIter", "JJson", "JFuture", "JRegex", "JIdentifier", "JVersion", "JMod");

    static String rustName(String javaName) {
        String s = snake(javaName);
        if (s.equals("self") || s.equals("super") || s.equals("crate") || s.equals("Self")) return s + "_";
        return KEYWORDS.contains(s) ? "r#" + s : s;
    }

    static String snake(String s) {
        s = s.replace('$', '_');
        if (s.equals(s.toUpperCase())) return s.toLowerCase();
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").toLowerCase();
    }

    /** A static's Rust name, unique in its module (Java's LOOK and look both want LOOK). */
    private static String staticName(Cls c, String java) {
        if (c.staticNames == null) {
            c.staticNames = new HashMap<>();
            Set<String> used = new HashSet<>();
            for (FieldDecl f : c.d.fields) {
                if (!f.mods.contains("static")) continue;
                String n = staticName(f.name), base = n;
                for (int i = 2; !used.add(n); i++) n = base + "_" + i;
                c.staticNames.put(f.name, n);
            }
        }
        return c.staticNames.getOrDefault(java, staticName(java));
    }

    private static boolean startsPath(String code, String path) {
        return code.startsWith(path) && (code.length() == path.length() || !Character.isJavaIdentifierPart(code.charAt(path.length())));
    }

    private static String staticName(String java0) {
        String java = java0.replace('$', '_');
        String s = java.equals(java.toUpperCase()) ? java : snake(java).toUpperCase();
        return KEYWORDS.contains(s.toLowerCase()) && s.equals(s.toLowerCase()) ? "r#" + s : s;
    }

    static String camel(String constant) {
        StringBuilder b = new StringBuilder();
        for (String part : constant.toLowerCase().split("_")) {
            if (part.isEmpty()) continue;
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String s = b.toString();
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) ? "V" + s : s;
    }

    private static String paren(String code) {
        if (code.matches("[\\w.:]+(\\(\\))?") || code.matches("\"([^\"\\\\]|\\\\.)*\"") || code.matches("'([^'\\\\]|\\\\.)+'")) return code;
        if (code.startsWith("(") && balancedWrap(code)) return code;
        if (code.matches("[\\w:]+(\\.[\\w]+|\\[[^\\[\\]]*\\])*") || code.matches("[\\w:]+\\([^()]*\\)")) return code;
        return "(" + code + ")";
    }

    private static boolean balancedWrap(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0 && i < s.length() - 1) return false;
            }
        }
        return s.endsWith(")");
    }

    private static String indent(String code, int levels) {
        String pad = "    ".repeat(levels);
        StringBuilder b = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (line.isEmpty()) continue;
            b.append(pad).append(line).append('\n');
        }
        return b.toString();
    }

    /** Helpers the translated code calls: Java's behaviour where Rust's differs. */
    static final String PRELUDE = """
            use std::sync::{LazyLock, Mutex};

            /// A Java static that changes: one value behind a lock.
            pub struct JCell<T>(Mutex<T>);
            impl<T: Clone> JCell<T> {
                pub const fn new(v: T) -> Self {
                    Self(Mutex::new(v))
                }
                pub fn get(&self) -> T {
                    self.0.lock().unwrap_or_else(|e| e.into_inner()).clone()
                }
                pub fn set(&self, v: T) {
                    *self.0.lock().unwrap_or_else(|e| e.into_inner()) = v;
                }
                pub fn with<R>(&self, f: impl FnOnce(&mut T) -> R) -> R {
                    f(&mut self.0.lock().unwrap_or_else(|e| e.into_inner()))
                }
            }

            /// Java's HashMap, LinkedHashMap and TreeMap. Keeps insertion order (LinkedHashMap's; HashMap's
            /// order is unspecified), or key order when made with `new_sorted` (TreeMap's).
            #[derive(Clone, Debug)]
            pub struct JMap<K, V> {
                index: std::collections::HashMap<K, usize>,
                entries: Vec<(K, V)>,
                sorted: bool,
            }
            /// Java's Map.equals: the same keys with equal values, in any order.
            impl<K: std::hash::Hash + Eq, V: PartialEq> PartialEq for JMap<K, V> {
                fn eq(&self, o: &Self) -> bool {
                    self.entries.len() == o.entries.len()
                        && self.entries.iter().all(|(k, v)| o.index.get(k).is_some_and(|&i| o.entries[i].1 == *v))
                }
            }
            impl<K, V> Default for JMap<K, V> {
                fn default() -> Self {
                    Self { index: std::collections::HashMap::new(), entries: Vec::new(), sorted: false }
                }
            }
            impl<K: std::hash::Hash + Ord + Clone, V> JMap<K, V> {
                pub fn new() -> Self {
                    Self::default()
                }
                pub fn new_sorted() -> Self {
                    Self { sorted: true, ..Self::default() }
                }
                pub fn from_iter(pairs: impl IntoIterator<Item = (K, V)>) -> Self {
                    let mut m = Self::new();
                    for (k, v) in pairs {
                        m.insert(k, v);
                    }
                    m
                }
                pub fn from_iter_sorted(pairs: impl IntoIterator<Item = (K, V)>) -> Self {
                    let mut m = Self::new_sorted();
                    for (k, v) in pairs {
                        m.insert(k, v);
                    }
                    m
                }
                pub fn len(&self) -> usize {
                    self.entries.len()
                }
                pub fn is_empty(&self) -> bool {
                    self.entries.is_empty()
                }
                pub fn clear(&mut self) {
                    self.index.clear();
                    self.entries.clear();
                }
                pub fn contains_key<Q: std::hash::Hash + Eq + ?Sized>(&self, k: &Q) -> bool
                where
                    K: std::borrow::Borrow<Q>,
                {
                    self.index.contains_key(k)
                }
                pub fn get<Q: std::hash::Hash + Eq + ?Sized>(&self, k: &Q) -> Option<&V>
                where
                    K: std::borrow::Borrow<Q>,
                {
                    self.index.get(k).map(|&i| &self.entries[i].1)
                }
                fn reindex(&mut self) {
                    self.index.clear();
                    for (i, (k, _)) in self.entries.iter().enumerate() {
                        self.index.insert(k.clone(), i);
                    }
                }
                fn slot(&mut self, k: K, v: V) -> usize {
                    if self.sorted {
                        let at = self.entries.partition_point(|e| e.0 < k);
                        self.entries.insert(at, (k, v));
                        self.reindex();
                        at
                    } else {
                        self.index.insert(k.clone(), self.entries.len());
                        self.entries.push((k, v));
                        self.entries.len() - 1
                    }
                }
                pub fn insert(&mut self, k: K, v: V) -> Option<V> {
                    if let Some(&i) = self.index.get(&k) {
                        return Some(std::mem::replace(&mut self.entries[i].1, v));
                    }
                    self.slot(k, v);
                    None
                }
                pub fn remove<Q: std::hash::Hash + Eq + ?Sized>(&mut self, k: &Q) -> Option<V>
                where
                    K: std::borrow::Borrow<Q>,
                {
                    let i = *self.index.get(k)?;
                    let (_, v) = self.entries.remove(i);
                    self.reindex();
                    Some(v)
                }
                /// computeIfAbsent and putIfAbsent: the value, made first when the key is missing.
                pub fn or_insert_with(&mut self, k: K, f: impl FnOnce() -> V) -> &mut V {
                    let i = match self.index.get(&k) {
                        Some(&i) => i,
                        None => self.slot(k, f()),
                    };
                    &mut self.entries[i].1
                }
                pub fn iter(&self) -> impl Iterator<Item = (&K, &V)> {
                    self.entries.iter().map(|(k, v)| (k, v))
                }
                pub fn keys(&self) -> impl Iterator<Item = &K> {
                    self.entries.iter().map(|e| &e.0)
                }
                pub fn values(&self) -> impl Iterator<Item = &V> {
                    self.entries.iter().map(|e| &e.1)
                }
                pub fn clone_pairs(&self) -> Vec<(K, V)>
                where
                    V: Clone,
                {
                    self.entries.clone()
                }
            }

            /// Java's HashSet, LinkedHashSet and TreeSet, kept like JMap's keys.
            #[derive(Clone, Debug)]
            pub struct JSet<T>(JMap<T, ()>);
            impl<T: std::hash::Hash + Eq> PartialEq for JSet<T> {
                fn eq(&self, o: &Self) -> bool {
                    self.0 == o.0
                }
            }
            impl<T> Default for JSet<T> {
                fn default() -> Self {
                    Self(JMap::default())
                }
            }
            impl<T: std::hash::Hash + Ord + Clone> JSet<T> {
                pub fn new() -> Self {
                    Self::default()
                }
                pub fn new_sorted() -> Self {
                    Self(JMap::new_sorted())
                }
                pub fn from_iter(items: impl IntoIterator<Item = T>) -> Self {
                    let mut s = Self::new();
                    for v in items {
                        s.insert(v);
                    }
                    s
                }
                pub fn from_iter_sorted(items: impl IntoIterator<Item = T>) -> Self {
                    let mut s = Self::new_sorted();
                    for v in items {
                        s.insert(v);
                    }
                    s
                }
                pub fn len(&self) -> usize {
                    self.0.len()
                }
                pub fn is_empty(&self) -> bool {
                    self.0.is_empty()
                }
                pub fn clear(&mut self) {
                    self.0.clear();
                }
                /// True when it wasn't there yet, as Java's add.
                pub fn insert(&mut self, v: T) -> bool {
                    self.0.insert(v, ()).is_none()
                }
                pub fn contains<Q: std::hash::Hash + Eq + ?Sized>(&self, v: &Q) -> bool
                where
                    T: std::borrow::Borrow<Q>,
                {
                    self.0.contains_key(v)
                }
                pub fn remove<Q: std::hash::Hash + Eq + ?Sized>(&mut self, v: &Q) -> bool
                where
                    T: std::borrow::Borrow<Q>,
                {
                    self.0.remove(v).is_some()
                }
                pub fn iter(&self) -> impl Iterator<Item = &T> {
                    self.0.keys()
                }
                pub fn clone_pairs(&self) -> Vec<T> {
                    self.0.keys().cloned().collect()
                }
            }

            /// The mod's data folder in Horizon, set by the hooks (game_side.rs `ensure_init`) before the mod's code runs.
            pub static J_DATA_DIR: JCell<Option<std::path::PathBuf>> = JCell::new(None);
            /// FabricLoader's config folder: the mod's data folder in Horizon (./config until the game sets it).
            pub fn j_config_dir() -> std::path::PathBuf {
                J_DATA_DIR.get().unwrap_or_else(|| std::path::PathBuf::from("config"))
            }

            /// Minecraft's RandomSource / java.util.Random: other numbers than Java's, the same ranges.
            #[derive(Clone, Debug)]
            pub struct JRandom(u64);
            impl JRandom {
                pub fn new() -> Self {
                    let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0x9E37_79B9);
                    Self(t | 1)
                }
                fn next(&mut self) -> u64 {
                    let mut v = self.0;
                    v ^= v << 13;
                    v ^= v >> 7;
                    v ^= v << 17;
                    self.0 = v;
                    v
                }
                /// 0 inclusive to n exclusive; Java throws on n <= 0.
                pub fn next_int(&mut self, n: i32) -> i32 {
                    assert!(n > 0, "bound must be positive");
                    (self.next() % n as u64) as i32
                }
                pub fn next_int_between(&mut self, lo: i32, hi: i32) -> i32 {
                    lo + self.next_int(hi - lo)
                }
                pub fn next_float(&mut self) -> f32 {
                    (self.next() >> 40) as f32 / (1u64 << 24) as f32
                }
                pub fn next_double(&mut self) -> f64 {
                    (self.next() >> 11) as f64 / (1u64 << 53) as f64
                }
                pub fn next_boolean(&mut self) -> bool {
                    self.next() & 1 == 1
                }
                pub fn next_gaussian(&mut self) -> f64 {
                    let (u, v) = (self.next_double().max(1e-12), self.next_double());
                    (-2.0 * u.ln()).sqrt() * (std::f64::consts::TAU * v).cos()
                }
            }

            /// Minecraft's Identifier (`namespace:path`), which is only text.
            #[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
            pub struct JIdentifier {
                pub namespace: String,
                pub path: String,
            }
            impl JIdentifier {
                pub fn new(namespace: &str, path: &str) -> Self {
                    Self { namespace: namespace.to_string(), path: path.to_string() }
                }
                /// `a:b`, or `minecraft:b` without a namespace.
                pub fn parse(s: &str) -> Self {
                    match s.split_once(':') {
                        Some((n, p)) => Self::new(n, p),
                        None => Self::new("minecraft", s),
                    }
                }
                /// Identifier.tryParse: None (null) when it has characters an identifier can't.
                pub fn try_parse(s: &str) -> Option<Self> {
                    let id = Self::parse(s);
                    Self::try_build(&id.namespace, &id.path)
                }
                pub fn try_build(namespace: &str, path: &str) -> Option<Self> {
                    let ns_ok = namespace.chars().all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_' | '.' | '-'));
                    let path_ok = path.chars().all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_' | '.' | '-' | '/'));
                    if ns_ok && path_ok { Some(Self::new(namespace, path)) } else { None }
                }
            }
            impl std::fmt::Display for JIdentifier {
                fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                    write!(f, "{}:{}", self.namespace, self.path)
                }
            }

            /// Java's String.equals.
            pub fn j_eq(a: &str, b: &str) -> bool {
                a == b
            }
            pub fn j_eq_ignore_case(a: &str, b: &str) -> bool {
                a.to_lowercase() == b.to_lowercase()
            }
            /// Java's String.length (in chars).
            pub fn j_len(s: &str) -> i32 {
                s.chars().count() as i32
            }
            pub fn j_char_at(s: &str, i: i32) -> char {
                s.chars().nth(i as usize).expect("charAt out of range")
            }
            /// Java's substring, by char.
            pub fn j_substring(s: &str, from: i32, to: Option<i32>) -> String {
                let n = s.chars().count() as i32;
                let to = to.unwrap_or(n);
                assert!(0 <= from && from <= to && to <= n, "substring out of range");
                s.chars().skip(from as usize).take((to - from) as usize).collect()
            }
            pub fn j_index_of(s: &str, sub: &str) -> i32 {
                s.find(sub).map_or(-1, |b| s[..b].chars().count() as i32)
            }
            pub fn j_index_of_char(s: &str, c: char) -> i32 {
                s.chars().position(|x| x == c).map_or(-1, |i| i as i32)
            }
            pub fn j_last_index_of(s: &str, sub: &str) -> i32 {
                s.rfind(sub).map_or(-1, |b| s[..b].chars().count() as i32)
            }
            /// Java's String.split on plain text: trailing empty parts dropped.
            pub fn j_split(s: &str, sep: &str) -> Vec<String> {
                let mut v: Vec<String> = s.split(sep).map(str::to_string).collect();
                while v.len() > 1 && v.last().is_some_and(|p| p.is_empty()) {
                    v.pop();
                }
                v
            }
            /// Java's String.split(sep, limit) on plain text.
            pub fn j_split_limit(s: &str, sep: &str, limit: i32) -> Vec<String> {
                if limit == 0 {
                    return j_split(s, sep);
                }
                if limit > 0 {
                    return s.splitn(limit as usize, sep).map(str::to_string).collect();
                }
                s.split(sep).map(str::to_string).collect()
            }
            /// Java's String.compareTo: by UTF-16 unit, then by length.
            pub fn j_compare(a: &str, b: &str) -> i32 {
                let (x, y): (Vec<u16>, Vec<u16>) = (a.encode_utf16().collect(), b.encode_utf16().collect());
                for (p, q) in x.iter().zip(y.iter()) {
                    if p != q {
                        return *p as i32 - *q as i32;
                    }
                }
                x.len() as i32 - y.len() as i32
            }
            /// Java's String.regionMatches, by char.
            pub fn j_region_matches(s: &str, ignore_case: bool, from: i32, other: &str, other_from: i32, len: i32) -> bool {
                let (a, b): (Vec<char>, Vec<char>) = (s.chars().collect(), other.chars().collect());
                if from < 0 || other_from < 0 || from as i64 + len as i64 > a.len() as i64 || other_from as i64 + len as i64 > b.len() as i64 {
                    return false;
                }
                (0..len.max(0) as usize).all(|i| {
                    let (p, q) = (a[from as usize + i], b[other_from as usize + i]);
                    p == q || ignore_case && (j_upper_char(p) == j_upper_char(q) || j_lower_char(p) == j_lower_char(q))
                })
            }
            /// Java's Character.toLowerCase / toUpperCase (one char).
            pub fn j_lower_char(c: char) -> char {
                let mut l = c.to_lowercase();
                if l.len() == 1 { l.next().unwrap_or(c) } else { c }
            }
            pub fn j_upper_char(c: char) -> char {
                let mut u = c.to_uppercase();
                if u.len() == 1 { u.next().unwrap_or(c) } else { c }
            }
            /// Java's Arrays.copyOfRange: past the end is filled with defaults.
            pub fn j_copy_of_range<T: Clone + Default>(a: &[T], from: usize, to: usize) -> Vec<T> {
                assert!(from <= to && from <= a.len(), "copyOfRange out of range");
                let mut v: Vec<T> = a[from..to.min(a.len())].to_vec();
                v.resize(to - from, T::default());
                v
            }
            pub fn j_hash(s: &str) -> i32 {
                s.encode_utf16().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
            }
            /// Java's Boolean.parseBoolean.
            pub fn j_parse_bool(s: &str) -> bool {
                s.eq_ignore_ascii_case("true")
            }
            /// A float as Java prints it in "..." + x (1.0, not 1).
            pub fn j_float(x: f64) -> String {
                if x.is_finite() && x == x.trunc() && x.abs() < 1e7 { format!("{x:.1}") } else { format!("{x}") }
            }
            /// Java's Math.round.
            pub fn j_round_f(x: f32) -> i32 {
                (x + 0.5).floor() as i32
            }
            pub fn j_round_d(x: f64) -> i64 {
                (x + 0.5).floor() as i64
            }
            /// Java's Math.clamp / Mth.clamp (no panic when min > max).
            pub fn j_clamp<T: PartialOrd>(v: T, min: T, max: T) -> T {
                if v < min { min } else if v > max { max } else { v }
            }
            pub fn j_lerp<T: Copy + std::ops::Add<Output = T> + std::ops::Sub<Output = T> + std::ops::Mul<Output = T>>(d: T, a: T, b: T) -> T {
                a + d * (b - a)
            }
            pub fn j_signum_f(x: f32) -> f32 {
                if x > 0.0 { 1.0 } else if x < 0.0 { -1.0 } else { x }
            }
            pub fn j_signum_d(x: f64) -> f64 {
                if x > 0.0 { 1.0 } else if x < 0.0 { -1.0 } else { x }
            }
            pub fn j_wrap_degrees(x: f64) -> f64 {
                let mut v = x % 360.0;
                if v >= 180.0 {
                    v -= 360.0;
                }
                if v < -180.0 {
                    v += 360.0;
                }
                v
            }
            pub fn j_frac(x: f64) -> f64 {
                x - x.floor()
            }
            pub fn j_compare_f(a: f64, b: f64) -> i32 {
                a.partial_cmp(&b).map_or(0, |o| o as i32)
            }
            pub fn j_copy_of<T: Clone + Default>(a: &[T], n: usize) -> Vec<T> {
                let mut v: Vec<T> = a.iter().take(n).cloned().collect();
                v.resize(n, T::default());
                v
            }
            /// Java's System.currentTimeMillis.
            pub fn j_millis() -> i64 {
                std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
            }
            /// Java's System.nanoTime: only differences mean anything.
            pub fn j_nanos() -> i64 {
                static START: LazyLock<std::time::Instant> = LazyLock::new(std::time::Instant::now);
                START.elapsed().as_nanos() as i64
            }
            """;
}
