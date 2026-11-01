// Checks Generator.bodyBlocker: which target bodies horizon-mixins can rewrite.
public class BodyCheckTest { public static void main(String[] a) throws Exception {
  var m = Class.forName("horizonport.Generator").getDeclaredMethod("bodyBlocker", String.class); m.setAccessible(true);
  String[][] cases = {
    {"{ if a != b || c { x(); } }", "null"}, {"{ instant |= f(w); }", "null"}, {"{ let d = |hand| Some(hand); }", "a closure"},
    {"{ v.iter().map(|x| x + 1); }", "a closure"}, {"{ run(|| go()); }", "a closure"}, {"{ let y = f()?; }", "a `?`"},
    {"{ let s = format!(\"{}\", a); }", "null"}, {"{ println!(\"hi\"); }", "a `println!` macro"}, {"{ let c = '?'; let s = \"a?b\"; }", "null"},
    {"{ match x { A => |v| v, _ => g } }", "a closure"}, {"{ if a && b || !c { } }", "null"}};
  int bad = 0;
  for (String[] c : cases) { Object r = m.invoke(null, c[0]); String got = String.valueOf(r); if (!got.equals(c[1])) { bad++; System.out.println("WRONG " + c[0] + " -> " + got + " (want " + c[1] + ")"); } }
  System.out.println(bad == 0 ? "  ok: all " + cases.length + " body cases right" : "  FAIL: " + bad + " body cases wrong");
  if (bad > 0) System.exit(1);
}}
