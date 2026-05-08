package com.minidb.mvcc;

import com.minidb.mvcc.RecordVersion;
import com.minidb.mvcc.Transaction;

public final class VisibilityChecker {

    private VisibilityChecker() {
    }

    public static boolean isVisible(RecordVersion version, ReadView readView) {
        return readView.isVisible(version.createdTxnId(), version.deletedTxnId());
    }

    public static boolean isVisible(RecordVersion version, Transaction txn) {
        ReadView rv = txn.readView();
        if (rv == null) {
            return false;
        }
        return rv.isVisible(version.createdTxnId(), version.deletedTxnId());
    }
}
