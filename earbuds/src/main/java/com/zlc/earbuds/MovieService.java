package com.zlc.earbuds;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * 视频同传: translates the film / video playing on the phone while you watch.
 * Takes the sound the video app plays (Android's playback capture, approved by the user through the
 * screen-recording prompt; Android 10+), recognises the dialogue sentence by sentence and shows
 * Chinese subtitles floating over the video and/or reads the translation into the earbuds while
 * the film's own sound is turned down.
 */
public class MovieService extends Service {
    interface Sink { void event(String json); }

    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACT_STOP = "stop";
    static final String PREFS = "movie";
    private static final String CH = "movie";
    private static final int NOTE_ID = 80;
    private static final long STALE_MS = 4000; // a line the voice can't get to within 4 s is only shown

    static volatile Sink sink;
    static volatile MovieService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private CloudAsr asr;
    private Voice voice;
    private String from = "en", to = "zh-CN", fromAsr = "en-US", toTts = "zh-CN";
    private boolean subs = true, dub = true, duck = true;
    private float rate = 1.25f;
    private StreamAsr stream;
    private int draftSession = -1;   // which stretch of speech the live subtitle belongs to
    private int draftGen;            // bumps when a line is finished, so late translations of it are dropped

    private String draftSrc = "", draftTr = "";
    private boolean draftBusy;
    private final ArrayDeque<String[]> speakQ = new ArrayDeque<>(); // {text, queuedAt}
    private boolean speaking;
    private int lines;

    // floating subtitles
    private WindowManager wm;
    private View overlay;
    private TextView subSrc, subTr;
    private WindowManager.LayoutParams lp;
    private final Runnable hideSubs = () -> { if (overlay != null) overlay.animate().alpha(0f).setDuration(400).start(); };

    static String state() {
        MovieService s = instance;
        return "{\"t\":\"movie\",\"on\":" + (s != null) + ",\"lines\":" + (s != null ? s.lines : 0) + "}";
    }

    static boolean overlayAllowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        voice = Voice.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent == null || ACT_STOP.equals(intent.getAction()) || projection != null) {
            if (intent != null && ACT_STOP.equals(intent.getAction())) stopSelf();
            return START_NOT_STICKY;
        }
        loadConfig();
        int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null || Build.VERSION.SDK_INT < 29) { emitError("capture"); stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { ui.post(MovieService.this::stopSelf); }
        }, ui);

        // live (streaming) recognition first; if Google's streaming service doesn't answer, sentence-by-sentence
        stream = new StreamAsr(projection, fromAsr, new StreamAsr.Cb() {
            @Override public void partial(int session, String text) { ui.post(() -> draft(session, text)); }
            @Override public void fin(int session, String text) { ui.post(() -> finalLine(session, text)); }
            @Override public void failed() { ui.post(MovieService.this::fallback); }
        });
        stream.start();
        if (subs) showOverlay();
        emit(state());
        return START_NOT_STICKY;
    }

    private void fallback() {
        if (projection == null || asr != null) return;
        stream = null;
        asr = new CloudAsr(new CloudAsr.Callback() {
            @Override public void event(String type, String text, String tag) { ui.post(() -> onAsr(type, text)); }
            @Override public void level(float db) { }
        });
        asr.useCapture(projection);
        asr.start(fromAsr, "", "movie");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (stream != null) stream.stop();
        if (asr != null) asr.stop();
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
        if (speaking) voice.stop(false);
        removeOverlay();
        instance = null;
        emit(state());
        super.onDestroy();
    }

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONObject c = new JSONObject(p.getString("cfg", "{}"));
            from = c.optString("from", from); to = c.optString("to", to);
            fromAsr = c.optString("fromAsr", fromAsr); toTts = c.optString("toTts", toTts);
            subs = c.optBoolean("subs", true); dub = c.optBoolean("dub", true); duck = c.optBoolean("duck", true);
            rate = (float) Math.max(1.25, c.optDouble("rate", 1.25));
        } catch (Exception ignored) { }
        if (subs && !overlayAllowed(this)) subs = false;
    }

    // ---------------------------------------------------------------- recognition → subtitles / voice

    private void onAsr(String type, String text) {
        if (type.startsWith("partial_")) { if (!"…".equals(text)) draft(0, text); return; }
        if (type.startsWith("final_")) { finalLine(0, text); return; }
        if ("error".equals(type) && "3".equals(text)) emitError("record");
    }

    /** Live subtitle while a line is still being spoken. */
    private void draft(int session, String text) {
        if (session != draftSession) { draftSession = session; draftTr = ""; }
        draftSrc = text;
        showSubs(text, draftTr, true);
        if (draftBusy) return;
        draftBusy = true;
        final String src = text;
        final int gen = draftGen, ses = session;
        new Thread(() -> {
            String tr = null;
            try { tr = Translator.translate(src, from, to); } catch (Exception ignored) { }
            final String r = tr;
            ui.post(() -> {
                draftBusy = false;
                // only if it is still the same line (a late answer for an earlier line is dropped)
                if (gen != draftGen || ses != draftSession) { if (!draftSrc.isEmpty()) draft(draftSession, draftSrc); return; }
                if (r != null) { draftTr = r; showSubs(draftSrc, draftTr, true); }
                if (!src.equals(draftSrc)) draft(draftSession, draftSrc);
            });
        }).start();
    }

    private void finalLine(int session, String text) {
        draftGen++;
        draftSrc = "";
        final String shown = session == draftSession ? draftTr : "";
        draftTr = "";
        final int gen = draftGen;
        showSubs(text, shown, true);
        new Thread(() -> {
            String tr = null;
            try { tr = Translator.translate(text, from, to); } catch (Exception ignored) { }
            final String r = tr != null ? tr : shown;
            ui.post(() -> {
                if (r == null || r.isEmpty()) return;
                lines++;
                // don't cover a newer line that is already being shown live
                if (gen == draftGen && draftSrc.isEmpty()) showSubs(text, r, false);
                emitLine(text, r);
                if (dub) {
                    speakQ.addLast(new String[]{r, String.valueOf(System.currentTimeMillis())});
                    if (!speaking) next();
                }
            });
        }).start();
    }

    private void next() {
        String[] it;
        long now = System.currentTimeMillis();
        while ((it = speakQ.pollFirst()) != null && now - Long.parseLong(it[1]) > STALE_MS) { /* too late to say: subtitles only */ }
        if (it == null) { speaking = false; return; }
        speaking = true;
        voice.speak("mv" + now, it[0], toTts, "ear", rate, duck, (id, how) -> next());
    }

    // ---------------------------------------------------------------- floating subtitles

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @SuppressLint("ClickableViewAccessibility")
    private void showOverlay() {
        if (overlay != null || !overlayAllowed(this)) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(30), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(170, 0, 0, 0));
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        subTr = new TextView(this);
        subTr.setTextColor(Color.WHITE);
        subTr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        subTr.setTypeface(Typeface.DEFAULT_BOLD);
        subTr.setShadowLayer(4, 0, 1, Color.BLACK);
        subTr.setText("视频同传已开启，播放视频就会出字幕");
        subSrc = new TextView(this);
        subSrc.setTextColor(Color.argb(200, 255, 255, 255));
        subSrc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subSrc.setMaxLines(2);
        box.addView(subTr);
        box.addView(subSrc);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.argb(220, 255, 255, 255));
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setPadding(dp(8), dp(4), dp(10), dp(8));
        close.setOnClickListener(v -> stopSelf());

        FrameLayout root = new FrameLayout(this);
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(close, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.horizontalMargin = 0.04f;
        lp.y = dp(90);

        // drag up / down to move the subtitles
        final float[] start = new float[2];
        root.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: start[0] = e.getRawY(); start[1] = lp.y; return true;
                case MotionEvent.ACTION_MOVE:
                    lp.y = (int) Math.max(0, start[1] - (e.getRawY() - start[0]));
                    try { wm.updateViewLayout(overlay, lp); } catch (Exception ignored) { }
                    return true;
                default: return false;
            }
        });
        try { wm.addView(root, lp); overlay = root; } catch (Exception e) { overlay = null; }
        ui.postDelayed(hideSubs, 5000);
    }

    private void showSubs(String src, String tr, boolean live) {
        if (overlay == null) return;
        ui.removeCallbacks(hideSubs);
        overlay.animate().cancel();
        overlay.setAlpha(1f);
        subTr.setText(tr == null || tr.isEmpty() ? "…" : tr);
        subTr.setAlpha(live ? 0.8f : 1f);
        subSrc.setText(src);
        ui.postDelayed(hideSubs, live ? 8000 : 5000);
    }

    private void removeOverlay() {
        ui.removeCallbacks(hideSubs);
        if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) { } overlay = null; }
    }

    // ---------------------------------------------------------------- notification, events

    private void emit(String json) { Sink s = sink; if (s != null) s.event(json); }

    private void emitError(String what) { emit("{\"t\":\"movie_error\",\"what\":\"" + what + "\"}"); }

    private void emitLine(String src, String tr) {
        try { emit(new JSONObject().put("t", "movie_line").put("src", src).put("tr", tr).toString()); } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "视频同传", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, MovieService.class).setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 4, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 5, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("视频同传进行中")
                .setContentText("正在把视频里的对白翻译成中文")
                .setOngoing(true).setShowWhen(false).setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "关闭", ps).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTE_ID, n);
        } catch (Exception ignored) { }
    }
}
