package com.minidb.mvcc;

import java.util.ArrayList;
import java.util.List;

public class Transaction {
    private final long txnId;
    private final IsolationLevel isolationLevel;
    private volatile TransactionStatus status;
    private volatile ReadView readView;
    private final List<UndoLogRecord> undoLogs;
    private final long startTimestamp;

    public Transaction(long txnId, IsolationLevel isolationLevel) {
        this.txnId = txnId;
        this.isolationLevel = isolationLevel;
        this.status = TransactionStatus.ACTIVE;
        this.undoLogs = new ArrayList<>();
        this.startTimestamp = System.currentTimeMillis();
    }

    public long txnId() {
        return txnId;
    }

    public IsolationLevel isolationLevel() {
        return isolationLevel;
    }

    public TransactionStatus status() {
        return status;
    }

    public ReadView readView() {
        return readView;
    }

    public void setReadView(ReadView readView) {
        this.readView = readView;
    }

    public List<UndoLogRecord> undoLogs() {
        return undoLogs;
    }

    public void addUndoLog(UndoLogRecord record) {
        this.undoLogs.add(record);
    }

    public long startTimestamp() {
        return startTimestamp;
    }

    public void markCommitted() {
        this.status = TransactionStatus.COMMITTED;
    }

    public void markAborted() {
        this.status = TransactionStatus.ABORTED;
    }

    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }
}
