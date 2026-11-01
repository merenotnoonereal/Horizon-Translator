package horizonport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Runs Vineflower (Apache-2.0, tools/vineflower-*.jar) to turn a mod jar back into Java source. */
final class Decompiler {
    private Decompiler() {}

    /** tools/vineflower-*.jar beside the build folder, newest name first; or the one given. */
    static Path find(Path given) throws Exception {
        // In-process (the desktop app), Vineflower is on the classpath: no jar to find.
        if (given == null && Boolean.getBoolean("horizonport.inprocess")) return null;
        if (given != null) {
            if (!Files.isRegularFile(given)) throw new IllegalArgumentException(given + " doesn't exist");
            return given;
        }
        Path here = Path.of(Decompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path p = here; p != null; p = p.getParent()) {
            Path tools = p.resolve("tools");
            if (!Files.isDirectory(tools)) continue;
            try (Stream<Path> s = Files.list(tools)) {
                List<Path> jars = s.filter(f -> f.getFileName().toString().matches("vineflower-[\\d.]+\\.jar")).sorted().toList();
                if (!jars.isEmpty()) return jars.get(jars.size() - 1);
            }
        }
        throw new IllegalStateException("No tools/vineflower-*.jar: download it from Maven Central (org.vineflower:vineflower) or pass --vineflower");
    }

    /** Decompiles jar into dir and returns how many .java files it wrote. */
    static int decompile(Path vineflower, Path jar, Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        // The desktop app runs Vineflower in this program instead of starting another one (it must be on the classpath).
        if (Boolean.getBoolean("horizonport.inprocess")) {
            inProcess(jar, dir);
            try (Stream<Path> s = Files.walk(dir)) {
                return (int) s.filter(f -> f.toString().endsWith(".java")).count();
            }
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> cmd = new ArrayList<>(List.of(java.toString(), "-jar", vineflower.toString(),
                "--decompile-generics=true", "--log-level=warn",
                // Kotlin classes as Java too: the translator reads Java only.
                "--kt-enable=false", jar.toAbsolutePath().toString(), dir.toAbsolutePath().toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) throw new IOException("Vineflower failed (exit " + code + "):\n" + log.strip());
        try (Stream<Path> s = Files.walk(dir)) {
            return (int) s.filter(f -> f.toString().endsWith(".java")).count();
        }
    }

    /** Vineflower through its API, by reflection so the converter still builds without it. Same options as the command line. */
    private static void inProcess(Path jar, Path dir) throws IOException {
        try {
            Class<?> api = Class.forName("org.jetbrains.java.decompiler.api.Decompiler");
            Object b = api.getMethod("builder").invoke(null);
            Class<?> bc = b.getClass();
            bc.getMethod("inputs", java.io.File[].class).invoke(b, (Object) new java.io.File[] {jar.toFile()});
            Class<?> saverType = Class.forName("org.jetbrains.java.decompiler.main.extern.IResultSaver");
            Object saver = Class.forName("org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver")
                    .getConstructor(java.io.File.class).newInstance(dir.toFile());
            bc.getMethod("output", saverType).invoke(b, saver);
            java.lang.reflect.Method option = bc.getMethod("option", String.class, Object.class);
            option.invoke(b, "decompile-generics", "1");
            option.invoke(b, "log-level", "warn");
            option.invoke(b, "kt-enable", "0");
            // One worker thread and no per-method timer: simpler and plenty for one mod.
            option.invoke(b, "thread-count", "1");
            option.invoke(b, "max-time-per-method", "0");
            Object d = bc.getMethod("build").invoke(b);
            d.getClass().getMethod("decompile").invoke(d);
        } catch (ReflectiveOperationException e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException t ? t.getCause() : e;
            throw new IOException("Vineflower failed: " + c, c);
        }
    }
}
