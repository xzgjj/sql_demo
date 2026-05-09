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
class ExceptionServiceTest {

    @Autowired private ExceptionService exceptionService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM exception_tickets");
    }

    @Test
    void shouldListExceptions() {
        seedException("PAYMENT", "PAY-001", "PAYMENT_AMOUNT_MISMATCH", "Expected 100 got 50");
        seedException("INVENTORY", "ORD-001", "STOCK_INSUFFICIENT", "Stock went negative");

        var page = exceptionService.listExceptions(null, 1, 20);
        assertEquals(2, page.total());
    }

    @Test
    void shouldFilterByStatus() {
        seedException("PAYMENT", "PAY-002", "ERR-001", "detail");
        seedException("PAYMENT", "PAY-003", "ERR-002", "detail");
        jdbc.update("UPDATE exception_tickets SET status = 30 WHERE biz_no = 'PAY-002'");

        var openPage = exceptionService.listExceptions(10, 1, 20);
        assertEquals(1, openPage.total());

        var resolvedPage = exceptionService.listExceptions(30, 1, 20);
        assertEquals(1, resolvedPage.total());
    }

    @Test
    void shouldGetExceptionDetail() {
        seedException("ROUTING", "ORD-R1", "MISSING_SHARD_KEY", "No user_id in query");

        var page = exceptionService.listExceptions(null, 1, 1);
        long id = page.items().get(0).id();

        var detail = exceptionService.getException(id);
        assertEquals("ROUTING", detail.bizType());
        assertEquals("ORD-R1", detail.bizNo());
    }

    @Test
    void shouldResolveException() {
        seedException("PAYMENT", "PAY-R1", "ERR-R1", "detail");
        var page = exceptionService.listExceptions(null, 1, 1);
        long id = page.items().get(0).id();

        exceptionService.resolveException(id, "Refunded manually");

        var resolved = exceptionService.getException(id);
        assertEquals(30, resolved.status());
        assertTrue(resolved.detail().contains("Refunded manually"));
    }

    @Test
    void shouldRejectResolveAlreadyResolved() {
        seedException("PAYMENT", "PAY-R2", "ERR-R2", "detail");
        var page = exceptionService.listExceptions(null, 1, 1);
        long id = page.items().get(0).id();

        exceptionService.resolveException(id, "First resolution");

        assertThrows(BusinessException.class,
            () -> exceptionService.resolveException(id, "Second resolution"));
    }

    @Test
    void shouldReturnEmptyList() {
        var page = exceptionService.listExceptions(null, 1, 20);
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    private long seedException(String bizType, String bizNo, String reasonCode, String detail) {
        jdbc.update("INSERT INTO exception_tickets (biz_type, biz_no, reason_code, detail, status) " +
            "VALUES (?, ?, ?, ?, 10)", bizType, bizNo, reasonCode,
            "{\"detail\":\"" + detail.replace("\"", "\\\"") + "\"}");
        return jdbc.queryForObject("SELECT id FROM exception_tickets WHERE biz_no = ?", Long.class, bizNo);
    }
}
