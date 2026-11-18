package com.zlc.earbuds;

import java.io.ByteArrayOutputStream;

/** Tiny FLAC encoder (16-bit mono, uncompressed "verbatim" frames) for sending speech to the online recognizer. */
public final class Flac {
    private static final int BLOCK = 4096;

    private Flac() { }

    public static byte[] encode(short[] pcm, int n, int rate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(n * 2 + 1024);
        out.write('f'); out.write('L'); out.write('a'); out.write('C');
        // STREAMINFO, last metadata block, 34 bytes
        out.write(0x80); out.write(0); out.write(0); out.write(34);
        Bits b = new Bits();
        b.put(BLOCK, 16);
        b.put(BLOCK, 16);
        b.put(0, 24);
        b.put(0, 24);
        b.put(rate, 20);
        b.put(0, 3);          // channels - 1
        b.put(15, 5);         // bits per sample - 1
        b.put(0, 4);          // total samples, high 4 of 36 bits
        b.put(n, 32);
        for (int i = 0; i < 16; i++) b.put(0, 8); // MD5 not computed
        byte[] si = b.bytes();
        out.write(si, 0, si.length);

        int frame = 0;
        for (int off = 0; off < n; off += BLOCK, frame++) {
            int len = Math.min(BLOCK, n - off);
            ByteArrayOutputStream f = new ByteArrayOutputStream(len * 2 + 16);
            f.write(0xFF);
            f.write(0xF8);                       // sync + fixed block size
            f.write((0x7 << 4) | rateCode(rate)); // block size: 16-bit value at end of header
            f.write(0x08);                        // mono, 16 bits per sample
            writeUtf8(f, frame);
            f.write(((len - 1) >> 8) & 0xFF);
            f.write((len - 1) & 0xFF);
            byte[] head = f.toByteArray();
            f.write(crc8(head, head.length));
            f.write(0x02);                        // verbatim subframe
            for (int i = 0; i < len; i++) {
                short s = pcm[off + i];
                f.write((s >> 8) & 0xFF);
                f.write(s & 0xFF);
            }
            byte[] fr = f.toByteArray();
            int crc = crc16(fr, fr.length);
            out.write(fr, 0, fr.length);
            out.write((crc >> 8) & 0xFF);
            out.write(crc & 0xFF);
        }
        return out.toByteArray();
    }

    private static int rateCode(int rate) {
        switch (rate) {
            case 8000: return 4;
            case 16000: return 5;
            case 22050: return 6;
            case 24000: return 7;
            case 32000: return 8;
            case 44100: return 9;
            case 48000: return 10;
            default: return 0;
        }
    }

    private static void writeUtf8(ByteArrayOutputStream f, int v) {
        if (v < 0x80) { f.write(v); return; }
        if (v < 0x800) { f.write(0xC0 | (v >> 6)); f.write(0x80 | (v & 0x3F)); return; }
        f.write(0xE0 | (v >> 12)); f.write(0x80 | ((v >> 6) & 0x3F)); f.write(0x80 | (v & 0x3F));
    }

    static int crc8(byte[] d, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            c ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
        }
        return c;
    }

    static int crc16(byte[] d, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            c ^= (d[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF;
        }
        return c;
    }

    private static final class Bits {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        private long acc;
        private int nbits;

        void put(long v, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                acc = (acc << 1) | ((v >> i) & 1);
                if (++nbits == 8) { o.write((int) acc); acc = 0; nbits = 0; }
            }
        }

        byte[] bytes() { return o.toByteArray(); }
    }
}
