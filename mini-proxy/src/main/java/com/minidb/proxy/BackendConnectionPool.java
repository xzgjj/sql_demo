package com.minidb.proxy;

public interface BackendConnectionPool {
    BackendConnection borrow(DataSourceId id);
    void release(BackendConnection conn);
    void invalidate(BackendConnection conn);
}
