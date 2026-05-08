package com.minidb.proxy;

import com.minidb.proxy.BackendConnection;
import io.netty.channel.Channel;

import java.util.UUID;

public class ProxySession {

    private final String sessionId;
    private final Channel clientChannel;
    private ConnectionState state;
    private boolean inTransaction;
    private int boundShardId;
    private BackendConnection boundConnection;
    private BackendConnection autoConnection; // non-tx connection, released on next query or disconnect
    private long lastWriteTimeMs;

    public ProxySession(Channel clientChannel) {
        this.sessionId = UUID.randomUUID().toString();
        this.clientChannel = clientChannel;
        this.state = ConnectionState.HANDSHAKING;
        this.inTransaction = false;
        this.boundShardId = -1;
        this.boundConnection = null;
        this.lastWriteTimeMs = 0;
    }

    public String sessionId() { return sessionId; }
    public Channel clientChannel() { return clientChannel; }
    public ConnectionState state() { return state; }
    public void setState(ConnectionState s) { this.state = s; }

    public boolean inTransaction() { return inTransaction; }
    public void beginTransaction() { this.inTransaction = true; }
    public void endTransaction() { this.inTransaction = false; }

    public int boundShardId() { return boundShardId; }
    public BackendConnection boundConnection() { return boundConnection; }

    public void bind(int shardId, BackendConnection connection) {
        this.boundShardId = shardId;
        this.boundConnection = connection;
    }

    public void unbind() {
        this.boundShardId = -1;
        this.boundConnection = null;
    }

    public BackendConnection autoConnection() { return autoConnection; }
    public void setAutoConnection(BackendConnection c) { this.autoConnection = c; }
    public void clearAutoConnection() { this.autoConnection = null; }

    public long lastWriteTimeMs() { return lastWriteTimeMs; }
    public void markWrite() { this.lastWriteTimeMs = System.currentTimeMillis(); }

    public boolean isRecentlyWritten(long windowMs) {
        if (lastWriteTimeMs == 0) return false;
        return System.currentTimeMillis() - lastWriteTimeMs < windowMs;
    }
}
