package io.github.ts3mobile.app.identity.documents;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
        if (mode.startsWith("w") && "true".equals(uri.getQueryParameter("failAfterPrefix"))) return prefixThenFail(file(uri));
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    private ParcelFileDescriptor prefixThenFail(File destination) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            Thread writer = new Thread(() -> {
                try (InputStream source = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                     FileOutputStream target = new FileOutputStream(destination)) {
                    byte[] prefix = new byte[4096];
                    int read = source.read(prefix);
                    if (read > 0) target.write(prefix, 0, read);
                    target.flush();
                    pipe[0].closeWithError("Fixture rejected the remaining bytes");
                } catch (IOException error) {
                    android.util.Log.w("FixtureDocument", "Fixture pipe closed", error);
                }
            }, "fixture-document-prefix");
            writer.setDaemon(true);
            writer.start();
            return pipe[1];
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Fixture pipe unavailable");
            failure.initCause(error);
            throw failure;
        }
    }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[0-9a-f-]{36}")) throw new FileNotFoundException("Invalid fixture");
        return new File(getContext().getCacheDir(), "backup-test-" + name);
    }
}
