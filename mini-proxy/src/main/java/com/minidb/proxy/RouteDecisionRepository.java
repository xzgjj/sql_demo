package com.minidb.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Persists route decision logs to MySQL route_decision_logs table.
 * Uses async batching to avoid blocking the proxy event loop.
 */
public class RouteDecisionRepository {

    private static final Logger log = LoggerFactory.getLogger(RouteDecisionRepository.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int connectTimeoutMs;
    private final LinkedBlockingQueue<DecisionEntry> queue;
    private volatile boolean running;
    private Thread writerThread;

    public RouteDecisionRepository(String host, int port, String username, String password,
                                   String database, int connectTimeoutMs) {
        this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
        this.username = username;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.queue = new LinkedBlockingQueue<>(5000);
    }

    public void start() {
        running = true;
        writerThread = new Thread(this::drainLoop, "route-decision-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        log.info("RouteDecisionRepository started, writing to {}", jdbcUrl);
    }

    public void stop() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
        }
        // Flush remaining entries
        List<DecisionEntry> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            flushSync(batch);
        }
    }

    public void record(DecisionEntry entry) {
        if (!running) return;
        if (!queue.offer(entry)) {
            log.debug("Route decision queue full, dropping entry for {}", entry.traceId());
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                List<DecisionEntry> batch = new ArrayList<>();
                // Poll with timeout, then drain whatever's available
                DecisionEntry first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 99); // batch up to 100
                }
                if (!batch.isEmpty()) {
                    flushBatch(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Route decision drain error: {}", e.getMessage());
            }
        }
    }

    private void flushBatch(List<DecisionEntry> batch) {
        try (Connection conn = getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            String sql = "INSERT INTO route_decision_logs (trace_id, session_id, sql_digest, " +
                    "key_type, key_value, target_ds, decision, reason, status, elapsed_ms) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (DecisionEntry e : batch) {
                    ps.setString(1, e.traceId());
                    ps.setString(2, e.sessionId());
                    ps.setString(3, digest(e.sqlPreview()));
                    ps.setString(4, e.keyType());
                    ps.setString(5, e.keyValue());
                    ps.setString(6, e.target());
                    ps.setString(7, e.decision());
                    ps.setString(8, e.reason());
                    ps.setString(9, e.status());
                    ps.setInt(10, e.elapsedMs());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.debug("Failed to flush route decision batch: {}", e.getMessage());
        }
    }

    private void flushSync(List<DecisionEntry> batch) {
        try (Connection conn = getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            String sql = "INSERT INTO route_decision_logs (trace_id, session_id, sql_digest, " +
                    "key_type, key_value, target_ds, decision, reason, status, elapsed_ms) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (DecisionEntry e : batch) {
                    ps.setString(1, e.traceId());
                    ps.setString(2, e.sessionId());
                    ps.setString(3, digest(e.sqlPreview()));
                    ps.setString(4, e.keyType());
                    ps.setString(5, e.keyValue());
                    ps.setString(6, e.target());
                    ps.setString(7, e.decision());
                    ps.setString(8, e.reason());
                    ps.setString(9, e.status());
                    ps.setInt(10, e.elapsedMs());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            }
        } catch (Exception e) {
            log.warn("Failed to flush route decisions on shutdown: {}", e.getMessage());
        }
    }

    private Connection getConnection() {
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user", username);
            props.setProperty("password", password);
            props.setProperty("connectTimeout", String.valueOf(connectTimeoutMs));
            return DriverManager.getConnection(jdbcUrl, props);
        } catch (SQLException e) {
            log.debug("RouteDecisionRepository connection failed: {}", e.getMessage());
            return null;
        }
    }

    private String digest(String sql) {
        if (sql == null || sql.isBlank()) return "empty";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(sql.hashCode());
        }
    }

    public record DecisionEntry(
            String traceId, String sessionId, String sqlPreview,
            String keyType, String keyValue, String target,
            String decision, String reason, String status, int elapsedMs) {}
}
