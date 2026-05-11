package com.minidb.proxy.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Implements MySQL mysql_native_password and caching_sha2_password algorithms.
 */
public final class AuthNativePassword {

    private static final SecureRandom RNG = new SecureRandom();

    private AuthNativePassword() {}

    public static byte[] generateScramble(int length) {
        byte[] scramble = new byte[length];
        RNG.nextBytes(scramble);
        return scramble;
    }

    // ---- mysql_native_password (SHA1) ----

    public static byte[] computeNativeAuthResponse(byte[] scramble, String password) {
        MessageDigest sha1 = sha1();
        byte[] passwordHash = sha1.digest(password.getBytes(StandardCharsets.UTF_8));
        byte[] doubleHash = sha1.digest(passwordHash);

        byte[] combined = new byte[scramble.length + doubleHash.length];
        System.arraycopy(scramble, 0, combined, 0, scramble.length);
        System.arraycopy(doubleHash, 0, combined, scramble.length, doubleHash.length);
        byte[] salted = sha1.digest(combined);

        byte[] result = new byte[salted.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (salted[i] ^ passwordHash[i]);
        }
        return result;
    }

    // ---- caching_sha2_password (SHA256) ----

    public static byte[] computeSha2AuthResponse(byte[] scramble, String password) {
        MessageDigest sha256 = sha256();
        byte[] digest1 = sha256.digest(password.getBytes(StandardCharsets.UTF_8));
        byte[] digest2 = sha256.digest(digest1);

        byte[] combined = new byte[digest2.length + scramble.length];
        System.arraycopy(digest2, 0, combined, 0, digest2.length);
        System.arraycopy(scramble, 0, combined, digest2.length, scramble.length);
        byte[] scrambleHash = sha256.digest(combined);

        byte[] result = new byte[digest1.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (digest1[i] ^ scrambleHash[i]);
        }
        return result;
    }

    /**
     * Try native (SHA1) auth first, then caching_sha2 (SHA256) as fallback.
     * Returns true if either algorithm matches.
     */
    public static boolean verifyAny(byte[] scramble, String password, byte[] clientAuthResponse) {
        if (clientAuthResponse.length == 0) return false;
        // Try mysql_native_password (20 bytes)
        if (clientAuthResponse.length == 20) {
            byte[] expected = computeNativeAuthResponse(scramble, password);
            if (MessageDigest.isEqual(expected, clientAuthResponse)) return true;
        }
        // Try caching_sha2_password (32 bytes)
        if (clientAuthResponse.length == 32) {
            byte[] expected = computeSha2AuthResponse(scramble, password);
            return MessageDigest.isEqual(expected, clientAuthResponse);
        }
        return false;
    }

    public static boolean verify(byte[] scramble, String password, byte[] clientAuthResponse) {
        if (clientAuthResponse.length == 0) return false;
        byte[] expected = computeNativeAuthResponse(scramble, password);
        return MessageDigest.isEqual(expected, clientAuthResponse);
    }

    private static MessageDigest sha1() {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException e) { throw new RuntimeException("SHA-1 not available", e); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new RuntimeException("SHA-256 not available", e); }
    }
}
