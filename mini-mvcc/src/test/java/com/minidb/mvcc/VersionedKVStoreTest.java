package com.minidb.mvcc;

import com.minidb.mvcc.WriteConflictException;
import com.minidb.mvcc.IsolationLevel;
import com.minidb.mvcc.Transaction;
import com.minidb.mvcc.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VersionedKVStoreTest {
    private VersionedKVStore store;
    private TransactionManager txnManager;

    @BeforeEach
    void setUp() {
        txnManager = new TransactionManager();
        store = new VersionedKVStore(txnManager);
    }

    @Test
    void shouldSeeOwnWrite() {
        Transaction txn = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(txn, "A", bytes("100"));
        Optional<byte[]> result = store.get(txn, "A");
        assertTrue(result.isPresent());
        assertEquals("100", str(result.get()));
    }

    @Test
    void shouldNotSeeUncommittedWrite() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("100"));

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        Optional<byte[]> result = store.get(t2, "A");
        assertFalse(result.isPresent());
    }

    @Test
    void shouldSeeCommittedWrite() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("100"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        Optional<byte[]> result = store.get(t2, "A");
        assertTrue(result.isPresent());
        assertEquals("100", str(result.get()));
    }

    @Test
    void shouldRollbackToOldValue() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("old"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t2, "A", bytes("new"));
        store.rollbackTransaction(t2);

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        Optional<byte[]> result = store.get(t3, "A");
        assertTrue(result.isPresent());
        assertEquals("old", str(result.get()));
    }

    @Test
    void shouldShowVersionChain() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("v1"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t2, "A", bytes("v2"));
        txnManager.commit(t2);

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t3, "A", bytes("v3"));
        txnManager.commit(t3);

        var chain = store.versionChain("A");
        assertFalse(chain.isEmpty());
        assertEquals("v3", str(chain.get(0).value()));
    }

    @Test
    void shouldDetectWriteConflict() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("100"));

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        assertThrows(WriteConflictException.class, () -> store.put(t2, "A", bytes("200")));
    }

    @Test
    void shouldPreserveVersionChainAfterRollback() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("v1"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t2, "A", bytes("v2"));
        txnManager.commit(t2);

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t3, "A", bytes("v3-to-rollback"));
        store.rollbackTransaction(t3);

        var chain = store.versionChain("A");
        assertEquals(2, chain.size(), "Should have v2 and v1 in chain");
        assertEquals("v2", str(chain.get(0).value()));
        assertEquals("v1", str(chain.get(1).value()));
    }

    @Test
    void shouldDeleteByMarking() {
        Transaction t1 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(t1, "A", bytes("100"));
        txnManager.commit(t1);

        Transaction t2 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.delete(t2, "A");
        txnManager.commit(t2);

        Transaction t3 = txnManager.begin(IsolationLevel.READ_COMMITTED);
        assertFalse(store.get(t3, "A").isPresent());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
