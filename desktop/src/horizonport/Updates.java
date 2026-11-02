package horizonport;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the user when a newer version is on GitHub Releases. It only reads the latest release's tag and page address
 * from GitHub's API and links to that page: it never downloads or runs anything itself.
 */
final class Updates {
    private Updates() {}

    static final String API = TranslatorApp.REPO_URL.replace("https://github.com/", "https://api.github.com/repos/") + "/releases/latest";

    /** The newest release: its version (tag without a leading v) and its page. */
    record Release(String version, String page) {}

    /** This app's version, written into the jar by scripts/build-desktop.sh; null when run from source. */
    static String current() {
        String forced = System.getProperty("horizonport.version"); // for testing the banner
        if (forced != null && !forced.isBlank()) return forced.strip();
        try (InputStream in = Updates.class.getResourceAsStream("app-version.txt")) {
            if (in == null) return null;
            String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    /** The latest published release, or null when GitHub can't be reached or says something unexpected. */
    static Release latest() {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(API).toURL().openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Horizon-Translator-update-check");
            try {
                if (c.getResponseCode() != 200) return null;
                String body;
                try (InputStream in = c.getInputStream()) {
                    body = new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
                }
                String tag = field(body, "tag_name"), page = field(body, "html_url");
                if (tag == null) return null;
                String expected = TranslatorApp.REPO_URL + "/releases/";
                if (page == null || !page.startsWith(expected)) page = TranslatorApp.REPO_URL + "/releases/latest";
                return new Release(tag.replaceFirst("^[vV]", ""), page);
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The first top-level-looking "name": "value" string in GitHub's JSON. */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Whether version a is newer than b, comparing the numbers (1.0.10 > 1.0.9; "1.1" = "1.1.0"). */
    static boolean newer(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] numbers(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("^[vV]?(\\d+(?:\\.\\d+)*)").matcher(v.strip());
        if (!m.find()) return null;
        String[] parts = m.group(1).split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
