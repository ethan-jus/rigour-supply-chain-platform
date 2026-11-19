package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.SalesPayrollBoardView.*;
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
    private final HrPayrollStore store;
    private final HrPayrollSource source;
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
                    publications) {
        this.publications = publications;
        this.store = store;
        this.source = source;
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
            var facts =
                    snapshot != null && LOCKED.contains(snapshot.statusCode())
                            ? List.<OrderPayrollLineView>of()
                            : source.payrollLines(
                                    tenant, e.employeeCode(), null, period.atEndOfMonth());
            result.add(employee(period, snapshot, e, facts));
        }
        for (var row : saved.values())
            if (sales(row, null)) {
                var facts =
                        LOCKED.contains(row.statusCode())
                                ? List.<OrderPayrollLineView>of()
                                : source.payrollLines(
                                        tenant, row.employeeCode(), null, period.atEndOfMonth());
                result.add(employee(period, row, null, facts));
            }
        return new SalesPayrollBoardView(month, List.copyOf(result));
    }

    public List<SalesPerformanceRow> performance(String month) {
        String action = "analytics:dashboard:read";
        var actor = HrPayrollService.actor(action);
        var period = period(month);
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
            result.add(
                    new SalesPerformanceRow(
                            row.employeeCode(),
                            row.statusCode(),
                            basis,
                            perf == null ? null : perf.collectionTarget(),
                            days == null
                                    ? null
                                    : days.stream()
                                            .mapToInt(PayrollPerformanceDayView::checkins)
                                            .sum(),
                            days == null
                                    ? null
                                    : days.stream()
                                            .mapToInt(PayrollPerformanceDayView::effectiveVisits)
                                            .sum(),
                            days == null
                                    ? null
                                    : days.stream()
                                            .mapToInt(PayrollPerformanceDayView::missingVisits)
                                            .sum()));
        }
        return result;
    }

    private static boolean sales(PayrollDetailView row, String currentCategory) {
        if (row != null
                && row.calculation() != null
                && row.calculation().scheme() != null
                && row.calculation().scheme().scheme() != null
                && row.calculation().scheme().scheme().definition() != null)
            return SALES.contains(row.calculation().scheme().scheme().definition().role());
        return currentCategory != null && SALES.contains(currentCategory);
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
                                new Order(
                                        s.sourceId(),
                                        s.orderNo(),
                                        s.date(),
                                        s.amount(),
                                        "CONFIRMED"));
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
                basisKnown
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
                locked ? "SNAPSHOT" : "ORDER",
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
}
