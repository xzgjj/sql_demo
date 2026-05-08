package com.minidb.proxy;

import com.alibaba.druid.pool.DruidDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class RouteTableLookup {

    private static final Logger log = LoggerFactory.getLogger(RouteTableLookup.class);

    private final DruidDataSource dataSource;

    public RouteTableLookup(String host, int port, String username, String password,
                           String database, int connectTimeoutMs) {
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8mb4",
                host, port, database);
        this.dataSource = new DruidDataSource();
        this.dataSource.setUrl(jdbcUrl);
        this.dataSource.setUsername(username);
        this.dataSource.setPassword(password);
        this.dataSource.setMaxActive(4);
        this.dataSource.setMinIdle(1);
        this.dataSource.setInitialSize(1);
        this.dataSource.setMaxWait(connectTimeoutMs);
        this.dataSource.setConnectTimeout(connectTimeoutMs);
        this.dataSource.setSocketTimeout(connectTimeoutMs);
        this.dataSource.setTestOnBorrow(true);
        this.dataSource.setValidationQuery("SELECT 1");
        this.dataSource.setTestWhileIdle(true);
        this.dataSource.setTimeBetweenEvictionRunsMillis(30000);
        this.dataSource.setMinEvictableIdleTimeMillis(60000);
    }

    public Long lookupByOrderNo(String orderNo) {
        return lookup("order_no", orderNo);
    }

    public Long lookupByPaymentNo(String paymentNo) {
        return lookup("payment_no", paymentNo);
    }

    private Long lookup(String column, String value) {
        String sql = "SELECT user_id FROM order_route WHERE " + column + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long userId = rs.getLong("user_id");
                    log.debug("Route lookup: {}='{}' -> user_id={}", column, value, userId);
                    return userId;
                }
            }
        } catch (SQLException e) {
            log.error("Route lookup failed: {}='{}'", column, value, e);
            throw new RouteLookupException("ROUTE_LOOKUP_FAILED",
                    "Failed to lookup route for " + column + "=" + value + ": " + e.getMessage());
        }
        log.debug("Route not found: {}='{}'", column, value);
        return null;
    }

    public void writeRoute(String orderNo, long userId) {
        String sql = "INSERT INTO order_route (order_no, user_id, biz_type) VALUES (?, ?, 'ORDER') " +
                     "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), updated_at = NOW()";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderNo);
            ps.setLong(2, userId);
            ps.executeUpdate();
            log.debug("Route written: order_no='{}' -> user_id={}", orderNo, userId);
        } catch (SQLException e) {
            log.error("Failed to write route: order_no='{}' -> user_id={}", orderNo, userId, e);
            throw new RouteLookupException("ROUTE_WRITE_FAILED",
                    "Failed to write route for order_no=" + orderNo + ": " + e.getMessage());
        }
    }

    public void bindPayment(String paymentNo, String orderNo) {
        String sql = "UPDATE order_route SET payment_no = ?, biz_type = 'PAYMENT', updated_at = NOW() " +
                     "WHERE order_no = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentNo);
            ps.setString(2, orderNo);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                log.warn("No order_route row found for order_no='{}' when binding payment_no='{}'",
                        orderNo, paymentNo);
            } else {
                log.debug("Payment bound: payment_no='{}' -> order_no='{}'", paymentNo, orderNo);
            }
        } catch (SQLException e) {
            log.error("Failed to bind payment: payment_no='{}' -> order_no='{}'", paymentNo, orderNo, e);
            throw new RouteLookupException("ROUTE_BIND_FAILED",
                    "Failed to bind payment for order_no=" + orderNo + ": " + e.getMessage());
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public static class RouteLookupException extends RuntimeException {
        private final String errorCode;

        public RouteLookupException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() { return errorCode; }
    }
}
