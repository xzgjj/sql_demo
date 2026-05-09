package com.minidb.proxy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Ring-buffer log of recent route decisions for observability.
 * Thread-safe, bounded, lossy — oldest entries are evicted when full.
 */
public class RouteDecisionLog {

    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final int maxEntries;

    public RouteDecisionLog(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public void record(String sessionId, String sqlPreview, String keyType, String keyValue,
                       String target, String reason, String status, long elapsedMs) {
        entries.addLast(new Entry(sessionId, sqlPreview, keyType, keyValue, target, reason, status, elapsedMs));
        while (entries.size() > maxEntries) {
            entries.pollFirst();
        }
    }

    public List<Entry> recent(int limit) {
        var list = new ArrayList<>(entries);
        int from = Math.max(0, list.size() - limit);
        return list.subList(from, list.size());
    }

    public List<Entry> bySession(String sessionId, int limit) {
        return entries.stream()
                .filter(e -> e.sessionId.equals(sessionId))
                .skip(Math.max(0, entries.size() - limit))
                .limit(limit)
                .toList();
    }

    public record Entry(String sessionId, String sqlPreview, String keyType, String keyValue,
                        String target, String reason, String status, long elapsedMs) {
        public String timestamp() { return Instant.now().toString(); }
    }
}
