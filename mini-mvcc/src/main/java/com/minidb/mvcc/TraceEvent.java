package com.minidb.mvcc;

import java.time.Instant;

public class TraceEvent {
    private final long sequence;
    private final long txnId;
    private final String operation;
    private final String key;
    private final byte[] valueSnapshot;
    private final String detail;
    private final Instant timestamp;
    private final ReadView readView;
    private final String isolationLevel;

    public TraceEvent(long sequence, long txnId, String operation, String key,
                      byte[] valueSnapshot, String detail) {
        this(sequence, txnId, operation, key, valueSnapshot, detail, null, null);
    }

    public TraceEvent(long sequence, long txnId, String operation, String key,
                      byte[] valueSnapshot, String detail,
                      ReadView readView, String isolationLevel) {
        this.sequence = sequence;
        this.txnId = txnId;
        this.operation = operation;
        this.key = key;
        this.valueSnapshot = valueSnapshot != null ? valueSnapshot.clone() : null;
        this.detail = detail;
        this.timestamp = Instant.now();
        this.readView = readView;
        this.isolationLevel = isolationLevel;
    }

    public long sequence() { return sequence; }
    public long txnId() { return txnId; }
    public String operation() { return operation; }
    public String key() { return key; }
    public byte[] valueSnapshot() { return valueSnapshot != null ? valueSnapshot.clone() : null; }
    public String detail() { return detail; }
    public Instant timestamp() { return timestamp; }
    public ReadView readView() { return readView; }
    public String isolationLevel() { return isolationLevel; }

    @Override
    public String toString() {
        return String.format("[%d] txn=%d %s key=%s%s",
                sequence, txnId, operation, key,
                detail != null ? " " + detail : "");
    }
}
