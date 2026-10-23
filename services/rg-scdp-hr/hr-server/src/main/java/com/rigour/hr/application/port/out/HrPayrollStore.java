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

    /** 报销城市人员引用：本城市员工和费用发生日有效的城市管辖人，仍受调用者人员范围限制。 */
    List<PayrollEmployeeView> expenseEmployees(
            String tenant, String action, String cityCode, String cityName, LocalDate at);

    PayrollEmployeeView employee(String tenant, String code, String action);

    String employeePositionName(String tenant, String code, String action);

    Long departmentAt(String tenant, String employee, LocalDate date);

    Set<Long> departmentsDuring(String tenant, String employee, LocalDate from, LocalDate to);

    /** Only codes already selected through the caller's employee data scope may be supplied. */
    default Map<String, Long> preparationDepartments(
            String tenant, List<String> employees, String month) {
        Map<String, Long> result = new java.util.HashMap<>();
        for (String employee : employees) {
            var input = input(tenant, employee, month);
            Long department =
                    input.departmentOverrideId() != null
                            ? input.departmentOverrideId()
                            : departmentAt(
                                    tenant,
                                    employee,
                                    java.time.YearMonth.parse(month).atEndOfMonth());
            if (department != null) result.put(employee, department);
        }
        return Map.copyOf(result);
    }

    List<PayrollDepartmentView> departments(String tenant, String action);

    List<PayrollCommissionPolicyView> policies();

    List<PayrollRuleView> rules(String tenant);

    void requireRuleScope(String tenant, PayrollRuleCommand command, String action);

    PayrollRuleView saveRule(String tenant, String id, PayrollRuleCommand command, String actor);

    PayrollInputCommand input(String tenant, String employee, String month);

    PayrollInputCommand saveInput(
            String tenant, String employee, String month, PayrollInputCommand input, String actor);

    Set<String> released(String tenant);

    /** 已保存工资的只读重算须保留本单提成，其他工资的已计提行仍不能重复计入。 */
    Set<String> releasedForCalculation(String tenant, String employee, String month);

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
