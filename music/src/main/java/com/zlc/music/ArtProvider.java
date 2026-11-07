package com.zlc.music;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Serves each song's cover to the media notification and lock screen (inside this app only). */
public class ArtProvider extends ContentProvider {
    static final String AUTHORITY = "com.zlc.music.art";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = uri.getLastPathSegment();
        File f = id == null || getContext() == null ? null : Art.file(getContext(), id, 512);
        if (f == null || !f.exists()) throw new FileNotFoundException("no cover");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String s, String[] a) {
        return 0;
    }
}
