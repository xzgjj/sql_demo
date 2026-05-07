package com.minidb.proxy.pool;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.nio.charset.StandardCharsets;

/**
 * A connection to a backend MySQL instance.
 */
public class BackendConnection {

    private final DataSourceId dataSourceId;
    private final Channel channel;
    private volatile long borrowedAt;
    private volatile long lastUsedAt;
    private volatile boolean healthy;

    public BackendConnection(DataSourceId dataSourceId, Channel channel) {
        this.dataSourceId = dataSourceId;
        this.channel = channel;
        this.healthy = true;
        this.lastUsedAt = System.currentTimeMillis();
    }

    public DataSourceId dataSourceId() { return dataSourceId; }
    public Channel channel() { return channel; }
    public boolean healthy() { return healthy && channel.isActive(); }
    public void markUnhealthy() { this.healthy = false; }

    public long borrowedAt() { return borrowedAt; }
    public void markBorrowed() { this.borrowedAt = System.currentTimeMillis(); }

    public long lastUsedAt() { return lastUsedAt; }
    public void markUsed() { this.lastUsedAt = System.currentTimeMillis(); }

    public ChannelFuture writeAndFlush(byte[] data) {
        return channel.writeAndFlush(channel.alloc().buffer().writeBytes(data));
    }

    public ChannelFuture writeAndFlush(String sql) {
        return writeAndFlush(sql.getBytes(StandardCharsets.UTF_8));
    }

    public void close() {
        if (channel.isOpen()) {
            channel.close();
        }
    }
}
