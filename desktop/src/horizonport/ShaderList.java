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
    record Pack(String name, String author, String about, String url, String tested, String licence) {}

    private static final String MODRINTH = "https://modrinth.com/shader/";

    static final List<Pack> BEST = List.of(
            new Pack("Complementary Reimagined", "EminGT", "Bright and clean, close to vanilla's look, with lots of options. A great first pick.",
                    MODRINTH + "complementary-reimagined", "r5.9.3", "Complementary License"),
            new Pack("Complementary Unbound", "EminGT", "Complementary's more realistic style: deeper shadows, volumetric light and clouds.",
                    MODRINTH + "complementary-unbound", "r5.9.3", "Complementary License"),
            new Pack("BSL Shaders", "CaptTatsu", "A classic: soft lighting, nice water and sky, runs well on most PCs.",
                    MODRINTH + "bsl-shaders", "v10.1.8", "all rights reserved"),
            new Pack("Photon", "sixthsurge", "Realistic sky, clouds and light, for strong graphics cards.",
                    MODRINTH + "photon-shader", "v1.4", "Photon License"),
            new Pack("Bliss", "Xonk", "Cinematic light and fog, based on Chocapic13's shaders.",
                    MODRINTH + "bliss-shader", "v2.1.2", "Chocapic13's licence"),
            new Pack("Solas", "Septonious", "Colourful and dreamy, with aurora and nebula skies.",
                    MODRINTH + "solas-shader", "V3.8", "all rights reserved"),
            new Pack("Noble", "BelmuTM", "Realistic and detailed: reflections, shadows and clouds.",
                    MODRINTH + "noble", "v1.9.9", "GPL-3.0"),
            new Pack("Super Duper Vanilla", "Eldeston", "Vanilla's style made prettier, with stylised effects.",
                    MODRINTH + "super-duper-vanilla", "", "FlameRender License"),
            new Pack("Mellow", "TheCMK", "Soft, warm and calm.",
                    MODRINTH + "mellow", "v3.4.1a", "MIT"),
            new Pack("MakeUp - Ultra Fast", "KDXavier", "Light and fast: good shaders for weak PCs and laptops.",
                    MODRINTH + "makeup-ultra-fast-shaders", "9.6", "LGPL-3.0"),
            new Pack("Miniature", "ukrech", "Tiny and very fast, a small step up from vanilla.",
                    MODRINTH + "miniature-shader", "2.19", "MIT"));

    static JComponent panel(TranslatorApp app) {
        JPanel p = card();
        p.add(label("Best shader packs", TEXT, 16f, true));
        p.add(Box.createVerticalStrut(6));
        p.add(wrapLabel("Packs that convert well. Press Download page, download the Iris or OptiFine version from the pack's "
                + "official page, then press Convert and pick the .zip you downloaded. It converts on your PC and goes into "
                + "Horizon's shaderpacks folder. Newer versions than the one tested usually work too.", MUTED));
        p.add(Box.createVerticalStrut(12));
        for (Pack pack : BEST) {
            p.add(packCard(app, pack));
            p.add(Box.createVerticalStrut(10));
        }
        p.add(wrapLabel("Converted packs are for your own use: most of these packs' licences don't allow sharing changed copies, "
                + "so don't upload them. Each pack belongs to its author; this app isn't affiliated with them.", MUTED));
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
        text.add(label("by " + pack.author() + (pack.tested().isEmpty() ? "" : "  ·  tested with " + pack.tested()) + "  ·  " + pack.licence(), MUTED, 12f, false));
        text.add(Box.createVerticalStrut(4));
        text.add(wrapLabel(pack.about(), TEXT));
        c.add(text, BorderLayout.CENTER);

        JPanel buttons = column();
        JButton page = ghost("Download page");
        page.setToolTipText(pack.url());
        page.addActionListener(e -> browse(pack.url()));
        JButton convert = primary("Convert…");
        convert.addActionListener(e -> {
            File f = pick(app, pack);
            if (f != null) app.convertPackFile(f);
        });
        buttons.add(page);
        buttons.add(Box.createVerticalStrut(6));
        buttons.add(convert);
        c.add(buttons, BorderLayout.EAST);
        return c;
    }

    /** The downloaded .zip, picked from the Downloads folder. */
    private static File pick(TranslatorApp app, Pack pack) {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        JFileChooser ch = new JFileChooser(Files.isDirectory(downloads) ? downloads.toFile() : null);
        ch.setDialogTitle("Pick the " + pack.name() + " .zip you downloaded");
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Shader pack (.zip)", "zip"));
        return ch.showOpenDialog(app.frame) == JFileChooser.APPROVE_OPTION ? ch.getSelectedFile() : null;
    }
}
