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
                    var codes =
                            rows.stream()
                                    .flatMap(r -> r.data().ledger().sources().stream())
                                    .filter(LogisticsDepartmentRefreshService::missing)
                                    .map(LogisticsCostLedger.Source::employeeCode)
                                    .distinct()
                                    .toList();
                    var employees = new HashMap<String, LogisticsCostEmployeeView>();
                    var conflicts = new HashSet<String>();
                    for (int start = 0; start < codes.size(); start += 100)
                        for (var employee :
                                references.employeeDepartments(
                                        caller,
                                        new HashSet<>(
                                                codes.subList(
                                                        start,
                                                        Math.min(codes.size(), start + 100))),
                                        "order:logistics-cost:sync-source")) {
                            var previous = employees.putIfAbsent(employee.employeeCode(), employee);
                            if (previous != null && !previous.equals(employee))
                                conflicts.add(employee.employeeCode());
                        }
                    conflicts.forEach(employees::remove);
                    for (var row : rows)
                        if (!fill(row.data().ledger(), employees).equals(row.data().ledger()))
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
            LogisticsCostLedger ledger, Map<String, LogisticsCostEmployeeView> employees) {
        return ledger.withSources(
                ledger.sources().stream()
                        .map(
                                source -> {
                                    if (!missing(source)) return source;
                                    var employee = employees.get(source.employeeCode());
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
}
