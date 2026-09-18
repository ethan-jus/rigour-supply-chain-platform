package com.rigour.merchant.application.port.out;

import java.util.List;

/** CRM 只按 HR 稳定员工编码核验新主责，不创建或维护人员档案。 */
public interface CrmEmployeeClient {
    record Owner(
            String code,
            String name,
            boolean usable,
            String unavailableReason,
            long revision,
            Long departmentId,
            String departmentName,
            List<Long> departmentAncestorIds,
            long organizationVersion,
            long accessVersion) {
        public Owner {
            departmentAncestorIds = List.copyOf(departmentAncestorIds);
        }

        public Owner(String code, String name, boolean usable, String reason, long revision) {
            this(code, name, usable, reason, revision, null, null, List.of(), 0, 0);
        }
    }

    Owner owner(String tenant, String employeeCode);

    List<Owner> search(String tenant, String keyword);
}
