package com.zlc.vdl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** In-app browser for a platform: visiting (or signing in) gives the cookies some platforms require. */
public class LoginActivity extends Activity {
    private WebView web;
    private boolean autoDone;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String platform = getIntent().getStringExtra("platform");
        boolean auto = getIntent().getBooleanExtra("auto", false);
        String home = Engine.platformHome(platform);
        if (home == null) { finish(); return; }
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1D2B6B"));
        bar.setPadding((int) (16 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
        TextView title = new TextView(this);
        title.setText(Engine.platformName(platform) + (auto ? " · 正在获取 Cookie…" : " · 登录后点“完成”"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button done = new Button(this);
        done.setText("完成");
        done.setOnClickListener(v -> close());
        bar.addView(done);
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (auto && !autoDone) {
                    autoDone = true;
                    web.postDelayed(() -> close(), 6000);
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        web.loadUrl(home);
    }

    private void close() {
        if (isFinishing()) return;
        CookieManager.getInstance().flush();
        Engine.writeCookies();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else close();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
