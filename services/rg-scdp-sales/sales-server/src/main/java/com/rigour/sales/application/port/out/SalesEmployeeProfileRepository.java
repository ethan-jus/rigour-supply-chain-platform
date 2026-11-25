package com.rigour.sales.application.port.out;

import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource.Employee;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SalesEmployeeProfileRepository {
    Optional<Profile> findByUser(UUID tenantId, UUID userId);
    Registration register(UUID tenantId, UUID userId, Employee employee, Instant now);
    boolean hasLegacyBinding(UUID tenantId, UUID userId);

    record Registration(Profile profile, UUID previousUserId) {}

    record Profile(UUID id, long employeeId, String employeeCode, String salesNo, UUID cityOrgId,
                   String status, Instant effectiveFrom, Instant effectiveTo) {
        public boolean activeAt(Instant now) {
            return "ACTIVE".equals(status) && !effectiveFrom.isAfter(now)
                    && (effectiveTo == null || effectiveTo.isAfter(now));
        }
    }
}
