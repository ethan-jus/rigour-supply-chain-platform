package com.rigour.integration.api.controller.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.BiScheduleDispatchApi.Claim;
import com.rigour.integration.application.service.dhb.BiSchedulePlanService;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

class BiScheduleDispatchControllerTest {
    private final BiSchedulePlanService service = mock(BiSchedulePlanService.class);
    private final BiScheduleDispatchController controller =
            new BiScheduleDispatchController(service);
    private final UUID tenant = UUID.randomUUID();

    private CallerIdentity actor(String scope, UUID id, UUID tenant) {
        return new CallerIdentity(
                scope,
                id,
                tenant,
                "TENANT".equals(scope) ? id : null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of(),
                Set.of("integration:schedule:execute-bi"));
    }

    @Test
    void rejectsUserAndUnrelatedServiceEvenWithDispatchPermission() {
        try (var context = mockStatic(AuthorizationContext.class)) {
            for (String scope : List.of("TENANT", "SERVICE")) {
                context.when(AuthorizationContext::requireCurrent)
                        .thenReturn(actor(scope, UUID.randomUUID(), tenant));
                assertThatThrownBy(controller::due)
                        .isInstanceOf(AuthorizationDeniedException.class);
            }
            verifyNoInteractions(service);
        }
    }

    @Test
    void usesSignedTenantAndRejectsTenantlessClaim() {
        var id =
                UUID.nameUUIDFromBytes(
                        "rigour-bi-schedule-worker".getBytes(StandardCharsets.UTF_8));
        var token = UUID.randomUUID();
        try (var context = mockStatic(AuthorizationContext.class)) {
            context.when(AuthorizationContext::requireCurrent)
                    .thenReturn(actor("SERVICE", id, tenant));
            controller.claim(new Claim(3, token));
            verify(service).claim(tenant, 3, token);
            context.verify(
                    () ->
                            AuthorizationContext.requirePermission(
                                    "integration:schedule:execute-bi"));
            context.when(AuthorizationContext::requireCurrent)
                    .thenReturn(actor("SERVICE", id, null));
            assertThatThrownBy(() -> controller.claim(new Claim(3, token)))
                    .isInstanceOf(AuthorizationDeniedException.class);
        }
    }
}
