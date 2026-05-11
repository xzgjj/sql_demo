package com.minidb.order.web;

import com.minidb.order.dto.ApiResponse;
import com.minidb.order.service.ConsoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ConsoleController {
    private final ConsoleService consoleService;

    public ConsoleController(ConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    @GetMapping("/runtime/mode")
    public ResponseEntity<ApiResponse<ConsoleService.RuntimeMode>> runtimeMode() {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.runtimeMode()));
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
            @RequestBody(required = false) String body) {
        if ("custom".equals(scenario)) {
            return ResponseEntity.ok(ApiResponse.ok(consoleService.runCustomScenario(body)));
        }
        return ResponseEntity.ok(ApiResponse.ok(consoleService.runLabScenario(scenario)));
    }

    @GetMapping("/proxy/sessions")
    public ResponseEntity<ApiResponse<ConsoleService.ProxySessionsResult>> proxySessions() {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.proxySessions()));
    }

    @GetMapping("/proxy/pools")
    public ResponseEntity<ApiResponse<ConsoleService.ProxyPoolsResult>> proxyPools() {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.proxyPools()));
    }

    @GetMapping("/proxy/decisions")
    public ResponseEntity<ApiResponse<ConsoleService.ProxyDecisionsResult>> proxyDecisions(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.proxyDecisions(sessionId, limit)));
    }

    @GetMapping("/runtime/proxy-status")
    public ResponseEntity<ApiResponse<ConsoleService.ProxyStatus>> proxyStatus() {
        return ResponseEntity.ok(ApiResponse.ok(consoleService.checkProxyStatus()));
    }
}
