package com.minidb.order.service;

import com.minidb.order.infra.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private final JdbcTemplate jdbc;

    public ProductService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProductItem(long productId, String sku, String name, BigDecimal price,
                              int status, int availableQty, int lockedQty) {}

    public record ProductPage(List<ProductItem> items, int page, int pageSize, long total) {}

    public ProductPage listProducts(Long categoryId, String keyword, int page, int pageSize) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();

        if (categoryId != null) {
            conditions.add("p.category_id = ?");
            params.add(categoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("(p.name LIKE ? OR p.sku LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        conditions.add("p.status = 1");

        String whereClause = String.join(" AND ", conditions);

        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM products p WHERE " + whereClause,
            Long.class, params.toArray()
        );

        int offset = (page - 1) * pageSize;
        var queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(offset);

        var items = jdbc.query(
            "SELECT p.id, p.sku, p.name, p.price, p.status, " +
            "COALESCE(pi.available_qty, 0) AS available_qty, " +
            "COALESCE(pi.locked_qty, 0) AS locked_qty " +
            "FROM products p LEFT JOIN product_inventory pi ON p.id = pi.product_id " +
            "WHERE " + whereClause + " ORDER BY p.id LIMIT ? OFFSET ?",
            (rs, rowNum) -> new ProductItem(
                rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getBigDecimal("price"), rs.getInt("status"),
                rs.getInt("available_qty"), rs.getInt("locked_qty")
            ),
            queryParams.toArray()
        );

        return new ProductPage(items, page, pageSize, total != null ? total : 0);
    }

    public ProductItem getProduct(long productId) {
        var items = jdbc.query(
            "SELECT p.id, p.sku, p.name, p.price, p.status, " +
            "COALESCE(pi.available_qty, 0) AS available_qty, " +
            "COALESCE(pi.locked_qty, 0) AS locked_qty " +
            "FROM products p LEFT JOIN product_inventory pi ON p.id = pi.product_id " +
            "WHERE p.id = ?",
            (rs, rowNum) -> new ProductItem(
                rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getBigDecimal("price"), rs.getInt("status"),
                rs.getInt("available_qty"), rs.getInt("locked_qty")
            ),
            productId
        );
        if (items.isEmpty()) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + productId);
        }
        return items.get(0);
    }
}
