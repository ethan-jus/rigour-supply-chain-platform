package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.*;

import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetailView;
import com.rigour.hr.api.v1.model.HrExpenseQuery;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollAuditView;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
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

    public HrExpenseService(
            HrExpenseStore expenses, HrPayrollStore payroll, HrPayrollSource source) {
        this.expenses = expenses;
        this.payroll = payroll;
        this.source = source;
    }

    public HrPageView<HrExpenseDetailView> expenses(HrExpenseQuery q) {
        var a = actor("hr:expense:read");
        if (q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("截止日期不能早于开始日期");
        return expenses.expenses(a.tenantId().toString(), q, "hr:expense:read");
    }

    public List<PayrollEmployeeView> employees() {
        var a = actor("hr:expense:read");
        return payroll.employees(a.tenantId().toString(), "hr:expense:read");
    }

    public HrExpenseDetailView save(String id, HrExpenseCommand c) {
        var a = actor("hr:expense:write");
        String t = a.tenantId().toString();
        if (c == null) throw bad("请填写报销费用");
        required(c.title(), 200, "报销事项");
        required(c.cityCode(), 64, "归属城市编码");
        required(c.cityName(), 120, "归属城市");
        required(c.employeeCode(), 50, "报销员工");
        money(c.amount(), true);
        text(c.remark(), 1000, "备注");
        text(c.reason(), 500, "变更原因");
        if (c.costDate() == null) throw bad("请选择费用日期");
        if (id == null) request(c.requestId());
        else revision(c.revision());
        if (c.typeCode() == null
                || !source.dictionary(t, "REIMBURSEMENT_EXPENSE_TYPE").contains(c.typeCode()))
            throw bad("请选择启用的报销类型，营销费用请在成本管理登记");
        if (c.paymentStatusCode() == null
                || !source.dictionary(t, "COST_PAYMENT_STATUS").contains(c.paymentStatusCode()))
            throw bad("付款状态无效");
        var employee = payroll.employee(t, c.employeeCode(), "hr:expense:write");
        return expenses.save(t, id, c, employee, a.principalId().toString());
    }

    public HrExpenseDetailView action(String id, PayrollActionCommand c, boolean confirm) {
        String p = "hr:expense:" + (confirm ? "confirm" : "void");
        var a = actor(p);
        checkAction(c);
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
}
