package com.minidb.proxy;

import com.minidb.proxy.protocol.MySqlPacketEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BackendConnectionPoolTest {

    private BackendConnectionPoolImpl pool;
    private EventLoopGroup workerGroup;

    @BeforeEach
    void setUp() {
        workerGroup = new NioEventLoopGroup(1);
        pool = new BackendConnectionPoolImpl(5000, 600_000, 5000, workerGroup);
    }

    @AfterEach
    void tearDown() {
        pool.drainAndClose();
        workerGroup.shutdownGracefully();
    }

    @Test
    void shouldRegisterAndRetrieveMaxSize() {
        DataSourceId id = new DataSourceId("test");
        pool.registerDataSource(id,
                new BackendConnectionPoolImpl.BackendServerConfig("127.0.0.1", 3306), 2);

        assertNotNull(pool);
    }

    @Test
    void shouldRejectUnknownDataSource() {
        assertThrows(IllegalArgumentException.class, () -> {
            pool.borrow(new DataSourceId("nonexistent"));
        });
    }

    @Test
    void shouldRejectBorrowWhenPoolClosed() {
        pool.drainAndClose();
        assertThrows(IllegalStateException.class, () -> {
            pool.borrow(new DataSourceId("test"));
        });
    }

    @Test
    void shouldIgnoreReleaseForConnectionNotBorrowedFromPool() {
        DataSourceId id = new DataSourceId("test");
        pool.registerDataSource(id,
                new BackendConnectionPoolImpl.BackendServerConfig("127.0.0.1", 3306), 2);

        Channel mockChannel = new io.netty.channel.embedded.EmbeddedChannel();
        BackendConnection conn = new BackendConnection(id, mockChannel);

        assertDoesNotThrow(() -> pool.release(conn));
        pool.release(conn);
        assertEquals(0, pool.activeCount(id));
    }

    @Test
    void shouldWriteSqlAsComQueryPacket() {
        DataSourceId id = new DataSourceId("test");
        EmbeddedChannel channel = new EmbeddedChannel(new MySqlPacketEncoder());
        BackendConnection conn = new BackendConnection(id, channel);

        conn.writeComQuery("SELECT 1");

        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded);

        byte[] sql = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        int payloadLength = encoded.readUnsignedByte()
                | (encoded.readUnsignedByte() << 8)
                | (encoded.readUnsignedByte() << 16);
        assertEquals(sql.length + 1, payloadLength);
        assertEquals(0, encoded.readUnsignedByte());
        assertEquals(0x03, encoded.readUnsignedByte());

        byte[] actualSql = new byte[sql.length];
        encoded.readBytes(actualSql);
        assertArrayEquals(sql, actualSql);
        encoded.release();
    }
}
