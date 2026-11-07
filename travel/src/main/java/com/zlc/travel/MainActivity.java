package com.zlc.travel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app screen (a web page in assets/index.html) plus location, sharing, links and network for it. */
public class MainActivity extends Activity {
    static final String TAG = "Travel";
    static final int REQ_LOC = 1;
    static final String UA = "SEATravelGuide/1.0 (Android; https://github.com/mronexie-cloud/GitHub)";
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    private LocationManager lm;
    private LocationListener listener;
    private boolean locating;
    /** for the automatic test, e.g. "plan:吉隆坡:3" */
    private String demo;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        demo = getIntent().getStringExtra("demo");
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(true);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setBackgroundColor(dark() ? 0xFF0F171A : 0xFFF6F2EA);
        web.setWebViewClient(new WebViewClient() {
            /** links to other sites (maps, Waze, ticket pages) open in their own apps */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                open(u.toString());
                return true;
            }

            /** Map tiles: fetched by the app with its own name (OpenStreetMap's tile policy) and kept for 30 days. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"tile.openstreetmap.org".equals(u.getHost())) return null;
                byte[] b = tile(u);
                Map<String, String> h = new HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                h.put("Cache-Control", "max-age=604800");
                if (b == null) return new WebResourceResponse("image/png", null, 404, "No tile", h, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/png", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
        });
        web.addJavascriptInterface(new Bridge(), "A");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    /** the automatic test sends new demo commands to the running app */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String d = intent.getStringExtra("demo");
        if (d != null) js("window.runDemo&&window.runDemo(" + JSONObject.quote(d) + ")");
    }

    byte[] tile(Uri u) {
        List<String> seg = u.getPathSegments();
        if (seg.size() != 3) return null;
        File dir = new File(getCacheDir(), "tiles");
        File f = new File(dir, seg.get(0) + "_" + seg.get(1) + "_" + seg.get(2).replaceAll("[^0-9.a-z]", ""));
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86400_000L) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                return b;
            } catch (Exception ignored) { }
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u.toString()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            byte[] b = out.toByteArray();
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); } catch (Exception ignored) { }
            return b;
        } catch (Exception e) {
            Log.w(TAG, "tile " + u, e);
            // offline: an old copy is better than nothing
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[(int) f.length()];
                    int n = 0, r;
                    while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                    return b;
                } catch (Exception ignored) { }
            }
            return null;
        }
    }

    boolean dark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean d = dark();
        web.setBackgroundColor(d ? 0xFF0F171A : 0xFFF6F2EA);
        getWindow().setStatusBarColor(d ? 0xFF0F171A : 0xFFF6F2EA);
        getWindow().setNavigationBarColor(d ? 0xFF162125 : 0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = getWindow().getDecorView().getSystemUiVisibility();
            f = d ? (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
        js("window.setDark&&window.setDark(" + d + ")");
    }

    void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    void open(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可以打开这个链接的 App", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack?window.onAndroidBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        stopLocating();
        bg.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------- location

    private boolean hasLocPerm() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void locate() {
        if (!hasLocPerm()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        Location best = lastKnown();
        if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60_000L) {
            sendLocation(best);
            return;
        }
        if (locating) return;
        locating = true;
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                stopLocating();
                sendLocation(l);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle e) { }

            @Override
            public void onProviderEnabled(String p) { }

            @Override
            public void onProviderDisabled(String p) { }
        };
        boolean any = false;
        try {
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0, 0, listener, Looper.getMainLooper());
                    any = true;
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "location", e);
        }
        if (!any) {
            stopLocating();
            if (best != null) sendLocation(best);
            else sendError("手机的定位服务没有打开，请在设置里打开定位");
            return;
        }
        ui.postDelayed(() -> {
            if (!locating) return;
            stopLocating();
            Location l = lastKnown();
            if (l != null) sendLocation(l);
            else sendError("暂时定位不到，请到户外再试，或直接输入出发地");
        }, 25_000);
    }

    private Location lastKnown() {
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "lastKnown", e);
        }
        return best;
    }

    private void stopLocating() {
        locating = false;
        if (listener != null) {
            try { lm.removeUpdates(listener); } catch (Exception ignored) { }
            listener = null;
        }
    }

    private void sendLocation(Location l) {
        Log.i(TAG, "location " + l.getLatitude() + "," + l.getLongitude() + " from " + l.getProvider());
        js("window.onLocate&&window.onLocate(" + l.getLatitude() + "," + l.getLongitude() + ",null)");
    }

    private void sendError(String m) {
        js("window.onLocate&&window.onLocate(0,0," + JSONObject.quote(m) + ")");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_LOC) return;
        if (hasLocPerm()) locate();
        else sendError("没有定位权限，请直接输入出发地");
    }

    // ---------- network for the page (geocoding, weather, exchange rates)

    static String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    class Bridge {
        @JavascriptInterface
        public boolean dark() {
            return MainActivity.this.dark();
        }

        @JavascriptInterface
        public String demo() {
            String d = demo;
            demo = null;
            return d == null ? "" : d;
        }

        @JavascriptInterface
        public void locate() {
            ui.post(MainActivity.this::locate);
        }

        @JavascriptInterface
        public void open(String url) {
            ui.post(() -> MainActivity.this.open(url));
        }

        @JavascriptInterface
        public void share(String text) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "分享攻略"));
            });
        }

        @JavascriptInterface
        public void toast(String m) {
            ui.post(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show());
        }

        /** Fetches a URL off the main thread; the answer comes back as window.onHttp(tag, text, error). */
        @JavascriptInterface
        public void httpGet(String url, String tag) {
            bg.execute(() -> {
                String text = null, err = null;
                try {
                    text = MainActivity.httpGet(url);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                    Log.w(TAG, "http " + url, e);
                }
                js("window.onHttp&&window.onHttp(" + JSONObject.quote(tag) + "," + (text == null ? "null" : JSONObject.quote(text))
                        + "," + (err == null ? "null" : JSONObject.quote(err)) + ")");
            });
        }

        @JavascriptInterface
        public void log(String m) {
            Log.i(TAG, m);
        }
    }
}
