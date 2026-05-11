package com.minidb.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Best-effort trace and audit logging service.
 * Writes to sql_audit_logs. Failures here do NOT block business operations.
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);
    private final JdbcTemplate jdbc;

    public TraceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record a SQL execution with trace_id.
     */
    public void recordSql(String traceId, String sql, Long orderId, Long userId,
                          String routeKey, String targetDs, Integer targetShard,
                          String status, String errorCode, int elapsedMs) {
        try {
            jdbc.update(
                "INSERT INTO sql_audit_logs (trace_id, order_id, user_id, sql_digest, sql_summary, " +
                "route_key, target_ds, target_shard, status, error_code, elapsed_ms) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                traceId, orderId, userId,
                digest(sql), summarize(sql),
                routeKey, targetDs, targetShard,
                status, errorCode, elapsedMs
            );
        } catch (Exception e) {
            log.debug("Failed to write sql_audit_log (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * Record a 2PC transaction result.
     */
    public void record2pc(String traceId, TwoPhaseCoordinator.TwoPhaseResult result, String summary) {
        try {
            String status = result.success() ? "COMMITTED" : "ABORTED";
            String details = "Votes: " + result.votes().stream()
                    .map(v -> v.dataSourceName() + "=" + v.vote())
                    .reduce((a, b) -> a + ", " + b).orElse("none")
                    + " | Phase1: " + String.join("; ", result.phase1Results())
                    + " | Phase2: " + String.join("; ", result.phase2Results());

            jdbc.update(
                "INSERT INTO sql_audit_logs (trace_id, sql_digest, sql_summary, " +
                "target_ds, status, error_code, elapsed_ms) " +
                "VALUES (?,?,?,?,?,?,?)",
                traceId,
                digest("2PC:" + summary),
                "2PC " + summary + " — " + details,
                "COORDINATOR",
                status,
                result.success() ? null : "2PC_ABORTED",
                result.elapsedMs()
            );
        } catch (Exception e) {
            log.debug("Failed to write 2PC audit log (non-blocking): {}", e.getMessage());
        }
    }

    private String digest(String sql) {
        if (sql == null || sql.isBlank()) return "empty";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sql.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sql.hashCode());
        }
    }

    private String summarize(String sql) {
        if (sql == null || sql.isBlank()) return "empty";
        String compact = sql.trim().replaceAll("\\s+", " ");
        return compact.length() > 128 ? compact.substring(0, 125) + "..." : compact;
    }
}
