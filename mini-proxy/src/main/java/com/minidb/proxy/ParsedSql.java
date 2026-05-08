package com.minidb.proxy;

import java.util.Collections;
import java.util.Set;

public class ParsedSql {

    private final SqlType type;
    private final Set<String> tables;
    private final Long shardKey;
    private final String altRouteKey;
    private final AltRouteType altRouteType;
    private final boolean primaryOnly;
    private final String originalSql;
    private final boolean hasForUpdate;
    private final boolean isTxCommand;

    public ParsedSql(SqlType type, Set<String> tables, Long shardKey,
                     String altRouteKey, AltRouteType altRouteType,
                     boolean primaryOnly,
                     String originalSql, boolean hasForUpdate, boolean isTxCommand) {
        this.type = type;
        this.tables = Collections.unmodifiableSet(tables);
        this.shardKey = shardKey;
        this.altRouteKey = altRouteKey;
        this.altRouteType = altRouteType != null ? altRouteType : AltRouteType.NONE;
        this.primaryOnly = primaryOnly;
        this.originalSql = originalSql;
        this.hasForUpdate = hasForUpdate;
        this.isTxCommand = isTxCommand;
    }

    public SqlType type() { return type; }
    public Set<String> tables() { return tables; }
    public Long shardKey() { return shardKey; }
    public String altRouteKey() { return altRouteKey; }
    public AltRouteType altRouteType() { return altRouteType; }
    public boolean isPrimaryOnly() { return primaryOnly; }
    public String originalSql() { return originalSql; }
    public boolean hasForUpdate() { return hasForUpdate; }
    public boolean isTxCommand() { return isTxCommand; }

    public boolean isWrite() {
        return type == SqlType.INSERT || type == SqlType.UPDATE || type == SqlType.DELETE;
    }

    public boolean isRead() {
        return type == SqlType.SELECT;
    }

    public boolean hasShardKey() {
        return shardKey != null;
    }

    public boolean hasAltRouteKey() {
        return altRouteKey != null && altRouteType != AltRouteType.NONE;
    }

    @Override
    public String toString() {
        return "ParsedSql{type=" + type + ", tables=" + tables +
                ", shardKey=" + shardKey +
                ", altRouteKey=" + altRouteKey + ", altRouteType=" + altRouteType +
                ", primaryOnly=" + primaryOnly +
                ", sql='" + originalSql + "'}";
    }
}
