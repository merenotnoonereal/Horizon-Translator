package horizonport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Packs a bundle as a `.hnmod`, Horizon's file for a native mod (horizon_mods::index::native), but only when the
 * conversion is complete: nothing commented out, no TODO left, every Java method translated, and nothing in the jar
 * that needs a content mod. The launcher installs a `.hnmod` from `horizon-native-mods/` and compiles it into the game.
 */
final class NativePackage {
    private NativePackage() {}

    /** full: the .hnmod was written to file. Otherwise gaps says what keeps it from being a full port. */
    record Outcome(boolean full, Path file, List<String> gaps) {}

    static final String MANIFEST = "horizon-native.json";

    /**
     * gaps: what the generator already knows is missing. hooks: the hook points' API version when the bundle only uses
     * hook points (it may then build on newer Horizon commits), else null (it builds on exactly `revision`).
     */
    static Outcome write(Path dir, Path jar, String id, String version, String game, String revision, String mixins,
            Integer hooks, List<String> known) throws IOException {
        List<String> gaps = new ArrayList<>(known);
        Path mod = dir.resolve("mixins").resolve(id);
        Path todo = dir.resolve("todo");
        if (Files.isDirectory(todo)) {
            try (Stream<Path> s = Files.list(todo)) {
                long n = s.count();
                if (n > 0 && gaps.stream().noneMatch(g -> g.contains("commented out")))
                    gaps.add(n + " mixins are written commented out (notes in `todo/`).");
            }
        }
        gaps.addAll(todos(dir, mod));
        gaps.addAll(contentLeft(jar));
        if (revision == null || !revision.matches("[0-9a-f]{40}"))
            gaps.add("The Horizon commit the mod builds on isn't known (pass --horizon-revision, or give a source with `.horizon-revision`).");
        if (mixins == null || !mixins.matches("[0-9a-f]{40}"))
            gaps.add("The horizon-mixins commit isn't known (Horizon's source has no `launcher/src/native_mods.rs`).");
        if (!id.matches("[a-z0-9_]{1,64}")) gaps.add("The mod id `" + id + "` isn't a valid Horizon mod id.");

        Path file = dir.resolve(id + ".hnmod");
        Files.deleteIfExists(file); // from an earlier, complete run
        if (!gaps.isEmpty()) return new Outcome(false, null, gaps);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", 1);
        manifest.put("id", id);
        manifest.put("version", version);
        manifest.put("game", game);
        manifest.put("horizon", revision);
        manifest.put("mixins", mixins);
        if (hooks != null) manifest.put("hooks", hooks);

        List<Path> files;
        try (Stream<Path> s = Files.walk(mod)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        Path tmp = dir.resolve(id + ".hnmod.part");
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, MANIFEST, Json.write(manifest).getBytes(StandardCharsets.UTF_8));
            for (Path f : files) {
                String rel = mod.relativize(f).toString().replace('\\', '/');
                String name = "mixins/" + id + "/" + rel;
                // Horizon accepts Rust and TOML, plus anything under mixins/<id>/data/.
                if (!(rel.endsWith(".rs") || rel.endsWith(".toml") || rel.startsWith("data/"))) continue;
                byte[] bytes = Files.readAllBytes(f);
                if (rel.equals("horizon-mixin.toml")) bytes = pinned(new String(bytes, StandardCharsets.UTF_8), revision).getBytes(StandardCharsets.UTF_8);
                put(zip, name, bytes);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        return new Outcome(true, file, List.of());
    }

    /**
     * Horizon checks that the mixin file's `[compatibility] horizon_revision` is the manifest's `horizon`. A bundle on
     * hook points only has none (it builds on any commit), so the packed copy names the one it was made on; with
     * `hooks` in the manifest the launcher still builds it on newer commits.
     */
    static String pinned(String toml, String revision) {
        if (toml.contains("horizon_revision = \"")) return toml;
        String block = "[compatibility]\nhorizon_revision = \"" + revision + "\"\n\n";
        int at = toml.indexOf("\n[");
        return at < 0 ? toml + "\n" + block : toml.substring(0, at + 1) + block + toml.substring(at + 1);
    }

    /** Every TODO left in the bundle's code, as "file:line: text" (at most 12, then a count). */
    private static List<String> todos(Path dir, Path mod) throws IOException {
        List<String> found = new ArrayList<>();
        if (!Files.isDirectory(mod)) return List.of("The bundle has no `mixins/` folder.");
        List<Path> files;
        try (Stream<Path> s = Files.walk(mod)) {
            files = s.filter(p -> p.toString().endsWith(".rs") || p.toString().endsWith(".toml")).sorted().toList();
        }
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).strip();
                if (l.contains("TODO")) found.add("`" + dir.relativize(f).toString().replace('\\', '/') + ":" + (i + 1) + "` " + l);
            }
        }
        List<String> out = new ArrayList<>();
        if (found.isEmpty()) return out;
        out.add(found.size() + " TODOs are left in the code:");
        for (int i = 0; i < Math.min(12, found.size()); i++) out.add("  " + found.get(i));
        if (found.size() > 12) out.add("  …and " + (found.size() - 12) + " more.");
        return out;
    }

    /**
     * What a native mod can't carry: data files (recipes, loot, tags, worldgen) and assets other than the mod's
     * translations and icon belong in a content mod (.hmod), which this tool doesn't make.
     */
    private static List<String> contentLeft(Path jar) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) return List.of();
        int data = 0, assets = 0;
        String icon = null;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry fmj = z.getEntry("fabric.mod.json");
            if (fmj != null) {
                try {
                    Object v = Json.map(Json.parse(new String(z.getInputStream(fmj).readAllBytes(), StandardCharsets.UTF_8))).get("icon");
                    if (v instanceof String s) icon = s;
                    else if (v instanceof Map<?, ?> m && !m.isEmpty()) icon = String.valueOf(m.values().iterator().next());
                } catch (RuntimeException ignored) {}
            }
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                String n = en.getName();
                if (en.isDirectory()) continue;
                if (n.startsWith("data/")) data++;
                else if (n.startsWith("assets/") && !n.matches("assets/[^/]+/lang/.*") && !n.equals(icon)) assets++;
            }
        }
        List<String> out = new ArrayList<>();
        if (data > 0) out.add(data + " data files (recipes, loot, tags, worldgen…) need a content mod (.hmod), which this tool doesn't make.");
        if (assets > 0) out.add(assets + " asset files (textures, models, sounds…) need a content mod (.hmod), which this tool doesn't make.");
        return out;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(315532800000L); // fixed time: the same bundle gives the same file
        zip.putNextEntry(e);
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Puts a .hnmod where the launcher looks for installed native mods (`<game folder>/horizon-native-mods/`) and turns
     * it on in `horizon-mods.txt`, as the launcher's own install does. home: the game folder (holding options.txt).
     */
    static Path install(Path hnmod, String id, Path home) throws IOException {
        Path dir = home.resolve("horizon-native-mods");
        Files.createDirectories(dir);
        Path to = dir.resolve(id + ".hnmod");
        Files.copy(hnmod, to, StandardCopyOption.REPLACE_EXISTING);
        Path list = home.resolve("horizon-mods.txt");
        List<String> lines = Files.isRegularFile(list) ? new ArrayList<>(Files.readAllLines(list, StandardCharsets.UTF_8)) : new ArrayList<>();
        String key = "mod." + id;
        lines.removeIf(l -> l.contains(":") && l.substring(0, l.indexOf(':')).strip().equals(key));
        lines.add(key + ":true");
        Files.writeString(list, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return to;
    }

    /**
     * Horizon's default game folder (`dirs::data_dir()/horizon`): %APPDATA%\horizon on Windows, $XDG_DATA_HOME/horizon
     * or ~/.local/share/horizon on Linux.
     */
    static Path defaultHome() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "horizon");
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && Path.of(xdg).isAbsolute()) return Path.of(xdg, "horizon");
        return Path.of(System.getProperty("user.home"), ".local", "share", "horizon");
    }
}
