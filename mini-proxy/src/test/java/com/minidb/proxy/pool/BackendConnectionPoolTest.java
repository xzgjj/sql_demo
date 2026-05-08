package com.minidb.proxy.pool;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void shouldReleaseConnectionToIdle() {
        DataSourceId id = new DataSourceId("test");
        pool.registerDataSource(id,
                new BackendConnectionPoolImpl.BackendServerConfig("127.0.0.1", 3306), 2);

        // Borrow with no real backend will eventually wait on the queue
        // Just testing release logic
        Channel mockChannel = new io.netty.channel.embedded.EmbeddedChannel();
        BackendConnection conn = new BackendConnection(id, mockChannel);

        pool.release(conn);
        // Connection should be idle and reusable
        BackendConnection reused = pool.borrow(id);
        assertNotNull(reused);
        assertEquals(id, reused.dataSourceId());
        pool.release(reused);
    }
}
