package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encodes a MySqlPacket to wire format: 3-byte LE payload length + 1-byte sequence ID + payload.
 */
public class MySqlPacketEncoder extends MessageToByteEncoder<MySqlPacket> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MySqlPacket msg, ByteBuf out) {
        int payloadLen = msg.payloadLength();
        out.writeByte(payloadLen & 0xFF);
        out.writeByte((payloadLen >>> 8) & 0xFF);
        out.writeByte((payloadLen >>> 16) & 0xFF);
        out.writeByte(msg.sequenceId());
        out.writeBytes(msg.payload(), 0, payloadLen);
    }
}
