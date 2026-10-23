package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;

class AuthorizationReadScopeTest {
    @Test void nestedReadsReuseOnlySuccessfulResultsAndAlwaysClearAfterFailure() {
        var jdbc = mock(JdbcTemplate.class);
        var settings = new JdbcAppSettingsStore(jdbc, mock(PlatformTransactionManager.class), mock(AppEmployeeClient.class));
        UUID first = UUID.randomUUID(), next = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(byte[].class)))
                .thenReturn(JdbcAppSettingsStore.bin(first), JdbcAppSettingsStore.bin(next));
        assertThatThrownBy(() -> settings.authorizationRead(() -> {
            assertThat(settings.applicationId()).isEqualTo(first);
            assertThat(settings.authorizationRead(settings::applicationId)).isEqualTo(first);
            throw new IllegalStateException("failed authorization");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(settings.authorizationRead(settings::applicationId)).isEqualTo(next);
        verify(jdbc, times(2)).queryForObject(anyString(), eq(byte[].class));
    }

    @Test void employeeFactsAreReusedOnlyWithinTheSameActorAndCall() {
        var employees = mock(AppEmployeeClient.class);
        var settings = new JdbcAppSettingsStore(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class), employees);
        var actor = new com.rigour.tenant.iam.application.service.management.ManagementModels.Actor(
                "TENANT", UUID.randomUUID(), UUID.randomUUID());
        var other = new com.rigour.tenant.iam.application.service.management.ManagementModels.Actor(
                "TENANT", UUID.randomUUID(), UUID.randomUUID());
        var active = new AppEmployeeClient.Employee(1, "E1", "员工", "ACTIVE", 1L, "部门", "STAFF", "员工", java.util.List.of(1L), 1, 1, 1, true, null);
        var disabled = new AppEmployeeClient.Employee(1, "E1", "员工", "INACTIVE", 1L, "部门", "STAFF", "员工", java.util.List.of(1L), 2, 1, 2, false, "已离职");
        when(employees.employee(actor.tenantId(), "E1")).thenReturn(active, disabled);
        when(employees.employee(other.tenantId(), "E1")).thenReturn(active);
        settings.authorizationRead(() -> {
            assertThat(settings.authorizationEmployee(actor, "E1")).isSameAs(active);
            assertThat(settings.authorizationEmployee(actor, "E1")).isSameAs(active);
            assertThat(settings.authorizationEmployee(other, "E1")).isSameAs(active);
            return null;
        });
        assertThat(settings.authorizationRead(() -> settings.authorizationEmployee(actor, "E1"))).isSameAs(disabled);
        verify(employees, times(2)).employee(actor.tenantId(), "E1");
        verify(employees).employee(other.tenantId(), "E1");
    }
}
