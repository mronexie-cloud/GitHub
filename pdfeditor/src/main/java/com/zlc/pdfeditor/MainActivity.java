package com.zlc.pdfeditor;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private boolean pageReady = false;
    private Intent pendingIntent;
    private volatile String pendingName = null;
    private volatile String pendingB64 = null;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return asset(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (pendingIntent != null) {
                    Intent i = pendingIntent;
                    pendingIntent = null;
                    handleIntent(i);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                String type = "*/*";
                for (String a : p.getAcceptTypes()) {
                    if (a == null) continue;
                    if (a.contains("image")) { type = "image/*"; break; }
                    if (a.contains("pdf")) { type = "application/pdf"; break; }
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(type);
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        pendingIntent = getIntent();
        web.loadUrl(HOME);
    }

    /** 用 https://appassets.androidplatform.net/assets/... 提供 APK 内的网页文件（fetch 可用） */
    private WebResourceResponse asset(Uri u) {
        if (!"appassets.androidplatform.net".equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || !path.startsWith("/assets/")) return null;
        path = path.substring("/assets/".length());
        String mime = "application/octet-stream";
        if (path.endsWith(".html")) mime = "text/html";
        else if (path.endsWith(".js")) mime = "application/javascript";
        else if (path.endsWith(".css")) mime = "text/css";
        else if (path.endsWith(".otf")) mime = "font/otf";
        else if (path.endsWith(".png")) mime = "image/png";
        try {
            WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", getAssets().open(path));
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady) handleIntent(intent); else pendingIntent = intent;
    }

    /** 从「用其他应用打开」或「分享」收到的 PDF */
    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        final Uri uri = u;
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                pendingName = displayName(uri);
                pendingB64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                runOnUiThread(() -> web.evaluateJavascript("window.__openPending&&window.__openPending()", null));
            } catch (Exception ignored) { }
        }).start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        return "文档.pdf";
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int k = 0; k < n; k++) r[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    r = new Uri[]{data.getData()};
                }
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var m=[].slice.call(document.querySelectorAll('.modal')).filter(function(x){return !x.hidden});"
                        + "if(m.length){m.forEach(function(x){x.hidden=true});return 1}"
                        + "var s=document.getElementById('side');if(s&&s.classList.contains('open')){s.classList.remove('open');document.getElementById('scrim').classList.remove('on');return 1}return 0})()",
                v -> { if ("0".equals(v)) super.onBackPressed(); });
    }

    class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDF编辑器");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) return "保存失败：无法创建文件";
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
                    return "已保存到 下载/PDF编辑器/" + name;
                } else {
                    File dir = new File(getExternalFilesDir(null), "PDF");
                    dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                    return "已保存到 " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "保存失败：" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String pendingName() { return pendingName == null ? "" : pendingName; }

        @JavascriptInterface
        public String pendingB64() {
            String b = pendingB64;
            pendingB64 = null;
            return b == null ? "" : b;
        }
    }
}
