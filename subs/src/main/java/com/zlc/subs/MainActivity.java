package com.zlc.subs;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 订阅卡包: the whole app is assets/index.html (subscription cards, expense records, bills).
 * Data lives in the WebView's localStorage. This activity adds renewal reminders as system
 * notifications, the share sheet for backups, the file picker for restoring them, the theme
 * of the system bars, and the back button.
 */
public class MainActivity extends Activity {
    private static final int PICK_FILE = 7;
    private static final int ASK_NOTIFY = 8;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private TextRecognizer recognizer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setBackgroundColor(0xFF000000);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                boolean images = false;
                for (String t : p.getAcceptTypes()) if (t != null && t.startsWith("image")) images = true;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(images ? "image/*" : "*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, images ? "选择账单截图" : "选择备份文件"), PICK_FILE);
                } catch (Exception e) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onAndroidResume&&onAndroidResume()", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_FILE && pending != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    out = new Uri[cd.getItemCount()];
                    for (int k = 0; k < out.length; k++) out[k] = cd.getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            pending.onReceiveValue(out);
            pending = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (web != null) web.evaluateJavascript("window.onAndroidResume&&onAndroidResume()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    private void ocrDone(String id, String json, String err) {
        String js = "window.__ocrDone&&__ocrDone(" + JSONObject.quote(id) + "," + (json == null ? "null" : JSONObject.quote(json))
                + "," + (err == null ? "null" : JSONObject.quote(err)) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    class Bridge {
        /** Reads the text of a screenshot on the phone; answers with __ocrDone(id, "[{t,x,y,w,h}]"). */
        @JavascriptInterface
        public void ocr(String id, String base64) {
            runOnUiThread(() -> {
                try {
                    byte[] b = Base64.decode(base64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                    if (bmp == null) {
                        ocrDone(id, null, "图片读不出来");
                        return;
                    }
                    if (recognizer == null) recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener(text -> {
                                JSONArray a = new JSONArray();
                                try {
                                    for (Text.TextBlock blk : text.getTextBlocks()) {
                                        for (Text.Line ln : blk.getLines()) {
                                            Rect r = ln.getBoundingBox();
                                            if (r == null) continue;
                                            JSONObject o = new JSONObject();
                                            o.put("t", ln.getText());
                                            o.put("x", r.left);
                                            o.put("y", r.top);
                                            o.put("w", r.width());
                                            o.put("h", r.height());
                                            a.put(o);
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                                ocrDone(id, a.toString(), null);
                            })
                            .addOnFailureListener(e -> ocrDone(id, null, "识别失败：" + e.getMessage()));
                } catch (Throwable e) {
                    ocrDone(id, null, "识别失败：" + e.getMessage());
                }
            });
        }


        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "订阅卡包备份");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "保存或发送备份"));
            });
        }

        /** Replaces all pending renewal reminders: [{"key","at"(ms),"title","text"}]. */
        @JavascriptInterface
        public void schedule(String json) {
            Reminders.schedule(MainActivity.this, json);
        }

        /** "on" or "off". */
        @JavascriptInterface
        public String notifyStatus() {
            return notificationsAllowed() ? "on" : "off";
        }

        /** Asks for the notification permission, or opens the app's notification settings. */
        @JavascriptInterface
        public void requestNotify() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && !getSharedPreferences("app", MODE_PRIVATE).getBoolean("askedNotify", false)) {
                    getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("askedNotify", true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFY);
                    return;
                }
                try {
                    Intent i;
                    if (Build.VERSION.SDK_INT >= 26) {
                        i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    } else {
                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    }
                    startActivity(i);
                } catch (Exception ignored) {
                }
            });
        }

        /** "light" or "dark": matches the status and navigation bars to the page. */
        @JavascriptInterface
        public void setBars(String theme) {
            runOnUiThread(() -> {
                boolean light = "light".equals(theme);
                int c = light ? 0xFFF2F2F6 : 0xFF000000;
                Window w = getWindow();
                w.setStatusBarColor(c);
                w.setNavigationBarColor(c);
                web.setBackgroundColor(c);
                int f = w.getDecorView().getSystemUiVisibility();
                if (Build.VERSION.SDK_INT >= 23) {
                    f = light ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    f = light ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
                w.getDecorView().setSystemUiVisibility(f);
            });
        }
    }
}
