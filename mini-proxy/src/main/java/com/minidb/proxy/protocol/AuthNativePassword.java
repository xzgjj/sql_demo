package com.minidb.proxy.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Implements MySQL mysql_native_password algorithm.
 *
 * server_salt = SHA1(password)
 * double_salt = SHA1(salt) XOR SHA1(scramble + SHA1(salt))
 * client sends: XOR(salt, scramble + double_salt)
 */
public final class AuthNativePassword {

    private static final SecureRandom RNG = new SecureRandom();

    private AuthNativePassword() {}

    public static byte[] generateScramble(int length) {
        byte[] scramble = new byte[length];
        RNG.nextBytes(scramble);
        return scramble;
    }

    public static byte[] computeAuthResponse(byte[] scramble, String password) {
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

    public static boolean verify(byte[] scramble, String password, byte[] clientAuthResponse) {
        if (clientAuthResponse.length == 0) {
            return false; // empty password
        }
        byte[] expected = computeAuthResponse(scramble, password);
        return MessageDigest.isEqual(expected, clientAuthResponse);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
