package com.minidb.proxy;

import com.minidb.proxy.handler.ProxyFrontendHandler;
import com.minidb.proxy.protocol.MySqlPacketDecoder;
import com.minidb.proxy.protocol.MySqlPacketEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class ProxyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ProxyConfig config;

    public ProxyChannelInitializer(ProxyConfig config) {
        this.config = config;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast(new MySqlPacketDecoder())
                .addLast(new MySqlPacketEncoder())
                .addLast(new ProxyFrontendHandler(config));
    }
}
