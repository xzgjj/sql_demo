package com.minidb.proxy;

import com.minidb.proxy.protocol.AuthNativePassword;
import com.minidb.proxy.protocol.HandshakeResponse41;
import com.minidb.proxy.protocol.HandshakeV10;
import com.minidb.proxy.protocol.MySqlPacket;
import com.minidb.proxy.protocol.ResponsePackets;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFrontendHandler.class);
    private static final AttributeKey<ProxySession> SESSION_KEY = AttributeKey.valueOf("session");
    private static final AttributeKey<byte[]> SCRAMBLE_KEY = AttributeKey.valueOf("scramble");

    static final byte COM_QUERY = 0x03;
    static final byte COM_PING = 0x0E;
    static final byte COM_QUIT = 0x01;

    private final ProxyConfig config;
    private final SqlParserImpl sqlParser;
    private final SqlRouterImpl router;
    private final BackendConnectionPool pool;
    private final RouteDecisionLog decisionLog;
    private ProxyManagementServer managementServer;

    public ProxyFrontendHandler(ProxyConfig config, SqlParserImpl sqlParser,
                                SqlRouterImpl router, BackendConnectionPool pool,
                                RouteDecisionLog decisionLog) {
        this.config = config;
        this.sqlParser = sqlParser;
        this.router = router;
        this.pool = pool;
        this.decisionLog = decisionLog;
    }

    public void setManagementServer(ProxyManagementServer mgmt) {
        this.managementServer = mgmt;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ProxySession session = new ProxySession(channel);
        channel.attr(SESSION_KEY).set(session);

        if (managementServer != null) {
            managementServer.registerSession(session);
        }

        byte[] scramble = AuthNativePassword.generateScramble(20);
        channel.attr(SCRAMBLE_KEY).set(scramble);

        MySqlPacket handshake = HandshakeV10.build(
                ThreadLocalRandom.current().nextInt(10000) + 1, scramble);
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

            if (scramble == null) {
                log.error("No scramble found for channel");
                ctx.writeAndFlush(ResponsePackets.err((byte) 2, 1064, "Internal error: no scramble"));
                return;
            }

            byte[] expected = AuthNativePassword.computeAuthResponse(scramble, config.proxyPassword());
            boolean ok = java.util.Arrays.equals(expected, response.authResponse());

            if (!ok) {
                log.debug("Auth: expected len={}, got len={}, authPlugin={}",
                        expected.length, response.authResponse().length, response.authPluginName());
            }

            if (ok) {
                session.setState(ConnectionState.READY);
                ctx.writeAndFlush(ResponsePackets.ok((byte) 2));
                log.info("Client {} authenticated", ctx.channel().remoteAddress());
            } else {
                log.warn("Auth failed for '{}'", response.username());
                ctx.writeAndFlush(ResponsePackets.accessDenied((byte) 2, response.username()));
                ctx.channel().eventLoop().schedule(
                        (Runnable) ctx::close, 100, TimeUnit.MILLISECONDS);
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

        // Handle non-COM_QUERY commands
        if (cmd == COM_PING) {
            ctx.writeAndFlush(ResponsePackets.ok((byte) (packet.sequenceId() + 1)));
            packet.release();
            return;
        }
        if (cmd == COM_QUIT) {
            packet.release();
            ctx.close();
            return;
        }
        if (cmd != COM_QUERY) {
            ctx.writeAndFlush(ResponsePackets.unsupportedCommand((byte) (packet.sequenceId() + 1)));
            packet.release();
            return;
        }

        packet.payload().skipBytes(1);
        String sql = packet.payload().readCharSequence(
                packet.payload().readableBytes(), StandardCharsets.UTF_8).toString();
        packet.release();

        log.debug("COM_QUERY: {}", sql);

        ParsedSql parsed = sqlParser.parse(sql);
        RoutePlan plan;
        long routeStart = System.currentTimeMillis();

        try {
            plan = router.route(session, parsed);
        } catch (SqlRouterImpl.CrossShardException e) {
            log.warn("Routing error: {}", e.getMessage());
            decisionLog.record(session.sessionId(), sqlPreview(sql), "NONE", "-",
                    "REJECTED", e.getMessage(), "ERR", System.currentTimeMillis() - routeStart);
            ctx.writeAndFlush(ResponsePackets.err((byte) 1, e.errorCode(), e.getMessage()));
            return;
        }

        if (plan.sessionOnly()) {
            handleTxCommand(ctx, session, parsed);
            return;
        }

        // Record route decision
        String target = plan.dataSourceId() != null ? plan.dataSourceId().name() : "session";
        String keyType = parsed.hasAltRouteKey() ? parsed.altRouteType().name()
                : parsed.shardKey() != null ? "USER_ID"
                : parsed.isPrimaryOnly() ? "PRIMARY_ONLY" : "DIRECT";
        String keyValue = parsed.shardKey() != null ? String.valueOf(parsed.shardKey())
                : parsed.hasAltRouteKey() ? parsed.altRouteKey() : "-";
        decisionLog.record(session.sessionId(), sqlPreview(sql), keyType, keyValue,
                target, "routed", "OK", System.currentTimeMillis() - routeStart);

        // Release any previous non-transactional connection before borrowing a new one
        if (!session.inTransaction()) {
            releaseAutoConnection(session);
        }

        BackendConnection backendConn;
        try {
            backendConn = plan.requiresBackend()
                    ? pool.borrow(plan.dataSourceId())
                    : pool.borrow(session.boundConnection().dataSourceId());
        } catch (Exception e) {
            log.error("Failed to borrow backend connection", e);
            ctx.writeAndFlush(ResponsePackets.err((byte) 1, 1040,
                    "Too many connections: " + e.getMessage()));
            return;
        }

        boolean needsBackendBegin = false;
        if (session.inTransaction() && session.boundConnection() == null) {
            session.bind(plan.shardId() != null ? plan.shardId() : -1, backendConn);
            needsBackendBegin = true;
            log.debug("Session {} bound to shard_{}", session.sessionId(), session.boundShardId());
        } else if (!session.inTransaction()) {
            session.setAutoConnection(backendConn);
        }

        if (parsed.isWrite()) {
            session.markWrite();
        }

        backendConn.setClientChannel(ctx.channel());
        if (needsBackendBegin) {
            backendConn.suppressNextResponse();
            backendConn.writeComQuery("BEGIN").addListener(beginFuture -> {
                if (!beginFuture.isSuccess()) {
                    handleBackendWriteFailure(ctx, session, backendConn, beginFuture.cause());
                    return;
                }
                backendConn.writeComQuery(sql).addListener(queryFuture ->
                        handleBackendQueryWriteResult(ctx, session, backendConn, queryFuture));
            });
            return;
        }
        backendConn.writeComQuery(sql).addListener(future ->
                handleBackendQueryWriteResult(ctx, session, backendConn, future));
    }

    private void handleBackendQueryWriteResult(ChannelHandlerContext ctx, ProxySession session,
                                               BackendConnection backendConn,
                                               io.netty.util.concurrent.Future<? super Void> future) {
            if (!future.isSuccess()) {
                handleBackendWriteFailure(ctx, session, backendConn, future.cause());
            }
            // Response relayed by BackendRelayHandler — no synthetic OK needed
    }

    private void handleBackendWriteFailure(ChannelHandlerContext ctx, ProxySession session,
                                           BackendConnection backendConn, Throwable cause) {
        log.error("Backend write failed to {}", backendConn.dataSourceId().name(), cause);
        pool.invalidate(backendConn);
        if (!session.inTransaction()) session.clearAutoConnection();
        ctx.writeAndFlush(ResponsePackets.err((byte) 1, 2006,
                "MySQL server has gone away"));
    }

    private void handleTxCommand(ChannelHandlerContext ctx, ProxySession session, ParsedSql sql) {
        SqlType type = sql.type();

        if (type == SqlType.BEGIN || isSetAutocommitOff(sql.originalSql())) {
            // Release auto connection before starting transaction
            releaseAutoConnection(session);
            session.beginTransaction();
            ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
            log.debug("Session {} BEGIN", session.sessionId());
        } else if (type == SqlType.COMMIT || type == SqlType.ROLLBACK) {
            String cmd = type == SqlType.COMMIT ? "COMMIT" : "ROLLBACK";
            BackendConnection bound = session.boundConnection();

            if (bound != null) {
                bound.writeComQuery(cmd).addListener(f -> {
                    if (!f.isSuccess()) {
                        log.error("{} write failed", cmd, f.cause());
                        pool.invalidate(bound);
                        session.unbind();
                        session.endTransaction();
                        ctx.writeAndFlush(ResponsePackets.err((byte) 1, 2006,
                                "MySQL server has gone away"));
                        return;
                    }
                    // BackendRelayHandler forwards the actual COMMIT/ROLLBACK response
                    // After relay, cleanup
                    ctx.channel().eventLoop().schedule((Runnable) () -> {
                        bound.clearClientChannel();
                        pool.release(bound);
                        session.unbind();
                        session.endTransaction();
                    }, 100, TimeUnit.MILLISECONDS);
                });
            } else {
                session.endTransaction();
                ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
            }
        } else {
            // SET/SHOW/OTHER inside transaction or standalone
            ctx.writeAndFlush(ResponsePackets.ok((byte) 1));
        }
    }

    private boolean isSetAutocommitOff(String sql) {
        if (sql == null) return false;
        String normalized = sql.trim().replaceAll("\\s+", " ").toUpperCase();
        return normalized.equals("SET AUTOCOMMIT=0")
                || normalized.equals("SET AUTOCOMMIT = 0");
    }

    private void releaseAutoConnection(ProxySession session) {
        BackendConnection autoConn = session.autoConnection();
        if (autoConn != null) {
            autoConn.clearClientChannel();
            pool.release(autoConn);
            session.clearAutoConnection();
        }
    }

    private static String sqlPreview(String sql) {
        if (sql == null) return "";
        return sql.length() > 80 ? sql.substring(0, 77) + "..." : sql;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
            if (managementServer != null) {
                managementServer.unregisterSession(session.sessionId());
            }
            log.info("Client {} disconnected, session={}", ctx.channel().remoteAddress(), session.sessionId());
            session.setState(ConnectionState.CLOSING);

            BackendConnection bound = session.boundConnection();
            if (bound != null) {
                if (session.inTransaction()) {
                    bound.writeComQuery("ROLLBACK");
                }
                bound.clearClientChannel();
                pool.release(bound);
                session.unbind();
            }
            releaseAutoConnection(session);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Exception on client channel {}", ctx.channel().remoteAddress(), cause);
        ProxySession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
            BackendConnection bound = session.boundConnection();
            if (bound != null) {
                bound.clearClientChannel();
                pool.invalidate(bound);
                session.unbind();
            }
            releaseAutoConnection(session);
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
