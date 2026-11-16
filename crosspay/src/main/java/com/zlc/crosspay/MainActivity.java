package com.zlc.crosspay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 环球收款 (demo): the whole app is the offline page in assets, shown full screen. */
public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"file".equals(r.getUrl().getScheme()); // the demo never leaves the app
            }
        });
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(this), "Android");
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        // Let the page close its own sheets first; otherwise leave the app.
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    static class Bridge {
        private final Activity act;
        Bridge(Activity a) { act = a; }

        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("环球收款", text));
        }

        @JavascriptInterface
        public void share(String text) {
            act.runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                act.startActivity(Intent.createChooser(i, "分享"));
            });
        }
    }
}
