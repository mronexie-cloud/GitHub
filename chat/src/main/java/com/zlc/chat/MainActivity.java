package com.zlc.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    static final String CH_MSG = "messages";
    static final String CH_MSG_SILENT = "messages_silent";
    private WebView web;
    private int notifSeq = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(this), "Android");
        setContentView(web);
        createChannels();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel loud = new NotificationChannel(CH_MSG, "新消息 New messages", NotificationManager.IMPORTANCE_HIGH);
        loud.enableVibration(true);
        nm.createNotificationChannel(loud);
        NotificationChannel quiet = new NotificationChannel(CH_MSG_SILENT, "新消息（静音）Silent", NotificationManager.IMPORTANCE_LOW);
        quiet.setSound(null, null);
        nm.createNotificationChannel(quiet);
        NotificationChannel keep = new NotificationChannel(KeepAliveService.CHANNEL, "保持在线 Stay online", NotificationManager.IMPORTANCE_LOW);
        keep.setShowBadge(false);
        nm.createNotificationChannel(keep);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.evaluateJavascript("window.__appVisible=true;window.onAppVisible&&window.onAppVisible()", null);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        for (int i = 0; i < 50; i++) nm.cancel(1000 + i);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (web != null) web.evaluateJavascript("window.__appVisible=false", null);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if ("true".equals(v)) return;
            if ("\"bg\"".equals(v)) { moveTaskToBack(true); return; }
            MainActivity.super.onBackPressed();
        });
    }

    class Bridge {
        private final Context ctx;
        Bridge(Context c) { ctx = c; }

        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }

        /** The phone's system font scale (1.0 = default). */
        @JavascriptInterface
        public float fontScale() {
            return ctx.getResources().getConfiguration().fontScale;
        }

        /** Plays the phone's default notification sound. */
        @JavascriptInterface
        public void playTone() {
            try {
                Ringtone r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                if (r != null) r.play();
            } catch (Exception ignored) {
            }
        }

        /** Shows a system notification for a new message (used while the app is in the background). */
        @JavascriptInterface
        public void notify(String title, String body, boolean silent) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent it = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 1, it, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, silent ? CH_MSG_SILENT : CH_MSG)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pi);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(silent ? Notification.PRIORITY_LOW : Notification.PRIORITY_HIGH);
                if (!silent) b.setDefaults(Notification.DEFAULT_ALL);
            }
            nm.notify(1000 + (notifSeq++ % 50), b.build());
        }

        /** Keeps the app alive in the background while the user is in a room. */
        @JavascriptInterface
        public void startKeepAlive(String title, String text) {
            Intent i = new Intent(ctx, KeepAliveService.class).putExtra("title", title).putExtra("text", text);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void stopKeepAlive() {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        }

        @JavascriptInterface
        public boolean batteryOk() {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        public void askBattery() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        /** Native GET used when the page cannot reach a translation service with fetch(). Result comes back via window.__httpCb. */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            if (url == null || !url.startsWith("https://")) return;
            new Thread(() -> {
                String body = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36");
                    if (c.getResponseCode() == 200) {
                        InputStream in = c.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        body = out.toString("UTF-8");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__httpCb && window.__httpCb(" + JSONObject.quote(id) + "," + (body == null ? "null" : JSONObject.quote(body)) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
