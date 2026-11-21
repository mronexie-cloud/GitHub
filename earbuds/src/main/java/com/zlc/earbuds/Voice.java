package com.zlc.earbuds;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads translations aloud: into the earbuds ("ear") or from the phone's own loudspeaker
 * ("speaker", even while earbuds are connected). Uses the phone's voice when it has one for
 * the language, otherwise Google's online voice. Other audio is ducked while it speaks.
 * One shared instance for the app and the background service.
 */
final class Voice {
    interface Done { void done(String id, String how); }

    private static Voice inst;

    static synchronized Voice get(Context c) {
        if (inst == null) inst = new Voice(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final AudioManager am;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private boolean speakerRouted;
    private int savedCallVolume = -1;
    private String speakingId;
    private Done doneCb;
    private AudioFocusRequest focus;
    private final AudioManager.OnAudioFocusChangeListener focusListener = c -> { };

    private Voice(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(c, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { ui.post(() -> finished(id, "done")); }
                @Override public void onError(String id) { ui.post(() -> finished(id, "error")); }
            });
        });
    }

    boolean has(String lang) {
        try { return ttsReady && tts.isLanguageAvailable(Locale.forLanguageTag(lang)) >= TextToSpeech.LANG_AVAILABLE; }
        catch (Exception e) { return false; }
    }

    boolean earbudsConnected() {
        if (Build.VERSION.SDK_INT >= 23) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
            }
        }
        return false;
    }

    private AudioAttributes attrs(boolean speaker) {
        return new AudioAttributes.Builder()
                .setUsage(speaker ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** Speaks text; done is called once with "done", "error" or "stopped". Call on the main thread. */
    void speak(String id, String text, String lang, String route, float rate, Done done) {
        speak(id, text, lang, route, rate, true, done);
    }

    void speak(String id, String text, String lang, String route, float rate, boolean lowerOthers, Done done) {
        stop(true);
        speakingId = id;
        doneCb = done;
        if (lowerOthers) duck(true);
        boolean speaker = "speaker".equals(route) && routeToSpeaker();
        Locale loc = Locale.forLanguageTag(lang);
        if (has(lang)) {
            tts.setLanguage(loc);
            tts.setSpeechRate(rate);
            tts.setAudioAttributes(attrs(speaker));
            Bundle p = new Bundle();
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, id) == TextToSpeech.SUCCESS) return;
        }
        speakOnline(id, text, lang, speaker);
    }

    void stop(boolean notify) {
        String id = speakingId;
        Done cb = doneCb;
        speakingId = null;
        doneCb = null;
        try { if (ttsReady) tts.stop(); } catch (Exception ignored) { }
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
        restore();
        duck(false);
        if (notify && id != null && cb != null) cb.done(id, "stopped");
    }

    boolean busy() { return speakingId != null; }

    private void finished(String id, String how) {
        if (id == null || !id.equals(speakingId)) return;
        Done cb = doneCb;
        speakingId = null;
        doneCb = null;
        restore();
        duck(false);
        if (cb != null) cb.done(id, how);
    }

    @SuppressWarnings("deprecation")
    private void duck(boolean on) {
        if (Build.VERSION.SDK_INT >= 26) {
            if (on) {
                if (focus == null) {
                    focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs(false)).setOnAudioFocusChangeListener(focusListener).build();
                    am.requestAudioFocus(focus);
                }
            } else if (focus != null) {
                am.abandonAudioFocusRequest(focus);
                focus = null;
            }
        } else {
            if (on) am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            else am.abandonAudioFocus(focusListener);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean routeToSpeaker() {
        if (!earbudsConnected()) return false;
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 31) {
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { ok = am.setCommunicationDevice(d); break; }
            }
        } else {
            am.setSpeakerphoneOn(true);
            ok = true;
        }
        speakerRouted = true;
        if (!ok) { restore(); return false; }
        int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        savedCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.max(savedCallVolume, (int) Math.ceil(max * 0.85)), 0); } catch (Exception ignored) { }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void restore() {
        if (!speakerRouted) return;
        speakerRouted = false;
        if (savedCallVolume >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0); } catch (Exception ignored) { }
            savedCallVolume = -1;
        }
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        else am.setSpeakerphoneOn(false);
        am.setMode(AudioManager.MODE_NORMAL);
    }

    /** No offline voice for this language on the phone: Google's online voice. */
    private void speakOnline(String id, String text, String lang, boolean speaker) {
        List<String> parts = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > 180) {
            int cut = -1;
            for (String sep : new String[]{"。", "！", "？", ". ", "! ", "? ", "，", ", ", " "}) {
                int k = rest.lastIndexOf(sep, 180);
                if (k > 40) { cut = k + sep.length(); break; }
            }
            if (cut < 0) cut = 180;
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) parts.add(rest);
        String l = lang.toLowerCase(Locale.ROOT);
        String tl = l.startsWith("zh-tw") ? "zh-TW" : l.startsWith("zh-hk") ? "yue" : l.startsWith("fil") ? "tl" : lang.split("-")[0];
        chunk(id, parts, 0, tl, speaker);
    }

    private void chunk(String id, List<String> parts, int idx, String tl, boolean speaker) {
        if (!id.equals(speakingId)) return;
        if (idx >= parts.size()) { finished(id, "done"); return; }
        try {
            String url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=" + URLEncoder.encode(tl, "UTF-8")
                    + "&q=" + URLEncoder.encode(parts.get(idx), "UTF-8");
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(attrs(speaker));
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36");
            h.put("Referer", "https://translate.google.com/");
            mp.setDataSource(ctx, Uri.parse(url), h);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> { m.release(); if (player == m) player = null; chunk(id, parts, idx + 1, tl, speaker); });
            mp.setOnErrorListener((m, w, e) -> { m.release(); if (player == m) player = null; finished(id, "error"); return true; });
            mp.prepareAsync();
        } catch (Exception e) {
            finished(id, "error");
        }
    }
}
