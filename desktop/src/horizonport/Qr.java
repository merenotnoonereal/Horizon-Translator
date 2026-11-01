package horizonport;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A small QR code encoder for the Support tab's wallet addresses: byte mode, error correction
 * level M, versions 1 to 10 (up to 213 bytes, plenty for any wallet address). Follows the QR
 * standard (ISO/IEC 18004) and picks the mask with the lowest penalty, like other encoders.
 */
final class Qr {
    // Error correction level M, per version 1..10 (index 0 unused).
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    final int version, size;
    private final boolean[][] dark;     // [y][x]
    private final boolean[][] function; // finder, timing, alignment, format and version modules

    static Qr encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 10; v++) {
            int bits = 4 + (v <= 9 ? 8 : 16) + data.length * 8;
            if (bits <= dataCodewords(v) * 8) return new Qr(v, data);
        }
        throw new IllegalArgumentException("Too long for a QR code: " + data.length + " bytes");
    }

    boolean isDark(int x, int y) { return x >= 0 && y >= 0 && x < size && y < size && dark[y][x]; }

    /** Black on white, with the 4-module quiet zone, each module {@code scale} pixels. */
    BufferedImage image(int scale) {
        int px = (size + 8) * scale;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++)
            for (int x = 0; x < px; x++)
                img.setRGB(x, y, isDark(x / scale - 4, y / scale - 4) ? 0x000000 : 0xFFFFFF);
        return img;
    }

    private Qr(int version, byte[] data) {
        this.version = version;
        this.size = version * 4 + 17;
        dark = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns();
        drawCodewords(addEccAndInterleave(dataBytes(data)));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < bestPenalty) { best = m; bestPenalty = p; }
            applyMask(m); // XOR again undoes it
        }
        applyMask(best);
        drawFormatBits(best);
    }

    // ---------------------------------------------------------------- data

    private static int rawDataModules(int v) {
        int r = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int n = v / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (v >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int v) { return rawDataModules(v) / 8 - ECC_PER_BLOCK[v] * BLOCKS[v]; }

    /** Mode, length, the bytes, terminator and padding. */
    private byte[] dataBytes(byte[] data) {
        int capacity = dataCodewords(version);
        byte[] out = new byte[capacity];
        int[] pos = {0};
        put(out, pos, 0b0100, 4);
        put(out, pos, data.length, version <= 9 ? 8 : 16);
        for (byte b : data) put(out, pos, b & 0xFF, 8);
        put(out, pos, 0, Math.min(4, capacity * 8 - pos[0]));
        pos[0] = (pos[0] + 7) / 8 * 8;
        for (int pad = 0xEC; pos[0] < capacity * 8; pad ^= 0xEC ^ 0x11) put(out, pos, pad, 8);
        return out;
    }

    private static void put(byte[] out, int[] pos, int value, int bits) {
        for (int i = bits - 1; i >= 0; i--, pos[0]++)
            if (((value >>> i) & 1) != 0) out[pos[0] >>> 3] |= (byte) (0x80 >>> (pos[0] & 7));
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = BLOCKS[version], eccLen = ECC_PER_BLOCK[version];
        int raw = rawDataModules(version) / 8;
        int numShort = numBlocks - raw % numBlocks, shortLen = raw / numBlocks;
        byte[] divisor = rsDivisor(eccLen);
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int len = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, divisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < blocks[0].length; i++)
            for (int j = 0; j < numBlocks; j++)
                if (i != shortLen - eccLen || j >= numShort) out[n++] = blocks[j][i];
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return r;
    }

    /** Multiplication in GF(2^8) with the QR polynomial 0x11D. */
    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------------------------------------------------------------- drawing

    private void set(int x, int y, boolean d) { dark[y][x] = d; function[y][x] = true; }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0))
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        drawFormatBits(0); // reserves the area; drawn for real after masking
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = (long) version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b);
                set(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private void drawFormatBits(int mask) {
        int data = mask; // level M's format bits are 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                boolean flip = switch (m) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                if (flip && !function[y][x]) dark[y][x] = !dark[y][x];
            }
    }

    /** The standard's four penalty rules (long runs, 2x2 blocks, finder look-alikes, balance). */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++)
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b <= size; b++) {
                    if (b < size && at(pass, a, b) == at(pass, a, b - 1)) { run++; continue; }
                    if (run >= 5) p += 3 + run - 5;
                    run = 1;
                }
                for (int b = -4; b < size; b++) {
                    if (at(pass, a, b) || at(pass, a, b + 1) || at(pass, a, b + 2) || at(pass, a, b + 3)) continue;
                    if (finderLike(pass, a, b + 4) || finderLike(pass, a, b - 7)) p += 40;
                }
            }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = dark[y][x];
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) p += 3;
            }
        int darkCount = 0;
        for (boolean[] row : dark) for (boolean d : row) if (d) darkCount++;
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return p + (long) k * 10;
    }

    /** Module b of row a (pass 0) or column a (pass 1); outside the code counts as light. */
    private boolean at(int pass, int a, int b) { return pass == 0 ? isDark(b, a) : isDark(a, b); }

    /** Dark-light-dark-dark-dark-light-dark starting at b. */
    private boolean finderLike(int pass, int a, int b) {
        return at(pass, a, b) && !at(pass, a, b + 1) && at(pass, a, b + 2) && at(pass, a, b + 3)
                && at(pass, a, b + 4) && !at(pass, a, b + 5) && at(pass, a, b + 6);
    }
}
