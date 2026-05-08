package com.minidb.mvcc;

import java.util.Objects;

public class ScenarioStep {
    public enum Action { BEGIN, PUT, GET, DELETE, COMMIT, ROLLBACK }

    private final Action action;
    private final String transactionRef;
    private final IsolationLevel isolationLevel;
    private final String key;
    private final byte[] value;
    private final String label;

    private ScenarioStep(Action action, String transactionRef, IsolationLevel isolationLevel,
                         String key, byte[] value, String label) {
        this.action = action;
        this.transactionRef = transactionRef;
        this.isolationLevel = isolationLevel;
        this.key = key;
        this.value = value != null ? value.clone() : null;
        this.label = label;
    }

    public static ScenarioStep begin(String ref, IsolationLevel level) {
        return new ScenarioStep(Action.BEGIN, ref, level, null, null, null);
    }

    public static ScenarioStep put(String ref, String key, byte[] value) {
        return new ScenarioStep(Action.PUT, ref, null, key, value, null);
    }

    public static ScenarioStep get(String ref, String key, String label) {
        return new ScenarioStep(Action.GET, ref, null, key, null, label);
    }

    public static ScenarioStep delete(String ref, String key) {
        return new ScenarioStep(Action.DELETE, ref, null, key, null, null);
    }

    public static ScenarioStep commit(String ref) {
        return new ScenarioStep(Action.COMMIT, ref, null, null, null, null);
    }

    public static ScenarioStep rollback(String ref) {
        return new ScenarioStep(Action.ROLLBACK, ref, null, null, null, null);
    }

    public Action action() { return action; }
    public String transactionRef() { return transactionRef; }
    public IsolationLevel isolationLevel() { return isolationLevel; }
    public String key() { return key; }
    public byte[] value() { return value != null ? value.clone() : null; }
    public String label() { return label; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScenarioStep that)) return false;
        return action == that.action && Objects.equals(transactionRef, that.transactionRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, transactionRef);
    }
}
