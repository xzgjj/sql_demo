package com.minidb.mvcc;

public class UndoLogRecord {
    private final long undoId;
    private final String key;
    private final byte[] oldValue;
    private final long oldCreatedTxnId;
    private final long oldDeletedTxnId;
    private final long prevUndoId;

    public UndoLogRecord(long undoId, String key, byte[] oldValue,
                         long oldCreatedTxnId, long oldDeletedTxnId,
                         long prevUndoId) {
        this.undoId = undoId;
        this.key = key;
        this.oldValue = oldValue != null ? oldValue.clone() : null;
        this.oldCreatedTxnId = oldCreatedTxnId;
        this.oldDeletedTxnId = oldDeletedTxnId;
        this.prevUndoId = prevUndoId;
    }

    public long undoId() {
        return undoId;
    }

    public String key() {
        return key;
    }

    public byte[] oldValue() {
        return oldValue != null ? oldValue.clone() : null;
    }

    public long oldCreatedTxnId() {
        return oldCreatedTxnId;
    }

    public long oldDeletedTxnId() {
        return oldDeletedTxnId;
    }

    public long prevUndoId() {
        return prevUndoId;
    }
}
