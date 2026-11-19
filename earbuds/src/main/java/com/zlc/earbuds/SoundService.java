package com.zlc.earbuds;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Sound effects for everything the phone plays: 全景声 (surround), 录音室 (studio), 无损 (no processing)
 * and 智能 (listens to what's playing and adapts the sound to the kind of music).
 *
 * Effects are attached to the phone's whole output (session 0) and also to every player that
 * announces its audio session (Spotify, YouTube Music, most music players), the same way
 * equalizer apps do. Runs as a small foreground service so the effects stay on in the background.
 */
public class SoundService extends Service {
    interface Sink { void event(String json); }

    static final String PREFS = "sound";
    static final String EXTRA_PRESET = "preset";
    private static final String CH = "sound";
    private static final int NOTE_ID = 79;

    static volatile Sink sink;
    static volatile SoundService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Chain> chains = new HashMap<>();
    private String preset = "off";
    private String genre = null;
    private String pendingGenre = null;
    private int pendingCount;
    private Visualizer vis;
    private double eLow, eMid, eHigh;
    private int frames;
    private BroadcastReceiver sessions;

    // target curves: gain in dB at 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz
    private static final int[] FREQ = {60, 230, 910, 3600, 14000};
    private static final double[] SPATIAL = {3, 1, 0, 1.5, 4};
    private static final double[] STUDIO = {1, -2, 0, 2, 3};
    private static final double[] G_VOCAL = {0, -1.5, 2, 3, 1};
    private static final double[] G_BASS = {2, 0, -0.5, 1.5, 2};
    private static final double[] G_ACOUSTIC = {1, 0, 0, 1, 3};
    private static final double[] G_POP = {2, 0, 0, 2, 2};

    /** One set of effects on one audio session. */
    private static final class Chain {
        Equalizer eq;
        BassBoost bass;
        Virtualizer virt;
        LoudnessEnhancer loud;

        Chain(int session) {
            try { eq = new Equalizer(1000, session); } catch (Throwable ignored) { }
            try { bass = new BassBoost(1000, session); } catch (Throwable ignored) { }
            try { virt = new Virtualizer(1000, session); } catch (Throwable ignored) { }
            try { if (Build.VERSION.SDK_INT >= 19) loud = new LoudnessEnhancer(session); } catch (Throwable ignored) { }
        }

        boolean any() { return eq != null || bass != null || virt != null || loud != null; }

        void apply(double[] curve, int bassStrength, int virtStrength, int loudMb) {
            if (eq != null) {
                try {
                    short[] range = eq.getBandLevelRange();
                    short n = eq.getNumberOfBands();
                    for (short b = 0; b < n; b++) {
                        int hz = eq.getCenterFreq(b) / 1000;
                        int mb = (int) Math.round(gainAt(curve, hz) * 100);
                        mb = Math.max(range[0], Math.min(range[1], mb));
                        eq.setBandLevel(b, (short) mb);
                    }
                    eq.setEnabled(true);
                } catch (Throwable ignored) { }
            }
            if (bass != null) {
                try {
                    if (bass.getStrengthSupported() && bassStrength > 0) { bass.setStrength((short) bassStrength); bass.setEnabled(true); }
                    else bass.setEnabled(false);
                } catch (Throwable ignored) { }
            }
            if (virt != null) {
                try {
                    if (virt.getStrengthSupported() && virtStrength > 0) { virt.setStrength((short) virtStrength); virt.setEnabled(true); }
                    else virt.setEnabled(false);
                } catch (Throwable ignored) { }
            }
            if (loud != null) {
                try {
                    if (loudMb > 0) { loud.setTargetGain(loudMb); loud.setEnabled(true); } else loud.setEnabled(false);
                } catch (Throwable ignored) { }
            }
        }

        void release() {
            for (AudioEffect e : new AudioEffect[]{eq, bass, virt, loud}) {
                if (e == null) continue;
                try { e.setEnabled(false); } catch (Throwable ignored) { }
                try { e.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Gain (dB) at a frequency, interpolated on a log scale between the curve points. */
    static double gainAt(double[] curve, int hz) {
        if (hz <= FREQ[0]) return curve[0];
        for (int i = 1; i < FREQ.length; i++) {
            if (hz <= FREQ[i]) {
                double t = (Math.log(hz) - Math.log(FREQ[i - 1])) / (Math.log(FREQ[i]) - Math.log(FREQ[i - 1]));
                return curve[i - 1] + t * (curve[i] - curve[i - 1]);
            }
        }
        return curve[FREQ.length - 1];
    }

    // ---------------------------------------------------------------- entry points

    static void set(Context c, String preset) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("preset", preset).apply();
        Intent i = new Intent(c, SoundService.class).putExtra(EXTRA_PRESET, preset);
        if (needsService(preset)) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } else if (instance != null) {
            c.startService(i); // tells the running service to switch off
        }
    }

    static String saved(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("preset", "off"); }

    static boolean needsService(String p) { return !"off".equals(p) && !"lossless".equals(p); }

    static String state(Context c) {
        SoundService s = instance;
        try {
            JSONObject o = new JSONObject().put("t", "sound").put("preset", s != null ? s.preset : saved(c))
                    .put("running", s != null).put("genre", s != null && s.genre != null ? s.genre : JSONObject.NULL)
                    .put("sessions", s != null ? s.chains.size() : 0)
                    .put("ok", s == null || !s.chains.isEmpty());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sessions = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (id <= 0) return;
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) attach(id);
                else { Chain ch = chains.remove(id); if (ch != null) ch.release(); emit(); }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(sessions, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(sessions, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String p = intent != null && intent.hasExtra(EXTRA_PRESET) ? intent.getStringExtra(EXTRA_PRESET) : saved(this);
        if (!needsService(p)) {
            goForeground(); // must be called after startForegroundService even when stopping straight away
            preset = p;
            stopSelf();
            return START_NOT_STICKY;
        }
        preset = p;
        goForeground();
        if (!chains.containsKey(0)) attach(0);
        applyAll();
        if ("smart".equals(preset)) startListening(); else stopListening();
        emit();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopListening();
        try { unregisterReceiver(sessions); } catch (Exception ignored) { }
        for (Chain c : chains.values()) c.release();
        chains.clear();
        instance = null;
        Sink s = sink;
        if (s != null) s.event(state(this));
        super.onDestroy();
    }

    private void attach(int session) {
        if (chains.containsKey(session)) return;
        Chain c = new Chain(session);
        if (!c.any()) return;
        chains.put(session, c);
        apply(c);
        emit();
    }

    private void applyAll() { for (Chain c : chains.values()) apply(c); }

    private void apply(Chain c) {
        switch (preset) {
            case "spatial": c.apply(SPATIAL, 350, 1000, 250); break;
            case "studio": c.apply(STUDIO, 0, 150, 150); break;
            case "smart":
                if ("vocal".equals(genre)) c.apply(G_VOCAL, 0, 200, 200);
                else if ("bass".equals(genre)) c.apply(G_BASS, 150, 400, 100);
                else if ("acoustic".equals(genre)) c.apply(G_ACOUSTIC, 100, 600, 200);
                else c.apply(G_POP, 250, 400, 200);
                break;
            default: c.release();
        }
    }

    // ---------------------------------------------------------------- 智能: recognise the kind of music

    private void startListening() {
        if (vis != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            final int rate = Visualizer.getMaxCaptureRate() / 2;
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int sr) { }
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) { onFft(fft, samplingRate); }
            }, rate, false, true);
            vis.setEnabled(true);
        } catch (Throwable e) {
            vis = null;
        }
    }

    private void stopListening() {
        if (vis == null) return;
        try { vis.setEnabled(false); } catch (Throwable ignored) { }
        try { vis.release(); } catch (Throwable ignored) { }
        vis = null;
        genre = null;
    }

    private void onFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length / 2;
        double sr = samplingRateMilliHz / 1000.0;
        double binHz = sr / fft.length;
        double lo = 0, mid = 0, hi = 0;
        for (int k = 1; k < n; k++) {
            double re = fft[2 * k], im = fft[2 * k + 1];
            double m = re * re + im * im;
            double hz = k * binHz;
            if (hz < 250) lo += m;
            else if (hz < 4000) mid += m;
            else if (hz < 16000) hi += m;
        }
        eLow += lo; eMid += mid; eHigh += hi;
        if (++frames < 30) return; // about 3 seconds of music
        double total = eLow + eMid + eHigh;
        double l = eLow / Math.max(total, 1), m = eMid / Math.max(total, 1), h = eHigh / Math.max(total, 1);
        boolean playing = total / frames > 50;
        eLow = eMid = eHigh = 0;
        frames = 0;
        if (!playing) return;
        String g;
        if (l > 0.55) g = "bass";
        else if (m > 0.55 && h < 0.12) g = "vocal";
        else if (l < 0.3 && h > 0.18) g = "acoustic";
        else g = "pop";
        final String guess = g;
        ui.post(() -> {
            if (guess.equals(genre)) { pendingGenre = null; return; }
            if (guess.equals(pendingGenre)) pendingCount++; else { pendingGenre = guess; pendingCount = 1; }
            if (pendingCount >= 2) { genre = guess; pendingGenre = null; applyAll(); emit(); updateNote(); }
        });
    }

    // ---------------------------------------------------------------- notification, events

    private void emit() {
        Sink s = sink;
        if (s != null) s.event(state(this));
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "音效", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification n = note();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTE_ID, n);
        } catch (Exception ignored) { }
    }

    private void updateNote() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTE_ID, note());
    }

    private static String name(String p) {
        switch (p) {
            case "spatial": return "超清 AUDIO 全景声";
            case "studio": return "超清录音室音质";
            case "smart": return "超清智能音效";
            default: return "音效";
        }
    }

    private static String genreName(String g) {
        if (g == null) return "正在识别曲风…";
        switch (g) {
            case "vocal": return "已适配：人声 / 播客";
            case "bass": return "已适配：电子 / 嘻哈";
            case "acoustic": return "已适配：古典 / 原声";
            default: return "已适配：流行";
        }
    }

    @SuppressWarnings("deprecation")
    private Notification note() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(name(preset) + " 已开启")
                .setContentText("smart".equals(preset) ? genreName(genre) : "对手机播放的声音生效")
                .setOngoing(true).setShowWhen(false).setContentIntent(pi).build();
    }
}
