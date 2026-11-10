package com.rigour.order.api;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.service.cost.LogisticsImportService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders/logistics-costs/imports")
public final class LogisticsImportController {
    private final LogisticsImportService service;

    public LogisticsImportController(LogisticsImportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Row>> rows(@RequestParam(defaultValue = "0") int begin) {
        return ApiResponse.success(service.rows(begin));
    }

    @GetMapping("/sources")
    public ApiResponse<List<Row>> sources(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "false") boolean differencesOnly) {
        return ApiResponse.success(
                service.query(new SourceQuery(begin, orderNo, state, differencesOnly)));
    }

    @PostMapping("/api-sources")
    public ApiResponse<Row> receive(@RequestBody ApiSource source) {
        return ApiResponse.success(service.receive(source));
    }

    @PostMapping("/api-employee-mappings")
    public ApiResponse<Void> employeeMapping(@RequestBody EmployeeMappingUpdate update) {
        service.employeeMapping(update);
        return ApiResponse.success(null);
    }

    @PostMapping("/api-vouchers")
    public ApiResponse<Void> voucher(@RequestBody VoucherUpdate update) {
        service.voucher(update);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/accept-source")
    public ApiResponse<Row> accept(@PathVariable String id, @RequestBody AcceptSource command) {
        return ApiResponse.success(service.accept(id, command));
    }

    @PostMapping("/{id}/verify-bill")
    public ApiResponse<Row> verify(@PathVariable String id, @RequestBody VerifyBill command) {
        return ApiResponse.success(service.verifyBill(id, command));
    }

    @GetMapping("/by-cost/{costId}")
    public ApiResponse<Row> byCost(@PathVariable String costId) {
        return ApiResponse.success(service.byCost(costId));
    }

    @PostMapping
    public ApiResponse<List<Row>> stage(@RequestBody Upload upload) {
        return ApiResponse.success(service.stage(upload));
    }

    @PostMapping("/{id}/generate")
    public ApiResponse<LogisticsCostDetailView> generate(
            @PathVariable String id, @RequestBody Generated command) {
        return ApiResponse.success(service.generate(id, command));
    }
}
