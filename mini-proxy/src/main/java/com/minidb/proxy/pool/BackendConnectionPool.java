package com.minidb.proxy.pool;

public interface BackendConnectionPool {
    BackendConnection borrow(DataSourceId id);
    void release(BackendConnection conn);
    void invalidate(BackendConnection conn);
}
