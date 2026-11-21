package com.zlc.deathclock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * 死亡倒计时: the page (assets/index.html) shows the countdown and settings; this activity
 * stores the settings, sends test emails and keeps the yearly background check scheduled.
 */
public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        Reminder.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Catch up on a missed yearly email (e.g. the phone was off that day).
        final Context app = getApplicationContext();
        new Thread(() -> {
            if (Reminder.runIfDue(app)) web.post(() -> web.evaluateJavascript("window.refreshStatus&&refreshStatus()", null));
        }).start();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private void reply(String id, boolean ok, String payload) {
        final String js = "window.__nativeCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload == null ? "" : payload) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    class Bridge {
        @JavascriptInterface
        public String getConfig() {
            try {
                return Reminder.publicConfig(MainActivity.this).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String saveConfig(String json) {
            try {
                Reminder.save(MainActivity.this, new JSONObject(json));
                Reminder.schedule(MainActivity.this);
                return "ok";
            } catch (Exception e) {
                return "保存失败：" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void sendTest(final String id) {
            new Thread(() -> {
                String err = Reminder.sendNow(MainActivity.this, true);
                reply(id, err == null, err == null ? "ok" : err);
            }).start();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }
    }
}
