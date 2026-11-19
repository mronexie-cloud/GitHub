package com.zlc.earbuds;

import java.io.ByteArrayOutputStream;

/** Tiny FLAC encoder (16-bit mono, uncompressed "verbatim" frames) for sending speech to the online recognizer. */
public final class Flac {
    private static final int BLOCK = 4096;

    private Flac() { }

    public static byte[] encode(short[] pcm, int n, int rate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(n * 2 + 1024);
        byte[] h = header(rate, BLOCK, n);
        out.write(h, 0, h.length);
        int frame = 0;
        for (int off = 0; off < n; off += BLOCK, frame++) {
            byte[] f = frame(pcm, off, Math.min(BLOCK, n - off), frame, rate);
            out.write(f, 0, f.length);
        }
        return out.toByteArray();
    }

    /** "fLaC" + STREAMINFO. For a live stream pass total = 0 (unknown length). */
    public static byte[] header(int rate, int block, long total) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(42);
        out.write('f'); out.write('L'); out.write('a'); out.write('C');
        out.write(0x80); out.write(0); out.write(0); out.write(34);
        Bits b = new Bits();
        b.put(block, 16);
        b.put(block, 16);
        b.put(0, 24);
        b.put(0, 24);
        b.put(rate, 20);
        b.put(0, 3);
        b.put(15, 5);
        b.put(total >> 32, 4);
        b.put(total & 0xffffffffL, 32);
        for (int i = 0; i < 16; i++) b.put(0, 8);
        byte[] si = b.bytes();
        out.write(si, 0, si.length);
        return out.toByteArray();
    }

    /** One self-contained frame of len samples (len <= 65536). */
    public static byte[] frame(short[] pcm, int off, int len, int frameNo, int rate) {
        ByteArrayOutputStream f = new ByteArrayOutputStream(len * 2 + 16);
        f.write(0xFF);
        f.write(0xF8);
        f.write((0x7 << 4) | rateCode(rate));
        f.write(0x08);
        writeUtf8(f, frameNo);
        f.write(((len - 1) >> 8) & 0xFF);
        f.write((len - 1) & 0xFF);
        byte[] head = f.toByteArray();
        f.write(crc8(head, head.length));
        f.write(0x02);
        for (int i = 0; i < len; i++) {
            short s = pcm[off + i];
            f.write((s >> 8) & 0xFF);
            f.write(s & 0xFF);
        }
        byte[] fr = f.toByteArray();
        int crc = crc16(fr, fr.length);
        f.write((crc >> 8) & 0xFF);
        f.write(crc & 0xFF);
        return f.toByteArray();
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
