package com.questlens.recorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class ExportProvider extends ContentProvider {
    private static final String AUTHORITY = "com.questlens.recorder.exports";

    static Uri uri(File file) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        if (uri.getPathSegments().size() != 1) return null;
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("quest-demo-[a-f0-9-]{36}\\.(zip|mp4)")) return null;
        File file = new File(Exports.directory(getContext()), name);
        return file.isFile() ? file : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (!"r".equals(mode) || file == null) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        File file = resolve(uri);
        if (file == null) return null;
        return file.getName().endsWith(".zip") ? "application/zip" : "video/mp4";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] arguments, String order) {
        File file = resolve(uri);
        if (file == null) return null;
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case OpenableColumns.DISPLAY_NAME: row.add(file.getName()); break;
                case OpenableColumns.SIZE: row.add(file.length()); break;
                default: row.add(null);
            }
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
