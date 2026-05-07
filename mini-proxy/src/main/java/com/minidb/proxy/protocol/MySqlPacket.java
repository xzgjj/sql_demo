package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;

/**
 * MySQL protocol packet — 3-byte little-endian length + 1-byte sequence ID + payload.
 */
public record MySqlPacket(int payloadLength, byte sequenceId, ByteBuf payload) {

    public static final int HEADER_SIZE = 4;
    public static final int MAX_PAYLOAD = 0xFFFFFF; // 16MB - 1

    public void release() {
        if (payload != null && payload.refCnt() > 0) {
            payload.release();
        }
    }
}
