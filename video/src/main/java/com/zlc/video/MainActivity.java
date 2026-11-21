package com.zlc.video;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * AI成片: the whole editor and renderer live in assets/index.html. This activity only adds
 * what a web page cannot do on its own:
 *  - free Edge neural voices (falls back to the phone's text-to-speech if Edge fails)
 *  - calling the AI script model without browser CORS limits
 *  - saving the finished MP4 into the gallery and sharing it
 *
 * Page -> native: Android.call(id, method, argsJson). Native -> page: window.__nativeCb(id, ok, payload).
 */
public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private OkHttpClient http;
    private EdgeTts edge;

    private TextToSpeech sysTts;
    private volatile boolean sysReady;
    private final Map<String, CountDownLatch> sysJobs = new ConcurrentHashMap<>();

    // The video being written into the gallery, one at a time.
    private OutputStream saveOut;
    private Uri saveUri;
    private File saveFile;
    private Uri lastSaved;
    private String lastMime = "video/mp4";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        edge = new EdgeTts(http);
        sysTts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            sysTts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            sysTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { finish(id); }
                @Override public void onError(String id) { finish(id); }
                private void finish(String id) {
                    CountDownLatch l = sysJobs.get(id);
                    if (l != null) l.countDown();
                }
            });
            sysReady = true;
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.d("AIVideo", m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (sysTts != null) sysTts.shutdown();
        net.shutdownNow();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private void reply(String id, boolean ok, String payload) {
        final String js = "window.__nativeCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload == null ? "" : payload) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static String errText(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ---------- AI script ----------
    private String httpPost(String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (key != null && !key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        JSONObject r = new JSONObject();
        r.put("status", code);
        r.put("body", text);
        return r.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    // ---------- voices ----------
    private String speak(JSONObject a) throws Exception {
        String text = a.getString("text");
        String voice = a.optString("voice", "zh-CN-XiaoxiaoNeural");
        int rate = a.optInt("rate", 0);
        boolean allowSystem = a.optBoolean("allowSystem", true);
        JSONObject r = new JSONObject();
        String edgeError = null;
        if (!"system".equals(voice)) {
            try {
                String rs = (rate >= 0 ? "+" : "") + rate + "%";
                byte[] mp3 = edge.synthesize(text, voice, rs, "+0Hz", 30);
                r.put("engine", "edge");
                r.put("mime", "audio/mpeg");
                r.put("b64", Base64.encodeToString(mp3, Base64.NO_WRAP));
                return r.toString();
            } catch (Exception e) {
                edgeError = errText(e);
                if (!allowSystem) throw e;
            }
        }
        byte[] wav = systemSpeak(text, rate);
        r.put("engine", "system");
        r.put("mime", "audio/wav");
        r.put("b64", Base64.encodeToString(wav, Base64.NO_WRAP));
        if (edgeError != null) r.put("edgeError", edgeError);
        return r.toString();
    }

    private byte[] systemSpeak(String text, int rate) throws Exception {
        for (int i = 0; i < 50 && !sysReady; i++) Thread.sleep(100);
        if (!sysReady) throw new Exception("手机没有可用的系统语音");
        String id = "u" + System.nanoTime();
        File f = new File(getCacheDir(), id + ".wav");
        CountDownLatch latch = new CountDownLatch(1);
        sysJobs.put(id, latch);
        try {
            synchronized (this) {
                sysTts.setSpeechRate(Math.max(0.5f, 1f + rate / 100f));
                Bundle p = new Bundle();
                p.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                int res = sysTts.synthesizeToFile(text, p, f, id);
                if (res != TextToSpeech.SUCCESS) throw new Exception("系统语音合成失败");
            }
            if (!latch.await(60, TimeUnit.SECONDS)) throw new Exception("系统语音超时");
            if (!f.exists() || f.length() < 100) throw new Exception("系统语音没有生成声音，请在手机设置里安装中文语音包");
            try (FileInputStream in = new FileInputStream(f)) {
                return readAll(in);
            }
        } finally {
            sysJobs.remove(id);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---------- saving ----------
    private String saveBegin(JSONObject a) throws Exception {
        closeQuietly();
        String name = a.optString("name", "AI成片_" + System.currentTimeMillis() + ".mp4");
        lastMime = a.optString("mime", "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, lastMime);
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AI成片");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            saveUri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (saveUri == null) throw new Exception("无法在相册创建文件");
            saveOut = cr.openOutputStream(saveUri);
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7));
                throw new Exception("需要存储权限，请允许后再点一次保存");
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AI成片");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            saveFile = new File(dir, name);
            saveOut = new FileOutputStream(saveFile);
        }
        return "ok";
    }

    private String saveChunk(JSONObject a) throws Exception {
        if (saveOut == null) throw new Exception("保存还没开始");
        saveOut.write(Base64.decode(a.getString("b64"), Base64.NO_WRAP));
        return "ok";
    }

    private String saveEnd() throws Exception {
        if (saveOut == null) throw new Exception("保存还没开始");
        saveOut.close();
        saveOut = null;
        if (saveUri != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(saveUri, v, null, null);
            lastSaved = saveUri;
            saveUri = null;
        } else if (saveFile != null) {
            final CountDownLatch scanned = new CountDownLatch(1);
            MediaScannerConnection.scanFile(this, new String[]{saveFile.getAbsolutePath()}, new String[]{lastMime},
                    (path, uri) -> { lastSaved = uri; scanned.countDown(); });
            scanned.await(10, TimeUnit.SECONDS);
            saveFile = null;
        }
        return lastSaved == null ? "" : lastSaved.toString();
    }

    private void closeQuietly() {
        try {
            if (saveOut != null) saveOut.close();
        } catch (Exception ignored) {
        }
        saveOut = null;
        if (saveUri != null) {
            try {
                getContentResolver().delete(saveUri, null, null);
            } catch (Exception ignored) {
            }
            saveUri = null;
        }
    }

    private String share() throws Exception {
        if (lastSaved == null) throw new Exception("请先保存到相册");
        final Uri uri = lastSaved;
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(lastMime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享视频"));
        });
        return "ok";
    }

    class Bridge {
        @JavascriptInterface
        public void call(final String id, final String method, final String argsJson) {
            final boolean ordered = method.startsWith("save");
            (ordered ? io : net).execute(() -> {
                try {
                    JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
                    String out;
                    switch (method) {
                        case "post": out = httpPost(a.getString("url"), a.optString("key"), a.getString("body")); break;
                        case "tts": out = speak(a); break;
                        case "saveBegin": out = saveBegin(a); break;
                        case "saveChunk": out = saveChunk(a); break;
                        case "saveEnd": out = saveEnd(); break;
                        case "saveAbort": closeQuietly(); out = "ok"; break;
                        case "share": out = share(); break;
                        default: throw new Exception("unknown method " + method);
                    }
                    reply(id, true, out);
                } catch (Throwable t) {
                    reply(id, false, errText(t));
                }
            });
        }

        @JavascriptInterface
        public void keepScreenOn(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }
    }
}
