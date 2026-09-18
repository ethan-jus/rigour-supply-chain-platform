package com.rigour.merchant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.application.port.out.CustomerMemberResponsibilityStore;
import com.rigour.merchant.application.service.CustomerMemberResponsibilityService;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

class CustomerMemberResponsibilityServiceTest {
    private final CustomerMemberResponsibilityStore store =
            mock(CustomerMemberResponsibilityStore.class);
    private final CustomerMemberResponsibilityService service =
            new CustomerMemberResponsibilityService(store);

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    private CallerIdentity actor(String... permissions) {
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(permissions));
        TestAuthorizationContext.set(caller);
        return caller;
    }

    @Test
    void userAdministratorWithoutCrmReadCannotListCustomerResponsibilities() {
        actor("supply:user:read");
        assertThatThrownBy(
                        () ->
                                service.customers(
                                        UUID.randomUUID(), "OWNED", null, null, null, null, 1, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void crmReaderCanInspectActualOwnershipButCannotPrepareTransfers() {
        var caller = actor("supply:user:read", "crm:customer:read");
        var target = UUID.randomUUID();
        service.customers(target, "OWNED", null, null, null, null, 1, 20);
        verify(store)
                .customers(
                        caller.tenantId().toString(),
                        target,
                        "OWNED",
                        null,
                        null,
                        null,
                        null,
                        1,
                        20,
                        "crm:customer:read");
        assertThatThrownBy(
                        () ->
                                service.customers(
                                        target, "CANDIDATES", null, null, null, null, 1, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.preview(target, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.apply(target, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoMoreInteractions(store);
    }

    @Test
    void assignmentAdministratorUsesAssignmentScopeForCandidateAndOwnedLists() {
        var caller = actor("supply:user:read", "crm:customer:assign-owner");
        var target = UUID.randomUUID();
        service.customers(target, "OWNED", null, null, null, null, 1, 20);
        service.customers(target, "CANDIDATES", null, null, null, null, 1, 20);
        verify(store)
                .customers(
                        caller.tenantId().toString(),
                        target,
                        "OWNED",
                        null,
                        null,
                        null,
                        null,
                        1,
                        20,
                        "crm:customer:assign-owner");
        verify(store)
                .customers(
                        caller.tenantId().toString(),
                        target,
                        "CANDIDATES",
                        null,
                        null,
                        null,
                        null,
                        1,
                        20,
                        "crm:customer:assign-owner");
    }
}
