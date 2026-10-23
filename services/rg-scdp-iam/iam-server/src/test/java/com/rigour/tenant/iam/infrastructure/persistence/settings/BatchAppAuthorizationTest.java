package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.application.service.settings.AppMemberModels.Assignment;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.Context;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;
import java.util.function.Supplier;

class BatchAppAuthorizationTest {
    final UUID app = UUID.randomUUID(), tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final UUID expenseNode = UUID.randomUUID(), dashboardNode = UUID.randomUUID();
    final Actor actor = new Actor("TENANT", user, tenant);
    final String expense = "hr:expense:read",
            dashboard = "analytics:cost-dashboard:read",
            denied = "hr:payroll:read";
    JdbcTemplate jdbc;
    JdbcAppSettingsStore settings;
    JdbcAppRoleStore roles;
    JdbcAppMemberStore members;
    JdbcAppAuthorizationStore store;
    Map<Actor, Context> contexts = new HashMap<>();
    Role self, department, unassigned;

    @BeforeEach
    void setup() {
        jdbc =
                spy(
                        new JdbcTemplate(
                                new DriverManagerDataSource(
                                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                                        "sa",
                                        "")));
        jdbc.execute(
                "CREATE TABLE iam_app_employee_binding(tenant_id BINARY(16),application_id"
                    + " BINARY(16),user_id BINARY(16),employee_code VARCHAR(50))");
        jdbc.execute(
                "CREATE TABLE iam_app_member(tenant_id BINARY(16),application_id BINARY(16),user_id"
                    + " BINARY(16),security_version BIGINT)");
        settings = mock(JdbcAppSettingsStore.class);
        roles = mock(JdbcAppRoleStore.class);
        members = mock(JdbcAppMemberStore.class);
        when(settings.authorizationRead(any()))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        when(settings.count(anyString(), any(Object[].class))).thenReturn(1);
        when(settings.applicationId()).thenReturn(app);
        when(settings.initialized(any())).thenReturn(true);
        when(settings.eligible(any())).thenReturn(true);
        when(settings.context(any())).thenAnswer(i -> contexts.get(i.getArgument(0)));
        when(settings.enabledActionNodes(any(), eq(expense))).thenReturn(Set.of(expenseNode));
        when(settings.enabledActionNodes(any(), eq(dashboard))).thenReturn(Set.of(dashboardNode));
        self = role("SELF", Set.of(expenseNode));
        department = role("DEPARTMENT", Set.of(dashboardNode));
        unassigned = role("ALL", Set.of(expenseNode, dashboardNode));
        addActor(actor, "E1", 42L);
        when(roles.all(actor)).thenReturn(List.of(self, department, unassigned));
        when(members.assignments(actor, user))
                .thenReturn(
                        List.of(
                                new Assignment(self.id(), Map.of()),
                                new Assignment(department.id(), Map.of())));
        store =
                new JdbcAppAuthorizationStore(
                        jdbc, settings, roles, members, mock(AppEmployeeClient.class));
        clearInvocations(jdbc);
    }

    Role role(String scope, Set<UUID> nodes) {
        return new Role(
                UUID.randomUUID(),
                scope,
                scope,
                null,
                "ACTIVE",
                false,
                1,
                1,
                nodes,
                List.of(),
                new RoleDataScope(scope, List.of()));
    }

    void addActor(Actor a, String code, long department) {
        jdbc.update(
                "INSERT INTO iam_app_employee_binding VALUES(?,?,?,?)",
                bin(a.tenantId()),
                bin(app),
                bin(a.principalId()),
                code);
        jdbc.update(
                "INSERT INTO iam_app_member VALUES(?,?,?,7)",
                bin(a.tenantId()),
                bin(app),
                bin(a.principalId()));
        contexts.put(a, new Context(true, false, "ACTIVE", 11, Set.of(expense, dashboard)));
        when(settings.authorizationEmployee(a, code))
                .thenReturn(
                        new AppEmployeeClient.Employee(
                                1,
                                code,
                                code,
                                "ACTIVE",
                                department,
                                "城市",
                                "STAFF",
                                "员工",
                                List.of(1L, department),
                                9,
                                5,
                                1,
                                true,
                                null));
    }

    CallerIdentity caller(Actor a) {
        return new CallerIdentity(
                "TENANT",
                a.principalId(),
                a.tenantId(),
                a.principalId(),
                null,
                UUID.randomUUID(),
                1,
                2,
                3,
                Set.of(),
                Set.of());
    }

    @Test
    void batchMatchesEverySingleActionAndLoadsCommonFactsOnce() {
        var c = caller(actor);
        var actions = List.of(expense, dashboard, denied);
        var expected = actions.stream().map(action -> store.authorization(c, action)).toList();
        clearInvocations(jdbc, settings, roles, members);
        var batch = store.authorizations(c, actions);
        assertThat(batch).isEqualTo(expected);
        verify(settings, times(1)).context(actor);
        verify(settings, times(1)).authorizationEmployee(actor, "E1");
        verify(settings, times(1)).count(anyString(), any(Object[].class));
        verify(roles, times(1)).all(actor);
        verify(members, times(1)).assignments(actor, user);
        assertThat(batch.get(0).clauses())
                .singleElement()
                .satisfies(
                        clause -> {
                            assertThat(clause.roleId()).isEqualTo(self.id());
                            assertThat(clause.scopeMode()).isEqualTo("SELF");
                        });
        assertThat(batch.get(1).clauses())
                .singleElement()
                .satisfies(
                        clause ->
                                assertThat(clause.departments().references())
                                        .containsExactly("42"));
        assertThat(batch.get(2).functionAllowed()).isFalse();
        assertThat(batch.get(2).clauses()).isEmpty();
    }

    @Test
    void anotherTenantOrUserNeverBorrowsAssignedRolesOrEmployee() {
        var other = new Actor("TENANT", UUID.randomUUID(), UUID.randomUUID());
        addActor(other, "E2", 99L);
        var only = role("DEPARTMENT", Set.of(expenseNode));
        when(members.assignments(other, other.principalId()))
                .thenReturn(List.of(new Assignment(only.id(), Map.of())));
        when(roles.all(other)).thenReturn(List.of(only, self, department));
        var first = store.authorizations(caller(actor), List.of(expense)).getFirst();
        var second = store.authorizations(caller(other), List.of(expense)).getFirst();
        assertThat(first.tenantId()).isEqualTo(tenant);
        assertThat(first.userId()).isEqualTo(user);
        assertThat(second.tenantId()).isEqualTo(other.tenantId());
        assertThat(second.userId()).isEqualTo(other.principalId());
        assertThat(second.employeeCode()).isEqualTo("E2");
        assertThat(second.clauses())
                .singleElement()
                .satisfies(
                        clause -> {
                            assertThat(clause.roleId()).isEqualTo(only.id());
                            assertThat(clause.departments().references()).containsExactly("99");
                        });
    }

    @Test
    void nextRequestSeesPermissionRevocationAndSessionRevocation() {
        var c = caller(actor);
        assertThat(store.authorizations(c, List.of(expense)).getFirst().functionAllowed()).isTrue();
        contexts.put(actor, new Context(true, false, "ACTIVE", 12, Set.of(dashboard)));
        var revoked = store.authorizations(c, List.of(expense)).getFirst();
        assertThat(revoked.functionAllowed()).isFalse();
        assertThat(revoked.applicationVersion()).isEqualTo(12);
        assertThat(revoked.clauses()).isEmpty();
        when(settings.count(anyString(), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> store.authorizations(c, List.of(expense)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void malformedOrOversizedRequestsFailBeforeReadingIdentity() {
        assertThatThrownBy(() -> store.authorizations(caller(actor), Collections.emptyList()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () -> store.authorizations(caller(actor), Collections.nCopies(33, expense)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.authorizations(caller(actor), List.of("invalid")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(roles, members);
        assertThat(store.authorizations(caller(actor), List.of(expense, expense))).hasSize(1);
    }

    @Test
    void missingSignedCallerNeverReadsPolicies() {
        assertThatThrownBy(() -> store.authorizations(null, List.of(expense)))
                .isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).context(any());
        verifyNoInteractions(roles, members);
    }
}
