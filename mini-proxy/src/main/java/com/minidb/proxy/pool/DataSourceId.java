package com.minidb.proxy.pool;

import java.util.Objects;

public record DataSourceId(String name) {

    public static final DataSourceId PRIMARY = new DataSourceId("primary");
    public static final DataSourceId REPLICA = new DataSourceId("replica");

    public static DataSourceId shard(int index) {
        return new DataSourceId("shard_" + index);
    }

    public boolean isReplica() {
        return "replica".equals(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataSourceId that)) return false;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
