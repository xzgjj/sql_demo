package com.minidb.mvcc.txn;

public enum TransactionStatus {
    ACTIVE,
    COMMITTED,
    ABORTED
}
