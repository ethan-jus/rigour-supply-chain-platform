package com.rigour.erp.api;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.service.cost.ProcurementCostRecordService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/erp/procurement-costs")
public final class ProcurementCostRecordController {
    private final ProcurementCostRecordService service;

    public ProcurementCostRecordController(ProcurementCostRecordService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MasterDataPageView<ProcurementCostRecordView>> page(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String procurementNo,
            @RequestParam(required = false) String supplierName,
            @RequestParam(required = false) String freightStatus,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        return ApiResponse.success(
                service.page(
                        begin, step, procurementNo, supplierName, freightStatus, dateFrom, dateTo));
    }

    @GetMapping("/{code}")
    public ApiResponse<ProcurementCostRecordView> detail(@PathVariable String code) {
        return ApiResponse.success(service.detail(code));
    }

    @GetMapping("/{code}/history")
    public ApiResponse<java.util.List<ErpCostAuditView>> history(@PathVariable String code) {
        return ApiResponse.success(service.history(code));
    }

    @PostMapping("/{code}/freight-confirmation")
    public ApiResponse<ProcurementCostRecordView> confirm(
            @PathVariable String code, @RequestBody ProcurementFreightConfirmation command) {
        return ApiResponse.success(service.confirmFreight(code, command));
    }

    @PostMapping("/{code}/freight-draft")
    public ApiResponse<ProcurementCostRecordView> draft(
            @PathVariable String code, @RequestBody ProcurementFreightConfirmation command) {
        return ApiResponse.success(service.draftFreight(code, command));
    }
}
