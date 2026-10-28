package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPayrollApi;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrPayrollDeterminationService;
import com.rigour.hr.application.service.HrPayrollService;
import com.rigour.hr.application.service.HrPayrollWorkflowService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class HrPayrollController implements HrPayrollApi {
    @Override
    public ApiResponse<List<PayrollEmployeeView>> ruleEmployees() {
        return ApiResponse.success(service.ruleEmployees());
    }

    @Override
    public ApiResponse<List<PayrollProductView>> products(
            String keyword, Long categoryId, Long productVariantId, int begin) {
        return ApiResponse.success(service.products(keyword, categoryId, productVariantId, begin));
    }

    @Override
    public ApiResponse<List<PayrollCategoryView>> productCategories() {
        return ApiResponse.success(service.productCategories());
    }

    @Override
    public ApiResponse<List<PayrollCommissionPolicyView>> policies() {
        return ApiResponse.success(service.policies());
    }

    @Override
    public ApiResponse<List<PayrollDepartmentView>> departments() {
        return ApiResponse.success(service.departments());
    }

    @Override
    public ApiResponse<List<PayrollDepartmentView>> payrollDepartments() {
        return ApiResponse.success(service.payrollDepartments());
    }

    private final HrPayrollService service;

    private final HrPayrollWorkflowService workflow;

    private final HrPayrollDeterminationService determinations;

    public HrPayrollController(
            HrPayrollService service,
            HrPayrollWorkflowService workflow,
            HrPayrollDeterminationService determinations) {
        this.workflow = workflow;
        this.service = service;
        this.determinations = determinations;
    }

    @Override
    public ApiResponse<PayrollDeterminationView> determination(
            String month, String employeeCode, PayrollDeterminationCommand command) {
        return ApiResponse.success(determinations.save(month, employeeCode, command));
    }

    @Override
    public ApiResponse<HrPageView<PayrollRuleView>> rules(
            int begin, int step, String kindCode, String keyword) {
        return ApiResponse.success(service.rules(begin, step, kindCode, keyword));
    }

    @Override
    public ApiResponse<PayrollRuleView> createRule(PayrollRuleCommand command) {
        return ApiResponse.success(service.saveRule(null, command));
    }

    @Override
    public ApiResponse<List<PayrollRuleView>> createCityManagers(
            List<PayrollRuleCommand> commands) {
        return ApiResponse.success(service.createCityManagers(commands));
    }

    @Override
    public ApiResponse<PayrollRuleView> updateRule(String id, PayrollRuleCommand command) {
        return ApiResponse.success(service.saveRule(id, command));
    }

    @Override
    public ApiResponse<List<PayrollEmployeeView>> employees() {
        return ApiResponse.success(service.employees());
    }

    @Override
    public ApiResponse<HrPageView<PayrollDetailView>> payroll(PayrollQuery query) {
        return ApiResponse.success(service.payroll(query));
    }

    @Override
    public ApiResponse<List<PayrollDetailView>> calculate(PayrollCalculateCommand command) {
        return ApiResponse.success(service.calculate(command));
    }

    @Override
    public ApiResponse<PayrollSummaryView> summary(PayrollQuery query) {
        return ApiResponse.success(service.summary(query));
    }

    @Override
    public ApiResponse<PayrollInputCommand> input(
            String month, String employeeCode, PayrollInputCommand command) {
        return ApiResponse.success(service.saveInput(month, employeeCode, command));
    }

    @Override
    public ApiResponse<PayrollInputCommand> input(String month, String employeeCode) {
        return ApiResponse.success(service.input(month, employeeCode));
    }

    @Override
    public ApiResponse<PayrollDetailView> confirm(String id, PayrollActionCommand command) {
        return ApiResponse.success(service.confirm(id, command));
    }

    @Override
    public ApiResponse<PayrollDetailView> paid(String id, PayrollPaymentCommand command) {
        return ApiResponse.success(workflow.pay(id, command));
    }

    @Override
    public ApiResponse<PayrollPreparationView> preparation(String month) {
        return ApiResponse.success(workflow.preparation(month));
    }

    @Override
    public ApiResponse<List<PayrollPreviewView>> preview(PayrollCalculateCommand command) {
        return ApiResponse.success(workflow.preview(command));
    }

    @Override
    public ApiResponse<Void> updateProfiles(PayrollBatchProfileCommand command) {
        workflow.updateProfiles(command);
        return ApiResponse.success(null);
    }

    @Override
    public ApiResponse<List<PayrollDetailView>> export(PayrollQuery query) {
        return ApiResponse.success(workflow.export(query));
    }

    @Override
    public ApiResponse<PayrollDetailView> reopen(String id, PayrollActionCommand command) {
        return ApiResponse.success(workflow.reopen(id, command));
    }

    @Override
    public ApiResponse<PayrollCorrectionView> correct(String id, PayrollCorrectionCommand command) {
        return ApiResponse.success(workflow.correct(id, command));
    }

    @Override
    public ApiResponse<Void> cancelCorrection(
            String id, String correctionId, PayrollActionCommand command) {
        workflow.cancelCorrection(id, correctionId, command);
        return ApiResponse.success(null);
    }

    @Override
    public ApiResponse<PayrollDetailView> detail(String id) {
        return ApiResponse.success(service.detail(id));
    }

    @Override
    public ApiResponse<List<PayrollAuditView>> history(String id) {
        return ApiResponse.success(service.history(id));
    }
}
