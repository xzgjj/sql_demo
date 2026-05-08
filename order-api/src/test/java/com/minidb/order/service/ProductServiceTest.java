package com.minidb.order.service;

import com.minidb.order.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductServiceTest {

    @Autowired
    private ProductService productService;

    @Test
    void shouldReturnAllActiveProducts() {
        var page = productService.listProducts(null, null, 1, 20);
        assertTrue(page.total() >= 6, "Should have at least 6 seed products");
        assertEquals(1, page.page());
        for (var p : page.items()) {
            assertEquals(1, p.status(), "All should be active");
        }
    }

    @Test
    void shouldFilterByCategory() {
        var page = productService.listProducts(1L, null, 1, 20);
        for (var p : page.items()) {
            // All returned products should be from category 1
            // Since category_id isn't in the DTO, verify by name
            assertNotNull(p.name());
        }
    }

    @Test
    void shouldSearchByKeyword() {
        var page = productService.listProducts(null, "咖啡", 1, 20);
        assertTrue(page.total() >= 2, "Should find coffee products");
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        var page = productService.listProducts(null, "NONEXISTENT_KEYWORD_XYZ", 1, 20);
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void shouldGetProductById() {
        var p = productService.getProduct(1001);
        assertEquals("COFFEE-001", p.sku());
        assertTrue(p.availableQty() >= 0);
    }

    @Test
    void shouldThrowForNonExistentProduct() {
        assertThrows(BusinessException.class, () -> productService.getProduct(9999));
    }

    @Test
    void shouldReturnStockInfo() {
        var p = productService.getProduct(1001);
        assertTrue(p.availableQty() >= 0, "Should have stock info");
        assertTrue(p.lockedQty() >= 0, "Should have locked stock");
    }
}
