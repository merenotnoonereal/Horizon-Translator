package horizonport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The mod's own Java source, when its author gives it: finds a method or an event registration so
 * the generator can copy it next to the Rust it becomes. Only for source you have the right to use,
 * such as your own mod's. It reads text; nothing is compiled or run.
 */
final class JavaSource {
    /** A piece of a source file: where it starts (1-based line) and its text. */
    record Snippet(String file, int line, String text) {}

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CALL = Pattern.compile("(?:\\b(?:this|[A-Z]\\w*)::(\\w+))|(?<![.\\w])(\\w+)\\s*\\(");

    private final Path root;
    /** How helpers name what they copy: "Original Java", or "Decompiled Java" when Vineflower wrote it. */
    String label = "Original Java";
    /** Outer class internal name (com/x/Foo) to its file. */
    private final Map<String, Path> files = new HashMap<>();
    private final Map<Path, String[]> texts = new HashMap<>();

    private JavaSource(Path root) {
        this.root = root;
    }

    static JavaSource index(Path root) throws IOException {
        JavaSource js = new JavaSource(root);
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = PACKAGE.matcher(text);
                String pkg = m.find() ? m.group(1).replace('.', '/') + "/" : "";
                String name = f.getFileName().toString();
                js.files.putIfAbsent(pkg + name.substring(0, name.length() - 5), f);
            }
        }
        return js;
    }

    Path root() {
        return root;
    }

    int size() {
        return files.size();
    }

    /** The method `name` declared in the class (or its outer class's file), with its annotations. */
    Snippet method(String internal, String name) throws IOException {
        String[] t = text(internal);
        if (t == null) return null;
        String code = t[0], masked = t[1];
        Matcher m = Pattern.compile("(?<![.\\w$])" + Pattern.quote(name) + "\\s*\\(").matcher(masked);
        while (m.find()) {
            int close = matching(masked, m.end() - 1, '(', ')');
            int i = close + 1;
            while (i < masked.length() && masked.charAt(i) != '{' && masked.charAt(i) != ';' && masked.charAt(i) != '=') i++;
            // A body, or `;` for an interface or abstract method (@Accessor, @Invoker).
            if (i >= masked.length() || (masked.charAt(i) != '{' && masked.charAt(i) != ';')) continue;
            // A declaration: the text before the name on its line is modifiers and a type, not an expression.
            int lineStart = masked.lastIndexOf('\n', m.start()) + 1;
            String before = masked.substring(lineStart, m.start()).trim();
            if (!isDeclarationPrefix(before)) continue;
            int start = annotationsStart(code, lineStart);
            int end = masked.charAt(i) == ';' ? i + 1 : matching(masked, i, '{', '}') + 1;
            return snippet(internal, code, start, end);
        }
        return null;
    }

    /**
     * Where the class registers an event: the statement holding `token` (END_CLIENT_TICK,
     * registerKeyBinding...), then up to four methods of the same file it calls.
     */
    Snippet event(String internal, String token) throws IOException {
        String[] t = text(internal);
        if (t == null) return null;
        String code = t[0], masked = t[1];
        Matcher m = Pattern.compile("(?<![\\w$])" + Pattern.quote(token) + "(?![\\w$])").matcher(masked);
        if (!m.find()) return null;
        int start = masked.lastIndexOf('\n', m.start()) + 1;
        int open = masked.indexOf('(', m.end());
        int semi = masked.indexOf(';', m.end());
        int end = open >= 0 && (semi < 0 || open < semi) ? matching(masked, open, '(', ')') + 1 : semi;
        if (end < 0) return null;
        while (end < masked.length() && masked.charAt(end) != ';' && masked.charAt(end) != '\n') end++;
        Snippet s = snippet(internal, code, start, Math.min(masked.length(), end + 1));

        // Methods of this file the registration calls, one level deep.
        StringBuilder text = new StringBuilder(s.text());
        Set<String> called = new LinkedHashSet<>();
        Matcher c = CALL.matcher(masked.substring(start, Math.min(masked.length(), end + 1)));
        while (c.find()) called.add(c.group(1) != null ? c.group(1) : c.group(2));
        called.removeAll(Set.of("if", "for", "while", "switch", "catch", "return", "new", "synchronized", token));
        int added = 0;
        for (String name : called) {
            if (added == 4) break;
            Snippet more = method(internal, name);
            if (more == null || more.line() == s.line()) continue;
            text.append("\n\n// ").append(more.file()).append(':').append(more.line()).append('\n').append(more.text());
            added++;
        }
        return new Snippet(s.file(), s.line(), text.toString());
    }

    private static final Pattern KEY_DEFAULT = Pattern.compile(
            "\"(key\\.[\\w.]+)\"\\s*,\\s*(?:(?:InputConstants\\.)?Type\\.KEYSYM\\s*,\\s*)?(?:GLFW\\.GLFW_KEY_(\\w+)|(-?\\d+))");

    /**
     * The default key a KeyMapping is made with, as Horizon names keys (`key.keyboard.h`): from
     * `new KeyMapping("key.mod.name", GLFW.GLFW_KEY_H, ...)` or the raw GLFW code (72).
     */
    String defaultKey(String keyName) throws IOException {
        for (Path f : files.values()) {
            Matcher m = KEY_DEFAULT.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group(1).equals(keyName)) continue;
                String name = m.group(2) != null ? m.group(2).toLowerCase() : glfwName(Integer.parseInt(m.group(3)));
                if (name == null || name.equals("unknown")) return "key.keyboard.unknown";
                return "key.keyboard." + name.replace("left_", "left.").replace("right_", "right.").replace("kp_", "keypad.");
            }
        }
        return "key.keyboard.unknown";
    }

    /** GLFW key codes for letters, digits and F1 to F12; others aren't guessed. */
    static String glfwName(int code) {
        if (code >= 65 && code <= 90) return String.valueOf((char) ('a' + code - 65));
        if (code >= 48 && code <= 57) return String.valueOf((char) ('0' + code - 48));
        if (code >= 290 && code <= 301) return "f" + (code - 289);
        return null;
    }

    private Snippet snippet(String internal, String code, int start, int end) {
        int line = 1;
        for (int k = 0; k < start; k++) if (code.charAt(k) == '\n') line++;
        String body = code.substring(start, end);
        return new Snippet(root.relativize(files.get(outer(internal))).toString().replace('\\', '/'), line, dedent(body));
    }

    private static final Pattern LEADING_ANNOTATION = Pattern.compile("^@[\\w.]+(?:\\([^)]*\\))?\\s*");
    private static final Pattern TYPE_AND_MODIFIERS = Pattern.compile("[\\w$<>\\[\\]?,.&\\s]*[\\w$>\\]]");
    private static final Set<String> NOT_A_TYPE = Set.of("return", "new", "else", "throw", "case", "yield", "assert");

    /** "private static void", "public <T> List<T>": modifiers and a return type, not part of an expression. */
    static boolean isDeclarationPrefix(String before) {
        String s = before;
        for (Matcher m = LEADING_ANNOTATION.matcher(s); m.find(); m = LEADING_ANNOTATION.matcher(s)) s = s.substring(m.end());
        s = s.trim();
        if (s.isEmpty() || !TYPE_AND_MODIFIERS.matcher(s).matches()) return false;
        String last = s.substring(s.lastIndexOf(' ') + 1);
        return !NOT_A_TYPE.contains(last) && !s.endsWith(".");
    }

    /** Walks up from a declaration's line over its annotations (`@Inject(...)`, possibly several lines). */
    private static int annotationsStart(String code, int lineStart) {
        int start = lineStart;
        while (start > 0) {
            int prevEnd = start - 1;
            int prevStart = code.lastIndexOf('\n', prevEnd - 1) + 1;
            String prev = code.substring(prevStart, prevEnd).trim();
            if (prev.isEmpty() || prev.endsWith(";") || prev.endsWith("{") || prev.endsWith("}") || prev.startsWith("*") || prev.startsWith("/") ) break;
            start = prevStart;
        }
        return start;
    }

    private String[] text(String internal) throws IOException {
        Path f = files.get(outer(internal));
        if (f == null) return null;
        String[] t = texts.get(f);
        if (t == null) {
            String code = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
            t = new String[] {code, mask(code)};
            texts.put(f, t);
        }
        return t;
    }

    private static String outer(String internal) {
        int d = internal.indexOf('$');
        return d < 0 ? internal : internal.substring(0, d);
    }

    /** The same text with comments and string/char contents blanked, so brackets in them don't count. */
    static String mask(String s) {
        char[] out = s.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) { if (out[i] != '\n') out[i] = ' '; i++; }
                if (i < out.length) { out[i++] = ' '; if (i < out.length) out[i++] = ' '; }
            } else if (c == '"' && i + 2 < out.length && out[i + 1] == '"' && out[i + 2] == '"') {
                i += 3;
                while (i < out.length && !(out[i] == '"' && i + 2 < out.length && out[i + 1] == '"' && out[i + 2] == '"')) { if (out[i] != '\n') out[i] = ' '; i++; }
                i += 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < out.length && out[i] != c && out[i] != '\n') {
                    if (out[i] == '\\' && i + 1 < out.length) out[i++] = ' ';
                    out[i++] = ' ';
                }
                i++;
            } else i++;
        }
        return new String(out);
    }

    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) depth++;
            else if (s.charAt(i) == c && --depth == 0) return i;
        }
        return s.length() - 1;
    }

    /** Removes the indentation every line shares. */
    private static String dedent(String s) {
        List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        int min = Integer.MAX_VALUE;
        for (String l : lines) if (!l.isBlank()) min = Math.min(min, l.length() - l.stripLeading().length());
        if (min == Integer.MAX_VALUE) min = 0;
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String l = lines.get(k);
            b.append(l.isBlank() ? "" : l.substring(Math.min(min, l.length() - l.stripLeading().length())));
            if (k + 1 < lines.size()) b.append('\n');
        }
        return b.toString();
    }
}
