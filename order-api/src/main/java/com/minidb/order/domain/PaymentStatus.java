package com.minidb.order.domain;

/**
 * 支付状态。
 */
public enum PaymentStatus {
    PENDING(10, "待支付"),
    SUCCESS(20, "支付成功"),
    FAILED(30, "支付失败"),
    REFUNDING(40, "退款中"),
    REFUNDED(50, "已退款"),
    EXCEPTION(90, "异常");

    private final int code;
    private final String label;

    PaymentStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    public static PaymentStatus fromCode(int code) {
        for (PaymentStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown payment status code: " + code);
    }
}
