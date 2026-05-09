package com.minidb.order.service;

import com.minidb.order.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IdempotencyServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM idempotency_records");
    }

    @Test
    void shouldDetectDuplicateRequest() {
        // 第一次请求应获取锁
        String result = idempotencyService.tryAcquire("key-1", "USER", 1L, "request-body");
        assertNull(result, "First request should get lock");
        idempotencyService.markCompleted("key-1", "USER", 1L, "\"response\"");

        // 第二次相同请求应返回第一次响应
        String cached = idempotencyService.tryAcquire("key-1", "USER", 1L, "request-body");
        assertNotNull(cached, "Duplicate should return cached response");
        assertEquals("\"response\"", cached);
    }

    @Test
    void shouldRejectDifferentRequestWithSameKey() {
        idempotencyService.tryAcquire("key-2", "USER", 1L, "body-a");
        idempotencyService.markCompleted("key-2", "USER", 1L, "\"response-a\"");

        assertThrows(BusinessException.class, () ->
            idempotencyService.tryAcquire("key-2", "USER", 1L, "body-b"));
    }

    @Test
    void shouldAllowRetryAfterFailedWithSameRequest() {
        assertNull(idempotencyService.tryAcquire("key-3", "USER", 1L, "body-a"));
        idempotencyService.markFailed("key-3", "USER", 1L);

        assertNull(idempotencyService.tryAcquire("key-3", "USER", 1L, "body-a"));

        Integer status = jdbc.queryForObject(
            "SELECT status FROM idempotency_records WHERE idempotency_key = 'key-3'",
            Integer.class);
        assertEquals(10, status);
    }

    @Test
    void shouldRejectRetryAfterFailedWithDifferentRequest() {
        assertNull(idempotencyService.tryAcquire("key-4", "USER", 1L, "body-a"));
        idempotencyService.markFailed("key-4", "USER", 1L);

        assertThrows(BusinessException.class, () ->
            idempotencyService.tryAcquire("key-4", "USER", 1L, "body-b"));
    }
}
