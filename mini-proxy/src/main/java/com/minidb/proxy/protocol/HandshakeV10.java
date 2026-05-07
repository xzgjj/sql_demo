package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;

/**
 * Builds a MySQL HandshakeV10 packet sent from server to client.
 *
 * Layout (after 4-byte packet header):
 *   1 byte  protocolVersion (0x0a)
 *   N bytes serverVersion (null-terminated ASCII)
 *   4 bytes connectionId (LE)
 *   8 bytes auth-plugin-data-part-1
 *   1 byte  filler (0x00)
 *   2 bytes capabilityFlags (lower 16 bits, LE)
 *   1 byte  characterSet
 *   2 bytes statusFlags
 *   2 bytes capabilityFlags (upper 16 bits, LE)
 *   1 byte  authPluginDataLen (21 for mysql_native_password)
 *   10 bytes reserved (0x00)
 *   N bytes auth-plugin-data-part-2 (12 + 1 null terminator for 8+12=20 total scramble)
 *   N bytes authPluginName (null-terminated, "mysql_native_password")
 */
public final class HandshakeV10 {

    private HandshakeV10() {}

    public static MySqlPacket build(int connectionId, byte[] scramble) {
        byte[] serverVersionBytes = "5.7.38-minidb-proxy".getBytes(StandardCharsets.US_ASCII);
        byte[] pluginNameBytes = "mysql_native_password".getBytes(StandardCharsets.US_ASCII);

        int capabilityFlags = CapabilityFlags.SERVER_BASIC_FLAGS;
        int statusFlags = 0x0002; // SERVER_STATUS_AUTOCOMMIT
        byte characterSet = 0x21; // utf8mb4

        ByteBuf payload = ByteBufAllocator.DEFAULT.buffer(128);
        try {
            payload.writeByte(0x0a); // protocol version 10
            payload.writeBytes(serverVersionBytes);
            payload.writeByte(0x00); // null-terminate version

            payload.writeIntLE(connectionId);

            // auth-plugin-data-part-1: first 8 bytes of scramble
            payload.writeBytes(scramble, 0, 8);

            payload.writeByte(0x00); // filler

            // capability flags lower 16 bits
            payload.writeShortLE(capabilityFlags & 0xFFFF);

            payload.writeByte(characterSet);
            payload.writeShortLE(statusFlags);

            // capability flags upper 16 bits
            payload.writeShortLE((capabilityFlags >>> 16) & 0xFFFF);

            // auth plugin data length (scramble bytes 8-19 + null terminator)
            payload.writeByte(21);

            // reserved (10 zero bytes)
            payload.writeZero(10);

            // auth-plugin-data-part-2: 12 bytes + null terminator
            if (scramble.length > 8) {
                int part2Len = Math.min(scramble.length - 8, 12);
                payload.writeBytes(scramble, 8, part2Len);
                int remaining = 12 - part2Len;
                while (remaining-- > 0) {
                    payload.writeByte(0x00);
                }
            } else {
                payload.writeZero(12);
            }
            payload.writeByte(0x00); // null terminator for scramble

            // auth plugin name
            payload.writeBytes(pluginNameBytes);
            payload.writeByte(0x00); // null-terminate

            return new MySqlPacket(payload.readableBytes(), (byte) 0,
                    payload.retain().slice(0, payload.readableBytes()));
        } finally {
            payload.release();
        }
    }

    public static HandshakeResponse41 parseResponse(ByteBuf payload) {
        if (payload.readableBytes() < 32) { // minimum handshake response size
            throw new IllegalArgumentException(
                    "Handshake response too short: " + payload.readableBytes() + " bytes");
        }
        int capabilityFlags = (int) payload.readUnsignedIntLE();
        int maxPacketSize = payload.readIntLE();
        byte characterSet = payload.readByte();
        payload.skipBytes(23); // reserved zeroes

        int userLen = payload.readUnsignedByte();
        String username = payload.readCharSequence(userLen, StandardCharsets.UTF_8).toString();

        byte[] authResponse;
        if ((capabilityFlags & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0) {
            authResponse = new byte[payload.readUnsignedByte()];
            payload.readBytes(authResponse);
        } else {
            int len = indexOfNullByte(payload);
            authResponse = new byte[len];
            payload.readBytes(authResponse);
        }

        String database = "";
        if ((capabilityFlags & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0) {
            database = payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8).toString();
        } else if (payload.readableBytes() > 0 && payload.getByte(payload.readerIndex()) != 0) {
            int nullPos = indexOfNullByte(payload);
            if (nullPos > 0) {
                payload.skipBytes(nullPos);
            }
        }

        String authPluginName = "";
        if (payload.readableBytes() > 0) {
            payload.skipBytes(1); // skip null
            authPluginName = payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8).toString();
        }

        return new HandshakeResponse41(capabilityFlags, maxPacketSize, characterSet,
                username, authResponse, database, authPluginName);
    }

    private static int indexOfNullByte(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = start + buf.readableBytes();
        for (int i = start; i < end; i++) {
            if (buf.getByte(i) == 0x00) {
                return i - start;
            }
        }
        return buf.readableBytes();
    }
}
