package com.minidb.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded HTTP management server for proxy observability.
 * Exposes /health, /sessions, /pools, /decisions on a dedicated port.
 * Uses hand-rolled JSON to avoid adding Jackson dependency to proxy module.
 */
public class ProxyManagementServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyManagementServer.class);

    private final int port;
    private final ProxyConfig config;
    private final BackendConnectionPoolImpl pool;
    private final RouteDecisionLog decisionLog;
    private final ConcurrentHashMap<String, ProxySession> sessions;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public ProxyManagementServer(int port, ProxyConfig config, BackendConnectionPoolImpl pool,
                                  RouteDecisionLog decisionLog) {
        this.port = port;
        this.config = config;
        this.pool = pool;
        this.decisionLog = decisionLog;
        this.sessions = new ConcurrentHashMap<>();
    }

    public void registerSession(ProxySession session) {
        sessions.put(session.sessionId(), session);
    }

    public void unregisterSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public void start() throws InterruptedException {
        boolean epoll = Epoll.isAvailable();
        bossGroup = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        workerGroup = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(65536))
                                .addLast(new ManagementHandler());
                    }
                });
        b.bind(port).sync();
        log.info("Proxy management HTTP server started on port {}", port);
    }

    public void shutdown() {
        if (bossGroup != null) bossGroup.shutdownGracefully(1, 5, java.util.concurrent.TimeUnit.SECONDS);
        if (workerGroup != null) workerGroup.shutdownGracefully(1, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private class ManagementHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String path = req.uri();
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            try {
                String json = switch (path) {
                    case "/health" -> healthJson();
                    case "/sessions" -> sessionsJson();
                    case "/pools" -> poolsJson();
                    case "/decisions" -> decisionsJson(req.uri());
                    default -> "{\"error\":\"not found\",\"paths\":[\"/health\",\"/sessions\",\"/pools\",\"/decisions\"]}";
                };
                writeJson(ctx, json);
            } catch (Exception e) {
                log.error("Management API error", e);
                writeJson(ctx, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private String healthJson() {
            return "{\"status\":\"UP\",\"listenPort\":" + config.listenPort()
                    + ",\"shardCount\":" + config.shardCount() + "}";
        }

        private String sessionsJson() {
            StringBuilder sb = new StringBuilder("{\"sessions\":[");
            var sessionList = List.copyOf(sessions.values());
            for (int i = 0; i < sessionList.size(); i++) {
                if (i > 0) sb.append(',');
                ProxySession s = sessionList.get(i);
                sb.append("{\"sessionId\":\"").append(s.sessionId()).append('"');
                sb.append(",\"clientAddr\":\"").append(s.clientChannel().remoteAddress() != null
                        ? escapeJson(s.clientChannel().remoteAddress().toString()) : "-").append('"');
                sb.append(",\"inTransaction\":").append(s.inTransaction());
                sb.append(",\"boundShardId\":").append(s.boundShardId());
                sb.append(",\"hasBackendConnection\":").append(s.boundConnection() != null);
                sb.append('}');
            }
            sb.append("],\"count\":").append(sessionList.size()).append('}');
            return sb.toString();
        }

        private String poolsJson() {
            StringBuilder sb = new StringBuilder("{\"pools\":{");
            var dsList = List.copyOf(pool.registeredDataSources());
            for (int i = 0; i < dsList.size(); i++) {
                if (i > 0) sb.append(',');
                DataSourceId id = dsList.get(i);
                sb.append('"').append(id.name()).append("\":{");
                sb.append("\"active\":").append(pool.activeCount(id));
                sb.append(",\"idle\":").append(pool.idleCount(id));
                sb.append(",\"max\":").append(pool.maxSize(id));
                sb.append(",\"healthy\":").append(pool.isHealthy(id));
                sb.append('}');
            }
            sb.append("},\"totalActive\":").append(pool.totalActive()).append('}');
            return sb.toString();
        }

        private String decisionsJson(String uri) {
            String sessionId = null;
            if (uri.contains("sessionId=")) {
                int start = uri.indexOf("sessionId=") + 10;
                int end = uri.indexOf('&', start);
                sessionId = end > 0 ? uri.substring(start, end) : uri.substring(start);
            }
            int limit = Math.min(intParam(uri, "limit", 50), 200);
            List<RouteDecisionLog.Entry> entries = sessionId != null
                    ? decisionLog.bySession(sessionId, limit)
                    : decisionLog.recent(limit);

            StringBuilder sb = new StringBuilder("{\"decisions\":[");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(',');
                RouteDecisionLog.Entry e = entries.get(i);
                sb.append("{\"sessionId\":\"").append(e.sessionId()).append('"');
                sb.append(",\"sql\":\"").append(escapeJson(e.sqlPreview())).append('"');
                sb.append(",\"keyType\":\"").append(e.keyType()).append('"');
                sb.append(",\"keyValue\":\"").append(e.keyValue()).append('"');
                sb.append(",\"target\":\"").append(e.target()).append('"');
                sb.append(",\"reason\":\"").append(escapeJson(e.reason())).append('"');
                sb.append(",\"status\":\"").append(e.status()).append('"');
                sb.append(",\"elapsedMs\":").append(e.elapsedMs());
                sb.append('}');
            }
            sb.append("],\"count\":").append(entries.size()).append('}');
            return sb.toString();
        }

        private int intParam(String uri, String key, int fallback) {
            String k = key + "=";
            int idx = uri.indexOf(k);
            if (idx < 0) return fallback;
            int start = idx + k.length();
            int end = uri.indexOf('&', start);
            try {
                return Integer.parseInt(end > 0 ? uri.substring(start, end) : uri.substring(start));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private void writeJson(ChannelHandlerContext ctx, String json) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ByteBuf content = Unpooled.wrappedBuffer(bytes);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }

        private static String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }
    }
}
