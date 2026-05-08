package com.minidb.proxy;

import com.minidb.proxy.MiniProxyServer;
import com.minidb.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Manual integration test. Requires a running MySQL on localhost:3306.
 * Start: docker compose up -d mysql-primary
 * Then remove @Disabled and run this test.
 */
class ProxyIntegrationTest {

    private MiniProxyServer proxy;
    private Thread proxyThread;

    @BeforeEach
    void setUp() throws Exception {
        ProxyConfig config = new ProxyConfig(13307, "proxy", "proxy123",
                4, 3000, 600_000, 3000, 1, 5000, "127.0.0.1", 3307);

        proxy = new MiniProxyServer(config);

        CountDownLatch latch = new CountDownLatch(1);
        proxyThread = new Thread(() -> {
            try {
                proxy.start();
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        proxyThread.setDaemon(true);
        proxyThread.start();
        latch.await(5, TimeUnit.SECONDS);
        assertTrue(proxy.isRunning(), "Mini-proxy should be running");
    }

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    @Disabled("Testcontainers auto-detection incompatible with WSL2 Docker Desktop. Manual test: docker compose up -d && mysql -h 127.0.0.1 -P 13307 -u proxy -p")
    void shouldCompleteHandshakeAndSelectOne() throws Exception {
        // Connect to mini-proxy
        Socket socket = new Socket("127.0.0.1", 13307);
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // 1. Read HandshakeV10
        byte[] handshake = readMySqlPacket(in);
        ByteArrayInputStream bis = new ByteArrayInputStream(handshake);
        int protocolVersion = bis.read();
        assertEquals(0x0a, protocolVersion);

        // Skip server version (null-terminated)
        skipNullTerm(bis);
        bis.skip(4); // connectionId

        // Read scramble: part1 (8) + filler (1) + skip lowerFlags(2)+charset(1)+status(2)+upper(2)+authLen(1)+reserved(10) + part2 (12) + terminator
        byte[] scramble = new byte[20];
        bis.read(scramble, 0, 8);
        bis.skip(1); // filler
        bis.skip(2 + 1 + 2 + 2 + 1 + 10); // skip to part2
        bis.read(scramble, 8, 12);

        // 2. Build and send HandshakeResponse41
        byte[] authResponse = computeAuthResponse(scramble, "proxy123");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // capability flags (4 LE) + maxPacket (4 LE) + charset (1) + reserved (23 zeroes)
        writeIntLE(baos, 0x01ABDF); // basic flags
        writeIntLE(baos, 0x1000000);
        baos.write(0x21); // utf8mb4
        for (int i = 0; i < 23; i++) baos.write(0x00);
        // username
        baos.write(5);
        baos.write("proxy".getBytes(StandardCharsets.UTF_8));
        // auth response
        baos.write(20);
        baos.write(authResponse);
        // null + plugin name
        baos.write(0x00);
        baos.write("mysql_native_password".getBytes(StandardCharsets.UTF_8));

        byte[] handshakeResponse = baos.toByteArray();
        writeMySqlPacket(out, (byte) 1, handshakeResponse);
        out.flush();

        // 3. Read auth result
        byte[] authResult = readMySqlPacket(in);
        assertEquals(0x00, authResult[0], "Auth should succeed (OK packet)");

        // 4. Send COM_QUERY "SELECT 1"
        ByteArrayOutputStream queryBaos = new ByteArrayOutputStream();
        queryBaos.write(0x03); // COM_QUERY
        queryBaos.write("SELECT 1".getBytes(StandardCharsets.UTF_8));
        byte[] queryPayload = queryBaos.toByteArray();
        writeMySqlPacket(out, (byte) 0, queryPayload);
        out.flush();

        // 5. Read response
        byte[] result = readMySqlPacket(in);
        // Should be OK (0x00) or column count (0x01) — depends on relay implementation
        assertNotNull(result);
        assertTrue(result.length > 0);

        socket.close();
    }

    // === MySQL Packet I/O helpers ===

    private static byte[] readMySqlPacket(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int length = b0 | (b1 << 8) | (b2 << 16);
        in.read(); // seq id
        byte[] payload = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(payload, offset, length - offset);
            if (n < 0) throw new EOFException("Unexpected end of stream");
            offset += n;
        }
        return payload;
    }

    private static void writeMySqlPacket(OutputStream out, byte seqId, byte[] payload) throws IOException {
        int len = payload.length;
        out.write(len & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write(seqId);
        out.write(payload);
    }

    private static void skipNullTerm(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) > 0) {}
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static byte[] computeAuthResponse(byte[] scramble, String password) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
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
}
