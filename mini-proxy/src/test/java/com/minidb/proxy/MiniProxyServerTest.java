package com.minidb.proxy;

import com.minidb.proxy.handler.ProxyFrontendHandler;
import com.minidb.proxy.parser.SqlParserImpl;
import com.minidb.proxy.pool.BackendConnectionPool;
import com.minidb.proxy.pool.BackendConnectionPoolImpl;
import com.minidb.proxy.pool.DataSourceId;
import com.minidb.proxy.protocol.*;
import com.minidb.proxy.router.SqlRouterImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MiniProxyServerTest {

    private EmbeddedChannel channel;
    private EventLoopGroup workerGroup;
    private BackendConnectionPool pool;

    @BeforeEach
    void setUp() {
        workerGroup = new NioEventLoopGroup(1);
        pool = new BackendConnectionPoolImpl(5000, 600_000, workerGroup);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
        if (pool instanceof BackendConnectionPoolImpl impl) {
            impl.drainAndClose();
        }
        workerGroup.shutdownGracefully();
    }

    private ProxyFrontendHandler createHandler(ProxyConfig config) {
        return new ProxyFrontendHandler(config,
                new SqlParserImpl(),
                new SqlRouterImpl(config.shardCount(), config.readAfterWriteWindowMs()),
                pool);
    }

    @Test
    void shouldSendHandshakeOnConnect() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                createHandler(config)
        );

        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire, "Should send HandshakeV10 on connect");

        wire.skipBytes(3); // length
        wire.skipBytes(1); // seq id
        byte protocolVersion = wire.readByte();
        assertEquals(0x0a, protocolVersion);
        wire.release();
    }

    @Test
    void shouldAuthenticateWithCorrectPassword() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                createHandler(config)
        );

        ByteBuf handshakeWire = channel.readOutbound();
        assertNotNull(handshakeWire);
        byte[] scrambleFull = extractScramble(handshakeWire);
        handshakeWire.release();

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
        resultWire.skipBytes(3);
        resultWire.skipBytes(1);
        byte header = resultWire.readByte();
        assertEquals(0x00, header, "Should be OK packet");
        resultWire.release();
    }

    @Test
    void shouldRejectWrongPassword() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                createHandler(config)
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
        assertNotNull(resultWire);
        resultWire.skipBytes(3);
        resultWire.skipBytes(1);
        byte header = resultWire.readByte();
        assertEquals((byte) 0xFF, header, "Should be ERR packet");
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

        latch.await();
        if (error.get() != null) throw error.get();
        assertTrue(server.isRunning());
        server.shutdown();
    }

    @Test
    void shouldHandleBeginCommitCommands() {
        ProxyConfig config = ProxyConfig.defaults();
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder(),
                createHandler(config)
        );

        // consume handshake and extract scramble
        ByteBuf handshakeWire = channel.readOutbound();
        assertNotNull(handshakeWire);
        byte[] scramble = extractScramble(handshakeWire);
        handshakeWire.release();

        // send correct auth
        byte[] authResponse = AuthNativePassword.computeAuthResponse(scramble, config.proxyPassword());
        ByteBuf authPayload = buildHandshakeResponse("proxy", authResponse);
        MySqlPacket authPacket = new MySqlPacket(authPayload.readableBytes(), (byte) 1,
                authPayload.retain().slice(0, authPayload.readableBytes()));
        try {
            channel.writeInbound(authPacket);
        } finally {
            authPayload.release();
        }

        // consume auth OK
        ByteBuf authResult = channel.readOutbound();
        assertNotNull(authResult, "Should receive auth OK");
        authResult.release();

        // send BEGIN
        byte[] beginBytes = "BEGIN".getBytes(StandardCharsets.UTF_8);
        ByteBuf beginPayload = ByteBufAllocator.DEFAULT.buffer(1 + beginBytes.length);
        beginPayload.writeByte(0x03); // COM_QUERY
        beginPayload.writeBytes(beginBytes);

        MySqlPacket beginPacket = new MySqlPacket(beginPayload.readableBytes(), (byte) 0,
                beginPayload.retain().slice(0, beginPayload.readableBytes()));
        try {
            channel.writeInbound(beginPacket);
        } finally {
            beginPayload.release();
        }

        ByteBuf result = channel.readOutbound();
        assertNotNull(result, "Should respond to BEGIN");
        // skip 4-byte header (3 length + 1 seq) then read OK header
        result.readByte(); result.readByte(); result.readByte(); // length LE
        result.readByte(); // seq
        byte header = result.readByte();
        assertEquals(0x00, header, "BEGIN should get OK (header 0x00)");
        result.release();
    }

    private static byte[] extractScramble(ByteBuf wire) {
        wire.skipBytes(3);
        wire.skipBytes(1);
        wire.readByte();
        int nullIdx = indexOfNull(wire);
        wire.skipBytes(nullIdx + 1);
        wire.readIntLE();
        byte[] scramble = new byte[20];
        wire.readBytes(scramble, 0, 8);
        wire.readByte();
        wire.skipBytes(2 + 1 + 2 + 2 + 1 + 10);
        wire.readBytes(scramble, 8, 12);
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
        buf.writeBytes("mysql_native_password".getBytes(StandardCharsets.UTF_8));
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
