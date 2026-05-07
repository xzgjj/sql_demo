package com.minidb.mvcc.conflict;

public class WriteConflictException extends RuntimeException {
    private final String key;
    private final long currentTxnId;
    private final long conflictingTxnId;

    public WriteConflictException(String key, long currentTxnId, long conflictingTxnId) {
        super("Write conflict on key '" + key + "': txn " + currentTxnId
                + " conflicts with active txn " + conflictingTxnId);
        this.key = key;
        this.currentTxnId = currentTxnId;
        this.conflictingTxnId = conflictingTxnId;
    }

    public String key() {
        return key;
    }

    public long currentTxnId() {
        return currentTxnId;
    }

    public long conflictingTxnId() {
        return conflictingTxnId;
    }
}
