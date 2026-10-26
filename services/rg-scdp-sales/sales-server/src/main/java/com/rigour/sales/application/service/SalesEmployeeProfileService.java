package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.SalesEmployeeProfileApi.ProfileView;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource.Employee;
import com.rigour.sales.application.port.out.SalesEmployeeProfileRepository;
import com.rigour.sales.application.port.out.SalesEmployeeProfileRepository.Profile;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesEmployeeProfileService {
    private final SalesEmployeeIdentitySource source;
    private final SalesEmployeeProfileRepository repository;
    private final AuditSink audit;
    private final Clock clock;

    public SalesEmployeeProfileService(SalesEmployeeIdentitySource source,
            SalesEmployeeProfileRepository repository, AuditSink audit, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public ProfileView profile() {
        var caller = requireCaller();
        var employee = source.current(caller);
        rejectLegacyBinding(caller);
        var profile = repository.findByUser(caller.tenantId(), caller.userId()).orElse(null);
        if (profile != null) validate(profile, employee, clock.instant());
        return view(employee, profile);
    }

    @Transactional
    public ProfileView register() {
        var caller = requireCaller();
        var employee = source.current(caller);
        rejectLegacyBinding(caller);
        Instant now = clock.instant();
        var profile = repository.register(caller.tenantId(), caller.userId(), employee, now);
        validate(profile, employee, now);
        audit.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), "SALES_EMPLOYEE_PROFILE_REGISTER", "SALES_PROFILE",
                profile.id().toString(), Map.of("hrEmployeeId", Long.toString(employee.id()),
                "employeeCode", employee.code(), "employeeRevision", Long.toString(employee.revision()),
                "organizationVersion", Long.toString(employee.organizationVersion()),
                "accessVersion", Long.toString(employee.accessVersion())),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        return view(employee, profile);
    }

    /** 每次正式业务读取/写入重新核验，HR 停用、IAM 撤权和员工改绑立即拒绝。 */
    public Optional<Profile> resolve(CallerIdentity caller, Instant now) {
        var profile = repository.findByUser(caller.tenantId(), caller.userId());
        if (profile.isPresent()) validate(profile.get(), source.current(caller), now);
        return profile;
    }

    private void rejectLegacyBinding(CallerIdentity caller) {
        if (repository.hasLegacyBinding(caller.tenantId(), caller.userId()))
            throw new BusinessException(ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
    }

    private static void validate(Profile profile, Employee employee, Instant now) {
        if (profile.employeeId() != employee.id() || !profile.employeeCode().equals(employee.code()))
            throw new BusinessException(ErrorCode.SALES_EMPLOYEE_BINDING_CONFLICT);
        if (!profile.activeAt(now)) throw new BusinessException(ErrorCode.SALES_PROFILE_INACTIVE);
    }

    private static CallerIdentity requireCaller() {
        var caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:context:read");
        return caller;
    }

    private static ProfileView view(Employee employee, Profile profile) {
        return new ProfileView(Long.toString(employee.id()), employee.code(), employee.name(),
                profile == null ? null : profile.id(), profile == null ? "UNREGISTERED" : "ACTIVE");
    }
}
