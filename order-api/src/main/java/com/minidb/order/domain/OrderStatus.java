package com.minidb.order.domain;

import java.util.Set;

/**
 * 订单状态机。
 * 所有状态流转必须经过此枚举验证。
 */
public enum OrderStatus {
    PENDING_PAYMENT(10, "待支付"),
    PAID(20, "已支付"),
    CANCELLED(30, "已取消"),
    PENDING_FULFILLMENT(40, "待履约"),
    SHIPPED(50, "已发货"),
    COMPLETED(60, "已完成"),
    REFUNDING(70, "退款中"),
    REFUNDED(80, "已退款"),
    EXCEPTION(90, "异常");

    private final int code;
    private final String label;

    OrderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    public static OrderStatus fromCode(int code) {
        for (OrderStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown order status code: " + code);
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING_PAYMENT -> target == PAID || target == CANCELLED;
            case PAID -> target == PENDING_FULFILLMENT || target == REFUNDING || target == EXCEPTION;
            case PENDING_FULFILLMENT -> target == SHIPPED || target == EXCEPTION;
            case SHIPPED -> target == COMPLETED;
            case REFUNDING -> target == REFUNDED;
            case CANCELLED, COMPLETED, REFUNDED, EXCEPTION -> false;
        };
    }

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case PENDING_PAYMENT -> Set.of(PAID, CANCELLED);
            case PAID -> Set.of(PENDING_FULFILLMENT, REFUNDING, EXCEPTION);
            case PENDING_FULFILLMENT -> Set.of(SHIPPED, EXCEPTION);
            case SHIPPED -> Set.of(COMPLETED);
            case REFUNDING -> Set.of(REFUNDED);
            default -> Set.of();
        };
    }
}
