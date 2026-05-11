package com.minidb.order.dto;

import java.util.List;

/**
 * Custom MVCC scenario definition — users define transactions, steps, and assertions.
 */
public record CustomScenarioRequest(
    List<TransactionDef> transactions,
    List<StepDef> steps,
    List<String> assertions
) {
    public record TransactionDef(String alias, String isolationLevel) {}

    public record StepDef(String txnAlias, String action, String key, String value) {}

    public boolean isValid() {
        if (transactions == null || transactions.isEmpty()) return false;
        if (steps == null || steps.isEmpty()) return false;
        if (transactions.size() > 4) return false;
        if (steps.size() > 20) return false;
        for (var txn : transactions) {
            if (txn.alias() == null || txn.alias().isBlank()) return false;
            if (txn.alias().length() > 8) return false;
            if (!"READ_COMMITTED".equals(txn.isolationLevel())
                    && !"REPEATABLE_READ".equals(txn.isolationLevel()))
                return false;
        }
        for (var step : steps) {
            if (step.txnAlias() == null || step.txnAlias().isBlank()) return false;
            if (step.action() == null) return false;
            if (step.key() != null && step.key().length() > 64) return false;
            if (step.value() != null && step.value().length() > 128) return false;
        }
        return true;
    }
}
