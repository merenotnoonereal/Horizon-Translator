package horizonport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static horizonport.TranslatorApp.*;

/**
 * "Best shader packs" on the Shader packs tab: packs known to convert well, each with a link to its official download
 * page. The player downloads the pack there and converts it here, on their own PC. Nothing converted is shared:
 * most of these packs' licences (Complementary's, BSL's...) don't allow sharing changed copies.
 */
final class ShaderList {
    private ShaderList() {}

    /** tested: the version this app's converter was checked with. */
    record Pack(String id, String name, String author, String url, String tested) {
        String about() { return I18n.t("shader.pack." + id + ".about"); }
        String licence() { return I18n.t("shader.pack." + id + ".licence"); }
    }

    private static final String MODRINTH = "https://modrinth.com/shader/";

    static final List<Pack> BEST = List.of(
            new Pack("reimagined", "Complementary Reimagined", "EminGT",
                    MODRINTH + "complementary-reimagined", "r5.9.3"),
            new Pack("unbound", "Complementary Unbound", "EminGT",
                    MODRINTH + "complementary-unbound", "r5.9.3"),
            new Pack("bsl", "BSL Shaders", "CaptTatsu",
                    MODRINTH + "bsl-shaders", "v10.1.8"),
            new Pack("photon", "Photon", "sixthsurge",
                    MODRINTH + "photon-shader", "v1.4"),
            new Pack("bliss", "Bliss", "Xonk",
                    MODRINTH + "bliss-shader", "v2.1.2"),
            new Pack("solas", "Solas", "Septonious",
                    MODRINTH + "solas-shader", "V3.8"),
            new Pack("noble", "Noble", "BelmuTM",
                    MODRINTH + "noble", "v1.9.9"),
            new Pack("super_duper", "Super Duper Vanilla", "Eldeston",
                    MODRINTH + "super-duper-vanilla", ""),
            new Pack("mellow", "Mellow", "TheCMK",
                    MODRINTH + "mellow", "v3.4.1a"),
            new Pack("makeup", "MakeUp - Ultra Fast", "KDXavier",
                    MODRINTH + "makeup-ultra-fast-shaders", "9.6"),
            new Pack("miniature", "Miniature", "ukrech",
                    MODRINTH + "miniature-shader", "2.19"));

    static JComponent panel(TranslatorApp app) {
        JPanel p = card();
        p.add(label(I18n.t("shader.best.title"), TEXT, 16f, true));
        p.add(Box.createVerticalStrut(6));
        p.add(wrapLabel(I18n.t("shader.best.desc"), MUTED));
        p.add(Box.createVerticalStrut(12));
        for (Pack pack : BEST) {
            p.add(packCard(app, pack));
            p.add(Box.createVerticalStrut(10));
        }
        p.add(wrapLabel(I18n.t("shader.best.disclaimer"), MUTED));
        return p;
    }

    static JComponent packCard(TranslatorApp app, Pack pack) {
        JPanel c = new JPanel(new BorderLayout(16, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        c.setBackground(SURFACE2);
        c.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(12, 14, 12, 14)));
        c.setAlignmentX(0);

        JPanel text = column();
        text.add(label(pack.name(), TEXT, 15f, true));
        String meta = pack.tested().isEmpty()
                ? I18n.t("shader.pack.meta", pack.author(), pack.licence())
                : I18n.t("shader.pack.meta_tested", pack.author(), pack.tested(), pack.licence());
        text.add(label(meta, MUTED, 12f, false));
        text.add(Box.createVerticalStrut(4));
        text.add(wrapLabel(pack.about(), TEXT));
        c.add(text, BorderLayout.CENTER);

        JPanel buttons = column();
        JButton page = ghost(I18n.t("shader.btn.page"));
        page.setToolTipText(pack.url());
        page.addActionListener(e -> browse(pack.url()));
        JButton convert = primary(I18n.t("shader.btn.convert"));
        convert.addActionListener(e -> {
            File f = pick(app, pack);
            if (f != null) app.convertPackFile(f);
        });
        buttons.add(page);
        buttons.add(Box.createVerticalStrut(6));
        buttons.add(convert);
        c.add(buttons, BorderLayout.LINE_END);
        return c;
    }

    /** The downloaded .zip, picked from the Downloads folder. */
    private static File pick(TranslatorApp app, Pack pack) {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        JFileChooser ch = new JFileChooser(Files.isDirectory(downloads) ? downloads.toFile() : null);
        ch.setDialogTitle(I18n.t("shader.pick.title", pack.name()));
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(I18n.t("shader.filter.zip"), "zip"));
        return ch.showOpenDialog(app.frame) == JFileChooser.APPROVE_OPTION ? ch.getSelectedFile() : null;
    }
}
