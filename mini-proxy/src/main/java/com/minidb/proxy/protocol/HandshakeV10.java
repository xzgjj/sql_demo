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
        byte[] serverVersionBytes = "8.4.9-minidb-proxy".getBytes(StandardCharsets.US_ASCII);
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
        // Note: MySQL HandshakeResponse41 first 4 bytes are:
        //   bytes 0-1: capability flags (lower 16 bits)
        //   bytes 2-3: max packet size (lower 16 bits)
        // readUnsignedIntLE() reads them as a single 32-bit value.
        // Capability flags lower 16 are in bits 0-15 of the result.
        int maxPacketSize = payload.readIntLE();
        byte characterSet = payload.readByte();
        payload.skipBytes(23); // reserved zeroes

        String username = readNullTerminatedString(payload);

        byte[] authResponse;
        if ((capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0) {
            long length = readLengthEncodedInteger(payload);
            if (length > payload.readableBytes()) {
                throw new IllegalArgumentException("Auth response length exceeds payload: " + length);
            }
            authResponse = new byte[(int) length];
            payload.readBytes(authResponse);
        } else if ((capabilityFlags & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0) {
            authResponse = new byte[payload.readUnsignedByte()];
            payload.readBytes(authResponse);
        } else {
            int len = indexOfNullByte(payload);
            authResponse = new byte[len];
            payload.readBytes(authResponse);
            if (payload.readableBytes() > 0) {
                payload.skipBytes(1);
            }
        }

        String database = "";
        if ((capabilityFlags & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0) {
            database = readNullTerminatedString(payload);
        }

        String authPluginName = "";
        if ((capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH) != 0 && payload.readableBytes() > 0) {
            authPluginName = readNullTerminatedString(payload);
        }

        return new HandshakeResponse41(capabilityFlags, maxPacketSize, characterSet,
                username, authResponse, database, authPluginName);
    }

    /**
     * Parse a backend MySQL HandshakeV10 to extract the scramble.
     * Used by the proxy when authenticating to backend MySQL instances.
     */
    public static byte[] extractScramble(byte[] payload) {
        int pos = 1; // skip protocol version (0x0a)

        // skip server version (null-terminated string)
        while (pos < payload.length && payload[pos] != 0) pos++;
        pos++; // null terminator

        if (pos + 4 > payload.length) throw new IllegalArgumentException("Handshake too short");
        // connectionId = readIntLE(payload, pos); — not needed
        pos += 4;

        // auth-plugin-data-part-1: 8 bytes
        byte[] scramble = new byte[20]; // 8 + 12 for mysql_native_password
        System.arraycopy(payload, pos, scramble, 0, 8);
        pos += 8;
        pos++; // filler (0x00)

        // capability flags lower 2 bytes
        pos += 2;
        // character set 1 byte
        pos += 1;
        // status flags 2 bytes
        pos += 2;
        // capability flags upper 2 bytes
        pos += 2;
        // auth plugin data length 1 byte
        pos += 1;
        // reserved 10 bytes
        pos += 10;

        // auth-plugin-data-part-2: 12 bytes + null terminator
        int remaining = Math.min(12, payload.length - pos);
        for (int i = 0; i < remaining && (payload[pos] != 0); i++) {
            if (8 + i < 20) scramble[8 + i] = payload[pos];
            pos++;
        }

        return scramble;
    }

    /**
     * Extract the auth plugin name from a backend MySQL HandshakeV10 packet.
     * Returns "mysql_native_password" if not found as safe default.
     */
    public static String extractAuthPluginName(byte[] payload) {
        // The auth plugin name is at the end of the handshake, after the auth-plugin-data-part-2
        int pos = 1; // skip protocol version
        while (pos < payload.length && payload[pos] != 0) pos++; // server version
        pos++; // null terminator
        if (pos + 4 > payload.length) return "mysql_native_password";
        pos += 4; // connection id
        if (pos + 8 > payload.length) return "mysql_native_password";
        pos += 8; // auth-plugin-data-part-1
        pos++; // filler
        pos += 2; // cap lower
        pos += 1; // charset
        pos += 2; // status
        pos += 2; // cap upper
        pos += 1; // auth data len
        pos += 10; // reserved
        // skip auth-plugin-data-part-2 (variable length, null-terminated)
        while (pos < payload.length && payload[pos] != 0) pos++;
        if (pos < payload.length) pos++; // null terminator
        // remaining bytes = auth plugin name
        if (pos < payload.length) {
            int endPos = pos;
            while (endPos < payload.length && payload[endPos] != 0) endPos++;
            return new String(payload, pos, endPos - pos, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "mysql_native_password";
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

    private static String readNullTerminatedString(ByteBuf payload) {
        int len = indexOfNullByte(payload);
        String value = payload.readCharSequence(len, StandardCharsets.UTF_8).toString();
        if (payload.readableBytes() > 0) {
            payload.skipBytes(1);
        }
        return value;
    }

    private static long readLengthEncodedInteger(ByteBuf payload) {
        int first = payload.readUnsignedByte();
        if (first < 0xFB) return first;
        if (first == 0xFC) return payload.readUnsignedShortLE();
        if (first == 0xFD) {
            int b0 = payload.readUnsignedByte();
            int b1 = payload.readUnsignedByte();
            int b2 = payload.readUnsignedByte();
            return b0 | (b1 << 8) | (b2 << 16);
        }
        if (first == 0xFE) return payload.readLongLE();
        throw new IllegalArgumentException("Unsupported NULL length-encoded integer");
    }
}
