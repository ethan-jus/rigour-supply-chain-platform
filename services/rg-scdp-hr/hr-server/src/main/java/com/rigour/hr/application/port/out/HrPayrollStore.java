package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface HrPayrollStore {
    com.rigour.hr.api.v1.model.PayrollSalarySourceView salarySource(String tenant, String employee);

    void lock(String tenant);

    List<PayrollDetailView> export(String tenant, PayrollQuery query, String action);

    List<PayrollDetailView> monthRecords(String tenant, String month, String action);

    List<PayrollCorrectionView> corrections(String tenant, String employee, String month);

    PayrollDetailView reopen(String tenant, String id, PayrollActionCommand command, String actor);

    PayrollDetailView pay(String tenant, String id, PayrollPaymentCommand command, String actor);

    PayrollCorrectionView correct(
            String tenant, String id, PayrollCorrectionCommand command, String actor);

    void cancelCorrection(
            String tenant,
            String id,
            String correctionId,
            PayrollActionCommand command,
            String actor);

    List<PayrollEmployeeView> employees(String tenant, String action);

    PayrollEmployeeView employee(String tenant, String code, String action);

    Long departmentAt(String tenant, String employee, LocalDate date);

    List<PayrollDepartmentView> departments(String tenant, String action);

    List<PayrollCommissionPolicyView> policies();

    List<PayrollRuleView> rules(String tenant);

    void requireRuleScope(String tenant, PayrollRuleCommand command, String action);

    PayrollRuleView saveRule(String tenant, String id, PayrollRuleCommand command, String actor);

    PayrollInputCommand input(String tenant, String employee, String month);

    PayrollInputCommand saveInput(
            String tenant, String employee, String month, PayrollInputCommand input, String actor);

    Set<String> released(String tenant);

    Map<String, PayrollCommissionDetailView> confirmedSalesCommissions(
            String tenant, Set<String> employees, Set<String> lineIds, String throughMonth);

    Optional<PayrollDetailView> byMonth(String tenant, String employee, String month);

    PayrollDetailView saveCalculation(
            String tenant,
            String employee,
            String month,
            PayrollEmployeeView identity,
            PayrollCalculationView result,
            String actor);

    PayrollDetailView detail(String tenant, String id, String action);

    HrPageView<PayrollDetailView> payroll(String tenant, PayrollQuery query, String action);

    PayrollSummaryView summary(String tenant, PayrollQuery query, String action);

    PayrollDetailView transition(
            String tenant, String id, PayrollActionCommand command, String status, String actor);

    List<PayrollAuditView> history(String tenant, String id);

    default List<PayrollDetailView> commissionHistory(
            String tenant, String employee, String throughMonth) {
        return List.of();
    }
}
