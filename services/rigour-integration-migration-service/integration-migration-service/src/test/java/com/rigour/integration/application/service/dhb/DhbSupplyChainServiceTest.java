package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbSupplyPageQueryCommand;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.lang.reflect.Method;
import java.time.Instant;
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

/** 订货宝供应商归一化契约回归测试。 */
@ExtendWith(MockitoExtension.class)
class DhbSupplyChainServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d8e");
    private static final UUID USER_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d8f");
    private static final UUID SESSION_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d90");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d91");

    @Mock private DhbClient client;
    @Mock private DhbIntegrationStore store;
    private DhbSupplyChainService service;

    @BeforeEach
    void setUp() {
        service = new DhbSupplyChainService(client, store);
        invokeContext("set", new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                SESSION_ID, 0, 0, 0, Set.of(), Set.of("integration:dhb:read")));
    }

    @AfterEach
    void tearDown() {
        invokeContext("clear", null);
    }

    @Test
    void keepsFullSupplierBankAccountInNormalizedIntegrationView() {
        String bankAccount = "6222000012345678";
        when(store.connector(TENANT_ID, CONNECTOR_ID)).thenReturn(new ConnectorView(
                CONNECTOR_ID, TENANT_ID, "DHB", "订货宝", "https://dhb.test", "secret-ref", "ACTIVE", 1));
        DhbClient.Supplier source = new DhbClient.Supplier("SUP-1", "SUP-GUID-1", "S-001", "供应商一",
                "上海", "地址", "联系人", "13800000000", "021-12345678", "supplier@test.com",
                "供应商一", "测试银行", bankAccount, "供应商一", "91310000", "备注",
                Instant.parse("2026-08-12T02:00:00Z"), Map.of("bank_account", bankAccount));
        when(client.getSuppliers(any(), any())).thenReturn(new DhbClient.Page<>(
                new DhbClient.PageRequest(0, 100), 1, List.of(source)));

        var result = service.suppliers(CONNECTOR_ID, new DhbSupplyPageQueryCommand(0, 100));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.address()).isEqualTo("地址");
            assertThat(item.mobile()).isEqualTo("13800000000");
            assertThat(item.phone()).isEqualTo("021-12345678");
            assertThat(item.email()).isEqualTo("supplier@test.com");
            assertThat(item.bankAccount()).isEqualTo(bankAccount);
            assertThat(item.taxpayerNumber()).isEqualTo("91310000");
        });
        verify(store).persistRawLanding(TENANT_ID, CONNECTOR_ID, "SUPPLIER", "SUP-1",
                source.sourceUpdatedAt(), source.attributes());
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
