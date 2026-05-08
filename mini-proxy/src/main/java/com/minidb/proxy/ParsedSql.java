package com.minidb.proxy;

import java.util.Collections;
import java.util.Set;

public class ParsedSql {

    private final SqlType type;
    private final Set<String> tables;
    private final Long shardKey;
    private final String originalSql;
    private final boolean hasForUpdate;
    private final boolean isTxCommand;

    public ParsedSql(SqlType type, Set<String> tables, Long shardKey,
                     String originalSql, boolean hasForUpdate, boolean isTxCommand) {
        this.type = type;
        this.tables = Collections.unmodifiableSet(tables);
        this.shardKey = shardKey;
        this.originalSql = originalSql;
        this.hasForUpdate = hasForUpdate;
        this.isTxCommand = isTxCommand;
    }

    public SqlType type() { return type; }
    public Set<String> tables() { return tables; }
    public Long shardKey() { return shardKey; }
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

    @Override
    public String toString() {
        return "ParsedSql{type=" + type + ", tables=" + tables +
                ", shardKey=" + shardKey + ", sql='" + originalSql + "'}";
    }
}
