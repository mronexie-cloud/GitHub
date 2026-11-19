package com.zlc.earbuds;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands-free two-way speech recognition for a conversation.
 * Records the microphone non-stop, cuts out each sentence by loudness, recognizes it in
 * both conversation languages at once and keeps the one that fits, so nobody has to press
 * anything to say who is talking. While someone is still talking, the sentence so far is
 * recognized about once a second so the text (and its translation) appears live.
 *
 * Events: ready, begin, partial_them / partial_me, final_them / final_me, nothing, error.
 */
final class CloudAsr {
    interface Callback {
        void event(String type, String text, String tag);
        void level(float db);
    }

    private static final String KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw"; // Chromium's public speech key
    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50; // 20 ms
    private static final int INTERIM_EVERY = RATE * 9 / 10; // new audio between live updates

    private final Callback cb;
    private volatile boolean running;
    private volatile String langThem = "en-US", langMe = "zh-CN";
    private volatile String tag = "";
    private Thread thread;
    private volatile int gen;
    private volatile boolean paused;
    private MediaProjection projection; // set: listen to what other apps play instead of the microphone
    private int maxSeconds = 10;

    private volatile int segId;           // sentence being recorded
    private volatile int doneSeg;         // last sentence whose final result was sent
    private volatile String segSide;      // "them" / "me" once known for the current sentence
    private final AtomicBoolean interimBusy = new AtomicBoolean(false);

    CloudAsr(Callback cb) { this.cb = cb; }

    synchronized void start(String langThem, String langMe, String tag) {
        this.langThem = langThem;
        this.langMe = langMe;
        this.tag = tag;
        if (running && thread != null && thread.isAlive()) { cb.event("ready", "", tag); return; }
        running = true;
        final int my = ++gen;
        final Thread prev = thread;
        thread = new Thread(() -> {
            if (prev != null) { try { prev.join(500); } catch (InterruptedException ignored) { } }
            loop(my);
        }, "cloud-asr");
        thread.start();
    }

    synchronized void stop() { running = false; }

    /** Listen to the sound other apps are playing (a film) instead of the microphone. Android 10+. */
    void useCapture(MediaProjection p) { projection = p; maxSeconds = 7; }

    /** While paused the microphone keeps running but everything heard is thrown away (e.g. while the phone speaks). */
    void setPaused(boolean p) { paused = p; }

    boolean isRunning() { return running; }

    private void loop(int my) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            if (projection != null && Build.VERSION.SDK_INT >= 29) rec = captureRecord(min);
            else rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED && projection == null) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new IllegalStateException(); }
            rec.startRecording();
        } catch (Throwable t) {
            if (gen == my) { running = false; cb.event("error", "3", tag); }
            return;
        }
        cb.event("ready", "", tag);

        short[] frame = new short[FRAME];
        int maxLen = RATE * maxSeconds;
        short[] seg = new short[maxLen + RATE];
        int preFrames = 15; // 300 ms kept from before speech starts
        short[] pre = new short[FRAME * preFrames];
        int preIdx = 0, preCount = 0;

        double noise = 200;
        boolean speaking = false;
        int segLen = 0, loudRun = 0, quietRun = 0, voiced = 0, lastInterimAt = 0;

        while (running && gen == my) {
            int got = 0;
            while (got < FRAME && running && gen == my) {
                int r = rec.read(frame, got, FRAME - got);
                if (r < 0) { if (gen == my) { running = false; cb.event("error", "3", tag); } break; }
                got += r;
            }
            if (!running || gen != my) break;
            if (paused) {
                if (speaking) { speaking = false; doneSeg = segId; }
                loudRun = 0;
                preCount = 0;
                cb.level(-2);
                continue;
            }
            double sum = 0;
            for (short s : frame) sum += (double) s * s;
            double rms = Math.sqrt(sum / FRAME);
            cb.level((float) ((20 * Math.log10(Math.max(rms, 1)) - 40) / 3));

            double startTh = Math.max(noise * 2.6, 320), endTh = Math.max(noise * 1.7, 220);
            if (!speaking) {
                if (rms < startTh) noise = noise * 0.97 + rms * 0.03;
                noise = Math.max(60, Math.min(noise, 3000));
                loudRun = rms > startTh ? loudRun + 1 : 0;
                System.arraycopy(frame, 0, pre, preIdx * FRAME, FRAME);
                preIdx = (preIdx + 1) % preFrames;
                if (preCount < preFrames) preCount++;
                if (loudRun >= 3) {
                    speaking = true;
                    segId++;
                    segSide = null;
                    segLen = 0;
                    int first = (preIdx - preCount + preFrames) % preFrames;
                    for (int i = 0; i < preCount; i++) {
                        System.arraycopy(pre, ((first + i) % preFrames) * FRAME, seg, segLen, FRAME);
                        segLen += FRAME;
                    }
                    quietRun = 0;
                    voiced = loudRun;
                    preCount = 0;
                    lastInterimAt = 0;
                    cb.event("begin", "", tag);
                }
            } else {
                System.arraycopy(frame, 0, seg, segLen, FRAME);
                segLen += FRAME;
                if (rms < endTh) quietRun++; else { quietRun = 0; voiced++; }
                // a sentence ends after 600 ms of quiet; long speech is cut at a short pause (or at 10 s)
                // so the listener hears the translation in pieces, like an interpreter
                boolean end = quietRun >= 30 || segLen >= maxLen || (segLen > RATE * (maxSeconds - 4) && quietRun >= 12);
                if (end) {
                    speaking = false;
                    loudRun = 0;
                    if (voiced >= 10) recognize(seg, segLen, segId, true);
                    else doneSeg = segId;
                } else if (voiced >= 15 && segLen - lastInterimAt >= INTERIM_EVERY && interimBusy.compareAndSet(false, true)) {
                    lastInterimAt = segLen;
                    recognize(seg, segLen, segId, false);
                }
            }
        }
        try { rec.stop(); } catch (Throwable ignored) { }
        rec.release();
    }

    @android.annotation.TargetApi(29)
    private AudioRecord captureRecord(int min) {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid()) // not our own Chinese voice
                .build();
        return new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min, RATE * 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
    }

    private static final class Result {
        String text = "";
        double conf = -1;
        boolean failed;
    }

    private void recognize(short[] seg, int len, int id, boolean isFinal) {
        short[] copy = new short[len];
        System.arraycopy(seg, 0, copy, 0, len);
        final String t = tag, lt = langThem, lm = langMe;
        final String known = segSide;
        new Thread(() -> {
            try {
                byte[] flac = Flac.encode(copy, len, RATE);
                String side;
                Result r;
                if (lm == null || lm.isEmpty()) {
                    // one language only (a film): no need to work out who is talking
                    r = request(flac, lt);
                    side = "them";
                } else if (!isFinal && known != null) {
                    r = request(flac, "me".equals(known) ? lm : lt);
                    side = known;
                } else {
                    // both languages at once, keep the result that fits
                    Result[] both = new Result[2];
                    Thread a = new Thread(() -> both[0] = request(flac, lt));
                    a.start();
                    both[1] = request(flac, lm);
                    try { a.join(20000); } catch (InterruptedException ignored) { }
                    if (both[0] == null) { both[0] = new Result(); both[0].failed = true; }
                    side = choose(both[0], lt, both[1], lm);
                    r = "me".equals(side) ? both[1] : both[0];
                    if (isFinal) {
                        Result other = "me".equals(side) ? both[0] : both[1];
                        cb.event("alt", other.text, t);
                    }
                    if (both[0].failed && both[1].failed) r.failed = true;
                }
                if (isFinal) {
                    doneSeg = Math.max(doneSeg, id);
                    if (r.failed && r.text.isEmpty()) cb.event("error", "2", t);
                    else if (r.text.isEmpty()) cb.event("nothing", "", t);
                    else cb.event("final_" + side, r.text, t);
                } else {
                    if (id == segId && id > doneSeg && !r.text.isEmpty()) {
                        if (segSide == null) segSide = side;
                        cb.event("partial_" + side, r.text, t);
                    }
                }
            } catch (Throwable e) {
                if (isFinal) { doneSeg = Math.max(doneSeg, id); cb.event("error", "2", t); }
            } finally {
                if (!isFinal) interimBusy.set(false);
            }
        }, "cloud-asr-send").start();
    }

    private static Result request(byte[] flac, String lang) {
        Result res = new Result();
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://www.google.com/speech-api/v2/recognize?output=json&pFilter=0&lang="
                    + URLEncoder.encode(lang, "UTF-8") + "&key=" + KEY);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "audio/x-flac; rate=" + RATE);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36");
            OutputStream os = c.getOutputStream();
            os.write(flac);
            os.close();
            if (c.getResponseCode() != 200) { res.failed = true; return res; }
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONArray arr = new JSONObject(line).optJSONArray("result");
                if (arr == null || arr.length() == 0) continue;
                JSONArray alt = arr.getJSONObject(0).optJSONArray("alternative");
                if (alt != null && alt.length() > 0) {
                    JSONObject a = alt.getJSONObject(0);
                    res.text = a.optString("transcript", "").trim();
                    res.conf = a.optDouble("confidence", -1);
                    break;
                }
            }
            br.close();
        } catch (Throwable t) {
            res.failed = true;
        } finally {
            if (c != null) c.disconnect();
        }
        return res;
    }

    // ---------------------------------------------------------------- who is talking

    static String scriptOf(String locale) {
        String l = locale.toLowerCase(Locale.ROOT);
        if (l.startsWith("zh") || l.startsWith("yue") || l.startsWith("cmn")) return "han";
        if (l.startsWith("ja")) return "kana";
        if (l.startsWith("ko")) return "hangul";
        if (l.startsWith("th")) return "thai";
        if (l.startsWith("ar")) return "arab";
        if (l.startsWith("hi") || l.startsWith("ne") || l.startsWith("mr")) return "deva";
        if (l.startsWith("ta")) return "tamil";
        if (l.startsWith("ru")) return "cyr";
        return "latin";
    }

    /** Share of the letters in text written in the script the language uses. */
    static double scriptShare(String text, String script) {
        int letters = 0, hit = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            boolean ok;
            switch (script) {
                case "han": ok = s == Character.UnicodeScript.HAN; break;
                case "kana": ok = s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HAN; break;
                case "hangul": ok = s == Character.UnicodeScript.HANGUL; break;
                case "thai": ok = s == Character.UnicodeScript.THAI; break;
                case "arab": ok = s == Character.UnicodeScript.ARABIC; break;
                case "deva": ok = s == Character.UnicodeScript.DEVANAGARI; break;
                case "tamil": ok = s == Character.UnicodeScript.TAMIL; break;
                case "cyr": ok = s == Character.UnicodeScript.CYRILLIC; break;
                default: ok = s == Character.UnicodeScript.LATIN;
            }
            if (ok) hit++;
        }
        return letters == 0 ? 0 : (double) hit / letters;
    }

    /**
     * Decides whose language a sentence was in, from the two recognition results. Returns "them" or "me".
     * A recognizer always tries to write *something* in its own language (Chinese speech "谢谢" comes out
     * as English "seaweed", English "hello" as Chinese "哈啰"), so the script of the text proves little;
     * the recognizer's own confidence is the main signal.
     */
    static String choose(Result a, String langA, Result b, String langB) {
        boolean ea = a.text.isEmpty(), eb = b.text.isEmpty();
        if (ea && !eb) return "me";
        if (eb) return "them";
        String sa = scriptOf(langA), sb = scriptOf(langB);
        double fa = scriptShare(a.text, sa), fb = scriptShare(b.text, sb);
        // a recognizer for a non-Latin language that still writes Latin letters did not hear its language
        if (!sb.equals("latin") && fb < 0.3 && !sa.equals(sb)) return "them";
        if (!sa.equals("latin") && fa < 0.3 && !sa.equals(sb)) return "me";
        if (a.conf >= 0 && b.conf >= 0 && Math.abs(a.conf - b.conf) >= 0.05) return a.conf > b.conf ? "them" : "me";
        if (a.conf >= 0 && b.conf < 0) return a.conf >= 0.6 ? "them" : "me";
        if (b.conf >= 0 && a.conf < 0) return b.conf >= 0.6 ? "me" : "them";
        // no usable confidence: the result with more real words is usually the right one
        int wa = words(a.text, sa), wb = words(b.text, sb);
        if (wa != wb) return wa > wb ? "them" : "me";
        return "them";
    }

    /** Rough amount of speech in a transcript, comparable across scripts (a CJK character ≈ half a word). */
    static int words(String t, String script) {
        if (script.equals("han") || script.equals("kana") || script.equals("thai")) {
            int n = 0;
            for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) n++;
            return (n + 1) / 2;
        }
        return t.trim().isEmpty() ? 0 : t.trim().split("\\s+").length;
    }
}
