package com.minidb.proxy.protocol;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AuthNativePasswordTest {

    @Test
    void shouldComputeAndVerifyCorrectPassword() {
        byte[] scramble = AuthNativePassword.generateScramble(20);
        String password = "proxy123";

        byte[] clientResponse = AuthNativePassword.computeAuthResponse(scramble, password);
        assertTrue(AuthNativePassword.verify(scramble, password, clientResponse));
    }

    @Test
    void shouldRejectWrongPassword() {
        byte[] scramble = AuthNativePassword.generateScramble(20);
        byte[] clientResponse = AuthNativePassword.computeAuthResponse(scramble, "proxy123");

        assertFalse(AuthNativePassword.verify(scramble, "wrongPassword", clientResponse));
    }

    @Test
    void shouldRejectEmptyResponse() {
        byte[] scramble = AuthNativePassword.generateScramble(20);
        assertFalse(AuthNativePassword.verify(scramble, "proxy123", new byte[0]));
    }

    @Test
    void shouldHandleEmptyPassword() {
        byte[] scramble = AuthNativePassword.generateScramble(20);
        byte[] clientResponse = AuthNativePassword.computeAuthResponse(scramble, "");
        assertTrue(AuthNativePassword.verify(scramble, "", clientResponse));
    }

    @Test
    void shouldGenerateDeterministicResponse() {
        byte[] scramble = new byte[20];
        new Random(42).nextBytes(scramble);
        String password = "test";

        byte[] response1 = AuthNativePassword.computeAuthResponse(scramble, password);
        byte[] response2 = AuthNativePassword.computeAuthResponse(scramble, password);
        assertArrayEquals(response1, response2);
    }

    @Test
    void shouldGenerateDifferentScrambles() {
        byte[] s1 = AuthNativePassword.generateScramble(20);
        byte[] s2 = AuthNativePassword.generateScramble(20);
        boolean same = true;
        for (int i = 0; i < 20; i++) {
            if (s1[i] != s2[i]) { same = false; break; }
        }
        assertFalse(same, "Two random scrambles should not be identical");
    }

    @Test
    void responseShouldBe20Bytes() {
        byte[] scramble = AuthNativePassword.generateScramble(20);
        byte[] response = AuthNativePassword.computeAuthResponse(scramble, "password");
        assertEquals(20, response.length);
    }
}
