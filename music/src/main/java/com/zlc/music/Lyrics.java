package com.zlc.music;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finds a song's lyrics, in this order: lyrics the person imported for it, lyrics stored inside the
 * file (MP3 / FLAC / M4A tags), a .lrc file with the same name next to the song or in the lyrics
 * folder the person chose, and finally the free LRCLIB lyrics database (if allowed in settings).
 */
class Lyrics {
    static final String UA = "MusicBox/1.0 (https://github.com/mronexie-cloud/GitHub)";
    /** lower-case file name without extension -> document address, for the chosen lyrics folder */
    static final Map<String, String> folderIndex = new HashMap<>();

    static JSONObject find(Context ctx, JSONObject s, boolean online, boolean force) {
        JSONObject r = new JSONObject();
        try {
            String id = s.optString("id");
            File imported = new File(ctx.getFilesDir(), "lyrics/" + safe(id) + ".lrc");
            String t;
            if (imported.exists() && !(t = text(Files.readAllBytes(imported.toPath()))).trim().isEmpty()) return r.put("src", "import").put("text", t);
            t = embedded(ctx, Uri.parse(s.optString("uri")));
            if (t != null && !t.trim().isEmpty()) return r.put("src", "tag").put("text", t);
            String path = s.optString("path");
            if (!path.isEmpty()) {
                int dot = path.lastIndexOf('.');
                File side = new File((dot > 0 ? path.substring(0, dot) : path) + ".lrc");
                try {
                    if (side.canRead()) {
                        t = text(Files.readAllBytes(side.toPath()));
                        if (!t.trim().isEmpty()) return r.put("src", "file").put("text", t);
                    }
                } catch (Exception ignored) {
                }
            }
            String doc = fromFolder(s);
            if (doc != null) {
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(doc))) {
                    t = text(readAll(in, 2_000_000));
                    if (!t.trim().isEmpty()) return r.put("src", "file").put("text", t);
                } catch (Exception ignored) {
                }
            }
            if (!online) return r.put("src", "none");
            File cache = new File(ctx.getFilesDir(), "lyrics-cache/" + safe(id) + ".lrc");
            File miss = new File(ctx.getFilesDir(), "lyrics-cache/" + safe(id) + ".none");
            if (!force) {
                if (cache.exists()) return r.put("src", "online").put("text", text(Files.readAllBytes(cache.toPath())));
                if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 7L * 86400_000) return r.put("src", "none");
            }
            t = lrclib(s.optString("title"), s.optString("artist"), s.optLong("dur") / 1000);
            if (t == null) return r.put("src", "none").put("offline", true);
            cache.getParentFile().mkdirs();
            if (t.isEmpty()) {
                new FileOutputStream(miss).close();
                miss.setLastModified(System.currentTimeMillis());
                cache.delete();
                return r.put("src", "none");
            }
            try (FileOutputStream o = new FileOutputStream(cache)) { o.write(t.getBytes(StandardCharsets.UTF_8)); }
            miss.delete();
            return r.put("src", "online").put("text", t);
        } catch (Exception e) {
            try { r.put("src", "none"); } catch (Exception ignored) { }
            return r;
        }
    }

    static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    static void saveImport(Context ctx, String id, byte[] data) throws IOException {
        File f = new File(ctx.getFilesDir(), "lyrics/" + safe(id) + ".lrc");
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(text(data).getBytes(StandardCharsets.UTF_8)); }
    }

    static void clearImport(Context ctx, String id) {
        new File(ctx.getFilesDir(), "lyrics/" + safe(id) + ".lrc").delete();
        new File(ctx.getFilesDir(), "lyrics-cache/" + safe(id) + ".lrc").delete();
    }

    // ---------- text decoding: UTF-8, UTF-16 with BOM, or GBK (common for Chinese .lrc files)

    static String text(byte[] b) {
        if (b == null) return "";
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF)
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        if (b.length >= 2 && (((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) || ((b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF)))
            return new String(b, StandardCharsets.UTF_16);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("GB18030"));
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            if (o.size() > max) break;
        }
        return o.toByteArray();
    }

    static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) throw new EOFException();
            off += n;
            len -= n;
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) {
                if (in.read() < 0) throw new EOFException();
                k = 1;
            }
            n -= k;
        }
    }

    // ---------- lyrics stored inside the file

    static String embedded(Context ctx, Uri uri) {
        try (InputStream raw = ctx.getContentResolver().openInputStream(uri)) {
            if (raw == null) return null;
            InputStream in = new BufferedInputStream(raw, 65536);
            byte[] h = new byte[12];
            in.mark(16);
            readFully(in, h, 0, 12);
            in.reset();
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return id3(in);
            if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return flac(in);
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return mp4(in);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** ID3v2 (MP3): USLT frame, or a TXXX frame named LYRICS. */
    static String id3(InputStream in) throws IOException {
        byte[] hd = new byte[10];
        readFully(in, hd, 0, 10);
        int ver = hd[3];
        int size = ((hd[6] & 0x7f) << 21) | ((hd[7] & 0x7f) << 14) | ((hd[8] & 0x7f) << 7) | (hd[9] & 0x7f);
        if (size <= 0 || size > 40_000_000) return null;
        byte[] tag = new byte[size];
        readFully(in, tag, 0, size);
        if ((hd[5] & 0x80) != 0) tag = unsync(tag);
        int p = 0;
        if ((hd[5] & 0x40) != 0 && ver >= 3) { // extended header
            int ext = ver == 4 ? synch(tag, 0) : be32(tag, 0) + 4;
            p = Math.max(0, ext);
        }
        String best = null;
        while (p + (ver == 2 ? 6 : 10) <= tag.length) {
            String fid;
            int fsize, hlen;
            if (ver == 2) {
                fid = new String(tag, p, 3, StandardCharsets.ISO_8859_1);
                fsize = ((tag[p + 3] & 0xff) << 16) | ((tag[p + 4] & 0xff) << 8) | (tag[p + 5] & 0xff);
                hlen = 6;
            } else {
                fid = new String(tag, p, 4, StandardCharsets.ISO_8859_1);
                fsize = ver == 4 ? synch(tag, p + 4) : be32(tag, p + 4);
                hlen = 10;
            }
            if (fid.charAt(0) == 0 || fsize <= 0 || p + hlen + fsize > tag.length) break;
            int b = p + hlen;
            if (fid.equals("USLT") || fid.equals("ULT")) {
                int enc = tag[b];
                int q = b + 4; // encoding + language
                q = skipString(tag, q, b + fsize, enc);
                String t = decode(tag, q, b + fsize, enc);
                if (best == null || t.length() > best.length()) best = t;
            } else if (fid.equals("TXXX") || fid.equals("TXX")) {
                int enc = tag[b];
                int q = skipString(tag, b + 1, b + fsize, enc);
                String desc = decode(tag, b + 1, q, enc).trim().toLowerCase(Locale.ROOT);
                if ((desc.startsWith("lyrics") || desc.equals("unsyncedlyrics")) && best == null) best = decode(tag, q, b + fsize, enc);
            }
            p = b + fsize;
        }
        return best;
    }

    static byte[] unsync(byte[] t) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(t.length);
        for (int i = 0; i < t.length; i++) {
            o.write(t[i]);
            if ((t[i] & 0xff) == 0xFF && i + 1 < t.length && t[i + 1] == 0) i++;
        }
        return o.toByteArray();
    }

    static int synch(byte[] b, int o) {
        return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f);
    }

    static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** Skips a terminated string (description) and returns the index after its terminator. */
    static int skipString(byte[] t, int q, int end, int enc) {
        if (enc == 1 || enc == 2) {
            while (q + 1 < end && !(t[q] == 0 && t[q + 1] == 0)) q += 2;
            return Math.min(end, q + 2);
        }
        while (q < end && t[q] != 0) q++;
        return Math.min(end, q + 1);
    }

    static String decode(byte[] t, int from, int to, int enc) {
        if (to <= from) return "";
        byte[] b = java.util.Arrays.copyOfRange(t, from, to);
        String s;
        switch (enc) {
            case 1: s = new String(b, StandardCharsets.UTF_16); break;
            case 2: s = new String(b, StandardCharsets.UTF_16BE); break;
            case 3: s = new String(b, StandardCharsets.UTF_8); break;
            default: {
                boolean ascii = true;
                for (byte x : b) if ((x & 0x80) != 0) { ascii = false; break; }
                s = ascii ? new String(b, StandardCharsets.ISO_8859_1) : text(b); // "Latin-1" tags from Chinese tools are often GBK
            }
        }
        int z = s.indexOf('\0');
        return z >= 0 ? s.substring(0, z) : s;
    }

    /** FLAC: the LYRICS / UNSYNCEDLYRICS comment. */
    static String flac(InputStream in) throws IOException {
        skipFully(in, 4);
        byte[] h = new byte[4];
        for (int i = 0; i < 64; i++) {
            readFully(in, h, 0, 4);
            boolean last = (h[0] & 0x80) != 0;
            int type = h[0] & 0x7f;
            int len = ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
            if (type == 4) {
                byte[] b = new byte[len];
                readFully(in, b, 0, len);
                ByteBuffer bb = ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int vl = bb.getInt();
                bb.position(bb.position() + vl);
                int n = bb.getInt();
                for (int k = 0; k < n && bb.remaining() >= 4; k++) {
                    int l = bb.getInt();
                    if (l < 0 || l > bb.remaining()) break;
                    String kv = new String(b, bb.position(), l, StandardCharsets.UTF_8);
                    bb.position(bb.position() + l);
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    String key = kv.substring(0, eq).toUpperCase(Locale.ROOT);
                    if (key.equals("LYRICS") || key.equals("UNSYNCEDLYRICS") || key.equals("SYNCEDLYRICS")) return kv.substring(eq + 1);
                }
                return null;
            }
            skipFully(in, len);
            if (last) break;
        }
        return null;
    }

    /** MP4 / M4A: moov/udta/meta/ilst/©lyr. */
    static String mp4(InputStream in) throws IOException {
        return atoms(in, Long.MAX_VALUE, 0);
    }

    static String atoms(InputStream in, long limit, int depth) throws IOException {
        byte[] h = new byte[8];
        long used = 0;
        while (used + 8 <= limit) {
            try {
                readFully(in, h, 0, 8);
            } catch (EOFException e) {
                return null;
            }
            long size = be32(h, 0) & 0xffffffffL;
            String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
            long head = 8;
            if (size == 1) {
                byte[] x = new byte[8];
                readFully(in, x, 0, 8);
                size = ((long) be32(x, 0) << 32) | (be32(x, 4) & 0xffffffffL);
                head = 16;
            } else if (size == 0) {
                size = limit == Long.MAX_VALUE ? Long.MAX_VALUE / 2 : limit - used;
            }
            if (size < head) return null;
            long body = size - head;
            used += size;
            if (depth < 6 && (type.equals("moov") || type.equals("udta") || type.equals("ilst") || type.equals("meta"))) {
                if (type.equals("meta")) { skipFully(in, 4); body -= 4; }
                String r = atoms(in, body, depth + 1);
                if (r != null) return r;
                continue;
            }
            if (type.equals("©lyr") && body < 2_000_000) {
                byte[] b = new byte[(int) body];
                readFully(in, b, 0, b.length);
                // child "data" atom: size, "data", type, locale, text
                if (b.length > 16) return new String(b, 16, b.length - 16, StandardCharsets.UTF_8);
                return null;
            }
            if (depth == 0 && type.equals("mdat") && body > 50_000_000) {
                skipFully(in, body); // large media data before the index: skip without reading
                continue;
            }
            skipFully(in, body);
        }
        return null;
    }

    // ---------- lyrics folder chosen by the person (needed on Android 11+ to read .lrc files)

    static void indexFolder(Context ctx, Uri tree) {
        Map<String, String> idx = new HashMap<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(DocumentsContract.getTreeDocumentId(tree));
        int dirs = 0;
        while (!stack.isEmpty() && dirs < 3000 && idx.size() < 50000) {
            String docId = stack.pop();
            dirs++;
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            try (Cursor c = ctx.getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        stack.push(id);
                    } else if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                        idx.put(name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT).trim(),
                                DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (folderIndex) {
            folderIndex.clear();
            folderIndex.putAll(idx);
        }
    }

    static String fromFolder(JSONObject s) {
        synchronized (folderIndex) {
            if (folderIndex.isEmpty()) return null;
            String name = s.optString("name");
            int dot = name.lastIndexOf('.');
            String base = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT).trim();
            String title = s.optString("title").toLowerCase(Locale.ROOT).trim();
            String artist = s.optString("artist").toLowerCase(Locale.ROOT).trim();
            for (String k : new String[]{base, artist + " - " + title, title + " - " + artist, title}) {
                String d = folderIndex.get(k);
                if (d != null) return d;
            }
            return null;
        }
    }

    // ---------- LRCLIB (free, open lyrics database)

    static String str(JSONObject o, String k) {
        return o.isNull(k) ? "" : o.optString(k, "");
    }

    /** Returns lyrics, "" when none were found, or null when offline. */
    static String lrclib(String title, String artist, long durSec) {
        String[] titles = {title, title.replaceAll("[\\(（\\[【].*?[\\)）\\]】]", "").trim()};
        boolean reached = false;
        for (int pass = 0; pass < 2; pass++) {
            String t = titles[pass];
            if (t.isEmpty() || (pass == 1 && t.equals(titles[0]))) continue;
            try {
                String q = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(t, "UTF-8")
                        + (artist.isEmpty() ? "" : "&artist_name=" + URLEncoder.encode(artist, "UTF-8"));
                HttpURLConnection c = (HttpURLConnection) new URL(q).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", UA);
                if (c.getResponseCode() != 200) continue;
                reached = true;
                JSONArray a;
                try (InputStream in = c.getInputStream()) { a = new JSONArray(new String(readAll(in, 4_000_000), StandardCharsets.UTF_8)); }
                JSONObject best = null;
                double bestScore = 1e9;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (str(o, "syncedLyrics").isEmpty() && str(o, "plainLyrics").isEmpty()) continue;
                    double d = durSec > 0 ? Math.abs(o.optDouble("duration", 0) - durSec) : 0;
                    if (durSec > 0 && d > 6) continue; // a different recording
                    double score = d + (str(o, "syncedLyrics").isEmpty() ? 3 : 0);
                    if (score < bestScore) { bestScore = score; best = o; }
                }
                if (best != null) {
                    String s = str(best, "syncedLyrics");
                    return s.isEmpty() ? str(best, "plainLyrics") : s;
                }
            } catch (Exception ignored) {
            }
        }
        return reached ? "" : null;
    }
}
