package horizonport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads text files as UTF-8 without ever failing on bytes that aren't UTF-8. Mods sometimes ship files saved
 * in a Windows code page (an author's name with "ł" in fabric.mod.json, say); strict reading
 * (Files.readString) throws MalformedInputException on those. Bad bytes become U+FFFD instead, and a leading
 * byte order mark is dropped.
 */
final class Text {
    private Text() {}

    static String read(Path f) throws IOException {
        String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static List<String> lines(Path f) throws IOException {
        return read(f).lines().toList();
    }
}
