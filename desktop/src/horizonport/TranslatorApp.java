package horizonport;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Horizon Translator for Windows: the website's menu as a desktop app, so everything runs on the
 * user's own PC. Mods go through horizon-port (Java, started as its own process with the bundled
 * Java), shader packs through horizon-shaderport.exe.
 *
 * Files next to this jar (the app folder): horizon-port.jar, vineflower-*.jar, horizon-src.zip,
 * mappings/horizon-mappings.json, horizon-shaderport.exe, logo.png, donate.json.
 *
 *   --selftest <mod.jar> <pack.zip> <out folder>   runs both converters without the window
 *   --screenshot <file.png> [tab number]           saves a picture of the window and exits
 */
public final class TranslatorApp {
    static final String REPO_URL = "https://github.com/merenotnoonereal/Horizon-Translator";
    static final String SITE_TERMS = REPO_URL + "/blob/main/web/public/terms.html";
    static final String DISCORD_URL = "https://discord.gg/nzqXYDXmq6";

    // Colours from the website (light theme).
    static final Color BG = new Color(0xf6f7f4), SURFACE = Color.WHITE, SURFACE2 = new Color(0xeef1ec),
            TEXT = new Color(0x1c2420), MUTED = new Color(0x5a665f), BORDER = new Color(0xd8ded6),
            ACCENT = new Color(0x1f6f5c), ACCENT_SOFT = new Color(0xdcefe7), WARN = new Color(0x9a6400),
            WARN_SOFT = new Color(0xfbefd6), OK = new Color(0x2e7d32), BAD = new Color(0xb3261e);

    final Path appDir;
    Path outDir;
    File modFile, packFile;

    JFrame frame;
    JTabbedPane tabs;
    JLabel modChosen, packChosen, status, outLabel, resultTitle;
    JCheckBox isAuthor, install, installMod;
    JTextField authorName;
    JButton convertMod, convertPack, openResult;
    JProgressBar progress;
    JEditorPane report;
    JTextArea log;
    JPanel resultPanel;
    Path lastResult;

    TranslatorApp(Path appDir) {
        this.appDir = appDir;
        this.outDir = Path.of(System.getProperty("user.home"), "Documents", "Horizon Translator");
    }

    public static void main(String[] args) throws Exception {
        Path appDir = appDir();
        if (args.length >= 1 && args[0].equals("--check-update")) {
            String have = Updates.current();
            Updates.Release r = Updates.latest();
            System.out.println("this app: " + (have == null ? "(no version: run from source)" : have));
            System.out.println("on GitHub: " + (r == null ? "(couldn't check)" : r.version() + "  " + r.page()));
            System.out.println("newer one available: " + (r != null && have != null && Updates.newer(r.version(), have)));
            System.exit(0);
        }
        if (args.length >= 4 && args[0].equals("--selftest")) {
            System.exit(selfTest(appDir, Path.of(args[1]), Path.of(args[2]), Path.of(args[3])));
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        TranslatorApp app = new TranslatorApp(appDir);
        SwingUtilities.invokeAndWait(app::build);
        if (args.length >= 2 && args[0].equals("--screenshot")) {
            if (args.length >= 3) SwingUtilities.invokeAndWait(() -> app.tabs.setSelectedIndex(Integer.parseInt(args[2])));
            Thread.sleep(args.length >= 3 ? 7000 : 800); // time for the Support tab's fetch
            SwingUtilities.invokeAndWait(() -> app.saveScreenshot(Path.of(args[1])));
            System.exit(0);
        }
    }

    /** The folder holding this app's jar (in the installed app: <install>/app). */
    static Path appDir() throws Exception {
        Path here = Path.of(TranslatorApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.isDirectory(here) ? here : here.getParent();
    }

    // ------------------------------------------------------------------ the window

    void build() {
        frame = new JFrame("Horizon Translator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        try {
            BufferedImage icon = ImageIO.read(appDir.resolve("logo.png").toFile());
            if (icon != null) frame.setIconImage(icon);
        } catch (IOException ignored) {}

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(header(), BorderLayout.NORTH);

        JPanel body = new FitWidth();
        body.setBorder(new EmptyBorder(16, 20, 20, 20));
        JPanel update = updateBanner();
        body.add(update);
        body.add(notice("Experimental: don't expect a finished mod.",
                "The mod converter is a helper, not a one-click port. Most mods come out only partly working: anything "
                        + "built on Minecraft features Horizon doesn't have yet is switched off and listed in PORTING.md for you to finish. "
                        + "Only a mod that converts fully comes out as a ready-to-install .hnmod file."));
        body.add(Box.createVerticalStrut(12));

        tabs = new JTabbedPane() { // as tall as the open tab, not the tallest one
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        tabs.addTab("Mods", onlyWhenOpen(modPanel()));
        tabs.addTab("Shader packs", onlyWhenOpen(shaderPanel()));
        tabs.addTab("Support me ♥", onlyWhenOpen(Support.panel(appDir)));
        tabs.addChangeListener(e -> { tabs.revalidate(); tabs.repaint(); });
        tabs.setAlignmentX(0);
        body.add(tabs);
        body.add(Box.createVerticalStrut(10));
        body.add(outputRow());
        body.add(Box.createVerticalStrut(10));

        progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setAlignmentX(0);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        body.add(progress);
        status = label("Everything runs on this PC: your files are never uploaded.", MUTED, 13f, false);
        body.add(status);
        body.add(Box.createVerticalStrut(10));
        body.add(resultPanel());

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setSize(920, 820);
        frame.setMinimumSize(new Dimension(640, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** A tab page that only asks for height while it's the open tab. */
    JComponent onlyWhenOpen(JComponent page) {
        JPanel w = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return tabs.getSelectedComponent() == this ? d : new Dimension(d.width, 0);
            }
        };
        w.setOpaque(false);
        w.add(page);
        return w;
    }

    JComponent header() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(SURFACE);
        h.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER), new EmptyBorder(10, 20, 10, 20)));
        JLabel title = label("Horizon Translator", TEXT, 18f, true);
        try {
            BufferedImage logo = ImageIO.read(appDir.resolve("logo.png").toFile());
            if (logo != null) title.setIcon(new ImageIcon(logo.getScaledInstance(28, 28, Image.SCALE_SMOOTH)));
            title.setIconTextGap(10);
        } catch (IOException ignored) {}
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(title);
        String version = Updates.current();
        if (version != null) {
            left.add(Box.createHorizontalStrut(10));
            left.add(label(version, MUTED, 12f, false));
        }
        h.add(left, BorderLayout.WEST);
        JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        links.setOpaque(false);
        links.add(link("How it works", REPO_URL + "/blob/main/docs/HOW-IT-WORKS.md"));
        links.add(link("Report an issue", REPO_URL + "/issues/new/choose"));
        links.add(link("Discord", DISCORD_URL));
        JLabel support = label("<html><u>Support me ♥</u></html>", ACCENT, 13f, true);
        support.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        support.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { tabs.setSelectedIndex(2); }
        });
        links.add(support);
        h.add(links, BorderLayout.EAST);
        return h;
    }

    JComponent modPanel() {
        JPanel p = card();
        modChosen = label(" ", ACCENT, 14f, true);
        p.add(dropZone("Drop a Fabric mod .jar here", "or click to choose a file", ".jar", f -> {
            modFile = f;
            modChosen.setText(f.getName() + "  ·  " + String.format(Locale.ROOT, "%.1f MB", f.length() / 1048576.0));
            convertMod.setEnabled(true);
        }, modChosen));
        p.add(Box.createVerticalStrut(12));
        p.add(wrapLabel("The converter only decompiles mods whose licence allows it (MIT, Apache, GPL, LGPL, MPL, OSL and similar). "
                + "All Rights Reserved mods are only converted for their author.", MUTED));
        p.add(Box.createVerticalStrut(8));
        isAuthor = new JCheckBox("I made this mod");
        isAuthor.setOpaque(false);
        isAuthor.setAlignmentX(0);
        p.add(isAuthor);
        JPanel nameRow = row();
        nameRow.add(label("Your name exactly as the mod lists it (fabric.mod.json): ", MUTED, 13f, false));
        authorName = new JTextField(18);
        authorName.setMaximumSize(new Dimension(260, 30));
        nameRow.add(authorName);
        nameRow.setVisible(false);
        isAuthor.addActionListener(e -> { nameRow.setVisible(isAuthor.isSelected()); p.revalidate(); });
        p.add(nameRow);
        installMod = new JCheckBox("If it converts fully, add the .hnmod to Horizon (the launcher then builds it)");
        installMod.setOpaque(false);
        installMod.setSelected(true);
        installMod.setAlignmentX(0);
        p.add(installMod);
        p.add(Box.createVerticalStrut(12));
        convertMod = primary("Convert mod");
        convertMod.setEnabled(false);
        convertMod.addActionListener(e -> convertModClicked());
        p.add(convertMod);
        p.add(Box.createVerticalStrut(6));
        p.add(agreeLine());
        return p;
    }

    JComponent shaderPanel() {
        JPanel p = card();
        packChosen = label(" ", ACCENT, 14f, true);
        p.add(dropZone("Drop a shader pack .zip here", "Iris or OptiFine format · or click to choose a file", ".zip", f -> {
            packFile = f;
            packChosen.setText(f.getName() + "  ·  " + String.format(Locale.ROOT, "%.1f MB", f.length() / 1048576.0));
            convertPack.setEnabled(true);
        }, packChosen));
        p.add(Box.createVerticalStrut(12));
        p.add(wrapLabel("Works with packs like BSL, Complementary, Bliss, Photon, Noble, Mellow, MakeUp, Solas and Super Duper Vanilla. "
                + "Big packs take a few minutes.", MUTED));
        p.add(Box.createVerticalStrut(8));
        install = new JCheckBox("Also install it into Horizon's shaderpacks folder");
        install.setOpaque(false);
        install.setSelected(true);
        install.setAlignmentX(0);
        p.add(install);
        p.add(Box.createVerticalStrut(12));
        convertPack = primary("Convert shader pack");
        convertPack.setEnabled(false);
        convertPack.addActionListener(e -> convertPackClicked());
        p.add(convertPack);
        p.add(Box.createVerticalStrut(6));
        p.add(agreeLine());
        return p;
    }

    JComponent outputRow() {
        JPanel r = row();
        outLabel = label("Results are saved in: " + outDir, MUTED, 13f, false);
        r.add(outLabel);
        r.add(Box.createHorizontalStrut(10));
        JButton change = ghost("Change…");
        change.addActionListener(e -> {
            JFileChooser c = new JFileChooser(outDir.toFile());
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                outDir = c.getSelectedFile().toPath();
                outLabel.setText("Results are saved in: " + outDir);
            }
        });
        r.add(change);
        r.add(Box.createHorizontalStrut(6));
        JButton open = ghost("Open folder");
        open.addActionListener(e -> openFolder(outDir));
        r.add(open);
        return r;
    }

    JComponent resultPanel() {
        resultPanel = card();
        resultTitle = label(" ", TEXT, 16f, true);
        resultPanel.add(resultTitle);
        resultPanel.add(Box.createVerticalStrut(8));
        JPanel actions = row();
        openResult = primary("Open result");
        openResult.addActionListener(e -> { if (lastResult != null) openFolder(lastResult); });
        actions.add(openResult);
        actions.add(Box.createHorizontalStrut(8));
        JButton issue = ghost("Report a problem");
        issue.addActionListener(e -> browse(REPO_URL + "/issues/new/choose"));
        actions.add(issue);
        resultPanel.add(actions);
        resultPanel.add(Box.createVerticalStrut(10));

        report = new JEditorPane("text/html", "");
        report.setEditable(false);
        report.addHyperlinkListener(e -> { if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) browse(e.getURL().toString()); });
        log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTabbedPane out = new JTabbedPane();
        out.addTab("Report", scroll(report));
        out.addTab("Converter log", scroll(log));
        out.setPreferredSize(new Dimension(800, 360));
        out.setAlignmentX(0);
        resultPanel.add(out);
        resultPanel.setVisible(false);
        return resultPanel;
    }

    JComponent footer() {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 6));
        f.setBackground(SURFACE2);
        f.add(label("Community project · not affiliated with Mojang, Microsoft or the Horizon team · no warranty", MUTED, 12f, false));
        f.add(link("Terms of Use", SITE_TERMS));
        f.add(link("Source code", REPO_URL));
        return f;
    }

    JComponent agreeLine() {
        JPanel r = row();
        r.add(label("By converting you agree to the ", MUTED, 12f, false));
        r.add(link("Terms of Use", SITE_TERMS));
        r.add(label(": only convert files you're allowed to modify.", MUTED, 12f, false));
        return r;
    }

    // ------------------------------------------------------------------ converting

    void convertModClicked() {
        String author = isAuthor.isSelected() ? authorName.getText().strip() : "";
        if (isAuthor.isSelected() && author.isEmpty()) { authorName.requestFocus(); return; }
        Path jar = modFile.toPath();
        boolean inst = installMod.isSelected();
        run("Converting " + jar.getFileName() + "… (checking the licence, decompiling, translating to Rust)",
                () -> convertMod(appDir, jar, author, inst, outDir));
    }

    void convertPackClicked() {
        Path pack = packFile.toPath();
        boolean inst = install.isSelected();
        run("Converting " + pack.getFileName() + "… (loading every program the way Horizon does, then fixing what fails)",
                () -> convertPack(appDir, pack, inst, outDir));
    }

    interface Job { Result call() throws Exception; }

    record Result(boolean ok, String title, String report, String log, Path where) {}

    void run(String what, Job job) {
        convertMod.setEnabled(false);
        convertPack.setEnabled(false);
        progress.setVisible(true);
        status.setText(what);
        status.setForeground(MUTED);
        new SwingWorker<Result, Void>() {
            @Override protected Result doInBackground() throws Exception { return job.call(); }
            @Override protected void done() {
                progress.setVisible(false);
                convertMod.setEnabled(modFile != null);
                convertPack.setEnabled(packFile != null);
                Result r;
                try { r = get(); } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    r = new Result(false, "Something went wrong: " + c.getMessage(), "", String.valueOf(c), null);
                }
                show(r);
            }
        }.execute();
    }

    void show(Result r) {
        status.setText(r.ok() ? "Done. Saved in " + r.where() : r.title());
        status.setForeground(r.ok() ? OK : BAD);
        resultTitle.setText(r.title());
        resultTitle.setForeground(r.ok() ? TEXT : BAD);
        lastResult = r.where();
        openResult.setVisible(r.where() != null);
        report.setText("<html><body style='font-family:sans-serif;font-size:11px'>" + Markdown.toHtml(r.report()) + "</body></html>");
        report.setCaretPosition(0);
        log.setText(r.log());
        log.setCaretPosition(0);
        resultPanel.setVisible(true);
        resultPanel.revalidate();
    }

    /**
     * horizon-port in its own process (with the Java this app ships), Vineflower in that process. Gives the bundle
     * folder; when the converter finds it's a full port, the <id>.hnmod in it (Horizon's native-mod file), which
     * install also puts into Horizon's horizon-native-mods folder.
     */
    static Result convertMod(Path appDir, Path jar, String author, boolean install, Path outDir) throws Exception {
        Path work = Files.createTempDirectory("horizon-translator-");
        try {
            Path bundle = work.resolve("bundle");
            List<String> cmd = new ArrayList<>(List.of(javaExe().toString(), "-Dhorizonport.inprocess=true",
                    "-Dhorizonport.srcdir=" + work.resolve("decompiled"), "-cp", classpath(appDir), "horizonport.Main",
                    "port", jar.toString(), "--horizon-src", appDir.resolve("horizon-src.zip").toString(),
                    "--mappings", appDir.resolve("mappings/horizon-mappings.json").toString(), "--out", bundle.toString()));
            if (!author.isEmpty()) { cmd.add("--i-am-the-author"); cmd.add(author); }
            if (install) cmd.add("--install");
            Proc p = exec(cmd, work, 10);
            if (p.code() == 3) return new Result(false, "Not converted: the mod's licence doesn't allow it", p.out(), p.out(), null);
            if (p.code() != 0 || !Files.isDirectory(bundle))
                return new Result(false, p.timedOut() ? "Stopped: the conversion took too long" : "The converter stopped with an error", "", p.out(), null);
            String id = modId(bundle, jar);
            Files.createDirectories(outDir);
            Path dest = unique(outDir.resolve(id + "-horizon"));
            moveTree(bundle, dest);
            String rep = read(dest.resolve("PORTING.md"));
            String tr = read(dest.resolve("report/translation.md"));
            String report = rep + (tr.isEmpty() ? "" : "\n\n" + tr);
            Path hnmod = dest.resolve(id + ".hnmod");
            if (Files.isRegularFile(hnmod)) {
                boolean added = p.out().contains("installed as ");
                return new Result(true, "Full port: " + hnmod.getFileName() + " is ready"
                        + (added ? "  ·  added to Horizon: build it in the launcher (Mods > Build)" : "  ·  the report says how to install it"),
                        report, p.out(), hnmod);
            }
            return new Result(true, "Not a full port, so no .hnmod was made: the report lists what's left", report, p.out(), dest);
        } finally {
            deleteTree(work); // includes the decompiled Java
        }
    }

    static Result convertPack(Path appDir, Path pack, boolean install, Path outDir) throws Exception {
        Path exe = appDir.resolve("horizon-shaderport.exe");
        if (!Files.isRegularFile(exe)) exe = appDir.resolve("horizon-shaderport");
        Files.createDirectories(outDir);
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "port", pack.toString(), "--out", outDir.toString()));
        if (install) cmd.add("--install");
        Proc p = exec(cmd, outDir, 20);
        String stem = pack.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
        Path zip = outDir.resolve(stem + "-horizon.zip");
        if (p.code() != 0 || !Files.isRegularFile(zip))
            return new Result(false, p.timedOut() ? "Stopped: the conversion took too long" : "This pack couldn't be converted", "", p.out(), null);
        String after = p.out().lines().filter(l -> l.contains("after:")).findFirst().orElse("Converted").strip();
        String rep = read(outDir.resolve(stem + "-horizon.md"));
        String installed = p.out().lines().filter(l -> l.contains("installed")).findFirst().orElse("");
        return new Result(true, after + (installed.isEmpty() ? "" : "  ·  installed into Horizon"), rep, p.out(), zip);
    }

    static int selfTest(Path appDir, Path jar, Path pack, Path out) throws Exception {
        Result m = convertMod(appDir, jar, "", false, out);
        System.out.println("mod: " + m.ok() + " | " + m.title() + " | " + m.where());
        Result s = convertPack(appDir, pack, false, out);
        System.out.println("shader: " + s.ok() + " | " + s.title() + " | " + s.where());
        return m.ok() && s.ok() ? 0 : 1;
    }

    record Proc(int code, String out, boolean timedOut) {}

    static Proc exec(List<String> cmd, Path dir, int minutes) throws Exception {
        Path logFile = Files.createTempFile("horizon-translator-log", ".txt");
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean done = p.waitFor(minutes, TimeUnit.MINUTES);
            if (!done) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); }
            return new Proc(done ? p.exitValue() : -1, Files.readString(logFile, StandardCharsets.UTF_8), !done);
        } finally {
            Files.deleteIfExists(logFile);
        }
    }

    static Path javaExe() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path w = bin.resolve("java.exe");
        return Files.isRegularFile(w) ? w : bin.resolve("java");
    }

    static String classpath(Path appDir) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add(appDir.resolve("horizon-port.jar").toString());
        try (Stream<Path> s = Files.list(appDir)) {
            s.filter(f -> f.getFileName().toString().matches("vineflower-[\\d.]+\\.jar")).forEach(f -> parts.add(f.toString()));
        }
        return String.join(File.pathSeparator, parts);
    }

    static String modId(Path bundle, Path jar) throws IOException {
        Path mixins = bundle.resolve("mixins");
        if (Files.isDirectory(mixins)) try (Stream<Path> s = Files.list(mixins)) {
            var first = s.filter(Files::isDirectory).findFirst();
            if (first.isPresent()) return first.get().getFileName().toString();
        }
        return jar.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
    }

    static Path unique(Path p) {
        if (!Files.exists(p)) return p;
        for (int i = 2; ; i++) {
            Path q = p.resolveSibling(p.getFileName() + "-" + i);
            if (!Files.exists(q)) return q;
        }
    }

    static void moveTree(Path from, Path to) throws IOException {
        try {
            Files.move(from, to);
        } catch (IOException e) { // another drive: copy, then delete
            try (Stream<Path> s = Files.walk(from)) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    Path t = to.resolve(from.relativize(f).toString());
                    if (Files.isDirectory(f)) Files.createDirectories(t); else Files.copy(f, t);
                }
            }
            deleteTree(from);
        }
    }

    static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    static String read(Path p) throws IOException { return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : ""; }

    // ------------------------------------------------------------------ small UI helpers

    static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    /** The scrolling page: always as wide as the window, scrolls only up and down. */
    /**
     * An HTML label that wraps to the width it's given, whatever the screen's font scale (a fixed CSS width came out
     * too wide on Linux). Its height follows its width.
     */
    static final class WrapLabel extends JLabel {
        private int laidOutWidth = -1;

        WrapLabel(String html) {
            super(html);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) {
                    if (getWidth() != laidOutWidth) { laidOutWidth = getWidth(); revalidate(); }
                }
            });
        }

        @Override public Dimension getPreferredSize() {
            javax.swing.text.View v = (javax.swing.text.View) getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
            if (v == null) return super.getPreferredSize();
            Insets in = getInsets();
            int w = getWidth() > 0 ? getWidth() : 640; // before the first layout: a guess
            v.setSize(Math.max(80, w - in.left - in.right), 0);
            int h = (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS));
            return new Dimension(w, h + in.top + in.bottom);
        }

        @Override public Dimension getMinimumSize() { return new Dimension(80, getPreferredSize().height); }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    static final class FitWidth extends JPanel implements Scrollable {
        FitWidth() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Items side by side at their own size; only as tall as they need. */
    static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setOpaque(false);
        p.setAlignmentX(0);
        return p;
    }

    static JPanel card() {
        JPanel p = column();
        p.setOpaque(true);
        p.setBackground(SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(16, 16, 16, 16)));
        p.setAlignmentX(0);
        return p;
    }

    static JLabel label(String text, Color color, float size, boolean bold) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        l.setAlignmentX(0);
        return l;
    }

    static JComponent wrapLabel(String text, Color color) {
        JLabel l = new WrapLabel("<html>" + escape(text) + "</html>");
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(13f));
        l.setAlignmentX(0);
        return l;
    }

    /**
     * Hidden until GitHub has a newer release than this app (checked once, in the background, at start): then a
     * green bar with the new version and a button to its release page. Nothing is downloaded by the app.
     */
    JPanel updateBanner() {
        JPanel bar = new JPanel(new BorderLayout(12, 0)) { // as tall as its wrapped text needs
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        bar.setBackground(ACCENT_SOFT);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, ACCENT), new EmptyBorder(10, 12, 10, 12)));
        bar.setAlignmentX(0);
        bar.setVisible(false);
        JLabel text = new WrapLabel(" ");
        text.setForeground(TEXT);
        text.setFont(text.getFont().deriveFont(13f));
        bar.add(text, BorderLayout.CENTER);
        JButton get = primary("Get the update");
        bar.add(get, BorderLayout.EAST);
        String have = Updates.current();
        if (have == null) return bar; // run from source: nothing to compare with
        Thread t = new Thread(() -> {
            Updates.Release r = Updates.latest();
            if (r == null || !Updates.newer(r.version(), have)) return;
            SwingUtilities.invokeLater(() -> {
                text.setText("<html><b>A newer version is out: " + escape(r.version()) + "</b> (you have " + escape(have)
                        + "). Download the new installer from GitHub and run it; your converted files stay.</html>");
                get.addActionListener(e -> browse(r.page()));
                bar.setVisible(true);
                Container parent = bar.getParent();
                if (parent != null) {
                    parent.add(Box.createVerticalStrut(12), parent.getComponentZOrder(bar) + 1);
                    parent.revalidate();
                    parent.repaint();
                }
            });
        }, "update-check");
        t.setDaemon(true);
        t.start();
        return bar;
    }

    static JComponent notice(String title, String text) {
        JLabel l = new WrapLabel("<html><b>" + escape(title) + "</b><br>" + escape(text) + "</html>");
        l.setForeground(TEXT);
        l.setFont(l.getFont().deriveFont(13f));
        l.setOpaque(true);
        l.setBackground(WARN_SOFT);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, WARN), new EmptyBorder(10, 12, 10, 12)));
        l.setAlignmentX(0);
        return l;
    }

    static JButton primary(String text) {
        JButton b = new JButton(text);
        b.setBackground(ACCENT);
        b.setForeground(Color.WHITE);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(10, 20, 10, 20));
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(0);
        b.addPropertyChangeListener("enabled", e -> b.setBackground(b.isEnabled() ? ACCENT : new Color(0x9fbfb5)));
        return b;
    }

    static JButton ghost(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setAlignmentX(0);
        return b;
    }

    static JLabel link(String text, String url) {
        JLabel l = label(text, ACCENT, 13f, false);
        l.setText("<html><u>" + escape(text) + "</u></html>");
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { browse(url); }
        });
        return l;
    }

    static JScrollPane scroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(new LineBorder(BORDER));
        return s;
    }

    interface FileTaken { void take(File f); }

    /** A dashed box you can drop a file on or click to pick one. */
    JComponent dropZone(String title, String sub, String ending, FileTaken taken, JLabel chosen) {
        JPanel z = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BORDER.darker());
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {8f, 6f}, 0f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
                g2.dispose();
            }
        };
        z.setBackground(SURFACE2);
        z.setPreferredSize(new Dimension(600, 130));
        z.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        z.setAlignmentX(0);
        z.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel inner = column();
        JLabel arrow = label("⬆", ACCENT, 22f, true);
        JLabel t = label(title, TEXT, 15f, true);
        JLabel s = label(sub, MUTED, 13f, false);
        for (JComponent c : List.of(arrow, t, s, chosen)) { c.setAlignmentX(0.5f); inner.add(c); }
        z.add(inner);

        Runnable pick = () -> {
            JFileChooser c = new JFileChooser();
            c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(ending + " files", ending.substring(1)));
            if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) accept(c.getSelectedFile(), ending, taken, chosen);
        };
        z.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { pick.run(); }
        });
        z.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport s) {
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) return false;
                    accept(files.get(0), ending, taken, chosen);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        return z;
    }

    void accept(File f, String ending, FileTaken taken, JLabel chosen) {
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(ending)) {
            chosen.setText("That's not a " + ending + " file.");
            return;
        }
        taken.take(f);
    }

    static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}
        xdgOpen(url);
    }

    static void openFolder(Path p) {
        try {
            Path dir = Files.isDirectory(p) ? p : p.getParent();
            Files.createDirectories(dir);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir.toFile());
                    return;
                }
            } catch (Exception ignored) {}
            xdgOpen(dir.toString());
        } catch (Exception ignored) {}
    }

    /** Linux desktops where Java's Desktop API isn't available (no GNOME libraries): the desktop's own opener. */
    static void xdgOpen(String what) {
        if (File.separatorChar == '\\') return;
        try { new ProcessBuilder("xdg-open", what).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException ignored) {}
    }

    static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    void saveScreenshot(Path png) {
        try {
            BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            frame.getRootPane().paint(img.getGraphics());
            ImageIO.write(img, "png", png.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Just enough Markdown for the reports: headings, lists, code, bold, tables as rows. */
    static final class Markdown {
        static String toHtml(String md) {
            StringBuilder out = new StringBuilder();
            boolean inList = false, inCode = false;
            for (String line : md.replace("\r", "").split("\n")) {
                if (line.startsWith("```")) {
                    if (inList) { out.append("</ul>"); inList = false; }
                    out.append(inCode ? "</pre>" : "<pre>");
                    inCode = !inCode;
                    continue;
                }
                if (inCode) { out.append(escape(line)).append("\n"); continue; }
                boolean item = line.matches("\\s*([-*]|\\d+\\.)\\s+.*");
                if (!item && inList) { out.append("</ul>"); inList = false; }
                if (line.startsWith("#")) {
                    int n = Math.min(4, (int) line.chars().takeWhile(c -> c == '#').count());
                    out.append("<h").append(n + 1).append(">").append(inline(line.substring(n).strip())).append("</h").append(n + 1).append(">");
                } else if (item) {
                    if (!inList) { out.append("<ul>"); inList = true; }
                    out.append("<li>").append(inline(line.replaceFirst("\\s*([-*]|\\d+\\.)\\s+", ""))).append("</li>");
                } else if (line.matches("\\s*\\|[\\s:|-]+\\|\\s*")) {
                    // table divider: skip
                } else if (line.strip().startsWith("|")) {
                    out.append("<div>").append(inline(line.strip().replaceAll("^\\||\\|$", "").replace("|", " · "))).append("</div>");
                } else if (!line.isBlank()) {
                    out.append("<p>").append(inline(line)).append("</p>");
                }
            }
            if (inList) out.append("</ul>");
            if (inCode) out.append("</pre>");
            return out.toString();
        }

        static String inline(String s) {
            return escape(s).replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>").replaceAll("`([^`]+)`", "<code>$1</code>");
        }
    }
}
