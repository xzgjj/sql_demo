package com.minidb.order;

/**
 * 幂等记录状态。
 */
public enum IdempotencyStatus {
    PROCESSING(10, "处理中"),
    COMPLETED(20, "已完成"),
    FAILED(30, "失败");

    private final int code;
    private final String label;

    IdempotencyStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }
}
