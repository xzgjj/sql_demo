package com.minidb.order.service;

import com.minidb.order.IdempotencyStatus;
import com.minidb.order.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public String tryAcquire(String idempotencyKey, String actorType, Long actorId, String requestBody) {
        String requestHash = sha256(requestBody);
        var existing = jdbc.query(
            "SELECT status, request_hash, response_body FROM idempotency_records WHERE idempotency_key=? AND actor_type=? AND actor_id=?",
            rs -> { if (!rs.next()) return null;
                return new Object[]{rs.getInt("status"), rs.getString("request_hash"), rs.getString("response_body")}; },
            idempotencyKey, actorType, actorId);
        if (existing != null) {
            int status = (int) existing[0];
            if (status == IdempotencyStatus.COMPLETED.getCode()) {
                if (requestHash.equals((String) existing[1])) return normalizeJsonColumn((String) existing[2]);
                else throw new BusinessException("DUPLICATE_REQUEST", "Same key but different content: " + idempotencyKey);
            } else if (status == IdempotencyStatus.PROCESSING.getCode())
                throw new BusinessException("REQUEST_IN_PROGRESS", "Concurrent request: " + idempotencyKey);
            else if (status == IdempotencyStatus.FAILED.getCode()) {
                if (!requestHash.equals((String) existing[1]))
                    throw new BusinessException("DUPLICATE_REQUEST", "Same key but different content: " + idempotencyKey);
                int updated = jdbc.update(
                    "UPDATE idempotency_records SET status=?, response_body=NULL WHERE idempotency_key=? AND actor_type=? AND actor_id=? AND status=?",
                    IdempotencyStatus.PROCESSING.getCode(), idempotencyKey, actorType, actorId, IdempotencyStatus.FAILED.getCode());
                if (updated == 0) throw new BusinessException("REQUEST_IN_PROGRESS", "Concurrent retry: " + idempotencyKey);
                return null;
            }
        }
        try {
            jdbc.update("INSERT INTO idempotency_records (idempotency_key,actor_type,actor_id,request_hash,status) VALUES (?,?,?,?,?)",
                idempotencyKey, actorType, actorId, requestHash, IdempotencyStatus.PROCESSING.getCode());
        } catch (DuplicateKeyException e) {
            for (int r = 0; r < 3; r++) {
                try { Thread.sleep(50L << r); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                var re = jdbc.query("SELECT status,request_hash,response_body FROM idempotency_records WHERE idempotency_key=? AND actor_type=? AND actor_id=?",
                    rs -> rs.next() ? new Object[]{rs.getInt("status"), rs.getString("request_hash"), rs.getString("response_body")} : null,
                    idempotencyKey, actorType, actorId);
                if (re != null) {
                    int status = (int) re[0];
                    if (status == IdempotencyStatus.COMPLETED.getCode()) {
                        if (requestHash.equals((String) re[1])) return normalizeJsonColumn((String) re[2]);
                        throw new BusinessException("DUPLICATE_REQUEST", "Same key different content");
                    }
                    if (status == IdempotencyStatus.FAILED.getCode() && requestHash.equals((String) re[1])) {
                        int updated = jdbc.update(
                            "UPDATE idempotency_records SET status=?, response_body=NULL WHERE idempotency_key=? AND actor_type=? AND actor_id=? AND status=?",
                            IdempotencyStatus.PROCESSING.getCode(), idempotencyKey, actorType, actorId, IdempotencyStatus.FAILED.getCode());
                        if (updated > 0) return null;
                    }
                    throw new BusinessException("REQUEST_IN_PROGRESS", "Concurrent request");
                }
            }
            throw new BusinessException("REQUEST_IN_PROGRESS", "Lock acquisition failed after retries");
        }
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String idempotencyKey, String actorType, Long actorId, String responseBody) {
        jdbc.update("UPDATE idempotency_records SET status=?, response_body=? WHERE idempotency_key=? AND actor_type=? AND actor_id=? AND status=?",
            IdempotencyStatus.COMPLETED.getCode(), responseBody, idempotencyKey, actorType, actorId, IdempotencyStatus.PROCESSING.getCode());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey, String actorType, Long actorId) {
        jdbc.update("UPDATE idempotency_records SET status=? WHERE idempotency_key=? AND actor_type=? AND actor_id=? AND status=?",
            IdempotencyStatus.FAILED.getCode(), idempotencyKey, actorType, actorId, IdempotencyStatus.PROCESSING.getCode());
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException("SHA-256 not available", e); }
    }

    private String normalizeJsonColumn(String value) {
        if (value == null) return null;
        boolean looksDoubleEncoded = value.startsWith("\"{")
                || value.startsWith("\"[")
                || value.startsWith("\"\\\"");
        if (!looksDoubleEncoded) return value;
        try {
            return objectMapper.readValue(value, String.class);
        } catch (Exception e) {
            return value;
        }
    }
}
