package com.zlc.travel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app screen (a web page in assets/index.html) plus location, sharing, links and network for it. */
public class MainActivity extends Activity {
    static final String TAG = "Travel";
    static final int REQ_LOC = 1, REQ_WATCH = 2, REQ_CAL = 3;
    /** the trip waiting for the calendar permission */
    private String pendingCal;
    static final String UA = "SEATravelGuide/1.9 (Android; https://github.com/mronexie-cloud/GitHub)";
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    private LocationManager lm;
    private LocationListener listener;
    private boolean locating;
    /** continuous position for the navigation page ("跟着我走") */
    private LocationListener watcher;
    private boolean watchWanted;
    /** for the automatic test, e.g. "plan:吉隆坡:3" */
    private String demo;
    /** text shared to the app (a place from Google Maps), waiting for the page */
    private String shared;

    static String sharedText(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return null;
        String t = i.getStringExtra(Intent.EXTRA_TEXT), sub = i.getStringExtra(Intent.EXTRA_SUBJECT);
        if (t == null) return null;
        return sub != null && !t.contains(sub) ? sub + "\n" + t : t;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        demo = getIntent().getStringExtra("demo");
        shared = sharedText(getIntent());
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
        String t = sharedText(intent);
        if (t != null) js("window.onShared&&window.onShared(" + JSONObject.quote(t) + ")");
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

    // ---------- following the person's position on the navigation page

    void watch(boolean on) {
        watchWanted = on;
        if (on) startWatching(); else stopWatching();
    }

    private void startWatching() {
        stopWatching();
        if (!hasLocPerm()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_WATCH);
            return;
        }
        watcher = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                js("window.onWatch&&window.onWatch(" + l.getLatitude() + "," + l.getLongitude() + "," + l.getAccuracy() + ")");
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle e) { }

            @Override
            public void onProviderEnabled(String p) { }

            @Override
            public void onProviderDisabled(String p) { }
        };
        try {
            Location last = lastKnown();
            if (last != null && System.currentTimeMillis() - last.getTime() < 2 * 60_000L) watcher.onLocationChanged(last);
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2000, 3, watcher, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "watch", e);
        }
    }

    private void stopWatching() {
        if (watcher != null) {
            try { lm.removeUpdates(watcher); } catch (Exception ignored) { }
            watcher = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopWatching(); // no GPS in the background
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (watchWanted) startWatching();
    }

    /** Opens Grab with the trip filled in; without Grab installed, its store page. */
    void grab(String url) {
        Log.i(TAG, "grab " + url);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "grab not installed");
        }
        Toast.makeText(this, "还没安装 Grab，先打开下载页面", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.grabtaxi.passenger")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            open("https://play.google.com/store/apps/details?id=com.grabtaxi.passenger");
        }
    }

    @Override
    protected void onDestroy() {
        stopWatching();
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

    // ---------- the trip in the phone's calendar, and the .ics file

    private boolean hasCalPerm() {
        return Build.VERSION.SDK_INT < 23
                || (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED);
    }

    private void calDone(boolean ok, String msg) {
        js("window.onCalendar&&window.onCalendar(" + ok + "," + JSONObject.quote(msg) + ")");
    }

    /** Writes the trip's events ({tag, events:[{title,start,end,tz,loc,desc}]}) into the phone's calendar.
     *  The same trip (tag) added again replaces the events it added before. */
    void addToCalendar(String json) {
        if (!hasCalPerm()) {
            pendingCal = json;
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, REQ_CAL);
            return;
        }
        bg.execute(() -> {
            try {
                JSONObject o = new JSONObject(json);
                ContentResolver cr = getContentResolver();
                String[] cal = pickCalendar(cr);
                long calId = Long.parseLong(cal[0]);
                SharedPreferences sp = getSharedPreferences("calendar", MODE_PRIVATE);
                String tag = o.getString("tag");
                for (String id : sp.getString(tag, "").split(",")) {
                    if (id.isEmpty()) continue;
                    try { cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, Long.parseLong(id)), null, null); } catch (Exception ignored) { }
                }
                JSONArray ev = o.getJSONArray("events");
                StringBuilder ids = new StringBuilder();
                long first = Long.MAX_VALUE;
                int n = 0;
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    v.put(Events.CALENDAR_ID, calId);
                    v.put(Events.TITLE, e.getString("title"));
                    v.put(Events.DESCRIPTION, e.optString("desc"));
                    v.put(Events.EVENT_LOCATION, e.optString("loc"));
                    long start = e.getLong("start");
                    v.put(Events.DTSTART, start);
                    v.put(Events.DTEND, e.getLong("end"));
                    v.put(Events.EVENT_TIMEZONE, e.optString("tz", "Asia/Kuala_Lumpur"));
                    Uri u = cr.insert(Events.CONTENT_URI, v);
                    if (u == null) continue;
                    ids.append(ContentUris.parseId(u)).append(',');
                    first = Math.min(first, start);
                    n++;
                }
                sp.edit().putString(tag, ids.toString()).apply();
                Log.i(TAG, "calendar: " + n + " events in " + cal[1]);
                calDone(true, "已加入日历「" + cal[1] + "」，共 " + n + " 条");
                final long at = first;
                if (n > 0) ui.postDelayed(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/" + at));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        Log.i(TAG, "no calendar app to show it", e);
                    }
                }, 1200);
            } catch (Exception e) {
                Log.w(TAG, "calendar", e);
                calDone(false, "加入日历失败：" + e.getMessage() + "。可以改用「分享日历文件」。");
            }
        });
    }

    /** {id, name}: the main writable calendar (the Google account's own one first); a local one when there is none */
    private String[] pickCalendar(ContentResolver cr) {
        String sel = Calendars.VISIBLE + "=1 AND " + Calendars.CALENDAR_ACCESS_LEVEL + ">=" + Calendars.CAL_ACCESS_CONTRIBUTOR;
        String[] best = null;
        int bestScore = -1;
        try (Cursor c = cr.query(Calendars.CONTENT_URI, new String[]{Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_TYPE, Calendars.IS_PRIMARY, Calendars.ACCOUNT_NAME, Calendars.OWNER_ACCOUNT}, sel, null, null)) {
            while (c != null && c.moveToNext()) {
                boolean google = "com.google".equals(c.getString(2));
                boolean primary = c.getInt(3) == 1 || (c.getString(4) != null && c.getString(4).equals(c.getString(5)));
                int score = (primary ? 2 : 0) + (google ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = new String[]{String.valueOf(c.getLong(0)), c.getString(1) == null ? "日历" : c.getString(1)};
                }
            }
        }
        if (best != null) return best;
        // no calendar on the phone (no account): make one that lives only on this phone
        Uri uri = Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, "东南亚旅游攻略")
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        ContentValues v = new ContentValues();
        v.put(Calendars.ACCOUNT_NAME, "东南亚旅游攻略");
        v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        v.put(Calendars.NAME, "travel");
        v.put(Calendars.CALENDAR_DISPLAY_NAME, "旅游行程");
        v.put(Calendars.CALENDAR_COLOR, 0xFF0E7672);
        v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
        v.put(Calendars.OWNER_ACCOUNT, "东南亚旅游攻略");
        v.put(Calendars.VISIBLE, 1);
        v.put(Calendars.SYNC_EVENTS, 1);
        v.put(Calendars.CALENDAR_TIME_ZONE, "Asia/Kuala_Lumpur");
        Uri made = cr.insert(uri, v);
        if (made == null) throw new IllegalStateException("手机里没有可以写入的日历");
        return new String[]{String.valueOf(ContentUris.parseId(made)), "旅游行程"};
    }

    /** Saves the text as a file and opens the share menu with it. */
    void shareFile(String name, String mime, String text) {
        try {
            File dir = FileShare.dir(this);
            dir.mkdirs();
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            File f = new File(dir, safe);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(text); }
            Uri u = Uri.parse("content://" + FileShare.AUTHORITY + "/" + safe);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(i, "分享日历文件");
            ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(ch);
        } catch (Exception e) {
            Log.w(TAG, "share file", e);
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAL) {
            String j = pendingCal;
            pendingCal = null;
            if (hasCalPerm() && j != null) addToCalendar(j);
            else calDone(false, "没有日历权限，加不进去。可以改用「分享日历文件」。");
            return;
        }
        if (code == REQ_WATCH) {
            if (hasLocPerm()) startWatching(); else sendError("没有定位权限，不能显示你的位置");
            return;
        }
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
        public void grab(String url) {
            ui.post(() -> MainActivity.this.grab(url));
        }

        @JavascriptInterface
        public void watch(boolean on) {
            ui.post(() -> MainActivity.this.watch(on));
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
        public void calendar(String json) {
            ui.post(() -> addToCalendar(json));
        }

        @JavascriptInterface
        public void shareFile(String name, String mime, String text) {
            ui.post(() -> MainActivity.this.shareFile(name, mime, text));
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
        public String shared() {
            String t = shared;
            shared = null;
            return t;
        }

        /** follows a short link (maps.app.goo.gl) to the full Google Maps address, which holds the position */
        @JavascriptInterface
        public void resolveUrl(String url, String tag) {
            bg.execute(() -> {
                String u = url;
                try {
                    for (int i = 0; i < 8; i++) {
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                        int code = c.getResponseCode();
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (code < 300 || code >= 400 || loc == null) break;
                        u = new URL(new URL(u), loc).toString();
                        // a consent page carries the real address in "continue"
                        Uri cu = Uri.parse(u);
                        if (cu.getHost() != null && cu.getHost().startsWith("consent.") && cu.getQueryParameter("continue") != null) {
                            u = cu.getQueryParameter("continue");
                            break;
                        }
                        if (u.contains("/maps/place/") || u.contains("!3d")) break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "resolve " + url, e);
                }
                // links like maps.google.com/?q=Name,+address&ftid=… carry no position: read it from the page
                if (!u.contains("!3d") && !u.matches(".*@-?\\d+\\.\\d+,-?\\d+\\.\\d+.*")) {
                    String at = pageCoords(u);
                    if (at != null) u = u + "#@" + at;
                }
                js("window.onResolved&&window.onResolved(" + JSONObject.quote(tag) + "," + JSONObject.quote(u) + ")");
            });
        }

        /** "lat,lng" of the place a Google Maps page shows (the preview image centre, or the first map view), or null */
        private String pageCoords(String u) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                c.setRequestProperty("Accept-Language", "en");
                c.setRequestProperty("Cookie", "CONSENT=YES+; SOCS=CAI");
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = r.read(buf)) > 0 && sb.length() < 3000000) sb.append(buf, 0, n);
                }
                c.disconnect();
                String h = sb.toString();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("center=(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)").matcher(h);
                if (m.find()) return m.group(1) + "," + m.group(2);
                m = java.util.regex.Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)").matcher(h);
                if (m.find()) return m.group(1) + "," + m.group(2);
            } catch (Exception e) {
                Log.w(TAG, "page coords " + u, e);
            }
            return null;
        }

        @JavascriptInterface
        public void log(String m) {
            Log.i(TAG, m);
        }
    }
}
