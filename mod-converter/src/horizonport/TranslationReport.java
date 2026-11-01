package horizonport;

import horizonport.RustTranslator.Item;
import horizonport.RustTranslator.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What {@link RustTranslator} turned into Rust, what it left, and why, grouped by cause. */
final class TranslationReport {
    private TranslationReport() {}

    /** The translated code as one module, for runtime.rs. */
    static String module(Result r) {
        StringBuilder b = new StringBuilder();
        b.append("/// The mod's own Java that doesn't need the game, translated to Rust by horizon-port.\n");
        b.append("/// Each Java method is here whole or not at all; the ones left out say why.\n");
        b.append("#[allow(dead_code, unused_variables, unused_mut, unused_parens, unused_imports, unreachable_code,\n");
        b.append("    non_snake_case, non_upper_case_globals, non_camel_case_types, clippy::all)]\n");
        b.append("pub mod java {\n");
        for (String line : r.rust().split("\n", -1)) {
            if (line.isEmpty()) b.append('\n');
            else b.append("    ").append(line).append('\n');
        }
        while (b.charAt(b.length() - 1) == '\n' && b.charAt(b.length() - 2) == '\n') b.setLength(b.length() - 1);
        b.append("}\n");
        return b.toString();
    }

    static void write(Result r, Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("java.rs"), module(r));
        Files.writeString(dir.resolve("translation.md"), markdown(r, "the mod"));
    }

    static String summary(Result r) {
        long fields = r.items().stream().filter(i -> i.what().equals("field")).count();
        long fieldsOk = r.items().stream().filter(i -> i.what().equals("field") && i.ok()).count();
        return "Translated " + r.translated() + " of " + r.methods() + " methods" + (r.covered() > 0 ? " (" + r.covered() + (r.covered() == 1 ? " more is" : " more are") + " done another way in Horizon)" : "")
                + " and " + fieldsOk + " of " + fields + " static fields to Rust"
                + (r.gameSide() > 0 ? " (" + r.gameSide() + " of those call the game, through Horizon's helpers)" : "")
                + (r.handlers().isEmpty() ? "" : "; " + r.handlers().stream().filter(h -> h.ok()).count() + " of " + r.handlers().size() + " Fabric event handlers")
                + (r.config() != null ? "; config class " + r.config().type() + " (" + r.config().toggles().size() + " on/off options)" : "");
    }

    /** Why something wasn't translated, as one of a few causes. */
    static String cause(String reason) {
        if (reason == null) return "translated";
        if (reason.startsWith(RustTranslator.COVERED)) return "Done another way in Horizon";
        if (reason.contains("which isn't translated") || reason.contains("isn't translated (")) return "Calls or uses other code that isn't translated";
        if (reason.contains("no Horizon helper")) return "Calls the game where Horizon has no helper yet";
        if (reason.contains("opens a screen")) return "Opens a screen (Horizon has no screen API for native mods)";
        if (reason.contains("translated text")) return "Uses translated text (Component.translatable)";
        if (reason.contains("Minecraft's")) return "Uses Minecraft classes (needs Horizon's game API)";
        if (reason.contains("Fabric's")) return "Uses Fabric API";
        if (reason.contains("com.google.gson")) return "Uses Gson (JSON)";
        if (reason.contains("java.nio") || reason.contains("java.io")) return "Uses files (java.nio / java.io)";
        if (reason.contains("java.util.regex") || reason.contains("regular expression")) return "Uses regular expressions";
        if (reason.contains("org.joml")) return "Uses JOML maths (vectors, matrices)";
        if (reason.contains("null")) return "Uses null";
        if (reason.contains("lambda") || reason.contains("method reference")) return "Uses lambdas or method references";
        if (reason.contains("try/") || reason.contains("try-with")) return "Uses try/catch";
        if (reason.contains("instanceof") || reason.contains("downcast") || reason.contains("extends") || reason.contains("implements")
                || reason.contains("interface")) return "Uses inheritance (extends, implements, instanceof)";
        if (reason.contains("second name") || reason.contains("second reference") || reason.contains("identity") || reason.contains("changing static"))
            return "Shares one object between names (Java references)";
        if (reason.contains("java.util")) return "Uses Java collections other than List (Map, Set...)";
        if (reason.contains("(") && reason.matches(".*`\\w+` \\([a-z][\\w.]*\\).*")) return "Uses other libraries";
        return "Other Java this translator doesn't handle yet";
    }

    static String markdown(Result r, String name) {
        StringBuilder b = new StringBuilder();
        b.append("# Java translated to Rust\n\n");
        b.append(summary(r)).append(". It's in `runtime.rs`, under `pub mod java`" + (r.game() != null ? "; code that calls the game is in `game_side.rs`" : "") + ".\n\n");
        b.append("Each method is translated whole or not at all. A method that can't be (it uses Minecraft, a lambda, null...) ");
        b.append("is left out with its reason, and so is every method that calls it.\n\n");
        Map<String, Integer> causes = new TreeMap<>();
        Map<String, List<Item>> firsts = new LinkedHashMap<>();
        for (Item i : r.items()) {
            if (!i.what().equals("method") || i.ok()) continue;
            String c = cause(i.reason());
            causes.merge(c, 1, Integer::sum);
            firsts.computeIfAbsent(c, k -> new ArrayList<>()).add(i);
        }
        if (!causes.isEmpty()) {
            b.append("## Why methods were left out\n\n| Cause | Methods | For example |\n|---|---|---|\n");
            causes.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> {
                Item ex = firsts.get(e.getKey()).get(0);
                b.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" | `").append(ex.name()).append("`: ")
                        .append(ex.reason().replace("|", "\\|")).append(" |\n");
            });
            b.append('\n');
        }
        if (!r.notes().isEmpty()) {
            b.append("## Close, not the same\n\nWhere the Rust does a little less than the Java, because Horizon doesn't have something:\n\n");
            for (String n : r.notes()) b.append("- ").append(n).append('\n');
            b.append('\n');
        }
        if (!r.handlers().isEmpty()) {
            b.append("## Fabric event handlers\n\n| Handler | Hook | Result |\n|---|---|---|\n");
            for (RustTranslator.Handler h : r.handlers()) {
                b.append("| ").append(h.where()).append(" | `").append(h.hook()).append("` | ")
                        .append(!h.ok() ? "not translated: " + h.reason() : h.left().isEmpty() ? "`" + h.fn() + "`, called from the hook"
                                : "`" + h.fn() + "`, called from the hook, without: " + String.join("; ", h.left()))
                        .append(" |\n");
            }
            b.append('\n');
        }
        b.append("## Every class\n\n");
        String file = null;
        for (Item i : r.items()) {
            if (i.what().equals("class") || i.what().equals("file")) {
                b.append("\n### `").append(i.name()).append("`").append(i.ok() ? "" : ": " + i.reason()).append("\n\n");
                file = i.file();
                continue;
            }
            b.append("- ").append(i.ok() ? "[x] " : "[ ] ").append('`').append(i.name()).append('`');
            if (!i.ok()) b.append(": ").append(i.reason());
            else if ("game".equals(i.reason())) b.append(" in game_side.rs");
            b.append(" (").append(file != null ? file : i.file()).append(':').append(i.line()).append(")\n");
        }
        return b.toString();
    }
}
