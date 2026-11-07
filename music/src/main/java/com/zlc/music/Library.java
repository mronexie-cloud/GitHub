package com.zlc.music;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the songs on the phone: everything Android's media index knows about, plus ordinary audio
 * files the index skips (folders marked "hidden from media apps" with a .nomedia file). Only plain,
 * playable audio formats are listed.
 */
class Library {
    static final Set<String> EXT = new HashSet<>(java.util.Arrays.asList("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "mka", "webm", "3gp"));
    /** id -> song */
    static final Map<String, JSONObject> songs = new ConcurrentHashMap<>();
    /** media id given to the player (file address) -> song id */
    static final Map<String, String> byUri = new ConcurrentHashMap<>();

    static String root() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    static JSONArray scan(Context ctx, long minMs) {
        Map<String, JSONObject> out = new HashMap<>();
        Set<String> paths = new HashSet<>();
        String root = root();
        String[] proj = {
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE};
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)) {
            android.util.Log.i("MusicBox", "media index rows: " + (c == null ? "no cursor" : c.getCount()));
            while (c != null && c.moveToNext()) {
                try {
                long id = c.getLong(0);
                long dur = c.isNull(5) ? 0 : c.getLong(5);
                String path = c.getString(6);
                String name = c.getString(7);
                if (path != null) paths.add(path);
                if (dur > 0 && dur < minMs) continue;
                if (dur == 0 && c.getLong(8) < 150_000) continue; // tiny sound with unknown length
                Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                JSONObject s = song("m" + id, uri.toString(), path, name, c.getString(1), c.getString(2), c.getString(3), dur, c.getLong(8), c.getLong(9), root);
                s.put("albumId", c.getLong(4));
                out.put(s.getString("id"), s);
                } catch (Exception e) {
                    android.util.Log.w("MusicBox", "media index row", e);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("MusicBox", "media index query failed", e);
        }
        int indexed = out.size();
        walk(ctx, paths, minMs, out, root);
        android.util.Log.i("MusicBox", "scan: " + indexed + " from the media index (" + paths.size() + " files), " + (out.size() - indexed) + " more from folders");
        songs.clear();
        byUri.clear();
        JSONArray arr = new JSONArray();
        for (JSONObject s : out.values()) {
            songs.put(s.optString("id"), s);
            byUri.put(s.optString("uri"), s.optString("id"));
            arr.put(s);
        }
        return arr;
    }

    static JSONObject song(String id, String uri, String path, String name, String title, String artist, String album,
                           long dur, long size, long added, String root) throws Exception {
        if (name == null || name.isEmpty()) name = path == null ? id : new File(path).getName();
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (title == null || title.trim().isEmpty()) title = base;
        if (artist == null || artist.equals("<unknown>")) artist = "";
        if (album == null || album.equals("<unknown>")) album = "";
        // "Artist - Title" file names from downloads
        if (artist.isEmpty() && title.equals(base) && base.contains(" - ")) {
            int k = base.indexOf(" - ");
            artist = base.substring(0, k).trim();
            title = base.substring(k + 3).trim();
        }
        String folder = "";
        if (path != null) {
            String dir = new File(path).getParent();
            if (dir != null) folder = dir.startsWith(root) ? dir.substring(root.length()).replaceFirst("^/", "") : dir;
        }
        JSONObject s = new JSONObject();
        s.put("id", id);
        s.put("uri", uri);
        s.put("path", path == null ? "" : path);
        s.put("name", name);
        s.put("title", title.trim());
        s.put("artist", artist.trim());
        s.put("album", album.trim());
        s.put("dur", dur);
        s.put("size", size);
        s.put("added", added);
        s.put("folder", folder);
        s.put("ext", name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "");
        return s;
    }

    /** Walks the shared storage for audio files the media index does not list (e.g. in .nomedia folders). */
    static void walk(Context ctx, Set<String> known, long minMs, Map<String, JSONObject> out, String root) {
        File cacheFile = new File(ctx.getFilesDir(), "walk-cache.json");
        JSONObject cache;
        try {
            cache = new JSONObject(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            cache = new JSONObject();
        }
        JSONObject next = new JSONObject();
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{new File(root), 0, false});
        java.util.List<String> unindexed = new java.util.ArrayList<>();
        int dirs = 0;
        while (!stack.isEmpty() && dirs < 20000) {
            Object[] top = stack.pop();
            File dir = (File) top[0];
            int depth = (Integer) top[1];
            // inside a folder marked "hidden from media apps"?
            boolean hiddenDir = (Boolean) top[2] || new File(dir, ".nomedia").exists();
            dirs++;
            File[] list;
            try {
                list = dir.listFiles();
            } catch (Exception e) {
                continue;
            }
            if (list == null) continue;
            for (File f : list) {
                String n = f.getName();
                if (f.isDirectory()) {
                    if (depth == 0 && n.equals("Android")) continue; // other apps' private data: not readable anyway
                    if (n.startsWith(".") || depth >= 9) continue;
                    stack.push(new Object[]{f, depth + 1, hiddenDir});
                    continue;
                }
                int dot = n.lastIndexOf('.');
                if (dot < 0 || !EXT.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;
                String p = f.getAbsolutePath();
                if (known.contains(p) || f.length() < 200_000) continue;
                try {
                    JSONObject m = cache.optJSONObject(p);
                    if (m == null || m.optLong("m") != f.lastModified() || m.optLong("l") != f.length()) {
                        m = readTags(p);
                        m.put("m", f.lastModified());
                        m.put("l", f.length());
                    }
                    next.put(p, m);
                    long dur = m.optLong("d");
                    if (dur > 0 && dur < minMs) continue;
                    if (m.optBoolean("bad")) continue;
                    String id = "f" + Long.toHexString(hash(p));
                    JSONObject s = song(id, Uri.fromFile(f).toString(), p, n, m.optString("t"), m.optString("a"), m.optString("al"),
                            dur, f.length(), f.lastModified() / 1000, root);
                    if (hiddenDir) s.put("hidden", true);
                    else unindexed.add(p);
                    out.put(id, s);
                } catch (Exception ignored) {
                }
            }
        }
        // files Android has not indexed yet (just copied, for example): ask it to, so covers load faster next time
        if (!unindexed.isEmpty()) {
            try {
                android.media.MediaScannerConnection.scanFile(ctx, unindexed.subList(0, Math.min(500, unindexed.size())).toArray(new String[0]), null, null);
            } catch (Exception ignored) {
            }
        }
        try (FileOutputStream o = new FileOutputStream(cacheFile)) {
            o.write(next.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static JSONObject readTags(String path) throws Exception {
        JSONObject m = new JSONObject();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            m.put("t", nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)));
            m.put("a", nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)));
            m.put("al", nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)));
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            m.put("d", d == null ? 0 : Long.parseLong(d));
        } catch (Exception e) {
            m.put("bad", true); // not a readable audio file
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return m;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    static String idOfUri(String uri) {
        String id = byUri.get(uri);
        return id != null ? id : "x:" + uri;
    }
}
