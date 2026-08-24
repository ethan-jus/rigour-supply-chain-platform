package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** getWaitShips 必须先落 Raw Landing 再向订单域返回的回归测试。 */
@ExtendWith(MockitoExtension.class)
class DhbIntegrationWaitShipsRawLandingTest {
    private static final UUID TENANT_ID = UUID.fromString("019fc98a-380a-77b5-b0b9-1db9dd868c11");
    private static final UUID USER_ID = UUID.fromString("019fc98a-380a-77b5-b0b9-1db9dd868c12");
    private static final UUID SESSION_ID = UUID.fromString("019fc98a-380a-77b5-b0b9-1db9dd868c13");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fc98a-380a-77b5-b0b9-1db9dd868c14");

    @Mock private DhbIntegrationStore store;
    @Mock private DhbClient client;
    @Mock private DhbOrderSyncService orderSyncService;
    @Mock private ProductMediaStorage productMediaStorage;
    @Mock private ProductImageObjectKeyFactory productImageObjectKeyFactory;
    @Mock private ProductMediaSyncStore productMediaSyncStore;

    private DhbIntegrationService service;

    @BeforeEach
    void setUp() {
        service = new DhbIntegrationService(store, client, orderSyncService, productMediaStorage,
                productImageObjectKeyFactory, productMediaSyncStore);
        invokeContext("set", new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                SESSION_ID, 0, 0, 0, Set.of(), Set.of("integration:dhb:read")));
        when(store.connector(TENANT_ID, CONNECTOR_ID)).thenReturn(new ConnectorView(
                CONNECTOR_ID, TENANT_ID, "DHB", "订货宝", "https://dhb.test", "env://DHB_TEST",
                "ACTIVE", 1));
    }

    @AfterEach
    void tearDown() {
        invokeContext("clear", null);
    }

    @Test
    void persistsCompleteSourceReceiptBeforeReturningView() {
        Map<String, Object> source = sourceReceipt();
        when(client.getWaitShips(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("DH-1001")))
                .thenReturn(new DhbClient.WaitShips("DH-1001", List.of(), List.of(), source));

        var result = service.waitShips(CONNECTOR_ID, "DH-1001");

        assertThat(result.orderNumber()).isEqualTo("DH-1001");
        assertThat(result.sourceFields()).isEqualTo(source);
        verify(store).persistRawLanding(TENANT_ID, CONNECTOR_ID, "WAIT_SHIPS", "DH-1001", null, source);
    }

    @Test
    void doesNotReturnSuccessWhenRawLandingFails() {
        Map<String, Object> source = sourceReceipt();
        when(client.getWaitShips(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("DH-1001")))
                .thenReturn(new DhbClient.WaitShips("DH-1001", List.of(), List.of(), source));
        doThrow(new IllegalStateException("Raw Landing 写入失败"))
                .when(store).persistRawLanding(TENANT_ID, CONNECTOR_ID, "WAIT_SHIPS",
                        "DH-1001", null, source);

        assertThatThrownBy(() -> service.waitShips(CONNECTOR_ID, "DH-1001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Raw Landing 写入失败");
    }

    private static Map<String, Object> sourceReceipt() {
        return Map.of(
                "orders_num", "DH-1001",
                "shipped", List.of(Map.of("ships_num", "S-1")),
                "wait_stock", List.of(Map.of("orders_list_id", "L-1")));
    }

    private static void invokeContext(String name, CallerIdentity caller) {
        try {
            Method method = caller == null
                    ? AuthorizationContext.class.getDeclaredMethod(name)
                    : AuthorizationContext.class.getDeclaredMethod(name, CallerIdentity.class);
            method.setAccessible(true);
            if (caller == null) method.invoke(null); else method.invoke(null, caller);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法设置测试调用上下文", error);
        }
    }
}
