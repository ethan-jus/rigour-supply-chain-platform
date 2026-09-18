package com.rigour.tenant.iam.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AppEmployeeClient {
    record Employee(
            long id,
            String employeeCode,
            String employeeName,
            String employmentStatus,
            Long departmentId,
            String departmentName,
            String positionCode,
            String positionName,
            List<Long> departmentAncestorIds,
            long employeeRevision,
            long organizationVersion,
            long accessVersion,
            boolean usable,
            String unavailableReason) {}

    record Page(long total, int begin, int step, List<Employee> items) {}

    Employee employee(UUID tenant, String code);

    Map<String, Employee> employees(UUID tenant, List<String> codes);

    Page search(UUID tenant, String keyword, int begin, int step);
}
