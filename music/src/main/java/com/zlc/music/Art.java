package com.zlc.music;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;

/** Song covers: the picture stored in the file (or Android's album art), cached as small JPEGs. */
class Art {
    static byte[] get(Context ctx, String id, int size) {
        if (id == null) return null;
        File dir = new File(ctx.getCacheDir(), "art");
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
                try { bm = ctx.getContentResolver().loadThumbnail(uri, new Size(size, size), null); } catch (Exception ignored) { }
            }
            if (bm == null) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if (id.startsWith("m")) r.setDataSource(ctx, uri);
                    else r.setDataSource(s.optString("path"));
                    byte[] pic = r.getEmbeddedPicture();
                    if (pic != null) bm = decode(pic, size);
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
            }
            if (bm == null && s.has("albumId") && Build.VERSION.SDK_INT < 29) {
                try (InputStream in = ctx.getContentResolver().openInputStream(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), s.optLong("albumId")))) {
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

    static File file(Context ctx, String id, int size) {
        return get(ctx, id, size) == null ? null : new File(new File(ctx.getCacheDir(), "art"), Lyrics.safe(id) + "_" + size + ".jpg");
    }
}
