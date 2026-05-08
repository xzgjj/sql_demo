-- V003: 路由映射表（仅存在于 PRIMARY，用于无分片键时的路由查找）
-- MiniDB-Lab Phase 5: 订单接入代理与分片

CREATE TABLE order_route (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(64) NULL,
    user_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER/PAYMENT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_route_order_no (order_no),
    UNIQUE KEY uk_route_payment_no (payment_no),
    KEY idx_route_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
