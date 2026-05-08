package com.minidb.mvcc;

import com.minidb.mvcc.WriteConflictException;
import com.minidb.mvcc.IsolationLevel;
import com.minidb.mvcc.Transaction;
import com.minidb.mvcc.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MvccConcurrencyTest {
    private TransactionManager txnManager;
    private VersionedKVStore store;

    @BeforeEach
    void setUp() {
        txnManager = new TransactionManager();
        store = new VersionedKVStore(txnManager);
    }

    @Test
    void concurrentPutsOnDifferentKeysShouldAllSucceed() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final String key = "Key" + i;
            executor.submit(() -> {
                try {
                    Transaction txn = txnManager.begin(IsolationLevel.READ_COMMITTED);
                    store.put(txn, key, bytes("val-" + key));
                    txnManager.commit(txn);
                    successes.incrementAndGet();
                } catch (WriteConflictException e) {
                    conflicts.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threads, successes.get());
        assertEquals(0, conflicts.get());
    }

    @Test
    void concurrentPutsOnSameKeyShouldCauseConflict() throws Exception {
        Transaction holder = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(holder, "shared", bytes("holder-value"));

        int challengers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(challengers);
        CountDownLatch doneLatch = new CountDownLatch(challengers);
        AtomicInteger conflicts = new AtomicInteger(0);

        for (int i = 0; i < challengers; i++) {
            executor.submit(() -> {
                try {
                    Transaction txn = txnManager.begin(IsolationLevel.READ_COMMITTED);
                    store.put(txn, "shared", bytes("value"));
                    txnManager.commit(txn);
                } catch (WriteConflictException e) {
                    conflicts.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(challengers, conflicts.get(),
                "All challengers should conflict with the active holder transaction");
    }

    @Test
    void concurrentReadersShouldNotBlockEachOther() throws Exception {
        Transaction writer = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(writer, "shared", bytes("data"));
        txnManager.commit(writer);

        int readers = 20;
        ExecutorService executor = Executors.newFixedThreadPool(readers);
        CountDownLatch latch = new CountDownLatch(readers);
        AtomicInteger readCount = new AtomicInteger(0);

        for (int i = 0; i < readers; i++) {
            executor.submit(() -> {
                try {
                    Transaction txn = txnManager.begin(IsolationLevel.REPEATABLE_READ);
                    var result = store.get(txn, "shared");
                    if (result.isPresent()) {
                        readCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(readers, readCount.get());
    }

    @Test
    void threadSafetyUnderMixedReadWrite() throws Exception {
        Transaction writer = txnManager.begin(IsolationLevel.READ_COMMITTED);
        store.put(writer, "counter", bytes("0"));
        txnManager.commit(writer);

        int operations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(operations);
        AtomicInteger reads = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < operations; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    if (idx % 2 == 0) {
                        Transaction txn = txnManager.begin(IsolationLevel.READ_COMMITTED);
                        var val = store.get(txn, "counter");
                        if (val.isPresent()) {
                            reads.incrementAndGet();
                        }
                    } else {
                        Transaction txn = txnManager.begin(IsolationLevel.READ_COMMITTED);
                        store.put(txn, "counter", bytes(String.valueOf(idx)));
                        txnManager.commit(txn);
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(reads.get() > 0, "Readers should succeed");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
