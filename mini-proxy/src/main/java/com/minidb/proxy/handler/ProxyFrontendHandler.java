package com.minidb.proxy.handler;

import com.minidb.proxy.ProxyConfig;
import com.minidb.proxy.parser.ParsedSql;
import com.minidb.proxy.parser.SqlParserImpl;
import com.minidb.proxy.parser.SqlType;
import com.minidb.proxy.pool.BackendConnection;
import com.minidb.proxy.pool.BackendConnectionPool;
import com.minidb.proxy.protocol.*;
import com.minidb.proxy.router.RoutePlan;
import com.minidb.proxy.router.SqlRouterImpl;
import com.minidb.proxy.session.ConnectionState;
import com.minidb.proxy.session.ProxySession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
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

    static final byte COM_QUERY = 0x03;

    private final ProxyConfig config;
    private final SqlParserImpl sqlParser;
    private final SqlRouterImpl router;
    private final BackendConnectionPool pool;

    public ProxyFrontendHandler(ProxyConfig config, SqlParserImpl sqlParser,
                                SqlRouterImpl router, BackendConnectionPool pool) {
        this.config = config;
        this.sqlParser = sqlParser;
        this.router = router;
        this.pool = pool;
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
                log.info("Client {} authenticated", ctx.channel().remoteAddress());
            } else {
                log.warn("Auth failed for '{}'", response.username());
                ctx.writeAndFlush(ResponsePackets.accessDenied((byte) 2, response.username()));
                ctx.channel().eventLoop().schedule((Runnable) ctx::close, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.error("Bad handshake", e);
            ctx.writeAndFlush(ResponsePackets.err((byte) 2, 1064, "Bad handshake"));
        } finally {
            packet.release();
        }
    }

    private void handleCommand(ChannelHandlerContext ctx, ProxySession session, MySqlPacket packet) {
        byte cmd = packet.payload().getByte(packet.payload().readerIndex());
        if (cmd != COM_QUERY) {
            ctx.writeAndFlush(ResponsePackets.unsupportedCommand((byte) (packet.sequenceId() + 1)));
            packet.release();
            return;
        }

        packet.payload().skipBytes(1); // skip command byte
        String sql = packet.payload().readCharSequence(
                packet.payload().readableBytes(), StandardCharsets.UTF_8).toString();
        packet.release();

        log.debug("COM_QUERY: {}", sql);

        ParsedSql parsed = sqlParser.parse(sql);
        RoutePlan plan;

        try {
            plan = router.route(session, parsed);
        } catch (SqlRouterImpl.CrossShardException e) {
            log.warn("Routing error: {}", e.getMessage());
            ctx.writeAndFlush(ResponsePackets.err((byte) 1, e.errorCode(), e.getMessage()));
            return;
        }

        // Transaction commands are handled purely in session
        if (plan.sessionOnly()) {
            handleTxCommand(ctx, session, parsed);
            return;
        }

        // Route to backend
        BackendConnection backendConn;
        try {
            backendConn = plan.requiresBackend()
                    ? pool.borrow(plan.dataSourceId())
                    : pool.borrow(session.boundConnection().dataSourceId());
        } catch (Exception e) {
            log.error("Failed to borrow backend connection to {}", plan.dataSourceId(), e);
            ctx.writeAndFlush(ResponsePackets.err((byte) 1, 1040,
                    "Too many connections: " + e.getMessage()));
            return;
        }

        // Inside transaction: bind if not yet bound
        if (session.inTransaction() && session.boundConnection() == null) {
            session.bind(plan.shardId() != null ? plan.shardId() : -1, backendConn);
            log.debug("Session {} bound to shard_{} on {}", session.sessionId(),
                    session.boundShardId(), backendConn.dataSourceId().name());
        }

        // Mark write time
        if (parsed.isWrite()) {
            session.markWrite();
        }

        // Relay SQL to backend
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        ByteBuf queryBuf = ctx.alloc().buffer(1 + sqlBytes.length);
        queryBuf.writeByte(COM_QUERY);
        queryBuf.writeBytes(sqlBytes);

        MySqlPacket queryPacket = new MySqlPacket(queryBuf.readableBytes(), (byte) 0,
                queryBuf.retain().slice(0, queryBuf.readableBytes()));
        queryBuf.release();

        boolean[] released = {false};

        // TODO: implement proper result set relay through backend channel pipeline
        // For now, relay SQL via backend and send a synthetic OK
        backendConn.writeAndFlush(sql).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("Backend write failed to {}", backendConn.dataSourceId().name(), future.cause());
                if (!released[0]) {
                    pool.invalidate(backendConn);
                    released[0] = true;
                }
                ctx.writeAndFlush(ResponsePackets.err((byte) 1, 2006,
                        "MySQL server has gone away"));
                return;
            }

            if (!session.inTransaction() && !released[0]) {
                pool.release(backendConn);
                released[0] = true;
            }
            // Send OK for non-read SQL, or we'd need real result set relay
            // TODO: result set relay in next step
            ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
        });
    }

    private void handleTxCommand(ChannelHandlerContext ctx, ProxySession session, ParsedSql sql) {
        SqlType type = sql.type();

        if (type == SqlType.BEGIN) {
            session.beginTransaction();
            ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
        } else if (type == SqlType.COMMIT || type == SqlType.ROLLBACK) {
            BackendConnection bound = session.boundConnection();
            if (bound != null) {
                bound.writeAndFlush(type == SqlType.COMMIT ? "COMMIT" : "ROLLBACK")
                        .addListener(f -> {
                            pool.release(bound);
                            session.unbind();
                            session.endTransaction();
                            ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
                        });
            } else {
                session.endTransaction();
                ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
            log.info("Client {} disconnected, session={}", ctx.channel().remoteAddress(), session.sessionId());
            session.setState(ConnectionState.CLOSING);

            // Rollback open transaction
            if (session.inTransaction() && session.boundConnection() != null) {
                session.boundConnection().writeAndFlush("ROLLBACK");
                pool.release(session.boundConnection());
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Exception on client channel {}", ctx.channel().remoteAddress(), cause);
        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null && session.inTransaction() && session.boundConnection() != null) {
            pool.invalidate(session.boundConnection());
        }
        ctx.close();
    }

    private void handleUnexpected(ChannelHandlerContext ctx, MySqlPacket packet) {
        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        log.warn("Unexpected packet in state {}", session != null ? session.state() : "UNKNOWN");
        ctx.writeAndFlush(ResponsePackets.err((byte) (packet.sequenceId() + 1), 1064, "Bad state"));
        packet.release();
    }
}
