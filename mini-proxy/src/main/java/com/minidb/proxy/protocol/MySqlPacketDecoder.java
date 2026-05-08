package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decodes MySQL protocol packets from wire format.
 *
 * Strategy: wrap Netty's LengthFieldBasedFrameDecoder internally for frame extraction,
 * then parse the 4-byte header to produce MySqlPacket objects.
 */
public class MySqlPacketDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(MySqlPacketDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < MySqlPacket.HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();

        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int payloadLength = b0 | (b1 << 8) | (b2 << 16);
        byte sequenceId = in.readByte();

        if (payloadLength > MySqlPacket.MAX_PAYLOAD) {
            log.error("Packet payload too large: {} > {}", payloadLength, MySqlPacket.MAX_PAYLOAD);
            ctx.close();
            return;
        }

        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        ByteBuf payload = in.readRetainedSlice(payloadLength);
        out.add(new MySqlPacket(payloadLength, sequenceId, payload));
    }
}
