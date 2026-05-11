package com.minidb.order.service;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Thread-local trace_id holder for full-chain observability.
 * Generates UUID v7-style IDs: 48-bit Unix ms timestamp + 74 random bits.
 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RNG = new SecureRandom();

    private TraceContext() {}

    public static String generate() {
        long ms = Instant.now().toEpochMilli();
        String id = String.format("%012x-%s", ms, randomHex(16));
        CURRENT.set(id);
        return id;
    }

    public static String current() {
        String id = CURRENT.get();
        if (id == null) {
            id = generate();
        }
        return id;
    }

    public static void set(String traceId) {
        CURRENT.set(traceId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
