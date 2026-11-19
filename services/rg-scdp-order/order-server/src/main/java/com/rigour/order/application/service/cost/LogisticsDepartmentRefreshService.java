package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 只补缺失的员工部门；不改已有历史归属、金额、付款或订单分摊。 */
@Service
@org.springframework.scheduling.annotation.EnableScheduling
public class LogisticsDepartmentRefreshService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LogisticsDepartmentRefreshService.class);
    private final OrderLogisticsCostStore store;
    private final OrderLogisticsReferences references;

    public LogisticsDepartmentRefreshService(
            OrderLogisticsCostStore store, OrderLogisticsReferences references) {
        this.store = store;
        this.references = references;
    }

    @Scheduled(
            fixedDelayString = "${rigour.order.logistics.department-recheck-ms:300000}",
            initialDelay = 15000)
    public void refresh() {
        for (var tenant : store.departmentTenants()) {
            var service =
                    UUID.nameUUIDFromBytes(
                            "order-logistics-department-refresh".getBytes(StandardCharsets.UTF_8));
            var caller =
                    new CallerIdentity(
                            "SERVICE",
                            service,
                            UUID.fromString(tenant),
                            null,
                            null,
                            UUID.randomUUID(),
                            0,
                            0,
                            0,
                            Set.of(),
                            Set.of("order:logistics-cost:sync-source"));
            try {
                String after = "";
                while (true) {
                    var rows = store.missingDepartments(tenant, after);
                    if (rows.isEmpty()) break;
                    var queries =
                            rows.stream()
                                    .flatMap(
                                            r ->
                                                    r.data().ledger().sources().stream()
                                                            .filter(
                                                                    LogisticsDepartmentRefreshService
                                                                            ::missing)
                                                            .map(s -> at(s, r.data().costDate())))
                                    .filter(q -> q.businessDate() != null)
                                    .distinct()
                                    .toList();
                    var employees =
                            new HashMap<
                                    OrderLogisticsReferences.DepartmentAt,
                                    LogisticsCostEmployeeView>();
                    for (int start = 0; start < queries.size(); start += 100)
                        employees.putAll(
                                references.employeeDepartmentsAt(
                                        caller,
                                        new HashSet<>(
                                                queries.subList(
                                                        start,
                                                        Math.min(queries.size(), start + 100))),
                                        "order:logistics-cost:sync-source"));
                    for (var row : rows)
                        if (!fill(row.data().ledger(), row.data().costDate(), employees)
                                .equals(row.data().ledger()))
                            store.fillDepartments(tenant, row.id(), row.revision(), employees);
                    after = rows.getLast().id();
                }
            } catch (RuntimeException unavailable) {
                log.warn(
                        "运输员工部门暂未核实，保留原记录 tenant={} errorType={}",
                        tenant,
                        unavailable.getClass().getSimpleName());
            }
        }
    }

    static boolean missing(LogisticsCostLedger.Source source) {
        return source.employeeCode() != null
                && !source.employeeCode().isBlank()
                && (source.departmentId() == null
                        || source.departmentName() == null
                        || source.departmentName().isBlank());
    }

    public static LogisticsCostLedger fill(
            LogisticsCostLedger ledger,
            java.time.LocalDate fallbackDate,
            Map<OrderLogisticsReferences.DepartmentAt, LogisticsCostEmployeeView> employees) {
        return ledger.withSources(
                ledger.sources().stream()
                        .map(
                                source -> {
                                    if (!missing(source)) return source;
                                    var employee = employees.get(at(source, fallbackDate));
                                    if (employee == null
                                            || employee.departmentId() == null
                                            || employee.departmentName() == null
                                            || employee.departmentName().isBlank()
                                            || source.departmentId() != null
                                                    && !source.departmentId()
                                                            .equals(employee.departmentId())
                                            || source.departmentId() == null
                                                    && source.departmentName() != null
                                                    && !source.departmentName().isBlank()
                                                    && !source.departmentName()
                                                            .equals(employee.departmentName()))
                                        return source;
                                    return new LogisticsCostLedger.Source(
                                            source.orderTypeCode(),
                                            source.orderCode(),
                                            source.linkRoleCode(),
                                            source.allocations(),
                                            source.noSourceReason(),
                                            source.employeeCode(),
                                            source.employeeName(),
                                            employee.departmentId(),
                                            employee.departmentName(),
                                            source.sourceWarehouseCode(),
                                            source.documentAmount(),
                                            source.documentDate(),
                                            source.linkedAt());
                                })
                        .toList());
    }

    static OrderLogisticsReferences.DepartmentAt at(
            LogisticsCostLedger.Source source, java.time.LocalDate fallbackDate) {
        return new OrderLogisticsReferences.DepartmentAt(
                source.employeeCode(),
                source.documentDate() == null ? fallbackDate : source.documentDate());
    }
}
