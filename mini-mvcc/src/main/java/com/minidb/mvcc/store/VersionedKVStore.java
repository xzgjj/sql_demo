package com.minidb.mvcc.store;

import com.minidb.mvcc.conflict.WriteConflictException;
import com.minidb.mvcc.txn.IsolationLevel;
import com.minidb.mvcc.txn.Transaction;
import com.minidb.mvcc.txn.TransactionManager;
import com.minidb.mvcc.undo.UndoLogRecord;
import com.minidb.mvcc.visibility.ReadView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class VersionedKVStore {
    private final ConcurrentHashMap<String, RecordVersion> latestVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, RecordVersion> allVersions = new ConcurrentHashMap<>();
    private final AtomicLong versionIdSeq = new AtomicLong(1);
    private final AtomicLong undoIdSeq = new AtomicLong(1);
    private final TransactionManager txnManager;

    public VersionedKVStore(TransactionManager txnManager) {
        this.txnManager = txnManager;
    }

    public Optional<byte[]> get(Transaction txn, String key) {
        ReadView rv = resolveReadView(txn);
        RecordVersion current = latestVersions.get(key);
        while (current != null) {
            if (rv.isVisible(current.createdTxnId(), current.deletedTxnId())) {
                if (current.deletedTxnId() != 0) {
                    return Optional.empty();
                }
                return Optional.ofNullable(current.value());
            }
            if (current.undoPtr() == 0) {
                return Optional.empty();
            }
            current = allVersions.get(current.undoPtr());
        }
        return Optional.empty();
    }

    public void put(Transaction txn, String key, byte[] value) {
        if (!txn.isActive()) {
            throw new IllegalStateException(
                    "Transaction " + txn.txnId() + " is not active");
        }
        latestVersions.compute(key, (k, current) -> {
            if (current == null) {
                long vid = versionIdSeq.getAndIncrement();
                RecordVersion v = new RecordVersion(key, value, txn.txnId(), 0, 0);
                allVersions.put(vid, v);
                return v;
            }
            if (current.createdTxnId() == txn.txnId()) {
                long prevPtr = current.undoPtr();
                long vid = versionIdSeq.getAndIncrement();
                RecordVersion v = new RecordVersion(key, value, txn.txnId(), 0, prevPtr);
                allVersions.put(vid, v);
                return v;
            }
            if (isActive(current.createdTxnId())) {
                throw new WriteConflictException(key, txn.txnId(), current.createdTxnId());
            }
            long undoVid = versionIdSeq.getAndIncrement();
            long undoId = undoIdSeq.getAndIncrement();
            long newVid = versionIdSeq.getAndIncrement();
            allVersions.put(undoVid, new RecordVersion(key, current.value(),
                    current.createdTxnId(), current.deletedTxnId(), current.undoPtr()));
            txn.addUndoLog(new UndoLogRecord(undoId, key, current.value(),
                    current.createdTxnId(), current.deletedTxnId(), 0));
            RecordVersion v = new RecordVersion(key, value, txn.txnId(), 0, undoVid);
            allVersions.put(newVid, v);
            return v;
        });
    }

    public void delete(Transaction txn, String key) {
        if (!txn.isActive()) {
            throw new IllegalStateException(
                    "Transaction " + txn.txnId() + " is not active");
        }
        latestVersions.compute(key, (k, current) -> {
            if (current == null) {
                return null;
            }
            if (current.deletedTxnId() != 0) {
                return current;
            }
            if (current.createdTxnId() != txn.txnId()
                    && isActive(current.createdTxnId())) {
                throw new WriteConflictException(key, txn.txnId(), current.createdTxnId());
            }
            long undoId = undoIdSeq.getAndIncrement();
            txn.addUndoLog(new UndoLogRecord(undoId, key, current.value(),
                    current.createdTxnId(), current.deletedTxnId(), 0));
            return current.withDeletedTxnId(txn.txnId());
        });
    }

    public void rollbackTransaction(Transaction txn) {
        var undoLogs = txn.undoLogs();
        for (int i = undoLogs.size() - 1; i >= 0; i--) {
            UndoLogRecord undo = undoLogs.get(i);
            restoreFromUndo(undo);
        }
        txnManager.abort(txn);
    }

    public List<RecordVersion> versionChain(String key) {
        List<RecordVersion> chain = new ArrayList<>();
        RecordVersion current = latestVersions.get(key);
        while (current != null) {
            chain.add(current);
            if (current.undoPtr() == 0) {
                break;
            }
            current = allVersions.get(current.undoPtr());
        }
        return Collections.unmodifiableList(chain);
    }

    private void restoreFromUndo(UndoLogRecord undo) {
        String key = undo.key();
        latestVersions.compute(key, (k, current) -> {
            if (current != null) {
                return new RecordVersion(key, undo.oldValue(),
                        undo.oldCreatedTxnId(), undo.oldDeletedTxnId(), undo.prevUndoId());
            }
            return null;
        });
    }

    private boolean isActive(long txnId) {
        return txnManager.activeTransactions().stream()
                .anyMatch(t -> t.txnId() == txnId);
    }

    private ReadView resolveReadView(Transaction txn) {
        if (txn.isolationLevel() == IsolationLevel.REPEATABLE_READ) {
            ReadView existing = txn.readView();
            if (existing != null) {
                return existing;
            }
            ReadView rv = txnManager.currentReadView(txn);
            txn.setReadView(rv);
            return rv;
        }
        return txnManager.currentReadView(txn);
    }
}
