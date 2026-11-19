package com.rigour.hr.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface HrHistoricalDepartmentStore {
    record Employee(
            String code, String name, Long departmentId, String departmentName, boolean deleted) {}

    record Assignment(
            String employeeCode,
            Long departmentId,
            String departmentName,
            Instant from,
            Instant to) {}

    List<Employee> employees(String tenant, Set<String> codes);

    List<Assignment> assignments(String tenant, Set<String> codes);
}
