package com.github.manevolent.ts3j.protocol.packet.handler.client;

import java.math.BigInteger;
import java.util.Arrays;

public final class UnsignedWidthProbe {
    public static void main(String[] args) {
        expectBytes(BigInteger.ZERO, new byte[64]);
        expectBytes(BigInteger.ONE, new byte[] {0, 0, 0, 1});
        expectBytes(BigInteger.valueOf(0x8000L), new byte[] {(byte) 0x80, 0});
        expectBytes(BigInteger.valueOf(0x80L), new byte[] {0, (byte) 0x80});
        expectBytes(BigInteger.valueOf(0xffffL), new byte[] {(byte) 0xff, (byte) 0xff});
        expectRejected(BigInteger.valueOf(0x10000L), 2);
        expectRejected(BigInteger.valueOf(-1), 2);
        expectRejected(BigInteger.ZERO, 0);
        System.out.println("TS3J_UNSIGNED_WIDTH_OK");
    }

    private static void expectBytes(BigInteger value, byte[] expected) {
        if (!Arrays.equals(expected, LocalClientHandlerConnecting.encodeUnsigned(value, expected.length))) {
            throw new AssertionError("incorrect fixed-width unsigned encoding");
        }
    }

    private static void expectRejected(BigInteger value, int width) {
        try {
            LocalClientHandlerConnecting.encodeUnsigned(value, width);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("out-of-range value was not rejected");
    }
}
