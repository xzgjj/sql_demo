package com.minidb.mvcc.scenario;

import com.minidb.mvcc.store.RecordVersion;
import com.minidb.mvcc.store.VersionedKVStore;
import com.minidb.mvcc.trace.TraceEvent;
import com.minidb.mvcc.txn.IsolationLevel;
import com.minidb.mvcc.txn.Transaction;
import com.minidb.mvcc.txn.TransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ScenarioRunner {
    private final TransactionManager txnManager;
    private final VersionedKVStore store;

    public ScenarioRunner(TransactionManager txnManager, VersionedKVStore store) {
        this.txnManager = txnManager;
        this.store = store;
    }

    public ScenarioResult run(List<ScenarioStep> steps) {
        List<TraceEvent> trace = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Transaction> transactions = new HashMap<>();
        long seq = 0;

        for (ScenarioStep step : steps) {
            seq++;
            try {
                switch (step.action()) {
                    case BEGIN -> {
                        IsolationLevel level = step.isolationLevel() != null
                                ? step.isolationLevel() : IsolationLevel.READ_COMMITTED;
                        Transaction txn = txnManager.begin(level);
                        transactions.put(step.transactionRef(), txn);
                        trace.add(new TraceEvent(seq, txn.txnId(), "BEGIN", "", null,
                                "isolation=" + level));
                    }
                    case PUT -> {
                        Transaction txn = transactions.get(step.transactionRef());
                        store.put(txn, step.key(), step.value());
                        trace.add(new TraceEvent(seq, txn.txnId(), "PUT", step.key(),
                                step.value(), null));
                    }
                    case GET -> {
                        Transaction txn = transactions.get(step.transactionRef());
                        Optional<byte[]> result = store.get(txn, step.key());
                        String label = step.label() != null ? step.label() : "";
                        if (result.isPresent()) {
                            trace.add(new TraceEvent(seq, txn.txnId(), "GET", step.key(),
                                    result.get(), label + " => " + str(result.get())));
                        } else {
                            trace.add(new TraceEvent(seq, txn.txnId(), "GET", step.key(),
                                    null, label + " => NOT_FOUND"));
                        }
                    }
                    case DELETE -> {
                        Transaction txn = transactions.get(step.transactionRef());
                        store.delete(txn, step.key());
                        trace.add(new TraceEvent(seq, txn.txnId(), "DELETE", step.key(),
                                null, null));
                    }
                    case COMMIT -> {
                        Transaction txn = transactions.get(step.transactionRef());
                        txnManager.commit(txn);
                        trace.add(new TraceEvent(seq, txn.txnId(), "COMMIT", "", null, null));
                    }
                    case ROLLBACK -> {
                        Transaction txn = transactions.get(step.transactionRef());
                        store.rollbackTransaction(txn);
                        trace.add(new TraceEvent(seq, txn.txnId(), "ROLLBACK", "", null, null));
                    }
                }
            } catch (Exception e) {
                errors.add("Step " + seq + " " + step.action() + ": " + e.getMessage());
                trace.add(new TraceEvent(seq, 0, "ERROR", step.key() != null ? step.key() : "",
                        null, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        Map<String, List<RecordVersion>> chains = new HashMap<>();
        for (TraceEvent event : trace) {
            String key = event.key();
            if (key != null && !key.isEmpty() && !chains.containsKey(key)) {
                List<RecordVersion> chain = store.versionChain(key);
                if (!chain.isEmpty()) {
                    chains.put(key, chain);
                }
            }
        }

        return new ScenarioResult(trace, chains, errors);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
