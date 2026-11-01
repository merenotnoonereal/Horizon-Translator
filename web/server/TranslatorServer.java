import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the website (web/public) on your own PC, for trying it before it goes on GitHub Pages.
 *
 * Both converters run in the visitor's browser, so this is only a file server. Opening index.html
 * straight from disk doesn't work: browsers block WebAssembly workers and CheerpJ on file:// pages.
 * Supports Range requests, which CheerpJ uses to read the jars and Horizon's source zip.
 *
 *   java web/server/TranslatorServer.java        (from the repo root; PORT=8080 by default)
 */
public class TranslatorServer {
    static Path root;

    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("ico", "image/x-icon"),
            Map.entry("json", "application/json"), Map.entry("md", "text/plain; charset=utf-8"),
            Map.entry("wasm", "application/wasm"), Map.entry("jar", "application/java-archive"),
            Map.entry("zip", "application/zip"));

    public static void main(String[] args) throws IOException {
        root = Path.of("web/public").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("index.html")))
            throw new IllegalStateException("run this from the repo root (no web/public/index.html here)");
        String p = System.getenv("PORT");
        int port = p == null || p.isBlank() ? 8080 : Integer.parseInt(p);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", TranslatorServer::handle);
        server.start();
        System.out.println("Horizon Translator: http://localhost:" + port);
    }

    static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) { send(ex, 405, "Method not allowed"); return; }
            String path = ex.getRequestURI().getPath();
            if (System.getenv("LOG_REQUESTS") != null)
                System.out.println(method + " " + path + " " + (ex.getRequestHeaders().getFirst("Range") == null ? "" : ex.getRequestHeaders().getFirst("Range")));
            if (path.endsWith("/")) path += "index.html";
            Path file = root.resolve(URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) { send(ex, 404, "Not found"); return; }

            String name = file.getFileName().toString();
            var h = ex.getResponseHeaders();
            h.set("Content-Type", TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream"));
            h.set("Cache-Control", "no-cache");
            h.set("Accept-Ranges", "bytes");
            h.set("X-Content-Type-Options", "nosniff");
            long size = Files.size(file), start = 0, end = size - 1;
            int code = 200;
            String range = ex.getRequestHeaders().getFirst("Range");
            Matcher m = range == null ? null : RANGE.matcher(range.trim());
            if (m != null && m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) start = Math.max(0, size - Long.parseLong(m.group(2)));
                else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
                }
                if (start > end || start >= size) {
                    h.set("Content-Range", "bytes */" + size);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                h.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
                code = 206;
            }
            long len = end - start + 1;
            if (method.equals("HEAD")) { h.set("Content-Length", Long.toString(len)); ex.sendResponseHeaders(code, -1); return; }
            ex.sendResponseHeaders(code, len);
            try (InputStream in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
                in.skipNBytes(start);
                byte[] buf = new byte[1 << 16];
                for (long left = len; left > 0; ) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        }
    }

    static void send(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
