package com.minidb.order.web;

import com.minidb.order.dto.ApiResponse;
import com.minidb.order.service.ConsoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConsoleController {
    private final ConsoleService consoleService;

    public ConsoleController(ConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    @GetMapping("/dashboard/summary")
    public ResponseEntity<ApiResponse<ConsoleService.DashboardSummary>> dashboardSummary() {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.dashboardSummary()));
    }

    @PostMapping("/console/demo/load")
    public ResponseEntity<ApiResponse<ConsoleService.DemoLoadResult>> loadDemo(
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.loadDemoData(idempotencyKey)));
    }

    @GetMapping("/audit/orders/{orderId}/trace")
    public ResponseEntity<ApiResponse<ConsoleService.OrderTrace>> orderTrace(@PathVariable("orderId") long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.traceOrder(orderId)));
    }

    @GetMapping("/proxy/routes/preview")
    public ResponseEntity<ApiResponse<ConsoleService.RoutePreview>> previewRoute(@RequestParam(name = "sql") String sql) {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.previewRoute(sql)));
    }

    @PostMapping("/lab/scenarios/{scenario}/run")
    public ResponseEntity<ApiResponse<ConsoleService.LabRunResult>> runScenario(
            @PathVariable("scenario") String scenario,
            @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.runLabScenario(scenario)));
    }
}
