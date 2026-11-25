package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.SalesPayrollBoardView.*;
import com.rigour.hr.application.port.out.HrAttendanceStore;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.order.api.v1.model.OrderPayrollLineView;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

@Service
public class SalesPayrollBoardService {
    private final HrAttendanceStore attendance;
    private final HrPayrollStore store;
    private final HrPayrollSource source;
    private final PayrollSettlementAttribution settlementAttribution;
    private final SupplyAuthorizationClient authorizations;
    private final com.rigour.hr.infrastructure.persistence.repository.SalesStatementPublications
            publications;
    private static final String READ = "hr:payroll:read", SELF = "hr:payroll:self";
    private static final Set<String> LOCKED = Set.of("CONFIRMED", "PAID");
    private static final Set<String> SALES = Set.of("SALES", "CITY_MANAGER", "TRAINEE_MANAGER");

    public SalesPayrollBoardService(
            HrPayrollStore store,
            HrPayrollSource source,
            SupplyAuthorizationClient authorizations,
            com.rigour.hr.infrastructure.persistence.repository.SalesStatementPublications
                    publications,
            HrAttendanceStore attendance,
            PayrollSettlementAttribution settlementAttribution) {
        this.attendance = attendance;
        this.publications = publications;
        this.store = store;
        this.source = source;
        this.settlementAttribution = settlementAttribution;
        this.authorizations = authorizations;
    }

    public SalesPayrollBoardView personal(String month) {
        var actor = HrPayrollService.actor(SELF);
        var period = period(month);
        if (!"TENANT".equals(actor.principalScope())) throw new AuthorizationDeniedException(SELF);
        var policy =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(SELF)
                        : authorizations.authorization(actor, SELF);
        if (policy == null
                || !"ACTIVE".equals(policy.mode())
                || !policy.functionAllowed()
                || !SELF.equals(policy.action())
                || !actor.tenantId().equals(policy.tenantId())
                || !actor.userId().equals(policy.userId())
                || policy.employeeCode() == null
                || policy.employeeCode().isBlank()) throw new AuthorizationDeniedException(SELF);
        var rows =
                publications
                        .published(
                                actor.tenantId().toString(),
                                policy.employeeCode(),
                                period.toString())
                        .stream()
                        .toList();
        return new SalesPayrollBoardView(month, rows);
    }

    @org.springframework.transaction.annotation.Transactional
    public void publish(String id, int revision) {
        String action = "hr:payroll:publish";
        var actor = HrPayrollService.actor(action);
        String tenant = actor.tenantId().toString();
        var row = store.detail(tenant, id, action);
        if (!LOCKED.contains(row.statusCode()) || row.revision() != revision)
            throw HrPayrollService.bad("只能公布已经确认的当前工资版本");
        publications.publish(
                tenant,
                id,
                row.month(),
                revision,
                actor.principalId().toString(),
                employee(period(row.month()), row, null, List.of()));
    }

    public SalesPayrollBoardView team(String month) {
        var actor = HrPayrollService.actor(READ);
        var period = period(month);
        var tenant = actor.tenantId().toString();
        var saved = new LinkedHashMap<String, PayrollDetailView>();
        for (var r : store.monthRecords(tenant, month, READ)) saved.put(r.employeeCode(), r);
        var result = new ArrayList<Employee>();
        for (var e : store.employees(tenant, READ)) {
            var snapshot = saved.remove(e.employeeCode());
            if (!sales(snapshot, e.payrollCategory())) continue;
            if (snapshot == null
                    && ((e.entryDate() != null && e.entryDate().isAfter(period.atEndOfMonth()))
                            || (e.leaveDate() != null && e.leaveDate().isBefore(period.atDay(1)))))
                continue;
            result.add(teamEmployee(tenant, period, snapshot, e));
        }
        for (var row : saved.values())
            if (sales(row, null)) result.add(teamEmployee(tenant, period, row, null));
        return new SalesPayrollBoardView(month, List.copyOf(result));
    }

    private Employee teamEmployee(
            String tenant, YearMonth period, PayrollDetailView row, PayrollEmployeeView person) {
        if (row != null && LOCKED.contains(row.statusCode()))
            return employee(period, row, person, List.of());
        var code = row == null ? person.employeeCode() : row.employeeCode();
        if ("SALES".equals(payrollRole(row, person == null ? null : person.payrollCategory()))
                && settlementAttribution.applies(period))
            return employee(
                    period,
                    row,
                    person,
                    List.of(),
                    settlementAttribution.calculate(tenant, code, period));
        return employee(
                period, row, person, source.payrollLines(tenant, code, null, period.atEndOfMonth()));
    }

    public List<SalesPerformanceRow> performance(String month) {
        String action = "analytics:dashboard:read";
        var actor = HrPayrollService.actor(action);
        var period = period(month);
        var employees = new HashMap<String, PayrollEmployeeView>();
        for (var employee : store.employees(actor.tenantId().toString(), action))
            employees.put(employee.employeeCode(), employee);
        var result = new ArrayList<SalesPerformanceRow>();
        for (var row : store.monthRecords(actor.tenantId().toString(), month, action)) {
            var c = row.calculation();
            if (c == null) continue;
            if (!sales(row, c.salarySource() == null ? null : c.salarySource().payrollCategory()))
                continue;
            var perf = c.performanceDetail();
            // Only business performance is projected. Pay, rules, commissions and deductions stay
            // in HR.
            var orders = employee(period, row, null, List.of());
            var basis =
                    LOCKED.contains(row.statusCode())
                            ? orders.basis()
                            : perf == null ? null : perf.collections();
            var days = perf == null ? null : perf.days();
            AttendanceRow confirmation = null;
            if (perf != null) {
                try {
                    confirmation =
                            attendance
                                    .employee(
                                            actor.tenantId().toString(),
                                            period,
                                            row.employeeCode(),
                                            action)
                                    .confirmation();
                } catch (AuthorizationDeniedException unavailable) {
                    // An archived employee can have a payroll snapshot but no accessible current
                    // attendance roster.
                }
            }
            var process =
                    PayrollPerformanceReportFacts.processSummary(
                            row, confirmation, new LinkedHashSet<>());
            var employee = employees.get(row.employeeCode());
            var scheme = c.scheme() == null ? null : c.scheme().scheme();
            boolean exempt =
                    PayrollPerformanceReportFacts.collectionExempt(
                            period,
                            employee == null ? null : employee.entryDate(),
                            scheme == null ? null : scheme.definition());
            result.add(
                    new SalesPerformanceRow(
                            row.employeeCode(),
                            row.statusCode(),
                            basis,
                            exempt
                                    ? BigDecimal.ZERO
                                    : perf == null ? null : perf.collectionTarget(),
                            process.actualCheckins(),
                            process.actualVisits(),
                            days == null
                                    ? null
                                    : days.stream()
                                            .mapToInt(PayrollPerformanceDayView::missingVisits)
                                            .sum(),
                            process.expectedVisits(),
                            row.employeeName(),
                            row.cityName(),
                            exempt));
        }
        return result;
    }

    private static boolean sales(PayrollDetailView row, String currentCategory) {
        var role = payrollRole(row, currentCategory);
        return role != null && SALES.contains(role);
    }

    static String payrollRole(PayrollDetailView row, String currentCategory) {
        if (row != null
                && row.calculation() != null
                && row.calculation().scheme() != null
                && row.calculation().scheme().scheme() != null
                && row.calculation().scheme().scheme().definition() != null)
            return row.calculation().scheme().scheme().definition().role();
        if (row != null && row.calculation() != null && row.calculation().salarySource() != null
                && row.calculation().salarySource().payrollCategory() != null)
            return row.calculation().salarySource().payrollCategory();
        return currentCategory;
    }

    private static YearMonth period(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException error) {
            throw HrPayrollService.bad("请选择有效的核算月份");
        }
    }

    static Employee employee(
            YearMonth month,
            PayrollDetailView row,
            PayrollEmployeeView person,
            List<OrderPayrollLineView> facts) {
        return employee(month, row, person, facts, null);
    }

    private static Employee employee(
            YearMonth month,
            PayrollDetailView row,
            PayrollEmployeeView person,
            List<OrderPayrollLineView> facts,
            PayrollSettlementAttribution.Result settlement) {
        var c = row == null ? null : row.calculation();
        boolean locked = row != null && LOCKED.contains(row.statusCode());
        var orders = new LinkedHashMap<String, Order>();
        var issues = new LinkedHashSet<String>();
        if (c != null && c.issues() != null) issues.addAll(c.issues());
        var perf = c == null ? null : c.performanceDetail();
        var basisKnown = true;
        if (locked && c == null) {
            basisKnown = false;
            issues.add("该工资记录缺少已确认的核算快照");
        } else if (locked) {
            if (c.settledOrders() != null
                    || (perf != null
                            && perf.sources() != null
                            && perf.sources().stream()
                                    .anyMatch(s -> "FULLY_PAID".equals(s.kind())))) {
                var evidence = c.settledOrders() != null ? c.settledOrders() : perf.sources();
                for (var s : evidence)
                    if ("FULLY_PAID".equals(s.kind())
                            && s.date() != null
                            && month.equals(YearMonth.from(s.date())))
                        orders.putIfAbsent(
                                s.sourceId(),
                                settledOrder(s, "CONFIRMED"));
            } else {
                for (var d :
                        c.details() == null
                                ? List.<PayrollCommissionDetailView>of()
                                : c.details()) {
                    var tr = d.trace();
                    if (tr == null
                            || tr.fullyPaidDate() == null
                            || !month.equals(YearMonth.from(tr.fullyPaidDate()))
                            || !Objects.equals(row.employeeCode(), d.sourceEmployeeCode())
                            || tr.settlement() == null) continue;
                    orders.putIfAbsent(
                            tr.orderId(),
                            new Order(
                                    tr.orderId(),
                                    d.orderNo(),
                                    tr.fullyPaidDate(),
                                    tr.settlement().orderAmount(),
                                    "CONFIRMED"));
                }
                if (orders.isEmpty()
                        && (perf == null
                                || perf.collections() == null
                                || perf.collections().signum() != 0)) {
                    basisKnown = false;
                    issues.add("该工资快照缺少整单结清明细");
                }
            }
        } else if (settlement != null) {
            issues.addAll(settlement.issues());
            for (var s : settlement.evidence())
                orders.put(s.sourceId(), settledOrder(s, "ELIGIBLE"));
            for (var f : settlement.rows())
                if (!orders.containsKey("FULL:" + f.orderId()))
                    orders.putIfAbsent(
                            f.orderId(),
                            new Order(
                                    f.orderId(),
                                    f.orderNo(),
                                    f.fullyPaidDate(),
                                    null,
                                    "REVIEW",
                                    f.paymentNo(),
                                    f.paymentOwnerCode(),
                                    f.paymentOwnerName()));
        } else {
            for (var f : facts) {
                if (f.fullyPaidDate() == null || !month.equals(YearMonth.from(f.fullyPaidDate()))) {
                    if ("NON_CASH_SETTLEMENT".equals(f.qualityStatus())
                            && orders.putIfAbsent(
                                            f.orderId(),
                                            new Order(
                                                    f.orderId(), f.orderNo(), null, null, "REVIEW"))
                                    == null) issues.add(f.orderNo() + " 减免或核销待核");
                    continue;
                }
                boolean ok = "COMPLETE".equals(f.qualityStatus()) && f.orderAmount() != null;
                if (!ok) issues.add(f.orderNo() + " 结清归属或基数待核");
                orders.putIfAbsent(
                        f.orderId(),
                        new Order(
                                f.orderId(),
                                f.orderNo(),
                                f.fullyPaidDate(),
                                ok ? f.orderAmount() : null,
                                ok ? "ELIGIBLE" : "REVIEW"));
            }
        }
        var basis =
                !locked && settlement != null
                        ? settlement.amount()
                        : basisKnown
                        ? orders.values().stream()
                                .map(Order::basis)
                                .filter(Objects::nonNull)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                        : null;
        var details =
                c == null || c.details() == null
                        ? List.<Commission>of()
                        : c.details().stream()
                                .map(
                                        d ->
                                                new Commission(
                                                        d.orderNo(),
                                                        d.productName(),
                                                        d.recipientRoleCode(),
                                                        d.ruleName(),
                                                        d.ruleRevision(),
                                                        d.quantity(),
                                                        d.accrued(),
                                                        d.released()))
                                .toList();
        BigDecimal paid =
                row == null
                        ? null
                        : row.payments() != null && !row.payments().isEmpty()
                                ? row.payments().stream()
                                        .map(PayrollPaymentView::amount)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                                : "PAID".equals(row.statusCode()) && c != null
                                        ? c.netPay()
                                        : BigDecimal.ZERO;
        return new Employee(
                row == null ? person.employeeCode() : row.employeeCode(),
                row == null ? person.employeeName() : row.employeeName(),
                row == null ? person.cityName() : row.cityName(),
                row == null ? "NOT_CALCULATED" : row.statusCode(),
                locked ? "SNAPSHOT" : settlement == null ? "ORDER" : "SETTLEMENT_PAYMENT",
                basis,
                List.copyOf(orders.values()),
                c == null ? null : c.commission(),
                c == null ? null : c.heldCommission(),
                c == null ? null : c.baseSalary(),
                perf == null ? null : perf.processPay(),
                c == null ? null : c.performance(),
                c == null ? null : c.allowance(),
                c == null ? null : c.deduction(),
                c == null ? null : c.grossPay(),
                c == null ? null : c.netPay(),
                paid,
                c == null ? null : c.correctionAmount(),
                List.copyOf(issues),
                details,
                c != null && c.scheme() != null ? c.scheme().metrics() : List.of(),
                row == null ? 0 : row.revision(),
                row == null ? null : row.updatedTime(),
                row == null ? null : row.id(),
                row == null ? person.departmentName() : row.departmentName(),
                c != null
                                && c.scheme() != null
                                && c.scheme().scheme() != null
                                && c.scheme().scheme().definition() != null
                        ? c.scheme().scheme().definition().role()
                        : person != null ? person.payrollCategory() : null,
                perf == null ? null : perf.collectionTarget(),
                c != null && c.corrections() != null ? c.corrections() : List.of());
    }

    private static Order settledOrder(PayrollCollectionEvidenceView evidence, String state) {
        return new Order(
                evidence.sourceId(),
                evidence.orderNo(),
                evidence.date(),
                evidence.amount(),
                state,
                evidence.settlementPaymentNo(),
                evidence.paymentOwnerCode(),
                evidence.paymentOwnerName());
    }
}
