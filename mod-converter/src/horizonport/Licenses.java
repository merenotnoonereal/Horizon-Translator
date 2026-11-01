package horizonport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides whether a mod's licence lets you decompile it and port it: open licences (GPL, LGPL, MPL,
 * MIT, Apache...) do, with the conditions written down; All Rights Reserved, no-derivatives and
 * unknown licences don't, unless you are the mod's author. It reads fabric.mod.json's `license` and
 * any LICENSE / COPYING file in the jar, and the strictest of them wins.
 *
 * This is a guide, not legal advice: it reads what the jar says about itself.
 */
final class Licenses {
    enum Kind { OPEN, NO_DERIVATIVES, CLOSED }

    record Licence(String id, Kind kind, String condition) {}

    record Check(String declared, List<String> files, List<Licence> licences, Kind kind, boolean allowed,
                 String author, List<String> authors, String reason) {}

    private record Rule(Pattern pattern, String id, Kind kind, String condition) {}

    private static final String KEEP = "Keep the copyright notice and licence text with the port.";
    private static final List<Rule> RULES = List.of(
            rule("agpl|affero", "AGPL-3.0", Kind.OPEN, "A port you share must be AGPL-3.0 too, with its source, also for players reaching it over a network."),
            rule("lgpl|lesser general public", "LGPL", Kind.OPEN, "A port you share stays LGPL, with its source."),
            rule("\\bgpl|gnu general public|gplv?[23]", "GPL", Kind.OPEN, "A port you share must be under the same GPL version, with its source."),
            rule("mpl|mozilla public", "MPL-2.0", Kind.OPEN, "Files you port stay MPL-2.0: publish them if you share the port."),
            rule("eupl", "EUPL-1.2", Kind.OPEN, "A port you share stays EUPL (or a compatible licence), with its source."),
            rule("\\bosl\\b|open software licen[cs]e", "OSL-3.0", Kind.OPEN, "A port you share stays OSL-3.0, with its source, also for players reaching it over a network."),
            rule("cc[- ]?by[- ]?(nc[- ]?)?nd|noderiv", "CC-BY-ND", Kind.NO_DERIVATIVES, "No derivatives: a port is a derivative."),
            rule("cc[- ]?by[- ]?nc[- ]?sa", "CC-BY-NC-SA", Kind.OPEN, "Credit the author, no commercial use, share the port under the same licence."),
            rule("cc[- ]?by[- ]?nc", "CC-BY-NC", Kind.OPEN, "Credit the author; no commercial use."),
            rule("cc[- ]?by[- ]?sa", "CC-BY-SA", Kind.OPEN, "Credit the author; share the port under the same licence."),
            rule("cc0|public domain|unlicense|wtfpl|0bsd", "public domain", Kind.OPEN, "No conditions."),
            rule("cc[- ]?by", "CC-BY", Kind.OPEN, "Credit the author."),
            rule("apache", "Apache-2.0", Kind.OPEN, KEEP + " Say what you changed."),
            rule("\\bmit\\b|expat|permission is hereby granted, free of charge", "MIT", Kind.OPEN, KEEP),
            rule("\\bbsd\\b|redistribution and use in source and binary forms", "BSD", Kind.OPEN, KEEP),
            rule("\\bisc\\b|\\bzlib\\b", "ISC/Zlib", Kind.OPEN, KEEP),
            rule("\\barr\\b|all rights reserved|proprietary|licenseref", "All Rights Reserved", Kind.CLOSED, null));

    private static Rule rule(String regex, String id, Kind kind, String condition) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), id, kind, condition);
    }

    /** What one licence name or text is; unknown text counts as closed. */
    static Licence classify(String text) {
        for (Rule r : RULES) if (r.pattern().matcher(text).find()) return new Licence(r.id(), r.kind(), r.condition());
        return new Licence("unknown (" + abbreviate(text) + ")", Kind.CLOSED, null);
    }

    static Check check(Path jar, String claimedAuthor) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Map<String, Object> fmj = Map.of();
            ZipEntry e = zip.getEntry("fabric.mod.json");
            if (e != null) {
                try (InputStream in = zip.getInputStream(e)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
                    fmj = Json.map(Json.parse(text));
                }
            }
            List<String> declared = new ArrayList<>();
            for (Object v : Json.list(fmj.get("license"))) if (v != null) declared.add(String.valueOf(v));
            List<String> authors = new ArrayList<>();
            for (Object v : Json.list(fmj.get("authors"))) {
                String name = v instanceof String s ? s : Json.str(Json.map(v).get("name"));
                if (name != null) authors.add(name);
            }

            List<Licence> licences = new ArrayList<>();
            for (String d : declared) {
                // "MIT OR Apache-2.0": any one choice is enough; "GPL-3.0 AND MIT": all apply.
                for (String part : d.split("(?i)\\s+(?:and|with)\\s+|,")) {
                    List<Licence> choices = new ArrayList<>();
                    for (String c : part.split("(?i)\\s+or\\s+")) choices.add(classify(c.trim()));
                    licences.add(choices.stream().filter(l -> l.kind() == Kind.OPEN).findFirst().orElse(choices.get(0)));
                }
            }
            List<String> files = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry f = en.nextElement();
                String n = f.getName();
                String base = n.substring(n.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
                if (f.isDirectory() || n.startsWith("META-INF/jars/") || n.chars().filter(ch -> ch == '/').count() > 1) continue;
                if (!(base.startsWith("LICENSE") || base.startsWith("LICENCE") || base.startsWith("COPYING"))) continue;
                try (InputStream in = zip.getInputStream(f)) {
                    String text = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                    Licence l = classifyFile(text);
                    files.add(n + ": " + l.id());
                    licences.add(l);
                }
            }

            Kind kind = licences.isEmpty() ? Kind.CLOSED : Kind.OPEN;
            for (Licence l : licences) if (l.kind().ordinal() > kind.ordinal()) kind = l.kind();

            String author = null;
            if (claimedAuthor != null) {
                for (String a : authors) if (a.equalsIgnoreCase(claimedAuthor.trim())) author = a;
            }
            boolean allowed;
            String reason;
            if (kind == Kind.OPEN) {
                allowed = true;
                reason = "Open licence: decompiling and porting are allowed, under the conditions below.";
            } else if (author != null) {
                allowed = true;
                reason = "The licence doesn't allow it, but you confirmed you are its author, " + author + ": your own code is yours to port.";
            } else {
                allowed = false;
                String what = licences.isEmpty() ? "no licence" : kind == Kind.NO_DERIVATIVES ? "a no-derivatives licence" : "a closed licence ("
                        + String.join(", ", licences.stream().filter(l -> l.kind() == Kind.CLOSED).map(Licence::id).distinct().toList()) + ")";
                reason = "The mod has " + what + ", so it isn't decompiled. "
                        + (claimedAuthor != null ? "\"" + claimedAuthor + "\" isn't one of its authors (" + String.join(", ", authors) + "). "
                        : "If you wrote it, pass --i-am-the-author with your name as the jar lists it (" + String.join(", ", authors) + "). ")
                        + "Otherwise ask its author for permission or the source.";
            }
            return new Check(String.join(" / ", declared), files, licences, kind, allowed, author, authors, reason);
        }
    }

    /** A LICENSE file: open licences say what they grant; a bare copyright line is all rights reserved. */
    private static Licence classifyFile(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (r.kind() == Kind.CLOSED) continue;
            if (r.pattern().matcher(t).find() && (t.contains("permission") || t.contains("license") || t.contains("licence"))) {
                return new Licence(r.id(), r.kind(), r.condition());
            }
        }
        return new Licence("All Rights Reserved (" + abbreviate(text) + ")", Kind.CLOSED, null);
    }

    static String markdown(Check c, String jar) {
        StringBuilder b = new StringBuilder("# Licence check: ").append(jar).append("\n\n");
        b.append("**").append(c.allowed() ? "Allowed" : "Not allowed").append("**: ").append(c.reason()).append("\n\n");
        b.append("- Declared in fabric.mod.json: ").append(c.declared().isEmpty() ? "nothing" : "`" + c.declared() + "`").append('\n');
        for (String f : c.files()) b.append("- Licence file ").append(f).append('\n');
        b.append("- Authors: ").append(c.authors().isEmpty() ? "none listed" : String.join(", ", c.authors())).append('\n');
        if (c.author() != null) b.append("- Author confirmation: you stated you are ").append(c.author()).append('\n');
        List<String> conditions = c.licences().stream().map(Licence::condition).filter(x -> x != null).distinct().toList();
        if (c.kind() == Kind.OPEN && !conditions.isEmpty()) {
            b.append("\n## Conditions for the port\n\n");
            for (String x : conditions) b.append("- ").append(x).append('\n');
        }
        b.append("\nThis reads what the jar says about itself; it isn't legal advice.\n");
        return b.toString();
    }

    private static String abbreviate(String s) {
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() > 60 ? one.substring(0, 57) + "..." : one;
    }
}
