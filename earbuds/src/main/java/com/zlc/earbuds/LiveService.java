package com.zlc.earbuds;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The translation engine, running as a foreground service so it keeps working with the
 * screen off or the app closed.
 *
 * Two ways to run:
 * - manual: the user pressed 开始; both sides are always translated.
 * - auto (自动翻译): the service waits; as soon as earbuds are connected it listens, and when it
 *   hears the other person's language the translation is read into the earbuds. What the user says
 *   is only translated for the other person while a conversation is going on (within 45 s of the
 *   other person speaking), so talking with friends in your own language does nothing.
 */
public class LiveService extends Service {
    interface Sink { void event(String json); }

    static final String ACT_MANUAL_ON = "manual_on", ACT_MANUAL_OFF = "manual_off",
            ACT_AUTO_ON = "auto_on", ACT_AUTO_OFF = "auto_off", ACT_CONFIG = "config", ACT_PAUSE_ALL = "pause_all";
    static final String PREFS = "live";
    private static final String CH = "live";
    private static final int NOTE_ID = 77;
    private static final long CONVERSATION_MS = 45000;

    static volatile Sink sink;
    static volatile LiveService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private CloudAsr asr;
    private Voice voice;
    private AudioManager am;
    private boolean manual, auto, listening, muted, foreground;
    private boolean earbuds;
    private long lastForeignAt;
    private String tag = "";
    private int seq;
    private String lastLine = "";

    // config
    private String them = "en", me = "zh-CN", themAsr = "en-US", meAsr = "zh-CN", themTts = "en-US", meTts = "zh-CN";
    private boolean spkThem = true, spkMe = true;
    private float rate = 1f;

    // live text while someone talks
    private String draftWho, draftSrc = "", draftTr = "", alt = "";
    private boolean draftBusy;

    private final ArrayDeque<JSONObject> speakQ = new ArrayDeque<>();
    private JSONObject speaking;
    private final List<JSONObject> hist = new ArrayList<>();
    private long lastLevelAt;

    private final AudioDeviceCallback devCb = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { ui.postDelayed(LiveService.this::update, 600); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { ui.postDelayed(LiveService.this::update, 300); }
    };

    // ---------------------------------------------------------------- entry points used by the app

    static void send(Context c, String action) {
        Intent i = new Intent(c, LiveService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static boolean autoEnabled(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto", false); }

    static String state() {
        LiveService s = instance;
        if (s == null) return "{\"t\":\"state\",\"manual\":false,\"auto\":false,\"listening\":false,\"muted\":false,\"earbuds\":false}";
        return s.stateJson();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        voice = Voice.get(this);
        loadConfig();
        loadHist();
        asr = new CloudAsr(new CloudAsr.Callback() {
            @Override public void event(String type, String text, String t) { ui.post(() -> onAsr(type, text, t)); }
            @Override public void level(float db) {
                long now = System.currentTimeMillis();
                if (now - lastLevelAt < 90 || sink == null) return;
                lastLevelAt = now;
                emit("{\"t\":\"level\",\"v\":" + db + "}");
            }
        });
        if (Build.VERSION.SDK_INT >= 23) am.registerAudioDeviceCallback(devCb, ui);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent != null ? intent.getAction() : null;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (a == null) auto = p.getBoolean("auto", false); // restarted by the system
        else switch (a) {
            case ACT_MANUAL_ON: manual = true; lastForeignAt = 0; break;
            case ACT_MANUAL_OFF: manual = false; break;
            case ACT_AUTO_ON: auto = true; p.edit().putBoolean("auto", true).apply(); break;
            case ACT_AUTO_OFF: auto = false; p.edit().putBoolean("auto", false).apply(); break;
            case ACT_PAUSE_ALL: manual = false; auto = false; p.edit().putBoolean("auto", false).apply(); break;
            case ACT_CONFIG: loadConfig(); if (listening) { stopListening(); } break;
            default:
        }
        if (!manual && !auto) {
            shutdown();
            return START_NOT_STICKY;
        }
        update();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (asr != null) asr.stop();
        if (Build.VERSION.SDK_INT >= 23) am.unregisterAudioDeviceCallback(devCb);
        voice.stop(false);
        instance = null;
        emit(state());
        super.onDestroy();
    }

    private void shutdown() {
        asr.stop();
        listening = false;
        speakQ.clear();
        if (speaking != null) { speaking = null; voice.stop(false); }
        clearDraft();
        emit(stateJson());
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        foreground = false;
        stopSelf();
    }

    // ---------------------------------------------------------------- listening on/off

    private void update() {
        earbuds = voice.earbudsConnected();
        boolean want = manual || (auto && earbuds);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) want = false;
        if (want && !listening) startListening();
        else if (!want && listening) stopListening();
        notifyState();
    }

    private void startListening() {
        listening = true;
        tag = "s" + (++seq);
        asr.setPaused(muted);
        asr.start(themAsr, meAsr, tag);
    }

    private void stopListening() {
        listening = false;
        asr.stop();
        clearDraft();
        if (manual || (auto && earbuds)) ui.postDelayed(this::update, 200); // config change: start again
    }

    private void setMuted(boolean m) {
        muted = m;
        asr.setPaused(m);
        notifyState();
    }

    // ---------------------------------------------------------------- recognition results

    private void onAsr(String type, String text, String t) {
        if (!listening && !type.startsWith("final")) return;
        switch (type) {
            case "alt": alt = text; return;
            case "begin": return;
            case "nothing": clearDraft(); return;
            case "error":
                emit("{\"t\":\"error\",\"code\":" + JSONObject.quote(text) + "}");
                if ("3".equals(text)) { listening = false; ui.postDelayed(this::update, 3000); }
                return;
            default:
        }
        boolean partial = type.startsWith("partial_"), fin = type.startsWith("final_");
        if (!partial && !fin) return;
        String who = type.endsWith("_me") ? "me" : "them";
        if (partial) { draft(who, text); return; }

        String otherText = alt;
        alt = "";
        String draftTranslation = who.equals(draftWho) ? draftTr : "";
        clearDraft();
        long now = System.currentTimeMillis();
        if ("them".equals(who)) lastForeignAt = now;
        else if (!manual && now - lastForeignAt > CONVERSATION_MS) return; // just you talking: not a conversation
        addEntry(who, text, otherText, draftTranslation);
    }

    private void draft(String who, String text) {
        if (!who.equals(draftWho)) { draftWho = who; draftTr = ""; }
        draftSrc = text;
        if ("me".equals(who) && !manual && System.currentTimeMillis() - lastForeignAt > CONVERSATION_MS) return;
        emitDraft();
        if (draftBusy || sink == null) return;
        draftBusy = true;
        final String w = who, src = text;
        new Thread(() -> {
            String tr = null;
            try { tr = Translator.translate(src, "me".equals(w) ? me : them, "me".equals(w) ? them : me); } catch (Exception ignored) { }
            final String r = tr;
            ui.post(() -> {
                draftBusy = false;
                if (r != null && w.equals(draftWho)) { draftTr = r; emitDraft(); }
                if (w.equals(draftWho) && !src.equals(draftSrc)) draft(w, draftSrc);
            });
        }).start();
    }

    private void emitDraft() {
        try {
            JSONObject o = new JSONObject().put("t", "draft").put("who", draftWho).put("src", draftSrc).put("tr", draftTr);
            emit(o.toString());
        } catch (Exception ignored) { }
    }

    private void clearDraft() {
        if (draftWho == null) return;
        draftWho = null;
        draftSrc = "";
        draftTr = "";
        emit("{\"t\":\"draft_clear\"}");
    }

    private void addEntry(String who, String text, String otherText, String draftTranslation) {
        final String from = "me".equals(who) ? me : them, to = "me".equals(who) ? them : me;
        final String tts = "me".equals(who) ? themTts : meTts;
        final JSONObject e = new JSONObject();
        try {
            e.put("id", "u" + System.currentTimeMillis() + Integer.toHexString((int) (Math.random() * 0xffff)));
            e.put("who", who).put("src", text).put("alt", otherText).put("from", from).put("to", to)
                    .put("tr", draftTranslation).put("st", "busy").put("t", System.currentTimeMillis());
        } catch (Exception ignored) { }
        hist.add(e);
        while (hist.size() > 150) hist.remove(0);
        emitEntry(e);
        new Thread(() -> {
            String tr = null;
            try { tr = Translator.translate(text, from, to); } catch (Exception ignored) { }
            final String r = tr;
            ui.post(() -> {
                try {
                    if (r != null) e.put("tr", r).put("st", "ok");
                    else e.put("st", e.optString("tr").isEmpty() ? "fail" : "ok");
                } catch (Exception ignored) { }
                emitEntry(e);
                saveHist();
                if (!"ok".equals(e.optString("st"))) return;
                lastLine = e.optString("tr");
                notifyState();
                boolean speak = "me".equals(who) ? spkMe : spkThem;
                if (speak && (manual || auto)) enqueue(e.optString("id"), e.optString("tr"), tts, "me".equals(who) ? "speaker" : "ear");
            });
        }).start();
    }

    // ---------------------------------------------------------------- reading translations aloud

    private void enqueue(String id, String text, String lang, String route) {
        JSONObject it = new JSONObject();
        try { it.put("id", id).put("text", text).put("lang", lang).put("route", route); } catch (Exception ignored) { }
        if ("speaker".equals(route)) {
            // your words for the other person go first
            speakQ.addFirst(it);
            if (speaking != null && !"speaker".equals(speaking.optString("route"))) { speaking = null; voice.stop(false); }
        } else speakQ.addLast(it);
        if (speaking == null) next();
    }

    private void next() {
        JSONObject it = speakQ.pollFirst();
        speaking = it;
        if (it == null) { if (muted) ui.postDelayed(() -> { if (speaking == null) setMuted(false); }, 250); return; }
        // the phone's speaker is heard by the microphone: stop listening while it talks
        boolean loud = "speaker".equals(it.optString("route")) || !voice.earbudsConnected();
        if (loud) setMuted(true);
        final String id = it.optString("id") + "#" + System.currentTimeMillis();
        voice.speak(id, it.optString("text"), it.optString("lang"), it.optString("route"), rate, (sid, how) -> {
            if (speaking != it) return;
            speaking = null;
            emit("{\"t\":\"speak\",\"id\":" + JSONObject.quote(it.optString("id")) + ",\"how\":" + JSONObject.quote(how) + "}");
            next();
        });
    }

    // ---------------------------------------------------------------- history (shared with the app's page)

    private File histFile() { return new File(getFilesDir(), "hist.json"); }

    static String historyJson(Context c) {
        LiveService s = instance;
        if (s != null) {
            JSONArray a = new JSONArray();
            for (JSONObject e : s.hist) a.put(e);
            return a.toString();
        }
        try {
            File f = new File(c.getFilesDir(), "hist.json");
            if (!f.exists()) return "[]";
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[]";
        }
    }

    static void writeHistory(Context c, String json) {
        LiveService s = instance;
        if (s != null) {
            s.hist.clear();
            try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) s.hist.add(a.getJSONObject(i)); } catch (Exception ignored) { }
        }
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), "hist.json"))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private void loadHist() {
        hist.clear();
        try { JSONArray a = new JSONArray(historyJson(this)); for (int i = 0; i < a.length(); i++) hist.add(a.getJSONObject(i)); } catch (Exception ignored) { }
    }

    private void saveHist() {
        JSONArray a = new JSONArray();
        for (JSONObject e : hist) a.put(e);
        try (FileOutputStream out = new FileOutputStream(histFile())) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- config, events, notification

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONObject c = new JSONObject(p.getString("cfg", "{}"));
            them = c.optString("them", them); me = c.optString("me", me);
            themAsr = c.optString("themAsr", themAsr); meAsr = c.optString("meAsr", meAsr);
            themTts = c.optString("themTts", themTts); meTts = c.optString("meTts", meTts);
            spkThem = c.optBoolean("spkThem", true); spkMe = c.optBoolean("spkMe", true);
            rate = (float) c.optDouble("rate", 1);
        } catch (Exception ignored) { }
    }

    private String stateJson() {
        try {
            return new JSONObject().put("t", "state").put("manual", manual).put("auto", auto).put("listening", listening)
                    .put("muted", muted).put("earbuds", earbuds).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void emitEntry(JSONObject e) {
        try { emit(new JSONObject().put("t", "entry").put("e", e).toString()); } catch (Exception ignored) { }
    }

    private void emit(String json) {
        Sink s = sink;
        if (s != null) s.event(json);
    }

    private void notifyState() {
        emit(stateJson());
        if (foreground) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTE_ID, buildNote());
        }
    }

    private void goForeground() {
        if (foreground) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "实时翻译", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification n = buildNote();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTE_ID, n);
            foreground = true;
        } catch (Exception e) {
            // not allowed to use the microphone from the background right now
            try { if (Build.VERSION.SDK_INT >= 26) startForeground(NOTE_ID, n); foreground = true; } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNote() {
        String title, text;
        if (manual) title = listening ? "正在实时翻译" : "实时翻译";
        else if (listening) title = "自动翻译 · 正在听";
        else title = "自动翻译 · 待命";
        if (!lastLine.isEmpty() && listening) text = lastLine;
        else if (!manual && !earbuds) text = "戴上耳机就自动开始";
        else if (!manual) text = "听到外语就翻译到耳机里";
        else text = "双方随便说，自动翻译";
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, LiveService.class).setAction(ACT_PAUSE_ALL);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "关闭", ps).build());
        return b.build();
    }
}
