package io.github.ts3mobile.app.release;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Runs against an already-built APK on ART. Compile separately so these reflective
 * calls cannot change R8 reachability or accidentally preserve the missing classes.
 */
public final class IdentityRoundTripProbe {
    private IdentityRoundTripProbe() {}

    public static void main(String[] args) {
        try {
            verifyIdentityRoundTrip();
            verifyIniStoreRoundTrip();
            System.out.println("RELEASE_IDENTITY_SMOKE_OK");
        } catch (Throwable failure) {
            // Exception messages can contain INI contents. Print only types and frames;
            // the temporary identity and its private key must never leave memory.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                System.err.println(cause.getClass().getName());
                for (StackTraceElement frame : cause.getStackTrace()) {
                    System.err.println("  at " + frame);
                }
            }
            System.exit(1);
        }
    }

    private static void verifyIdentityRoundTrip() throws Exception {
        Class<?> identityClass = Class.forName("com.github.manevolent.ts3j.identity.LocalIdentity");
        Object generated = identityClass.getMethod("generateNew", int.class).invoke(null, 0);
        byte[] originalKey = (byte[]) identityClass.getMethod("toASN").invoke(generated);
        Object current = generated;
        // First load covers a newly generated identity; the second covers reloading
        // an identity previously read and exported. Both use only in-memory material.
        for (int attempt = 0; attempt < 2; attempt++) {
            String material = (String) identityClass.getMethod("export").invoke(current);
            try (InputStream input = new ByteArrayInputStream(material.getBytes(StandardCharsets.UTF_8))) {
                current = identityClass.getMethod("read", InputStream.class).invoke(null, input);
            }
            byte[] restoredKey = (byte[]) identityClass.getMethod("toASN").invoke(current);
            if (!Arrays.equals(originalKey, restoredKey)) {
                throw new AssertionError("Identity roundtrip changed the key");
            }
        }
        System.out.println("IDENTITY_ROUNDTRIP_OK");
    }

    private static void verifyIniStoreRoundTrip() throws Exception {
        // IniParser, IniBuilder and IniFormatter all use ini4j's reflective factory.
        Class<?> iniClass = Class.forName("org.ini4j.Ini");
        Object ini = iniClass.getConstructor(Reader.class)
                .newInstance(new StringReader("[fixture]\nvalue=roundtrip\n"));
        StringWriter output = new StringWriter();
        iniClass.getMethod("store", Writer.class).invoke(ini, output);
        Object restored = iniClass.getConstructor(Reader.class)
                .newInstance(new StringReader(output.toString()));
        Object value = iniClass.getMethod("get", Object.class, Object.class)
                .invoke(restored, "fixture", "value");
        if (!"roundtrip".equals(value)) {
            throw new AssertionError("INI store/load roundtrip changed the fixture");
        }
        System.out.println("INI_STORE_ROUNDTRIP_OK");
    }
}
