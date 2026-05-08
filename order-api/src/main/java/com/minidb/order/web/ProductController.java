package com.minidb.order.web;

import com.minidb.order.dto.ApiResponse;
import com.minidb.order.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "商品管理")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "查询商品列表", description = "支持按分类、关键词筛选，分页返回。仅返回上架商品。")
    public ResponseEntity<ApiResponse<ProductService.ProductPage>> listProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1 || pageSize > 100) pageSize = 20;
        return ResponseEntity.ok(ApiResponse.ok(
            productService.listProducts(categoryId, keyword, page, pageSize)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询商品详情", description = "返回商品信息及当前库存。")
    public ResponseEntity<ApiResponse<ProductService.ProductItem>> getProduct(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getProduct(id)));
    }
}
