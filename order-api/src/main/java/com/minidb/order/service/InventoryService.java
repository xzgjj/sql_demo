package com.minidb.order.service;

import com.minidb.order.infra.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存服务。
 * 所有库存变更必须使用条件更新，禁止快照读后再更新。
 * 库存区分：available_qty（可售）、locked_qty（锁定）、shipped_qty（已出库）。
 */
@Service
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProductStock(long productId, int availableQty, int lockedQty, int shippedQty, int version) {}

    /**
     * 查询商品库存（快照读，仅用于展示，不用于扣减决策）。
     */
    public List<ProductStock> getStock(List<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        String sql = "SELECT product_id, available_qty, locked_qty, shipped_qty, version " +
                     "FROM product_inventory WHERE product_id IN (" +
                     productIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")";
        return jdbc.query(sql, (rs, rowNum) -> new ProductStock(
            rs.getLong("product_id"),
            rs.getInt("available_qty"),
            rs.getInt("locked_qty"),
            rs.getInt("shipped_qty"),
            rs.getInt("version")
        ), productIds.toArray());
    }

    /**
     * 下单锁定库存：available_qty -= qty, locked_qty += qty。
     * 使用条件更新防止超卖。
     *
     * @return true 表示锁定成功
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockStock(long productId, int qty) {
        int affected = jdbc.update(
            "UPDATE product_inventory SET available_qty = available_qty - ?, locked_qty = locked_qty + ?, " +
            "version = version + 1 WHERE product_id = ? AND available_qty >= ?",
            qty, qty, productId, qty
        );
        if (affected == 0) {
            log.warn("Stock lock failed: product={}, qty={}", productId, qty);
            return false;
        }
        return true;
    }

    /**
     * 取消订单释放库存：available_qty += qty, locked_qty -= qty。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseStock(long productId, int qty) {
        int affected = jdbc.update(
            "UPDATE product_inventory SET available_qty = available_qty + ?, locked_qty = locked_qty - ?, " +
            "version = version + 1 WHERE product_id = ? AND locked_qty >= ?",
            qty, qty, productId, qty
        );
        if (affected == 0) {
            throw new BusinessException("INVENTORY_ERROR",
                String.format("Failed to release stock: product=%d, qty=%d", productId, qty));
        }
    }

    /**
     * 发货转库存：locked_qty -= qty, shipped_qty += qty。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void shipStock(long productId, int qty) {
        int affected = jdbc.update(
            "UPDATE product_inventory SET locked_qty = locked_qty - ?, shipped_qty = shipped_qty + ?, " +
            "version = version + 1 WHERE product_id = ? AND locked_qty >= ?",
            qty, qty, productId, qty
        );
        if (affected == 0) {
            throw new BusinessException("INVENTORY_ERROR",
                String.format("Failed to ship stock: product=%d, qty=%d", productId, qty));
        }
    }

    /**
     * 写库存流水。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeJournal(long productId, String bizType, String bizNo,
                             int changeAvailable, int changeLocked, int changeShipped) {
        jdbc.update(
            "INSERT INTO inventory_journals (product_id, biz_type, biz_no, " +
            "change_available, change_locked, change_shipped) VALUES (?, ?, ?, ?, ?, ?)",
            productId, bizType, bizNo, changeAvailable, changeLocked, changeShipped
        );
    }

    /**
     * 批量下单锁定库存。任一商品库存不足整体失败。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockBatch(List<StockLockItem> items) {
        for (StockLockItem item : items) {
            if (!lockStock(item.productId, item.quantity)) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                    String.format("Product %d stock insufficient, need %d", item.productId, item.quantity));
            }
            writeJournal(item.productId, "ORDER_CREATE", item.bizNo, -item.quantity, item.quantity, 0);
        }
    }

    /**
     * 批量释放库存（取消订单时）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseBatch(List<StockLockItem> items) {
        for (StockLockItem item : items) {
            releaseStock(item.productId, item.quantity);
            writeJournal(item.productId, "ORDER_CANCEL", item.bizNo, item.quantity, -item.quantity, 0);
        }
    }

    public record StockLockItem(long productId, int quantity, String bizNo) {}
}
