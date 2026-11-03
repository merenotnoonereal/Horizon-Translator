package horizonport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The real compile check (`compile`): horizon-mixins applies the mod to a copy of Horizon's source, as the launcher's
 * Build does, and `cargo check` compiles horizon-client with it. Unlike `check`, it sees everything the mod's code
 * touches, the game's own types included. Nothing is run: cargo check only compiles (Horizon's own build scripts
 * run, as in any build of Horizon).
 *
 * The work folder keeps cargo's target folder between runs (the first run compiles Horizon's dependencies: a few GB
 * and several minutes; later runs only Horizon and the mod). Errors in the mod's code are told by the mod file and
 * function they came from, since horizon-mixins reformats the code it pastes in.
 */
final class RealCompile {
    private static final String MARKER = ".horizon-port-stage";
    private static final Pattern SHORT = Pattern.compile("^(.+?):(\\d+):(\\d+): (error|warning)(\\[\\w+\\])?: (.*)$");

    private RealCompile() {}

    static Path defaultWork() {
        String env = System.getenv("HORIZON_PORT_WORK");
        if (env != null && !env.isBlank()) return Path.of(env);
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) return Path.of(local, "horizon-port", "compile");
        return Path.of(System.getProperty("user.home"), ".cache", "horizon-port", "compile");
    }

    static int run(Path dir, Path horizonSrc, Path work) throws IOException, InterruptedException {
        if (horizonSrc.getFileSystem() != FileSystems.getDefault()) {
            System.out.println("compile needs Horizon's source unpacked in a folder, not a .zip.");
            System.out.println("RESULT: not checked");
            return 2;
        }
        Path mixins = dir.resolve("mixins");
        List<Path> mods;
        try (Stream<Path> l = Files.list(mixins)) { mods = l.filter(p -> Files.isRegularFile(p.resolve("horizon-mixin.toml"))).toList(); }
        if (mods.size() != 1) throw new IllegalArgumentException("expected one mod under " + mixins + ", found " + mods.size());
        Path mod = mods.get(0).toAbsolutePath();
        String id = mod.getFileName().toString();
        Path tool = NativeCheck.mixinsTool(dir);
        if (tool == null) {
            System.out.println("horizon-mixins isn't installed: the launcher installs it on its first native build (or set HORIZON_MIXINS to the tool).");
            System.out.println("RESULT: not checked");
            return 2;
        }
        String cargo = NativeCheck.cargo();
        if (cargo == null) {
            System.out.println("Rust (cargo) isn't installed here.");
            System.out.println("RESULT: not checked");
            return 2;
        }

        work = work.toAbsolutePath();
        Path stage = work.resolve("stage"), target = work.resolve("target");
        Files.createDirectories(work);
        if (Files.exists(stage)) {
            // Only ever delete a folder this command made.
            if (!Files.isRegularFile(stage.resolve(MARKER))) {
                throw new IllegalArgumentException(stage + " exists and wasn't made by `compile`: pick another --work folder");
            }
            delete(stage);
        }
        System.out.println("Applying " + id + " to " + horizonSrc + " with " + tool.getFileName() + "...");
        Process apply = new ProcessBuilder(tool.toString(), "apply", "--workspace", horizonSrc.toAbsolutePath().toString(),
                "--mods", mixins.toAbsolutePath().toString(), "--out", stage.toString()).redirectErrorStream(true).start();
        String applied = new String(apply.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (apply.waitFor() != 0) {
            System.out.println("horizon-mixins can't apply the mod:\n" + applied.strip());
            if (applied.contains("E_REVISION")) {
                System.out.println("(The mod pins a Horizon commit: the source folder needs it in a `.horizon-revision` file, or a git checkout.)");
            }
            System.out.println("RESULT: failed (the hooks don't apply)");
            return 1;
        }
        Files.writeString(stage.resolve(MARKER), "made by horizon-port compile; deleted on its next run\n");

        boolean first = !Files.isDirectory(target);
        System.out.println(first ? "Compiling Horizon with the mod (first run: Horizon's dependencies too, a few GB in " + target + ", several minutes)..."
                : "Compiling Horizon with the mod...");
        ProcessBuilder pb = new ProcessBuilder(cargo, "check", "-p", "horizon-client", "--bin", "horizon-client", "--message-format", "short")
                .directory(stage.toFile()).redirectErrorStream(true);
        pb.environment().put("CARGO_TARGET_DIR", target.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        Locator where = new Locator(stage, mod, id);
        int errors = 0, warnings = 0;
        List<String> shown = new ArrayList<>();
        for (String line : out.split("\\R")) {
            Matcher m = SHORT.matcher(line);
            if (!m.matches()) {
                if (line.startsWith("error:") && !line.startsWith("error: could not compile")) { shown.add(line); errors++; }
                continue;
            }
            String place = where.of(m.group(1), Integer.parseInt(m.group(2)));
            boolean error = m.group(4).equals("error");
            if (error) errors++;
            else if (place == null) continue; // Horizon's own warnings
            else warnings++;
            String src = where.line(m.group(1), Integer.parseInt(m.group(2)));
            shown.add(m.group(4) + (m.group(5) == null ? "" : m.group(5)) + ": " + m.group(6) + "\n  in "
                    + (place != null ? place : "Horizon's " + rel(stage, m.group(1)) + ":" + m.group(2))
                    + (src == null ? "" : "\n  | " + src.strip()));
        }
        for (String s : shown) System.out.println(s);
        if (code != 0 && errors == 0) {
            // Not a compile error (a missing dependency, no network...): show the end of cargo's output.
            String[] all = out.strip().split("\\R");
            for (int i = Math.max(0, all.length - 15); i < all.length; i++) System.out.println(all[i]);
        }
        if (code == 0) {
            System.out.println("RESULT: ok (Horizon compiles with the mod" + (warnings > 0 ? "; " + warnings + " warnings in the mod" : "") + ")");
            return 0;
        }
        System.out.println("RESULT: failed (" + (errors == 1 ? "1 error" : errors > 0 ? errors + " errors" : "cargo check failed") + ")");
        System.out.println("The staged source is in " + stage + " (kept until the next run).");
        return 1;
    }

    private static String rel(Path stage, String file) {
        Path f = Path.of(file);
        return (f.isAbsolute() ? stage.relativize(f) : f).toString().replace('\\', '/');
    }

    /** Which of the mod's files a line of the staged source came from. */
    private static final class Locator {
        private final Path stage;
        private final String id;
        private final Map<String, String> helperFiles = new LinkedHashMap<>(); // fn name -> mod file
        private final Map<String, List<String>> lines = new java.util.HashMap<>();

        Locator(Path stage, Path mod, String id) throws IOException {
            this.stage = stage;
            this.id = id;
            try (Stream<Path> s = Files.list(mod)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".rs")).toList()) {
                    Matcher m = Pattern.compile("(?m)^fn (\\w+)").matcher(Text.read(f));
                    if (m.find()) helperFiles.put(m.group(1), "mixins/" + id + "/" + f.getFileName());
                }
            }
        }

        private List<String> lines(String file) {
            return lines.computeIfAbsent(file, f -> {
                try {
                    Path p = Path.of(f);
                    return Files.readAllLines(p.isAbsolute() ? p : stage.resolve(p), StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    return List.of();
                }
            });
        }

        String line(String file, int n) {
            List<String> l = lines(file);
            return n >= 1 && n <= l.size() ? l.get(n - 1) : null;
        }

        /** "mixins/<id>/runtime.rs (staged game.rs:123)", or null when the line is Horizon's own. */
        String of(String file, int n) {
            List<String> l = lines(file);
            if (n < 1 || n > l.size()) return null;
            String name = rel(stage, file);
            // The runtime module: `pub(crate) mod kit_<id> {` ... the `}` at the same indent.
            Pattern open = Pattern.compile("^(\\s*)(pub(\\([a-z]+\\))? )?mod (kit_" + Pattern.quote(id) + "|" + Pattern.quote(id) + ")\\b.*\\{\\s*$");
            Pattern helper = Pattern.compile("^(\\s*)fn (\\w+)\\s*\\(");
            for (int i = n - 1; i >= 0; i--) {
                String s = l.get(i);
                Matcher o = open.matcher(s);
                if (o.matches()) {
                    return end(l, i, o.group(1)) >= n - 1 ? "mixins/" + id + "/runtime.rs (staged " + name + ":" + n + ")" : null;
                }
                Matcher h = helper.matcher(s);
                if (h.find() && helperFiles.containsKey(h.group(2)) && end(l, i, h.group(1)) >= n - 1) {
                    return helperFiles.get(h.group(2)) + ", fn " + h.group(2) + " (staged " + name + ":" + n + ")";
                }
            }
            return null;
        }

        /** The line of the `}` closing a block that opened at line `from` with this indent. */
        private static int end(List<String> l, int from, String indent) {
            for (int i = from + 1; i < l.size(); i++) if (l.get(i).equals(indent + "}")) return i;
            return l.size();
        }
    }

    private static void delete(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                x.toFile().setWritable(true);
                Files.delete(x);
            }
        }
    }
}
