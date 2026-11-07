package io.github.ts3mobile.app.upgrade;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Test-only instrumentation for a fresh disposable emulator, using the installed Debug app's real vaults. */
public final class UpgradeDataProbe extends Instrumentation {
    private static final String PACKAGE = "io.github.ts3mobile.app.";
    private static final String MARKER = "improvement_upgrade_fixture_v1";
    private String stage;
    private ClassLoader targetLoader;
    private boolean includeTuning;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        stage = arguments.getString("stage", "verify");
        includeTuning = Boolean.parseBoolean(arguments.getString("includeTuning", "false"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            targetLoader = context.getClassLoader();
            SharedPreferences marker = context.getSharedPreferences(MARKER, Context.MODE_PRIVATE);
            if ("verify-raw".equals(stage) || "verify-after-identity-maintenance".equals(stage)) {
                boolean changedIdentity = "verify-after-identity-maintenance".equals(stage);
                verifyRawStorage(context, marker, changedIdentity);
                result.putString("stream", changedIdentity ? "UPGRADE_UNRELATED_FILES_ORIGINAL_KEYS_UID_OK\n"
                    : "UPGRADE_REAL_VAULT_FILES_KEYS_UID_OK\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            Object identities = type("identity.IdentityVault").getConstructor(Context.class).newInstance(context);
            Object settings = type("settings.ConnectionSettingsVault").getConstructor(Context.class).newInstance(context);
            Object bookmarks = null;
            for (Constructor<?> constructor : type("bookmarks.BookmarkVault").getDeclaredConstructors()) {
                if (constructor.getParameterTypes().length == 4) {
                    bookmarks = constructor.newInstance(context, null, 2, null);
                    break;
                }
            }
            require(bookmarks != null, "Bookmark vault constructor unavailable");
            Object form = type("settings.RememberedConnection").getConstructors()[0].newInstance(
                "voice.example.com", "9987", "Upgrade fixture", "fixture-server-password");
            Object bookmark = null;
            for (Constructor<?> constructor : type("bookmarks.ServerBookmark").getConstructors()) {
                if (constructor.getParameterTypes().length == 10) {
                    bookmark = constructor.newInstance("upgrade-fixture", 0L, "Upgrade fixture", "voice.example.com",
                        9987, "Upgrade fixture", 42, true, "fixture-server-password", "fixture-channel-password");
                    break;
                }
            }
            require(bookmark != null, "Bookmark constructor unavailable");
            if ("seed".equals(stage)) {
                require(!marker.contains("identity_digest"), "Fixture already exists");
                require(!new File(context.getFilesDir(), "datastore/ts3_identity.preferences_pb").exists(), "Existing identity refused");
                require(!new File(context.getFilesDir(), "datastore/ts3_connection_settings.preferences_pb").exists(), "Existing form refused");
                require(!new File(context.getFilesDir(), "datastore/ts3_bookmarks.preferences_pb").exists(), "Existing bookmarks refused");
                String identity = (String) suspendCall(identities, "getOrCreate");
                suspendCall(settings, "write", form);
                suspendCall(bookmarks, "save", bookmark);
                require(marker.edit().putString("identity_digest", digest(identity)).putInt("uid", context.getApplicationInfo().uid).commit(),
                    "Fixture marker write failed");
                recordRawStorage(context, marker);
                if (includeTuning) seedTuning(context, marker);
                result.putString("stream", "UPGRADE_REAL_VAULT_FIXTURE_SEEDED\n");
            } else if ("verify".equals(stage)) {
                require(marker.contains("identity_digest"), "Missing seed marker");
                require(marker.getInt("uid", -1) == context.getApplicationInfo().uid, "Application UID changed");
                require(marker.getString("identity_digest", "").equals(digest((String) suspendCall(identities, "getOrCreate"))),
                    "Identity changed or could not be decrypted");
                require(form.equals(suspendCall(settings, "read")), "Connection form changed");
                List<?> entries = (List<?>) suspendCall(bookmarks, "read");
                require(entries.size() == 1, "Bookmark count changed");
                Object entry = entries.get(0);
                require("upgrade-fixture".equals(value(entry, "getId")), "Bookmark identity changed");
                require("Upgrade fixture".equals(value(entry, "getName")), "Bookmark name changed");
                require("voice.example.com".equals(value(entry, "getHost")), "Bookmark endpoint changed");
                require(Integer.valueOf(9987).equals(value(entry, "getPort")), "Bookmark port changed");
                require(Integer.valueOf(42).equals(value(entry, "getDefaultChannelId")), "Bookmark channel changed");
                require(Long.valueOf(1L).equals(value(entry, "getRevision")), "Bookmark revision changed");
                require("fixture-server-password".equals(value(entry, "getPassword")), "Encrypted server password changed");
                require("fixture-channel-password".equals(value(entry, "getChannelPassword")), "Encrypted channel password changed");
                verifyRawStorage(context, marker);
                if (marker.contains("tuning_digest")) {
                    require(expectedTuning().equals(suspendCall(tuningStore(context), "read")), "Microphone preferences changed");
                }
                result.putString("stream", "UPGRADE_REAL_IDENTITY_FORM_BOOKMARKS_UID_OK\n");
            } else {
                throw new IllegalArgumentException("Unknown stage");
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Never report material, exception messages, argument values or stack contents.
            result.putString("stream", "UPGRADE_FIXTURE_FAILED " + failure.getClass().getSimpleName() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Class<?> type(String suffix) throws ClassNotFoundException {
        return Class.forName(PACKAGE + suffix, true, targetLoader);
    }

    private Object tuningStore(Context context) throws Exception {
        return type("settings.MicrophonePreferenceDataStoreKt")
            .getMethod("microphonePreferenceStore", Context.class).invoke(null, context);
    }

    private Object expectedTuning() throws Exception {
        return Class.forName("io.github.ts3mobile.audio.opus.MicrophoneSettings", true, targetLoader)
            .getConstructor(int.class, boolean.class, boolean.class, int.class).newInstance(147, false, false, -28);
    }

    private void seedTuning(Context context, SharedPreferences marker) throws Exception {
        require(!new File(context.getFilesDir(), "datastore/ts3_microphone_preferences.preferences_pb").exists(),
            "Existing microphone preferences refused");
        suspendCall(tuningStore(context), "write", expectedTuning(), false);
        require(marker.edit().putString("tuning_digest", fileDigest(context, "ts3_microphone_preferences")).commit(),
            "Microphone compatibility marker failed");
    }

    private Object value(Object target, String getter) throws Exception {
        return target.getClass().getMethod(getter).invoke(target);
    }

    private Object suspendCall(Object receiver, String name, Object... arguments) throws Exception {
        Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", true, targetLoader);
        Object emptyContext = Class.forName("kotlin.coroutines.EmptyCoroutineContext", true, targetLoader).getField("INSTANCE").get(null);
        Object suspended = Class.forName("kotlin.coroutines.intrinsics.CoroutineSingletons", true, targetLoader)
            .getField("COROUTINE_SUSPENDED").get(null);
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Object completion = Proxy.newProxyInstance(targetLoader, new Class<?>[] { continuation }, (proxy, method, args) -> {
            if ("getContext".equals(method.getName())) return emptyContext;
            if ("resumeWith".equals(method.getName())) {
                outcome.set(args[0]);
                resumed.countDown();
                return null;
            }
            if ("toString".equals(method.getName())) return "UpgradeFixtureContinuation";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
            return null;
        });
        Object[] parameters = Arrays.copyOf(arguments, arguments.length + 1);
        parameters[arguments.length] = completion;
        for (Method method : receiver.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals(name) && types.length == parameters.length && types[types.length - 1] == continuation) {
                Object immediate = method.invoke(receiver, parameters);
                if (immediate != suspended) return immediate;
                require(resumed.await(20, TimeUnit.SECONDS), "Vault operation timed out");
                Object result = outcome.get();
                Class.forName("kotlin.ResultKt", true, targetLoader).getMethod("throwOnFailure", Object.class).invoke(null, result);
                return result;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private String digest(String material) throws Exception {
        return digestBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static final String[] FILES = { "ts3_identity", "ts3_connection_settings", "ts3_bookmarks" };
    private static final String[] ALIASES = { "ts3_mobile_identity_v1", "ts3_mobile_connection_settings_v1", "ts3_mobile_bookmarks_v1" };
    private static final byte[] CHALLENGE = "synthetic-upgrade-key-challenge".getBytes(StandardCharsets.UTF_8);

    private void recordRawStorage(Context context, SharedPreferences marker) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        SharedPreferences.Editor editor = marker.edit();
        for (int i = 0; i < FILES.length; i++) {
            editor.putString("file_digest_" + i, fileDigest(context, FILES[i]));
            SecretKey key = (SecretKey) keys.getKey(ALIASES[i], null);
            require(key != null, "Missing original vault key");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            editor.putString("iv_" + i, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            editor.putString("challenge_" + i, Base64.encodeToString(cipher.doFinal(CHALLENGE), Base64.NO_WRAP));
        }
        require(editor.commit(), "Compatibility marker write failed");
    }

    private void verifyRawStorage(Context context, SharedPreferences marker) throws Exception {
        verifyRawStorage(context, marker, false);
    }

    private void verifyRawStorage(Context context, SharedPreferences marker, boolean changedIdentity) throws Exception {
        require(marker.getInt("uid", -1) == context.getApplicationInfo().uid, "Application UID changed");
        if (marker.contains("tuning_digest")) {
            require(fileDigest(context, "ts3_microphone_preferences").equals(marker.getString("tuning_digest", "")),
                "Microphone preference bytes changed");
        }
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        for (int i = 0; i < FILES.length; i++) {
            // Only the explicitly named maintenance stage permits the intentionally changed
            // identity file. Every original key, other Vault and tuning byte is still checked.
            if (!changedIdentity || i != 0) {
                require(fileDigest(context, FILES[i]).equals(marker.getString("file_digest_" + i, "")), "Encrypted vault file changed");
            } else {
                require(new File(context.getFilesDir(), "datastore/ts3_identity.preferences_pb").length() > 0, "Identity store missing");
            }
            SecretKey key = (SecretKey) keys.getKey(ALIASES[i], null);
            require(key != null, "Vault key missing after update");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(marker.getString("iv_" + i, ""), Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(marker.getString("challenge_" + i, ""), Base64.NO_WRAP));
            require(Arrays.equals(CHALLENGE, plain), "Original vault key cannot decrypt");
        }
    }

    private String fileDigest(Context context, String name) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(new File(context.getFilesDir(), "datastore/" + name + ".preferences_pb"))) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private String digestBytes(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String hex(byte[] hash) {
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
