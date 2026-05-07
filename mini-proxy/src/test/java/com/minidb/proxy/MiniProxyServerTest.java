package com.minidb.proxy;

import com.minidb.proxy.handler.ProxyFrontendHandler;
import com.minidb.proxy.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MiniProxyServerTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldSendHandshakeOnConnect() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                new ProxyFrontendHandler(config)
        );

        // Outbound goes through encoder → ByteBuf
        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire, "Should send HandshakeV10 on connect");

        // Decode wire format: 4-byte header + payload
        wire.skipBytes(3); // length LE
        wire.skipBytes(1); // seq id
        byte protocolVersion = wire.readByte();
        assertEquals(0x0a, protocolVersion, "Protocol version should be 10");
        wire.release();
    }

    @Test
    void shouldAuthenticateWithCorrectPassword() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                new ProxyFrontendHandler(config)
        );

        // consume and parse handshake to extract scramble
        ByteBuf handshakeWire = channel.readOutbound();
        assertNotNull(handshakeWire);
        byte[] scrambleFull = extractScramble(handshakeWire);
        handshakeWire.release();

        // build correct auth response
        byte[] authResponse = AuthNativePassword.computeAuthResponse(scrambleFull, config.proxyPassword());
        ByteBuf responsePayload = buildHandshakeResponse("proxy", authResponse);

        MySqlPacket response = new MySqlPacket(responsePayload.readableBytes(), (byte) 1,
                responsePayload.retain().slice(0, responsePayload.readableBytes()));
        try {
            channel.writeInbound(response);
        } finally {
            responsePayload.release();
        }

        ByteBuf resultWire = channel.readOutbound();
        assertNotNull(resultWire, "Should send OK after correct auth");
        // decode wire: header(4) + payload
        resultWire.skipBytes(3); // length LE
        resultWire.skipBytes(1); // seq id
        byte header = resultWire.readByte();
        assertEquals(0x00, header, "Should be OK packet (header 0x00)");
        resultWire.release();
    }

    @Test
    void shouldRejectWrongPassword() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                new ProxyFrontendHandler(config)
        );

        ByteBuf handshakeWire = channel.readOutbound();
        assertNotNull(handshakeWire);
        handshakeWire.release();

        byte[] wrongResponse = new byte[20];
        ByteBuf responsePayload = buildHandshakeResponse("proxy", wrongResponse);

        MySqlPacket response = new MySqlPacket(responsePayload.readableBytes(), (byte) 1,
                responsePayload.retain().slice(0, responsePayload.readableBytes()));
        try {
            channel.writeInbound(response);
        } finally {
            responsePayload.release();
        }

        ByteBuf resultWire = channel.readOutbound();
        assertNotNull(resultWire, "Should send ERR after wrong auth");
        resultWire.skipBytes(3);
        resultWire.skipBytes(1);
        byte header = resultWire.readByte();
        assertEquals((byte) 0xFF, header, "Should be ERR packet (header 0xFF)");
        resultWire.release();
    }

    @Test
    void shouldBindToPort() throws Exception {
        ProxyConfig config = new ProxyConfig(13307, "proxy", "proxy123", 16, 5000, 600_000, 3000, 2);
        MiniProxyServer server = new MiniProxyServer(config);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                server.start();
                latch.countDown();
            } catch (Exception e) {
                error.set(e);
            }
        });
        t.setDaemon(true);
        t.start();

        latch.await(); // wait for bind to complete
        if (error.get() != null) {
            throw error.get();
        }

        assertTrue(server.isRunning(), "Server should be running");
        server.shutdown();
    }

    /** Extract the 20-byte scramble from the HandshakeV10 wire format. */
    private static byte[] extractScramble(ByteBuf wire) {
        wire.skipBytes(3); // length
        wire.skipBytes(1); // seq id
        wire.readByte();   // protocol version
        // skip null-term server version
        int nullIdx = indexOfNull(wire);
        wire.skipBytes(nullIdx + 1);
        wire.readIntLE();  // connectionId
        byte[] scramble = new byte[20];
        wire.readBytes(scramble, 0, 8); // part1
        wire.readByte();   // filler
        // skip: lowerFlags(2) + charset(1) + statusFlags(2) + upperFlags(2) + authLen(1) + reserved(10)
        wire.skipBytes(2 + 1 + 2 + 2 + 1 + 10);
        wire.readBytes(scramble, 8, 12); // part2
        return scramble;
    }

    private static ByteBuf buildHandshakeResponse(String username, byte[] authResponse) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        buf.writeIntLE(CapabilityFlags.CLIENT_BASIC_FLAGS);
        buf.writeIntLE(0x1000000);
        buf.writeByte(0x21);
        buf.writeZero(23);
        buf.writeByte(username.length());
        buf.writeBytes(username.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(authResponse.length);
        buf.writeBytes(authResponse);
        buf.writeByte(0x00);
        byte[] plugin = "mysql_native_password".getBytes(StandardCharsets.UTF_8);
        buf.writeBytes(plugin);
        return buf;
    }

    private static int indexOfNull(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = start + buf.readableBytes();
        for (int i = start; i < end; i++) {
            if (buf.getByte(i) == 0x00) return i - start;
        }
        return buf.readableBytes();
    }
}
