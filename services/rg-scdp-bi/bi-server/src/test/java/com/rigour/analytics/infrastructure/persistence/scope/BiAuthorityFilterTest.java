package com.rigour.analytics.infrastructure.persistence.scope;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.TestRoleScope;
import com.rigour.shared.context.CallerIdentity;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.*;

class BiAuthorityFilterTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID();
    private final BiAuthorityProjector authority = mock(BiAuthorityProjector.class);
    private final BiPeopleProjector people = mock(BiPeopleProjector.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final BiAuthorityFilter filter = new BiAuthorityFilter(authority, people);

    private void role(String mode, Set<String> permissions) {
        TestRoleScope.set(
                new CallerIdentity(
                        "TENANT",
                        USER,
                        TENANT,
                        USER,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of("SYS_ADMIN"),
                        permissions),
                mode);
    }

    @AfterEach
    void clear() {
        TestRoleScope.clear();
    }

    @Test
    void allDataReadDoesNotDependOnSalesOrOwnershipProjection() throws Exception {
        role("ALL", Set.of("analytics:dashboard:read"));
        doThrow(new IllegalStateException("sales service unavailable"))
                .when(people)
                .refresh(TENANT);
        var request =
                new MockHttpServletRequest(
                        "GET", "/api/v1/analytics/supply-dashboard/effective-scope");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(authority, people);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT", "SELF"})
    void restrictedReadsStillDenyWhenOwnershipCannotBeVerified(String mode) throws Exception {
        role(mode, Set.of("analytics:dashboard:read"));
        doThrow(new IllegalStateException("ownership unavailable")).when(people).refreshOrganization(TENANT);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/analytics/supply-dashboard/overview"),
                response,
                chain);
        assertThat(response.getStatus()).isEqualTo(503);
        verify(authority).refresh(TENANT);
        verifyNoInteractions(chain);
    }

    @Test
    void departmentReadUsesCurrentOwnershipBeforeQuery() throws Exception {
        role("DEPARTMENT", Set.of("analytics:dashboard:read"));
        var request =
                new MockHttpServletRequest("GET", "/api/v1/analytics/supply-dashboard/overview");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        var order = inOrder(authority, people, chain);
        order.verify(authority).refresh(TENANT);
        order.verify(people).refreshOrganization(TENANT);
        order.verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT", "SELF"})
    void restrictedFinancialReadDoesNotRequireSalesVisits(String mode) throws Exception {
        role(mode, Set.of("analytics:dashboard:read"));
        doThrow(new IllegalStateException("sales unavailable")).when(people).refresh(TENANT);
        var request = new MockHttpServletRequest("GET", "/api/v1/analytics/supply-dashboard/effective-scope");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(authority).refresh(TENANT);
        verify(people).refreshOrganization(TENANT);
        verify(people, never()).refresh(TENANT);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"visits", "city-contacts"})
    void visitReadsStillRejectUnavailableSalesSource(String endpoint) throws Exception {
        role("DEPARTMENT", Set.of("analytics:dashboard:read"));
        doThrow(new IllegalStateException("sales unavailable")).when(people).refresh(TENANT);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/analytics/supply/dashboard/" + endpoint), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(chain);
    }

    @Test
    void allReadScopeCannotSkipOwnershipForWritesWithDifferentActionScope() throws Exception {
        role("ALL", Set.of("analytics:dashboard:read"));
        doThrow(new IllegalStateException("sales unavailable")).when(people).refresh(TENANT);
        var request =
                new MockHttpServletRequest("POST", "/api/v1/analytics/operating-workspace/actions");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(authority).refresh(TENANT);
        verify(people).refreshOrganization(TENANT);
        verify(chain).doFilter(request, response);
    }

    @Test
    void allDataWithoutMenuPermissionDoesNotPass() throws Exception {
        role("ALL", Set.of());
        var response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest(
                        "GET", "/api/v1/analytics/supply-dashboard/effective-scope"),
                response,
                chain);
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(authority, people, chain);
    }
}
