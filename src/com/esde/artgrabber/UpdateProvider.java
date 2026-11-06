package com.esde.artgrabber;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Hands the downloaded update APK to Android's installer (read-only, only that one file). */
public class UpdateProvider extends ContentProvider {
    public static final String AUTHORITY = "com.esde.artgrabber.updates";
    public static final String FILE = "update.apk";

    public static File file(android.content.Context c) { return new File(new File(c.getCacheDir(), "updates"), FILE); }

    public static Uri uri() { return Uri.parse("content://" + AUTHORITY + "/" + FILE); }

    private File target(Uri uri) throws FileNotFoundException {
        if (!FILE.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        File f = file(getContext());
        if (!f.isFile()) throw new FileNotFoundException(f.getPath());
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(target(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = target(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = "ES-DE-Art-Grabber.apk";
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
