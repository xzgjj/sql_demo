package com.minidb.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox事件处理器。
 * 定时扫描NEW状态的outbox事件并分发处理。
 * 当前支持的事件类型：ORDER_PAID（生成履约任务）。
 */
@Component
public class OutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private final JdbcTemplate jdbc;
    private final FulfillmentService fulfillmentService;

    public OutboxProcessor(JdbcTemplate jdbc, FulfillmentService fulfillmentService) {
        this.jdbc = jdbc;
        this.fulfillmentService = fulfillmentService;
    }

    /**
     * 每10秒扫描一次未处理的outbox事件。
     */
    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void processOutbox() {
        var events = jdbc.query(
            "SELECT id, event_type, aggregate_type, aggregate_id, payload, retry_count " +
            "FROM outbox_events WHERE status = 10 " +  // 10 = NEW
            "ORDER BY id LIMIT 10",
            (rs, rowNum) -> new Object[]{
                rs.getLong("id"), rs.getString("event_type"),
                rs.getString("aggregate_type"), rs.getLong("aggregate_id"),
                rs.getString("payload"), rs.getInt("retry_count")
            }
        );

        for (var event : events) {
            long eventId = (long) event[0];
            String eventType = (String) event[1];
            long aggregateId = (long) event[3];
            int retryCount = (int) event[5];

            try {
                jdbc.update("UPDATE outbox_events SET status = 20 WHERE id = ?", eventId);

                switch (eventType) {
                    case "ORDER_PAID" -> fulfillmentService.createTaskFromOrderPaid(aggregateId);
                    default -> log.debug("No handler for event type: {}", eventType);
                }

                jdbc.update("UPDATE outbox_events SET status = 30 WHERE id = ?", eventId);
                log.debug("Outbox event {} processed: {}", eventId, eventType);

            } catch (Exception e) {
                log.error("Failed to process outbox event {}: {}", eventId, e.getMessage());
                int nextRetry = Math.min(retryCount + 1, 5);
                jdbc.update(
                    "UPDATE outbox_events SET status = 10, retry_count = ?, " +
                    "next_retry_at = DATE_ADD(NOW(), INTERVAL ? SECOND) WHERE id = ?",
                    nextRetry, (int) Math.pow(2, nextRetry) * 10, eventId
                );
                if (nextRetry >= 5) {
                    jdbc.update("UPDATE outbox_events SET status = 40 WHERE id = ?", eventId);
                    log.error("Outbox event {} exhausted retries", eventId);
                }
            }
        }
    }
}
