package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;

/**
 * Builds MySQL OK and ERR packets.
 */
public final class ResponsePackets {

    private ResponsePackets() {}

    /**
     * Build an OK packet.
     * header: 0x00 | affectedRows (lenenc) | lastInsertId (lenenc) | statusFlags (2 LE) | warnings (2 LE) | info (string)
     */
    public static MySqlPacket ok(byte sequenceId, long affectedRows, long lastInsertId, int statusFlags, String info) {
        ByteBuf payload = ByteBufAllocator.DEFAULT.buffer(32);
        try {
            payload.writeByte(0x00); // OK header
            writeLenEncInt(payload, affectedRows);
            writeLenEncInt(payload, lastInsertId);
            payload.writeShortLE(statusFlags);
            payload.writeShortLE(0); // warnings
            if (info != null && !info.isEmpty()) {
                payload.writeBytes(info.getBytes(StandardCharsets.UTF_8));
            }
            return new MySqlPacket(payload.readableBytes(), sequenceId,
                    payload.retain().slice(0, payload.readableBytes()));
        } finally {
            payload.release();
        }
    }

    /** Build an OK packet with 0 affected rows, 0 last insert id, autocommit status. */
    public static MySqlPacket ok(byte sequenceId) {
        return ok(sequenceId, 0, 0, 0x0002, null);
    }

    /**
     * Build an ERR packet.
     * header: 0xFF | errorCode (2 LE) | sqlStateMarker '#' | sqlState (5) | message
     */
    public static MySqlPacket err(byte sequenceId, int errorCode, String sqlState, String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuf payload = ByteBufAllocator.DEFAULT.buffer(16 + msgBytes.length);
        try {
            payload.writeByte(0xFF); // ERR header
            payload.writeShortLE(errorCode);
            payload.writeByte((byte) '#');
            payload.writeBytes(sqlState.getBytes(StandardCharsets.US_ASCII));
            payload.writeBytes(msgBytes);
            return new MySqlPacket(payload.readableBytes(), sequenceId,
                    payload.retain().slice(0, payload.readableBytes()));
        } finally {
            payload.release();
        }
    }

    public static MySqlPacket err(byte sequenceId, int errorCode, String message) {
        return err(sequenceId, errorCode, "HY000", message);
    }

    public static MySqlPacket accessDenied(byte sequenceId, String username) {
        String msg = String.format("Access denied for user '%s'@'localhost' (using password: YES)", username);
        return err(sequenceId, 1045, "28000", msg);
    }

    public static MySqlPacket unsupportedCommand(byte sequenceId) {
        return err(sequenceId, 1064, "Unsupported command");
    }

    private static void writeLenEncInt(ByteBuf buf, long value) {
        if (value < 251) {
            buf.writeByte((int) value);
        } else if (value < 0x10000) {
            buf.writeByte(0xFC);
            buf.writeShortLE((int) value);
        } else if (value < 0x1000000) {
            buf.writeByte(0xFD);
            buf.writeMediumLE((int) value);
        } else {
            buf.writeByte(0xFE);
            buf.writeLongLE(value);
        }
    }
}
