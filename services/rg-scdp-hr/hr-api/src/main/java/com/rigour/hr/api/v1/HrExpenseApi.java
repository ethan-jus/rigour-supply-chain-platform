package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetailView;
import com.rigour.hr.api.v1.model.HrExpenseQuery;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollAuditView;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface HrExpenseApi {
    String BASE_PATH = "/api/v1/hr/expenses";

    @GetMapping(BASE_PATH)
    ApiResponse<HrPageView<HrExpenseDetailView>> expenses(@ModelAttribute HrExpenseQuery query);

    @GetMapping(BASE_PATH + "/employees")
    ApiResponse<List<PayrollEmployeeView>> employees();

    @PostMapping(BASE_PATH)
    ApiResponse<HrExpenseDetailView> create(@RequestBody HrExpenseCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<HrExpenseDetailView> update(
            @PathVariable String id, @RequestBody HrExpenseCommand command);

    @PostMapping(BASE_PATH + "/{id}/confirm")
    ApiResponse<HrExpenseDetailView> confirm(
            @PathVariable String id, @RequestBody PayrollActionCommand command);

    @PostMapping(BASE_PATH + "/{id}/void")
    ApiResponse<HrExpenseDetailView> voidExpense(
            @PathVariable String id, @RequestBody PayrollActionCommand command);

    @GetMapping(BASE_PATH + "/{id}/history")
    ApiResponse<List<PayrollAuditView>> history(@PathVariable String id);
}
