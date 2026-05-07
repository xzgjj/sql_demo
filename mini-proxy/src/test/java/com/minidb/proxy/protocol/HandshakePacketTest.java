package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HandshakePacketTest {

    @Test
    void shouldBuildHandshakeV10() {
        byte[] scramble = AuthNativePassword.generateScramble(20);
        MySqlPacket packet = HandshakeV10.build(1, scramble);

        assertNotNull(packet);
        assertEquals((byte) 0, packet.sequenceId());

        ByteBuf payload = packet.payload();
        byte protocolVersion = payload.readByte();
        assertEquals(0x0a, protocolVersion);

        int nullIdx = indexOfNull(payload);
        String version = payload.readCharSequence(nullIdx, StandardCharsets.UTF_8).toString();
        payload.skipBytes(1); // null terminator
        assertTrue(version.contains("minidb-proxy") || version.contains("5.7"));

        int connectionId = payload.readIntLE();
        assertEquals(1, connectionId);

        // auth-plugin-data-part-1 is first 8 bytes of scramble
        byte[] part1 = new byte[8];
        payload.readBytes(part1);
        for (int i = 0; i < 8; i++) {
            assertEquals(scramble[i], part1[i]);
        }

        // filler
        assertEquals(0x00, payload.readByte());

        packet.release();
    }

    @Test
    void shouldBuildOkPacket() {
        MySqlPacket ok = ResponsePackets.ok((byte) 1);
        assertNotNull(ok);
        assertEquals((byte) 1, ok.sequenceId());

        ByteBuf payload = ok.payload();
        assertEquals(0x00, payload.readByte()); // OK header
        assertEquals(0x00, payload.readByte()); // affectedRows=0 (lenenc 0)
        assertEquals(0x00, payload.readByte()); // lastInsertId=0 (lenenc 0)
        assertEquals(0x02, payload.readShortLE()); // statusFlags=AUTOCOMMIT
        ok.release();
    }

    @Test
    void shouldBuildOkPacketWithInfo() {
        MySqlPacket ok = ResponsePackets.ok((byte) 2, 1, 10, 0x0002, "Rows matched: 1");
        assertNotNull(ok);

        ByteBuf payload = ok.payload();
        assertEquals(0x00, payload.readByte()); // OK header
        assertTrue(payload.readableBytes() > 0);
        ok.release();
    }

    @Test
    void shouldBuildErrPacket() {
        MySqlPacket err = ResponsePackets.err((byte) 3, 1045, "28000", "Access denied");
        assertNotNull(err);
        assertEquals((byte) 3, err.sequenceId());

        ByteBuf payload = err.payload();
        assertEquals((byte) 0xFF, payload.readByte()); // ERR header
        assertEquals(1045, payload.readShortLE());      // error code
        assertEquals((byte) '#', payload.readByte());   // sql state marker
        byte[] sqlState = new byte[5];
        payload.readBytes(sqlState);
        assertEquals("28000", new String(sqlState, StandardCharsets.US_ASCII));
        err.release();
    }

    @Test
    void shouldBuildAccessDenied() {
        MySqlPacket err = ResponsePackets.accessDenied((byte) 2, "proxy");
        ByteBuf payload = err.payload();
        assertEquals((byte) 0xFF, payload.readByte());
        assertEquals(1045, payload.readShortLE());
        err.release();
    }

    @Test
    void shouldParseHandshakeResponse() {
        ByteBuf payload = ByteBufAllocator.DEFAULT.buffer();
        payload.writeIntLE(CapabilityFlags.CLIENT_BASIC_FLAGS);
        payload.writeIntLE(0x1000000); // max packet 16M
        payload.writeByte(0x21);       // utf8mb4
        payload.writeZero(23);         // reserved
        payload.writeByte(5);          // username length
        payload.writeBytes("proxy".getBytes(StandardCharsets.UTF_8));

        byte[] authResponse = new byte[20];
        new java.util.Random(42).nextBytes(authResponse);
        payload.writeByte(20);          // auth response length
        payload.writeBytes(authResponse);

        payload.writeByte(0x00);        // null terminator
        payload.writeBytes("mysql_native_password".getBytes(StandardCharsets.UTF_8));

        HandshakeResponse41 resp = HandshakeV10.parseResponse(payload);
        assertEquals("proxy", resp.username());
        assertEquals(20, resp.authResponse().length);
        assertEquals("mysql_native_password", resp.authPluginName());
        payload.release();
    }

    private static int indexOfNull(ByteBuf buf) {
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
