package com.minidb.mvcc;

import com.minidb.mvcc.ReadView;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class TransactionManager {
    private final AtomicLong nextTxnId = new AtomicLong(1);
    private final Map<Long, Transaction> activeTransactions = new ConcurrentHashMap<>();

    public Transaction begin(IsolationLevel level) {
        long txnId = nextTxnId.getAndIncrement();
        Transaction txn = new Transaction(txnId, level);
        activeTransactions.put(txnId, txn);
        if (level == IsolationLevel.REPEATABLE_READ) {
            txn.setReadView(currentReadView(txn));
        }
        return txn;
    }

    public ReadView currentReadView(Transaction txn) {
        Set<Long> activeIds = activeTransactions.keySet().stream()
                .filter(id -> id != txn.txnId())
                .collect(Collectors.toSet());
        long low = activeIds.stream().min(Long::compare).orElse(txn.txnId());
        long high = nextTxnId.get();
        return new ReadView(txn.txnId(), low, high, activeIds);
    }

    public void commit(Transaction txn) {
        if (!txn.isActive()) {
            throw new IllegalStateException("Transaction " + txn.txnId() + " is not active");
        }
        txn.markCommitted();
        activeTransactions.remove(txn.txnId());
    }

    public void abort(Transaction txn) {
        if (!txn.isActive()) {
            throw new IllegalStateException("Transaction " + txn.txnId() + " is not active");
        }
        txn.markAborted();
        activeTransactions.remove(txn.txnId());
    }

    public Set<Transaction> activeTransactions() {
        return Set.copyOf(activeTransactions.values());
    }

    public boolean isActive(long txnId) {
        return activeTransactions.containsKey(txnId);
    }

    public long nextTxnId() {
        return nextTxnId.get();
    }
}
