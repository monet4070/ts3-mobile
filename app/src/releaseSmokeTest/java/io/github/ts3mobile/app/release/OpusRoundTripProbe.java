package io.github.ts3mobile.app.release;

import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;

public final class OpusRoundTripProbe {
    private OpusRoundTripProbe() {}

    public static void main(String[] arguments) {
        try {
            if (arguments.length != 2) throw new IllegalArgumentException("APK and native directory required");
            ClassLoader loader = new PathClassLoader(arguments[0], arguments[1], ClassLoader.getSystemClassLoader());
            Class<?> encoder = Class.forName("io.github.ts3mobile.audio.opus.NativeOpusEncoder", true, loader);
            Class<?> decoder = Class.forName("io.github.ts3mobile.audio.opus.NativeOpusDecoder", true, loader);
            Class<?> inspector = Class.forName("io.github.ts3mobile.audio.opus.NativeOpusInfo", true, loader);
            System.out.println("RELEASE_OPUS_VERSION=" + method(inspector, "nativeVersion").invoke(null));
            Method inspect = method(inspector, "nativePacketInfo", byte[].class);
            Method createEncoder = method(encoder, "nativeCreate", int.class, int.class, int.class);
            Method createDecoder = method(decoder, "nativeCreate", int.class, int.class);
            Method encode = method(encoder, "nativeEncode", long.class, short[].class);
            Method decode = method(decoder, "nativeDecode", long.class, byte[].class, int.class);
            Method destroyEncoder = method(encoder, "nativeDestroy", long.class);
            Method destroyDecoder = method(decoder, "nativeDestroy", long.class);
            for (int lifetime = 0; lifetime < 20; lifetime++) {
                long encoderHandle = (Long) createEncoder.invoke(null, 48000, 1, 64000);
                long decoderHandle = 0;
                try {
                    decoderHandle = (Long) createDecoder.invoke(null, 48000, 1);
                    for (int frame = 0; frame < 10; frame++) {
                        short[] pcm = new short[960];
                        for (int index = 0; index < pcm.length; index++) {
                            pcm[index] = (short) (8000 * Math.sin(2 * Math.PI * 1000 * index / 48000));
                        }
                        byte[] packet = (byte[]) encode.invoke(null, encoderHandle, pcm);
                        int[] metadata = (int[]) inspect.invoke(null, (Object) packet);
                        if (metadata.length != 2 || metadata[0] != 960) throw new AssertionError("Packet duration mismatch");
                        short[] decoded = (short[]) decode.invoke(null, decoderHandle, packet, 5760);
                        if (packet.length == 0 || decoded.length != 960) throw new AssertionError("Codec frame mismatch");
                        boolean audible = false;
                        for (short sample : decoded) audible |= sample != 0;
                        if (!audible) throw new AssertionError("Silent codec output");
                    }
                } finally {
                    if (decoderHandle != 0) destroyDecoder.invoke(null, decoderHandle);
                    if (encoderHandle != 0) destroyEncoder.invoke(null, encoderHandle);
                }
            }
            System.out.println("RELEASE_OPUS_ROUNDTRIP_OK");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method result = owner.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }
}
