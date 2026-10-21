package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface HrPayrollApi {
    String BASE_PATH = "/api/v1/hr/payroll";

    @GetMapping(BASE_PATH + "/rule-employees")
    ApiResponse<List<PayrollEmployeeView>> ruleEmployees();

    @GetMapping(BASE_PATH + "/rule-departments")
    ApiResponse<List<PayrollDepartmentView>> departments();

    @GetMapping(BASE_PATH + "/products")
    ApiResponse<List<PayrollProductView>> products(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long productVariantId);

    @GetMapping(BASE_PATH + "/product-categories")
    ApiResponse<List<PayrollCategoryView>> productCategories();

    @GetMapping(BASE_PATH + "/commission-policies")
    ApiResponse<List<PayrollCommissionPolicyView>> policies();

    @GetMapping(BASE_PATH + "/rules")
    ApiResponse<HrPageView<PayrollRuleView>> rules(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam String kindCode,
            @RequestParam(defaultValue = "") String keyword);

    @PostMapping(BASE_PATH + "/rules")
    ApiResponse<PayrollRuleView> createRule(@RequestBody PayrollRuleCommand command);

    @PostMapping(BASE_PATH + "/rules/city-managers")
    ApiResponse<List<PayrollRuleView>> createCityManagers(
            @RequestBody List<PayrollRuleCommand> commands);

    @PutMapping(BASE_PATH + "/rules/{id}")
    ApiResponse<PayrollRuleView> updateRule(
            @PathVariable String id, @RequestBody PayrollRuleCommand command);

    @GetMapping(BASE_PATH + "/departments")
    ApiResponse<List<PayrollDepartmentView>> payrollDepartments();

    @GetMapping(BASE_PATH + "/employees")
    ApiResponse<List<PayrollEmployeeView>> employees();

    @GetMapping(BASE_PATH)
    ApiResponse<HrPageView<PayrollDetailView>> payroll(@ModelAttribute PayrollQuery query);

    @GetMapping(BASE_PATH + "/summary")
    ApiResponse<PayrollSummaryView> summary(@ModelAttribute PayrollQuery query);

    @PostMapping(BASE_PATH + "/calculate")
    ApiResponse<List<PayrollDetailView>> calculate(@RequestBody PayrollCalculateCommand command);

    @PutMapping(BASE_PATH + "/inputs/{month}/{employeeCode}")
    ApiResponse<PayrollInputCommand> input(
            @PathVariable String month,
            @PathVariable String employeeCode,
            @RequestBody PayrollInputCommand command);

    @GetMapping(BASE_PATH + "/inputs/{month}/{employeeCode}")
    ApiResponse<PayrollInputCommand> input(
            @PathVariable String month, @PathVariable String employeeCode);

    @PostMapping(BASE_PATH + "/{id}/confirm")
    ApiResponse<PayrollDetailView> confirm(
            @PathVariable String id, @RequestBody PayrollActionCommand command);

    @PostMapping(BASE_PATH + "/{id}/paid")
    ApiResponse<PayrollDetailView> paid(
            @PathVariable String id, @RequestBody PayrollPaymentCommand command);

    @GetMapping(BASE_PATH + "/preparation")
    ApiResponse<PayrollPreparationView> preparation(@RequestParam String month);

    @PostMapping(BASE_PATH + "/preview")
    ApiResponse<List<PayrollPreviewView>> preview(@RequestBody PayrollCalculateCommand command);

    @PutMapping(BASE_PATH + "/employee-profiles")
    ApiResponse<Void> updateProfiles(@RequestBody PayrollBatchProfileCommand command);

    @GetMapping(BASE_PATH + "/export")
    ApiResponse<List<PayrollDetailView>> export(@ModelAttribute PayrollQuery query);

    @PostMapping(BASE_PATH + "/{id}/reopen")
    ApiResponse<PayrollDetailView> reopen(
            @PathVariable String id, @RequestBody PayrollActionCommand command);

    @PostMapping(BASE_PATH + "/{id}/corrections")
    ApiResponse<PayrollCorrectionView> correct(
            @PathVariable String id, @RequestBody PayrollCorrectionCommand command);

    @PostMapping(BASE_PATH + "/{id}/corrections/{correctionId}/cancel")
    ApiResponse<Void> cancelCorrection(
            @PathVariable String id,
            @PathVariable String correctionId,
            @RequestBody PayrollActionCommand command);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<PayrollDetailView> detail(@PathVariable String id);

    @GetMapping(BASE_PATH + "/{id}/history")
    ApiResponse<List<PayrollAuditView>> history(@PathVariable String id);
}
