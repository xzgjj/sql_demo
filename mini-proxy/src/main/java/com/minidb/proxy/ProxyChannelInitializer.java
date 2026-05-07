package com.minidb.proxy;

import com.minidb.proxy.handler.ProxyFrontendHandler;
import com.minidb.proxy.parser.SqlParserImpl;
import com.minidb.proxy.pool.BackendConnectionPool;
import com.minidb.proxy.protocol.MySqlPacketDecoder;
import com.minidb.proxy.protocol.MySqlPacketEncoder;
import com.minidb.proxy.router.SqlRouterImpl;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class ProxyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ProxyConfig config;
    private final SqlParserImpl sqlParser;
    private final SqlRouterImpl router;
    private final BackendConnectionPool pool;

    public ProxyChannelInitializer(ProxyConfig config, SqlParserImpl sqlParser,
                                    SqlRouterImpl router, BackendConnectionPool pool) {
        this.config = config;
        this.sqlParser = sqlParser;
        this.router = router;
        this.pool = pool;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast(new MySqlPacketDecoder())
                .addLast(new MySqlPacketEncoder())
                .addLast(new ProxyFrontendHandler(config, sqlParser, router, pool));
    }
}
