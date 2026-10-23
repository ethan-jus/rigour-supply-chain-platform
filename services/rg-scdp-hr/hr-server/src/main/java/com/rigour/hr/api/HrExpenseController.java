package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrExpenseApi;
import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetailView;
import com.rigour.hr.api.v1.model.HrExpensePageView;
import com.rigour.hr.api.v1.model.HrExpenseQuery;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollAuditView;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.application.service.HrExpenseService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class HrExpenseController implements HrExpenseApi {
    private final HrExpenseService service;

    public HrExpenseController(HrExpenseService service) {
        this.service = service;
    }

    public ApiResponse<HrExpensePageView> expenses(HrExpenseQuery query) {
        return ApiResponse.success(service.expenses(query));
    }

    public ApiResponse<HrExpenseDetailView> detail(String id) {
        return ApiResponse.success(service.detail(id));
    }

    public ApiResponse<List<PayrollEmployeeView>> employees(
            String cityCode, String cityName, java.time.LocalDate costDate) {
        return ApiResponse.success(service.employees(cityCode, cityName, costDate));
    }

    public ApiResponse<HrExpenseDetailView> create(HrExpenseCommand command) {
        return ApiResponse.success(service.save(null, command));
    }

    public ApiResponse<HrExpenseDetailView> update(String id, HrExpenseCommand command) {
        return ApiResponse.success(service.save(id, command));
    }

    public ApiResponse<HrExpenseDetailView> confirm(String id, PayrollActionCommand command) {
        return ApiResponse.success(service.action(id, command, true));
    }

    public ApiResponse<HrExpenseDetailView> voidExpense(String id, PayrollActionCommand command) {
        return ApiResponse.success(service.action(id, command, false));
    }

    public ApiResponse<List<PayrollAuditView>> history(String id) {
        return ApiResponse.success(service.history(id));
    }
}
