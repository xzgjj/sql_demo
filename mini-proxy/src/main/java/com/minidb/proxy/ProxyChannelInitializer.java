package com.minidb.proxy;

import com.minidb.proxy.protocol.MySqlPacketDecoder;
import com.minidb.proxy.protocol.MySqlPacketEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class ProxyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ProxyConfig config;
    private final SqlParserImpl sqlParser;
    private final SqlRouterImpl router;
    private final BackendConnectionPool pool;
    private final RouteDecisionLog decisionLog;
    private volatile ProxyManagementServer managementServer;

    public ProxyChannelInitializer(ProxyConfig config, SqlParserImpl sqlParser,
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
    protected void initChannel(SocketChannel ch) {
        ProxyFrontendHandler handler = new ProxyFrontendHandler(config, sqlParser, router, pool, decisionLog);
        if (managementServer != null) {
            handler.setManagementServer(managementServer);
        }
        ch.pipeline()
                .addLast(new MySqlPacketDecoder())
                .addLast(new MySqlPacketEncoder())
                .addLast(handler);
    }
}
