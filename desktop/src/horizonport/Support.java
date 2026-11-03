package horizonport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static horizonport.TranslatorApp.*;

/**
 * The "Support me" tab: crypto addresses with QR codes, from the donate.json shipped with the app.
 * The app never downloads addresses and never writes to the clipboard: viewers scan the QR code or
 * select the address themselves. (Fetching wallet addresses at runtime and putting them on the
 * clipboard is what "clipper" malware does, so antivirus heuristics treat it as suspicious.)
 */
final class Support {
    record Wallet(String name, String coins, String type, String networks, String address) {}
    record Link(String name, String url) {}
    record Info(String message, List<Wallet> wallets, List<Link> links) {
        static final Info EMPTY = new Info("", List.of(), List.of());
    }

    // Same checks as the website (app.js) and scripts/check-donate.mjs.
    static final Map<String, Pattern> FORMATS = Map.of(
            "bitcoin", Pattern.compile("(bc1[02-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})"),
            "evm", Pattern.compile("0x[0-9a-fA-F]{40}"),
            "solana", Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}"),
            "litecoin", Pattern.compile("(ltc1[02-9ac-hj-np-z]{11,71}|[LM3][1-9A-HJ-NP-Za-km-z]{25,34})"),
            "monero", Pattern.compile("[48][1-9A-HJ-NP-Za-km-z]{94}([1-9A-HJ-NP-Za-km-z]{11})?"));

    /** Filled in and in the right format for its type (any type without a check just needs no spaces). */
    static boolean usable(Wallet w) {
        String a = w.address();
        if (a.isEmpty() || !a.matches("\\S+")) return false;
        Pattern p = FORMATS.get(w.type().toLowerCase(java.util.Locale.ROOT));
        return p == null || p.matcher(a).matches();
    }

    static Info bundled(Path appDir) {
        try {
            Path f = appDir.resolve("donate.json");
            return Files.isRegularFile(f) ? parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)) : Info.EMPTY;
        } catch (Exception e) {
            return Info.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    static Info parse(String json) {
        Map<String, Object> root = (Map<String, Object>) new Json(json).value();
        List<Wallet> wallets = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("wallets", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            wallets.add(new Wallet(str(m, "name"), str(m, "coins"), str(m, "type"), str(m, "networks"), str(m, "address")));
        }
        List<Link> links = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("links", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            links.add(new Link(str(m, "name"), str(m, "url")));
        }
        return new Info(str(root, "message"), wallets, links);
    }

    static String str(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s.strip() : "";
    }

    // ------------------------------------------------------------------ the tab

    static JComponent panel(Path appDir) {
        JPanel p = card();
        fill(p, bundled(appDir));
        return p;
    }

    static void fill(JPanel p, Info info) {
        p.removeAll();
        p.add(label("Support me", TEXT, 16f, true));
        p.add(Box.createVerticalStrut(6));
        if (!info.message().isEmpty()) {
            p.add(wrapLabel(info.message(), MUTED));
            p.add(Box.createVerticalStrut(12));
        }
        List<Wallet> wallets = info.wallets().stream().filter(Support::usable).toList();
        List<Link> links = info.links().stream().filter(l -> l.url().startsWith("https://") && !l.name().isEmpty()).toList();
        if (wallets.isEmpty() && links.isEmpty()) {
            p.add(wrapLabel("Donation addresses haven't been added yet. Thanks for thinking of it!", MUTED));
        }
        if (!wallets.isEmpty()) {
            JPanel grid = new JPanel(new GridLayout(0, 3, 12, 12)) {
                @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            };
            grid.setOpaque(false);
            grid.setAlignmentX(0);
            for (Wallet w : wallets) grid.add(walletCard(w));
            p.add(grid);
            p.add(Box.createVerticalStrut(12));
            p.add(wrapLabel("Only send on the networks listed under each address. Crypto sent on another network or to a "
                    + "mistyped address can't be got back. Scan the QR code, or select the address and press Ctrl+C, instead of typing it.", MUTED));
        }
        if (!links.isEmpty()) {
            p.add(Box.createVerticalStrut(10));
            JPanel r = row();
            for (Link l : links) {
                JButton b = ghost(l.name());
                b.addActionListener(e -> browse(l.url()));
                r.add(b);
                r.add(Box.createHorizontalStrut(8));
            }
            p.add(r);
        }
        p.revalidate();
        p.repaint();
    }

    static JComponent walletCard(Wallet w) {
        JPanel c = column();
        c.setOpaque(true);
        c.setBackground(SURFACE2);
        c.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(12, 12, 12, 12)));
        c.add(centred(w.name() + (w.coins().isEmpty() ? "" : " (" + w.coins() + ")"), TEXT, 14f, true));
        if (!w.networks().isEmpty()) c.add(centred("Send on: " + w.networks(), MUTED, 12f, false));
        c.add(Box.createVerticalStrut(8));

        JLabel qr;
        try {
            Qr code = Qr.encode(w.address());
            qr = new JLabel(new ImageIcon(code.image(Math.max(2, 180 / (code.size + 8)))));
            qr.setToolTipText(w.name() + " address");
        } catch (IllegalArgumentException e) {
            qr = label("(address too long for a QR code)", MUTED, 12f, false);
        }
        c.add(qr);
        c.add(Box.createVerticalStrut(8));

        JTextArea addr = new JTextArea(w.address());
        addr.setEditable(false);
        addr.setToolTipText("Select the address and press Ctrl+C to copy it");
        addr.setLineWrap(true);
        addr.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        addr.setColumns(26);
        addr.setRows((w.address().length() + 25) / 26);
        addr.setBackground(SURFACE);
        addr.setBorder(new EmptyBorder(4, 6, 4, 6));
        addr.setMaximumSize(addr.getPreferredSize());
        c.add(addr);

        for (Component k : c.getComponents()) if (k instanceof JComponent j) j.setAlignmentX(0.5f);
        return c;
    }

    /** Centred text that wraps inside a wallet card. */
    static JLabel centred(String text, Color color, float size, boolean bold) {
        JLabel l = label("<html><div style='text-align:center;width:190px'>" + escape(text) + "</div></html>", color, size, bold);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }

    /** Just enough JSON for donate.json: objects, arrays, strings, numbers, true/false/null. */
    static final class Json {
        final String s;
        int i;

        Json(String s) { this.s = s; }

        Object value() {
            ws();
            char ch = s.charAt(i);
            if (ch == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (ch == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (ch == '"') return string();
            int start = i;
            while (i < s.length() && "{}[],: \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String word = s.substring(start, i);
            return switch (word) {
                case "true" -> true;
                case "false" -> false;
                case "null" -> null;
                default -> Double.parseDouble(word);
            };
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char ch = s.charAt(i++);
                if (ch == '"') return b.toString();
                if (ch != '\\') { b.append(ch); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            i++;
        }
    }
}
