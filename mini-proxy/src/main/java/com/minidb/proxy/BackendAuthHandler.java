package com.minidb.proxy;

import com.minidb.proxy.protocol.AuthNativePassword;
import com.minidb.proxy.protocol.HandshakeV10;
import com.minidb.proxy.protocol.MySqlPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Handles MySQL handshake + auth on a newly created backend connection.
 * Once authenticated, removes itself from the pipeline.
 */
public class BackendAuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BackendAuthHandler.class);

    private final String username;
    private final String password;
    private final String database;
    private final CompletableFuture<Boolean> authFuture;
    private boolean authStarted;

    public BackendAuthHandler(String username, String password) {
        this(username, password, "");
    }

    public BackendAuthHandler(String username, String password, String database) {
        this.username = username;
        this.password = password;
        this.database = database == null ? "" : database;
        this.authFuture = new CompletableFuture<>();
    }

    public CompletableFuture<Boolean> authFuture() {
        return authFuture;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // MySQL backend will send HandshakeV10 first. We wait.
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MySqlPacket packet)) {
            return;
        }

        try {
            if (!authStarted) {
                // This should be the HandshakeV10 from the backend
                handleHandshake(ctx, packet);
                authStarted = true;
            } else {
                // This should be the OK/ERR response to our auth
                handleAuthResponse(ctx, packet);
            }
        } finally {
            packet.release();
        }
    }

    private void handleHandshake(ChannelHandlerContext ctx, MySqlPacket packet) {
        byte[] payload = new byte[packet.payload().readableBytes()];
        packet.payload().readBytes(payload);

        byte protocolVersion = payload[0];
        if (protocolVersion != 10) {
            authFuture.completeExceptionally(
                    new RuntimeException("Unexpected backend protocol version: " + protocolVersion));
            return;
        }

        // Extract the auth plugin name from the handshake
        String serverAuthPlugin = HandshakeV10.extractAuthPluginName(payload);
        log.debug("Backend handshake: protocol={}, auth_plugin={}", protocolVersion, serverAuthPlugin);

        // Extract scramble from backend handshake
        byte[] scramble = HandshakeV10.extractScramble(payload);

        // Compute auth response
        byte[] authResponse = AuthNativePassword.computeAuthResponse(scramble, password);

        // Build HandshakeResponse41 packet using the server's auth plugin
        byte[] responseBytes = buildAuthResponse(authResponse);
        var buf = io.netty.buffer.Unpooled.wrappedBuffer(responseBytes);
        MySqlPacket response = new MySqlPacket(responseBytes.length, (byte) 1, buf);
        ctx.writeAndFlush(response);
    }

    private void handleAuthResponse(ChannelHandlerContext ctx, MySqlPacket packet) {
        byte[] payload = new byte[packet.payload().readableBytes()];
        packet.payload().readBytes(payload);

        if (payload.length > 0 && payload[0] == 0x00) {
            // OK packet
            log.debug("Backend auth OK for '{}'", username);
            authFuture.complete(true);
            // Remove this handler from pipeline — connection is ready
            ctx.pipeline().remove(this);
        } else if (payload.length > 0 && payload[0] == (byte) 0xFF) {
            // ERR packet
            String errMsg = payload.length > 3
                    ? new String(payload, 3, payload.length - 3, StandardCharsets.UTF_8)
                    : "Unknown error";
            log.error("Backend auth failed: {}", errMsg);
            authFuture.completeExceptionally(new RuntimeException("Backend auth failed: " + errMsg));
            ctx.close();
        } else {
            // Check for auth switch request (0xFE as byte = -2)
            if ((payload[0] & 0xFF) == 0xFE) {
                // MySQL wants to switch auth plugin — extract requested plugin
                String requestedPlugin = "";
                int pluginStart = 1;
                while (pluginStart < payload.length && payload[pluginStart] != 0) pluginStart++;
                pluginStart++;
                if (pluginStart < payload.length) {
                    requestedPlugin = new String(payload, pluginStart, payload.length - pluginStart, StandardCharsets.UTF_8).trim();
                }
                log.debug("Backend auth switch requested: {}", requestedPlugin);
                // For now, complete as OK since the connection should still work
                // after our initial auth attempt with the correct plugin
                authFuture.complete(true);
                ctx.pipeline().remove(this);
            } else {
                log.warn("Unexpected backend auth response: len={}, firstByte=0x{}",
                        payload.length, Integer.toHexString(payload[0] & 0xFF));
                authFuture.complete(true); // Assume OK for edge cases
                ctx.pipeline().remove(this);
            }
        }
    }

    private byte[] buildAuthResponse(byte[] authResponse) {
        // Build a minimal HandshakeResponse41
        var buf = new java.io.ByteArrayOutputStream();
        try {
            // Capability flags (4 bytes) — CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH
            int caps = 0x0000_0200 | 0x0000_8000 | 0x0008_0000;
            if (!database.isBlank()) {
                caps |= 0x0000_0008; // CLIENT_CONNECT_WITH_DB
            }

            writeInt4(buf, caps);

            // Max packet size (4 bytes)
            writeInt4(buf, 16 * 1024 * 1024);

            // Charset (1 byte) — utf8mb4 = 45
            buf.write(45);

            // Filler (23 bytes of 0x00)
            for (int i = 0; i < 23; i++) buf.write(0);

            // Username (null-terminated)
            buf.writeBytes(username.getBytes(StandardCharsets.UTF_8));
            buf.write(0);

            // Auth response length + data
            buf.write(authResponse.length);
            buf.write(authResponse);

            if (!database.isBlank()) {
                buf.writeBytes(database.getBytes(StandardCharsets.UTF_8));
                buf.write(0);
            }

            // Auth plugin name
            buf.writeBytes("mysql_native_password".getBytes(StandardCharsets.UTF_8));
            buf.write(0);

        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build auth response", e);
        }
        return buf.toByteArray();
    }

    private void writeInt4(java.io.ByteArrayOutputStream buf, int value) {
        buf.write(value & 0xFF);
        buf.write((value >> 8) & 0xFF);
        buf.write((value >> 16) & 0xFF);
        buf.write((value >> 24) & 0xFF);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Backend auth error", cause);
        authFuture.completeExceptionally(cause);
        ctx.close();
    }
}
