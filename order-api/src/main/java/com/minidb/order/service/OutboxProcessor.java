package com.minidb.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minidb.order.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
    private final ObjectMapper objectMapper;

    public OutboxProcessor(JdbcTemplate jdbc, FulfillmentService fulfillmentService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.fulfillmentService = fulfillmentService;
        this.objectMapper = objectMapper;
    }

    /**
     * 每10秒扫描一次未处理的outbox事件。
     */
    @Scheduled(fixedDelay = 10000)
    public void processOutbox() {
        var events = jdbc.query(
            "SELECT id, event_type, aggregate_type, aggregate_id, payload, retry_count " +
            "FROM outbox_events WHERE status = 10 " +  // 10 = NEW
            "AND (next_retry_at IS NULL OR next_retry_at <= NOW()) " +
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
            String payload = (String) event[4];
            int retryCount = (int) event[5];

            try {
                int claimed = jdbc.update("UPDATE outbox_events SET status = 20 WHERE id = ? AND status = 10",
                    eventId);
                if (claimed == 0) {
                    log.debug("Outbox event {} already claimed by another processor", eventId);
                    continue;
                }

                switch (eventType) {
                    case "ORDER_PAID" -> fulfillmentService.createTaskFromOrderPaid(
                            aggregateId, extractRequiredUserId(eventId, payload));
                    default -> log.debug("No handler for event type: {}", eventType);
                }

                jdbc.update("UPDATE outbox_events SET status = 30 WHERE id = ? AND status = 20", eventId);
                log.debug("Outbox event {} processed: {}", eventId, eventType);

            } catch (Exception e) {
                log.error("Failed to process outbox event {}: {}", eventId, e.getMessage());
                int nextRetry = Math.min(retryCount + 1, 5);
                int delaySeconds = (int) Math.pow(2, nextRetry) * 10;
                jdbc.update(
                    "UPDATE outbox_events SET status = 10, retry_count = ?, " +
                    "next_retry_at = ? WHERE id = ? AND status = 20",
                    nextRetry, LocalDateTime.now().plusSeconds(delaySeconds), eventId
                );
                if (nextRetry >= 5) {
                    jdbc.update("UPDATE outbox_events SET status = 40 WHERE id = ? AND status = 10", eventId);
                    log.error("Outbox event {} exhausted retries", eventId);
                }
            }
        }
    }

    private Long extractRequiredUserId(long eventId, String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            JsonNode userIdNode = root.get("user_id");
            if (userIdNode == null || !userIdNode.canConvertToLong()) {
                throw new BusinessException("OUTBOX_ROUTE_KEY_MISSING",
                        "ORDER_PAID outbox event " + eventId + " must contain user_id");
            }
            long userId = userIdNode.asLong();
            if (userId <= 0) {
                throw new BusinessException("OUTBOX_ROUTE_KEY_INVALID",
                        "ORDER_PAID outbox event " + eventId + " has invalid user_id");
            }
            return userId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("OUTBOX_PAYLOAD_INVALID",
                    "ORDER_PAID outbox event " + eventId + " payload is not valid JSON", e);
        }
    }
}
