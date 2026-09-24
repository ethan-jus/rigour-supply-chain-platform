package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.DhbBindingReviewCommand;
import com.rigour.hr.application.port.out.HrDhbBindingReviewStore;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class HrDhbBindingReviewServiceTest {
    private final HrDhbBindingReviewStore store = mock(HrDhbBindingReviewStore.class);
    private final HrDhbBindingReviewService service = new HrDhbBindingReviewService(store);
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private CallerIdentity caller(String scope, Set<String> permissions) {
        return new CallerIdentity(scope,user,tenant,"TENANT".equals(scope) ? user : null,null,UUID.randomUUID(),0,0,0,Set.of(),permissions);
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void onlyTenantUserWithUpdatePermissionCanConfirm() {
        var command = new DhbBindingReviewCommand(2,9L);
        TestAuthorizationContext.set(caller("TENANT",Set.of("hr:employee:read")));
        assertThatThrownBy(() -> service.confirm(1,command)).isInstanceOf(AuthorizationDeniedException.class);
        TestAuthorizationContext.set(caller("SERVICE",Set.of("hr:employee:update")));
        assertThatThrownBy(() -> service.confirm(1,command)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
        TestAuthorizationContext.set(caller("TENANT",Set.of("hr:employee:update")));
        service.confirm(1,command);
        verify(store).confirm(tenant.toString(),1,command,user.toString());
    }
}
