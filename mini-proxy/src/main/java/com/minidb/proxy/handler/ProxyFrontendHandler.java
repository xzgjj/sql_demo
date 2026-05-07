package com.minidb.proxy.handler;

import com.minidb.proxy.ProxyConfig;
import com.minidb.proxy.protocol.*;
import com.minidb.proxy.session.ConnectionState;
import com.minidb.proxy.session.ProxySession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFrontendHandler.class);
    private static final AttributeKey<ProxySession> SESSION_KEY = AttributeKey.valueOf("session");
    private static final AttributeKey<byte[]> SCRAMBLE_KEY = AttributeKey.valueOf("scramble");

    public static final byte COM_QUERY = 0x03;

    private final ProxyConfig config;

    public ProxyFrontendHandler(ProxyConfig config) {
        this.config = config;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ProxySession session = new ProxySession(channel);
        channel.attr(SESSION_KEY).set(session);

        byte[] scramble = AuthNativePassword.generateScramble(20);
        channel.attr(SCRAMBLE_KEY).set(scramble);

        MySqlPacket handshake = HandshakeV10.build(1 + (int) (Math.random() * 10000), scramble);
        ctx.writeAndFlush(handshake);

        log.debug("Handshake sent to {}", channel.remoteAddress());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MySqlPacket packet)) {
            return;
        }

        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        if (session == null) {
            packet.release();
            return;
        }

        switch (session.state()) {
            case HANDSHAKING -> handleHandshakeResponse(ctx, session, packet);
            case READY -> handleCommand(ctx, session, packet);
            default -> handleUnexpected(ctx, packet);
        }
    }

    private void handleHandshakeResponse(ChannelHandlerContext ctx, ProxySession session, MySqlPacket packet) {
        try {
            HandshakeResponse41 response = HandshakeV10.parseResponse(packet.payload());
            byte[] scramble = ctx.channel().attr(SCRAMBLE_KEY).get();

            boolean ok = AuthNativePassword.verify(scramble, config.proxyPassword(), response.authResponse());

            if (ok) {
                session.setState(ConnectionState.READY);
                ctx.writeAndFlush(ResponsePackets.ok((byte) 2));
                log.info("Client {} authenticated as {}", ctx.channel().remoteAddress(), response.username());
            } else {
                log.warn("Authentication failed for user '{}'", response.username());
                ctx.writeAndFlush(ResponsePackets.accessDenied((byte) 2, response.username()));
                // close after sending error
                ctx.channel().eventLoop().schedule(() -> ctx.close(),
                        100, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.error("Error parsing handshake response", e);
            ctx.writeAndFlush(ResponsePackets.err((byte) 2, 1064, "Bad handshake"));
        } finally {
            packet.release();
        }
    }

    private void handleCommand(ChannelHandlerContext ctx, ProxySession session, MySqlPacket packet) {
        // TODO: full command dispatch in next batch
        byte cmd = packet.payload().getByte(packet.payload().readerIndex());
        if (cmd == COM_QUERY) {
            packet.payload().skipBytes(1); // skip command byte
            String sql = packet.payload().readCharSequence(
                    packet.payload().readableBytes(), StandardCharsets.UTF_8).toString();
            log.info("COM_QUERY from {}: {}", session.sessionId(), sql);

            // Placeholder: send OK for SELECT 1
            ctx.writeAndFlush(ResponsePackets.ok((byte) (packet.sequenceId() + 1)));
        } else {
            ctx.writeAndFlush(ResponsePackets.unsupportedCommand((byte) (packet.sequenceId() + 1)));
        }
        packet.release();
    }

    private void handleUnexpected(ChannelHandlerContext ctx, MySqlPacket packet) {
        log.warn("Unexpected packet in state {}", ctx.channel().attr(SESSION_KEY).get().state());
        ctx.writeAndFlush(ResponsePackets.err((byte) (packet.sequenceId() + 1), 1064, "Bad state"));
        packet.release();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
            log.info("Client {} disconnected, session={}", ctx.channel().remoteAddress(), session.sessionId());
            session.setState(ConnectionState.CLOSING);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Exception on client channel", cause);
        ctx.close();
    }
}
