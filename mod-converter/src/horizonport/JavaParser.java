package horizonport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A parser for the part of Java that {@link RustTranslator} turns into Rust: classes, enums,
 * fields, methods and their statements and expressions. Anything else (lambdas, anonymous classes,
 * switch expressions, generics on methods...) is kept as a failure with its reason, so the rest of
 * the file still parses. It reads text; nothing is compiled or run.
 */
final class JavaParser {
    /** Something this parser (or the translator) doesn't handle, with a short reason. */
    static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsupported(String why) {
            super(why, null, false, System.getenv("HP_TRACE") != null && why.contains(System.getenv("HP_TRACE")));
            if (System.getenv("HP_TRACE") != null && why.contains(System.getenv("HP_TRACE"))) {
                System.err.println("HP_TRACE " + why);
                for (StackTraceElement el : new Throwable().getStackTrace()) if (el.getClassName().startsWith("horizonport")) System.err.println("  at " + el);
            }
        }
    }

    // ---- tokens ----

    enum T { IDENT, NUM, STR, CHR, OP, EOF }

    record Tok(T t, String s, int line) {
        boolean is(String x) {
            return (t == T.OP || t == T.IDENT) && s.equals(x);
        }
    }

    static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = src.length(), line = 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                i += 2;
                continue;
            }
            int start = i;
            if (Character.isJavaIdentifierStart(c)) {
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) i++;
                out.add(new Tok(T.IDENT, src.substring(start, i), line));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && (Character.digit(src.charAt(i), 16) >= 0 || src.charAt(i) == '_')) i++;
                } else {
                    while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '.')) i++;
                    if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                        i++;
                        if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                        while (i < n && Character.isDigit(src.charAt(i))) i++;
                    }
                }
                if (i < n && "lLfFdD".indexOf(src.charAt(i)) >= 0) i++;
                out.add(new Tok(T.NUM, src.substring(start, i), line));
                continue;
            }
            if (c == '"') {
                if (src.startsWith("\"\"\"", i)) throw new Unsupported("a text block");
                i++;
                while (i < n && src.charAt(i) != '"') i += src.charAt(i) == '\\' ? 2 : 1;
                i++;
                out.add(new Tok(T.STR, src.substring(start, i), line));
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') i += src.charAt(i) == '\\' ? 2 : 1;
                i++;
                out.add(new Tok(T.CHR, src.substring(start, i), line));
                continue;
            }
            String op = null;
            for (String o : OPS) {
                if (src.startsWith(o, i)) { op = o; break; }
            }
            if (op == null) op = String.valueOf(c);
            i += op.length();
            out.add(new Tok(T.OP, op, line));
        }
        out.add(new Tok(T.EOF, "", line));
        return out;
    }

    // Longest first. `>>` and `>>>` are split again where a type's `>` is wanted.
    private static final String[] OPS = {">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>"};

    // ---- the tree ----

    record TypeRef(String name, List<TypeRef> args, int dims) {
        TypeRef elem() {
            return new TypeRef(name, args, dims - 1);
        }

        TypeRef array() {
            return new TypeRef(name, args, dims + 1);
        }

        String simple() {
            return name.substring(name.lastIndexOf('.') + 1);
        }

        @Override
        public String toString() {
            return name + (args.isEmpty() ? "" : "<...>") + "[]".repeat(dims);
        }
    }

    sealed interface Expr permits Lit, Name, Field, Index, Call, New, NewArray, ArrayInit, Unary, Binary, Assign, Cond, Cast, InstanceOf, This, Lambda, MethodRef, SwitchExpr {}

    record Lit(String kind, String text) implements Expr {}
    record Name(String id) implements Expr {}
    record Field(Expr target, String name) implements Expr {}
    record Index(Expr array, Expr index) implements Expr {}
    record Call(Expr target, String name, List<Expr> args) implements Expr {}
    record New(TypeRef type, List<Expr> args) implements Expr {}
    record NewArray(TypeRef type, List<Expr> dims, ArrayInit init) implements Expr {}
    record ArrayInit(List<Expr> items) implements Expr {}
    record Unary(String op, Expr e, boolean postfix) implements Expr {}
    record Binary(String op, Expr l, Expr r) implements Expr {}
    record Assign(String op, Expr target, Expr value) implements Expr {}
    record Cond(Expr c, Expr a, Expr b) implements Expr {}
    record Cast(TypeRef type, Expr e) implements Expr {}
    record InstanceOf(Expr e, TypeRef type, String binding) implements Expr {}
    record This() implements Expr {}
    /** A lambda: its parameter names and its body (a Block, or an Expr for `x -> expr`). */
    record Lambda(List<String> params, Object body) implements Expr {}
    /** `Target::name` (`name` is "new" for a constructor reference). */
    record MethodRef(Expr target, String name) implements Expr {}
    /** `switch (x) { case A -> value; ... }` used as a value. */
    record SwitchExpr(Switch sw) implements Expr {}

    sealed interface Stmt permits Block, Local, ExprStmt, If, While, DoWhile, For, ForEach, Return, Break, Continue, Throw, Try, Switch, Sync, Empty, Note, Yield {}

    record Block(List<Stmt> stmts) implements Stmt {}
    record Declarator(String name, int dims, Expr init) {}
    record Local(TypeRef type, List<Declarator> vars) implements Stmt {}
    record ExprStmt(Expr e) implements Stmt {}
    record If(Expr c, Stmt then, Stmt otherwise) implements Stmt {}
    record While(Expr c, Stmt body) implements Stmt {}
    record DoWhile(Stmt body, Expr c) implements Stmt {}
    record For(List<Stmt> init, Expr cond, List<Expr> update, Stmt body) implements Stmt {}
    record ForEach(TypeRef type, String var, Expr iter, Stmt body) implements Stmt {}
    record Return(Expr e) implements Stmt {}
    record Break(String label) implements Stmt {}
    record Continue(String label) implements Stmt {}
    record Throw(Expr e) implements Stmt {}
    record Catch(List<String> types, String name, Block body) {}
    /** `res`: the try-with-resources declarations, when they could be read (else empty, with `resources` set). */
    record Try(Block body, List<Catch> catches, Block fin, boolean resources, List<Local> res) implements Stmt {}
    /** One case group: its labels (none for default) and statements; `arrow` for `case X ->`. */
    record Case(List<Expr> labels, boolean arrow, List<Stmt> body) {}
    record Switch(Expr sel, List<Case> cases) implements Stmt {}
    record Sync(Expr lock, Block body) implements Stmt {}
    record Empty() implements Stmt {}
    /** Not Java: a statement the translator left out, written as a TODO comment. */
    record Note(String text) implements Stmt {}
    /** `yield value;` inside a switch expression's block. */
    record Yield(Expr e) implements Stmt {}

    record Param(TypeRef type, String name, boolean varargs) {}

    static final class FieldDecl {
        Set<String> mods;
        TypeRef type;
        String name;
        Expr init;
        String error;
        int line;
    }

    static final class MethodDecl {
        Set<String> mods;
        TypeRef ret; // null for a constructor
        String name;
        List<Param> params = new ArrayList<>();
        Block body; // null: abstract, native, or failed (error says why)
        String error;
        boolean generic;
        int line;
        /** The exceptions it declares (`throws IOException`): simple names. */
        List<String> throwsTypes = new ArrayList<>();

        boolean isStatic() {
            return mods.contains("static");
        }
    }

    record EnumConst(String name, List<Expr> args, boolean body) {}

    static final class ClassDecl {
        String kind; // class, enum, interface, record, annotation
        String name;
        String qualified; // Outer.Inner
        Set<String> mods;
        TypeRef ext;
        List<TypeRef> impls = new ArrayList<>();
        boolean generic;
        List<FieldDecl> fields = new ArrayList<>();
        List<MethodDecl> methods = new ArrayList<>();
        List<ClassDecl> nested = new ArrayList<>();
        List<EnumConst> constants = new ArrayList<>();
        List<Param> components = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean staticInit;
        ClassDecl outer;
        int line;
    }

    record Unit(String pkg, List<String> imports, List<ClassDecl> classes, String file) {}

    // ---- the parser ----

    private final List<Tok> toks;
    private int p;

    private JavaParser(List<Tok> toks) {
        this.toks = toks;
    }

    static Unit parseUnit(String src, String file) {
        JavaParser jp = new JavaParser(lex(src));
        return jp.unit(file);
    }

    /** One expression on its own (for tests). */
    static Expr parseExpr(String src) {
        JavaParser jp = new JavaParser(lex(src));
        Expr e = jp.expr();
        jp.expectEof();
        return e;
    }

    /** Statements on their own (for tests). */
    static Block parseBlock(String src) {
        JavaParser jp = new JavaParser(lex("{" + src + "}"));
        Block b = jp.block();
        jp.expectEof();
        return b;
    }

    private void expectEof() {
        if (peek().t() != T.EOF) throw new Unsupported("unexpected `" + peek().s() + "`");
    }

    private Tok peek() {
        return toks.get(p);
    }

    private Tok peek(int k) {
        return toks.get(Math.min(p + k, toks.size() - 1));
    }

    private boolean at(String s) {
        return peek().is(s);
    }

    private boolean accept(String s) {
        if (at(s)) { p++; return true; }
        return false;
    }

    private Tok next() {
        Tok t = toks.get(p);
        if (t.t() != T.EOF) p++;
        return t;
    }

    private void expect(String s) {
        if (!accept(s)) throw new Unsupported("expected `" + s + "` but found `" + peek().s() + "` (line " + peek().line() + ")");
    }

    private String ident() {
        Tok t = next();
        if (t.t() != T.IDENT) throw new Unsupported("expected a name but found `" + t.s() + "` (line " + t.line() + ")");
        return t.s();
    }

    /** Splits a `>>` or `>>>` (or `>=`, `>>=`) token so a type's closing `>` can be taken. */
    private void expectCloseAngle() {
        Tok t = peek();
        if (t.is(">")) { p++; return; }
        if (t.t() == T.OP && t.s().startsWith(">")) {
            // Split for good, so parsing this again (after a look-ahead) sees the same tokens.
            toks.set(p, new Tok(T.OP, ">", t.line()));
            toks.add(p + 1, new Tok(T.OP, t.s().substring(1), t.line()));
            p++;
            return;
        }
        throw new Unsupported("expected `>` (line " + t.line() + ")");
    }

    private Unit unit(String file) {
        String pkg = "";
        List<String> imports = new ArrayList<>();
        List<ClassDecl> classes = new ArrayList<>();
        while (peek().t() != T.EOF) {
            if (accept("package")) {
                pkg = qualifiedName();
                expect(";");
            } else if (accept("import")) {
                boolean st = accept("static");
                StringBuilder b = new StringBuilder(ident());
                while (accept(".")) b.append('.').append(accept("*") ? "*" : ident());
                expect(";");
                imports.add((st ? "static " : "") + b);
            } else if (accept(";")) {
                // stray
            } else {
                classes.add(classDecl(modifiers(), null));
            }
        }
        return new Unit(pkg, imports, classes, file);
    }

    private String qualifiedName() {
        StringBuilder b = new StringBuilder(ident());
        while (at(".") && peek(1).t() == T.IDENT) {
            p++;
            b.append('.').append(ident());
        }
        return b.toString();
    }

    private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "static", "final", "abstract", "native",
            "synchronized", "transient", "volatile", "strictfp", "default", "sealed", "non-sealed");

    private Set<String> modifiers() {
        Set<String> mods = new java.util.LinkedHashSet<>();
        while (true) {
            if (at("@") && !peek(1).is("interface")) {
                p++;
                String a = qualifiedName();
                mods.add("@" + a.substring(a.lastIndexOf('.') + 1));
                if (at("(")) skipBalanced("(", ")");
            } else if (peek().t() == T.IDENT && MODIFIERS.contains(peek().s())) {
                mods.add(next().s());
            } else if (at("non") && peek(1).is("-") && peek(2).is("sealed")) {
                p += 3;
            } else {
                return mods;
            }
        }
    }

    private void skipBalanced(String open, String close) {
        expect(open);
        int depth = 1;
        while (depth > 0) {
            Tok t = next();
            if (t.t() == T.EOF) throw new Unsupported("unbalanced `" + open + "`");
            if (t.is(open)) depth++;
            else if (t.is(close)) depth--;
        }
    }

    /** From an opening token, the index just after its matching close. */
    private int matchingEnd(int from, String open, String close) {
        int depth = 0;
        for (int k = from; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.t() == T.EOF) break;
            if (t.is(open)) depth++;
            else if (t.is(close) && --depth == 0) return k + 1;
        }
        throw new Unsupported("unbalanced `" + open + "`");
    }

    private ClassDecl classDecl(Set<String> mods, ClassDecl outer) {
        ClassDecl c = new ClassDecl();
        c.mods = mods;
        c.outer = outer;
        c.line = peek().line();
        if (accept("@")) { expect("interface"); c.kind = "annotation"; }
        else if (accept("class")) c.kind = "class";
        else if (accept("enum")) c.kind = "enum";
        else if (accept("interface")) c.kind = "interface";
        else if (accept("record")) c.kind = "record";
        else throw new Unsupported("expected a class at `" + peek().s() + "` (line " + peek().line() + ")");
        c.name = ident();
        c.qualified = outer == null ? c.name : outer.qualified + "." + c.name;
        if (at("<")) { c.generic = true; typeParams(); }
        if (c.kind.equals("record") && at("(")) {
            expect("(");
            if (!at(")")) {
                do {
                    modifiers();
                    TypeRef t = type();
                    c.components.add(new Param(t, ident(), false));
                } while (accept(","));
            }
            expect(")");
        }
        if (accept("extends")) {
            c.ext = type();
            while (accept(",")) c.impls.add(type());
        }
        if (accept("implements")) {
            c.impls.add(type());
            while (accept(",")) c.impls.add(type());
        }
        if (accept("permits")) {
            type();
            while (accept(",")) type();
        }
        expect("{");
        if (c.kind.equals("enum")) enumConstants(c);
        while (!accept("}")) {
            if (peek().t() == T.EOF) throw new Unsupported("unclosed class " + c.name);
            member(c);
        }
        return c;
    }

    private void typeParams() {
        expect("<");
        int depth = 1;
        while (depth > 0) {
            Tok t = peek();
            if (t.t() == T.EOF) throw new Unsupported("unclosed type parameters");
            if (t.is("<")) { depth++; p++; }
            else if (t.t() == T.OP && t.s().startsWith(">")) { depth--; expectCloseAngle(); }
            else p++;
        }
    }

    private void enumConstants(ClassDecl c) {
        while (!at(";") && !at("}")) {
            modifiers();
            String name = ident();
            List<Expr> args = List.of();
            boolean body = false;
            if (at("(")) {
                int start = p;
                try {
                    args = arguments();
                } catch (Unsupported e) {
                    c.errors.add("enum constant " + name + ": " + e.getMessage());
                    p = matchingEnd(start, "(", ")");
                }
            }
            if (at("{")) {
                body = true;
                p = matchingEnd(p, "{", "}");
            }
            c.constants.add(new EnumConst(name, args, body));
            if (!accept(",")) break;
        }
        accept(";");
    }

    private void member(ClassDecl c) {
        if (accept(";")) return;
        int startLine = peek().line();
        if (at("{") || (at("static") && peek(1).is("{"))) {
            if (accept("static")) c.staticInit = true;
            else c.errors.add("an instance initializer block");
            p = matchingEnd(p, "{", "}");
            return;
        }
        Set<String> mods = modifiers();
        if (at("class") || at("enum") || at("interface") || at("record") || (at("@") && peek(1).is("interface"))) {
            c.nested.add(classDecl(mods, c));
            return;
        }
        boolean generic = false;
        if (at("<")) { generic = true; typeParams(); }
        if (peek().t() == T.IDENT && peek().s().equals(c.name) && peek(1).is("(")) {
            p++;
            MethodDecl m = new MethodDecl();
            m.mods = mods;
            m.name = "<init>";
            m.generic = generic;
            m.line = startLine;
            methodRest(m);
            c.methods.add(m);
            return;
        }
        TypeRef type = type();
        String name = ident();
        if (at("(")) {
            MethodDecl m = new MethodDecl();
            m.mods = mods;
            m.ret = type;
            m.name = name;
            m.generic = generic;
            m.line = startLine;
            methodRest(m);
            c.methods.add(m);
            return;
        }
        // Fields: name [= init] {, name [= init]} ;
        while (true) {
            FieldDecl f = new FieldDecl();
            f.mods = mods;
            f.line = peek().line();
            int dims = 0;
            while (accept("[")) { expect("]"); dims++; }
            f.type = dims == 0 ? type : new TypeRef(type.name(), type.args(), type.dims() + dims);
            f.name = name;
            if (accept("=")) {
                int save = p;
                try {
                    f.init = at("{") ? arrayInit() : expr();
                } catch (Unsupported e) {
                    f.error = e.getMessage();
                    p = save;
                    skipToFieldEnd();
                }
            }
            c.fields.add(f);
            if (accept(",")) {
                name = ident();
                continue;
            }
            expect(";");
            return;
        }
    }

    /** After a failed initializer: to the `,` or `;` that ends it, outside brackets. */
    private void skipToFieldEnd() {
        int depth = 0;
        while (true) {
            Tok t = peek();
            if (t.t() == T.EOF) return;
            if (t.is("(") || t.is("{") || t.is("[")) depth++;
            else if (t.is(")") || t.is("}") || t.is("]")) depth--;
            else if (depth == 0 && (t.is(";") || t.is(","))) return;
            p++;
        }
    }

    private void methodRest(MethodDecl m) {
        expect("(");
        if (!at(")")) {
            do {
                modifiers();
                TypeRef t = type();
                boolean varargs = accept("...");
                String name = ident();
                int dims = 0;
                while (accept("[")) { expect("]"); dims++; }
                if (varargs) t = t.array();
                if (dims > 0) t = new TypeRef(t.name(), t.args(), t.dims() + dims);
                m.params.add(new Param(t, name, varargs));
            } while (accept(","));
        }
        expect(")");
        while (accept("[")) expect("]");
        if (accept("throws")) {
            do m.throwsTypes.add(type().simple()); while (accept(","));
        }
        if (accept("default")) { // annotation member default
            while (!at(";")) next();
        }
        if (accept(";")) return;
        int start = p;
        matchingEnd(p, "{", "}");
        try {
            m.body = block();
        } catch (Unsupported e) {
            m.body = null;
            m.error = e.getMessage();
            // Splitting `>>` may have added tokens: find the end again.
            p = matchingEnd(start, "{", "}");
        }
    }

    private static final Set<String> PRIMS = Set.of("int", "long", "float", "double", "boolean", "char", "byte", "short", "void");

    /** Skips type-use annotations such as `@Nullable` in `Outer.@Nullable Inner` or `int @Nullable []`. */
    private void typeAnnotations() {
        while (at("@") && peek(1).t() == T.IDENT && !peek(1).is("interface")) {
            p++;
            qualifiedName();
            if (at("(")) skipBalanced("(", ")");
        }
    }

    TypeRef type() {
        typeAnnotations();
        String name;
        if (peek().t() == T.IDENT && PRIMS.contains(peek().s())) name = next().s();
        else if (accept("?")) {
            if (accept("extends") || accept("super")) return type();
            name = "Object";
        } else name = ident();
        List<TypeRef> args = new ArrayList<>();
        while (true) {
            if (at("<")) {
                p++;
                if (!at(">")) {
                    do args.add(type()); while (accept(","));
                }
                expectCloseAngle();
            }
            if (at(".") && (peek(1).t() == T.IDENT || peek(1).is("@"))) {
                p++;
                typeAnnotations();
                name = name + "." + ident();
                continue;
            }
            break;
        }
        int dims = 0;
        typeAnnotations();
        while (at("[") && peek(1).is("]")) { p += 2; dims++; typeAnnotations(); }
        return new TypeRef(name, args, dims);
    }

    // ---- statements ----

    Block block() {
        expect("{");
        List<Stmt> out = new ArrayList<>();
        while (!accept("}")) {
            if (peek().t() == T.EOF) throw new Unsupported("unclosed block");
            out.add(stmt());
        }
        return new Block(out);
    }

    private Stmt stmt() {
        Tok t = peek();
        if (t.is("{")) return block();
        if (t.is(";")) { p++; return new Empty(); }
        if (t.t() == T.IDENT && peek(1).is(":") ) throw new Unsupported("a labelled statement");
        if (t.t() == T.IDENT) {
            switch (t.s()) {
                case "if": {
                    p++;
                    expect("(");
                    Expr c = expr();
                    expect(")");
                    Stmt th = stmt();
                    Stmt el = accept("else") ? stmt() : null;
                    return new If(c, th, el);
                }
                case "while": {
                    p++;
                    expect("(");
                    Expr c = expr();
                    expect(")");
                    return new While(c, stmt());
                }
                case "do": {
                    p++;
                    Stmt body = stmt();
                    expect("while");
                    expect("(");
                    Expr c = expr();
                    expect(")");
                    expect(";");
                    return new DoWhile(body, c);
                }
                case "for":
                    return forStmt();
                case "return": {
                    p++;
                    Expr e = at(";") ? null : expr();
                    expect(";");
                    return new Return(e);
                }
                case "break": {
                    p++;
                    String label = peek().t() == T.IDENT ? ident() : null;
                    expect(";");
                    return new Break(label);
                }
                case "continue": {
                    p++;
                    String label = peek().t() == T.IDENT ? ident() : null;
                    expect(";");
                    return new Continue(label);
                }
                case "throw": {
                    p++;
                    Expr e = expr();
                    expect(";");
                    return new Throw(e);
                }
                case "try":
                    return tryStmt();
                case "switch":
                    return switchStmt();
                case "synchronized": {
                    p++;
                    expect("(");
                    Expr lock = expr();
                    expect(")");
                    return new Sync(lock, block());
                }
                case "class": case "interface": case "enum": case "record":
                    throw new Unsupported("a class declared inside a method");
                case "assert":
                    throw new Unsupported("an assert");
                case "yield":
                    if (!peek(1).is("=") && !peek(1).is("(") && !peek(1).is(".")) {
                        p++;
                        Expr y = expr();
                        expect(";");
                        return new Yield(y);
                    }
                    break;
                default:
                    break;
            }
        }
        if (looksLikeLocal()) {
            Local l = localDecl();
            expect(";");
            return l;
        }
        Expr e = expr();
        expect(";");
        return new ExprStmt(e);
    }

    /** `Type name` (with `final` or annotations before) starts a local variable. */
    private boolean looksLikeLocal() {
        int save = p;
        try {
            modifiers();
            if (peek().t() != T.IDENT) return false;
            if (peek().is("var")) return peek(1).t() == T.IDENT;
            type();
            return peek().t() == T.IDENT && (peek(1).is("=") || peek(1).is(";") || peek(1).is(",") || peek(1).is(":") || peek(1).is("["));
        } catch (Unsupported e) {
            return false;
        } finally {
            p = save;
        }
    }

    private Local localDecl() {
        modifiers();
        if (at("var")) throw new Unsupported("`var`");
        TypeRef type = type();
        List<Declarator> vars = new ArrayList<>();
        do {
            String name = ident();
            int dims = 0;
            while (accept("[")) { expect("]"); dims++; }
            Expr init = null;
            if (accept("=")) init = at("{") ? arrayInit() : expr();
            vars.add(new Declarator(name, dims, init));
        } while (accept(","));
        return new Local(type, vars);
    }

    private Stmt forStmt() {
        expect("for");
        expect("(");
        int save = p;
        if (looksLikeLocal()) {
            modifiers();
            TypeRef t = type();
            String name = ident();
            if (accept(":")) {
                Expr it = expr();
                expect(")");
                return new ForEach(t, name, it, stmt());
            }
            p = save;
        }
        List<Stmt> init = new ArrayList<>();
        if (!at(";")) {
            if (looksLikeLocal()) init.add(localDecl());
            else {
                do init.add(new ExprStmt(expr())); while (accept(","));
            }
        }
        expect(";");
        Expr cond = at(";") ? null : expr();
        expect(";");
        List<Expr> update = new ArrayList<>();
        if (!at(")")) {
            do update.add(expr()); while (accept(","));
        }
        expect(")");
        return new For(init, cond, update, stmt());
    }

    private Stmt tryStmt() {
        expect("try");
        boolean resources = false;
        List<Local> res = new ArrayList<>();
        if (at("(")) {
            resources = true;
            int save = p;
            try {
                expect("(");
                do {
                    if (at(")")) break;
                    modifiers();
                    TypeRef t = type();
                    String name = ident();
                    expect("=");
                    res.add(new Local(t, List.of(new Declarator(name, 0, expr()))));
                } while (accept(";"));
                expect(")");
            } catch (Unsupported u) {
                p = save;
                res.clear();
                skipBalanced("(", ")");
            }
        }
        Block body = block();
        List<Catch> catches = new ArrayList<>();
        while (accept("catch")) {
            expect("(");
            modifiers();
            List<String> types = new ArrayList<>();
            types.add(type().simple());
            while (accept("|")) types.add(type().simple());
            String name = ident();
            expect(")");
            catches.add(new Catch(types, name, block()));
        }
        Block fin = accept("finally") ? block() : null;
        return new Try(body, catches, fin, resources, res);
    }

    /** Inside `case A, B ->`: the arrow ends the labels, it isn't a lambda. */
    private boolean caseLabel;

    private Stmt switchStmt() {
        expect("switch");
        expect("(");
        Expr sel = expr();
        expect(")");
        expect("{");
        List<Case> cases = new ArrayList<>();
        while (!accept("}")) {
            List<Expr> labels = new ArrayList<>();
            boolean isDefault = false;
            if (accept("default")) isDefault = true;
            else {
                expect("case");
                caseLabel = true;
                try {
                    do labels.add(ternary()); while (accept(","));
                } finally {
                    caseLabel = false;
                }
            }
            if (accept("->")) {
                Stmt s;
                if (at("{")) s = block();
                else if (at("throw")) s = stmt();
                else {
                    Expr e = expr();
                    expect(";");
                    s = new ExprStmt(e);
                }
                cases.add(new Case(isDefault ? List.of() : labels, true, List.of(s)));
            } else {
                expect(":");
                List<Stmt> body = new ArrayList<>();
                while (!at("case") && !at("default") && !at("}")) body.add(stmt());
                cases.add(new Case(isDefault ? List.of() : labels, false, body));
            }
        }
        return new Switch(sel, cases);
    }

    // ---- expressions ----

    Expr expr() {
        Expr l = ternary();
        Tok t = peek();
        if (t.t() == T.OP && (t.s().equals("=") || (t.s().endsWith("=") && t.s().length() >= 2
                && !t.s().equals("==") && !t.s().equals("!=") && !t.s().equals("<=") && !t.s().equals(">=")))) {
            p++;
            Expr r = at("{") ? arrayInit() : expr();
            return new Assign(t.s(), l, r);
        }
        return l;
    }

    private Expr ternary() {
        Expr c = binary(0);
        if (accept("?")) {
            Expr a = ternary();
            expect(":");
            Expr b = ternary();
            return new Cond(c, a, b);
        }
        return c;
    }

    private static final String[][] LEVELS = {
            {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">=", "instanceof"},
            {"<<", ">>", ">>>"}, {"+", "-"}, {"*", "/", "%"}};

    private Expr binary(int level) {
        if (level == LEVELS.length) return unary();
        Expr l = binary(level + 1);
        while (true) {
            Tok t = peek();
            String op = null;
            for (String o : LEVELS[level]) if (t.is(o)) op = o;
            if (op == null) return l;
            p++;
            if (op.equals("instanceof")) {
                accept("final");
                TypeRef type = type();
                String binding = peek().t() == T.IDENT && !peek().is("instanceof") ? ident() : null;
                l = new InstanceOf(l, type, binding);
                continue;
            }
            l = new Binary(op, l, binary(level + 1));
        }
    }

    private Expr unary() {
        Tok t = peek();
        if (t.is("+") || t.is("-") || t.is("!") || t.is("~")) {
            p++;
            Expr e = unary();
            if (t.s().equals("-") && e instanceof Lit lit && (lit.kind().equals("int") || lit.kind().equals("long")
                    || lit.kind().equals("float") || lit.kind().equals("double")) && !lit.text().startsWith("-")) {
                return new Lit(lit.kind(), "-" + lit.text());
            }
            return new Unary(t.s(), e, false);
        }
        if (t.is("++") || t.is("--")) {
            p++;
            return new Unary(t.s(), unary(), false);
        }
        if (t.is("(") && isCast()) {
            p++;
            TypeRef type = type();
            while (accept("&")) type();
            expect(")");
            return new Cast(type, unary());
        }
        return postfix(primary());
    }

    /** `(` Type `)` followed by something that starts an operand. */
    private boolean isCast() {
        int save = p;
        try {
            p++;
            if (peek().t() != T.IDENT) return false;
            boolean prim = PRIMS.contains(peek().s());
            type();
            while (accept("&")) type();
            if (!accept(")")) return false;
            Tok n = peek();
            if (prim) return !n.is(")") && !n.is(";") && !n.is(",") && !(n.t() == T.OP && n.s().matches("[*/%=<>&|^?:.]+|==|!="));
            return n.t() == T.IDENT || n.t() == T.NUM || n.t() == T.STR || n.t() == T.CHR || n.is("(") || n.is("!") || n.is("~");
        } catch (Unsupported e) {
            return false;
        } finally {
            p = save;
        }
    }

    private Expr primary() {
        Tok t = next();
        switch (t.t()) {
            case NUM:
                return number(t.s());
            case STR:
                return new Lit("string", t.s());
            case CHR:
                return new Lit("char", t.s());
            case OP:
                if (t.s().equals("(")) {
                    if (lambdaAhead(p - 1)) return lambda(true);
                    Expr e = expr();
                    expect(")");
                    return e;
                }
                throw new Unsupported("unexpected `" + t.s() + "` (line " + t.line() + ")");
            case IDENT:
                break;
            default:
                throw new Unsupported("unexpected end");
        }
        switch (t.s()) {
            case "true": case "false":
                return new Lit("boolean", t.s());
            case "null":
                return new Lit("null", "null");
            case "this":
                // this(...): the constructor starts as another one of the class.
                if (at("(")) return new Call(null, "this", arguments());
                return new This();
            case "super":
                // Read so the rest of the method can be; the translator refuses it where it's used.
                if (at("(")) return new Call(null, "super", arguments());
                return new Name("super");
            case "new":
                return creator();
            case "switch":
                p--;
                return new SwitchExpr((Switch) switchStmt());
            default:
                break;
        }
        if (at("->") && !caseLabel) {
            p--;
            return lambda(false);
        }
        if (PRIMS.contains(t.s()) && (at("[") || at("."))) {
            if (at(".") && peek(1).is("class")) {
                // `float.class`: what the translator makes of a class literal (usually nothing).
                accept(".");
                expect("class");
                return new Field(new Name(t.s()), "class");
            }
            throw new Unsupported("a method reference");
        }
        if (at("(")) return new Call(null, t.s(), arguments());
        return new Name(t.s());
    }

    /** After `(` (parens) or at the single parameter's name. */
    private Expr lambda(boolean parens) {
        List<String> params = new ArrayList<>();
        if (parens) {
            if (!at(")")) {
                do {
                    modifiers();
                    // `(a, b)` or `(Type a, Type b)`
                    if (peek(1).is(",") || peek(1).is(")")) params.add(ident());
                    else {
                        type();
                        params.add(ident());
                    }
                } while (accept(","));
            }
            expect(")");
        } else {
            params.add(ident());
        }
        expect("->");
        Object body = at("{") ? block() : expr();
        return new Lambda(params, body);
    }

    private boolean lambdaAhead(int open) {
        try {
            int end = matchingEnd(open, "(", ")");
            return toks.get(end).is("->");
        } catch (Unsupported e) {
            return false;
        }
    }

    private static Lit number(String s) {
        String low = s.toLowerCase();
        if (low.startsWith("0x")) return new Lit(low.endsWith("l") ? "long" : "int", s);
        if (low.endsWith("f")) return new Lit("float", s);
        if (low.endsWith("d")) return new Lit("double", s);
        if (low.endsWith("l")) return new Lit("long", s);
        if (low.contains(".") || low.contains("e")) return new Lit("double", s);
        return new Lit("int", s);
    }

    private Expr creator() {
        TypeRef base;
        String name;
        if (peek().t() == T.IDENT && PRIMS.contains(peek().s())) name = next().s();
        else name = qualifiedName();
        List<TypeRef> args = new ArrayList<>();
        if (at("<")) {
            p++;
            if (!at(">")) {
                do args.add(type()); while (accept(","));
            }
            expectCloseAngle();
        }
        base = new TypeRef(name, args, 0);
        if (at("[")) {
            List<Expr> dims = new ArrayList<>();
            int n = 0;
            while (at("[")) {
                p++;
                if (at("]")) { p++; n++; continue; }
                dims.add(expr());
                expect("]");
                n++;
            }
            ArrayInit init = at("{") ? arrayInit() : null;
            return new NewArray(new TypeRef(name, args, n), dims, init);
        }
        List<Expr> a = arguments();
        if (at("{")) throw new Unsupported("an anonymous class");
        return new New(base, a);
    }

    private ArrayInit arrayInit() {
        expect("{");
        List<Expr> items = new ArrayList<>();
        while (!accept("}")) {
            items.add(at("{") ? arrayInit() : expr());
            if (!accept(",")) {
                expect("}");
                break;
            }
        }
        return new ArrayInit(items);
    }

    private List<Expr> arguments() {
        expect("(");
        List<Expr> out = new ArrayList<>();
        if (accept(")")) return out;
        do out.add(expr()); while (accept(","));
        expect(")");
        return out;
    }

    private Expr postfix(Expr e) {
        while (true) {
            if (at(".")) {
                p++;
                if (at("<")) {
                    // `Foo.<T>bar()`: the type arguments say nothing the translator needs.
                    int depth = 0;
                    do {
                        Tok t = next();
                        if (t.s().equals("<")) depth++;
                        else if (t.s().equals(">")) depth--;
                        else if (t.s().equals(">>")) depth -= 2;
                        else if (t.s().equals(">>>")) depth -= 3;
                    } while (depth > 0);
                }
                if (at("new")) throw new Unsupported("an inner class creation");
                if (accept("class")) {
                    e = new Field(e, "class");
                    continue;
                }
                String name = ident();
                if (at("(")) e = new Call(e, name, arguments());
                else e = new Field(e, name);
            } else if (at("[") && peek(1).is("]")) {
                // `String[]::new`, `int[].class`
                String dims = "";
                while (accept("[")) {
                    expect("]");
                    dims += "[]";
                }
                if (accept("::")) {
                    expect("new");
                    e = new MethodRef(new Name((e instanceof Name n ? n.id() : "array") + dims), "new");
                } else {
                    expect(".");
                    expect("class");
                    e = new Field(e, "class");
                }
            } else if (at("[")) {
                p++;
                Expr idx = expr();
                expect("]");
                e = new Index(e, idx);
            } else if (at("++") || at("--")) {
                e = new Unary(next().s(), e, true);
            } else if (at("::")) {
                p++;
                String name = accept("new") ? "new" : ident();
                e = new MethodRef(e, name);
            } else {
                return e;
            }
        }
    }
}
