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
 *   --lang <code>                                 sets the UI language (e.g. ar, hi, zh-CN)
 *   --selftest <mod.jar> <pack.zip> <out folder>   runs both converters without the window
 *   --make-mod <spec.json> <out folder> [game folder]  makes a mod as the "Make a mod" tab does (and installs it)
 *   --screenshot <file.png> [tab number] [example] saves a picture of the window and exits (example: fills
 *                                                  the "Make a mod" tab from that kit example)
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
    File modFile, packFile, resourcePackFile;

    JFrame frame;
    JTabbedPane tabs;
    JLabel modChosen, packChosen, resourcePackChosen, status, outLabel, resultTitle;
    JCheckBox isAuthor, install, installMod, installResourcePack;
    JTextField authorName;
    JButton convertMod, convertPack, convertResourcePack, openResult;
    JProgressBar progress;
    JEditorPane report;
    JTextArea log;
    JPanel resultPanel;
    Path lastResult;
    ModKitPanel kit;
    JComponent page;

    TranslatorApp(Path appDir) {
        this.appDir = appDir;
        this.outDir = Path.of(System.getProperty("user.home"), "Documents", "Horizon Translator");
    }

    public static void main(String[] args) throws Exception {
        Path appDir = appDir();
        String cliLang = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--lang") && i + 1 < args.length) {
                cliLang = args[i + 1];
                break;
            }
        }
        I18n.init(cliLang);

        if (args.length >= 1 && args[0].equals("--check-update")) {
            String have = Updates.current();
            Updates.Release r = Updates.latest();
            System.out.println("this app: " + (have == null ? "(no version: run from source)" : have));
            System.out.println("on GitHub: " + (r == null ? "(couldn't check)" : r.version() + "  " + r.page()));
            System.out.println("newer one available: " + (r != null && have != null && Updates.newer(r.version(), have)));
            System.exit(0);
        }
        if (args.length >= 3 && args[0].equals("--make-mod")) { // what "Make a mod" > Make mod does, without the window
            Result r = ModKitPanel.make(appDir, Json.map(Json.parse(Text.read(Path.of(args[1])))), Path.of(args[2]),
                    args.length >= 4 ? Path.of(args[3]) : null);
            System.out.println(r.ok() + " | " + r.title() + " | " + r.where());
            System.exit(r.ok() ? 0 : 1);
        }
        if (args.length >= 4 && args[0].equals("--selftest")) {
            Path rp = args.length >= 5 ? Path.of(args[4]) : null;
            System.exit(selfTest(appDir, Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), rp));
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        I18n.setupFonts();
        TranslatorApp app = new TranslatorApp(appDir);
        SwingUtilities.invokeAndWait(app::build);

        int shotIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--screenshot")) { shotIndex = i; break; }
        }
        if (shotIndex != -1 && shotIndex + 1 < args.length) {
            Path shotPath = Path.of(args[shotIndex + 1]);
            if (shotIndex + 2 < args.length) {
                int tabIdx = Integer.parseInt(args[shotIndex + 2]);
                SwingUtilities.invokeAndWait(() -> app.tabs.setSelectedIndex(tabIdx));
            }
            if (shotIndex + 3 < args.length) {
                int exIdx = Integer.parseInt(args[shotIndex + 3]);
                SwingUtilities.invokeAndWait(() -> app.kit.useExample(exIdx));
            }
            Thread.sleep(shotIndex + 2 < args.length ? 7000 : 800); // time for the Support tab's fetch
            SwingUtilities.invokeAndWait(() -> app.saveScreenshot(shotPath));
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
        frame = new JFrame(I18n.t("app.title"));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        try {
            BufferedImage icon = ImageIO.read(appDir.resolve("logo.png").toFile());
            if (icon != null) frame.setIconImage(icon);
        } catch (IOException ignored) {}

        rebuildWindow();

        frame.setSize(920, 820);
        frame.setMinimumSize(new Dimension(640, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    void rebuildWindow() {
        int selectedTab = tabs != null ? tabs.getSelectedIndex() : 0;
        I18n.setupFonts();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(header(), BorderLayout.NORTH);

        JPanel body = new FitWidth();
        page = body;
        body.setBorder(new EmptyBorder(16, 20, 20, 20));
        JPanel update = updateBanner();
        body.add(update);
        JComponent experimental;
        body.add(experimental = notice(I18n.t("notice.experimental.title"), I18n.t("notice.experimental.desc")));
        body.add(Box.createVerticalStrut(12));

        tabs = new JTabbedPane() { // as tall as the open tab, not the tallest one
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        tabs.setFont(I18n.uiFont(Font.BOLD, 14f));
        tabs.addTab(I18n.t("tab.mods"), onlyWhenOpen(modPanel()));
        tabs.addTab(I18n.t("tab.shaders"), onlyWhenOpen(shaderTab()));
        tabs.addTab(I18n.t("tab.resourcepacks"), onlyWhenOpen(resourcePackPanel()));
        kit = new ModKitPanel(this);
        tabs.addTab(I18n.t("tab.kit"), onlyWhenOpen(kit.panel()));
        tabs.addTab(I18n.t("tab.get_mods"), onlyWhenOpen(GetMods.panel(this)));
        tabs.addTab("<html>" + escape(I18n.t("tab.support")) + "</html>", onlyWhenOpen(Support.panel(appDir)));
        // The converter's warning is about converted mods: only on the Mods tab.
        tabs.addChangeListener(e -> { experimental.setVisible(tabs.getSelectedIndex() == 0); tabs.revalidate(); tabs.repaint(); });
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
        status = label(I18n.t("status.local_only"), MUTED, 13f, false);
        body.add(status);
        body.add(Box.createVerticalStrut(10));
        body.add(resultPanel());

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        if (selectedTab >= 0 && selectedTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(selectedTab);
        }
        frame.applyComponentOrientation(I18n.isRtl() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
        frame.revalidate();
        frame.repaint();
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
        JLabel title = label(I18n.t("app.title"), TEXT, 18f, true);
        try {
            BufferedImage logo = ImageIO.read(appDir.resolve("logo.png").toFile());
            if (logo != null) title.setIcon(new ImageIcon(logo.getScaledInstance(28, 28, Image.SCALE_SMOOTH)));
            title.setIconTextGap(10);
        } catch (IOException ignored) {}
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        left.setOpaque(false);
        left.add(title);
        String version = Updates.current();
        if (version != null) {
            left.add(Box.createHorizontalStrut(10));
            left.add(label(version, MUTED, 12f, false));
        }
        h.add(left, BorderLayout.LINE_START);

        JPanel links = new JPanel(new FlowLayout(FlowLayout.TRAILING, 14, 0));
        links.setOpaque(false);

        JComboBox<I18n.Language> langBox = new JComboBox<>(I18n.LANGUAGES.toArray(new I18n.Language[0]));
        langBox.setSelectedItem(I18n.currentLanguage());
        langBox.setMaximumSize(new Dimension(140, 26));
        langBox.setFont(I18n.uiFont(Font.PLAIN, 12f));
        langBox.setFocusable(false);
        langBox.addActionListener(e -> {
            I18n.Language chosen = (I18n.Language) langBox.getSelectedItem();
            if (chosen != null && !chosen.code().equals(I18n.lang())) {
                I18n.setLang(chosen.code());
                rebuildWindow();
            }
        });
        links.add(langBox);

        links.add(link(I18n.t("nav.how_it_works"), REPO_URL + "/blob/main/docs/HOW-IT-WORKS.md"));
        links.add(link(I18n.t("nav.report_issue"), REPO_URL + "/issues/new/choose"));
        links.add(link(I18n.t("nav.discord"), DISCORD_URL));
        JLabel support = label("<html><u>" + escape(I18n.t("nav.support")) + "</u></html>", ACCENT, 13f, true);
        support.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        support.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { tabs.setSelectedIndex(tabs.getTabCount() - 1); }
        });
        links.add(support);
        h.add(links, BorderLayout.LINE_END);
        return h;
    }

    JComponent modPanel() {
        JPanel p = card();
        modChosen = label(" ", ACCENT, 14f, true);
        p.add(dropZone(I18n.t("mod.drop.title"), I18n.t("drop.sub.click"), ".jar", f -> {
            modFile = f;
            modChosen.setText(f.getName() + "  ·  " + String.format(Locale.ROOT, "%.1f MB", f.length() / 1048576.0));
            convertMod.setEnabled(true);
        }, modChosen));
        p.add(Box.createVerticalStrut(12));
        p.add(wrapLabel(I18n.t("mod.licence_notice"), MUTED));
        p.add(Box.createVerticalStrut(8));
        isAuthor = new JCheckBox(I18n.t("mod.author.check"));
        isAuthor.setOpaque(false);
        isAuthor.setAlignmentX(0);
        p.add(isAuthor);
        JPanel nameRow = row();
        nameRow.add(label(I18n.t("mod.author.label"), MUTED, 13f, false));
        authorName = new JTextField(18);
        authorName.setMaximumSize(new Dimension(260, 30));
        nameRow.add(authorName);
        nameRow.setVisible(false);
        isAuthor.addActionListener(e -> { nameRow.setVisible(isAuthor.isSelected()); p.revalidate(); });
        p.add(nameRow);
        installMod = new JCheckBox(I18n.t("mod.install.check"));
        installMod.setOpaque(false);
        installMod.setSelected(true);
        installMod.setAlignmentX(0);
        p.add(installMod);
        p.add(Box.createVerticalStrut(12));
        convertMod = primary(I18n.t("mod.btn.convert"));
        convertMod.setEnabled(false);
        convertMod.addActionListener(e -> convertModClicked());
        p.add(convertMod);
        p.add(Box.createVerticalStrut(6));
        p.add(agreeLine());
        return p;
    }

    /** The Shader packs tab: the converter, then the list of packs that convert well. */
    JComponent shaderTab() {
        JPanel t = column();
        t.add(shaderPanel());
        t.add(Box.createVerticalStrut(12));
        t.add(ShaderList.panel(this));
        return t;
    }

    void choosePack(File f) {
        packFile = f;
        packChosen.setText(f.getName() + "  ·  " + String.format(Locale.ROOT, "%.1f MB", f.length() / 1048576.0));
        convertPack.setEnabled(true);
    }

    /** Convert a pack picked from the "Best shader packs" list, and bring the progress line into view. */
    void convertPackFile(File f) {
        if (progress.isVisible()) return; // one conversion at a time
        choosePack(f);
        convertPackClicked();
        SwingUtilities.invokeLater(() -> status.scrollRectToVisible(new Rectangle(0, 0, status.getWidth(), status.getHeight())));
    }

    JComponent shaderPanel() {
        JPanel p = card();
        packChosen = label(" ", ACCENT, 14f, true);
        p.add(dropZone(I18n.t("shader.drop.title"), I18n.t("shader.drop.sub"), ".zip", this::choosePack, packChosen));
        p.add(Box.createVerticalStrut(12));
        p.add(wrapLabel(I18n.t("shader.notice"), MUTED));
        p.add(Box.createVerticalStrut(8));
        install = new JCheckBox(I18n.t("shader.install.check"));
        install.setOpaque(false);
        install.setSelected(true);
        install.setAlignmentX(0);
        p.add(install);
        p.add(Box.createVerticalStrut(12));
        convertPack = primary(I18n.t("shader.btn.convert_pack"));
        convertPack.setEnabled(false);
        convertPack.addActionListener(e -> convertPackClicked());
        p.add(convertPack);
        p.add(Box.createVerticalStrut(6));
        p.add(agreeLine());
        return p;
    }

    void chooseResourcePack(File f) {
        if (f == null || !f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) return;
        resourcePackFile = f;
        resourcePackChosen.setText(f.getName() + "  ·  " + String.format(Locale.ROOT, "%.1f MB", f.length() / 1048576.0));
        convertResourcePack.setEnabled(true);
    }

    JComponent resourcePackPanel() {
        JPanel p = card();
        resourcePackChosen = label(" ", ACCENT, 14f, true);
        p.add(dropZone(I18n.t("resourcepack.drop.title"), I18n.t("resourcepack.drop.sub"), ".zip", this::chooseResourcePack, resourcePackChosen));
        p.add(Box.createVerticalStrut(12));
        p.add(wrapLabel(I18n.t("resourcepack.notice"), MUTED));
        p.add(Box.createVerticalStrut(8));
        installResourcePack = new JCheckBox(I18n.t("resourcepack.install.check"));
        installResourcePack.setOpaque(false);
        installResourcePack.setSelected(true);
        installResourcePack.setAlignmentX(0);
        p.add(installResourcePack);
        p.add(Box.createVerticalStrut(12));
        convertResourcePack = primary(I18n.t("resourcepack.btn.convert_pack"));
        convertResourcePack.setEnabled(false);
        convertResourcePack.addActionListener(e -> convertResourcePackClicked());
        p.add(convertResourcePack);
        p.add(Box.createVerticalStrut(6));
        p.add(agreeLine());
        return p;
    }

    JComponent outputRow() {
        JPanel r = row();
        outLabel = label(I18n.t("output.saved_in", outDir), MUTED, 13f, false);
        r.add(outLabel);
        r.add(Box.createHorizontalStrut(10));
        JButton change = ghost(I18n.t("output.btn.change"));
        change.addActionListener(e -> {
            JFileChooser c = new JFileChooser(outDir.toFile());
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                outDir = c.getSelectedFile().toPath();
                outLabel.setText(I18n.t("output.saved_in", outDir));
            }
        });
        r.add(change);
        r.add(Box.createHorizontalStrut(6));
        JButton open = ghost(I18n.t("output.btn.open"));
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
        openResult = primary(I18n.t("result.btn.open"));
        openResult.addActionListener(e -> { if (lastResult != null) openFolder(lastResult); });
        actions.add(openResult);
        actions.add(Box.createHorizontalStrut(8));
        JButton issue = ghost(I18n.t("result.btn.issue"));
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
        out.addTab(I18n.t("result.tab.report"), scroll(report));
        out.addTab(I18n.t("result.tab.log"), scroll(log));
        out.setPreferredSize(new Dimension(800, 360));
        out.setAlignmentX(0);
        resultPanel.add(out);
        resultPanel.setVisible(false);
        return resultPanel;
    }

    JComponent footer() {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEADING, 14, 6));
        f.setBackground(SURFACE2);
        f.add(label(I18n.t("footer.disclaimer"), MUTED, 12f, false));
        f.add(link(I18n.t("footer.terms"), SITE_TERMS));
        f.add(link(I18n.t("footer.source"), REPO_URL));
        return f;
    }

    JComponent agreeLine() {
        JPanel r = row();
        r.add(label(I18n.t("agree.prefix"), MUTED, 12f, false));
        r.add(link(I18n.t("agree.terms"), SITE_TERMS));
        r.add(label(I18n.t("agree.suffix"), MUTED, 12f, false));
        return r;
    }

    // ------------------------------------------------------------------ converting

    void convertModClicked() {
        String author = isAuthor.isSelected() ? authorName.getText().strip() : "";
        if (isAuthor.isSelected() && author.isEmpty()) { authorName.requestFocus(); return; }
        Path jar = modFile.toPath();
        boolean inst = installMod.isSelected();
        run(I18n.t("mod.status.converting", jar.getFileName()),
                () -> convertMod(appDir, jar, author, inst, outDir));
    }

    void convertPackClicked() {
        Path pack = packFile.toPath();
        boolean inst = install.isSelected();
        run(I18n.t("shader.status.converting", pack.getFileName()),
                () -> convertPack(appDir, pack, inst, outDir));
    }

    void convertResourcePackClicked() {
        Path pack = resourcePackFile.toPath();
        boolean inst = installResourcePack.isSelected();
        run(I18n.t("resourcepack.status.converting", pack.getFileName()),
                () -> convertResourcePack(appDir, pack, inst, outDir));
    }

    interface Job { Result call() throws Exception; }

    record Result(boolean ok, String title, String report, String log, Path where) {}

    void run(String what, Job job) {
        convertMod.setEnabled(false);
        convertPack.setEnabled(false);
        if (convertResourcePack != null) convertResourcePack.setEnabled(false);
        if (kit != null && kit.make != null) kit.make.setEnabled(false);
        progress.setVisible(true);
        status.setText(what);
        status.setForeground(MUTED);
        new SwingWorker<Result, Void>() {
            @Override protected Result doInBackground() throws Exception { return job.call(); }
            @Override protected void done() {
                progress.setVisible(false);
                convertMod.setEnabled(modFile != null);
                convertPack.setEnabled(packFile != null);
                if (convertResourcePack != null) convertResourcePack.setEnabled(resourcePackFile != null);
                if (kit != null && kit.make != null) kit.make.setEnabled(true);
                Result r;
                try { r = get(); } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    r = new Result(false, I18n.t("status.error", c.getMessage()), "", String.valueOf(c), null);
                }
                show(r);
            }
        }.execute();
    }

    void show(Result r) {
        status.setText(r.ok() ? I18n.t("status.done", r.where()) : r.title());
        status.setForeground(r.ok() ? OK : BAD);
        resultTitle.setText(r.title());
        resultTitle.setForeground(r.ok() ? TEXT : BAD);
        lastResult = r.where();
        openResult.setVisible(r.where() != null);
        report.setText("<html><body style='font-family:" + I18n.fontFamily() + ",sans-serif;font-size:11px'>" + Markdown.toHtml(r.report()) + "</body></html>");
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
            List<String> cmd = new ArrayList<>(List.of(javaExe().toString(),
                    // UTF-8 output whatever the Windows language; the log is read back as UTF-8 (issue #2).
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dhorizonport.inprocess=true",
                    "-Dhorizonport.srcdir=" + work.resolve("decompiled"), "-cp", classpath(appDir), "horizonport.Main",
                    "port", jar.toString(), "--horizon-src", appDir.resolve("horizon-src.zip").toString(),
                    "--mappings", appDir.resolve("mappings/horizon-mappings.json").toString(), "--out", bundle.toString()));
            if (!author.isEmpty()) { cmd.add("--i-am-the-author"); cmd.add(author); }
            if (install) cmd.add("--install");
            Proc p = exec(cmd, work, 10);
            if (p.code() == 3) return new Result(false, I18n.t("mod.result.licence_denied"), p.out(), p.out(), null);
            if (p.code() != 0 || !Files.isDirectory(bundle))
                return new Result(false, p.timedOut() ? I18n.t("result.timeout") : I18n.t("mod.result.failed"), "", p.out(), null);
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
                String title = added
                        ? I18n.t("mod.result.full_port_installed", hnmod.getFileName())
                        : I18n.t("mod.result.full_port_manual", hnmod.getFileName());
                return new Result(true, title, report, p.out(), hnmod);
            }
            return new Result(true, I18n.t("mod.result.partial_port"), report, p.out(), dest);
        } finally {
            deleteTree(work); // includes the decompiled Java
        }
    }

    static Result convertPack(Path appDir, Path pack, boolean install, Path outDir) throws Exception {
        Path exe = appDir.resolve("horizon-shaderport.exe");
        if (!Files.isRegularFile(exe)) exe = appDir.resolve("horizon-shaderport");
        if (!Files.isRegularFile(exe)) exe = Path.of("shader-converter/target/release/horizon-shaderport.exe");
        if (!Files.isRegularFile(exe)) exe = Path.of("shader-converter/target/release/horizon-shaderport");
        if (!Files.isRegularFile(exe)) exe = Path.of("C:/dev/ht-build/target/release/horizon-shaderport.exe");
        if (!Files.isRegularFile(exe)) exe = Path.of("C:/dev/ht-build/target/release/horizon-shaderport");
        Files.createDirectories(outDir);
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "port", pack.toString(), "--out", outDir.toString()));
        if (install) cmd.add("--install");
        Proc p = exec(cmd, outDir, 20);
        String stem = pack.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
        Path zip = outDir.resolve(stem + "-horizon.zip");
        if (p.code() != 0 || !Files.isRegularFile(zip))
            return new Result(false, p.timedOut() ? I18n.t("result.timeout") : I18n.t("shader.result.failed"), "", p.out(), null);
        String after = p.out().lines().filter(l -> l.contains("after:")).findFirst().orElse("Converted").strip();
        String rep = read(outDir.resolve(stem + "-horizon.md"));
        String installed = p.out().lines().filter(l -> l.contains("installed")).findFirst().orElse("");
        String title = installed.isEmpty() ? after : I18n.t("shader.result.installed", after);
        return new Result(true, title, rep, p.out(), zip);
    }

    static Result convertResourcePack(Path appDir, Path pack, boolean install, Path outDir) throws Exception {
        Path exe = appDir.resolve("horizon-resourcepackport.exe");
        if (!Files.isRegularFile(exe)) exe = appDir.resolve("horizon-resourcepackport");
        if (!Files.isRegularFile(exe)) exe = Path.of("resourcepack-converter/target/release/horizon-resourcepackport.exe");
        if (!Files.isRegularFile(exe)) exe = Path.of("resourcepack-converter/target/release/horizon-resourcepackport");
        if (!Files.isRegularFile(exe)) exe = Path.of("C:/dev/ht-build/target/release/horizon-resourcepackport.exe");
        if (!Files.isRegularFile(exe)) exe = Path.of("C:/dev/ht-build/target/release/horizon-resourcepackport");
        Files.createDirectories(outDir);
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "port", pack.toString(), "--out", outDir.toString()));
        if (install) cmd.add("--install");
        Proc p = exec(cmd, outDir, 20);
        String stem = pack.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
        Path zip = outDir.resolve(stem + "-horizon.zip");
        if (p.code() != 0 || !Files.isRegularFile(zip))
            return new Result(false, p.timedOut() ? I18n.t("result.timeout") : I18n.t("shader.result.failed"), "", p.out(), null);
        String after = p.out().lines().filter(l -> l.contains("after:")).findFirst().orElse("Converted").strip();
        String rep = read(outDir.resolve(stem + "-horizon.md"));
        String installed = p.out().lines().filter(l -> l.contains("installed")).findFirst().orElse("");
        String title = installed.isEmpty() ? after : I18n.t("shader.result.installed", after);
        return new Result(true, title, rep, p.out(), zip);
    }

    static int selfTest(Path appDir, Path jar, Path pack, Path out) throws Exception {
        return selfTest(appDir, jar, pack, out, null);
    }

    static int selfTest(Path appDir, Path jar, Path pack, Path out, Path resourcePack) throws Exception {
        Result m = convertMod(appDir, jar, "", false, out);
        System.out.println("mod: " + m.ok() + " | " + m.title() + " | " + m.where());
        Result s = convertPack(appDir, pack, false, out);
        System.out.println("shader: " + s.ok() + " | " + s.title() + " | " + s.where());
        Path rpZip = resourcePack;
        if (rpZip == null) {
            Files.createDirectories(out);
            rpZip = out.resolve("test-resourcepack.zip");
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(rpZip))) {
                zos.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
                byte[] meta = "{\"pack\":{\"pack_format\":3,\"description\":\"Test Pack\"}}".getBytes(StandardCharsets.UTF_8);
                zos.write(meta);
                zos.closeEntry();
            }
        }
        Result rp = convertResourcePack(appDir, rpZip, false, out);
        System.out.println("resourcepack: " + rp.ok() + " | " + rp.title() + " | " + rp.where());
        return m.ok() && s.ok() && rp.ok() ? 0 : 1;
    }

    record Proc(int code, String out, boolean timedOut) {}

    static Proc exec(List<String> cmd, Path dir, int minutes) throws Exception {
        Path logFile = Files.createTempFile("horizon-translator-log", ".txt");
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean done = p.waitFor(minutes, TimeUnit.MINUTES);
            if (!done) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); }
            return new Proc(done ? p.exitValue() : -1, text(Files.readAllBytes(logFile)), !done);
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

    static String read(Path p) throws IOException { return Files.isRegularFile(p) ? text(Files.readAllBytes(p)) : ""; }

    /** UTF-8 that never throws: bytes that aren't UTF-8 become U+FFFD instead of a MalformedInputException. */
    static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

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
            setFont(I18n.uiFont(Font.PLAIN, 13f));
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
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)) {
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
        l.setFont(I18n.uiFont(bold ? Font.BOLD : Font.PLAIN, size));
        l.setAlignmentX(0);
        return l;
    }

    static JComponent wrapLabel(String text, Color color) {
        JLabel l = new WrapLabel("<html>" + escape(text) + "</html>");
        l.setForeground(color);
        l.setFont(I18n.uiFont(Font.PLAIN, 13f));
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
        text.setFont(I18n.uiFont(Font.PLAIN, 13f));
        bar.add(text, BorderLayout.CENTER);
        JButton get = primary(I18n.t("update.btn.get"));
        bar.add(get, BorderLayout.LINE_END);
        String have = Updates.current();
        if (have == null) return bar; // run from source: nothing to compare with
        Thread t = new Thread(() -> {
            Updates.Release r = Updates.latest();
            if (r == null || !Updates.newer(r.version(), have)) return;
            SwingUtilities.invokeLater(() -> {
                text.setText(I18n.t("update.banner", escape(r.version()), escape(have)));
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
        l.setFont(I18n.uiFont(Font.PLAIN, 13f));
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
        b.setFont(I18n.uiFont(Font.BOLD, 14f));
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
        b.setFont(I18n.uiFont(Font.PLAIN, 13f));
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
            c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(I18n.t("drop.filter", ending), ending.substring(1)));
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
            chosen.setText(I18n.t("drop.wrong_file", ending));
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
            BufferedImage img;
            if (Boolean.getBoolean("shot.full")) { // the whole page, not just what the window shows
                page.setSize(page.getWidth(), page.getPreferredSize().height);
                page.doLayout();
                page.validate();
                img = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics g = img.getGraphics();
                g.setColor(BG);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                page.paint(g);
            } else {
                img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                frame.getRootPane().paint(img.getGraphics());
            }
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
            return escape(s).replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>").replaceAll("`([^`]+)`", "<code>$1</code>")
                    .replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        }
    }
}
