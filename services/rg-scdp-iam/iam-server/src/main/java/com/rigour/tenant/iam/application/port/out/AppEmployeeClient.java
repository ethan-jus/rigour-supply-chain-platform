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
            String unavailableReason,
            List<Long> managedDepartmentIds) {
        public Employee {
            managedDepartmentIds = managedDepartmentIds == null ? List.of() : List.copyOf(managedDepartmentIds);
        }

        public Employee(long id, String employeeCode, String employeeName, String employmentStatus,
                Long departmentId, String departmentName, String positionCode, String positionName,
                List<Long> departmentAncestorIds, long employeeRevision, long organizationVersion, long accessVersion,
                boolean usable, String unavailableReason) {
            this(id, employeeCode, employeeName, employmentStatus, departmentId, departmentName, positionCode,
                    positionName, departmentAncestorIds, employeeRevision, organizationVersion, accessVersion,
                    usable, unavailableReason, List.of());
        }
    }

    record Page(long total, int begin, int step, List<Employee> items) {}

    Employee employee(UUID tenant, String code);

    Map<String, Employee> employees(UUID tenant, List<String> codes);

    Page search(UUID tenant, String keyword, int begin, int step);
}
