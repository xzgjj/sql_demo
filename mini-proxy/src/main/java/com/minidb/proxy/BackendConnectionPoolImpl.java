package com.minidb.proxy;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal backend connection pool using blocking queues with idle eviction.
 */
public class BackendConnectionPoolImpl implements BackendConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(BackendConnectionPoolImpl.class);

    private final Map<DataSourceId, BlockingQueue<BackendConnection>> idleQueues = new ConcurrentHashMap<>();
    private final Map<DataSourceId, Integer> maxSizes = new ConcurrentHashMap<>();
    private final Map<DataSourceId, AtomicInteger> activeCounts = new ConcurrentHashMap<>();
    private final Map<DataSourceId, BackendServerConfig> serverConfigs = new ConcurrentHashMap<>();

    private final long borrowTimeoutMs;
    private final long idleTimeoutMs;
    private final int connectTimeoutMs;
    private final EventLoopGroup workerGroup;
    private volatile boolean closed;
    private ScheduledExecutorService evictionScheduler;

    public BackendConnectionPoolImpl(long borrowTimeoutMs, long idleTimeoutMs,
                                      int connectTimeoutMs, EventLoopGroup workerGroup) {
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.workerGroup = workerGroup;
    }

    public void registerDataSource(DataSourceId id, BackendServerConfig config, int maxSize) {
        serverConfigs.put(id, config);
        maxSizes.put(id, maxSize);
        idleQueues.computeIfAbsent(id, k -> new LinkedBlockingQueue<>());
        activeCounts.putIfAbsent(id, new AtomicInteger(0));
    }

    public void startEviction() {
        if (idleTimeoutMs <= 0) return;
        evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pool-evictor");
            t.setDaemon(true);
            return t;
        });
        evictionScheduler.scheduleWithFixedDelay(this::evictIdleConnections,
                idleTimeoutMs / 2, idleTimeoutMs / 2, TimeUnit.MILLISECONDS);
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
                incActiveCount(id);
                log.debug("Reused idle connection to {}", id.name());
                return conn;
            }
            conn.close();
            decActiveCount(id);
        }

        // Maybe create a new connection
        int active = activeCounts.getOrDefault(id, new AtomicInteger(0)).get();
        int max = maxSizes.getOrDefault(id, 16);
        if (active < max) {
            BackendConnection newConn = createConnection(id);
            if (newConn != null) {
                newConn.markBorrowed();
                incActiveCount(id);
                log.debug("Created new connection to {}", id.name());
                return newConn;
            }
        }

        // Wait for idle connection with bounded retry
        for (int retries = 0; retries < 3; retries++) {
            try {
                conn = queue.poll(borrowTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while borrowing connection to " + id.name(), e);
            }
            if (conn == null) {
                throw new RuntimeException("Connection pool exhausted for " + id.name());
            }
            if (conn.healthy()) {
                conn.markBorrowed();
                incActiveCount(id);
                return conn;
            }
            conn.close();
            decActiveCount(id);
        }
        throw new RuntimeException("No healthy connection available for " + id.name() + " after retries");
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
        decActiveCount(id);
        queue.offer(conn);
        log.debug("Released connection to {}", id.name());
    }

    @Override
    public void invalidate(BackendConnection conn) {
        if (conn == null) return;
        DataSourceId id = conn.dataSourceId();
        conn.markUnhealthy();
        conn.close();
        decActiveCount(id);
        log.info("Invalidated connection to {}", id.name());
    }

    public void drainAndClose() {
        closed = true;
        if (evictionScheduler != null) {
            evictionScheduler.shutdown();
        }
        for (Map.Entry<DataSourceId, BlockingQueue<BackendConnection>> entry : idleQueues.entrySet()) {
            BackendConnection conn;
            while ((conn = entry.getValue().poll()) != null) {
                conn.close();
            }
        }
        log.info("Connection pool drained");
    }

    private void evictIdleConnections() {
        long now = System.currentTimeMillis();
        for (var entry : idleQueues.entrySet()) {
            entry.getValue().removeIf(conn -> {
                if (now - conn.lastUsedAt() > idleTimeoutMs) {
                    conn.close();
                    decActiveCount(entry.getKey());
                    log.debug("Evicted idle connection to {}", entry.getKey().name());
                    return true;
                }
                return false;
            });
        }
    }

    private void incActiveCount(DataSourceId id) {
        AtomicInteger c = activeCounts.get(id);
        if (c != null) c.incrementAndGet();
    }

    private void decActiveCount(DataSourceId id) {
        AtomicInteger c = activeCounts.get(id);
        if (c != null) {
            // floor at 0 to prevent underflow
            c.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
    }

    private BackendConnection createConnection(DataSourceId id) {
        BackendServerConfig cfg = serverConfigs.get(id);
        if (cfg == null) return null;

        ChannelFuture connectFuture = connect(cfg.host(), cfg.port());
        if (!connectFuture.awaitUninterruptibly(connectTimeoutMs)) {
            log.error("Connection timeout to {}:{}", cfg.host(), cfg.port());
            return null;
        }
        if (!connectFuture.isSuccess()) {
            log.error("Failed to connect to {}:{}", cfg.host(), cfg.port(), connectFuture.cause());
            return null;
        }

        // Add MySQL packet codec for backend connection
        var channel = connectFuture.channel();
        if (channel.pipeline().get("backend-codec") == null) {
            channel.pipeline().addLast("backend-codec",
                    new com.minidb.proxy.protocol.MySqlPacketDecoder());
        }

        // Perform MySQL handshake + auth with backend
        BackendAuthHandler authHandler = new BackendAuthHandler(
                cfg.username(), cfg.password());
        channel.pipeline().addLast("backend-auth", authHandler);

        try {
            Boolean ok = authHandler.authFuture().get(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!ok) {
                channel.close();
                return null;
            }
        } catch (Exception e) {
            log.error("Backend auth failed to {}:{}", cfg.host(), cfg.port(), e);
            channel.close();
            return null;
        }

        // Auth succeeded, BackendAuthHandler has removed itself from pipeline.
        return new BackendConnection(id, channel);
    }

    private ChannelFuture connect(String host, int port) {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        return b.connect(host, port);
    }

    public record BackendServerConfig(String host, int port, String username, String password) {
        public BackendServerConfig(String host, int port) {
            this(host, port, "root", "root123");
        }
    }
}
