package com.rigour.order.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.port.out.DhbSyncTargetDiscoveryClient;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DhbOrderManualSyncServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb000-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("019fb000-0000-7000-8000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("019fb000-0000-7000-8000-000000000011");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb000-0000-7000-8000-000000000010");
    private static final UUID OTHER_CONNECTOR_ID = UUID.fromString("019fb000-0000-7000-8000-000000000012");

    @AfterEach
    void clearCaller() throws Exception {
        invokeContextMethod("clear");
    }

    @Test
    void runsOnlyExplicitConnectorValidatedByIntegrationDiscovery() throws Exception {
        DhbOrderSyncService syncService = mock(DhbOrderSyncService.class);
        DhbSyncTargetDiscoveryClient discovery = mock(DhbSyncTargetDiscoveryClient.class);
        when(discovery.discover(any())).thenReturn(List.of(
                new SyncTargetView(TASK_ID, TENANT_ID, CONNECTOR_ID)));
        DhbOrderSyncResult result = new DhbOrderSyncResult(UUID.randomUUID(), "ORDER_DOMAIN", "SUCCEEDED",
                3, 0, 1, 1, 0, 0, 0, Set.of("ORDER"));
        when(syncService.run(any(), any())).thenReturn(result);
        DhbOrderManualSyncService service = new DhbOrderManualSyncService(syncService, discovery);
        setCaller();

        assertThat(service.run(CONNECTOR_ID, new DhbOrderSyncCommand(true, 1))).isEqualTo(result);
        verify(syncService).run(CONNECTOR_ID, new DhbOrderSyncCommand(true, 1));

        assertThatThrownBy(() -> service.run(OTHER_CONNECTOR_ID, new DhbOrderSyncCommand(true, 1)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void resolvesCurrentTenantConnectorWhenPortalDoesNotProvideConnectorId() throws Exception {
        DhbOrderSyncService syncService = mock(DhbOrderSyncService.class);
        DhbSyncTargetDiscoveryClient discovery = mock(DhbSyncTargetDiscoveryClient.class);
        when(discovery.discover(any())).thenReturn(List.of(
                new SyncTargetView(TASK_ID, TENANT_ID, CONNECTOR_ID)));
        DhbOrderSyncResult result = new DhbOrderSyncResult(UUID.randomUUID(), "ORDER_DOMAIN", "SUCCEEDED",
                1, 1, 1, 0, 0, 0, 0, Set.of("ORDER"));
        when(syncService.run(any(), any())).thenReturn(result);
        DhbOrderManualSyncService service = new DhbOrderManualSyncService(syncService, discovery);
        setCaller();

        assertThat(service.run(new DhbOrderSyncCommand(true, 1))).isEqualTo(result);
        verify(syncService).run(CONNECTOR_ID, new DhbOrderSyncCommand(true, 1));

        ArgumentCaptor<CallerIdentity> serviceCaller = ArgumentCaptor.forClass(CallerIdentity.class);
        verify(discovery).discover(serviceCaller.capture());
        assertThat(serviceCaller.getValue().principalScope()).isEqualTo("SERVICE");
        assertThat(serviceCaller.getValue().tenantId()).isNull();
        assertThat(serviceCaller.getValue().userId()).isNull();
        assertThat(serviceCaller.getValue().permissions()).contains("integration:dhb:sync-discovery");
    }

    @Test
    void rejectsManualTargetDiscoveryWithoutAuthenticatedTenantCaller() {
        DhbOrderManualSyncService service = new DhbOrderManualSyncService(
                mock(DhbOrderSyncService.class), mock(DhbSyncTargetDiscoveryClient.class));

        assertThatThrownBy(() -> service.run(new DhbOrderSyncCommand(true, 1)))
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessageContaining("authenticated-caller");
    }

    private static void setCaller() throws Exception {
        invokeContextMethod("set", new Class<?>[]{CallerIdentity.class}, new Object[]{new CallerIdentity(
                "TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.fromString("019fb000-0000-7000-8000-000000000003"), 0, 0, 0,
                Set.of("ORDER_OPERATOR"), Set.of("integration:dhb:read", "integration:dhb:write"))});
    }

    private static void invokeContextMethod(String name) throws Exception {
        invokeContextMethod(name, new Class<?>[0], new Object[0]);
    }

    private static void invokeContextMethod(String name, Class<?>[] types, Object[] args) throws Exception {
        Method method = AuthorizationContext.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }
}
