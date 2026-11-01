// Checks Licenses.classify: which licence names let a mod be decompiled and ported.
public class LicenseTest {
    public static void main(String[] a) throws Exception {
        var classify = Class.forName("horizonport.Licenses").getDeclaredMethod("classify", String.class);
        classify.setAccessible(true);
        String[][] cases = {
            {"MIT", "OPEN"}, {"Apache-2.0", "OPEN"}, {"GPL-3.0-or-later", "OPEN"}, {"GPLv3", "OPEN"}, {"GNU GPL v3", "OPEN"},
            {"LGPL-3.0-only", "OPEN"}, {"MPL-2.0", "OPEN"}, {"OSL-3.0", "OPEN"}, {"Polyform-Shield-1.0.0", "CLOSED"}, {"BSD-3-Clause", "OPEN"}, {"CC0-1.0", "OPEN"}, {"CC-BY-4.0", "OPEN"},
            {"CC-BY-NC-SA-4.0", "OPEN"}, {"CC-BY-ND-4.0", "NO_DERIVATIVES"}, {"CC-BY-NC-ND-4.0", "NO_DERIVATIVES"},
            {"ARR", "CLOSED"}, {"All Rights Reserved", "CLOSED"}, {"Proprietary", "CLOSED"}, {"LicenseRef-Custom", "CLOSED"},
            {"Custom", "CLOSED"}, {"", "CLOSED"}};
        int bad = 0;
        for (String[] c : cases) {
            Object licence = classify.invoke(null, c[0]);
            var kind = licence.getClass().getDeclaredMethod("kind");
            kind.setAccessible(true);
            String got = String.valueOf(kind.invoke(licence));
            if (!got.equals(c[1])) { bad++; System.out.println("  WRONG \"" + c[0] + "\" -> " + got + " (want " + c[1] + ")"); }
        }
        System.out.println(bad == 0 ? "  ok: all " + cases.length + " licence names right" : "  FAIL: " + bad + " licence names wrong");
        if (bad > 0) System.exit(1);
    }
}
