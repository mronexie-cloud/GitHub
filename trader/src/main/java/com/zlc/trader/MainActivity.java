package com.zlc.trader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 天才交易员: a full-screen WebView running assets/index.html. The game is fully offline;
 * the only native help it needs is the system file picker (for choosing background music).
 */
public class MainActivity extends Activity {
    private static final int PICK = 7;
    private static final boolean FULLSCREEN = false;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private long lastBack;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "选择音乐"), PICK);
                } catch (Exception e) {
                    pending = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        immersive();
    }

    private void immersive() {
        if (!FULLSCREEN) return;
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || pending == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null && data.getData() != null) out = new Uri[]{data.getData()};
        pending.onReceiveValue(out);
        pending = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    public void onBackPressed() {
        // The game saves on its own; ask twice so a stray back swipe does not close it.
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
            return;
        }
        lastBack = now;
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
    }
}
