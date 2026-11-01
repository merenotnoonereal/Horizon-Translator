import java.lang.reflect.Method;

// Checks RustTranslator on small Java classes: what it turns into Rust, and what it leaves out and why.
public class TranslatorTest {
    static int bad = 0, total = 0;

    public static void main(String[] a) throws Exception {
        Class<?> t = Class.forName("horizonport.RustTranslator");
        Method m = t.getDeclaredMethod("translateText", String.class);
        m.setAccessible(true);

        String maths = "package p; public final class Maths {\n"
                + "  public static final int MAX = 200; static final String KEY = \"size=\";\n"
                + "  private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }\n"
                + "  static float scale(int size) { return size / 100.0F; }\n"
                + "  static int swing(int base, int s) { return s == 100 ? base : Math.max(1, Math.round(base * 100.0F / s)); }\n"
                + "  static int sum(int[] xs) { int t = 0; for (int x : xs) { t += x; } return t; }\n"
                + "  static void fill(float[] out, float v) { for (int i = 0; i < out.length; i++) { out[i] = v; } }\n"
                + "  static int parse(String s, int fallback) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; } }\n"
                + "  static String label(String name, int n, float f) { return name + \": \" + n + \" {\" + f + \"}\"; }\n"
                + "  static boolean on(String line) { return line.startsWith(\"on=\") && Boolean.parseBoolean(line.substring(KEY.length()).trim()); }\n"
                + "  static int kind(int k) { switch (k) { case 1: case 2: return 10; case 3: return 20; default: return 0; } }\n"
                + "  static int down(int n) { int c = 0; for (int i = n; i >= 0; i--) c++; return c; }\n"
                + "}\n";
        String r = run(m, maths);
        has(r, "pub const MAX: i32 = 200;", "int constant");
        has(r, "pub const KEY: &str = \"size=\";", "String constant");
        has(r, "min.max(max.min(v))", "Math.max/min");
        has(r, "pub fn scale(size: i32) -> f32 {\n", "float return");
        has(r, "(size as f32) / 100.0f32", "int to float promotion");
        has(r, "j_round_f(", "Math.round");
        has(r, "(1i32).max(", "typed literal receiver");
        has(r, "for &x in xs.iter() {", "for-each over an array");
        has(r, "pub fn fill(out: &mut [f32], v: f32)", "array written through becomes &mut");
        has(r, "for i in 0..(out.len() as i32) {", "counting for loop becomes a range");
        has(r, "match s.trim().parse::<i32>() {", "parseInt in try/catch");
        has(r, "format!(\"{}: {} {{{}}}\", name, n, j_float(", "string concatenation");
        has(r, "j_parse_bool(", "Boolean.parseBoolean");
        has(r, "1 | 2 => {", "switch case group");
        has(r, "for i in (0..=n).rev() {", "counting down");

        String data = "package p; public final class Clip {\n"
                + "  private final float[][] keys;\n"
                + "  private Clip(float[][] keys) { this.keys = keys; }\n"
                + "  public static Clip of(float[]... keys) { return new Clip(keys); }\n"
                + "  public float first(int i) { float[] k = this.keys[i]; float[] j = i > 0 ? this.keys[i - 1] : k; return k[0] + j[0]; }\n"
                + "  public void bump() { this.keys[0][0] += 1.0F; }\n"
                + "  static final Clip STILL = Clip.of(new float[]{0.0F}, new float[]{1.0F});\n"
                + "  static int count; static void tick() { count++; }\n"
                + "}\n";
        r = run(m, data);
        has(r, "pub struct Clip {\n        pub keys: Vec<Vec<f32>>,", "struct from fields");
        has(r, "pub fn new(keys: &[Vec<f32>]) -> Self", "constructor");
        has(r, "let k = &self.keys[i as usize];", "array alias is a borrow");
        has(r, "if i > 0 { &self.keys[(i - 1) as usize] } else { k }", "?: between borrows");
        has(r, "pub fn bump(&mut self)", "a method that writes is &mut self");
        has(r, "LazyLock::new(|| clip::of(&[vec![0.0f32], vec![1.0f32]]))", "static object with varargs");
        has(r, "pub static COUNT: JCell<i32> = JCell::new(0i32);", "changing static");
        has(r, "clip::COUNT.set(clip::COUNT.get() + 1);", "static ++");

        String cfg = "package p; public final class Cfg {\n"
                + "  public static Cfg INSTANCE = new Cfg();\n"
                + "  public boolean enabled = true; public boolean showArm = false; public int sway = 100;\n"
                + "  public enum Mode { OFF, ON }\n"
                + "  static boolean isOn() { return INSTANCE.enabled; }\n"
                + "  static void off() { INSTANCE.enabled = false; }\n"
                + "}\n";
        r = run(m, cfg);
        has(r, "pub static INSTANCE: LazyLock<JCell<cfg::Cfg>>", "config instance");
        has(r, "cfg::INSTANCE.with(|o| o.enabled)", "read through the instance");
        has(r, "cfg::INSTANCE.with(|o| o.enabled = value_", "write through the instance");
        has(r, "(\"showArm\", \"Show arm\"),", "toggle label");
        has(r, "pub fn load_settings(&mut self, get: impl Fn(&str) -> Option<String>)", "settings loading");
        has(r, "pub mod cfg_mode {", "nested enum");

        String sw = "package p; public final class Sw {\n"
                + "  enum Kind { BLADE, WAND, TOOL }\n"
                + "  static int speed(Kind k) { return switch (k) { case BLADE, WAND -> 2; case TOOL -> { int s = 3; yield s * 2; } }; }\n"
                + "  static String name(int n) { String r = switch (n) { case 1 -> \"one\"; case 2 -> \"two\"; default -> \"many\"; }; return r; }\n"
                + "  static float f(Kind k) { return switch (k) { case BLADE -> 1; default -> 0.5F; }; }\n"
                + "}\n";
        r = run(m, sw);
        has(r, "pub fn speed(k: sw_kind::Kind) -> i32 {", "switch expression method translated");
        has(r, "sw_kind::Kind::Blade | sw_kind::Kind::Wand => 2,", "switch expression arms");
        has(r, "_ => unreachable!(\"no case matched\"),", "exhaustive enum switch");
        has(r, "_ => \"many\".to_string(),", "switch expression default");
        has(r, "sw_kind::Kind::Blade => 1.0f32,", "arm widened to the switch's type");

        String inc = "package p; public final class Inc {\n"
                + "  static int bump(int n, boolean idle) { if (++n >= 56 || !idle) { n = 0; } return n; }\n"
                + "}\n";
        r = run(m, inc);
        has(r, "n += 1;\n", "prefix ++ in a condition done before the if");
        has(r, "if (n >= 56) || (!idle) {", "the condition reads the bumped value");

        String st = "package p; import java.util.*; import java.util.stream.*;\n"
                + "public final class St {\n"
                + "  static List<String> lower(List<String> in) { return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toList()); }\n"
                + "  static String[] arr(List<String> in) { return in.stream().map(String::trim).toArray(String[]::new); }\n"
                + "  static boolean any(List<Integer> xs) { return xs.stream().anyMatch(v -> v > 3); }\n"
                + "  static int total(List<String> in) { return in.stream().mapToInt(String::length).sum(); }\n"
                + "  static String joined(Set<String> in) { return in.stream().sorted().collect(Collectors.joining(\", \")); }\n"
                + "  static void sortByLen(List<String> in) { in.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())); }\n"
                + "  static void drop(List<String> in) { in.removeIf(s -> s.startsWith(\"#\")); }\n"
                + "  static int cmp(String a, String b) { return a.compareTo(b); }\n"
                + "  static String[] head(String s) { return s.split(\":\", 2); }\n"
                + "  static String both(List<String> in) { return String.join(\"/\", in); }\n"
                + "  static String signed(double v) { return String.format(\"%+.2f\", v); }\n"
                + "  static boolean near(String a, String b) { return a.regionMatches(true, 0, b, 0, 2); }\n"
                + "  static long count(List<String> in) { return in.stream().distinct().count(); }\n"
                + "}\n";
        r = run(m, st);
        has(r, ".map(|s: String| s.to_lowercase()).filter(|s: &String| !s.is_empty()).collect::<Vec<_>>()", "stream map/filter/collect");
        has(r, "pub fn arr(", "toArray with a constructor reference");
        has(r, ".any(|v: i32| v > 3)", "anyMatch");
        has(r, ".sum::<i32>()", "mapToInt sum");
        has(r, ".join(\", \")", "joining");
        has(r, ".sort_by(", "List.sort with a comparator");
        has(r, ".retain(", "removeIf");
        has(r, "j_compare(", "String.compareTo");
        has(r, "j_split_limit(", "split with a limit");
        has(r, "format!(\"{:+.2}\"", "signed format");
        has(r, "j_region_matches(", "regionMatches");

        String rx = "package p; import java.util.regex.*;\n"
                + "public final class Rx {\n"
                + "  private static final Pattern ID = Pattern.compile(\"server/[0-9a-f]{8}/(.+)\");\n"
                + "  static String key(String id) { Matcher m = ID.matcher(id); return m.matches() ? \"server/\" + m.group(1) : id; }\n"
                + "  static int count(String s) { Matcher m = ID.matcher(s); int n = 0; while (m.find()) n++; return n; }\n"
                + "  static String[] words(String s) { return s.trim().split(\"\\\\s+\"); }\n"
                + "  static String[] cols(String s) { return s.split(\"\\t\", 4); }\n"
                + "  static String clean(String s) { return s.replaceAll(\"[^a-z]\", \"_\"); }\n"
                + "  static boolean num(String s) { return s.matches(\"-?\\\\d+\"); }\n"
                + "}\n";
        r = run(m, rx);
        has(r, "LazyLock::new(|| JRegex::new(\"server/[0-9a-f]{8}/(.+)\"))", "a Pattern constant");
        has(r, "let mut m = JMatcher::new(", "a Matcher local is mutable");
        has(r, "m.group(1)", "Matcher.group");
        has(r, ".split(&s.trim(), 0)", "split on a regex");
        has(r, "JRegex::new(\"\\t\")); &*RE }.split(&s, 4)", "split on a tab with a limit");
        has(r, ".replace_all(&s, \"_\")", "replaceAll");
        has(r, "pub struct JRegex", "the regex engine is included");

        String js = "package p; import com.google.gson.*; import java.util.*;\n"
                + "public final class Js {\n"
                + "  public static final class Cfg { public boolean on = true; public float scale = 1.0F; public int[] keys = new int[]{1, 2}; public String name = \"x\"; }\n"
                + "  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();\n"
                + "  static String save(Cfg c) { return GSON.toJson(c); }\n"
                + "  static Cfg load(String s) { JsonObject json = JsonParser.parseString(s).getAsJsonObject(); Cfg c = GSON.fromJson(json, Cfg.class); return c == null ? new Cfg() : c; }\n"
                + "  static List<String> strings(JsonElement e) { List<String> out = new ArrayList<>(); if (e == null) return out;\n"
                + "    if (e.isJsonArray()) { for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString()); } else out.add(e.getAsString()); return out; }\n"
                + "  static float first(JsonObject o) { JsonArray a = o.getAsJsonArray(\"v\"); return a != null && a.size() > 0 ? a.get(0).getAsFloat() : 0F; }\n"
                + "  static String make() { JsonObject o = new JsonObject(); o.addProperty(\"n\", 3); o.addProperty(\"s\", \"t\"); return o.toString(); }\n"
                + "}\n";
        r = run(m, js);
        has(r, "pub fn to_jjson(&self) -> JJson {", "Gson writing of a class");
        has(r, "pub fn from_jjson(j: &JJson) -> Option<Self> {", "Gson reading of a class");
        has(r, ".to_json(&c.to_jjson())", "Gson.toJson");
        has(r, "from_jjson(&json)", "Gson.fromJson from a JsonObject");
        has(r, "for x in ((e.as_ref().expect(\"null\")).as_array()).elements() {", "a for-each over a JsonArray");
        has(r, ".member_array(\"v\")", "getAsJsonArray(name)");
        has(r, "o.add(\"n\", JJson::int(3 as i64));", "addProperty");
        has(r, "pub enum JJson", "the JSON code is included");

        String sl = "package p; import java.util.function.*;\n"
                + "public final class Sl {\n"
                + "  public static final class Opts { public static Opts INSTANCE = new Opts(); public boolean shown = true; public float size = 0.5F; public int count = 3; }\n"
                + "  static void build() { Opts c = Opts.INSTANCE; slider(\"size\", 0.0, 1.0, 0.05, () -> c.size, v -> c.size = (float) v); toggle(\"shown\", c.shown); }\n"
                + "  static void slider(String key, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) { }\n"
                + "  static void toggle(String key, boolean on) { }\n"
                + "}\n";
        r = run(m, sl);
        has(r, "(\"size\", \"Size\", 0.0, 1.0, 0.05),", "a slider in the mod's screen becomes a number option with its range and step");
        has(r, "pub fn nudge(&mut self, key: &str, steps: i32) {", "number options move a step at a time");
        has(r, "\"size\" => self.size = v as f32,", "a float option set from the step grid");

        String fnv = "package p; import java.util.*; import java.util.function.*;\n"
                + "public final class Fv {\n"
                + "  static int count(List<String> in, Predicate<String> keep) { int n = 0; for (String s : in) if (keep.test(s)) n++; return n; }\n"
                + "  static int longOnes(List<String> in) { return count(in, s -> s.length() > 3); }\n"
                + "  static int twice(IntSupplier f) { return f.getAsInt() * 2; }\n"
                + "  static int four() { return twice(() -> 2); }\n"
                + "  static void each(List<String> in, BiConsumer<String, Integer> out) { for (int i = 0; i < in.size(); i++) out.accept(in.get(i), i); }\n"
                + "}\n";
        r = run(m, fnv);
        has(r, "keep: &dyn Fn(&String) -> bool", "a Predicate parameter");
        has(r, "(keep)(&s.to_string())", "Predicate.test");
        has(r, "fv::count(r#in, &(|s: &String| j_len(s) > 3))", "a lambda passed as a Predicate");
        has(r, "pub fn four() -> i32 {", "a lambda passed as an IntSupplier");
        has(r, "out: &dyn Fn(&String, &i32)", "a BiConsumer parameter");

        String coll = "package p; import java.util.*;\n"
                + "public final class Coll {\n"
                + "  private static final Map<String, Integer> COUNTS = new HashMap<>();\n"
                + "  private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();\n"
                + "  private static final Set<String> SEEN = new HashSet<>();\n"
                + "  private static String last = null;\n"
                + "  static void count(String w) { COUNTS.merge(w, 1, Integer::sum); }\n"
                + "  static int countOf(String w) { return COUNTS.getOrDefault(w, 0); }\n"
                + "  static boolean firstTime(String w) { return SEEN.add(w); }\n"
                + "  static void remember(String w) { last = w; }\n"
                + "  static String lastOr(String d) { return last == null ? d : last; }\n"
                + "  static int total(Map<String, Integer> m) { int t = 0; for (Map.Entry<String, Integer> e : m.entrySet()) { t += e.getValue(); } return t; }\n"
                + "  static int longKeys(Map<String, Integer> m) { int n = 0; for (String k : m.keySet()) { if (k.length() > 3) n++; } return n; }\n"
                + "  static Map<String, List<String>> group(List<String> words) {\n"
                + "    Map<String, List<String>> out = new TreeMap<>();\n"
                + "    for (String w : words) { out.computeIfAbsent(w.substring(0, 1), k -> new ArrayList<>()).add(w); }\n"
                + "    return out;\n"
                + "  }\n"
                + "  static String find(Map<String, String> m, String k) { String v = m.get(k); if (v == null) return \"?\"; return v; }\n"
                + "  static Optional<String> pick(List<String> xs) { if (xs.isEmpty()) return Optional.empty(); return Optional.of(xs.get(0)); }\n"
                + "  static String pickOr(List<String> xs) { return pick(xs).orElse(\"none\"); }\n"
                + "  static int sizeOf(Set<Integer> s) { Set<Integer> c = new HashSet<>(s); c.add(7); return c.size(); }\n"
                + "}\n";
        r = run(m, coll);
        has(r, "pub static COUNTS: LazyLock<JCell<JMap<String, i32>>>", "a static map that changes");
        has(r, "pub fn count(w: &str) {", "Map.merge translated");
        has(r, "pub fn count_of(w: &str) -> i32 {", "Map.getOrDefault translated");
        has(r, "pub fn first_time(w: &str) -> bool {", "Set.add translated");
        has(r, "pub static LAST: LazyLock<JCell<Option<String>>> = LazyLock::new(|| JCell::new(None));", "a static set to null is an Option");
        has(r, "pub fn last_or(d: &str) -> String {", "null check on a nullable static");
        has(r, "for e in m.iter() {", "for-each over entrySet");
        has(r, "for k in m.keys() {", "for-each over keySet");
        has(r, "pub fn group(words: &[String]) -> JMap<String, Vec<String>> {", "TreeMap return");
        has(r, ".or_insert_with(", "computeIfAbsent");
        has(r, "JMap::new_sorted()", "a TreeMap keeps keys sorted");
        has(r, "let v: Option<String> = m.get(&*k).cloned();", "Map.get is an Option");
        has(r, "pub fn pick(xs: &[String]) -> Option<String> {", "Optional return");
        has(r, ".unwrap_or(\"none\".to_string())", "Optional.orElse");
        has(r, "JSet::from_iter(", "new HashSet<>(set)");

        // Decompiled Iterator loops, and a try that sets a local then breaks out of the loop.
        String iter = "package p; import java.io.IOException; import java.nio.file.*; import java.util.*;\n"
                + "public class Walk {\n"
                + "  static int total(List<String> xs) { int n = 0; Iterator it = xs.iterator(); while (it.hasNext()) { String s = (String)it.next(); n += s.length(); } return n; }\n"
                + "  static String firstReadable(List<String> names) {\n"
                + "    Iterator var1 = names.iterator();\n"
                + "    String text;\n"
                + "    while (true) {\n"
                + "      if (!var1.hasNext()) return null;\n"
                + "      String n = (String)var1.next();\n"
                + "      try { text = Files.readString(Path.of(n)); break; } catch (IOException e) { }\n"
                + "    }\n"
                + "    return text;\n"
                + "  }\n"
                + "}\n";
        r = run(m, iter);
        has(r, "let mut it: JIter<String> = JIter::new(xs.iter().cloned().collect::<Vec<_>>());", "Iterator from a list");
        has(r, "while it.has_next() {", "Iterator.hasNext");
        has(r, "let s: String = it.next();", "Iterator.next with its cast");
        has(r, "pub fn first_readable(names: &[String]) -> Option<String> {", "Iterator loop translated");
        has(r, "Ok(hp_ok) => {", "a try that sets a local and then breaks");

        String files = "package p; import java.io.IOException; import java.nio.file.*; import java.util.List;\n"
                + "import net.fabricmc.loader.api.FabricLoader;\n"
                + "public final class Cfg2 {\n"
                + "  private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(\"x.txt\");\n"
                + "  private static Boolean cached;\n"
                + "  public static boolean isOn() { Boolean v = cached; if (v == null) { v = read(); cached = v; } return v; }\n"
                + "  private static boolean read() {\n"
                + "    try { if (Files.exists(FILE)) { return parse(Files.readAllLines(FILE)); } } catch (IOException e) { LOG.error(\"no\", e); }\n"
                + "    return true;\n"
                + "  }\n"
                + "  static boolean parse(List<String> lines) { for (String l : lines) { if (l.startsWith(\"on=\")) return Boolean.parseBoolean(l.substring(3)); } return true; }\n"
                + "  public static void set(boolean on) {\n"
                + "    cached = on;\n"
                + "    try { write(FILE, \"on=\" + on + System.lineSeparator()); } catch (IOException e) { }\n"
                + "  }\n"
                + "  static void write(Path file, String text) throws IOException {\n"
                + "    Files.createDirectories(file.getParent());\n"
                + "    Path tmp = file.resolveSibling(file.getFileName() + \".tmp\");\n"
                + "    Files.writeString(tmp, text);\n"
                + "    try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }\n"
                + "    catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }\n"
                + "  }\n"
                + "  static final org.slf4j.Logger LOG = null;\n"
                + "}\n";
        r = run(m, files);
        has(r, "LazyLock::new(|| j_config_dir().join(\"x.txt\"))", "FabricLoader's config dir");
        has(r, "pub fn is_on() -> bool {", "nullable Boolean cache");
        has(r, "-> std::io::Result<Option<bool>> {", "try/catch IOException as a closure");
        has(r, "std::fs::read_to_string(", "Files.readAllLines");
        has(r, "Ok(Some(v)) => return v,", "return from inside the try");
        has(r, "pub fn write(file: &std::path::Path, text: &str) -> std::io::Result<()> {", "throws IOException");
        has(r, "std::fs::rename(", "Files.move");
        has(r, "cfg2::write(&(*cfg2::FILE), ", "calling a throwing method in a try");

        String joml = "package p; import com.mojang.blaze3d.vertex.PoseStack; import com.mojang.math.Axis;\n"
                + "import org.joml.Matrix4f; import org.joml.Vector3f; import org.joml.Quaternionf;\n"
                + "public final class Pz {\n"
                + "  private static final Vector3f GRIP = new Vector3f(0.6875F, 0.3125F, 0.5F);\n"
                + "  private static final Matrix4f base = new Matrix4f();\n"
                + "  private static final Vector3f target = new Vector3f();\n"
                + "  static void arm(PoseStack pose, float a, int invert) {\n"
                + "    pose.translate(invert * 0.1F, -0.2F, 0.0F);\n"
                + "    pose.mulPose(Axis.YP.rotationDegrees(invert * a));\n"
                + "    pose.rotateAround(Axis.XP.rotationDegrees(a), 0.0F, 0.5F, 0.0F);\n"
                + "    pose.rotateAround(new Quaternionf(-0.7027F, invert * 0.3505F, 0.0F, 0.5028F).normalize(), 0.0F, 0.15625F, 0.0F);\n"
                + "    base.set(pose.last().pose());\n"
                + "  }\n"
                + "  static Matrix4f display(int invert) { return new Matrix4f().translate(invert / 16.0F, 0.125F, -0.625F).rotateX((float) Math.toRadians(-90.0)).scale(0.9F); }\n"
                + "  static void aim(PoseStack pose, Matrix4f display, Vector3f rest, float t) {\n"
                + "    Vector3f nock = new Vector3f(rest).lerp(GRIP, t);\n"
                + "    new Matrix4f(base).invert().mul(pose.last().pose()).mul(display).transformPosition(nock, target);\n"
                + "  }\n"
                + "  static float len(Vector3f v) { v.add(1.0F, 0.0F, 0.0F).mul(2.0F); return v.length(); }\n"
                + "  static float run() { PoseStack p = new PoseStack(); p.pushPose(); arm(p, 30.0F, 1); aim(p, display(1), GRIP, 0.5F); p.popPose(); return len(target); }\n"
                + "}\n";
        r = run(m, joml);
        has(r, "pub fn arm(pose: &mut JPoseStack, a: f32, invert: i32) {", "a PoseStack parameter");
        has(r, "pose.mul_pose(JQuat::rotation_y(", "Axis.YP.rotationDegrees");
        has(r, "pose.rotate_around(JQuat::rotation_x(", "PoseStack.rotateAround");
        has(r, "pub static BASE: JCell<JMat4> = JCell::new(JMat4::IDENTITY);", "a matrix static that changes");
        has(r, "JMat4::IDENTITY.translate(", "a fresh Matrix4f chain");
        has(r, ".transform_position(nock)", "transformPosition");
        has(r, "pub fn len(mut v: JVec3) -> f32 {", "a vector changed in place");
        has(r, "pub struct JPoseStack", "the maths come with it");

        String bad1 ="package p; import net.minecraft.world.item.ItemStack; public final class B {\n"
                + "  static boolean empty(ItemStack s) { return s.isEmpty(); }\n"
                + "  static boolean caller(ItemStack s) { return empty(s); }\n"
                + "  static String n() { return null; }\n"
                + "  static Runnable r() { return () -> {}; }\n"
                + "  static int ok() { return 1; }\n"
                + "}\n";
        r = run(m, bad1);
        has(r, "B.empty(1): needs the game, so it's in game_side.rs.", "ItemStack code goes to the game side");
        has(r, "pub fn n() -> Option<String> {", "returning null gives an Option");
        has(r, "B.r(0): not translated", "lambda left out");
        has(r, "pub fn ok() -> i32 {", "the rest still translates");

        String hud = "package p; import net.minecraft.client.Minecraft; import net.minecraft.client.KeyMapping;\n"
                + "import net.minecraft.client.gui.GuiGraphics; import net.minecraft.client.player.LocalPlayer; import net.minecraft.network.chat.Component;\n"
                + "public final class Hud {\n"
                + "  static KeyMapping toggle; static boolean shown = true;\n"
                + "  public static void init() {\n"
                + "    toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping(\"key.test.toggle\", 72, \"cat\"));\n"
                + "    ClientTickEvents.END_CLIENT_TICK.register(client -> {\n"
                + "      while (toggle.consumeClick()) { shown = !shown; client.player.displayClientMessage(Component.literal(\"HUD \" + (shown ? \"on\" : \"off\")), false); }\n"
                + "      if (client.player != null) client.player.resetFallDistance();\n"
                + "    });\n"
                + "    HudRenderCallback.EVENT.register((g, delta) -> draw(g));\n"
                + "  }\n"
                + "  static void draw(GuiGraphics g) {\n"
                + "    Minecraft mc = Minecraft.getInstance(); LocalPlayer p = mc.player;\n"
                + "    if (!shown || p == null) return;\n"
                + "    String line = String.format(\"XYZ %.1f %.1f %.1f\", p.getX(), p.getY(), p.getZ());\n"
                + "    int w = mc.font.width(line);\n"
                + "    g.fill(2, 2, 6 + w, 14, 0x78000000);\n"
                + "    g.drawString(mc.font, line, 4, 4, 0xFFFFFF);\n"
                + "    if (mc.options.keyShift.isDown()) g.drawString(mc.font, \"Sneaking\", 4, 16, 0xFFFF00);\n"
                + "  }\n"
                + "}\n";
        r = game(m, hud);
        has(r, "pub fn hud_draw(game: &mut crate::game::Game<'_>, batch: &mut horizon_client::hud::HudBatch) {", "GuiGraphics method takes game and batch");
        has(r, "let p: Option<crate::game::native_hooks::NativePlayer> = game.native_player();", "mc.player");
        has(r, "p.as_ref().expect(\"no player\").x", "player.getX()");
        has(r, "game.native_text_width(&hp_text) as i32", "font.width");
        has(r, "game.native_fill(batch, [hp_x1, hp_y1, hp_x2 - hp_x1, hp_y2 - hp_y1], hp_argb)", "fill corners to x, y, w, h");
        has(r, "game.native_text(batch, &hp_text, hp_at, hp_rgb, true)", "drawString");
        has(r, "game.native_mapping(\"key.sneak\").is_some_and(|(held, _)| held)", "vanilla key isDown");
        has(r, "while key_consume(\"toggle\") {", "mod key consumeClick");
        has(r, "game.native_chat(&hp_text)", "displayClientMessage");
        has(r, "// TODO: left out the call to `player.resetFallDistance(..)`", "unmapped statement left as a TODO, its `if` kept");
        has(r, "hud_draw(game, batch);", "handler passes batch on");
        has(r, "pub fn key_event(game: &mut crate::game::Game<'_>, key: &str, pressed: bool) {", "key glue");

        System.out.println(bad == 0 ? "  ok: all " + total + " translation checks right" : "  FAIL: " + bad + " of " + total + " translation checks wrong");
        if (bad > 0) System.exit(1);
    }

    static String run(Method m, String src) throws Exception {
        Object result = m.invoke(null, src);
        Method rust = result.getClass().getDeclaredMethod("rust");
        rust.setAccessible(true);
        String out = (String) rust.invoke(result);
        // With a folder given, keep each output so the test script can compile it with rustc.
        String dir = System.getProperty("rust.out");
        if (dir != null) {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "case" + (++written) + ".rs"), out);
        }
        return out;
    }

    static String game(Method m, String src) throws Exception {
        Object result = m.invoke(null, src);
        Method game = result.getClass().getDeclaredMethod("game");
        game.setAccessible(true);
        return (String) game.invoke(result);
    }

    static int written = 0;

    static void has(String out, String want, String what) {
        total++;
        if (!out.contains(want)) {
            bad++;
            System.out.println("  WRONG " + what + ": missing\n    " + want.replace("\n", "\n    "));
        }
    }
}
