package com.minidb.mvcc.store;

public class RecordVersion {
    private final String key;
    private final byte[] value;
    private final long createdTxnId;
    private final long deletedTxnId;
    private final long undoPtr;

    public RecordVersion(String key, byte[] value, long createdTxnId,
                         long deletedTxnId, long undoPtr) {
        this.key = key;
        this.value = value != null ? value.clone() : null;
        this.createdTxnId = createdTxnId;
        this.deletedTxnId = deletedTxnId;
        this.undoPtr = undoPtr;
    }

    public String key() {
        return key;
    }

    public byte[] value() {
        return value != null ? value.clone() : null;
    }

    public long createdTxnId() {
        return createdTxnId;
    }

    public long deletedTxnId() {
        return deletedTxnId;
    }

    public long undoPtr() {
        return undoPtr;
    }

    public RecordVersion withDeletedTxnId(long newDeletedTxnId) {
        return new RecordVersion(key, value, createdTxnId, newDeletedTxnId, undoPtr);
    }
}
