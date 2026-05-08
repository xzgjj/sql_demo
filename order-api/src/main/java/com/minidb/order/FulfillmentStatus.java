package com.minidb.order;

/**
 * 履约任务状态。
 */
public enum FulfillmentStatus {
    PENDING_CLAIM(10, "待领取"),
    PICKING(20, "拣货中"),
    PICKED(30, "已拣货"),
    SHIPPED(40, "已发货"),
    COMPLETED(50, "已完成"),
    EXCEPTION(90, "异常");

    private final int code;
    private final String label;

    FulfillmentStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    public static FulfillmentStatus fromCode(int code) {
        for (FulfillmentStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown fulfillment status code: " + code);
    }
}
