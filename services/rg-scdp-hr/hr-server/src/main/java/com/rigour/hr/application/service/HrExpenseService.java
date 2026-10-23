package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.*;

import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetailView;
import com.rigour.hr.api.v1.model.HrExpensePageView;
import com.rigour.hr.api.v1.model.HrExpenseQuery;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollAuditView;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.application.port.out.HrExpenseCitySource;
import com.rigour.hr.application.port.out.HrExpenseStore;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public final class HrExpenseService {
    private final HrExpenseStore expenses;
    private final HrPayrollStore payroll;
    private final HrPayrollSource source;
    private final HrExpenseCitySource cities;

    private final com.rigour.hr.application.port.out.HrExpenseCostSource costSource;

    public HrExpenseService(
            HrExpenseStore expenses,
            HrPayrollStore payroll,
            HrPayrollSource source,
            com.rigour.hr.application.port.out.HrExpenseCostSource costSource,
            HrExpenseCitySource cities) {
        this.expenses = expenses;
        this.payroll = payroll;
        this.source = source;
        this.costSource = costSource;
        this.cities = cities;
    }

    public HrExpensePageView expenses(HrExpenseQuery q) {
        var a = actor("hr:expense:read");
        text(q.documentNo(), 100, "费用单号");
        text(q.title(), 200, "费用事项");
        text(q.remark(), 1000, "备注");
        if (q.costMonth() != null && !q.costMonth().isBlank()) {
            try {
                java.time.YearMonth.parse(q.costMonth());
            } catch (RuntimeException e) {
                throw bad("费用月份无效");
            }
        }
        if (q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("截止日期不能早于开始日期");
        return expenses.expenses(a.tenantId().toString(), q, "hr:expense:read");
    }

    public HrExpenseDetailView detail(String id) {
        var a = actor("hr:expense:read");
        return expenses.expenseDetail(a.tenantId().toString(), id, "hr:expense:read");
    }

    public List<PayrollEmployeeView> employees() {
        return employees(null, null, null);
    }

    public List<PayrollEmployeeView> employees(
            String cityCode, String cityName, java.time.LocalDate costDate) {
        var a = actor("hr:expense:read");
        if (cityCode == null || cityCode.isBlank()) {
            if (cityName != null && !cityName.isBlank()) throw bad("请完整选择归属城市");
            return payroll.employees(a.tenantId().toString(), "hr:expense:read");
        }
        required(cityCode, 64, "归属城市编码");
        required(cityName, 120, "归属城市");
        validateCity(cityCode, cityName, "hr:expense:read");
        // 总部是费用承担对象，不是员工所在地；仍使用原员工读取范围。
        if (com.rigour.hr.api.v1.model.HrCostCityReference.HEADQUARTERS_CODE.equals(cityCode))
            return payroll.employees(a.tenantId().toString(), "hr:expense:read");
        var at =
                costDate == null
                        ? java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        : costDate;
        return payroll.expenseEmployees(
                a.tenantId().toString(), "hr:expense:read", cityCode, cityName, at);
    }

    public HrExpenseDetailView save(String id, HrExpenseCommand c) {
        var a = actor("hr:expense:write");
        String t = a.tenantId().toString();
        if (c == null) throw bad("请填写报销费用");
        c = HrExpenseRules.classify(c);
        required(c.title(), 200, "报销事项");
        required(c.cityCode(), 64, "归属城市编码");
        required(c.cityName(), 120, "归属城市");
        required(c.employeeCode(), 50, "报销员工");
        money(c.amount(), true);
        text(c.remark(), 1000, "备注");
        text(c.reason(), 500, "变更原因");
        if (c.costDate() == null) throw bad("请选择费用日期");
        HrExpenseRules.validate(c, false, 0);
        if (id == null) request(c.requestId());
        else revision(c.revision());
        if (c.typeCode() == null
                || !source.dictionary(t, "REIMBURSEMENT_EXPENSE_TYPE").contains(c.typeCode()))
            throw bad("请选择启用的报销类型");
        if (c.paymentStatusCode() == null
                || !source.dictionary(t, "COST_PAYMENT_STATUS").contains(c.paymentStatusCode()))
            throw bad("付款状态无效");
        validateCity(c.cityCode(), c.cityName(), "hr:expense:write");
        var employee = payroll.employee(t, c.employeeCode(), "hr:expense:write");
        return expenses.save(t, id, c, employee, a.principalId().toString());
    }

    public HrExpenseDetailView action(String id, PayrollActionCommand c, boolean confirm) {
        String p = "hr:expense:" + (confirm ? "confirm" : "void");
        var a = actor(p);
        checkAction(c);
        if (confirm) {
            var current = expenses.expenseDetail(a.tenantId().toString(), id, p);
            validateCity(current.data().cityCode(), current.data().cityName(), p);
            if (current.data().expense() != null && current.data().expense().relatedCost() != null)
                costSource.verify(current.data());
        }
        return expenses.expenseTransition(
                a.tenantId().toString(),
                id,
                c,
                confirm ? "CONFIRMED" : "VOID",
                a.principalId().toString());
    }

    public List<PayrollAuditView> history(String id) {
        var a = actor("hr:expense:read");
        String t = a.tenantId().toString();
        expenses.expenseDetail(t, id, "hr:expense:read");
        return payroll.history(t, id);
    }

    private void validateCity(String code, String name, String action) {
        required(code, 64, "归属城市编码");
        required(name, 120, "归属城市");
        if (!name.equals(cities.cityName(code, action))) throw bad("城市名称与授权目录不一致，请重新选择归属城市");
    }
}
