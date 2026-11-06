package com.zlc.vdl;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/** Everything that is not UI: link parsing, the yt-dlp engine, download tasks, cookies and saving to the gallery. */
public class Engine {

    public interface Listener {
        void onEngine(String state, String version);
        void onAnalyze(String reqId, JSONObject result);
        void onTask(JSONObject task);
    }

    static final String FOLDER = "万能下载";
    static final String UA_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /** platform key -> {name, home page used for cookies/login, cookie domain, session cookie name} */
    static final String[][] PLATFORMS = {
            {"douyin", "抖音", "https://www.douyin.com/", "douyin.com", "sessionid"},
            {"tiktok", "TikTok", "https://www.tiktok.com/", "tiktok.com", "sessionid"},
            {"xhs", "小红书", "https://www.xiaohongshu.com/explore", "xiaohongshu.com", "web_session"},
            {"x", "X", "https://x.com/login", "x.com", "auth_token"},
            {"youtube", "YouTube", "https://m.youtube.com/", "youtube.com", "LOGIN_INFO"},
            {"ig", "Instagram", "https://www.instagram.com/accounts/login/", "instagram.com", "sessionid"},
            {"bili", "哔哩哔哩", "https://m.bilibili.com/", "bilibili.com", "SESSDATA"},
            {"fb", "Facebook", "https://m.facebook.com/", "facebook.com", "c_user"},
    };

    static volatile Listener listener;
    static volatile String state = "init";
    static volatile String version = "";
    static Context app;
    static final ExecutorService bg = Executors.newCachedThreadPool();
    static final ExecutorService dl = Executors.newFixedThreadPool(2);
    static final Map<String, JSONObject> tasks = Collections.synchronizedMap(new LinkedHashMap<>());
    static final Set<String> canceled = ConcurrentHashMap.newKeySet();
    static final Map<String, Long> lastEmit = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- start-up

    static void init(Context c) {
        app = c.getApplicationContext();
        loadTasks();
        bg.execute(() -> {
            try {
                YoutubeDL.getInstance().init(app);
                FFmpeg.getInstance().init(app);
                version = String.valueOf(YoutubeDL.getInstance().versionName(app));
                setState("ready");
                long last = prefs().getLong("updatedAt", 0);
                if (System.currentTimeMillis() - last > 20L * 3600_000L) update();
            } catch (Throwable e) {
                setState("error: " + e.getMessage());
            }
        });
    }

    static SharedPreferences prefs() {
        return app.getSharedPreferences("vdl", Context.MODE_PRIVATE);
    }

    static void setState(String s) {
        state = s;
        Listener l = listener;
        if (l != null) l.onEngine(state, version);
    }

    /** Platforms change often, so the parsing engine (yt-dlp) is refreshed from GitHub about once a day. */
    static void update() {
        if (state.equals("updating")) return;
        String before = state;
        setState("updating");
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
            version = String.valueOf(YoutubeDL.getInstance().versionName(app));
            prefs().edit().putLong("updatedAt", System.currentTimeMillis()).apply();
            setState("ready");
        } catch (Throwable e) {
            setState(before.startsWith("error") ? before : "ready");
        }
    }

    // ---------------------------------------------------------------- links

    static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s<>\"'，。！？、）)】]+").matcher(text);
        if (!m.find()) return null;
        String u = m.group();
        while (u.endsWith(".") || u.endsWith(",") || u.endsWith(";")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static String platformOf(String url) {
        String h;
        try { h = Uri.parse(url).getHost(); } catch (Exception e) { h = null; }
        if (h == null) return "other";
        h = h.toLowerCase();
        if (h.contains("douyin")) return "douyin";
        if (h.contains("tiktok")) return "tiktok";
        if (h.contains("xiaohongshu") || h.contains("xhslink")) return "xhs";
        if (h.equals("x.com") || h.endsWith(".x.com") || h.contains("twitter") || h.equals("t.co")) return "x";
        if (h.contains("youtube") || h.contains("youtu.be")) return "youtube";
        if (h.contains("instagram")) return "ig";
        if (h.contains("bilibili") || h.contains("b23.tv")) return "bili";
        if (h.contains("facebook") || h.contains("fb.watch")) return "fb";
        return "other";
    }

    static boolean isShortLink(String url) {
        String h = Uri.parse(url).getHost();
        if (h == null) return false;
        h = h.toLowerCase();
        return h.equals("v.douyin.com") || h.equals("xhslink.com") || h.endsWith(".xhslink.com") || h.equals("b23.tv")
                || h.equals("vm.tiktok.com") || h.equals("vt.tiktok.com") || h.equals("t.co") || h.equals("fb.watch")
                || (h.contains("facebook.com") && url.contains("/share/"));
    }

    /** Follows short-link redirects (v.douyin.com, xhslink.com, b23.tv, ...) to the real page address. */
    static String resolve(String url) {
        String cur = url;
        for (int i = 0; i < 6 && isShortLink(cur); i++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", UA_IPHONE);
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) cur = new URL(new URL(cur), loc).toString();
                else break;
            } catch (Exception e) {
                break;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        // Douyin pages come in many shapes; yt-dlp understands www.douyin.com/video/<id>
        if (platformOf(cur).equals("douyin")) {
            Matcher m = Pattern.compile("(?:/video/|/note/|modal_id=|aweme_id=)(\\d{8,})").matcher(cur);
            if (m.find()) cur = "https://www.douyin.com/video/" + m.group(1);
        }
        return cur;
    }

    // ---------------------------------------------------------------- analyze

    static void analyze(String reqId, String text) {
        bg.execute(() -> {
            JSONObject out;
            try {
                out = analyzeNow(text);
            } catch (Throwable e) {
                out = err(null, e.getMessage());
            }
            Listener l = listener;
            if (l != null) l.onAnalyze(reqId, out);
        });
    }

    static JSONObject analyzeNow(String text) throws Exception {
        String url = extractUrl(text);
        if (url == null) return err(null, "NO_LINK");
        if (!state.equals("ready") && !state.equals("updating")) waitReady();
        url = resolve(url);
        String platform = platformOf(url);
        String ytErr;
        try {
            YoutubeDLRequest r = new YoutubeDLRequest(url);
            r.addOption("--dump-single-json");
            r.addOption("--no-playlist");
            r.addOption("--no-warnings");
            addCookies(r);
            YoutubeDLResponse res = YoutubeDL.getInstance().execute(r);
            JSONObject j = new JSONObject(res.getOut());
            return summarize(j, url, platform);
        } catch (Throwable e) {
            ytErr = cleanError(e.getMessage());
        }
        if (platform.equals("douyin")) {
            try {
                JSONObject d = douyinShare(url);
                if (d != null) return d;
            } catch (Throwable ignored) {
            }
        }
        JSONObject e = err(platform, ytErr);
        String low = ytErr == null ? "" : ytErr.toLowerCase();
        boolean cookies = low.contains("cookie") || low.contains("sign in") || low.contains("log in") || low.contains("login")
                || low.contains("412") || low.contains("not a bot") || low.contains("rate-limit") || low.contains("private");
        boolean visitHelps = platform.equals("douyin") || platform.equals("tiktok") || platform.equals("xhs") || platform.equals("youtube") || platform.equals("bili");
        e.put("needCookies", (cookies || visitHelps) && !platform.equals("other"));
        e.put("url", url);
        return e;
    }

    static void waitReady() throws InterruptedException {
        for (int i = 0; i < 120 && !(state.equals("ready") || state.equals("updating")); i++) Thread.sleep(500);
    }

    static JSONObject err(String platform, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("platform", platform == null ? "other" : platform);
            o.put("msg", msg == null ? "" : msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    static String cleanError(String m) {
        if (m == null) return "";
        String last = null;
        for (String line : m.split("\n")) if (line.contains("ERROR")) last = line;
        String s = last != null ? last : m;
        s = s.replaceAll("^.*?ERROR:\\s*", "").replaceAll("\\s*;\\s*please report this issue.*$", "");
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    static JSONObject summarize(JSONObject j, String url, String platform) throws Exception {
        if (j.has("entries") && !j.has("formats")) {
            JSONArray en = j.optJSONArray("entries");
            if (en != null && en.length() > 0) j = en.getJSONObject(0);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("source", "ytdlp");
        o.put("platform", platform);
        o.put("url", j.optString("webpage_url", url));
        o.put("title", j.optString("title", ""));
        o.put("uploader", j.optString("uploader", j.optString("channel", "")));
        o.put("duration", j.optDouble("duration", 0));
        o.put("thumbnail", j.optString("thumbnail", ""));
        TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
        boolean video = false;
        JSONArray fm = j.optJSONArray("formats");
        if (fm != null) for (int i = 0; i < fm.length(); i++) {
            JSONObject f = fm.getJSONObject(i);
            String vc = f.optString("vcodec", "");
            int h = f.optInt("height", 0);
            if (h > 0 && !"none".equals(vc)) { heights.add(h); video = true; }
        } else if (j.optInt("height", 0) > 0) { heights.add(j.optInt("height")); video = true; }
        if (!video && fm != null && fm.length() > 0) video = !"none".equals(j.optString("vcodec", ""));
        o.put("heights", new JSONArray(new ArrayList<>(heights)));
        o.put("video", video || heights.size() > 0);
        return o;
    }

    /** Douyin without cookies: read the public share page that the Douyin app itself opens. */
    static JSONObject douyinShare(String url) throws Exception {
        Matcher m = Pattern.compile("(\\d{8,})").matcher(url);
        if (!m.find()) return null;
        String id = m.group(1);
        String html = httpGetText("https://www.iesdouyin.com/share/video/" + id + "/", "https://www.douyin.com/");
        Matcher r = Pattern.compile("window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\})</script>", Pattern.DOTALL).matcher(html);
        if (!r.find()) return null;
        JSONObject loader = new JSONObject(r.group(1)).optJSONObject("loaderData");
        if (loader == null) return null;
        JSONObject item = null;
        for (Iterator<String> it = loader.keys(); it.hasNext(); ) {
            JSONObject v = loader.optJSONObject(it.next());
            if (v == null) continue;
            JSONObject vi = v.optJSONObject("videoInfoRes");
            if (vi == null) continue;
            JSONArray list = vi.optJSONArray("item_list");
            if (list != null && list.length() > 0) item = list.getJSONObject(0);
        }
        if (item == null) return null;
        JSONObject video = item.getJSONObject("video");
        String play = video.getJSONObject("play_addr").getJSONArray("url_list").getString(0).replace("playwm", "play");
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("source", "douyin");
        o.put("platform", "douyin");
        o.put("url", "https://www.douyin.com/video/" + id);
        o.put("direct", play);
        o.put("title", item.optString("desc", "抖音视频 " + id));
        JSONObject author = item.optJSONObject("author");
        o.put("uploader", author == null ? "" : author.optString("nickname", ""));
        o.put("duration", video.optDouble("duration", 0) / 1000.0);
        JSONObject cover = video.optJSONObject("cover");
        o.put("thumbnail", cover == null ? "" : cover.optJSONArray("url_list").optString(0, ""));
        JSONArray hs = new JSONArray();
        if (video.optInt("height", 0) > 0) hs.put(video.optInt("height"));
        o.put("heights", hs);
        o.put("video", true);
        return o;
    }

    static String httpGetText(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA_IPHONE);
            if (referer != null) c.setRequestProperty("Referer", referer);
            InputStream in = c.getInputStream();
            byte[] b = readAll(in);
            return new String(b, "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- cookies

    static File cookieFile() {
        return new File(app.getFilesDir(), "cookies.txt");
    }

    static void addCookies(YoutubeDLRequest r) {
        File f = cookieFile();
        if (f.exists() && f.length() > 0) r.addOption("--cookies", f.getAbsolutePath());
    }

    /** Copies cookies from the in-app browser into a Netscape cookies.txt that yt-dlp reads. Call on the UI thread. */
    static void writeCookies() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n");
        long exp = System.currentTimeMillis() / 1000 + 365L * 86400;
        for (String[] p : PLATFORMS) {
            for (String host : new String[]{"https://www." + p[3] + "/", "https://" + p[3] + "/", "https://m." + p[3] + "/"}) {
                String all = cm.getCookie(host);
                if (all == null) continue;
                for (String kv : all.split(";\\s*")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String line = "." + p[3] + "\tTRUE\t/\tTRUE\t" + exp + "\t" + kv.substring(0, eq).trim() + "\t" + kv.substring(eq + 1).trim() + "\n";
                    if (sb.indexOf(line) < 0) sb.append(line);
                }
            }
        }
        try (FileOutputStream o = new FileOutputStream(cookieFile())) {
            o.write(sb.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    /** For each platform: "login" (signed in), "visit" (has cookies) or "none". Call on the UI thread. */
    static JSONObject loginStatus() {
        JSONObject o = new JSONObject();
        CookieManager cm = CookieManager.getInstance();
        for (String[] p : PLATFORMS) {
            String c = cm.getCookie("https://www." + p[3] + "/");
            if (c == null) c = cm.getCookie("https://" + p[3] + "/");
            String st = c == null || c.isEmpty() ? "none" : (c.contains(p[4] + "=") ? "login" : "visit");
            try { o.put(p[0], st); } catch (Exception ignored) { }
        }
        return o;
    }

    static void clearCookies(String platform) {
        CookieManager cm = CookieManager.getInstance();
        for (String[] p : PLATFORMS) {
            if (!p[0].equals(platform)) continue;
            for (String host : new String[]{"https://www." + p[3] + "/", "https://" + p[3] + "/", "https://m." + p[3] + "/"}) {
                String all = cm.getCookie(host);
                if (all == null) continue;
                for (String kv : all.split(";\\s*")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String name = kv.substring(0, eq).trim();
                    cm.setCookie(host, name + "=; Max-Age=0");
                    cm.setCookie(host, name + "=; Max-Age=0; Domain=." + p[3] + "; Path=/");
                }
            }
        }
        cm.flush();
        writeCookies();
    }

    static String platformHome(String platform) {
        for (String[] p : PLATFORMS) if (p[0].equals(platform)) return p[2];
        return null;
    }

    static String platformName(String platform) {
        for (String[] p : PLATFORMS) if (p[0].equals(platform)) return p[1];
        return platform;
    }

    // ---------------------------------------------------------------- downloads

    static String download(JSONObject req) {
        String id = "t" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        JSONObject t = new JSONObject();
        try {
            t.put("id", id);
            t.put("url", req.optString("url"));
            t.put("direct", req.optString("direct", ""));
            t.put("title", req.optString("title", ""));
            t.put("thumbnail", req.optString("thumbnail", ""));
            t.put("platform", req.optString("platform", "other"));
            t.put("quality", req.optString("quality", "best"));
            t.put("state", "queued");
            t.put("progress", 0);
            t.put("createdAt", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        tasks.put(id, t);
        emit(t, true);
        DownloadService.refresh(app);
        dl.execute(() -> run(t));
        return id;
    }

    static void run(JSONObject t) {
        String id = t.optString("id");
        if (canceled.remove(id)) { finish(t, "canceled", null); return; }
        File dir = new File(app.getCacheDir(), "dl/" + id);
        deleteDir(dir);
        dir.mkdirs();
        set(t, "state", "running");
        emit(t, true);
        try {
            if (!state.equals("ready") && !state.equals("updating")) waitReady();
            String quality = t.optString("quality", "best");
            boolean audio = quality.equals("audio");
            if (!t.optString("direct").isEmpty() && !audio) {
                directDownload(t, new File(dir, safeName(t.optString("title")) + ".mp4"));
            } else {
                YoutubeDLRequest r = new YoutubeDLRequest(t.optString("url"));
                r.addOption("--no-playlist");
                r.addOption("--no-mtime");
                r.addOption("--no-warnings");
                r.addOption("--concurrent-fragments", 4);
                r.addOption("-o", dir.getAbsolutePath() + "/%(title).70B [%(id)s].%(ext)s");
                addCookies(r);
                if (audio) {
                    r.addOption("-f", "ba/b");
                    r.addOption("-x");
                    r.addOption("--audio-format", "mp3");
                } else {
                    if (quality.equals("best")) r.addOption("-f", "bv*+ba/b");
                    else {
                        String h = quality.replaceAll("\\D", "");
                        r.addOption("-f", "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/bv*+ba/b");
                    }
                    r.addOption("-S", "res,vcodec:h264,acodec:m4a");
                    r.addOption("--merge-output-format", "mp4");
                }
                Function3<Float, Long, String, Unit> cb = (progress, eta, line) -> {
                    set(t, "progress", Math.max(0, Math.min(100, progress)));
                    set(t, "eta", eta);
                    emit(t, false);
                    return Unit.INSTANCE;
                };
                YoutubeDL.getInstance().execute(r, id, cb);
            }
            if (canceled.remove(id)) { deleteDir(dir); finish(t, "canceled", null); return; }
            File out = pickOutput(dir);
            if (out == null) throw new Exception("NO_FILE");
            set(t, "state", "saving");
            set(t, "progress", 100);
            emit(t, true);
            String mime = mimeOf(out.getName(), audio);
            Uri uri = saveToGallery(out, mime, audio);
            set(t, "uri", uri == null ? "" : uri.toString());
            set(t, "mime", mime);
            set(t, "size", out.length());
            set(t, "file", out.getName());
            deleteDir(dir);
            finish(t, "done", null);
            notifyDone(t);
        } catch (YoutubeDL.CanceledException e) {
            deleteDir(dir);
            finish(t, "canceled", null);
        } catch (Throwable e) {
            deleteDir(dir);
            if (canceled.remove(id)) finish(t, "canceled", null);
            else finish(t, "failed", cleanError(e.getMessage()));
        }
    }

    static void directDownload(JSONObject t, File out) throws Exception {
        String id = t.optString("id");
        HttpURLConnection c = (HttpURLConnection) new URL(t.optString("direct")).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA_IPHONE);
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            long total = c.getContentLengthLong();
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (canceled.contains(id)) throw new YoutubeDL.CanceledException();
                    o.write(buf, 0, n);
                    done += n;
                    if (total > 0) { set(t, "progress", done * 100.0 / total); emit(t, false); }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    static File pickOutput(File dir) {
        File best = null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") || n.contains(".part-Frag") || f.isDirectory()) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    static String mimeOf(String name, boolean audio) {
        String n = name.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".webm")) return audio ? "audio/webm" : "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        return audio ? "audio/mpeg" : "video/mp4";
    }

    static String safeName(String s) {
        s = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").trim();
        if (s.isEmpty()) s = "video";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    /** Puts the finished file in the phone's gallery: Movies/万能下载 (or Music/万能下载 for audio). */
    static Uri saveToGallery(File f, String mime, boolean audio) throws Exception {
        ContentResolver cr = app.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri col = audio ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = cr.insert(col, v);
            if (item == null) throw new Exception("SAVE_FAILED");
            try (InputStream in = new FileInputStream(f); OutputStream o = cr.openOutputStream(item)) {
                copy(in, o);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, v, null, null);
            return item;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), FOLDER);
        dir.mkdirs();
        File dest = new File(dir, f.getName());
        try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(dest)) {
            copy(in, o);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Uri> ref = new AtomicReference<>();
        MediaScannerConnection.scanFile(app, new String[]{dest.getAbsolutePath()}, new String[]{mime}, (path, uri) -> {
            ref.set(uri);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    static void copy(InputStream in, OutputStream o) throws Exception {
        if (o == null) throw new Exception("SAVE_FAILED");
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }

    static void cancel(String id) {
        JSONObject t = tasks.get(id);
        if (t == null) return;
        String st = t.optString("state");
        if (!st.equals("queued") && !st.equals("running")) return;
        canceled.add(id);
        try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Throwable ignored) { }
        if (st.equals("queued")) { /* run() will notice */ }
    }

    static void remove(String id) {
        cancel(id);
        tasks.remove(id);
        saveTasks();
    }

    static void set(JSONObject t, String k, Object v) {
        try { synchronized (t) { t.put(k, v); } } catch (Exception ignored) { }
    }

    static void finish(JSONObject t, String st, String error) {
        set(t, "state", st);
        if (error != null) set(t, "error", error);
        emit(t, true);
        saveTasks();
        DownloadService.refresh(app);
    }

    static void emit(JSONObject t, boolean force) {
        String id = t.optString("id");
        long now = System.currentTimeMillis();
        Long last = lastEmit.get(id);
        if (!force && last != null && now - last < 350) return;
        lastEmit.put(id, now);
        Listener l = listener;
        if (l != null) {
            JSONObject copy;
            synchronized (t) {
                try { copy = new JSONObject(t.toString()); } catch (Exception e) { copy = t; }
            }
            l.onTask(copy);
        }
        DownloadService.progress(app);
    }

    static JSONArray taskList() {
        JSONArray a = new JSONArray();
        synchronized (tasks) {
            List<JSONObject> l = new ArrayList<>(tasks.values());
            for (int i = l.size() - 1; i >= 0; i--) a.put(l.get(i));
        }
        return a;
    }

    static int activeCount() {
        int n = 0;
        synchronized (tasks) {
            for (JSONObject t : tasks.values()) {
                String s = t.optString("state");
                if (s.equals("queued") || s.equals("running") || s.equals("saving")) n++;
            }
        }
        return n;
    }

    static double activeProgress() {
        double sum = 0;
        int n = 0;
        synchronized (tasks) {
            for (JSONObject t : tasks.values()) {
                String s = t.optString("state");
                if (s.equals("queued") || s.equals("running") || s.equals("saving")) { sum += t.optDouble("progress", 0); n++; }
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    static void saveTasks() {
        JSONArray a = new JSONArray();
        synchronized (tasks) {
            int skip = Math.max(0, tasks.size() - 100);
            int i = 0;
            for (JSONObject t : tasks.values()) {
                if (i++ < skip) continue;
                String s = t.optString("state");
                if (s.equals("done") || s.equals("failed") || s.equals("canceled")) a.put(t);
            }
        }
        prefs().edit().putString("tasks", a.toString()).apply();
    }

    static void loadTasks() {
        try {
            JSONArray a = new JSONArray(prefs().getString("tasks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                tasks.put(t.optString("id"), t);
            }
        } catch (Exception ignored) {
        }
    }

    static JSONObject task(String id) {
        return tasks.get(id);
    }

    static void notifyDone(JSONObject t) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, App.CH_DONE) : new Notification.Builder(app);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("下载完成")
                    .setContentText(t.optString("title"))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(2000 + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Throwable ignored) {
        }
    }
}
