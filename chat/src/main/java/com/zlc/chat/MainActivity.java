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
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
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
    private static final int PICK_FILE = 42;
    private ValueCallback<Uri[]> fileCallback;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private static final int MIC_PERMISSION = 2;
    private PermissionRequest pendingMic;

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
        web.setWebChromeClient(new WebChromeClient() {
            // lets <input type="file"> open the phone's photo / video picker
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, PICK_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            // full-screen video playback
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) { callback.onCustomViewHidden(); return; }
                fullView = view;
                fullCallback = callback;
                ((FrameLayout) getWindow().getDecorView()).addView(view, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            // microphone for voice calls
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    if (!audio) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingMic != null) pendingMic.deny();
                        pendingMic = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
                    }
                });
            }
        });
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

    private void exitFullscreen() {
        if (fullView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(fullView);
        fullView = null;
        if (fullCallback != null) fullCallback.onCustomViewHidden();
        fullCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_PERMISSION && pendingMic != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingMic.deny();
            pendingMic = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
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
        if (fullView != null) { exitFullscreen(); return; }
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

        /** Call started or ended: phone-call audio mode, speaker on, and keep the microphone alive in the background. */
        @SuppressWarnings("deprecation")
        @JavascriptInterface
        public void callMode(boolean on, String title, String text) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(on ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(on);
            }
            if (on) {
                Intent i = new Intent(ctx, KeepAliveService.class).putExtra("title", title).putExtra("text", text).putExtra("mic", true);
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                    else ctx.startService(i);
                } catch (Exception ignored) {
                }
            }
        }

        @SuppressWarnings("deprecation")
        @JavascriptInterface
        public void setSpeaker(boolean on) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setSpeakerphoneOn(on);
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
