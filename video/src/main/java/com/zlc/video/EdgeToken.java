package com.zlc.video;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The "v2" Sec-MS-GEC token Edge's read-aloud service has required since late 2026:
 * "&lt;CS64 hex&gt;-&lt;salted SHA-256 hex&gt;" of the 5-minute-rounded Windows time + client token.
 *
 * Straight port of edge_tts/drm.py (edge-tts 7.3.1, LGPL-3.0 library by rany2) to Java.
 * All arithmetic is mod 2^32, which Java ints do natively. EdgeTokenTest pins it to values
 * produced by the Python original.
 */
final class EdgeToken {
    private EdgeToken() { }

    static final long WIN_EPOCH = 11644473600L;

    private static final int[] SHA256_INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    // Hash state after the service's hidden 57-byte salt.
    private static final int[] SHA256_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2};
    private static final int[] SHA256_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
            0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564};
    private static final int[] K = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2};
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
    private static final int[] K_BLOCK;
    static {
        K_BLOCK = K.clone();
        K_BLOCK[14] = SHA256_ADJUSTMENT;
    }
    private static final int SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static final int[][] CS64_COEFFICIENTS = {
            {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D}, {0x3BEB65BC, 0x29514038},
            {0xC89C5933, 0xB328CBDF}, {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB}, {0x06064CF3, 0xFA51A3DA},
            {0xDA79FA60, 0x207CC464}, {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167}};
    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJUSTMENT0 = 0xF2F90B1A;
    private static final int CS64_ADJUSTMENT1 = 0x0B1A0000;

    private static int rotr(int x, int n) { return Integer.rotateRight(x, n); }
    private static int rol16(int x) { return Integer.rotateLeft(x, 16); }
    private static int sigma0(int x) { return rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3); }
    private static int sigma1(int x) { return rotr(x, 17) ^ rotr(x, 19) ^ (x >>> 10); }

    /** Standard SHA-256 compression rounds, optionally resuming at round 14 of the first block. */
    private static int[] rounds(int[] state, int[] w, int first) {
        int[] k = first == 14 ? K_BLOCK : K;
        int a = state[0], b = state[1], c = state[2], d = state[3], e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = first; i < 64; i++) {
            int t1 = h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + (g ^ (e & (f ^ g))) + k[i] + w[i];
            int t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) | (c & (a | b)));
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }

    private static void expand(int[] w, int from) {
        for (int i = from; i < 64; i++) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16];
    }

    private static int[] init(byte[] d) {
        int[] w = new int[64];
        w[14] = ((d[0] & 0xff) << 16) | ((d[1] & 0xff) << 8) | (d[2] & 0xff);
        w[15] = ((d[3] & 0xff) << 24) | ((d[4] & 0xff) << 16) | ((d[5] & 0xff) << 8) | (d[6] & 0xff);
        for (int i = 16; i < 30; i++) {
            int v = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) v += i == 16 ? (sigma1(w[i - 2]) ^ SIGMA1_ADJUSTMENT) : sigma1(w[i - 2]);
            if (i - 7 >= 14) v += w[i - 7];
            if (i - 15 >= 14) v += i == 29 ? (sigma0(w[i - 15]) ^ SIGMA0_ADJUSTMENT) : sigma0(w[i - 15]);
            w[i] = v;
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA256_ADJUSTMENT - K[14];
        expand(w, 31);
        int[] work = rounds(SHA256_STATE, w, 14);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = SHA256_INITIAL[i] + work[i];
        return out;
    }

    /** SHA-256 of the service's hidden salt followed by {@code data}. */
    static byte[] sha256(byte[] data) {
        if (data.length < 7) throw new IllegalArgumentException("Token salt must contain at least seven bytes");
        long length = 57 + data.length;
        int rest = data.length - 7;
        int zeros = (int) Math.floorMod(55 - length, 64L);
        byte[] padded = new byte[rest + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, rest);
        padded[rest] = (byte) 0x80;
        long bits = length * 8;
        for (int i = 0; i < 8; i++) padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        int[] state = init(data);
        for (int off = 0; off < padded.length; off += 64) {
            int[] w = new int[64];
            for (int i = 0; i < 16; i++) {
                int p = off + i * 4;
                w[i] = ((padded[p] & 0xff) << 24) | ((padded[p + 1] & 0xff) << 16) | ((padded[p + 2] & 0xff) << 8) | (padded[p + 3] & 0xff);
            }
            expand(w, 16);
            int[] work = rounds(state, w, 0);
            for (int i = 0; i < 8; i++) state[i] += work[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (state[i] >>> 24);
            out[i * 4 + 1] = (byte) (state[i] >>> 16);
            out[i * 4 + 2] = (byte) (state[i] >>> 8);
            out[i * 4 + 3] = (byte) state[i];
        }
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /** Little-endian 32-bit words of 0x00 + data, zero-padded to an even number of words. */
    private static int[] wordsLe4Even(byte[] data) {
        int len = data.length + 1;
        int padded = len + Math.floorMod(-len, 4);
        byte[] buf = new byte[padded];
        System.arraycopy(data, 0, buf, 1, data.length);
        int n = padded / 4;
        int[] w = new int[n + (n % 2)];
        for (int i = 0; i < n; i++) w[i] = le32(buf, i * 4);
        return w;
    }

    static int[] cs64(byte[] data, byte[] digest) {
        int keyA = le32(digest, 0) | 1;
        int keyB = le32(digest, 4) | 1;
        int[] factors = {keyA * CS64_FACTOR, keyB * CS64_FACTOR};
        int[] words = wordsLe4Even(data);
        int n = CS64_COEFFICIENTS.length + words.length;
        int[] terms = new int[n];
        int[] offsets = new int[n];
        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            terms[i] = CS64_COEFFICIENTS[i][0] * factors[i % 2];
            offsets[i] = CS64_COEFFICIENTS[i][1];
        }
        for (int i = 0; i < words.length; i++) {
            int word = words[i];
            int term = word * (i % 2 == 0 ? keyA : keyB);
            int offset = rol16(word) * (i % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0];
                offset += CS64_ADJUSTMENT1;
            }
            terms[CS64_COEFFICIENTS.length + i] = term;
            offsets[CS64_COEFFICIENTS.length + i] = offset;
        }
        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < n; i += 2) {
            int x = terms[i], y = terms[i + 1];
            int t = rol16(offsets[i] + x);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * 0xC3EFEA97 + q;
            t = rol16(offsets[i + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;
            swapAcc += swapLast;

            state = state * keyA + x;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;
            t = state * keyB + y;
            t = rol16(t) * 0xA27416F5;
            t = rol16(t) * 0xD38396FF;
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69;
            acc += state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X", x));
        return s.toString();
    }

    /** Token for a Unix time in seconds (already corrected for clock skew). */
    static String generate(double unixSeconds, String clientToken) {
        long ticks = (long) Math.floor(unixSeconds + WIN_EPOCH);
        ticks -= Math.floorMod(ticks, 300L);
        byte[] data = ((ticks * 10_000_000L) + clientToken).getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256(data);
        int[] c = cs64(data, digest);
        byte[] cs = new byte[8];
        for (int k = 0; k < 2; k++) for (int i = 0; i < 4; i++) cs[k * 4 + i] = (byte) (c[k] >>> (8 * i));
        return hex(cs) + "-" + hex(digest);
    }
}
