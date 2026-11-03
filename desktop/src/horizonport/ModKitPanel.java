package horizonport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static horizonport.TranslatorApp.*;

/**
 * The "Make a mod" tab: a builder for new Horizon native mods (ModKit in horizon-port makes them), the kit's examples,
 * the guide, and "Check and pack" for a mod folder whose code was edited by hand.
 */
final class ModKitPanel {
    private final TranslatorApp app;

    private JTextField name, modId, version, author;
    /** The id follows the name until the player types one, or a mod with its own id is opened. */
    private boolean idFollowsName = true, settingId;
    private JTextArea description;
    private JLabel idLabel, problems;
    private JCheckBox hudOn, zoomOn, welcomeOn, install;
    private JComboBox<String> hudCorner, hudKey, zoomKey;
    private JTextArea hudLines, welcome;
    private JTextField hudColor;
    private JCheckBox hudBg, zoomHold, zoomSmooth, zoomScroll;
    private JSpinner zoomFactor;
    private Rows commands, keys, timers;
    private JComboBox<Example> examples;
    JButton make;

    ModKitPanel(TranslatorApp app) { this.app = app; }

    // ------------------------------------------------------------------ keys

    /** The keys the builder offers: what players see, and the vanilla key name. */
    static final String[][] KEYS;
    static {
        List<String[]> k = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) k.add(new String[] {String.valueOf(c), "key.keyboard." + Character.toLowerCase(c)});
        for (char c = '0'; c <= '9'; c++) k.add(new String[] {String.valueOf(c), "key.keyboard." + c});
        for (int i = 1; i <= 12; i++) k.add(new String[] {"F" + i, "key.keyboard.f" + i});
        String[][] more = {
                {"Left Alt", "key.keyboard.left.alt"}, {"Right Alt", "key.keyboard.right.alt"}, {"Left Shift", "key.keyboard.left.shift"},
                {"Left Ctrl", "key.keyboard.left.control"}, {"Right Ctrl", "key.keyboard.right.control"}, {"Tab", "key.keyboard.tab"},
                {"Caps Lock", "key.keyboard.caps.lock"}, {"` (grave)", "key.keyboard.grave.accent"}, {"- (minus)", "key.keyboard.minus"},
                {"= (equals)", "key.keyboard.equal"}, {"[", "key.keyboard.left.bracket"}, {"]", "key.keyboard.right.bracket"},
                {"; (semicolon)", "key.keyboard.semicolon"}, {"' (apostrophe)", "key.keyboard.apostrophe"}, {", (comma)", "key.keyboard.comma"},
                {". (period)", "key.keyboard.period"}, {"/ (slash)", "key.keyboard.slash"}, {"\\ (backslash)", "key.keyboard.backslash"},
                {"Up", "key.keyboard.up"}, {"Down", "key.keyboard.down"}, {"Left", "key.keyboard.left"}, {"Right", "key.keyboard.right"},
                {"Insert", "key.keyboard.insert"}, {"Delete", "key.keyboard.delete"}, {"Home", "key.keyboard.home"}, {"End", "key.keyboard.end"},
                {"Page Up", "key.keyboard.page.up"}, {"Page Down", "key.keyboard.page.down"},
                {"Middle mouse button", "key.mouse.middle"}, {"Mouse button 4", "key.mouse.4"}, {"Mouse button 5", "key.mouse.5"},
        };
        k.addAll(List.of(more));
        for (int i = 0; i <= 9; i++) k.add(new String[] {"Keypad " + i, "key.keyboard.keypad." + i});
        KEYS = k.toArray(new String[0][]);
    }

    static final String NO_KEY = "(no key)";

    static JComboBox<String> keyBox(boolean optional, String chosen) {
        JComboBox<String> b = new JComboBox<>();
        if (optional) b.addItem(NO_KEY);
        for (String[] k : KEYS) b.addItem(k[0]);
        setKey(b, chosen);
        b.setMaximumRowCount(16);
        b.setMaximumSize(new Dimension(170, 30));
        return b;
    }

    static void setKey(JComboBox<String> b, String id) {
        for (String[] k : KEYS) if (k[1].equals(id)) { b.setSelectedItem(k[0]); return; }
        b.setSelectedIndex(0);
    }

    static String keyOf(JComboBox<String> b) {
        Object v = b.getSelectedItem();
        for (String[] k : KEYS) if (k[0].equals(v)) return k[1];
        return "";
    }

    static final String[] ACTIONS = {"Say in chat", "Show / hide the HUD box", "Zoom in / out"};
    static final String[] ACTION_IDS = {"say", "toggle_hud", "toggle_zoom"};

    // ------------------------------------------------------------------ the page

    JComponent panel() {
        JPanel p = card();
        p.add(label("Make your own Horizon mod", TEXT, 17f, true));
        p.add(Box.createVerticalStrut(4));
        p.add(wrapLabel("Pick what your mod does, fill in the boxes and press Make mod. You get a ready-to-install .hnmod and the "
                + "mod's Rust code, which you can change later if you want to go further. The mods work on newer Horizon versions too: "
                + "they only use the parts of the game Horizon keeps stable for mods.", MUTED));
        p.add(Box.createVerticalStrut(10));

        JPanel top = row();
        top.add(label("Start from: ", TEXT, 13f, true));
        examples = new JComboBox<>(loadExamples().toArray(new Example[0]));
        examples.setMaximumSize(new Dimension(260, 30));
        top.add(examples);
        top.add(Box.createHorizontalStrut(6));
        JButton use = ghost("Use");
        use.addActionListener(e -> { if (examples.getSelectedItem() instanceof Example ex) load(ex.spec()); });
        top.add(use);
        top.add(Box.createHorizontalStrut(14));
        JButton open = ghost("Open a mod I made…");
        open.addActionListener(e -> openProject());
        top.add(open);
        top.add(Box.createHorizontalStrut(14));
        JButton guide = ghost("Guide");
        guide.addActionListener(e -> showGuide());
        top.add(guide);
        p.add(top);
        p.add(Box.createVerticalStrut(14));

        // The basics.
        JPanel basics = grid();
        name = new JTextField("My First Mod", 24);
        version = new JTextField("1.0.0", 10);
        author = new JTextField("", 18);
        description = area(2, "A new Horizon mod.");
        addRow(basics, "Name", name);
        modId = new JTextField(20);
        modId.setToolTipText("Horizon tells mods apart by their id: keep it the same for new versions of the same mod.");
        idLabel = label(" ", MUTED, 12f, false);
        JPanel idRow = row();
        idRow.add(modId);
        idRow.add(Box.createHorizontalStrut(8));
        idRow.add(idLabel);
        addRow(basics, "Mod id", idRow);
        addRow(basics, "Version", version);
        addRow(basics, "Made by", author);
        addRow(basics, "Description", new JScrollPane(description));
        p.add(basics);
        name.getDocument().addDocumentListener(onChange(this::refreshId));
        modId.getDocument().addDocumentListener(onChange(() -> {
            if (!settingId) idFollowsName = false;
            SwingUtilities.invokeLater(this::refreshId);
        }));
        refreshId();
        p.add(Box.createVerticalStrut(8));
        p.add(placeholderHelp());

        // HUD box.
        JPanel hud = grid();
        hudCorner = new JComboBox<>(new String[] {"Top left", "Top right", "Bottom left", "Bottom right"});
        hudCorner.setMaximumSize(new Dimension(160, 30));
        hudLines = area(3, "XYZ {x} / {y} / {z}\nFacing {facing}");
        hudColor = new JTextField("ffffff", 7);
        JButton pick = ghost("Pick…");
        pick.addActionListener(e -> {
            Color c = JColorChooser.showDialog(app.frame, "Text colour", parseColor(hudColor.getText()));
            if (c != null) hudColor.setText(String.format("%06x", c.getRGB() & 0xFFFFFF));
        });
        JPanel colour = row();
        colour.add(hudColor);
        colour.add(Box.createHorizontalStrut(6));
        colour.add(pick);
        hudBg = check("Dark box behind the text", true);
        hudKey = keyBox(true, "key.keyboard.h");
        addRow(hud, "Corner", hudCorner);
        addRow(hud, "Lines", new JScrollPane(hudLines));
        addRow(hud, "Text colour", colour);
        addRow(hud, "", hudBg);
        addRow(hud, "Show/hide key", hudKey);
        hudOn = section(p, "HUD box", "Text in a corner of the screen, like coordinates or FPS. Players can hide it.", hud);

        // Commands.
        commands = new Rows("Add command", () -> commandRow(Map.of()));
        commandsOn = section(p, "Chat commands", "Commands players type in chat, like /hello. /<mod id> always lists your commands and keys.", commands.view);

        // Keys.
        keys = new Rows("Add key", () -> keyRow(Map.of()));
        keysOn = section(p, "Keys", "Keys that do something. Players can change them in Options > Mods.", keys.view);

        // Zoom.
        JPanel zoom = grid();
        zoomKey = keyBox(false, "key.keyboard.c");
        zoomFactor = new JSpinner(new SpinnerNumberModel(4.0, 1.5, 10.0, 0.5));
        zoomFactor.setMaximumSize(new Dimension(80, 30));
        zoomHold = check("Only while the key is held (otherwise press to zoom in, press again to zoom out)", true);
        zoomSmooth = check("Smooth, like the spyglass", true);
        zoomScroll = check("Mouse wheel zooms closer or further while zooming", true);
        addRow(zoom, "Key", zoomKey);
        addRow(zoom, "Times closer", zoomFactor);
        addRow(zoom, "", zoomHold);
        addRow(zoom, "", zoomSmooth);
        addRow(zoom, "", zoomScroll);
        zoomOn = section(p, "Zoom", "A zoom key, like OptiFine's.", zoom);

        // Messages.
        JPanel msg = column();
        welcomeOn = check("Welcome message", false);
        msg.add(welcomeOn);
        welcome = area(2, "Welcome back! You're in the {dimension}.");
        JPanel w = grid();
        addRow(w, "On joining", new JScrollPane(welcome));
        msg.add(w);
        timers = new Rows("Add timed message", () -> timerRow(Map.of()));
        msg.add(Box.createVerticalStrut(6));
        msg.add(label("Timed messages", TEXT, 13f, true));
        msg.add(timers.view);
        messagesOn = section(p, "Chat messages", "A message when you join a world, and reminders every so often.", msg);

        p.add(Box.createVerticalStrut(14));
        problems = new JLabel(" ");
        problems.setForeground(BAD);
        problems.setAlignmentX(0);
        problems.setVisible(false);
        p.add(problems);
        install = check("Add it to Horizon (then press Build on the launcher's Mods page)", true);
        p.add(install);
        p.add(Box.createVerticalStrut(10));
        JPanel actions = row();
        make = primary("Make mod");
        make.addActionListener(e -> makeClicked());
        actions.add(make);
        actions.add(Box.createHorizontalStrut(10));
        JButton pack = ghost("Check and pack a folder…");
        pack.setToolTipText("For a mod you changed by hand: checks its code (when Rust is installed) and makes a new .hnmod");
        pack.addActionListener(e -> packClicked());
        actions.add(pack);
        p.add(actions);
        p.add(Box.createVerticalStrut(6));
        p.add(label("Native mods run with the player's rights: only install mods whose code you trust.", MUTED, 12f, false));

        load(Map.of("name", "My First Mod", "version", "1.0.0", "description", "A new Horizon mod.",
                "commands", List.of(Map.of("name", "hello", "action", "say", "text", List.of("Hello from {name}! You're at {bx}, {by}, {bz} in the {dimension}.")))));
        return p;
    }

    private JCheckBox commandsOn, keysOn, messagesOn;

    /** A titled part of the form that its checkbox opens and closes. */
    private JCheckBox section(JPanel page, String title, String sub, JComponent body) {
        page.add(Box.createVerticalStrut(12));
        JPanel box = new JPanel() { // the page's full width, open or closed
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(true);
        box.setBackground(SURFACE2);
        box.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(10, 12, 10, 12)));
        box.setAlignmentX(0);
        JCheckBox on = new JCheckBox(title);
        on.setOpaque(false);
        on.setFont(on.getFont().deriveFont(Font.BOLD, 14f));
        on.setAlignmentX(0);
        box.add(on);
        JComponent help = label(sub, MUTED, 12f, false);
        box.add(help);
        JPanel inner = column();
        inner.setBorder(new EmptyBorder(8, 0, 0, 0));
        inner.add(body);
        inner.setAlignmentX(0);
        box.add(inner);
        on.addActionListener(e -> { inner.setVisible(on.isSelected()); box.revalidate(); app.tabs.revalidate(); });
        inner.setVisible(false);
        page.add(box);
        return on;
    }

    private static void setOn(JCheckBox on, boolean v) {
        if (on.isSelected() != v) on.doClick();
    }

    private static JPanel grid() {
        JPanel g = new JPanel(new GridBagLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        g.setOpaque(false);
        g.setAlignmentX(0);
        return g;
    }

    private static void addRow(JPanel g, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = g.getComponentCount() / 2;
        c.gridx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(3, 0, 3, 10);
        g.add(TranslatorApp.label(label, TEXT, 13f, false), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = field instanceof JScrollPane ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 0);
        g.add(field, c);
    }

    private static JTextArea area(int rows, String text) {
        JTextArea a = new JTextArea(text, rows, 40);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        return a;
    }

    private static JCheckBox check(String text, boolean on) {
        JCheckBox c = new JCheckBox(text, on);
        c.setOpaque(false);
        c.setAlignmentX(0);
        return c;
    }

    private static javax.swing.event.DocumentListener onChange(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }

    private void refreshId() {
        if (idFollowsName) setId(ModKit.idFrom(name.getText().strip()));
        String id = modId.getText().strip();
        idLabel.setText("its help command: /" + id);
    }

    private JComponent placeholderHelp() {
        StringBuilder b = new StringBuilder("<html><b>In any text</b> you can use ");
        for (int i = 0; i < ModKit.PLACEHOLDERS.length; i++) {
            String[] ph = ModKit.PLACEHOLDERS[i];
            b.append(i == 0 ? "" : ", ").append("<code>{").append(ph[0]).append("}</code> (").append(escape(ph[1])).append(")");
        }
        b.append(".</html>");
        JLabel l = new WrapLabel(b.toString());
        l.setForeground(MUTED);
        l.setFont(l.getFont().deriveFont(12f));
        l.setAlignmentX(0);
        return l;
    }

    // ------------------------------------------------------------------ lists of rows

    /** A row of a list: its fields and the spec part it stands for. */
    record Row(JComponent view, Supplier<Map<String, Object>> value) {}

    /** Commands, keys or timed messages: rows with a remove button each, and an add button. */
    final class Rows {
        final JPanel view = column();
        private final JPanel list = column();
        final List<Row> rows = new ArrayList<>();
        private final Supplier<Row> blank;

        Rows(String add, Supplier<Row> blank) {
            this.blank = blank;
            view.add(list);
            JButton b = ghost("+ " + add);
            b.addActionListener(e -> add(blank.get()));
            view.add(Box.createVerticalStrut(4));
            view.add(b);
        }

        void add(Row r) {
            JPanel line = row();
            line.add(r.view());
            line.add(Box.createHorizontalStrut(6));
            JButton x = ghost("Remove");
            line.add(x);
            line.setBorder(new EmptyBorder(3, 0, 3, 0));
            rows.add(r);
            list.add(line);
            x.addActionListener(e -> { rows.remove(r); list.remove(line); relayout(); });
            relayout();
        }

        void clear() { rows.clear(); list.removeAll(); relayout(); }

        List<Object> values() {
            List<Object> out = new ArrayList<>();
            for (Row r : rows) out.add(r.value().get());
            return out;
        }

        private void relayout() { view.revalidate(); view.repaint(); if (app.tabs != null) app.tabs.revalidate(); }
    }

    private static JComboBox<String> actionBox(String id, JTextField text) {
        JComboBox<String> a = new JComboBox<>(ACTIONS);
        int i = List.of(ACTION_IDS).indexOf(id);
        a.setSelectedIndex(Math.max(0, i));
        Runnable sync = () -> text.setEnabled(a.getSelectedIndex() == 0);
        a.addActionListener(e -> sync.run());
        sync.run();
        return a;
    }

    private static String text(Object o) {
        if (o instanceof List<?> l) return String.join(" | ", l.stream().map(String::valueOf).toList());
        return o == null ? "" : String.valueOf(o);
    }

    /** "a | b" in a one-line field is two chat lines. */
    private static List<String> lines(String field) {
        List<String> out = new ArrayList<>();
        for (String s : field.split("\\s*\\|\\s*")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    private Row commandRow(Map<?, ?> m) {
        JPanel r = row();
        JTextField n = new JTextField(String.valueOf(m.get("name") == null ? "" : m.get("name")), 10);
        JTextField t = new JTextField(text(m.get("text")), 30);
        t.setCaretPosition(0);
        t.setToolTipText("What it says in chat. Use | for a new line.");
        JComboBox<String> a = actionBox(String.valueOf(m.get("action")), t);
        r.add(TranslatorApp.label("/", TEXT, 14f, true));
        r.add(n);
        r.add(Box.createHorizontalStrut(6));
        r.add(a);
        r.add(Box.createHorizontalStrut(6));
        r.add(t);
        return new Row(r, () -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", n.getText().strip());
            v.put("action", ACTION_IDS[a.getSelectedIndex()]);
            v.put("text", lines(t.getText()));
            return v;
        });
    }

    private Row keyRow(Map<?, ?> m) {
        JPanel r = row();
        JTextField l = new JTextField(String.valueOf(m.get("label") == null ? "Say hi" : m.get("label")), 12);
        l.setToolTipText("The key's name in Options > Mods");
        JComboBox<String> k = keyBox(false, String.valueOf(m.get("key") == null ? "key.keyboard.g" : m.get("key")));
        JTextField t = new JTextField(text(m.get("text") == null ? "Hi!" : m.get("text")), 22);
        t.setCaretPosition(0);
        t.setToolTipText("What it says in chat. Use | for a new line.");
        JComboBox<String> a = actionBox(String.valueOf(m.get("action")), t);
        r.add(l);
        r.add(Box.createHorizontalStrut(6));
        r.add(k);
        r.add(Box.createHorizontalStrut(6));
        r.add(a);
        r.add(Box.createHorizontalStrut(6));
        r.add(t);
        return new Row(r, () -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("label", l.getText().strip());
            v.put("key", keyOf(k));
            v.put("action", ACTION_IDS[a.getSelectedIndex()]);
            v.put("text", lines(t.getText()));
            return v;
        });
    }

    private Row timerRow(Map<?, ?> m) {
        JPanel r = row();
        int every = m.get("every") instanceof Number n ? n.intValue() : 1800;
        JSpinner minutes = new JSpinner(new SpinnerNumberModel(Math.max(0.1, every / 60.0), 0.1, 1440.0, 1.0));
        minutes.setMaximumSize(new Dimension(80, 30));
        JTextField t = new JTextField(text(m.get("text") == null ? "Time for a break!" : m.get("text")), 32);
        t.setCaretPosition(0);
        t.setToolTipText("What it says in chat. Use | for a new line.");
        r.add(TranslatorApp.label("Every ", TEXT, 13f, false));
        r.add(minutes);
        r.add(TranslatorApp.label(" minutes: ", TEXT, 13f, false));
        r.add(t);
        return new Row(r, () -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("every", (int) Math.round(((Number) minutes.getValue()).doubleValue() * 60));
            v.put("text", lines(t.getText()));
            return v;
        });
    }

    // ------------------------------------------------------------------ the spec <-> the form

    private void setId(String id) {
        if (id.equals(modId.getText())) return;
        // Not inside the name field's own change event: Swing refuses to change a document while it notifies.
        SwingUtilities.invokeLater(() -> { settingId = true; modId.setText(id); settingId = false; });
    }

    Map<String, Object> spec() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name.getText().strip());
        s.put("id", modId.getText().strip());
        s.put("version", version.getText().strip());
        s.put("description", description.getText().strip().replaceAll("\\s*\\R\\s*", " "));
        s.put("author", author.getText().strip());
        if (hudOn.isSelected()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("corner", List.of("top_left", "top_right", "bottom_left", "bottom_right").get(hudCorner.getSelectedIndex()));
            h.put("lines", List.of(hudLines.getText().split("\\R")));
            h.put("color", hudColor.getText().strip());
            h.put("background", hudBg.isSelected());
            h.put("key", keyOf(hudKey));
            s.put("hud", h);
        }
        if (zoomOn.isSelected()) {
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("key", keyOf(zoomKey));
            z.put("factor", ((Number) zoomFactor.getValue()).doubleValue());
            z.put("hold", zoomHold.isSelected());
            z.put("smooth", zoomSmooth.isSelected());
            z.put("scroll", zoomScroll.isSelected());
            s.put("zoom", z);
        }
        if (commandsOn.isSelected()) s.put("commands", commands.values());
        if (keysOn.isSelected()) s.put("keys", keys.values());
        if (messagesOn.isSelected()) {
            if (welcomeOn.isSelected()) s.put("welcome", List.of(welcome.getText().split("\\R")));
            s.put("timers", timers.values());
        }
        return s;
    }

    void load(Map<?, ?> s) {
        String id = str(s.get("id"), "").strip();
        idFollowsName = id.isEmpty();
        name.setText(str(s.get("name"), ""));
        if (!id.isEmpty()) { settingId = true; modId.setText(id); settingId = false; }
        version.setText(str(s.get("version"), "1.0.0"));
        author.setText(str(s.get("author"), ""));
        description.setText(str(s.get("description"), ""));
        Map<?, ?> h = s.get("hud") instanceof Map<?, ?> m ? m : null;
        setOn(hudOn, h != null);
        if (h != null) {
            hudCorner.setSelectedIndex(Math.max(0, List.of("top_left", "top_right", "bottom_left", "bottom_right").indexOf(str(h.get("corner"), "top_left"))));
            hudLines.setText(h.get("lines") instanceof List<?> l ? String.join("\n", l.stream().map(String::valueOf).toList()) : str(h.get("lines"), ""));
            hudColor.setText(str(h.get("color"), "ffffff").replaceFirst("^#", ""));
            hudBg.setSelected(!Boolean.FALSE.equals(h.get("background")));
            String k = str(h.get("key"), "");
            if (k.isEmpty()) hudKey.setSelectedItem(NO_KEY); else setKey(hudKey, k);
        }
        Map<?, ?> z = s.get("zoom") instanceof Map<?, ?> m ? m : null;
        setOn(zoomOn, z != null);
        if (z != null) {
            setKey(zoomKey, str(z.get("key"), "key.keyboard.c"));
            zoomFactor.setValue(Math.max(1.5, Math.min(10, z.get("factor") instanceof Number n ? n.doubleValue() : 4)));
            zoomHold.setSelected(!Boolean.FALSE.equals(z.get("hold")));
            zoomSmooth.setSelected(!Boolean.FALSE.equals(z.get("smooth")));
            zoomScroll.setSelected(!Boolean.FALSE.equals(z.get("scroll")));
        }
        commands.clear();
        for (Object o : listOf(s.get("commands"))) commands.add(commandRow((Map<?, ?>) o));
        setOn(commandsOn, !commands.rows.isEmpty());
        keys.clear();
        for (Object o : listOf(s.get("keys"))) keys.add(keyRow((Map<?, ?>) o));
        setOn(keysOn, !keys.rows.isEmpty());
        List<?> wl = listOf(s.get("welcome"));
        welcomeOn.setSelected(!wl.isEmpty());
        if (!wl.isEmpty()) welcome.setText(String.join("\n", wl.stream().map(String::valueOf).toList()));
        timers.clear();
        for (Object o : listOf(s.get("timers"))) timers.add(timerRow((Map<?, ?>) o));
        setOn(messagesOn, !wl.isEmpty() || !timers.rows.isEmpty());
        problems.setVisible(false);
        refreshId();
    }

    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static List<?> listOf(Object o) { return o instanceof List<?> l ? l : List.of(); }

    private static Color parseColor(String hex) {
        try { return new Color(Integer.parseInt(hex.strip().replaceFirst("^#", ""), 16)); } catch (NumberFormatException e) { return Color.WHITE; }
    }

    // ------------------------------------------------------------------ examples, projects, guide

    record Example(String title, Map<String, Object> spec) {
        @Override public String toString() { return title; }
    }

    private List<Example> loadExamples() {
        List<Example> out = new ArrayList<>();
        Path dir = app.appDir.resolve("kit-examples");
        if (Files.isDirectory(dir)) try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                try {
                    Map<String, Object> spec = Json.map(Json.parse(Text.read(f)));
                    String d = str(spec.get("description"), "");
                    out.add(new Example(str(spec.get("name"), f.getFileName().toString()) + (d.isEmpty() ? "" : " — " + shorten(d, 50)), spec));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1).strip() + "…"; }

    private void openProject() {
        JFileChooser c = new JFileChooser(app.outDir.toFile());
        c.setDialogTitle("Pick the folder of a mod made here (it has horizon-kit.json)");
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (c.showOpenDialog(app.frame) != JFileChooser.APPROVE_OPTION) return;
        Path f = c.getSelectedFile().toPath().resolve("horizon-kit.json");
        try {
            load(Json.map(Json.parse(Text.read(f))));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(app.frame, "That folder has no horizon-kit.json (only mods made with this tab have one).",
                    "Not a kit mod", JOptionPane.WARNING_MESSAGE);
        }
    }

    void showGuide() {
        Path f = app.appDir.resolve("making-mods.md");
        String md;
        try { md = Text.read(f); } catch (Exception e) { browse(REPO_URL + "/blob/main/web/public/making-mods.md"); return; }
        JEditorPane pane = new JEditorPane("text/html", "<html><body style='font-family:sans-serif;font-size:11px;margin:10px'>"
                + Markdown.toHtml(md) + "</body></html>");
        pane.setEditable(false);
        pane.addHyperlinkListener(e -> { if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) browse(e.getURL().toString()); });
        pane.setCaretPosition(0);
        JDialog d = new JDialog(app.frame, "Making Horizon mods: the guide", false);
        JScrollPane sp = new JScrollPane(pane);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        d.add(sp);
        d.setSize(820, 760);
        d.setLocationRelativeTo(app.frame);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ making and packing

    private void makeClicked() {
        Map<String, Object> spec = spec();
        ModKit.Spec checked = ModKit.parse(spec);
        if (!checked.problems.isEmpty()) {
            problems.setText("<html><b>Fix these first:</b><br>• " + String.join("<br>• ", checked.problems.stream().map(TranslatorApp::escape).toList()) + "</html>");
            problems.setVisible(true);
            app.tabs.revalidate();
            return;
        }
        problems.setVisible(false);
        boolean inst = install.isSelected();
        Path out = app.outDir;
        Path appDir = app.appDir;
        app.run("Making " + checked.name + "…", () -> make(appDir, spec, out, inst ? NativePackage.defaultHome() : null));
    }

    /** What "Make mod" does: the project in a new folder of `out` named after the mod, its .hnmod, and the install. */
    static Result make(Path appDir, Map<String, Object> spec, Path out, Path install) throws Exception {
        ModKit.Spec checked = ModKit.parse(spec);
        if (!checked.problems.isEmpty()) return new Result(false, "Fix these first: " + String.join(" ", checked.problems), "", "", null);
        Files.createDirectories(out);
        Path dest = unique(out.resolve(checked.id));
        ModKit.Made m = ModKit.make(spec, dest, Main.sourceRoot(appDir.resolve("horizon-src.zip")), install);
        String readme = read(dest.resolve("README.md"));
        boolean added = m.notes().stream().anyMatch(n -> n.startsWith("installed as "));
        String notes = m.notes().stream().filter(n -> !n.startsWith("installed as ")).map(n -> "- " + n).reduce("", (a, b) -> a + b + "\n");
        if (m.hnmod() == null)
            return new Result(false, "Made the mod's folder, but no .hnmod: see the report", readme + "\n\n## Why there's no .hnmod\n\n" + notes, String.join("\n", m.notes()), dest);
        return new Result(true, "Made " + checked.name + ": " + m.hnmod().getFileName()
                + (added ? "  ·  added to Horizon: build it in the launcher" : "  ·  copy it into Horizon's horizon-native-mods folder"),
                readme, "Project: " + dest + "\nMod file: " + m.hnmod() + "\n" + String.join("\n", m.notes()), dest);
    }

    private void packClicked() {
        JFileChooser c = new JFileChooser(app.outDir.toFile());
        c.setDialogTitle("Pick your mod's folder (the one with mixins/ and Cargo.toml)");
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (c.showOpenDialog(app.frame) != JFileChooser.APPROVE_OPTION) return;
        Path dir = c.getSelectedFile().toPath();
        if (!Files.isDirectory(dir.resolve("mixins"))) {
            JOptionPane.showMessageDialog(app.frame, "That folder has no mixins/ folder: pick the mod's own folder.", "Not a mod folder", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean inst = install.isSelected();
        Path appDir = app.appDir;
        app.run("Checking and packing " + dir.getFileName() + "… (the first check with Rust takes a minute)", () -> {
            List<String> cmd = new ArrayList<>(List.of(javaExe().toString(), "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", classpath(appDir), "horizonport.Main", "pack", dir.toString(),
                    "--horizon-src", appDir.resolve("horizon-src.zip").toString(),
                    "--mappings", appDir.resolve("mappings/horizon-mappings.json").toString()));
            if (inst) cmd.add("--install");
            Proc p = exec(cmd, dir, 15);
            String log = p.out();
            boolean ok = p.code() == 0;
            String report = "```\n" + log.strip() + "\n```\n";
            String title = ok ? "Packed " + dir.getFileName() + (log.contains("installed as ") ? "  ·  added to Horizon: build it in the launcher" : "")
                    : p.timedOut() ? "Stopped: the check took too long" : "Not packed: the log says what to fix";
            return new Result(ok, title, report, log, dir);
        });
    }

    /** For the screenshot test: the form filled from an example. */
    void useExample(int i) {
        if (i < examples.getItemCount()) { examples.setSelectedIndex(i); load(examples.getItemAt(i).spec()); }
    }

}
