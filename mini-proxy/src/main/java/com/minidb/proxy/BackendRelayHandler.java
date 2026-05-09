package com.minidb.proxy;

import com.minidb.proxy.protocol.MySqlPacket;
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
        if (connection.shouldSuppressResponse()) {
            log.debug("Backend relay: suppressing response");
            ReferenceCountUtil.release(msg);
            return;
        }
        Channel clientChannel = connection.clientChannel();
        if (clientChannel != null && clientChannel.isActive()) {
            if (msg instanceof MySqlPacket pkt) {
                int firstByte = pkt.payload().readableBytes() > 0 ? pkt.payload().getByte(pkt.payload().readerIndex()) & 0xFF : -1;
                log.debug("Backend relay: seq={}, len={}, firstByte=0x{}",
                        pkt.sequenceId(), pkt.payloadLength(),
                        firstByte >= 0 ? Integer.toHexString(firstByte) : "?");
            }
            clientChannel.writeAndFlush(msg, clientChannel.voidPromise());
        } else {
            log.debug("Backend relay: no client channel, releasing");
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
