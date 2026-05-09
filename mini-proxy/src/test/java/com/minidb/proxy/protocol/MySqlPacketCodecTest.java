package com.minidb.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MySqlPacketCodecTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(
                new MySqlPacketDecoder(),
                new MySqlPacketEncoder()
        );
    }

    @Test
    void shouldDecodeSinglePacket() {
        String sql = "SELECT 1";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        ByteBuf wire = ByteBufAllocator.DEFAULT.buffer();
        wire.writeByte(sqlBytes.length & 0xFF);
        wire.writeByte((sqlBytes.length >>> 8) & 0xFF);
        wire.writeByte((sqlBytes.length >>> 16) & 0xFF);
        wire.writeByte(0); // sequence 0
        wire.writeBytes(sqlBytes);

        assertTrue(channel.writeInbound(wire));
        MySqlPacket packet = channel.readInbound();

        assertNotNull(packet);
        assertEquals(sqlBytes.length, packet.payloadLength());
        assertEquals(0, packet.sequenceId());
        byte[] payload = new byte[packet.payload().readableBytes()];
        packet.payload().readBytes(payload);
        assertEquals(sql, new String(payload, StandardCharsets.UTF_8));
    }

    @Test
    void shouldDecodeMultiplePackets() {
        for (int i = 0; i < 3; i++) {
            byte[] data = ("test" + i).getBytes(StandardCharsets.UTF_8);
            ByteBuf wire = ByteBufAllocator.DEFAULT.buffer();
            wire.writeByte(data.length & 0xFF);
            wire.writeByte((data.length >>> 8) & 0xFF);
            wire.writeByte((data.length >>> 16) & 0xFF);
            wire.writeByte((byte) i);
            wire.writeBytes(data);

            assertTrue(channel.writeInbound(wire));
        }

        for (int i = 0; i < 3; i++) {
            MySqlPacket packet = channel.readInbound();
            assertNotNull(packet);
            assertEquals((byte) i, packet.sequenceId());
        }
    }

    @Test
    void shouldEncodePacket() {
        String payload = "hello";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(bytes);
        MySqlPacket packet = new MySqlPacket(bytes.length, (byte) 3, payloadBuf);

        assertTrue(channel.writeOutbound(packet));
        assertEquals(0, payloadBuf.refCnt());
        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded);

        assertEquals(bytes.length, encoded.readUnsignedByte()
                | (encoded.readUnsignedByte() << 8) | (encoded.readUnsignedByte() << 16));
        assertEquals(3, encoded.readByte());
        byte[] decoded = new byte[bytes.length];
        encoded.readBytes(decoded);
        assertArrayEquals(bytes, decoded);
    }

    @Test
    void shouldHandleEmptyPayload() {
        ByteBuf wire = ByteBufAllocator.DEFAULT.buffer();
        wire.writeByte(0);
        wire.writeByte(0);
        wire.writeByte(0);
        wire.writeByte(7);

        assertTrue(channel.writeInbound(wire));
        MySqlPacket packet = channel.readInbound();

        assertNotNull(packet);
        assertEquals(0, packet.payloadLength());
        assertEquals(7, packet.sequenceId());
    }

    @Test
    void shouldHandleMaxPayload() {
        int maxPayload = MySqlPacket.MAX_PAYLOAD;
        byte[] data = new byte[maxPayload];
        for (int i = 0; i < maxPayload; i++) {
            data[i] = (byte) (i & 0xFF);
        }

        ByteBuf wire = ByteBufAllocator.DEFAULT.buffer();
        wire.writeByte(maxPayload & 0xFF);
        wire.writeByte((maxPayload >>> 8) & 0xFF);
        wire.writeByte((maxPayload >>> 16) & 0xFF);
        wire.writeByte(0);
        wire.writeBytes(data);

        assertTrue(channel.writeInbound(wire));
        MySqlPacket packet = channel.readInbound();
        assertNotNull(packet);
        assertEquals(maxPayload, packet.payloadLength());
    }

    @Test
    void shouldRejectIncompletePacket() {
        ByteBuf wire = ByteBufAllocator.DEFAULT.buffer();
        wire.writeByte(0);
        wire.writeByte(0);
        wire.writeByte(0);
        // missing sequence byte
        assertFalse(channel.writeInbound(wire));
        assertNull(channel.readInbound());
    }

    @Test
    void shouldRejectTruncatedPayload() {
        ByteBuf wire = ByteBufAllocator.DEFAULT.buffer();
        wire.writeByte(50);    // payload length = 50
        wire.writeByte(0);
        wire.writeByte(0);
        wire.writeByte(0);     // sequence
        wire.writeByte(1);     // only 1 byte of promised 50
        assertFalse(channel.writeInbound(wire));
        assertNull(channel.readInbound());
    }
}
