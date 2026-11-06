package com.zlc.music;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app screen (a web page in assets/index.html) plus everything it needs from the phone. */
public class MainActivity extends Activity {
    static final int REQ_PERM = 1, REQ_LRC = 2, REQ_TREE = 3;
    private WebView web;
    private boolean pageReady;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(2);
    private ListenableFuture<MediaController> future;
    private MediaController ctl;
    private SharedPreferences prefs;
    private String importFor;
    private Uri pendingView;
    /** for the automatic test: "play", "song:<title>", "tab:<name>" */
    private volatile String demo;
    private long lastSave;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (ctl != null && ctl.isPlaying()) {
                pushState(false);
                ui.postDelayed(this, 500);
            }
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("music", MODE_PRIVATE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setBackgroundColor(dark() ? 0xFF16130F : 0xFFFAF7F4);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushState(true);
            }

            /** Album covers: the page asks for https://local.art/<song id>?s=<pixels>. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"local.art".equals(u.getHost())) return null;
                int size = 160;
                try { size = Integer.parseInt(u.getQueryParameter("s")); } catch (Exception ignored) { }
                byte[] b = art(u.getLastPathSegment(), Math.max(64, Math.min(size, 1000)));
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "max-age=86400");
                h.put("Access-Control-Allow-Origin", "*");
                if (b == null) return new WebResourceResponse("image/png", null, 404, "No cover", h, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
        });
        web.addJavascriptInterface(new Bridge(), "A");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        connect();
        demo = getIntent().getStringExtra("demo");
        handleIntent(getIntent());
        String tree = prefs.getString("lrcTree", null);
        if (tree != null) bg.execute(() -> Lyrics.indexFolder(this, Uri.parse(tree)));
    }

    // ---------- player connection

    private void connect() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        future = new MediaController.Builder(this, token).buildAsync();
        future.addListener(() -> {
            try {
                ctl = future.get();
                ctl.addListener(new Player.Listener() {
                    @Override
                    public void onEvents(Player player, Player.Events events) {
                        pushState(events.contains(Player.EVENT_TIMELINE_CHANGED));
                        if (player.isPlaying()) { ui.removeCallbacks(tick); ui.post(tick); }
                    }
                });
                pushState(true);
                playPendingView();
            } catch (Exception ignored) {
            }
        }, ui::post);
    }

    private static String idOf(MediaItem m) {
        return m == null ? "" : Library.idOfUri(m.mediaId);
    }

    /** Sends the player's state to the page; with the queue when it changed. */
    private void pushState(boolean withQueue) {
        if (!pageReady || ctl == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("playing", ctl.isPlaying());
            o.put("want", ctl.getPlayWhenReady());
            o.put("buffering", ctl.getPlaybackState() == Player.STATE_BUFFERING);
            o.put("index", ctl.getCurrentMediaItemIndex());
            MediaItem cur = ctl.getCurrentMediaItem();
            o.put("id", idOf(cur));
            if (cur != null) {
                o.put("title", cur.mediaMetadata.title == null ? "" : cur.mediaMetadata.title.toString());
                o.put("artist", cur.mediaMetadata.artist == null ? "" : cur.mediaMetadata.artist.toString());
            }
            o.put("pos", Math.max(0, ctl.getCurrentPosition()));
            long d = ctl.getDuration();
            o.put("dur", d == C.TIME_UNSET ? 0 : d);
            o.put("repeat", ctl.getRepeatMode());
            o.put("shuffle", ctl.getShuffleModeEnabled());
            o.put("count", ctl.getMediaItemCount());
            o.put("sleep", PlaybackService.sleepLeft());
            if (withQueue) {
                JSONArray q = new JSONArray();
                for (int i = 0; i < ctl.getMediaItemCount(); i++) q.put(idOf(ctl.getMediaItemAt(i)));
                o.put("queue", q);
            }
            js("window.onState&&onState(" + o + ")");
            long now = System.currentTimeMillis();
            if (ctl.getMediaItemCount() > 0 && (withQueue || !ctl.isPlaying() || now - lastSave > 5000)) {
                lastSave = now;
                SharedPreferences.Editor e = prefs.edit();
                if (withQueue) {
                    StringBuilder ids = new StringBuilder();
                    for (int i = 0; i < ctl.getMediaItemCount() && i < 5000; i++) {
                        String id = idOf(ctl.getMediaItemAt(i));
                        if (id.startsWith("x:")) continue;
                        if (ids.length() > 0) ids.append(',');
                        ids.append(id);
                    }
                    e.putString("q", ids.toString());
                }
                e.putString("qid", idOf(cur)).putLong("qp", Math.max(0, ctl.getCurrentPosition())).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private MediaItem item(JSONObject s) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(s.optString("title"))
                .setArtist(s.optString("artist").isEmpty() ? null : s.optString("artist"))
                .setAlbumTitle(s.optString("album").isEmpty() ? null : s.optString("album"));
        if (s.has("albumId")) md.setArtworkUri(Uri.parse("content://media/external/audio/albumart/" + s.optLong("albumId")));
        String uri = s.optString("uri");
        return new MediaItem.Builder().setMediaId(uri).setUri(uri).setMediaMetadata(md.build()).build();
    }

    private List<MediaItem> items(JSONArray ids) {
        List<MediaItem> list = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            JSONObject s = Library.songs.get(ids.optString(i));
            if (s != null) list.add(item(s));
        }
        return list;
    }

    private int indexOf(String id) {
        if (ctl == null) return -1;
        for (int i = 0; i < ctl.getMediaItemCount(); i++) if (idOf(ctl.getMediaItemAt(i)).equals(id)) return i;
        return -1;
    }

    // ---------- opening an audio file from a file manager ("Open with 音乐盒")

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getStringExtra("demo") != null) {
            demo = intent.getStringExtra("demo");
            js("window.runDemo&&runDemo()");
        }
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            pendingView = i.getData();
            playPendingView();
        }
    }

    private void playPendingView() {
        if (pendingView == null || ctl == null) return;
        Uri u = pendingView;
        pendingView = null;
        String name = u.getLastPathSegment();
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name != null && name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
        MediaItem m = new MediaItem.Builder().setMediaId(u.toString()).setUri(u)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(name).build()).build();
        ctl.setMediaItem(m);
        ctl.prepare();
        ctl.play();
        js("window.openPlayer&&openPlayer()");
    }

    // ---------- album covers

    private byte[] art(String id, int size) {
        if (id == null) return null;
        File dir = new File(getCacheDir(), "art");
        dir.mkdirs();
        File f = new File(dir, Lyrics.safe(id) + "_" + size + ".jpg");
        File none = new File(dir, Lyrics.safe(id) + ".none");
        try {
            if (f.exists()) return Files.readAllBytes(f.toPath());
            if (none.exists()) return null;
            JSONObject s = Library.songs.get(id);
            if (s == null) return null;
            Bitmap bm = null;
            Uri uri = Uri.parse(s.optString("uri"));
            if (id.startsWith("m") && Build.VERSION.SDK_INT >= 29) {
                try { bm = getContentResolver().loadThumbnail(uri, new Size(size, size), null); } catch (Exception ignored) { }
            }
            if (bm == null) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if (id.startsWith("m")) r.setDataSource(this, uri);
                    else r.setDataSource(s.optString("path"));
                    byte[] pic = r.getEmbeddedPicture();
                    if (pic != null) bm = decode(pic, size);
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
            }
            if (bm == null && s.has("albumId") && Build.VERSION.SDK_INT < 29) {
                try (InputStream in = getContentResolver().openInputStream(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), s.optLong("albumId")))) {
                    if (in != null) bm = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) {
                }
            }
            if (bm == null) {
                new FileOutputStream(none).close();
                return null;
            }
            if (bm.getWidth() > size * 1.3 || bm.getHeight() > size * 1.3) {
                float k = (float) size / Math.max(bm.getWidth(), bm.getHeight());
                bm = Bitmap.createScaledBitmap(bm, Math.max(1, (int) (bm.getWidth() * k)), Math.max(1, (int) (bm.getHeight() * k)), true);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 86, o);
            byte[] b = o.toByteArray();
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(b); }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] pic, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(pic, 0, pic.length, o2);
    }

    // ---------- permissions, pickers, theme

    private String audioPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPerm() {
        return checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) js("window.onPerm&&onPerm(" + hasPerm() + ")");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (requestCode == REQ_LRC && importFor != null) {
            String id = importFor;
            importFor = null;
            bg.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    Lyrics.saveImport(this, id, Lyrics.readAll(in, 2_000_000));
                    sendLyrics(id, false);
                } catch (Exception e) {
                    toast("歌词文件读取失败");
                }
            });
        } else if (requestCode == REQ_TREE) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.edit().putString("lrcTree", u.toString()).apply();
            bg.execute(() -> {
                Lyrics.indexFolder(this, u);
                int n;
                synchronized (Lyrics.folderIndex) { n = Lyrics.folderIndex.size(); }
                js("window.onLrcFolder&&onLrcFolder(" + n + ")");
            });
        }
    }

    private boolean dark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        js("window.setDark&&setDark(" + dark() + ")");
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void sendLyrics(String id, boolean force) {
        JSONObject s = Library.songs.get(id);
        if (s == null) return;
        JSONObject r = Lyrics.find(this, s, prefs.getBoolean("online", true), force);
        js("window.onLyrics&&onLyrics(" + JSONObject.quote(id) + "," + r + ")");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack?onAndroidBack():false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true); // keep the music going
        });
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        if (future != null) MediaController.releaseFuture(future);
        ctl = null;
        super.onDestroy();
    }

    /** Everything the page can ask for. */
    class Bridge {
        @JavascriptInterface
        public boolean hasPerm() {
            return MainActivity.this.hasPerm();
        }

        @JavascriptInterface
        public void askPerm() {
            ui.post(() -> {
                // (media playback notifications do not need the notification permission)
                requestPermissions(new String[]{audioPerm()}, REQ_PERM);
            });
        }

        @JavascriptInterface
        public String demo() {
            String d = demo;
            demo = null;
            return d == null ? "" : d;
        }

        @JavascriptInterface
        public boolean dark() {
            return MainActivity.this.dark();
        }

        /** Lists the songs (min length in seconds); the result arrives in window.onLibrary. */
        @JavascriptInterface
        public void scan(int minSec) {
            bg.execute(() -> {
                JSONArray a = Library.scan(MainActivity.this, minSec * 1000L);
                js("window.onLibrary&&onLibrary(" + a + ")");
            });
        }

        @JavascriptInterface
        public void play(String idsJson, int index, long posMs) {
            ui.post(() -> {
                if (ctl == null) return;
                try {
                    List<MediaItem> list = items(new JSONArray(idsJson));
                    if (list.isEmpty()) return;
                    ctl.setMediaItems(list, Math.max(0, Math.min(index, list.size() - 1)), Math.max(0, posMs));
                    if (ctl.getRepeatMode() == Player.REPEAT_MODE_OFF) ctl.setRepeatMode(Player.REPEAT_MODE_ALL);
                    ctl.prepare();
                    ctl.play();
                } catch (Exception ignored) {
                }
            });
        }

        /** Puts the last queue back (paused) after the app was closed. */
        @JavascriptInterface
        public void restore() {
            ui.post(() -> {
                if (ctl == null || ctl.getMediaItemCount() > 0) return;
                String q = prefs.getString("q", "");
                if (q.isEmpty()) return;
                JSONArray ids = new JSONArray();
                for (String id : q.split(",")) ids.put(id);
                List<MediaItem> list = items(ids);
                if (list.isEmpty()) return;
                String cur = prefs.getString("qid", "");
                int at = 0;
                for (int i = 0; i < list.size(); i++) if (Library.idOfUri(list.get(i).mediaId).equals(cur)) at = i;
                ctl.setMediaItems(list, at, prefs.getLong("qp", 0));
                ctl.setRepeatMode(prefs.getInt("repeat", Player.REPEAT_MODE_ALL));
                ctl.setShuffleModeEnabled(prefs.getBoolean("shuffle", false));
                ctl.prepare();
            });
        }

        @JavascriptInterface
        public void cmd(String name, String arg) {
            ui.post(() -> {
                if (ctl == null) return;
                try {
                    switch (name) {
                        case "toggle":
                            if (ctl.isPlaying()) ctl.pause();
                            else {
                                if (ctl.getPlaybackState() == Player.STATE_IDLE) ctl.prepare();
                                if (ctl.getPlaybackState() == Player.STATE_ENDED) ctl.seekTo(0, 0);
                                ctl.play();
                            }
                            break;
                        case "next":
                            if (ctl.hasNextMediaItem()) ctl.seekToNextMediaItem();
                            else if (ctl.getMediaItemCount() > 0) ctl.seekTo(0, 0);
                            ctl.play();
                            break;
                        case "prev":
                            if (ctl.getCurrentPosition() > 3000 || !ctl.hasPreviousMediaItem()) ctl.seekTo(0);
                            else ctl.seekToPreviousMediaItem();
                            ctl.play();
                            break;
                        case "seek":
                            ctl.seekTo(Long.parseLong(arg));
                            break;
                        case "mode": // 0 repeat all, 1 repeat one, 2 shuffle
                            int m = Integer.parseInt(arg);
                            ctl.setShuffleModeEnabled(m == 2);
                            ctl.setRepeatMode(m == 1 ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_ALL);
                            prefs.edit().putInt("repeat", ctl.getRepeatMode()).putBoolean("shuffle", m == 2).apply();
                            break;
                        case "jump":
                            ctl.seekTo(Integer.parseInt(arg), 0);
                            ctl.play();
                            break;
                        case "remove": {
                            int i = Integer.parseInt(arg);
                            if (i >= 0 && i < ctl.getMediaItemCount()) ctl.removeMediaItem(i);
                            break;
                        }
                        case "playNext":
                        case "enqueue": {
                            JSONObject s = Library.songs.get(arg);
                            if (s == null) break;
                            int old = indexOf(arg);
                            if (old >= 0 && old != ctl.getCurrentMediaItemIndex()) ctl.removeMediaItem(old);
                            else if (old >= 0) break;
                            if (ctl.getMediaItemCount() == 0) {
                                ctl.setMediaItem(item(s));
                                ctl.prepare();
                                ctl.play();
                            } else if (name.equals("playNext")) {
                                ctl.addMediaItem(ctl.getCurrentMediaItemIndex() + 1, item(s));
                            } else {
                                ctl.addMediaItem(item(s));
                            }
                            break;
                        }
                        case "clear":
                            ctl.stop();
                            ctl.clearMediaItems();
                            break;
                    }
                } catch (Exception ignored) {
                }
                pushState(true);
            });
        }

        @JavascriptInterface
        public void sleep(int minutes) {
            ui.post(() -> {
                PlaybackService.setSleep(minutes * 60_000L);
                pushState(false);
            });
        }

        @JavascriptInterface
        public void lyrics(String id, boolean force) {
            bg.execute(() -> sendLyrics(id, force));
        }

        @JavascriptInterface
        public void importLyrics(String id) {
            ui.post(() -> {
                importFor = id;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, REQ_LRC); } catch (Exception e) { toast("没有可用的文件选择器"); }
            });
        }

        @JavascriptInterface
        public void clearLyrics(String id) {
            bg.execute(() -> {
                Lyrics.clearImport(MainActivity.this, id);
                sendLyrics(id, false);
            });
        }

        @JavascriptInterface
        public void pickLyricsFolder() {
            ui.post(() -> {
                try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE); } catch (Exception e) { toast("没有可用的文件选择器"); }
            });
        }

        @JavascriptInterface
        public int lyricsFolderCount() {
            synchronized (Lyrics.folderIndex) { return Lyrics.folderIndex.size(); }
        }

        @JavascriptInterface
        public String get(String key) {
            return prefs.getString("js_" + key, "");
        }

        @JavascriptInterface
        public void set(String key, String value) {
            prefs.edit().putString("js_" + key, value).apply();
            if (key.equals("online")) prefs.edit().putBoolean("online", !"0".equals(value)).apply();
        }

        @JavascriptInterface
        public void toast(String s) {
            MainActivity.this.toast(s);
        }
    }
}
