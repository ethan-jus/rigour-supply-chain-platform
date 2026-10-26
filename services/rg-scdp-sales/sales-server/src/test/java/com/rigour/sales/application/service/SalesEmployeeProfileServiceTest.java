package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource.Employee;
import com.rigour.sales.application.port.out.SalesEmployeeProfileRepository;
import com.rigour.sales.application.port.out.SalesEmployeeProfileRepository.Profile;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SalesEmployeeProfileServiceTest {
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final Employee employee = new Employee(42, "EMP-42", "张三", 1, 1, 1);
    private final SalesEmployeeIdentitySource source = mock(SalesEmployeeIdentitySource.class);
    private final SalesEmployeeProfileRepository repository = mock(SalesEmployeeProfileRepository.class);
    private final AuditSink audit = mock(AuditSink.class);
    private final Clock clock = mock(Clock.class);
    private final SalesEmployeeProfileService service =
            new SalesEmployeeProfileService(source, repository, audit, clock);
    private final Instant began = Instant.parse("2026-10-26T08:00:00.123456789Z");
    private final Instant completed = Instant.parse("2026-10-26T08:00:00.124Z");

    @BeforeEach
    void setup() throws Exception {
        var setter = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        setter.setAccessible(true);
        setter.invoke(null, new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(),
                1, 1, 1, Set.of(), Set.of("sales:context:read")));
        when(source.current(any())).thenReturn(employee);
        when(clock.instant()).thenReturn(began, completed);
    }

    @AfterEach
    void clear() throws Exception {
        var method = AuthorizationContext.class.getDeclaredMethod("clear");
        method.setAccessible(true);
        method.invoke(null);
    }

    @Test
    void registrationUsesDatabasePrecisionAndValidatesAfterConcurrentWait() {
        var id = UUID.randomUUID();
        when(repository.register(eq(tenant), eq(user), eq(employee), any()))
                .thenReturn(new Profile(id, employee.id(), employee.code(), employee.code(), null,
                        "ACTIVE", Instant.parse("2026-10-26T08:00:00.123457Z"), null));

        assertThat(service.register().salesProfileId()).isEqualTo(id);

        var storedTime = ArgumentCaptor.forClass(Instant.class);
        verify(repository).register(eq(tenant), eq(user), eq(employee), storedTime.capture());
        assertThat(storedTime.getValue()).isEqualTo(Instant.parse("2026-10-26T08:00:00.123456Z"));
        verify(audit).append(any());
    }

    @Test
    void futureEffectiveProfileRemainsRejectedAfterRegistration() {
        when(repository.register(eq(tenant), eq(user), eq(employee), any()))
                .thenReturn(new Profile(UUID.randomUUID(), employee.id(), employee.code(), employee.code(),
                        null, "ACTIVE", completed.plusSeconds(60), null));

        assertThatThrownBy(service::register).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_PROFILE_INACTIVE));
        verifyNoInteractions(audit);
    }
}
