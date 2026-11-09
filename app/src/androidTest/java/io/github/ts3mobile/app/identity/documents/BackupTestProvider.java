package io.github.ts3mobile.app.identity.documents;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Standalone instrumentation-package provider; no target-APK/Kotlin runtime dependency. */
public final class BackupTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return file(uri).delete() ? 1 : 0; } catch (FileNotFoundException invalid) { return 0; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("r".equals(mode) && "true".equals(uri.getQueryParameter("denyRead"))) throw new FileNotFoundException("Fixture read denied");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[0-9a-f-]{36}")) throw new FileNotFoundException("Invalid fixture");
        return new File(getContext().getCacheDir(), "backup-test-" + name);
    }
}
