package horizonport;

import javax.swing.UIManager;
import java.awt.ComponentOrientation;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

/**
 * Internationalization (i18n) system for Horizon Translator desktop app.
 * Supports 22 languages with English as fallback.
 */
public final class I18n {
    private I18n() {}

    public record Language(String code, String displayName, boolean rtl) {
        @Override public String toString() { return displayName; }
    }

    public static final List<Language> LANGUAGES = List.of(
            new Language("en", "English", false),
            new Language("zh-CN", "简体中文", false),
            new Language("hi", "हिन्दी", false),
            new Language("es", "Español", false),
            new Language("ar", "العربية", true),
            new Language("fr", "Français", false),
            new Language("bn", "বাংলা", false),
            new Language("pt-BR", "Português (Brasil)", false),
            new Language("ru", "Русский", false),
            new Language("id", "Bahasa Indonesia", false),
            new Language("ur", "اردو", true),
            new Language("de", "Deutsch", false),
            new Language("ja", "日本語", false),
            new Language("mr", "मराठी", false),
            new Language("vi", "Tiếng Việt", false),
            new Language("te", "తెలుగు", false),
            new Language("tr", "Türkçe", false),
            new Language("ta", "தமிழ்", false),
            new Language("ko", "한국어", false),
            new Language("sw", "Kiswahili", false),
            new Language("fil", "Filipino", false),
            new Language("fa", "فارسی", true)
    );

    private static String currentLang = "en";
    private static String currentFontFamily = "Segoe UI";
    private static Set<String> availableFontFamilies;
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();

    /** Initializes the language from CLI argument, system property, Preferences, or Locale. */
    public static void init(String cliLang) {
        String chosen = cliLang;
        if (chosen == null || chosen.isBlank()) {
            chosen = System.getProperty("horizonport.lang");
        }
        if (chosen == null || chosen.isBlank()) {
            try {
                chosen = Preferences.userNodeForPackage(TranslatorApp.class).get("lang", "");
            } catch (Exception ignored) {}
        }
        if (chosen == null || chosen.isBlank()) {
            chosen = matchLocale(Locale.getDefault());
        }
        setLangInternal(chosen, false);
    }

    /** Changes active language and saves choice to Preferences. */
    public static void setLang(String code) {
        setLangInternal(code, true);
    }

    private static void setLangInternal(String code, boolean savePref) {
        Language lang = forCode(code);
        currentLang = lang.code();
        if (savePref) {
            try {
                Preferences.userNodeForPackage(TranslatorApp.class).put("lang", currentLang);
            } catch (Exception ignored) {}
        }
        setupFonts();
    }

    public static String lang() {
        return currentLang;
    }

    public static Language currentLanguage() {
        return forCode(currentLang);
    }

    public static boolean isRtl() {
        return currentLanguage().rtl();
    }

    public static Language forCode(String code) {
        if (code != null) {
            for (Language l : LANGUAGES) {
                if (l.code().equalsIgnoreCase(code)) return l;
            }
        }
        return LANGUAGES.get(0); // English
    }

    public static String matchLocale(Locale locale) {
        if (locale == null) return "en";
        String tag = locale.toLanguageTag();
        for (Language l : LANGUAGES) {
            if (l.code().equalsIgnoreCase(tag)) return l.code();
        }
        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "zh" -> "zh-CN";
            case "pt" -> "pt-BR";
            case "tl", "fil" -> "fil";
            case "in", "id" -> "id";
            case "ur" -> "ur";
            case "ar" -> "ar";
            case "fa" -> "fa";
            case "hi" -> "hi";
            case "es" -> "es";
            case "fr" -> "fr";
            case "bn" -> "bn";
            case "ru" -> "ru";
            case "de" -> "de";
            case "ja" -> "ja";
            case "mr" -> "mr";
            case "vi" -> "vi";
            case "te" -> "te";
            case "tr" -> "tr";
            case "ta" -> "ta";
            case "ko" -> "ko";
            case "sw" -> "sw";
            default -> "en";
        };
    }

    // ------------------------------------------------------------------ properties & translation

    public static String t(String key, Object... args) {
        String pattern = null;
        if (currentLang != null && !currentLang.isEmpty()) {
            Properties active = getProperties(currentLang);
            pattern = active.getProperty(key);
        }
        if (pattern == null || pattern.isBlank()) {
            Properties fallback = getProperties("en");
            pattern = fallback.getProperty(key);
        }
        if (pattern == null || pattern.isBlank()) {
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern.replace("''", "'");
        }
        try {
            String prepared = preparePattern(pattern);
            return new MessageFormat(prepared, localeFor(currentLang)).format(args);
        } catch (Exception e) {
            String result = pattern;
            for (int i = 0; i < args.length; i++) {
                result = result.replace("{" + i + "}", String.valueOf(args[i]));
            }
            return result.replace("''", "'");
        }
    }

    private static String preparePattern(String pattern) {
        if (!pattern.contains("{")) return pattern;
        // Auto-double single quotes that aren't already doubled
        return pattern.replaceAll("(?<!')'(?!')", "''");
    }

    private static Locale localeFor(String code) {
        if (code == null || code.isEmpty() || "en".equalsIgnoreCase(code)) return Locale.ENGLISH;
        if ("zh-CN".equalsIgnoreCase(code)) return Locale.SIMPLIFIED_CHINESE;
        return Locale.forLanguageTag(code);
    }

    private static Properties getProperties(String code) {
        return CACHE.computeIfAbsent(code, I18n::loadProperties);
    }

    private static Properties loadProperties(String code) {
        Properties p = new Properties();
        String res = "/horizonport/i18n/messages_" + code + ".properties";
        InputStream in = I18n.class.getResourceAsStream(res);
        if (in == null) {
            // Dev fallback on disk
            Path dev = Path.of("desktop/src/horizonport/i18n/messages_" + code + ".properties");
            if (!Files.isRegularFile(dev)) {
                dev = Path.of("src/horizonport/i18n/messages_" + code + ".properties");
            }
            if (Files.isRegularFile(dev)) {
                try { in = Files.newInputStream(dev); } catch (IOException ignored) {}
            }
        }
        if (in != null) {
            try (InputStream stream = in;
                 Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                p.load(reader);
            } catch (IOException ignored) {}
        }
        return p;
    }

    // ------------------------------------------------------------------ fonts

    public static synchronized void setupFonts() {
        currentFontFamily = pickFontFamily(currentLang);
        Font baseFont = new Font(currentFontFamily, Font.PLAIN, 12);
        String[] fontKeys = {
                "Label.font", "Button.font", "TabbedPane.font", "CheckBox.font", "ComboBox.font",
                "TextField.font", "TextArea.font", "TitledBorder.font", "EditorPane.font", "TextPane.font",
                "MenuBar.font", "Menu.font", "MenuItem.font", "ToolTip.font", "List.font",
                "Table.font", "TableHeader.font", "RadioButton.font", "FormattedTextField.font", "PasswordField.font"
        };
        for (String key : fontKeys) {
            Font current = UIManager.getFont(key);
            int style = current != null ? current.getStyle() : Font.PLAIN;
            float size = current != null ? current.getSize2D() : 12f;
            UIManager.put(key, baseFont.deriveFont(style, size));
        }
    }

    public static Font uiFont(int style, float size) {
        if (currentFontFamily == null) setupFonts();
        return new Font(currentFontFamily, style, Math.round(size));
    }

    public static String fontFamily() {
        if (currentFontFamily == null) setupFonts();
        return currentFontFamily;
    }

    private static synchronized Set<String> getAvailableFontFamilies() {
        if (availableFontFamilies == null) {
            availableFontFamilies = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            try {
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                availableFontFamilies.addAll(Arrays.asList(ge.getAvailableFontFamilyNames()));
            } catch (Exception ignored) {}
        }
        return availableFontFamilies;
    }

    private static String pickFontFamily(String lang) {
        Set<String> avail = getAvailableFontFamilies();
        String sample;
        String[] candidates;

        switch (lang) {
            case "zh-CN" -> {
                sample = "简体中文";
                candidates = new String[] {"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "PingFang SC", "Noto Sans CJK SC", "Noto Sans SC"};
            }
            case "ja" -> {
                sample = "日本語";
                candidates = new String[] {"Yu Gothic UI", "Yu Gothic", "Meiryo UI", "Meiryo", "MS Gothic", "Noto Sans CJK JP", "Noto Sans JP"};
            }
            case "ko" -> {
                sample = "한국어";
                candidates = new String[] {"Malgun Gothic", "Gulim", "Noto Sans CJK KR", "Noto Sans KR"};
            }
            case "hi" -> {
                sample = "हिन्दी";
                candidates = new String[] {"Nirmala UI", "Mangal", "Noto Sans Devanagari", "Utsaah"};
            }
            case "mr" -> {
                sample = "मराठी";
                candidates = new String[] {"Nirmala UI", "Mangal", "Noto Sans Devanagari", "Aparajita"};
            }
            case "bn" -> {
                sample = "বাংলা";
                candidates = new String[] {"Nirmala UI", "Vrinda", "Shonar Bangla", "Noto Sans Bengali"};
            }
            case "ta" -> {
                sample = "தமிழ்";
                candidates = new String[] {"Nirmala UI", "Latha", "Vijaya", "Noto Sans Tamil"};
            }
            case "te" -> {
                sample = "తెలుగు";
                candidates = new String[] {"Nirmala UI", "Gautami", "Vani", "Noto Sans Telugu"};
            }
            case "ar" -> {
                sample = "العربية";
                candidates = new String[] {"Segoe UI", "Tahoma", "Arial", "Noto Sans Arabic"};
            }
            case "ur" -> {
                sample = "اردو";
                candidates = new String[] {"Segoe UI", "Tahoma", "Arial", "Noto Sans Arabic"};
            }
            case "fa" -> {
                sample = "فارسی";
                candidates = new String[] {"Segoe UI", "Tahoma", "Arial", "Noto Sans Arabic"};
            }
            default -> {
                sample = "English";
                candidates = new String[] {"Segoe UI", "Tahoma", "SansSerif"};
            }
        }

        // Try candidates first
        for (String c : candidates) {
            if (avail.contains(c)) {
                Font f = new Font(c, Font.PLAIN, 12);
                if (f.canDisplayUpTo(sample) == -1) return c;
            }
        }

        // Search through all available system fonts if candidates don't cover sample text
        for (String fName : avail) {
            Font f = new Font(fName, Font.PLAIN, 12);
            if (f.canDisplayUpTo(sample) == -1) return fName;
        }

        return "Segoe UI";
    }
}
