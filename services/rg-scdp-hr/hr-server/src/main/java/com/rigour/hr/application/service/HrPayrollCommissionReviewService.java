package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/** 读权限核验后复用真正的提成计算器；不调用保存、状态转换或发放接口。 */
@Service
public final class HrPayrollCommissionReviewService {
    private final HrPayrollWorkflowService workflow;
    private final HrPayrollStore payroll;
    private final HrPayrollSchemeStore schemes;
    private final HrPayrollSource source;
    private final ConfiguredCommissionService commission;
    public HrPayrollCommissionReviewService(HrPayrollWorkflowService workflow, HrPayrollStore payroll,
            HrPayrollSchemeStore schemes, HrPayrollSource source, ConfiguredCommissionService commission) {
        this.workflow = workflow; this.payroll = payroll; this.schemes = schemes;
        this.source = source; this.commission = commission;
    }

    public List<PayrollCommissionReviewView> preview(PayrollQuery query) {
        var actor = HrPayrollService.actor("hr:payroll:calculate");
        var rows = workflow.export(query);
        if (rows.isEmpty()) return List.of();
        String tenant = actor.tenantId().toString();
        var versions = schemes.versions(tenant);
        var assignments = payroll.rules(tenant).stream().filter(r -> "CITY_MANAGER".equals(r.data().kindCode()) && r.data().enabled()).toList();
        var result = new ArrayList<PayrollCommissionReviewView>();
        for (var row : rows) {
            var snapshot = row.calculation().scheme();
            if (snapshot == null) throw HrPayrollService.bad("工资单缺少原核算规则快照，请先核对工资依据");
            var employee = payroll.employee(tenant, row.employeeCode(), "hr:payroll:calculate");
            var month = YearMonth.parse(row.month());
            var lines = source.payrollLines(tenant, row.employeeCode(), null, month.atEndOfMonth());
            if (lines.size() >= 20001) throw HrPayrollService.bad("订单来源超过完整读取上限，请缩小试算范围");
            // 本次只授权销售存量订单补算；城市总政策涉及任期，不以本人销售替代团队事实。
            if (!"SALES".equals(snapshot.scheme().definition().role()))
                throw HrPayrollService.bad("历史提成试算暂仅适用于销售人员，请单独选择销售工资单");
            var computed = commission.calculate(tenant, employee, month, snapshot.scheme(), versions, lines, assignments);
            result.add(compare(row, computed));
        }
        return List.copyOf(result);
    }

    static PayrollCommissionReviewView compare(PayrollDetailView row, ConfiguredCommissionResult computed) {
        var before = row.calculation().details().stream().collect(Collectors.toMap(d -> d.sourceLineId() + "|" + d.recipientRoleCode(), d -> d));
        var after = computed.details().stream().collect(Collectors.toMap(d -> d.sourceLineId() + "|" + d.recipientRoleCode(), d -> d));
        var keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet());
        var lines = new ArrayList<PayrollCommissionReviewView.Line>();
        var issues = new ArrayList<>(computed.issues());
        for (var key : keys) {
            var old = before.get(key); var next = after.get(key); var source = next == null ? old : next;
            lines.add(new PayrollCommissionReviewView.Line(source.sourceLineId(), source.recipientRoleCode(), source.orderNo(),
                    source.productName(), source.unitCode(), source.quantity(), old == null ? null : old.released(),
                    next == null ? null : next.released(), next == null ? null : next.ruleName(),
                    next == null ? "当前规则不计提或已在其他工资计提，需核对原明细" : next.message()));
            if (next == null) issues.add(source.orderNo() + "：原提成行在本次结果中缺失，请核对排除项、来源及已计提状态");
        }
        var adjustment = row.calculation().scheme().commissionAdjustment();
        var subtotal = computed.released().add(adjustment == null ? BigDecimal.ZERO : adjustment);
        BigDecimal preview = issues.isEmpty() ? subtotal : null;
        var saved = row.calculation().commission();
        return new PayrollCommissionReviewView(row.id(), row.revision(), row.month(), row.employeeCode(), row.employeeName(),
                row.calculation().sourceFingerprint(), saved, preview, saved == null || preview == null ? null : preview.subtract(saved),
                subtotal, List.copyOf(lines), List.copyOf(issues));
    }
}
