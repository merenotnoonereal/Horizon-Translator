package horizonport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import static horizonport.TranslatorApp.*;

/**
 * The "Get mods" tab: the mods made for Horizon that this project shares (web/public/mods on GitHub). The list and
 * the `.hnmod` files come from the website when it can be reached, else from the copy shipped with the app. Install
 * checks a file's SHA-256 against the list and its id against its own manifest, then adds it to Horizon like the
 * other tabs do; the launcher builds it.
 */
final class GetMods {
    private GetMods() {}

    /** Where the website keeps the list and the files: only files from here (or the app's own copy) are installed. */
    static final String SITE = "https://merenotnoonereal.github.io/Horizon-Translator/mods/";
    static final String SOURCE = REPO_URL + "/tree/main/mods/";
    private static final Pattern FILE = Pattern.compile("[a-z0-9_]{1,64}\\.hnmod");
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final int MAX_BYTES = 20 << 20;

    /** One mod of the list. */
    record Mod(String id, String name, String version, String description, String author, String file, String sha256,
               int hooks, String needs) {}

    /** The list, and where it came from. */
    record Listing(List<Mod> mods, boolean online) {}

    // ------------------------------------------------------------------ the list

    @SuppressWarnings("unchecked")
    static List<Mod> parse(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        List<Mod> out = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("mods", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            String id = Support.str(m, "id"), file = Support.str(m, "file"), sha = Support.str(m, "sha256").toLowerCase();
            if (!ID.matcher(id).matches() || !FILE.matcher(file).matches() || !sha.matches("[0-9a-f]{64}")) continue;
            int hooks = m.get("hooks") instanceof Number n ? n.intValue() : 1;
            out.add(new Mod(id, Support.str(m, "name").isEmpty() ? id : Support.str(m, "name"), Support.str(m, "version"),
                    Support.str(m, "description"), Support.str(m, "author"), file, sha, hooks, Support.str(m, "needs")));
        }
        return out;
    }

    /** The website's list, else the app's copy (mods/index.json beside the app). */
    static Listing load(Path appDir) {
        try {
            byte[] b = fetch(SITE + "index.json", 1 << 20);
            return new Listing(parse(new String(b, StandardCharsets.UTF_8)), true);
        } catch (Exception e) {
            try {
                Path local = appDir.resolve("mods").resolve("index.json");
                return new Listing(Files.isRegularFile(local) ? parse(Text.read(local)) : List.of(), false);
            } catch (Exception e2) {
                return new Listing(List.of(), false);
            }
        }
    }

    static byte[] fetch(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "Horizon-Translator-get-mods");
        try {
            if (c.getResponseCode() != 200) throw new IOException(url + ": HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                byte[] b = in.readNBytes(max + 1);
                if (b.length > max) throw new IOException(url + " is too big");
                return b;
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ getting one

    /** The mod's file, checked: from the website when the list came from there, else the app's copy. */
    static Path download(Path appDir, Mod m, boolean online) throws Exception {
        byte[] b;
        if (online) {
            b = fetch(SITE + m.file(), MAX_BYTES);
        } else {
            Path local = appDir.resolve("mods").resolve(m.file());
            if (!Files.isRegularFile(local)) throw new IOException(I18n.t("getmods.error.no_copy", m.file()));
            b = Files.readAllBytes(local);
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        if (!sha.equals(m.sha256())) throw new IOException(I18n.t("getmods.error.sha_mismatch", m.file()));
        Path tmp = Files.createTempFile("horizon-mod-", ".hnmod");
        Files.write(tmp, b);
        String id = manifestValue(tmp, "id");
        if (!m.id().equals(id)) {
            Files.deleteIfExists(tmp);
            throw new IOException(I18n.t("getmods.error.id_mismatch", m.file(), id, m.id()));
        }
        return tmp;
    }

    /** A string from a .hnmod's horizon-native.json, or null. */
    static String manifestValue(Path hnmod, String key) {
        try (ZipFile z = new ZipFile(hnmod.toFile())) {
            var e = z.getEntry("horizon-native.json");
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                Object v = Json.map(Json.parse(new String(in.readNBytes(1 << 16), StandardCharsets.UTF_8))).get(key);
                return v == null ? null : String.valueOf(v);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** The version of the mod installed in Horizon's game folder, or null when it isn't there. */
    static String installedVersion(Path home, String id) {
        Path f = home.resolve("horizon-native-mods").resolve(id + ".hnmod");
        return Files.isRegularFile(f) ? manifestValue(f, "version") : null;
    }

    // ------------------------------------------------------------------ the tab

    static JComponent panel(TranslatorApp app) {
        JPanel p = card();
        JPanel list = column();
        JLabel status = label(" ", MUTED, 13f, false);
        JButton refresh = ghost(I18n.t("getmods.btn.refresh"));
        Runnable load = () -> {
            refresh.setEnabled(false);
            status.setText(I18n.t("getmods.status.loading"));
            Thread t = new Thread(() -> {
                Listing l = load(app.appDir);
                SwingUtilities.invokeLater(() -> {
                    show(app, list, l);
                    status.setText(l.mods().isEmpty() ? I18n.t("getmods.status.empty")
                            : (l.online() ? I18n.t("getmods.status.online", count(l.mods().size()))
                            : I18n.t("getmods.status.offline", count(l.mods().size()))));
                    refresh.setEnabled(true);
                });
            }, "get-mods");
            t.setDaemon(true);
            t.start();
        };
        refresh.addActionListener(e -> load.run());

        p.add(label(I18n.t("getmods.title"), TEXT, 16f, true));
        p.add(Box.createVerticalStrut(6));
        p.add(wrapLabel(I18n.t("getmods.desc"), MUTED));
        p.add(Box.createVerticalStrut(10));
        JPanel top = row();
        top.add(refresh);
        top.add(Box.createHorizontalStrut(10));
        top.add(status);
        p.add(top);
        p.add(Box.createVerticalStrut(12));
        p.add(list);
        p.add(Box.createVerticalStrut(8));
        p.add(wrapLabel(I18n.t("getmods.warning"), MUTED));
        // Loaded the first time the tab shows.
        p.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && p.isShowing()
                    && p.getClientProperty("loaded") == null) {
                p.putClientProperty("loaded", true);
                load.run();
            }
        });
        return p;
    }

    static String count(int n) {
        return I18n.t(n == 1 ? "getmods.count.one" : "getmods.count.other", n);
    }

    static void show(TranslatorApp app, JPanel list, Listing l) {
        list.removeAll();
        for (Mod m : l.mods()) {
            list.add(modCard(app, m, l.online()));
            list.add(Box.createVerticalStrut(10));
        }
        list.revalidate();
        list.repaint();
    }

    static JComponent modCard(TranslatorApp app, Mod m, boolean online) {
        JPanel c = new JPanel(new BorderLayout(16, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        c.setBackground(SURFACE2);
        c.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(12, 14, 12, 14)));
        c.setAlignmentX(0);

        JPanel text = column();
        text.add(label(m.name() + (m.version().isEmpty() ? "" : "  " + m.version()), TEXT, 15f, true));
        if (!m.author().isEmpty()) text.add(label(I18n.t("getmods.by_author", m.author()), MUTED, 12f, false));
        text.add(Box.createVerticalStrut(4));
        if (!m.description().isEmpty()) text.add(wrapLabel(m.description(), TEXT));
        text.add(Box.createVerticalStrut(4));
        String needs = m.needs().isEmpty()
                ? I18n.t("getmods.needs", m.hooks())
                : I18n.t("getmods.needs_detail", m.hooks(), m.needs());
        text.add(wrapLabel(needs, MUTED));
        JLabel state = label(" ", OK, 12f, true);
        text.add(Box.createVerticalStrut(4));
        text.add(state);
        c.add(text, BorderLayout.CENTER);

        JPanel buttons = column();
        JButton install = primary(I18n.t("getmods.btn.install"));
        JButton save = ghost(I18n.t("getmods.btn.save"));
        JLabel source = link(I18n.t("getmods.link.source"), SOURCE + m.id());
        buttons.add(install);
        buttons.add(Box.createVerticalStrut(6));
        buttons.add(save);
        buttons.add(Box.createVerticalStrut(6));
        buttons.add(source);
        c.add(buttons, BorderLayout.LINE_END);

        Runnable refreshState = () -> {
            String have = installedVersion(NativePackage.defaultHome(), m.id());
            if (have == null) {
                state.setText(" ");
                install.setText(I18n.t("getmods.btn.install"));
            } else if (have.equals(m.version())) {
                state.setForeground(OK);
                state.setText(I18n.t("getmods.state.installed", have));
                install.setText(I18n.t("getmods.btn.reinstall"));
            } else {
                state.setForeground(WARN);
                state.setText(I18n.t("getmods.state.update_available", have, m.version()));
                install.setText(I18n.t("getmods.btn.update"));
            }
        };
        refreshState.run();

        install.addActionListener(e -> {
            install.setEnabled(false);
            save.setEnabled(false);
            state.setForeground(MUTED);
            state.setText(I18n.t("getmods.state.downloading"));
            Thread t = new Thread(() -> {
                String msg;
                boolean ok;
                try {
                    Path f = download(app.appDir, m, online);
                    try {
                        NativePackage.install(f, m.id(), NativePackage.defaultHome());
                    } finally {
                        Files.deleteIfExists(f);
                    }
                    ok = true;
                    msg = null;
                } catch (Exception ex) {
                    ok = false;
                    msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                boolean done = ok;
                String error = msg;
                SwingUtilities.invokeLater(() -> {
                    install.setEnabled(true);
                    save.setEnabled(true);
                    if (done) {
                        refreshState.run();
                        state.setText(state.getText() + I18n.t("getmods.state.build_reminder"));
                    } else {
                        state.setForeground(BAD);
                        state.setText("<html>" + escape(error) + "</html>");
                    }
                });
            }, "install-" + m.id());
            t.setDaemon(true);
            t.start();
        });

        save.addActionListener(e -> {
            JFileChooser ch = new JFileChooser();
            ch.setSelectedFile(new java.io.File(m.file()));
            if (ch.showSaveDialog(c) != JFileChooser.APPROVE_OPTION) return;
            Path to = ch.getSelectedFile().toPath();
            save.setEnabled(false);
            Thread t = new Thread(() -> {
                String error = null;
                try {
                    Path f = download(app.appDir, m, online);
                    Files.move(f, to, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                String err = error;
                SwingUtilities.invokeLater(() -> {
                    save.setEnabled(true);
                    state.setForeground(err == null ? OK : BAD);
                    state.setText(err == null ? "<html>" + I18n.t("getmods.state.saved", escape(to.toString())) + "</html>" : "<html>" + escape(err) + "</html>");
                });
            }, "save-" + m.id());
            t.setDaemon(true);
            t.start();
        });
        return c;
    }
}
