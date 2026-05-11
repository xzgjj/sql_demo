package com.minidb.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class MiniProxyServer {

    private static final Logger log = LoggerFactory.getLogger(MiniProxyServer.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private BackendConnectionPoolImpl pool;
    private volatile boolean running;

    public MiniProxyServer(ProxyConfig config) {
        this.config = config;
    }

    private ProxyManagementServer managementServer;

    public void start() throws InterruptedException {
        // Validate credential configuration at startup
        var credWarnings = config.validateCredentials();
        if (!credWarnings.isEmpty()) {
            log.warn("Credential warnings ({} items):", credWarnings.size());
            credWarnings.forEach(w -> log.warn("  - {}", w));
        }

        boolean epoll = Epoll.isAvailable();
        int threads = Runtime.getRuntime().availableProcessors();
        bossGroup = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        workerGroup = epoll ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);

        // Route table lookup (JDBC-based, for two-phase routing via PRIMARY)
        RouteTableLookup routeTableLookup = new RouteTableLookup(
                config.backendHost(), config.primaryPort(),
                config.backendUsername(), config.backendPassword(),
                config.primaryDatabase(), config.backendConnectTimeoutMs());

        // Observability: route decision ring buffer (last 2000 entries)
        RouteDecisionLog decisionLog = new RouteDecisionLog(2000);

        // Shared components
        SqlParserImpl sqlParser = new SqlParserImpl();
        SqlRouterImpl router = new SqlRouterImpl(config.shardCount(),
                config.readAfterWriteWindowMs(), routeTableLookup);
        pool = new BackendConnectionPoolImpl(config.borrowTimeoutMs(), config.idleTimeoutMs(),
                config.backendConnectTimeoutMs(), workerGroup);

        // Register backend data sources from config (with credentials for auth)
        var backendCfg = new BackendConnectionPoolImpl.BackendServerConfig(
                config.backendHost(), config.primaryPort(),
                config.backendUsername(), config.backendPassword(), config.primaryDatabase());
        pool.registerDataSource(DataSourceId.PRIMARY, backendCfg, config.backendPoolMaxSize());
        pool.registerDataSource(DataSourceId.REPLICA,
                new BackendConnectionPoolImpl.BackendServerConfig(
                        config.backendHost(), config.replicaPort(),
                        config.backendUsername(), config.backendPassword(),
                        config.replicaDatabase()), 8);
        for (int i = 0; i < config.shardCount(); i++) {
            pool.registerDataSource(DataSourceId.shard(i),
                    new BackendConnectionPoolImpl.BackendServerConfig(
                            config.backendHost(), config.shardPort(i),
                            config.backendUsername(), config.backendPassword(),
                            config.shardDatabase(i)), 8);
        }

        pool.startEviction();

        // Start HTTP management server for observability
        managementServer = new ProxyManagementServer(config.listenPort() + 1, config, pool, decisionLog);
        managementServer.start();

        ProxyChannelInitializer channelInit = new ProxyChannelInitializer(config, sqlParser, router, pool, decisionLog);
        channelInit.setManagementServer(managementServer);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(channelInit)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

        b.bind(config.listenPort()).sync();
        running = true;
        log.info("mini-proxy started on port {}{}", config.listenPort(),
                epoll ? " (epoll)" : " (nio)");
    }

    public void shutdown() {
        running = false;
        if (managementServer != null) {
            managementServer.shutdown();
        }
        if (pool != null) {
            pool.drainAndClose();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(1, 30, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(1, 30, TimeUnit.SECONDS);
        }
        log.info("mini-proxy stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public static void main(String[] args) throws Exception {
        MiniProxyServer server = new MiniProxyServer(ProxyConfig.defaults());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    }
}
