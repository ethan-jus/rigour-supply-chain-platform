package com.rigour.sales.api;

import com.rigour.sales.api.v1.model.FieldworkReport;
import com.rigour.sales.application.service.FieldworkReportService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales/management")
public class FieldworkReportController {
    private final FieldworkReportService service;

    public FieldworkReportController(FieldworkReportService service) {
        this.service = service;
    }

    @GetMapping("/fieldwork/report")
    public ApiResponse<FieldworkReport> report(
            @RequestParam LocalDate date,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String departmentId,
            @RequestParam(defaultValue = "false") boolean includeDescendants,
            @RequestParam(required = false) UUID person,
            @RequestParam(required = false) String employeeCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean pendingOnly,
            @RequestParam(defaultValue = "false") boolean riskQueue,
            @RequestParam(defaultValue = "risk") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                service.report(
                        date,
                        new FieldworkReportService.Filters(
                                city,
                                departmentId,
                                includeDescendants,
                                person,
                                status,
                                pendingOnly,
                                sort,
                                page,
                                size,
                                employeeCode),
                        riskQueue));
    }

    @GetMapping("/customer-followups/restock")
    public ApiResponse<java.util.List<FieldworkReport.Commitment>> restock(
            @RequestParam(defaultValue = "PLANNED") String status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @RequestParam(required = false) String ownerCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(service.commitments(status, from, through, ownerCode, page, size));
    }

    @GetMapping("/visits/{visitId}/content")
    public ApiResponse<FieldworkReport.VisitContent> content(@PathVariable UUID visitId) {
        return ApiResponse.success(service.visitContent(visitId));
    }

    @GetMapping("/visits/records")
    public ApiResponse<FieldworkReport.Ledger> visits(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID person,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String departmentId,
            @RequestParam(defaultValue = "false") boolean includeDescendants,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String assessment,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) UUID visitId,
            @RequestParam(required = false) String employeeCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(required = false) Boolean ascending) {
        return ApiResponse.success(
                service.ledger(
                        from,
                        to,
                        person,
                        city,
                        departmentId,
                        includeDescendants,
                        keyword,
                        assessment,
                        origin,
                        visitId,
                        employeeCode,
                        page,
                        size, sort, ascending));
    }
}
