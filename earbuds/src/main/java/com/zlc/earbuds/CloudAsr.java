package com.zlc.earbuds;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Online speech recognition that does not need the phone's own recognizer (Google app):
 * records the microphone, cuts out each sentence by loudness, and sends it to Google's
 * web speech service. Keeps recording while a sentence is being recognized, so nothing is missed.
 */
final class CloudAsr {
    interface Callback {
        void event(String type, String text, String tag);
        void level(float db);
    }

    private static final String KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw"; // Chromium's public speech key
    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50; // 20 ms

    private final Callback cb;
    private volatile boolean running;
    private volatile boolean forceEnd;
    private volatile String lang = "en-US";
    private volatile String tag = "";
    private Thread thread;
    private volatile int gen;

    CloudAsr(Callback cb) { this.cb = cb; }

    synchronized void start(String lang, String tag) {
        this.lang = lang;
        this.tag = tag;
        forceEnd = false;
        if (running && thread != null && thread.isAlive()) { cb.event("ready", "", tag); return; }
        running = true;
        final int my = ++gen;
        final Thread prev = thread;
        thread = new Thread(() -> {
            // let the previous recorder release the microphone first
            if (prev != null) { try { prev.join(500); } catch (InterruptedException ignored) { } }
            loop(my);
        }, "cloud-asr");
        thread.start();
    }

    synchronized void stop() { running = false; }

    void finish() { forceEnd = true; }

    boolean isRunning() { return running; }

    private void loop(int my) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
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
        int maxLen = RATE * 15;
        short[] seg = new short[maxLen + RATE];
        int preFrames = 15; // 300 ms kept from before speech starts
        short[] pre = new short[FRAME * preFrames];
        int preIdx = 0, preCount = 0;

        double noise = 200;
        boolean speaking = false;
        int segLen = 0, loudRun = 0, quietRun = 0, voiced = 0;
        String segTag = tag, segLang = lang;

        while (running && gen == my) {
            int got = 0;
            while (got < FRAME && running && gen == my) {
                int r = rec.read(frame, got, FRAME - got);
                if (r < 0) { if (gen == my) { running = false; cb.event("error", "3", tag); } break; }
                got += r;
            }
            if (!running || gen != my) break;
            double sum = 0;
            for (short s : frame) sum += (double) s * s;
            double rms = Math.sqrt(sum / FRAME);
            cb.level((float) ((20 * Math.log10(Math.max(rms, 1)) - 40) / 3));

            double startTh = Math.max(noise * 2.6, 320), endTh = Math.max(noise * 1.7, 220);
            if (!speaking) {
                if (rms < startTh) noise = noise * 0.97 + rms * 0.03;
                noise = Math.max(60, Math.min(noise, 3000));
                loudRun = rms > startTh ? loudRun + 1 : 0;
                // keep a short history so the first syllable isn't cut off
                System.arraycopy(frame, 0, pre, preIdx * FRAME, FRAME);
                preIdx = (preIdx + 1) % preFrames;
                if (preCount < preFrames) preCount++;
                if (loudRun >= 3 || forceEnd) {
                    if (forceEnd && loudRun < 3) { forceEnd = false; cb.event("final", "", tag); continue; }
                    speaking = true;
                    segTag = tag;
                    segLang = lang;
                    segLen = 0;
                    int first = (preIdx - preCount + preFrames) % preFrames;
                    for (int i = 0; i < preCount; i++) {
                        System.arraycopy(pre, ((first + i) % preFrames) * FRAME, seg, segLen, FRAME);
                        segLen += FRAME;
                    }
                    quietRun = 0;
                    voiced = loudRun;
                    preCount = 0;
                    cb.event("begin", "", segTag);
                }
            } else {
                System.arraycopy(frame, 0, seg, segLen, FRAME);
                segLen += FRAME;
                if (rms < endTh) quietRun++; else { quietRun = 0; voiced++; }
                if (quietRun >= 40 || segLen >= maxLen || forceEnd) { // 800 ms of quiet ends a sentence
                    forceEnd = false;
                    speaking = false;
                    loudRun = 0;
                    if (voiced >= 10) send(seg, segLen, segLang, segTag);
                    else if (segTag.startsWith("m")) cb.event("final", "", segTag);
                }
            }
        }
        try { rec.stop(); } catch (Throwable ignored) { }
        rec.release();
    }

    private void send(short[] seg, int len, String lang, String tag) {
        short[] copy = new short[len];
        System.arraycopy(seg, 0, copy, 0, len);
        cb.event("partial", "…", tag);
        new Thread(() -> {
            String text = null;
            boolean failed = false;
            HttpURLConnection c = null;
            try {
                byte[] flac = Flac.encode(copy, len, RATE);
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
                if (c.getResponseCode() != 200) failed = true;
                else {
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null && text == null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        JSONArray res = new JSONObject(line).optJSONArray("result");
                        if (res == null || res.length() == 0) continue;
                        JSONArray alt = res.getJSONObject(0).optJSONArray("alternative");
                        if (alt != null && alt.length() > 0) text = alt.getJSONObject(0).optString("transcript", null);
                    }
                    br.close();
                }
            } catch (Throwable t) {
                failed = true;
            } finally {
                if (c != null) c.disconnect();
            }
            if (failed) cb.event("error", "2", tag);
            else if (text != null && !text.trim().isEmpty()) cb.event("final", text.trim(), tag);
            else cb.event(tag.startsWith("m") ? "final" : "nothing", "", tag);
        }, "cloud-asr-send").start();
    }
}
