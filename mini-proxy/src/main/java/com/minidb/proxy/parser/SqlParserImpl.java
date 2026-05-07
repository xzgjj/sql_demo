package com.minidb.proxy.parser;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLValuableExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.util.JdbcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SqlParserImpl {

    private static final Logger log = LoggerFactory.getLogger(SqlParserImpl.class);

    public ParsedSql parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return new ParsedSql(SqlType.OTHER, Set.of(), null, sql, false, false);
        }

        String trimmed = sql.trim().toUpperCase();

        // Fast-path for transaction commands (Druid may not parse these)
        if (trimmed.equals("BEGIN") || trimmed.equals("START TRANSACTION")) {
            return new ParsedSql(SqlType.BEGIN, Set.of(), null, sql, false, true);
        }
        if (trimmed.equals("COMMIT")) {
            return new ParsedSql(SqlType.COMMIT, Set.of(), null, sql, false, true);
        }
        if (trimmed.equals("ROLLBACK")) {
            return new ParsedSql(SqlType.ROLLBACK, Set.of(), null, sql, false, true);
        }

        // Fast-path for SET/SHOW
        if (trimmed.startsWith("SET ") || trimmed.startsWith("SHOW ")) {
            SqlType type = trimmed.startsWith("SET ") ? SqlType.SET : SqlType.SHOW;
            return new ParsedSql(type, Set.of(), null, sql, false, false);
        }

        try {
            List<SQLStatement> stmts = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
            if (stmts.isEmpty()) {
                return new ParsedSql(SqlType.OTHER, Set.of(), null, sql, false, false);
            }
            SQLStatement stmt = stmts.get(0);

            SqlType type = classifyType(stmt);
            TableCollector tables = new TableCollector();
            stmt.accept(tables);

            Long shardKey = null;
            boolean forUpdate = false;

            if (stmt instanceof SQLSelectStatement selectStmt) {
                shardKey = extractShardKeyFromSelect(selectStmt);
                forUpdate = hasForUpdate(selectStmt);
            } else if (stmt instanceof SQLUpdateStatement updateStmt) {
                shardKey = extractShardKeyFromUpdate(updateStmt);
            } else if (stmt instanceof SQLDeleteStatement deleteStmt) {
                shardKey = extractShardKeyFromDelete(deleteStmt);
            } else if (stmt instanceof SQLInsertStatement insertStmt) {
                shardKey = extractShardKeyFromInsert(insertStmt);
            }

            return new ParsedSql(type, tables.tables, shardKey, sql, forUpdate, false);

        } catch (Exception e) {
            log.warn("Failed to parse SQL: {}", sql, e);
            return new ParsedSql(SqlType.OTHER, Set.of(), null, sql, false, false);
        }
    }

    private SqlType classifyType(SQLStatement stmt) {
        if (stmt instanceof SQLSelectStatement) return SqlType.SELECT;
        if (stmt instanceof SQLInsertStatement) return SqlType.INSERT;
        if (stmt instanceof SQLUpdateStatement) return SqlType.UPDATE;
        if (stmt instanceof SQLDeleteStatement) return SqlType.DELETE;
        return SqlType.OTHER;
    }

    private Long extractShardKeyFromSelect(SQLSelectStatement stmt) {
        SQLSelectQueryBlock queryBlock = extractQueryBlock(stmt);
        if (queryBlock == null) return null;
        return extractFromWhere(queryBlock.getWhere());
    }

    private Long extractShardKeyFromUpdate(SQLUpdateStatement stmt) {
        return extractFromWhere(stmt.getWhere());
    }

    private Long extractShardKeyFromDelete(SQLDeleteStatement stmt) {
        return extractFromWhere(stmt.getWhere());
    }

    private Long extractShardKeyFromInsert(SQLInsertStatement stmt) {
        List<String> columns = stmt.getColumns().stream()
                .map(col -> col.toString().replace("`", "").toLowerCase())
                .toList();
        int userIdIdx = -1;
        for (int i = 0; i < columns.size(); i++) {
            if ("user_id".equals(columns.get(i))) {
                userIdIdx = i;
                break;
            }
        }
        if (userIdIdx < 0) return null;

        List<SQLInsertStatement.ValuesClause> valuesList = stmt.getValuesList();
        if (valuesList.isEmpty()) return null;
        List<SQLExpr> values = valuesList.get(0).getValues();
        if (userIdIdx >= values.size()) return null;
        return extractIntegerValue(values.get(userIdIdx));
    }

    private Long extractFromWhere(SQLExpr where) {
        if (where instanceof SQLBinaryOpExpr binaryOp) {
            if (binaryOp.getOperator().isRelational()
                    && "=".equals(binaryOp.getOperator().getName())) {
                Long left = extractUserIdFromExpr(binaryOp.getLeft());
                Long right = extractIntegerValue(binaryOp.getRight());
                if (left != null && right != null) return right;
                // Check reversed: WHERE 100 = user_id
                Long leftVal = extractIntegerValue(binaryOp.getLeft());
                Long rightId = extractUserIdFromExpr(binaryOp.getRight());
                if (leftVal != null && rightId != null) return leftVal;
            }
            if (binaryOp.getOperator().isLogical()) {
                // Try left branch first
                Long result = extractFromWhere(binaryOp.getLeft());
                if (result != null) return result;
                return extractFromWhere(binaryOp.getRight());
            }
        }
        return null;
    }

    private Long extractUserIdFromExpr(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr idExpr) {
            String name = idExpr.getName().replace("`", "").toLowerCase();
            if ("user_id".equals(name)) return 0L; // marker — found user_id reference
        }
        return null;
    }

    private Long extractIntegerValue(SQLExpr expr) {
        if (expr instanceof SQLIntegerExpr intExpr) {
            return intExpr.getNumber().longValue();
        }
        if (expr instanceof SQLValuableExpr valued) {
            Object val = valued.getValue();
            if (val instanceof Number n) return n.longValue();
        }
        return null;
    }

    private boolean hasForUpdate(SQLSelectStatement stmt) {
        SQLSelectQueryBlock block = extractQueryBlock(stmt);
        return block != null && block.isForUpdate();
    }

    private SQLSelectQueryBlock extractQueryBlock(SQLSelectStatement stmt) {
        if (stmt.getSelect().getQuery() instanceof SQLSelectQueryBlock block) {
            return block;
        }
        return null;
    }

    private static class TableCollector extends MySqlASTVisitorAdapter {
        final Set<String> tables = new HashSet<>();

        @Override
        public boolean visit(SQLExprTableSource x) {
            tables.add(x.getTableName().replace("`", "").toLowerCase());
            return true;
        }

        @Override
        public boolean visit(MySqlInsertStatement x) {
            tables.add(x.getTableName().getSimpleName().replace("`", "").toLowerCase());
            return true;
        }

        @Override
        public boolean visit(SQLUpdateStatement x) {
            tables.add(x.getTableName().getSimpleName().replace("`", "").toLowerCase());
            return true;
        }

        @Override
        public boolean visit(SQLDeleteStatement x) {
            tables.add(x.getTableName().getSimpleName().replace("`", "").toLowerCase());
            return true;
        }
    }
}
