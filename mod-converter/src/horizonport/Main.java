package horizonport;

import horizonport.Analyzer.ModAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** horizon-port: works out how a Fabric mod's code carries over to Horizon. */
public final class Main {
    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            usage();
            return args.length == 0 ? 2 : 0;
        }
        String cmd = args[0];
        List<String> positional = new ArrayList<>();
        List<Path> mappingFiles = new ArrayList<>();
        Path out = null, horizonSrc = null, javaSrc = null, vineflower = null;
        String revision = null, author = null;
        int minScore = 6;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--mappings" -> mappingFiles.add(Path.of(value(args, ++i)));
                case "--out" -> out = Path.of(value(args, ++i));
                case "--min-score" -> minScore = Integer.parseInt(value(args, ++i));
                case "--horizon-src" -> horizonSrc = sourceRoot(Path.of(value(args, ++i)));
                case "--horizon-revision" -> revision = value(args, ++i);
                case "--java-src" -> javaSrc = Path.of(value(args, ++i));
                case "--i-am-the-author" -> author = value(args, ++i);
                case "--vineflower" -> vineflower = Path.of(value(args, ++i));
                default -> positional.add(args[i]);
            }
        }
        if (mappingFiles.isEmpty() || mappingFiles.stream().noneMatch(p -> p.getFileName().toString().equals("horizon-mappings.json"))) {
            mappingFiles.add(0, defaultMappings());
        }
        Mappings mappings = Mappings.load(mappingFiles);

        switch (cmd) {
            case "check" -> {
                if (positional.size() != 1 || horizonSrc == null) { usage(); return 2; }
                return NativeCheck.run(Path.of(positional.get(0)), horizonSrc);
            }
            case "translate" -> {
                if (positional.size() != 1) { usage(); return 2; }
                Path src = Path.of(positional.get(0));
                if (!Files.isDirectory(src)) throw new IllegalArgumentException(src + " is not a folder");
                RustTranslator.Result r = RustTranslator.translate(src, "mod");
                Path dir = out != null ? out : Path.of("out", "translate-" + src.getFileName());
                TranslationReport.write(r, dir);
                System.out.println(TranslationReport.summary(r));
                System.out.println("Wrote " + dir.resolve("java.rs") + " and " + dir.resolve("translation.md"));
                return 0;
            }
            case "analyze" -> {
                if (positional.size() != 1) { usage(); return 2; }
                ModAnalysis a = Analyzer.analyze(Path.of(positional.get(0)), mappings);
                Path dir = out != null ? out : Path.of("out", a.modId());
                Report.write(a, dir);
                System.out.println(Report.summary(a));
                System.out.println("Report: " + dir.resolve("report.md"));
                return 0;
            }
            case "match" -> {
                if (positional.size() != 2) { usage(); return 2; }
                ModAnalysis a = Analyzer.analyze(Path.of(positional.get(0)), mappings);
                Path src = Path.of(positional.get(1));
                if (!Files.isDirectory(src)) throw new IllegalArgumentException(src + " is not a folder");
                SourceMatch idx = SourceMatch.index(src);
                Map<String, Object> suggestions = idx.suggest(a, mappings, minScore);
                Path file = out != null ? out : Path.of("out", a.modId(), "suggested-mappings.json");
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, Json.write(suggestions));
                int n = Json.map(suggestions.get("methods")).size();
                System.out.println("Indexed " + idx.fns.size() + " Rust functions in " + src);
                System.out.println("Suggested " + n + " of " + a.unmappedCalls().size() + " unmapped game methods (score >= " + minScore + ")");
                System.out.println("Wrote " + file);
                System.out.println("Check them, then: analyze <jar> --mappings " + file);
                return 0;
            }
            case "generate" -> {
                if (positional.size() != 1) { usage(); return 2; }
                JavaSource js = null;
                if (javaSrc != null) {
                    if (!Files.isDirectory(javaSrc)) throw new IllegalArgumentException(javaSrc + " is not a folder");
                    js = JavaSource.index(javaSrc);
                    if (js.size() == 0) System.out.println("No .java files under " + javaSrc);
                }
                generate(Path.of(positional.get(0)), mappings, minScore, horizonSrc, revision, js, out);
                return 0;
            }
            case "license" -> {
                if (positional.size() != 1) { usage(); return 2; }
                Licenses.Check c = Licenses.check(Path.of(positional.get(0)), author);
                System.out.print(Licenses.markdown(c, Path.of(positional.get(0)).getFileName().toString()));
                return c.allowed() ? 0 : 3;
            }
            case "decompile", "port" -> {
                if (positional.size() != 1) { usage(); return 2; }
                Path jar = Path.of(positional.get(0));
                Licenses.Check c = Licenses.check(jar, author);
                String check = Licenses.markdown(c, jar.getFileName().toString());
                if (!c.allowed()) {
                    System.out.print(check);
                    return 3;
                }
                ModAnalysis a = Analyzer.analyze(jar, mappings);
                // Kept apart from the bundle, so sharing a bundle never shares decompiled code.
                Path srcDir = cmd.equals("decompile") && out != null ? out
                        : System.getProperty("horizonport.srcdir") != null ? Path.of(System.getProperty("horizonport.srcdir"), a.modId() + "-src")
                        : Path.of("out", a.modId() + "-src");
                int n = Decompiler.decompile(Decompiler.find(vineflower), jar, srcDir);
                Files.writeString(srcDir.resolve("LICENSE-CHECK.md"), check);
                System.out.println(c.reason());
                System.out.println("Decompiled " + n + " Java files into " + srcDir);
                if (cmd.equals("decompile")) return 0;
                JavaSource js = JavaSource.index(srcDir);
                js.label = "Decompiled Java";
                Path dir = generate(jar, mappings, minScore, horizonSrc, revision, js, out);
                Path porting = dir.resolve("PORTING.md");
                Files.writeString(porting, Files.readString(porting) + "\n## Licence\n\n" + check.substring(check.indexOf('\n') + 1).strip()
                        + (System.getProperty("horizonport.srcdir") != null
                                ? "\n\nThe decompiled source was only used for the conversion and has been deleted.\n"
                                : "\n\nThe decompiled source is in `" + srcDir + "`, outside this bundle: don't share it unless the licence lets you.\n"));
                return 0;
            }
            default -> {
                System.err.println("unknown command: " + cmd);
                usage();
                return 2;
            }
        }
    }

    /** analyze, match (taking the high-confidence matches), then write the bundle. Returns its folder. */
    private static Path generate(Path jar, Mappings mappings, int minScore, Path horizonSrc, String revision, JavaSource js, Path out) throws Exception {
        if (revision != null && !revision.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("--horizon-revision needs a full 40-character commit");
        ModAnalysis a = Analyzer.analyze(jar, mappings);
        SourceMatch idx = null;
        int accepted = 0;
        if (horizonSrc != null) {
            if (!Files.isDirectory(horizonSrc)) throw new IllegalArgumentException(horizonSrc + " is not a folder");
            idx = SourceMatch.index(horizonSrc);
            // The Minecraft version this Horizon plays (FabricLoader.getModContainer("minecraft") in translated code).
            Path version = horizonSrc.resolve("crates/horizon-core/src/version.rs");
            if (Files.isRegularFile(version)) {
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile("pub const VANILLA_VERSION: &str = \"([^\"]+)\";").matcher(Files.readString(version));
                if (vm.find()) RustTranslator.gameVersion = vm.group(1);
            }
            // Only matches the doc and the name (or impl) agree on are taken without a person.
            for (Map.Entry<String, Object> e : Json.map(idx.suggest(a, mappings, minScore).get("methods")).entrySet()) {
                Map<String, Object> s = Json.map(e.getValue());
                if ("high".equals(s.get("confidence")) && !mappings.methods.containsKey(e.getKey())) {
                    mappings.methods.put(e.getKey(), s);
                    accepted++;
                }
            }
            a = Analyzer.analyze(jar, mappings);
        }
        Path dir = out != null ? out : Path.of("out", a.modId() + "-horizon");
        String summary = Generator.generate(a, mappings, idx, js, revision, dir);
        Report.write(a, dir.resolve("report"));
        System.out.println(a.name() + " " + a.version() + ": " + summary);
        if (horizonSrc != null) System.out.println("Took " + accepted + " high-confidence matches from " + horizonSrc);
        else System.out.println("Without --horizon-src, mixins into Horizon's own functions stay commented out.");
        System.out.println("Bundle: " + dir + "  (start with " + dir.resolve("PORTING.md") + ")");
        return dir;
    }

    /** Horizon's source: a folder, or a .zip of it (read in place; the desktop app ships it zipped). */
    static Path sourceRoot(Path p) throws java.io.IOException {
        if (!p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip") || !Files.isRegularFile(p)) return p;
        java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(p);
        Path root = fs.getPath("/");
        // A zip holding one top folder (horizon-<commit>/...) is read from inside that folder.
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            List<Path> top = s.toList();
            if (top.size() == 1 && Files.isDirectory(top.get(0)) && !Files.exists(root.resolve("crates"))) return top.get(0);
        }
        return root;
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) throw new IllegalArgumentException(args[i - 1] + " needs a value");
        return args[i];
    }

    /** mappings/horizon-mappings.json next to the build folder, else in the current folder. */
    private static Path defaultMappings() throws Exception {
        Path here = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path p = here; p != null; p = p.getParent()) {
            Path m = p.resolve("mappings").resolve("horizon-mappings.json");
            if (Files.isRegularFile(m)) return m;
        }
        Path m = Path.of("mappings", "horizon-mappings.json");
        if (Files.isRegularFile(m)) return m;
        throw new IllegalStateException("can't find mappings/horizon-mappings.json; pass --mappings");
    }

    private static void usage() {
        System.out.println("""
                horizon-port: how a Fabric mod's code carries over to Horizon

                  analyze <mod.jar> [--out DIR] [--mappings FILE]...
                      Reads the jar (metadata, mixins, the game methods it uses) and writes
                      DIR/report.md and DIR/report.json (default DIR: out/<mod id>).

                  match <mod.jar> <horizon-source-folder> [--out FILE] [--min-score N] [--mappings FILE]...
                      Indexes Horizon's Rust source and suggests the Horizon function for each
                      game method the mod uses that the table doesn't map yet
                      (default FILE: out/<mod id>/suggested-mappings.json, N: 6).

                  translate <java-source-folder> [--out DIR]
                      Translates the mod's Java that doesn't need the game (maths, data, config,
                      strings...) into Rust: DIR/java.rs and DIR/translation.md, which says what
                      was left out and why (default DIR: out/translate-<folder>).

                  check <bundle-folder> --horizon-src FOLDER
                      Compiles the bundle's Rust (runtime.rs, game_side.rs and the hook-point
                      helpers) against the native_* helpers of that Horizon source, with cargo,
                      without building Horizon. Writes its crate to <bundle>/target/horizon-check.

                  generate <mod.jar> [--horizon-src FOLDER] [--horizon-revision SHA] [--java-src FOLDER] [--out DIR] [--mappings FILE]...
                      Writes a Horizon native-mod bundle (default DIR: out/<mod id>-horizon): the
                      hook points its Fabric events need, an [[injection]] for each mixin whose
                      Horizon function is known, a helper per injection listing the Java game calls
                      and their Horizon equivalents, and PORTING.md. With --horizon-src it matches
                      the mod against that source first and takes the high-confidence matches.
                      With --java-src (the mod's own source, e.g. src/main/java) each helper also
                      gets the original Java it stands in for, as comments.

                  license <mod.jar> [--i-am-the-author NAME]
                      Whether the mod's licence lets you decompile and port it. Open licences (GPL,
                      LGPL, MPL, MIT, Apache, CC-BY...) do, with conditions; All Rights Reserved,
                      no-derivatives and unknown licences don't, unless NAME is one of its authors.

                  decompile <mod.jar> [--i-am-the-author NAME] [--out DIR] [--vineflower JAR]
                      The licence check, then Vineflower (tools/vineflower-*.jar) writes the Java
                      source to DIR (default: out/<mod id>-src) with LICENSE-CHECK.md.

                  port <mod.jar> --horizon-src FOLDER [--horizon-revision SHA] [--i-am-the-author NAME] [--out DIR]
                      decompile, then generate with that source: the Horizon bundle, each helper
                      holding the decompiled Java it stands in for, the licence's terms in PORTING.md.

                The base table, mappings/horizon-mappings.json, is always loaded first; each
                --mappings file is laid on top of it.""");
    }
}
