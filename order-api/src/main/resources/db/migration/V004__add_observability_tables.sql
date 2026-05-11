-- V004: 添加可观测性审计表
-- sql_audit_logs: 记录每次 SQL 执行（仅 digest，非原文）
-- route_decision_logs: 持久化 proxy 路由决策

CREATE TABLE IF NOT EXISTS sql_audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    order_id BIGINT NULL,
    user_id BIGINT NULL,
    sql_digest VARCHAR(128) NOT NULL,
    sql_summary VARCHAR(512) NOT NULL,
    route_key VARCHAR(64) NULL,
    target_ds VARCHAR(64) NULL,
    target_shard INT NULL,
    tx_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    elapsed_ms INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_trace (trace_id),
    INDEX idx_audit_order (order_id, created_at),
    INDEX idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS route_decision_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    sql_digest VARCHAR(128) NOT NULL,
    key_type VARCHAR(32) NOT NULL,
    key_value VARCHAR(128) NULL,
    target_ds VARCHAR(64) NOT NULL,
    decision VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    elapsed_ms INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_route_trace (trace_id),
    INDEX idx_route_session (session_id, created_at),
    INDEX idx_route_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
