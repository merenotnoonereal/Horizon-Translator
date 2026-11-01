package horizonport;

import horizonport.Analyzer.Call;
import horizonport.Analyzer.Feature;
import horizonport.Analyzer.ModAnalysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes report.md (for people) and report.json (for the next tools) for one analysed mod. */
final class Report {
    static final List<String> STATUSES = List.of("auto", "content", "needs-mapping", "needs-translation", "unsupported");

    private static final Map<String, String> MEANING = Map.of(
            "auto", "The Horizon hook or injection is known: the wiring can be generated. The Java body still has to become Rust.",
            "content", "Data and assets: horizon-port-pack carries these into a content mod (.hmod).",
            "needs-mapping", "The kind of change carries over, but the matching Horizon function isn't in the table yet. Run `match`.",
            "needs-translation", "No direct equivalent: a person (or an AI agent) has to rewrite this part.",
            "unsupported", "Horizon has nothing this can attach to yet.");

    static Map<String, Integer> counts(ModAnalysis a) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String s : STATUSES) c.put(s, 0);
        for (Feature f : a.features()) c.merge(f.status(), 1, Integer::sum);
        return c;
    }

    static String verdict(ModAnalysis a) {
        Map<String, Integer> c = counts(a);
        int total = a.features().size();
        int ready = c.get("auto") + c.get("content");
        if (total == 0) return "Nothing to convert";
        if (c.get("unsupported") == 0 && c.get("needs-translation") == 0 && c.get("needs-mapping") == 0) return "Convertible";
        if (ready == 0) return "Not convertible yet";
        return "Partly convertible";
    }

    static String summary(ModAnalysis a) {
        Map<String, Integer> c = counts(a);
        StringBuilder b = new StringBuilder();
        b.append(a.name()).append(' ').append(a.version()).append(": ").append(verdict(a)).append('\n');
        for (String s : STATUSES) b.append(String.format("  %-18s %d%n", s, c.get(s)));
        b.append(String.format("  game calls mapped  %d of %d", a.mappedCalls().size(), a.mappedCalls().size() + a.unmappedCalls().size()));
        return b.toString();
    }

    static void write(ModAnalysis a, Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), markdown(a));
        Files.writeString(dir.resolve("report.json"), Json.write(json(a)));
    }

    private static String markdown(ModAnalysis a) {
        StringBuilder b = new StringBuilder();
        Map<String, Integer> c = counts(a);
        b.append("# Horizon port report: ").append(a.name()).append("\n\n");
        b.append("`").append(a.modId()).append("` ").append(a.version()).append(" · ").append(a.jar())
                .append(" · ").append(a.classes()).append(" classes\n\n");
        b.append("**").append(verdict(a)).append("**\n\n");

        b.append("| Status | Count | Meaning |\n|---|---|---|\n");
        for (String s : STATUSES) b.append("| ").append(s).append(" | ").append(c.get(s)).append(" | ").append(MEANING.get(s)).append(" |\n");
        b.append('\n');

        if (!a.warnings().isEmpty()) {
            b.append("## Warnings\n\n");
            for (String w : a.warnings()) b.append("- ").append(w).append('\n');
            b.append('\n');
        }

        if (!a.entrypoints().isEmpty()) {
            b.append("## Entry points\n\n");
            a.entrypoints().forEach((k, v) -> b.append("- `").append(k).append("`: ").append(String.join(", ", v)).append('\n'));
            b.append('\n');
        }

        section(b, a, "event", "Fabric events", "Event", true);
        section(b, a, "mixin", "Mixins", "Injection", true);
        section(b, a, "class", "Classes extending the game", "Class", false);
        section(b, a, "access", "Access wideners", "File", false);
        section(b, a, "content", "Content", "What", false);

        int total = a.mappedCalls().size() + a.unmappedCalls().size();
        b.append("## Game methods and fields used\n\n");
        b.append(a.mappedCalls().size()).append(" of ").append(total)
                .append(" are mapped to Horizon. Every Java body that uses an unmapped one needs it mapped before its Rust can be written.\n\n");
        if (!a.mappedCalls().isEmpty()) {
            b.append("<details><summary>Mapped</summary>\n\n| Java | Horizon |\n|---|---|\n");
            for (Call call : a.mappedCalls()) b.append("| `").append(call.key()).append("` | ").append(call.horizon()).append(" |\n");
            b.append("\n</details>\n\n");
        }
        if (!a.unmappedCalls().isEmpty()) {
            b.append("<details><summary>Not mapped yet</summary>\n\n| Java | Kind | Classes using it |\n|---|---|---|\n");
            for (Call call : a.unmappedCalls()) b.append("| `").append(call.key()).append("` | ").append(call.kind()).append(" | ").append(call.uses()).append(" |\n");
            b.append("\n</details>\n\n");
        }
        return b.toString();
    }

    private static void section(StringBuilder b, ModAnalysis a, String category, String title, String col, boolean horizonCol) {
        List<Feature> fs = new ArrayList<>();
        for (Feature f : a.features()) if (f.category().equals(category)) fs.add(f);
        if (fs.isEmpty()) return;
        b.append("## ").append(title).append("\n\n");
        b.append("| ").append(col).append(" | In | ").append(horizonCol ? "Horizon | " : "").append("Status | Note |\n");
        b.append("|---|---|").append(horizonCol ? "---|" : "").append("---|---|\n");
        for (Feature f : fs) {
            b.append("| ").append(cell(f.detail())).append(" | `").append(shortSource(f.source())).append("` | ");
            if (horizonCol) b.append(cell(f.horizon() == null ? "–" : f.horizon())).append(" | ");
            b.append(f.status()).append(" | ").append(cell(f.note() == null ? "" : f.note())).append(" |\n");
        }
        b.append('\n');
    }

    /** Drops the package only: "SettingsScreen$1", "SomeMixin.mod$handler". */
    private static String shortSource(String source) {
        return source.substring(source.lastIndexOf('/') + 1);
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static Map<String, Object> json(ModAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.modId());
        m.put("name", a.name());
        m.put("version", a.version());
        m.put("jar", a.jar());
        m.put("verdict", verdict(a));
        m.put("counts", counts(a));
        m.put("intermediary", a.intermediary());
        m.put("entrypoints", a.entrypoints());
        List<Object> fs = new ArrayList<>();
        for (Feature f : a.features()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("category", f.category());
            o.put("in", f.source());
            o.put("detail", f.detail());
            o.put("horizon", f.horizon());
            o.put("status", f.status());
            o.put("note", f.note());
            fs.add(o);
        }
        m.put("features", fs);
        m.put("mappedCalls", calls(a.mappedCalls()));
        m.put("unmappedCalls", calls(a.unmappedCalls()));
        m.put("warnings", a.warnings());
        return m;
    }

    private static List<Object> calls(List<Call> calls) {
        List<Object> l = new ArrayList<>();
        for (Call c : calls) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("java", c.key());
            o.put("kind", c.kind());
            o.put("uses", c.uses());
            if (c.horizon() != null) o.put("horizon", c.horizon());
            l.add(o);
        }
        return l;
    }
}
