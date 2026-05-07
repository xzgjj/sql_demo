package com.minidb.proxy.handler;

import com.minidb.proxy.pool.BackendConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays raw ByteBuf from backend MySQL channel directly to the client channel.
 * ByteBuf goes through the client's outbound pipeline — MySqlPacketEncoder passes
 * non-MySqlPacket objects through unchanged, so raw MySQL protocol bytes reach the wire.
 */
public class BackendRelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BackendRelayHandler.class);

    private final BackendConnection connection;

    public BackendRelayHandler(BackendConnection connection) {
        this.connection = connection;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel clientChannel = connection.clientChannel();
        if (clientChannel != null && clientChannel.isActive()) {
            clientChannel.writeAndFlush(msg, clientChannel.voidPromise());
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connection.markUnhealthy();
        log.debug("Backend connection to {} closed", connection.dataSourceId().name());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Backend connection error to {}", connection.dataSourceId().name(), cause);
        connection.markUnhealthy();
        ctx.close();
    }
}
