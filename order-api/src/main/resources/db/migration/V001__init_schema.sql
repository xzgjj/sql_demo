-- V001: 初始化数据库表结构
-- MiniDB-Lab Phase 4: 订单单库闭环基础表

-- ===================== 商品与库存 =====================

CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL DEFAULT 0,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=上架 0=下架',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_products_sku (sku),
    KEY idx_products_category_status (category_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_inventory (
    product_id BIGINT PRIMARY KEY,
    available_qty INT NOT NULL DEFAULT 0 COMMENT '可售库存',
    locked_qty INT NOT NULL DEFAULT 0 COMMENT '锁定库存(已下单未发货)',
    shipped_qty INT NOT NULL DEFAULT 0 COMMENT '已出库库存',
    version INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (available_qty >= 0),
    CHECK (locked_qty >= 0),
    CHECK (shipped_qty >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventory_journals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    biz_type VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATE/ORDER_CANCEL/FULFILL_SHIP',
    biz_no VARCHAR(64) NOT NULL,
    change_available INT NOT NULL DEFAULT 0,
    change_locked INT NOT NULL DEFAULT 0,
    change_shipped INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_biz (biz_type, biz_no, product_id),
    KEY idx_inventory_product_created (product_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== 订单与明细 =====================

CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 10 COMMENT '10=待支付 20=已支付 30=已取消 40=待履约 50=已发货 60=已完成 70=退款中 80=已退款 90=异常',
    total_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    remark VARCHAR(256) DEFAULT NULL,
    expires_at DATETIME NOT NULL,
    paid_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    completed_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_user_created (user_id, created_at),
    KEY idx_orders_status_expires (status, expires_at),
    KEY idx_orders_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    quantity INT NOT NULL,
    line_amount DECIMAL(12,2) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_items_user_order (user_id, order_id),
    KEY idx_order_items_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== 支付 =====================

CREATE TABLE payments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL DEFAULT 'mock_pay',
    channel_trade_no VARCHAR(128) NULL,
    amount DECIMAL(12,2) NOT NULL,
    status TINYINT NOT NULL DEFAULT 10 COMMENT '10=待支付 20=成功 30=失败 40=退款中 50=已退款 90=异常',
    paid_at DATETIME NULL,
    raw_callback JSON NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payments_payment_no (payment_no),
    UNIQUE KEY uk_payments_channel_trade (channel, channel_trade_no),
    KEY idx_payments_user_order (user_id, order_id),
    KEY idx_payments_order_status (order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== 履约 =====================

CREATE TABLE fulfillment_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 10 COMMENT '10=待领取 20=拣货中 30=已拣货 40=已发货 50=已完成 90=异常',
    assignee_id BIGINT NULL,
    claimed_at DATETIME NULL,
    picked_at DATETIME NULL,
    shipped_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fulfillment_task_no (task_no),
    UNIQUE KEY uk_fulfillment_order (order_id),
    KEY idx_fulfillment_user_status (user_id, status),
    KEY idx_fulfillment_assignee_status (assignee_id, status),
    KEY idx_fulfillment_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    carrier VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_shipments_user_order (user_id, order_id),
    KEY idx_shipments_tracking (tracking_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== 工程保障 =====================

CREATE TABLE idempotency_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL COMMENT '用户ID或系统标识',
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(32) NULL,
    resource_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 10 COMMENT '10=处理中 20=已完成 30=失败',
    response_body JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotency_actor_key (actor_type, actor_id, idempotency_key),
    KEY idx_idempotency_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL COMMENT 'ORDER_CREATED/ORDER_PAID/FULFILLMENT_REQUIRED/ORDER_SHIPPED',
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 10 COMMENT '10=NEW 20=PROCESSING 30=DELIVERED 40=FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_outbox_status_retry (status, next_retry_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_status_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    from_status TINYINT NULL,
    to_status TINYINT NOT NULL,
    operator VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    reason VARCHAR(256) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_logs_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE exception_tickets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_type VARCHAR(32) NOT NULL COMMENT 'PAYMENT/INVENTORY/FULFILLMENT/ROUTING',
    biz_no VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    detail JSON NULL,
    status TINYINT NOT NULL DEFAULT 10 COMMENT '10=OPEN 20=IN_PROGRESS 30=RESOLVED 40=CLOSED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_exception_status_created (status, created_at),
    KEY idx_exception_biz (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
