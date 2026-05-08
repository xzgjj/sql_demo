package com.minidb.mvcc;

import com.minidb.mvcc.IsolationLevel;
import com.minidb.mvcc.Transaction;
import com.minidb.mvcc.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MvccIsolationTest {
    private VersionedKVStore store;
    private TransactionManager txnManager;

    @BeforeEach
    void setUp() {
        txnManager = new TransactionManager();
        store = new VersionedKVStore(txnManager);
    }

    @Test
    void rcShouldSeeOtherCommittedChangesOnSecondRead() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("initial"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        assertEquals("initial", str(store.get(t2, "A").get()));

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t3, "A", bytes("updated"));
        txnManager.commit(t3);

        assertEquals("updated", str(store.get(t2, "A").get()));
    }

    @Test
    void rrShouldNotSeeOtherCommittedChangesAfterFirstRead() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("initial"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.REPEATABLE_READ);
        assertEquals("initial", str(store.get(t2, "A").get()));

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t3, "A", bytes("updated"));
        txnManager.commit(t3);

        assertEquals("initial", str(store.get(t2, "A").get()));
    }

    @Test
    void rrShouldSeeOwnWrites() {
        Transaction t1 = txnManager.begin(IsolationLevel.REPEATABLE_READ);
        store.put(t1, "A", bytes("v1"));
        assertEquals("v1", str(store.get(t1, "A").get()));
        store.put(t1, "A", bytes("v2"));
        assertEquals("v2", str(store.get(t1, "A").get()));
    }

    @Test
    void rcEachReadCreatesNewReadView() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("v1"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        var result1 = store.get(t2, "A");

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t3, "A", bytes("v2"));
        txnManager.commit(t3);

        var result2 = store.get(t2, "A");

        assertEquals("v1", str(result1.get()));
        assertEquals("v2", str(result2.get()));
    }

    @Test
    void rrReadViewCapturesBeginTimeSnapshot() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("before"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.REPEATABLE_READ);

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t3, "A", bytes("after"));
        txnManager.commit(t3);

        assertEquals("before", str(store.get(t2, "A").get()));
    }

    @Test
    void shouldNotSeeAbortedChanges() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("aborted"));
        store.rollbackTransaction(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        assertFalse(store.get(t2, "A").isPresent());
    }

    @Test
    void deleteShouldBeVisibleAfterCommit() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("value"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.delete(t2, "A");
        txnManager.commit(t2);

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        assertFalse(store.get(t3, "A").isPresent());
    }

    @Test
    void rrShouldNotSeeDeletedVersion() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("value"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.REPEATABLE_READ);
        assertEquals("value", str(store.get(t2, "A").get()));

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.delete(t3, "A");
        txnManager.commit(t3);

        assertEquals("value", str(store.get(t2, "A").get()));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
