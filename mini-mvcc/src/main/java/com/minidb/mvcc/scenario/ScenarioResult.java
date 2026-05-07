package com.minidb.mvcc.scenario;

import com.minidb.mvcc.store.RecordVersion;
import com.minidb.mvcc.trace.TraceEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ScenarioResult {
    private final List<TraceEvent> trace;
    private final Map<String, List<RecordVersion>> versionChains;
    private final List<String> errors;

    public ScenarioResult(List<TraceEvent> trace,
                          Map<String, List<RecordVersion>> versionChains,
                          List<String> errors) {
        this.trace = Collections.unmodifiableList(trace);
        this.versionChains = Collections.unmodifiableMap(versionChains);
        this.errors = Collections.unmodifiableList(errors);
    }

    public List<TraceEvent> trace() { return trace; }
    public Map<String, List<RecordVersion>> versionChains() { return versionChains; }
    public List<String> errors() { return errors; }
    public boolean hasErrors() { return !errors.isEmpty(); }
}
