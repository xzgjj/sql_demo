package com.minidb.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Teaching-grade Two-Phase Commit coordinator.
 *
 * Coordinates writes across PRIMARY (metadata tables) and a single shard
 * (business tables). Each participant executes in its own local transaction.
 * Both must succeed for the global transaction to commit.
 *
 * Simplifications for teaching:
 * - No XA recovery log (coordinator crash → manual fix)
 * - Participant timeout 5s, timeout → ABORT
 * - Max 2 participants (PRIMARY + 1 shard)
 * - Direct JDBC connections, bypassing proxy
 */
@Service
public class TwoPhaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCoordinator.class);
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final String primaryUrl;
    private final String primaryUser;
    private final String primaryPassword;
    private final String shardHost;
    private final int shardPortBase;
    private final String shardUser;
    private final String shardPassword;
    private final String shardDbPrefix;
    private final int shardCount;
    private final TraceService traceService;

    public TwoPhaseCoordinator(
            @Value("${spring.datasource.url}") String primaryUrl,
            @Value("${spring.datasource.username}") String primaryUser,
            @Value("${spring.datasource.password}") String primaryPassword,
            @Value("${minidb.shard.host:127.0.0.1}") String shardHost,
            @Value("${minidb.shard.port-base:4409}") int shardPortBase,
            @Value("${minidb.shard.username:root}") String shardUser,
            @Value("${minidb.shard.password:root123}") String shardPassword,
            @Value("${minidb.shard.database-prefix:minidb_shard_}") String shardDbPrefix,
            @Value("${minidb.order.shard-count:4}") int shardCount,
            TraceService traceService) {
        this.primaryUrl = primaryUrl;
        this.primaryUser = primaryUser;
        this.primaryPassword = primaryPassword;
        this.shardHost = shardHost;
        this.shardPortBase = shardPortBase;
        this.shardUser = shardUser;
        this.shardPassword = shardPassword;
        this.shardDbPrefix = shardDbPrefix;
        this.shardCount = shardCount;
        this.traceService = traceService;
    }

    public int shardIndex(long userId) {
        return (int) Math.floorMod(userId, shardCount);
    }

    /**
     * Execute a 2PC plan. Returns result with votes from each participant.
     */
    public TwoPhaseResult execute(TwoPhasePlan plan) {
        String traceId = plan.traceId();
        long startMs = System.currentTimeMillis();
        log.info("2PC [{}] Phase 1 — PREPARE, shard={}", traceId, plan.shardIndex());

        int shardIndex = plan.shardIndex();
        String shardUrl = shardJdbcUrl(shardIndex);
        Connection primaryConn = null;
        Connection shardConn = null;
        List<ParticipantVote> votes = new ArrayList<>();
        List<String> phase1Details = new ArrayList<>();
        List<String> phase2Details = new ArrayList<>();

        try {
            // --- Phase 1: PREPARE ---
            primaryConn = DriverManager.getConnection(primaryUrl, primaryUser, primaryPassword);
            primaryConn.setAutoCommit(false);
            primaryConn.setNetworkTimeout(null, DEFAULT_TIMEOUT_MS);

            shardConn = DriverManager.getConnection(shardUrl, shardUser, shardPassword);
            shardConn.setAutoCommit(false);
            shardConn.setNetworkTimeout(null, DEFAULT_TIMEOUT_MS);

            // Execute PRIMARY statements
            try {
                for (SqlStatement s : plan.primaryStatements()) {
                    executeStatement(primaryConn, s);
                }
                votes.add(new ParticipantVote("PRIMARY", Vote.OK, plan.primaryStatements().size() + " statements"));
                phase1Details.add("PRIMARY: OK");
            } catch (Exception e) {
                log.error("2PC [{}] PRIMARY PREPARE failed: {}", traceId, e.getMessage());
                votes.add(new ParticipantVote("PRIMARY", Vote.ABORT, e.getMessage()));
                phase1Details.add("PRIMARY: ABORT — " + e.getMessage());
            }

            // Execute shard statements (only if PRIMARY is OK)
            if (primaryOk(votes)) {
                try {
                    for (SqlStatement s : plan.shardStatements()) {
                        executeStatement(shardConn, s);
                    }
                    votes.add(new ParticipantVote(shardDsName(shardIndex), Vote.OK,
                            plan.shardStatements().size() + " statements"));
                    phase1Details.add(shardDsName(shardIndex) + ": OK");
                } catch (Exception e) {
                    log.error("2PC [{}] shard_{} PREPARE failed: {}", traceId, shardIndex, e.getMessage());
                    votes.add(new ParticipantVote(shardDsName(shardIndex), Vote.ABORT, e.getMessage()));
                    phase1Details.add(shardDsName(shardIndex) + ": ABORT — " + e.getMessage());
                }
            }

            // --- Phase 2: COMMIT or ROLLBACK ---
            boolean allOk = votes.stream().allMatch(v -> v.vote() == Vote.OK) && votes.size() == 2;

            if (allOk) {
                log.info("2PC [{}] Phase 2 — COMMIT (all {} participants OK)", traceId, votes.size());
                primaryConn.commit();
                phase2Details.add("PRIMARY: COMMITTED");
                shardConn.commit();
                phase2Details.add(shardDsName(shardIndex) + ": COMMITTED");
            } else {
                log.warn("2PC [{}] Phase 2 — ROLLBACK ({} votes, not all OK)", traceId, votes.size());
                rollback(primaryConn, "PRIMARY", phase2Details);
                rollback(shardConn, shardDsName(shardIndex), phase2Details);
            }

            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            TwoPhaseResult result = new TwoPhaseResult(allOk, traceId, votes, phase1Details,
                    phase2Details, elapsedMs);

            // Record to audit log
            traceService.record2pc(traceId, result, plan.summary());

            return result;

        } catch (SQLException e) {
            log.error("2PC [{}] coordinator connection error: {}", traceId, e.getMessage());
            rollback(primaryConn, "PRIMARY", phase2Details);
            rollback(shardConn, shardDsName(shardIndex), phase2Details);
            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            if (votes.isEmpty()) {
                votes.add(new ParticipantVote("COORDINATOR", Vote.ABORT,
                        "Connection failed: " + e.getMessage()));
            }
            return new TwoPhaseResult(false, traceId, votes, phase1Details,
                    phase2Details, elapsedMs);
        } finally {
            close(primaryConn);
            close(shardConn);
        }
    }

    private void executeStatement(Connection conn, SqlStatement s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(s.sql())) {
            Object[] params = s.params();
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
            }
            int affected = ps.executeUpdate();
            log.debug("2PC exec: rows={}, sql={}", affected,
                    s.sql().substring(0, Math.min(60, s.sql().length())));
        }
    }

    private boolean primaryOk(List<ParticipantVote> votes) {
        return votes.stream().anyMatch(v -> "PRIMARY".equals(v.dataSourceName()) && v.vote() == Vote.OK);
    }

    private void rollback(Connection conn, String name, List<String> details) {
        if (conn == null) return;
        try {
            conn.rollback();
            details.add(name + ": ROLLED_BACK");
        } catch (SQLException e) {
            details.add(name + ": ROLLBACK_FAILED — " + e.getMessage());
            log.error("2PC rollback failed for {}: {}", name, e.getMessage());
        }
    }

    private void close(Connection conn) {
        if (conn == null) return;
        try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        try { conn.close(); } catch (SQLException ignored) {}
    }

    private String shardJdbcUrl(int index) {
        int port = shardPortBase + index;
        return "jdbc:mysql://" + shardHost + ":" + port + "/" + shardDbPrefix + index
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    }

    private String shardDsName(int index) {
        return "shard_" + index;
    }

    // ---- DTOs ----

    public record TwoPhasePlan(
            String traceId,
            int shardIndex,
            List<SqlStatement> primaryStatements,
            List<SqlStatement> shardStatements,
            String summary
    ) {}

    public record SqlStatement(String sql, Object... params) {}

    public record TwoPhaseResult(
            boolean success,
            String traceId,
            List<ParticipantVote> votes,
            List<String> phase1Results,
            List<String> phase2Results,
            int elapsedMs
    ) {}

    public record ParticipantVote(String dataSourceName, Vote vote, String detail) {}

    public enum Vote { OK, ABORT, TIMEOUT }
}
