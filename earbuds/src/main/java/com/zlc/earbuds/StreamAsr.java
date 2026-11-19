package com.zlc.earbuds;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streaming recognition of what a video app is playing, for 视频同传.
 * Unlike {@link CloudAsr} (which uploads a sentence after it ends), this sends the sound while the
 * person is still talking and gets words back as they are said, through Google's full-duplex
 * speech service (the one Chrome's speech input uses): an upload stream ("up") and a result
 * stream ("down") paired by a random id. One pair per stretch of speech.
 * If the service doesn't answer, {@link Cb#failed()} is called once so the caller can fall back.
 */
@android.annotation.TargetApi(29)
final class StreamAsr {
    interface Cb {
        void partial(int session, String text);
        void fin(int session, String text);
        void failed();
    }

    private static final String KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw";
    private static final String BASE = "https://www.google.com/speech-api/full-duplex/v1/";
    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50;     // 20 ms analysis frames
    private static final int CHUNK = RATE / 10;     // 100 ms sent at a time

    private final MediaProjection projection;
    private final String lang;
    private final Cb cb;
    private volatile boolean running;
    private volatile boolean gaveUp;
    private int sessionNo;
    private volatile int okSessions, badSessions;

    StreamAsr(MediaProjection projection, String lang, Cb cb) {
        this.projection = projection;
        this.lang = lang;
        this.cb = cb;
    }

    void start() {
        running = true;
        new Thread(this::loop, "stream-asr").start();
    }

    void stop() { running = false; }

    private AudioRecord record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build();
        return new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min, RATE))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
    }

    private void loop() {
        AudioRecord rec;
        try {
            rec = record();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
            rec.startRecording();
        } catch (Throwable t) {
            giveUp();
            return;
        }
        short[] frame = new short[FRAME];
        int preFrames = 15;
        short[] pre = new short[FRAME * preFrames];
        int preIdx = 0, preCount = 0;
        short[] chunk = new short[CHUNK];
        int chunkLen = 0;
        double noise = 200;
        int loudRun = 0, quietRun = 0, voiced = 0, sent = 0;
        Session cur = null;

        while (running) {
            int got = 0;
            while (got < FRAME && running) {
                int r = rec.read(frame, got, FRAME - got);
                if (r < 0) { running = false; break; }
                got += r;
            }
            if (!running) break;
            double sum = 0;
            for (short s : frame) sum += (double) s * s;
            double rms = Math.sqrt(sum / FRAME);
            double startTh = Math.max(noise * 2.4, 300), endTh = Math.max(noise * 1.6, 200);

            if (cur == null) {
                if (rms < startTh) noise = Math.max(60, Math.min(3000, noise * 0.97 + rms * 0.03));
                loudRun = rms > startTh ? loudRun + 1 : 0;
                System.arraycopy(frame, 0, pre, preIdx * FRAME, FRAME);
                preIdx = (preIdx + 1) % preFrames;
                if (preCount < preFrames) preCount++;
                if (loudRun >= 2) {
                    cur = new Session(++sessionNo);
                    cur.open();
                    int first = (preIdx - preCount + preFrames) % preFrames;
                    for (int i = 0; i < preCount; i++) {
                        System.arraycopy(pre, ((first + i) % preFrames) * FRAME, chunk, chunkLen, FRAME);
                        chunkLen += FRAME;
                        if (chunkLen == CHUNK) { cur.send(chunk, CHUNK); chunkLen = 0; }
                    }
                    preCount = 0;
                    quietRun = 0;
                    voiced = loudRun;
                    sent = 0;
                }
            } else {
                System.arraycopy(frame, 0, chunk, chunkLen, FRAME);
                chunkLen += FRAME;
                sent += FRAME;
                if (chunkLen == CHUNK) { cur.send(chunk, CHUNK); chunkLen = 0; }
                if (rms < endTh) quietRun++; else { quietRun = 0; voiced++; }
                // end of a stretch of speech: 500 ms quiet, or 20 s (keeps each session short)
                if (quietRun >= 25 || sent >= RATE * 20) {
                    if (chunkLen > 0) { cur.send(chunk, chunkLen); chunkLen = 0; }
                    cur.end(voiced >= 25);
                    cur = null;
                    loudRun = 0;
                }
            }
        }
        if (cur != null) cur.end(false);
        try { rec.stop(); } catch (Throwable ignored) { }
        rec.release();
    }

    private synchronized void result(boolean ok, boolean mattered) {
        if (ok) { okSessions++; badSessions = 0; return; }
        if (!mattered) return;
        badSessions++;
        if (okSessions == 0 && badSessions >= 2) giveUp();
    }

    private void giveUp() {
        if (gaveUp) return;
        gaveUp = true;
        running = false;
        cb.failed();
    }

    /** One up/down pair for one stretch of speech. */
    private final class Session {
        final int no;
        final String pair;
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        volatile boolean gotResult, failed, long_;
        int frameNo;

        Session(int no) {
            this.no = no;
            Random r = new Random();
            pair = Long.toHexString(r.nextLong() & Long.MAX_VALUE) + Long.toHexString(System.nanoTime());
        }

        void open() {
            q.add(Flac.header(RATE, CHUNK, 0));
            new Thread(this::down, "asr-down").start();
            new Thread(this::up, "asr-up").start();
        }

        void send(short[] pcm, int len) { q.add(Flac.frame(pcm, 0, len, frameNo++, RATE)); }

        void end(boolean mattered) {
            long_ = mattered;
            q.add(new byte[0]); // end marker
        }

        private void up() {
            HttpURLConnection c = null;
            try {
                String u = BASE + "up?key=" + KEY + "&pair=" + pair + "&output=pb&lang=" + URLEncoder.encode(lang, "UTF-8")
                        + "&pFilter=0&maxAlternatives=1&app=chromium&continuous&interim";
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setChunkedStreamingMode(0);
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Content-Type", "audio/x-flac; rate=" + RATE);
                OutputStream os = c.getOutputStream();
                while (true) {
                    byte[] b = q.poll(25, TimeUnit.SECONDS);
                    if (b == null || b.length == 0) break;
                    os.write(b);
                    os.flush();
                }
                os.close();
                if (c.getResponseCode() != 200) failed = true;
            } catch (Throwable t) {
                failed = true;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        private void down() {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE + "down?key=" + KEY + "&pair=" + pair + "&output=pb").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("down " + c.getResponseCode());
                DataInputStream in = new DataInputStream(c.getInputStream());
                while (true) {
                    int len;
                    try { len = in.readInt(); } catch (EOFException e) { break; }
                    if (len < 0 || len > 1 << 20) break;
                    byte[] msg = new byte[len];
                    in.readFully(msg);
                    event(msg);
                }
            } catch (Throwable t) {
                failed = true;
            } finally {
                if (c != null) c.disconnect();
                boolean ok = gotResult;
                result(ok, !ok && (failed || long_));
            }
        }

        /** SpeechRecognitionEvent { status=1; repeated result=2 { repeated alternative=1 { transcript=1 }; final=2 } } */
        private void event(byte[] m) {
            StringBuilder interim = new StringBuilder();
            int[] p = {0};
            while (p[0] < m.length) {
                int key = (int) varint(m, p);
                int field = key >>> 3, wt = key & 7;
                if (field == 2 && wt == 2) {
                    int len = (int) varint(m, p);
                    int end = p[0] + len;
                    String text = null;
                    boolean fin = false;
                    while (p[0] < end) {
                        int k2 = (int) varint(m, p);
                        int f2 = k2 >>> 3, w2 = k2 & 7;
                        if (f2 == 1 && w2 == 2) {
                            int l2 = (int) varint(m, p);
                            int e2 = p[0] + l2;
                            while (p[0] < e2) {
                                int k3 = (int) varint(m, p);
                                if ((k3 >>> 3) == 1 && (k3 & 7) == 2) {
                                    int l3 = (int) varint(m, p);
                                    if (text == null) text = new String(m, p[0], l3, StandardCharsets.UTF_8);
                                    p[0] += l3;
                                } else skip(m, p, k3 & 7);
                            }
                            p[0] = e2;
                        } else if (f2 == 2 && w2 == 0) {
                            fin = varint(m, p) != 0;
                        } else skip(m, p, w2);
                    }
                    p[0] = end;
                    if (text == null || text.trim().isEmpty()) continue;
                    gotResult = true;
                    if (fin) cb.fin(no, text.trim());
                    else interim.append(text);
                } else skip(m, p, wt);
            }
            if (interim.length() > 0) cb.partial(no, interim.toString().trim());
        }
    }

    private static long varint(byte[] b, int[] p) {
        long v = 0;
        int shift = 0;
        while (p[0] < b.length) {
            int x = b[p[0]++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return v;
    }

    private static void skip(byte[] b, int[] p, int wt) {
        switch (wt) {
            case 0: varint(b, p); break;
            case 1: p[0] += 8; break;
            case 2: { int l = (int) varint(b, p); p[0] += l; break; }
            case 5: p[0] += 4; break;
            default: p[0] = b.length;
        }
    }

    @SuppressWarnings("unused")
    static byte[] testEncode(String text, boolean fin) {
        // builds an event the same shape as the service's, for checking the parser
        ByteArrayOutputStream alt = new ByteArrayOutputStream();
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        alt.write(0x0a); alt.write(t.length); alt.write(t, 0, t.length);
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        byte[] a = alt.toByteArray();
        res.write(0x0a); res.write(a.length); res.write(a, 0, a.length);
        res.write(0x10); res.write(fin ? 1 : 0);
        ByteArrayOutputStream ev = new ByteArrayOutputStream();
        ev.write(0x08); ev.write(0);
        byte[] r = res.toByteArray();
        ev.write(0x12); ev.write(r.length); ev.write(r, 0, r.length);
        return ev.toByteArray();
    }
}
