package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.HrHistoricalDepartmentApi.*;
import com.rigour.hr.application.port.out.HrHistoricalDepartmentStore;
import com.rigour.hr.application.port.out.HrHistoricalDepartmentStore.*;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/** 当前任用校验和历史费用归属分别处理，历史记录不因离职或岗位停用而失去部门。 */
@Service
public class HrHistoricalDepartmentService {
    private final HrHistoricalDepartmentStore store;

    public HrHistoricalDepartmentService(HrHistoricalDepartmentStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<Attribution> resolve(List<Query> queries) {
        var caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope()) || caller.tenantId() == null)
            throw new AuthorizationDeniedException("service-identity-reader");
        AuthorizationContext.requirePermission("hr:employee:identity-read");
        if (queries == null
                || queries.size() > 100
                || queries.stream()
                        .anyMatch(
                                q ->
                                        q == null
                                                || q.employeeCode() == null
                                                || q.employeeCode().isBlank()
                                                || q.employeeCode().length() > 50
                                                || q.businessDate() == null))
            throw new IllegalArgumentException("历史部门核验需提供员工编号及业务日期，单次最多100项");
        if (queries.isEmpty()) return List.of();
        var distinct = queries.stream().distinct().toList();
        var codes = distinct.stream().map(Query::employeeCode).collect(Collectors.toSet());
        var employees =
                store.employees(caller.tenantId().toString(), codes).stream()
                        .collect(Collectors.toMap(Employee::code, e -> e));
        var assignments =
                store.assignments(caller.tenantId().toString(), codes).stream()
                        .collect(Collectors.groupingBy(Assignment::employeeCode));
        return distinct.stream()
                .map(
                        q ->
                                select(
                                        q,
                                        employees.get(q.employeeCode()),
                                        assignments.getOrDefault(q.employeeCode(), List.of())))
                .toList();
    }

    static Attribution select(Query q, Employee employee, List<Assignment> history) {
        if (employee == null) return result(q, null, null, null, "UNRESOLVED", "未找到员工档案");
        var start = q.businessDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var end = q.businessDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var candidates =
                history.stream()
                        .filter(
                                a ->
                                        a.from() != null
                                                && a.from().isBefore(end)
                                                && (a.to() == null || a.to().isAfter(start)))
                        .map(
                                a ->
                                        new AbstractMap.SimpleImmutableEntry<>(
                                                a.departmentId(), a.departmentName()))
                        .distinct()
                        .toList();
        if (candidates.size() > 1)
            return result(q, employee, null, null, "UNRESOLVED", "业务当日存在不同部门任职，需核对历史归属");
        if (candidates.size() == 1) {
            var a = candidates.getFirst();
            if (valid(a.getKey(), a.getValue()))
                return result(q, employee, a.getKey(), a.getValue(), "ASSIGNMENT", null);
        }
        // 有任职历史但业务日期落在缺口中时，不能拿调岗后的当前部门补旧单。
        if (!history.isEmpty())
            return result(q, employee, null, null, "UNRESOLVED", "业务日期缺少明确的部门任职记录");
        // 旧导入员工可能尚无任职流水；只复用该员工明确保存的部门快照，不推断在职状态。
        if (!employee.deleted() && valid(employee.departmentId(), employee.departmentName()))
            return result(
                    q,
                    employee,
                    employee.departmentId(),
                    employee.departmentName(),
                    "EMPLOYEE_SNAPSHOT",
                    null);
        return result(q, employee, null, null, "UNRESOLVED", "缺少可追溯的员工部门资料");
    }

    private static boolean valid(Long id, String name) {
        return id != null && id > 0 && name != null && !name.isBlank();
    }

    private static Attribution result(
            Query q, Employee e, Long id, String name, String basis, String issue) {
        return new Attribution(
                q.employeeCode(),
                q.businessDate(),
                e == null ? null : e.name(),
                id,
                name,
                basis,
                issue);
    }
}
