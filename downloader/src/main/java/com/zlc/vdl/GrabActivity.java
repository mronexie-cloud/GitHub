package com.zlc.vdl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Douyin only gives its videos to a real browser (the page first runs a JavaScript check, and the
 * data API needs signed requests). So we open the video's phone web page in an in-app browser, let
 * Douyin's own page do that work, and read the video from it: the video info the page carries
 * (window._ROUTER_DATA) or else the address its player loads. The page stays covered while this
 * happens and is only shown if Douyin asks the person to verify.
 */
public class GrabActivity extends Activity {
    static final String UA_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    static final long STEP_MS = 25000, TOTAL_MS = 100000;

    static final String POLL_JS = "(function(){var o={srcs:[],title:document.title||'',ready:document.readyState};"
            + "try{document.querySelectorAll('video,video source').forEach(function(e){var s=e.currentSrc||e.src||'';if(s&&o.srcs.indexOf(s)<0)o.srcs.push(s);});}catch(e){}"
            + "try{var m=document.querySelector('meta[property=\"og:image\"]');if(m)o.poster=m.content;var v=document.querySelector('video');if(v&&v.poster&&!o.poster)o.poster=v.poster;}catch(e){}"
            + "try{var t=document.body?document.body.innerText.slice(0,4000):'';o.verify=!!document.querySelector('#captcha_container,.captcha_verify_container,iframe[src*=captcha],iframe[src*=verify]')||/请完成.{0,6}验证|拖动.{0,4}滑块|验证码|安全验证/.test(t);}catch(e){}"
            + "try{if(window._ROUTER_DATA)o.router=JSON.stringify(window._ROUTER_DATA);}catch(e){}"
            + "return JSON.stringify(o);})()";

    private WebView web;
    private TextView hint;
    private View cover;
    private String reqId, id, url;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> media = Collections.synchronizedList(new ArrayList<>());
    private long started, stepStarted;
    private int step = -1;
    private boolean finished;
    private String[] steps;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reqId = getIntent().getStringExtra("reqId");
        id = getIntent().getStringExtra("id");
        url = getIntent().getStringExtra("url");
        if (id == null) id = "";
        if (id.isEmpty() && url == null) { finish(); return; }
        // www.douyin.com sends a phone to m.douyin.com/share/video/<id>; the second entry is a plain retry of that page
        steps = id.isEmpty() ? new String[]{url, url}
                : new String[]{"https://www.douyin.com/video/" + id, "https://m.douyin.com/share/video/" + id};
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101426"));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1D2B6B"));
        bar.setPadding((int) (16 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(Engine.ZH ? "抖音 · 读取视频" : "Douyin · reading the video");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        hint = new TextView(this);
        hint.setText(Engine.ZH ? "通常几秒钟就好" : "Usually takes a few seconds");
        hint.setTextColor(Color.parseColor("#C9D2FF"));
        hint.setTextSize(12);
        texts.addView(hint);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button cancel = new Button(this);
        cancel.setText(Engine.ZH ? "取消" : "Cancel");
        cancel.setOnClickListener(v -> fail(Engine.ZH ? "已取消" : "Canceled", false));
        bar.addView(cancel);
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(UA_ANDROID); // a normal phone browser, not an app's web view
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.contains("/aweme/v1/play") && !media.contains(u)) media.add(u);
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String sch = req.getUrl().getScheme();
                return sch == null || !sch.startsWith("http"); // ignore "open in the Douyin app" links
            }
        });

        FrameLayout frame = new FrameLayout(this);
        frame.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout cv = new LinearLayout(this);
        cv.setOrientation(LinearLayout.VERTICAL);
        cv.setGravity(Gravity.CENTER);
        cv.setBackgroundColor(Color.parseColor("#101426"));
        cv.setClickable(true);
        cv.addView(new ProgressBar(this));
        TextView ct = new TextView(this);
        ct.setText(Engine.ZH ? "正在通过抖音网页读取视频…" : "Reading the video from Douyin's web page…");
        ct.setTextColor(Color.WHITE);
        ct.setTextSize(15);
        ct.setGravity(Gravity.CENTER);
        ct.setPadding((int) (24 * d), (int) (16 * d), (int) (24 * d), (int) (12 * d));
        cv.addView(ct);
        Button show = new Button(this);
        show.setText(Engine.ZH ? "显示网页" : "Show page");
        show.setOnClickListener(v -> cv.setVisibility(View.GONE));
        cv.addView(show);
        cover = cv;
        frame.addView(cv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        started = System.currentTimeMillis();
        next();
        ui.postDelayed(this::poll, 1500);
    }

    private void next() {
        step++;
        if (step >= steps.length) { fail(null, true); return; }
        stepStarted = System.currentTimeMillis();
        web.loadUrl(steps[step]);
    }

    private void poll() {
        if (finished || isFinishing()) return;
        web.evaluateJavascript(POLL_JS, v -> {
            if (finished) return;
            try {
                JSONObject o = new JSONObject(new JSONObject("{\"v\":" + v + "}").getString("v"));
                JSONObject router = o.optString("router").isEmpty() ? null : new JSONObject(o.optString("router"));
                // 1. the video info the page carries
                JSONObject a = router == null ? null : findAweme(router, 0);
                if (a != null) {
                    JSONArray imgs = a.optJSONArray("images");
                    if (imgs != null && imgs.length() > 0) {
                        fail(Engine.ZH ? "这是图文作品，目前只支持下载视频" : "This is a photo post; only videos are supported", false);
                        return;
                    }
                    JSONObject r = fromAweme(a, o);
                    if (r != null) { succeed(r); return; }
                }
                // the page says the video is gone (deleted, private, region-blocked)
                if (router != null && "complete".equals(o.optString("ready"))) {
                    String why = filtered(router, 0);
                    if (why != null && System.currentTimeMillis() - stepStarted > 4000 && o.optJSONArray("srcs").length() == 0) {
                        fail((Engine.ZH ? "抖音没有提供这个作品" : "Douyin does not offer this video") + (why.isEmpty() ? "" : "：" + why)
                                + (Engine.ZH ? "（可能已删除、仅作者可见或地区受限）" : " (deleted, private or region-limited)"), false);
                        return;
                    }
                }
                // 2. what the player loads
                String src = null;
                JSONArray srcs = o.optJSONArray("srcs");
                if (srcs != null) for (int i = 0; i < srcs.length() && src == null; i++) {
                    String x = fix(srcs.optString(i));
                    if (x.startsWith("http")) src = x;
                }
                if (src == null) synchronized (media) { if (!media.isEmpty()) src = media.get(0); }
                if (src != null) { succeed(fromPage(src, o)); return; }

                long now = System.currentTimeMillis();
                if (o.optBoolean("verify")) {
                    cover.setVisibility(View.GONE);
                    hint.setText(Engine.ZH ? "抖音要求验证：请在下方完成，完成后会自动继续" : "Douyin asks you to verify: complete it below, then it continues");
                    if (now - started > TOTAL_MS * 3) { fail(null, true); return; }
                } else {
                    if (now - started > TOTAL_MS) { fail(null, true); return; }
                    if (now - stepStarted > STEP_MS) next();
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this::poll, 800);
        });
    }

    /** Finds this video's info object anywhere in the page data. */
    private JSONObject findAweme(Object node, int depth) {
        if (depth > 14 || node == null) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject v = o.optJSONObject("video");
            String aid = o.optString("aweme_id", o.optString("awemeId", ""));
            boolean ours = id.isEmpty() ? !aid.isEmpty() : id.equals(aid);
            if (v != null && v.optJSONObject("play_addr") != null && (ours || aid.isEmpty() && o.has("desc"))) return o;
            if (o.optJSONArray("images") != null && ours) return o;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                JSONObject r = findAweme(o.opt(it.next()), depth + 1);
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = findAweme(a.opt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** If the page data says the video was filtered out, returns the reason ("" if none given), else null. */
    private String filtered(Object node, int depth) {
        if (depth > 10 || node == null) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray items = o.optJSONArray("item_list");
            JSONArray fl = o.optJSONArray("filter_list");
            if (items != null && items.length() == 0 && fl != null && fl.length() > 0) {
                JSONObject f = fl.optJSONObject(0);
                return f == null ? "" : f.optString("notice", f.optString("filter_reason", ""));
            }
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String r = filtered(o.opt(it.next()), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String fix(String u) {
        if (u == null) return "";
        return u.startsWith("//") ? "https:" + u : u;
    }

    static final java.util.regex.Pattern VIDEO_ID = java.util.regex.Pattern.compile("[?&]video_id=([^&]+)");

    static String videoUri(String u) {
        java.util.regex.Matcher m = VIDEO_ID.matcher(u == null ? "" : u);
        return m.find() ? m.group(1) : "";
    }

    /** Builds the result from the page's video info. The page's own addresses (with watermark) are kept as fallbacks. */
    private JSONObject fromAweme(JSONObject a, JSONObject page) {
        try {
            JSONObject v = a.optJSONObject("video");
            List<String> urls = new ArrayList<>();
            JSONObject pa = v.optJSONObject("play_addr");
            JSONArray l = pa == null ? null : pa.optJSONArray("url_list");
            if (l != null) for (int i = 0; i < l.length(); i++) urls.add(fix(l.optString(i)));
            JSONArray srcs = page.optJSONArray("srcs");
            if (srcs != null) for (int i = 0; i < srcs.length(); i++) urls.add(fix(srcs.optString(i)));
            synchronized (media) { urls.addAll(media); }
            JSONObject o = base(urls);
            if (o == null) return null;
            String uri = pa == null ? "" : pa.optString("uri", "");
            if (uri.isEmpty()) for (String u : urls) if (!(uri = videoUri(u)).isEmpty()) break;
            o.put("uri", uri);
            String desc = a.optString("desc", "").trim();
            o.put("title", desc.isEmpty() ? cleanTitle(page.optString("title")) : desc);
            JSONObject au = a.optJSONObject("author");
            o.put("uploader", au == null ? "" : au.optString("nickname", ""));
            double dur = v.optDouble("duration", 0);
            o.put("duration", dur > 1000 ? dur / 1000.0 : dur);
            String cover = null;
            for (String k : new String[]{"cover", "origin_cover", "dynamic_cover"}) {
                JSONObject c = v.optJSONObject(k);
                JSONArray cl = c == null ? null : c.optJSONArray("url_list");
                if (cl != null && cl.length() > 0) { cover = fix(cl.optString(0)); break; }
            }
            o.put("thumbnail", cover != null ? cover : page.optString("poster", ""));
            o.put("heights", new JSONArray());
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    private JSONObject fromPage(String src, JSONObject page) {
        try {
            List<String> urls = new ArrayList<>();
            urls.add(src);
            synchronized (media) { urls.addAll(media); }
            JSONObject o = base(urls);
            if (o == null) return null;
            o.put("uri", videoUri(src));
            String t = cleanTitle(page.optString("title"));
            o.put("title", t.isEmpty() ? ("抖音视频 " + id).trim() : t);
            o.put("uploader", "");
            o.put("duration", 0);
            o.put("thumbnail", page.optString("poster", ""));
            o.put("heights", new JSONArray());
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String cleanTitle(String t) {
        if (t == null) return "";
        t = t.replaceAll("\\s*[-–|]\\s*抖音\\s*$", "").trim();
        return t.startsWith("在抖音记录美好生活") ? "" : t;
    }

    /** The first address is downloaded; the others are fallbacks. */
    private JSONObject base(List<String> urls) throws Exception {
        JSONArray uniq = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (String u : urls) {
            if (u == null || !u.startsWith("http") || seen.contains(u)) continue;
            seen.add(u);
            uniq.put(u);
        }
        if (uniq.length() == 0) return null;
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("source", "web");
        o.put("platform", "douyin");
        o.put("url", !id.isEmpty() ? "https://www.douyin.com/video/" + id : url);
        o.put("direct", uniq.optString(0));
        o.put("directs", uniq);
        o.put("ua", UA_ANDROID);
        o.put("referer", "https://www.douyin.com/");
        o.put("video", true);
        return o;
    }

    private void succeed(JSONObject result) {
        if (finished || result == null) return;
        finished = true;
        Engine.bg.execute(() -> {
            noWatermark(result);
            ui.post(() -> {
                Engine.Listener l = Engine.listener;
                if (l != null) l.onAnalyze(reqId, result);
                finish();
            });
        });
    }

    /**
     * The page plays a copy with the Douyin watermark ("playwm"). The same video id also opens the clean
     * original through Douyin's "play" address. It comes in a couple of sizes, and "1080p" is not always
     * the sharper one, so we read each candidate's picture size from the start of the file and put the
     * sharpest clean copy first, keeping the page's own addresses as fallbacks.
     */
    static void noWatermark(JSONObject result) {
        try {
            String uri = result.optString("uri", "");
            if (uri.isEmpty()) return;
            List<long[]> info = new ArrayList<>();
            List<String> clean = new ArrayList<>();
            for (String host : new String[]{"https://aweme.snssdk.com", "https://www.iesdouyin.com"}) {
                for (String ratio : new String[]{"1080p", "720p"}) {
                    String u = host + "/aweme/v1/play/?video_id=" + uri + "&ratio=" + ratio + "&line=0";
                    long[] m = Engine.mp4Info(u, UA_ANDROID, "https://www.douyin.com/");
                    android.util.Log.i("VDL", "clean " + ratio + " " + host + " -> " + (m == null ? "none" : m[0] + "x" + m[1] + " " + m[2] + "B"));
                    if (m == null) continue;
                    int at = 0;
                    while (at < info.size() && better(info.get(at), m)) at++;
                    info.add(at, m);
                    clean.add(at, u);
                }
                if (!clean.isEmpty()) break; // the second host is only a backup
            }
            if (clean.isEmpty()) return;
            JSONArray all = new JSONArray();
            for (String u : clean) all.put(u);
            JSONArray old = result.optJSONArray("directs");
            if (old != null) for (int i = 0; i < old.length(); i++) all.put(old.optString(i));
            result.put("directs", all);
            result.put("direct", clean.get(0));
            result.put("nowm", true);
            long[] best = info.get(0);
            long shortSide = Math.min(best[0], best[1]);
            if (shortSide > 0) result.put("heights", new JSONArray().put(shortSide));
        } catch (Throwable e) {
            android.util.Log.w("VDL", "no-watermark lookup failed", e);
        }
    }

    /** True if a is at least as good as b: more pixels first, then the bigger file. */
    private static boolean better(long[] a, long[] b) {
        long pa = a[0] * a[1], pb = b[0] * b[1];
        return pa != pb ? pa > pb : a[2] >= b[2];
    }

    private void fail(String msg, boolean suggestLogin) {
        if (finished) return;
        finished = true;
        JSONObject e = Engine.err("douyin", msg != null ? msg : (Engine.ZH
                ? "抖音网页没有给出视频。请稍后重试；如果一直这样，可以在设置里打开抖音并登录后再试。"
                : "Douyin's page did not give the video. Retry later, or sign in to Douyin in Settings and try again."));
        try {
            e.put("needCookies", suggestLogin);
            e.put("url", url);
        } catch (Exception ignored) {
        }
        Engine.Listener l = Engine.listener;
        if (l != null) l.onAnalyze(reqId, e);
        finish();
    }

    @Override
    public void onBackPressed() {
        fail(Engine.ZH ? "已取消" : "Canceled", false);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
