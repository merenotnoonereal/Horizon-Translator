// Checks JavaSource.isDeclarationPrefix: what may stand before a method's name in its declaration.
public class JavaSourceTest {
    public static void main(String[] a) throws Exception {
        var f = Class.forName("horizonport.JavaSource").getDeclaredMethod("isDeclarationPrefix", String.class);
        f.setAccessible(true);
        Object[][] cases = {
            {"private void", true}, {"public static <T> List<T>", true}, {"@Override public int", true},
            {"@Inject(method = \"tick\") private void", true}, {"int", true}, {"Map<String, List<Integer>>", true},
            {"if (", false}, {"return", false}, {"int y =", false}, {"this.", false}, {"while (!", false},
            {"", false}, {"new", false}, {"x &&", false}, {"foo(a,", false}};
        int bad = 0;
        for (Object[] c : cases) {
            boolean got = (boolean) f.invoke(null, c[0]);
            if (got != (boolean) c[1]) { bad++; System.out.println("  WRONG \"" + c[0] + "\" -> " + got); }
        }
        System.out.println(bad == 0 ? "  ok: all " + cases.length + " declaration cases right" : "  FAIL: " + bad + " declaration cases wrong");
        if (bad > 0) System.exit(1);
    }
}
