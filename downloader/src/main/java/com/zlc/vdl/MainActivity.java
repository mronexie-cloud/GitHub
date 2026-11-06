package com.zlc.vdl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity implements Engine.Listener {
    private static final int LOGIN = 7;
    private WebView web;
    private boolean pageReady = false;
    private String pendingShare = null;
    private boolean pendingAuto = false;
    private String lastClip = null;
    private String loginPlatform = null;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                onEngine(Engine.state, Engine.version);
                flushShare();
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        Engine.listener = this;
        web.loadUrl("file:///android_asset/index.html");
        handleIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Links shared from Douyin / TikTok / YouTube ... land here. */
    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        pendingShare = text;
        pendingAuto = i.getBooleanExtra("autostart", false);
        flushShare();
    }

    private void flushShare() {
        if (!pageReady || pendingShare == null) return;
        js("window.onShared(" + JSONObject.quote(pendingShare) + "," + pendingAuto + ")");
        lastClip = pendingShare;
        pendingShare = null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || !pageReady) return;
        String c = readClipboard();
        if (c == null || c.equals(lastClip)) return;
        String url = Engine.extractUrl(c);
        if (url == null || Engine.platformOf(url).equals("other")) return;
        lastClip = c;
        js("window.onClipboard(" + JSONObject.quote(c) + ")");
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            return t == null ? null : t.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---- Engine.Listener (called from background threads)

    @Override
    public void onEngine(String state, String version) {
        if (pageReady) js("window.onEngine && window.onEngine(" + JSONObject.quote(state) + "," + JSONObject.quote(version) + ")");
    }

    @Override
    public void onAnalyze(String reqId, JSONObject result) {
        js("window.onAnalyze && window.onAnalyze(" + JSONObject.quote(reqId) + "," + result.toString() + ")");
    }

    @Override
    public void onTask(JSONObject task) {
        js("window.onTask && window.onTask(" + task.toString() + ")");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LOGIN) js("window.onLogin && window.onLogin(" + JSONObject.quote(loginPlatform == null ? "" : loginPlatform) + ")");
    }

    @Override
    protected void onDestroy() {
        if (Engine.listener == this) Engine.listener = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    class Bridge {
        @JavascriptInterface
        public String engine() {
            try {
                return new JSONObject().put("state", Engine.state).put("version", Engine.version).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void analyze(String reqId, String text) {
            Engine.analyze(reqId, text);
        }

        @JavascriptInterface
        public String download(String json) {
            try {
                if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ui.post(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2));
                }
                return Engine.download(new JSONObject(json));
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String tasks() {
            return Engine.taskList().toString();
        }

        @JavascriptInterface
        public void cancel(String id) {
            Engine.cancel(id);
        }

        @JavascriptInterface
        public void remove(String id) {
            Engine.remove(id);
        }

        @JavascriptInterface
        public boolean open(String id) {
            JSONObject t = Engine.task(id);
            if (t == null || t.optString("uri").isEmpty()) return false;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(t.optString("uri")), t.optString("mime", "video/*"));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean share(String id) {
            JSONObject t = Engine.task(id);
            if (t == null || t.optString("uri").isEmpty()) return false;
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(t.optString("mime", "video/*"));
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(t.optString("uri")));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String paste() {
            final String[] out = {null};
            final Object lock = new Object();
            ui.post(() -> {
                String c = readClipboard();
                synchronized (lock) { out[0] = c == null ? "" : c; lock.notifyAll(); }
            });
            synchronized (lock) {
                try { if (out[0] == null) lock.wait(1500); } catch (InterruptedException ignored) { }
            }
            return out[0] == null ? "" : out[0];
        }

        @JavascriptInterface
        public String logins() {
            final String[] out = {null};
            final Object lock = new Object();
            ui.post(() -> {
                String s = Engine.loginStatus().toString();
                synchronized (lock) { out[0] = s; lock.notifyAll(); }
            });
            synchronized (lock) {
                try { if (out[0] == null) lock.wait(1500); } catch (InterruptedException ignored) { }
            }
            return out[0] == null ? "{}" : out[0];
        }

        @JavascriptInterface
        public void login(String platform, boolean auto) {
            ui.post(() -> {
                loginPlatform = platform;
                Intent i = new Intent(MainActivity.this, LoginActivity.class).putExtra("platform", platform).putExtra("auto", auto);
                startActivityForResult(i, LOGIN);
            });
        }

        @JavascriptInterface
        public void logout(String platform) {
            ui.post(() -> {
                Engine.clearCookies(platform);
                js("window.onLogin && window.onLogin(" + JSONObject.quote(platform) + ")");
            });
        }

        @JavascriptInterface
        public void update() {
            Engine.bg.execute(Engine::update);
        }
    }
}
