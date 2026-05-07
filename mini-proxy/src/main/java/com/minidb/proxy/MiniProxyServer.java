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
    private volatile boolean running;

    public MiniProxyServer(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        boolean epoll = Epoll.isAvailable();
        int threads = Runtime.getRuntime().availableProcessors();
        bossGroup = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        workerGroup = epoll ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ProxyChannelInitializer(config))
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
