package com.minidb.proxy;

import com.minidb.proxy.DataSourceId;

public record RoutePlan(DataSourceId dataSourceId, Integer shardId, boolean sessionOnly) {

    public static RoutePlan sessionOnlyPlan() {
        return new RoutePlan(null, null, true);
    }

    public static RoutePlan toDataSource(DataSourceId id, Integer shardId) {
        return new RoutePlan(id, shardId, false);
    }

    public boolean requiresBackend() {
        return dataSourceId != null;
    }
}
