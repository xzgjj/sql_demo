package com.minidb.proxy.pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Minimal backend connection pool using blocking queues.
 */
public class BackendConnectionPoolImpl implements BackendConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(BackendConnectionPoolImpl.class);

    private final Map<DataSourceId, BlockingQueue<BackendConnection>> idleQueues = new ConcurrentHashMap<>();
    private final Map<DataSourceId, Integer> maxSizes = new ConcurrentHashMap<>();
    private final Map<DataSourceId, Integer> activeCounts = new ConcurrentHashMap<>();
    private final Map<DataSourceId, BackendServerConfig> serverConfigs = new ConcurrentHashMap<>();

    private final long borrowTimeoutMs;
    private final long idleTimeoutMs;
    private final EventLoopGroup workerGroup;
    private volatile boolean closed;

    public BackendConnectionPoolImpl(long borrowTimeoutMs, long idleTimeoutMs, EventLoopGroup workerGroup) {
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.workerGroup = workerGroup;
    }

    public void registerDataSource(DataSourceId id, BackendServerConfig config, int maxSize) {
        serverConfigs.put(id, config);
        maxSizes.put(id, maxSize);
        idleQueues.computeIfAbsent(id, k -> new LinkedBlockingQueue<>());
        activeCounts.putIfAbsent(id, 0);
    }

    @Override
    public BackendConnection borrow(DataSourceId id) {
        if (closed) throw new IllegalStateException("Pool closed");

        BlockingQueue<BackendConnection> queue = idleQueues.get(id);
        if (queue == null) throw new IllegalArgumentException("Unknown DataSource: " + id);

        // Try idle connections first
        BackendConnection conn = queue.poll();
        if (conn != null) {
            if (conn.healthy()) {
                conn.markBorrowed();
                activeCounts.merge(id, 1, Integer::sum);
                log.debug("Reused idle connection to {}", id.name());
                return conn;
            }
            // stale — close and remove
            conn.close();
            activeCounts.merge(id, -1, Integer::sum);
        }

        // Maybe create a new connection
        int active = activeCounts.getOrDefault(id, 0);
        int max = maxSizes.getOrDefault(id, 16);
        if (active < max) {
            BackendConnection newConn = createConnection(id);
            if (newConn != null) {
                newConn.markBorrowed();
                activeCounts.merge(id, 1, Integer::sum);
                log.debug("Created new connection to {}", id.name());
                return newConn;
            }
        }

        // Wait for idle connection
        try {
            conn = queue.poll(borrowTimeoutMs, TimeUnit.MILLISECONDS);
            if (conn == null) {
                throw new RuntimeException("Connection pool exhausted for " + id.name() + " after " + borrowTimeoutMs + "ms");
            }
            if (!conn.healthy()) {
                conn.close();
                activeCounts.merge(id, -1, Integer::sum);
                return borrow(id); // retry
            }
            conn.markBorrowed();
            activeCounts.merge(id, 1, Integer::sum);
            return conn;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while borrowing connection to " + id.name(), e);
        }
    }

    @Override
    public void release(BackendConnection conn) {
        if (conn == null) return;
        DataSourceId id = conn.dataSourceId();
        BlockingQueue<BackendConnection> queue = idleQueues.get(id);
        if (queue == null) {
            conn.close();
            return;
        }
        conn.markUsed();
        activeCounts.merge(id, -1, Integer::sum);
        queue.offer(conn);
        log.debug("Released connection to {}", id.name());
    }

    @Override
    public void invalidate(BackendConnection conn) {
        if (conn == null) return;
        DataSourceId id = conn.dataSourceId();
        conn.markUnhealthy();
        conn.close();
        activeCounts.merge(id, -1, Integer::sum);
        log.info("Invalidated connection to {}", id.name());
    }

    public void drainAndClose() {
        closed = true;
        for (Map.Entry<DataSourceId, BlockingQueue<BackendConnection>> entry : idleQueues.entrySet()) {
            BackendConnection conn;
            while ((conn = entry.getValue().poll()) != null) {
                conn.close();
            }
        }
        log.info("Connection pool drained");
    }

    private BackendConnection createConnection(DataSourceId id) {
        BackendServerConfig cfg = serverConfigs.get(id);
        if (cfg == null) return null;

        ChannelFuture connectFuture = connect(cfg.host(), cfg.port());
        if (!connectFuture.awaitUninterruptibly(5000)) {
            log.error("Connection timeout to {}:{}", cfg.host(), cfg.port());
            return null;
        }
        if (!connectFuture.isSuccess()) {
            log.error("Failed to connect to {}:{}", cfg.host(), cfg.port(), connectFuture.cause());
            return null;
        }

        return new BackendConnection(id, connectFuture.channel());
    }

    private ChannelFuture connect(String host, int port) {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // minimal handler — just log events
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                log.debug("Backend connection closed to {}:{}", host, port);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                log.warn("Backend connection error to {}:{}", host, port, cause);
                                ctx.close();
                            }
                        });
                    }
                });
        return b.connect(host, port);
    }

    public record BackendServerConfig(String host, int port) {}
}
