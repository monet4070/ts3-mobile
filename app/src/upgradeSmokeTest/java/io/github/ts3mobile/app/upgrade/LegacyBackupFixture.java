package io.github.ts3mobile.app.upgrade;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

final class LegacyBackupFixture {
    private static final String IDENTITY = "io.github.ts3mobile.app.identity.";
    private static final String SYNTHETIC_PASSPHRASE = "disposable upgrade probe passphrase";

    private LegacyBackupFixture() {}

    static void write(Context context, String syntheticMaterial) throws Exception {
        File document = document(context);
        if (document.exists()) throw new IllegalStateException("Legacy backup fixture already exists");
        Class<?> codecType = type(context, "backup.IdentityBackupCodec");
        Object baselineCodec = codecType.getConstructor().newInstance();
        byte[] material = syntheticMaterial.getBytes(StandardCharsets.UTF_8);
        char[] secret = SYNTHETIC_PASSPHRASE.toCharArray();
        byte[] ciphertext = null;
        try {
            ciphertext = (byte[]) codecType.getMethod("encrypt", byte[].class, char[].class).invoke(baselineCodec, material, secret);
            try (FileOutputStream destination = new FileOutputStream(document)) {
                destination.write(ciphertext);
                destination.getFD().sync();
            }
        } finally {
            Arrays.fill(material, (byte) 0);
            Arrays.fill(secret, '\u0000');
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
        }
    }

    static void verify(Context context, String expectedMaterial) throws Exception {
        Class<?> codecType = type(context, "backup.IdentityBackupCodec");
        Class<?> documentType = type(context, "documents.BackupDocument");
        Class<?> importedType = type(context, "documents.ImportedIdentity");
        Object document = Proxy.newProxyInstance(context.getClassLoader(), new Class<?>[]{documentType}, (proxy, method, args) -> {
            if (!"openRead".equals(method.getName())) throw new UnsupportedOperationException("Legacy document is read-only");
            return new FileInputStream(document(context));
        });
        Object reader = importedType.getField("Companion").get(null);
        char[] secret = SYNTHETIC_PASSPHRASE.toCharArray();
        try (AutoCloseable preview = (AutoCloseable) reader.getClass().getMethod("read", documentType, char[].class, codecType)
                .invoke(reader, document, secret, codecType.getConstructor().newInstance())) {
            String material = (String) importedType.getMethod("materialForCommit").invoke(preview);
            if (!MessageDigest.isEqual(digest(expectedMaterial), digest(material))) {
                throw new IllegalStateException("Legacy backup recovered a different synthetic identity");
            }
        } finally {
            Arrays.fill(secret, '\u0000');
        }
    }

    private static File document(Context context) {
        return new File(context.getFilesDir(), "synthetic-legacy-probe.ts3mbak");
    }

    private static Class<?> type(Context context, String name) throws ClassNotFoundException {
        return Class.forName(IDENTITY + name, true, context.getClassLoader());
    }

    private static byte[] digest(String material) throws Exception {
        byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
