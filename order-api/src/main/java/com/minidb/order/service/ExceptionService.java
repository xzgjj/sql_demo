package com.minidb.order.service;

import com.minidb.order.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;

@Service
public class ExceptionService {
    private static final Logger log = LoggerFactory.getLogger(ExceptionService.class);
    private final JdbcTemplate jdbc;

    public ExceptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ExceptionItem(long id, String bizType, String bizNo, String reasonCode,
                                String detail, int status, LocalDateTime createdAt) {}

    public record ExceptionListPage(java.util.List<ExceptionItem> items, int page, int pageSize, long total) {}

    public ExceptionListPage listExceptions(Integer status, int page, int pageSize) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();
        if (status != null) {
            conditions.add("status = ?");
            params.add(status);
        }
        String whereClause = conditions.isEmpty() ? "1=1" : String.join(" AND ", conditions);

        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exception_tickets WHERE " + whereClause,
            Long.class, params.toArray()
        );

        int offset = (page - 1) * pageSize;
        var queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(offset);

        var items = jdbc.query(
            "SELECT id, biz_type, biz_no, reason_code, detail, status, created_at " +
            "FROM exception_tickets WHERE " + whereClause + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> new ExceptionItem(
                rs.getLong("id"), rs.getString("biz_type"), rs.getString("biz_no"),
                rs.getString("reason_code"), rs.getString("detail"), rs.getInt("status"),
                rs.getTimestamp("created_at").toLocalDateTime()
            ),
            queryParams.toArray()
        );

        return new ExceptionListPage(items, page, pageSize, total != null ? total : 0);
    }

    public ExceptionItem getException(long id) {
        var items = jdbc.query(
            "SELECT id, biz_type, biz_no, reason_code, detail, status, created_at " +
            "FROM exception_tickets WHERE id = ?",
            (rs, rowNum) -> new ExceptionItem(
                rs.getLong("id"), rs.getString("biz_type"), rs.getString("biz_no"),
                rs.getString("reason_code"), rs.getString("detail"), rs.getInt("status"),
                rs.getTimestamp("created_at").toLocalDateTime()
            ),
            id
        );
        if (items.isEmpty()) throw new BusinessException("EXCEPTION_NOT_FOUND", "Exception ticket not found: " + id);
        return items.get(0);
    }

    @Transactional
    public void resolveException(long id, String resolution) {
        var existing = jdbc.query(
            "SELECT status, detail FROM exception_tickets WHERE id = ?",
            rs -> rs.next() ? new Object[]{rs.getInt("status"), rs.getString("detail")} : null,
            id
        );
        if (existing == null) throw new BusinessException("EXCEPTION_NOT_FOUND", "Exception not found: " + id);
        if ((int) existing[0] != 10)
            throw new BusinessException("EXCEPTION_STATUS_CHANGED",
                "Exception already resolved or in progress, status: " + existing[0]);

        String oldDetail = (String) existing[1];
        String newDetail = (oldDetail != null ? oldDetail : "") + "\n[RESOLVED] " + resolution;
        jdbc.update("UPDATE exception_tickets SET status = 30, detail = ? WHERE id = ? AND status = 10",
            newDetail, id);
        log.info("Exception ticket {} resolved: {}", id, resolution);
    }
}
