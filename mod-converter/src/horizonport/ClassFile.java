package horizonport;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the parts of a .class file the converter needs: names, superclass, annotations and the
 * fields and methods it references. It reads the constant pool only and never decompiles code.
 */
final class ClassFile {
    record Annotation(String type, Map<String, Object> values) {
        Object get(String key) {
            return values.get(key);
        }
    }

    record Member(String name, String desc, List<Annotation> annotations) {}

    /** A field or method this class uses: kind is "field" or "method". */
    record Ref(String kind, String owner, String name, String desc) {
        String key() {
            return owner + "#" + name;
        }
    }

    String name;
    String superName;
    final List<String> interfaces = new ArrayList<>();
    final List<Annotation> annotations = new ArrayList<>();
    final List<Member> fields = new ArrayList<>();
    final List<Member> methods = new ArrayList<>();
    final List<Ref> refs = new ArrayList<>();
    final Set<String> classRefs = new LinkedHashSet<>();
    /** String literals: key names, command names, translation keys. */
    final List<String> strings = new ArrayList<>();

    private Object[] cp;
    private int[][] cpIdx;

    static ClassFile parse(byte[] bytes) throws IOException {
        ClassFile cf = new ClassFile();
        cf.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        return cf;
    }

    private void read(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        cp = new Object[n];
        cpIdx = new int[n][];
        int[] tags = new int[n];
        for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> cp[i] = in.readUTF();
                case 3 -> cp[i] = in.readInt();
                case 4 -> cp[i] = in.readFloat();
                case 5 -> { cp[i] = in.readLong(); i++; }
                case 6 -> { cp[i] = in.readDouble(); i++; }
                case 7, 8, 16, 19, 20 -> cpIdx[i] = new int[] {in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> cpIdx[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> cpIdx[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        for (int i = 1; i < n; i++) {
            switch (tags[i]) {
                case 7 -> classRefs.add(utf(cpIdx[i][0]));
                case 8 -> strings.add(utf(cpIdx[i][0]));
                case 9, 10, 11 -> {
                    int[] nat = cpIdx[cpIdx[i][1]];
                    refs.add(new Ref(tags[i] == 9 ? "field" : "method", className(cpIdx[i][0]), utf(nat[0]), utf(nat[1])));
                }
                default -> {}
            }
        }
        in.readUnsignedShort();
        name = className(in.readUnsignedShort());
        int sup = in.readUnsignedShort();
        superName = sup == 0 ? null : className(sup);
        int ni = in.readUnsignedShort();
        for (int i = 0; i < ni; i++) interfaces.add(className(in.readUnsignedShort()));
        readMembers(in, fields);
        readMembers(in, methods);
        readAttributes(in, annotations);
    }

    private void readMembers(DataInputStream in, List<Member> out) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            String n = utf(in.readUnsignedShort());
            String d = utf(in.readUnsignedShort());
            List<Annotation> anns = new ArrayList<>();
            readAttributes(in, anns);
            out.add(new Member(n, d, anns));
        }
    }

    private void readAttributes(DataInputStream in, List<Annotation> anns) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String an = utf(in.readUnsignedShort());
            byte[] data = new byte[in.readInt()];
            in.readFully(data);
            // Mixin's annotations are class-retained, so they sit in the invisible table.
            if (an.equals("RuntimeVisibleAnnotations") || an.equals("RuntimeInvisibleAnnotations")) {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
                int na = d.readUnsignedShort();
                for (int k = 0; k < na; k++) anns.add(annotation(d));
            }
        }
    }

    private Annotation annotation(DataInputStream d) throws IOException {
        String type = utf(d.readUnsignedShort());
        int pairs = d.readUnsignedShort();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs; i++) {
            String key = utf(d.readUnsignedShort());
            values.put(key, elementValue(d));
        }
        return new Annotation(type, values);
    }

    private Object elementValue(DataInputStream d) throws IOException {
        char tag = (char) d.readUnsignedByte();
        return switch (tag) {
            case 'B', 'C', 'I', 'S', 'D', 'F', 'J' -> cp[d.readUnsignedShort()];
            case 'Z' -> ((Integer) cp[d.readUnsignedShort()]) != 0;
            case 's' -> utf(d.readUnsignedShort());
            case 'e' -> {
                d.readUnsignedShort();
                yield utf(d.readUnsignedShort());
            }
            case 'c' -> utf(d.readUnsignedShort());
            case '@' -> annotation(d);
            case '[' -> {
                int n = d.readUnsignedShort();
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(elementValue(d));
                yield l;
            }
            default -> throw new IOException("unknown annotation value tag " + tag);
        };
    }

    private String utf(int i) {
        return (String) cp[i];
    }

    private String className(int i) {
        return utf(cpIdx[i][0]);
    }

    Annotation annotation(String type) {
        for (Annotation a : annotations) if (a.type().equals(type)) return a;
        return null;
    }

    /** "Lnet/minecraft/client/Minecraft;" to "net/minecraft/client/Minecraft". */
    static String descToInternal(String desc) {
        if (desc.startsWith("L") && desc.endsWith(";")) return desc.substring(1, desc.length() - 1);
        return desc;
    }

    static String simpleName(String internal) {
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }
}
