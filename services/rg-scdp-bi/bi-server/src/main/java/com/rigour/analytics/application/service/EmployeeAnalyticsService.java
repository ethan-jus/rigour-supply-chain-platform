package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView;
import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView.Structure;
import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView.Summary;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.EmployeeAnalyticsStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 从 HR 人员总体计算结构，避免把有订单的员工当作全体销售或在职人数。 */
@Service
public final class EmployeeAnalyticsService {
    private final EmployeeAnalyticsStore store;
    private final BiDataScopeService scopes;
    private final Clock clock;

    public EmployeeAnalyticsService(EmployeeAnalyticsStore store, BiDataScopeService scopes, Clock analyticsClock) {
        this.store = store; this.scopes = scopes; this.clock = analyticsClock;
    }

    public EmployeeAnalyticsView report(Instant from, Instant to, String regionCode, String employeeCode) {
        var scope = scopes.resolve(regionCode, employeeCode);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? BiBusinessTime.monthStart(end) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start.atZone(BiBusinessTime.ZONE).toLocalDate(), end.atZone(BiBusinessTime.ZONE).toLocalDate()) > 1095) throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择开始不晚于结束且不超过三年的日期范围", List.of());
        var snapshot = store.read(scope.tenantId(), start, end, scope.regionCode(), scope.ownerStaffCode());
        if (snapshot.syncedAt() == null) {
            return new EmployeeAnalyticsView("NOT_READY", null, start, end, null, List.of(), List.of(), List.of(), List.of());
        }
        var rows = snapshot.rows();
        var employees = rows.stream().map(EmployeeAnalyticsStore.Row::employee).toList();
        var summary = new Summary(employees.size(),
                employees.stream().filter(e -> "ACTIVE".equals(e.employmentStatus())).count(),
                employees.stream().filter(e -> "LEFT".equals(e.employmentStatus())).count(),
                employees.stream().filter(e -> "INACTIVE".equals(e.employmentStatus())).count(),
                employees.stream().filter(e -> !List.of("ACTIVE", "LEFT", "INACTIVE").contains(e.employmentStatus())).count(),
                employees.stream().filter(e -> within(e.entryDate(), start, end)).count(),
                employees.stream().filter(e -> within(e.leaveDate(), start, end)).count(),
                employees.stream().filter(e -> e.entryDate() == null).count(),
                employees.stream().filter(e -> blank(e.departmentName())).count(),
                rows.stream().filter(e -> blank(e.regionCode())).count(),
                employees.stream().anyMatch(e -> e.orderCount() == null) ? null
                        : employees.stream().filter(e -> e.orderCount() > 0).count());
        return new EmployeeAnalyticsView(employees.isEmpty() ? "EMPTY" : "READY", snapshot.syncedAt(), start, end,
                summary, structure(employees, EmployeeAnalyticsView.Employee::cityName),
                structure(employees, EmployeeAnalyticsView.Employee::positionName), employees, months(employees, start, end));
    }

    private static List<EmployeeAnalyticsView.Month> months(List<EmployeeAnalyticsView.Employee> employees, Instant from, Instant to) {
        var counts = new TreeMap<YearMonth, long[]>();
        var end = YearMonth.from(to.atZone(BiBusinessTime.ZONE));
        for (var month = YearMonth.from(from.atZone(BiBusinessTime.ZONE)); !month.isAfter(end); month = month.plusMonths(1)) {
            counts.put(month, new long[2]);
        }
        for (var employee : employees) {
            if (within(employee.entryDate(), from, to)) counts.get(YearMonth.from(employee.entryDate().atZone(BiBusinessTime.ZONE)))[0]++;
            if (within(employee.leaveDate(), from, to)) counts.get(YearMonth.from(employee.leaveDate().atZone(BiBusinessTime.ZONE)))[1]++;
        }
        return counts.entrySet().stream().map(e -> new EmployeeAnalyticsView.Month(e.getKey().toString(), e.getValue()[0], e.getValue()[1])).toList();
    }

    private static List<Structure> structure(List<EmployeeAnalyticsView.Employee> rows,
            Function<EmployeeAnalyticsView.Employee, String> dimension) {
        var counts = new TreeMap<String, long[]>();
        for (var row : rows) {
            String name = dimension.apply(row);
            var count = counts.computeIfAbsent(blank(name) ? "未填写" : name, key -> new long[3]);
            count[0]++;
            if ("ACTIVE".equals(row.employmentStatus())) count[1]++;
            if ("LEFT".equals(row.employmentStatus())) count[2]++;
        }
        return counts.entrySet().stream().map(e -> new Structure(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong(Structure::total).reversed().thenComparing(Structure::name)).toList();
    }

    private static boolean within(Instant value, Instant from, Instant to) {
        return value != null && !value.isBefore(from) && !value.isAfter(to);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
