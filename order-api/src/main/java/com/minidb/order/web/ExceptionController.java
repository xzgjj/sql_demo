package com.minidb.order.web;

import com.minidb.order.dto.ApiResponse;
import com.minidb.order.service.ExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/exceptions")
@Tag(name = "Exceptions", description = "异常工单管理")
public class ExceptionController {
    private final ExceptionService exceptionService;

    public ExceptionController(ExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @GetMapping
    @Operation(summary = "查询异常工单列表", description = "按状态筛选，支持分页。10=OPEN, 20=IN_PROGRESS, 30=RESOLVED, 40=CLOSED")
    public ResponseEntity<ApiResponse<ExceptionService.ExceptionListPage>> listExceptions(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1 || pageSize > 100) pageSize = 20;
        return ResponseEntity.ok(ApiResponse.ok(exceptionService.listExceptions(status, page, pageSize)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询异常工单详情")
    public ResponseEntity<ApiResponse<ExceptionService.ExceptionItem>> getException(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(exceptionService.getException(id)));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "解决异常工单", description = "将状态从OPEN变为RESOLVED，记录处理备注。必须携带 Idempotency-Key。")
    public ResponseEntity<ApiResponse<Void>> resolveException(
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, String> body) {
        exceptionService.resolveException(id, body.getOrDefault("resolution", "Manual resolution"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
